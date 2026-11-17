package com.openscribe.android.colab

import android.util.Base64
import com.openscribe.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class RuntimeProxyInfo(
    val token: String,
    val tokenExpiresInSeconds: Int = 3600,
    val url: String,
)

@Serializable
data class ColabRuntimeSession(
    val endpoint: String,
    val accelerator: String = "T4",
    val proxyUrl: String,
    val proxyToken: String,
    val notebookHash: String,
    var kernelId: String? = null,
    var sessionId: String? = null,
    val isFreshlyAllocated: Boolean = false,
    var lastVerifiedAtMs: Long = 0L,
)

class ColabConnectionLostException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Headless Google Colab T4 GPU Client in pure Kotlin following googlecolab/google-colab-cli.
 * Uses HTTP/1.1 with generous REST timeouts (60s connect / 150s read / 150s write / 165s call)
 * matching Python requests so free-tier T4 VM spin-up (45-120s) never times out prematurely,
 * and a dedicated WebSocket client with readTimeout=0, callTimeout=0, and pingInterval=0
 * (relying on colab_worker.py's 20s @@OPENSCRIBE_HEARTBEAT@@).
 */
class ColabClient(
    private val authManager: ColabAuthManager,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(150, TimeUnit.SECONDS)
        .callTimeout(165, TimeUnit.SECONDS)
        .build(),
) {
    private class TransientColabAssignException(
        val statusCode: Int,
        message: String,
    ) : IOException(message)

    companion object {
        private const val TAG = "ColabClient"
        private const val COLAB_DOMAIN = "https://colab.research.google.com"
        private const val TUN_ENDPOINT = "/tun/m"
        private const val XSSI_PREFIX = ")]}'\n"
        private const val AGENT_HEADER = "X-Colab-Client-Agent"
        private const val AGENT_VALUE = "colab-cli"
        private const val XSRF_HEADER = "X-Goog-Colab-Token"
        private const val PROXY_TOKEN_HEADER = "X-Colab-Runtime-Proxy-Token"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    // Fast HTTP/1.1 control-plane client for /assignments and /api/kernels reachability checks
    private val controlHttpClient: OkHttpClient = httpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // Dedicated non-pooled HTTP/1.1 client for /tun/m/assign matching google-colab-cli timeouts (60s/150s/150s/165s)
    private val assignHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(150, TimeUnit.SECONDS)
        .callTimeout(165, TimeUnit.SECONDS)
        .build()

    // Dedicated WebSocket client: readTimeout=0, callTimeout=0, and pingInterval=0 so active Whisper runs never time out
    private val wsHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    // HTTP/1.1 streaming client for large remote file downloads from Colab runtime proxy
    private val mediaHttpClient: OkHttpClient = httpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun OkHttpClient.executeCancellable(request: Request): Response {
        return suspendCancellableCoroutine { cont ->
            val call = newCall(request)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    } else {
                        runCatching { response.close() }
                    }
                }
            })
        }
    }

    @Volatile
    var activeSession: ColabRuntimeSession? = null
        private set

    fun uuidToWebSafeBase64(uuid: UUID): String {
        val transformed = uuid.toString().replace("-", "_")
        val paddingCount = (44 - transformed.length).coerceAtLeast(0)
        return transformed + ".".repeat(paddingCount)
    }

    private fun stripXssi(body: String): String {
        return if (body.startsWith(XSSI_PREFIX)) body.removePrefix(XSSI_PREFIX) else body
    }

    suspend fun ensureWarmT4Runtime(
        onStatus: (String) -> Unit,
    ): ColabRuntimeSession = withContext(Dispatchers.IO) {
        activeSession?.let { existing ->
            val nowMs = System.currentTimeMillis()
            if (nowMs - existing.lastVerifiedAtMs < 5_000L || isProxyReachable(existing)) {
                existing.lastVerifiedAtMs = System.currentTimeMillis()
                onStatus("Reusing warm Colab T4 GPU runtime (${existing.endpoint.take(12)})...")
                return@withContext existing
            } else {
                activeSession = null
            }
        }

        val accessToken = authManager.getValidAccessToken()
        val existingList = listAssignments(accessToken)
        val staleEndpoints = HashSet<String>()
        val orderedCandidates = existingList.sortedByDescending {
            it.accelerator.equals("T4", ignoreCase = true)
        }

        for (candidate in orderedCandidates) {
            val session = ColabRuntimeSession(
                endpoint = candidate.endpoint,
                accelerator = candidate.accelerator,
                proxyUrl = candidate.proxyUrl.trimEnd('/'),
                proxyToken = candidate.proxyToken,
                notebookHash = uuidToWebSafeBase64(UUID.randomUUID()),
                isFreshlyAllocated = false,
            )
            var reachable = isProxyReachable(session)
            if (!reachable) {
                delay(600L)
                reachable = isProxyReachable(session)
            }
            if (reachable) {
                session.lastVerifiedAtMs = System.currentTimeMillis()
                activeSession = session
                onStatus("Connected to existing Colab ${session.accelerator} runtime.")
                return@withContext session
            } else {
                AppLogger.i(TAG, "Unassigning unreachable Colab runtime ${session.endpoint}")
                staleEndpoints.add(session.endpoint)
                runCatching { unassign(session.endpoint) }
            }
        }

        onStatus("Booting fresh Google Colab T4 GPU runtime...")
        AppLogger.i(TAG, "Allocating fresh Colab T4 GPU runtime")
        val session = allocateFreshT4(accessToken, staleEndpoints, onStatus)
        AppLogger.i(TAG, "T4 runtime assigned: ${session.endpoint} (fresh=${session.isFreshlyAllocated})")
        if (session.isFreshlyAllocated) {
            onStatus("T4 GPU allocated — waiting for Jupyter runtime...")
            waitForRuntimeReady(session)
        }
        session.lastVerifiedAtMs = System.currentTimeMillis()
        activeSession = session
        return@withContext session
    }

    private suspend fun waitForRuntimeReady(session: ColabRuntimeSession) {
        val deadlineMs = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadlineMs) {
            if (isProxyReachable(session)) return
            delay(350L)
        }
    }

    private suspend fun allocateFreshT4(
        accessToken: String,
        staleEndpoints: Set<String>,
        onStatus: (String) -> Unit,
    ): ColabRuntimeSession {
        // Generate nbh ONCE per allocation call so retries query/confirm the SAME VM instead of abandoning it
        val nbh = uuidToWebSafeBase64(UUID.randomUUID())
        val assignUrl = "$COLAB_DOMAIN$TUN_ENDPOINT/assign?nbh=${urlEncode(nbh)}&variant=GPU&accelerator=T4&authuser=0"
        var lastError: Throwable? = null

        for (attempt in 0..2) {
            try {
                if (attempt > 0) {
                    val currentAssignments = listAssignments(accessToken)
                    val adopted = currentAssignments.firstOrNull {
                        it.endpoint !in staleEndpoints && it.accelerator.equals("T4", ignoreCase = true)
                    } ?: currentAssignments.firstOrNull { it.endpoint !in staleEndpoints }
                    if (adopted != null) {
                        val adoptedSession = ColabRuntimeSession(
                            endpoint = adopted.endpoint,
                            accelerator = adopted.accelerator,
                            proxyUrl = adopted.proxyUrl.trimEnd('/'),
                            proxyToken = adopted.proxyToken,
                            notebookHash = nbh,
                            isFreshlyAllocated = true,
                        )
                        if (isProxyReachable(adoptedSession)) {
                            AppLogger.i(TAG, "Adopted T4 runtime ${adopted.endpoint} via /assignments on retry $attempt")
                            onStatus("Connected to newly provisioned Colab ${adoptedSession.accelerator} runtime.")
                            return adoptedSession
                        }
                    }
                }

                onStatus(
                    if (attempt == 0) {
                        "Requesting Colab T4 GPU slot..."
                    } else {
                        "Checking Colab T4 GPU allocation status (retry $attempt/2)..."
                    },
                )
                val getReq = Request.Builder()
                    .url(assignUrl)
                    .get()
                    .header("Connection", "close")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header(AGENT_HEADER, AGENT_VALUE)
                    .build()

                val getBody = assignHttpClient.executeCancellable(getReq).use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.code == 502 || resp.code == 503 || resp.code == 504) {
                        throw TransientColabAssignException(
                            resp.code,
                            "Colab assign GET gateway error (${resp.code}): ${raw.take(200)}",
                        )
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Colab assign GET failed (${resp.code}): $raw")
                    }
                    stripXssi(raw)
                }

                val getObj = json.parseToJsonElement(getBody).jsonObject
                if (getObj.containsKey("runtimeProxyInfo")) {
                    AppLogger.i(TAG, "Colab GET /assign returned runtimeProxyInfo immediately for nbh=${nbh.take(12)} (attempt=$attempt)")
                    return parseAssignedSession(getObj, nbh, isFresh = attempt > 0)
                }

                val xsrfToken = getObj["token"]?.jsonPrimitive?.content
                    ?: throw IllegalStateException("Missing XSRF token from Colab /assign response")

                onStatus("Confirming Colab T4 GPU allocation (free-tier VMs may take up to 2 min)...")
                val postReq = Request.Builder()
                    .url(assignUrl)
                    .post(ByteArray(0).toRequestBody(null))
                    .header("Connection", "close")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/json")
                    .header(AGENT_HEADER, AGENT_VALUE)
                    .header(XSRF_HEADER, xsrfToken)
                    .build()

                return coroutineScope {
                    val winner = CompletableDeferred<ColabRuntimeSession>()
                    val postJob = launch {
                        try {
                            val postBody = assignHttpClient.executeCancellable(postReq).use { resp ->
                                val raw = resp.body?.string().orEmpty()
                                if (resp.code == 412) {
                                    throw IllegalStateException("Too many active Colab sessions (412). Free a session in Colab and retry.")
                                }
                                if (resp.code == 502 || resp.code == 503 || resp.code == 504) {
                                    throw TransientColabAssignException(
                                        resp.code,
                                        "Colab T4 GPU allocation gateway error (${resp.code}): ${raw.take(200)}",
                                    )
                                }
                                if (!resp.isSuccessful) {
                                    throw IllegalStateException("Colab T4 GPU allocation failed (${resp.code}): $raw")
                                }
                                stripXssi(raw)
                            }
                            val parsed = parseAssignedSession(json.parseToJsonElement(postBody).jsonObject, nbh, isFresh = true)
                            winner.complete(parsed)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (t: Throwable) {
                            winner.completeExceptionally(t)
                        }
                    }

                    // If POST response stalls on Colab gateway/NAT, adopt the newly provisioned runtime as soon as /assignments exposes it
                    val watcherJob = launch {
                        delay(10_000L)
                        while (!winner.isCompleted) {
                            val current = listAssignments(accessToken)
                            val found = current.firstOrNull {
                                it.endpoint !in staleEndpoints && it.accelerator.equals("T4", ignoreCase = true)
                            } ?: current.firstOrNull { it.endpoint !in staleEndpoints }
                            if (found != null) {
                                val adopted = ColabRuntimeSession(
                                    endpoint = found.endpoint,
                                    accelerator = found.accelerator,
                                    proxyUrl = found.proxyUrl.trimEnd('/'),
                                    proxyToken = found.proxyToken,
                                    notebookHash = nbh,
                                    isFreshlyAllocated = true,
                                )
                                if (winner.complete(adopted)) {
                                    AppLogger.i(TAG, "Adopted T4 runtime ${found.endpoint} via /assignments watcher")
                                    return@launch
                                }
                            }
                            delay(5_000L)
                        }
                    }

                    try {
                        winner.await()
                    } finally {
                        watcherJob.cancel()
                        postJob.cancel()
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                val isRetryable = t is SocketTimeoutException ||
                    t is TransientColabAssignException ||
                    t is IOException
                AppLogger.w(TAG, "T4 allocation attempt ${attempt + 1} failed for nbh=${nbh.take(12)} (retryable=$isRetryable)", t)
                if (isRetryable && attempt < 2) {
                    val backoffSec = if (attempt == 0) 10L else 20L
                    onStatus("Colab T4 allocation delayed — waiting ${backoffSec}s to recover VM (${t.message ?: "timeout"})...")
                    delay(backoffSec * 1_000L)
                } else {
                    throw t
                }
            }
        }
        throw lastError ?: IllegalStateException("Colab T4 GPU allocation failed.")
    }

    private fun parseAssignedSession(
        obj: JsonObject,
        nbh: String,
        isFresh: Boolean,
    ): ColabRuntimeSession {
        val endpoint = obj["endpoint"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing endpoint in Colab assignment")
        val accelerator = obj["accelerator"]?.jsonPrimitive?.content ?: "T4"
        val proxyInfo = obj["runtimeProxyInfo"]?.jsonObject
            ?: throw IllegalStateException("Missing runtimeProxyInfo in Colab assignment")
        val proxyUrl = proxyInfo["url"]?.jsonPrimitive?.content?.trimEnd('/')
            ?: throw IllegalStateException("Missing proxy url in Colab assignment")
        val proxyToken = proxyInfo["token"]?.jsonPrimitive?.content
            ?: throw IllegalStateException("Missing proxy token in Colab assignment")
        return ColabRuntimeSession(
            endpoint = endpoint,
            accelerator = accelerator,
            proxyUrl = proxyUrl,
            proxyToken = proxyToken,
            notebookHash = nbh,
            isFreshlyAllocated = isFresh,
        )
    }

    private data class ListedAssignmentLite(
        val endpoint: String,
        val accelerator: String,
        val proxyUrl: String,
        val proxyToken: String,
    )

    private suspend fun listAssignments(accessToken: String): List<ListedAssignmentLite> {
        val req = Request.Builder()
            .url("$COLAB_DOMAIN$TUN_ENDPOINT/assignments?authuser=0")
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .build()

        return try {
            controlHttpClient.executeCancellable(req).use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = stripXssi(resp.body?.string().orEmpty())
                if (body.isBlank()) return@use emptyList()
                val arr = json.parseToJsonElement(body).jsonObject["assignments"]?.jsonArray
                    ?: return@use emptyList()
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val endpoint = o["endpoint"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val acc = o["accelerator"]?.jsonPrimitive?.content ?: "T4"
                    val proxy = o["runtimeProxyInfo"]?.jsonObject ?: return@mapNotNull null
                    val url = proxy["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val token = proxy["token"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    ListedAssignmentLite(endpoint, acc, url, token)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun isProxyReachable(session: ColabRuntimeSession): Boolean {
        val url = "${session.proxyUrl}/api/kernels?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val req = Request.Builder()
            .url(url)
            .get()
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()
        return try {
            controlHttpClient.executeCancellable(req).use { it.isSuccessful }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun unassignActiveRuntime() {
        val session = activeSession ?: return
        activeSession = null
        runCatching { unassign(session.endpoint) }
    }

    suspend fun unassign(endpoint: String) = withContext(Dispatchers.IO) {
        val accessToken = authManager.getValidAccessToken()
        val unassignUrl = "$COLAB_DOMAIN$TUN_ENDPOINT/unassign/$endpoint?authuser=0"
        val getReq = Request.Builder()
            .url(unassignUrl)
            .get()
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .build()
        val xsrf = controlHttpClient.executeCancellable(getReq).use { resp ->
            val body = stripXssi(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) return@withContext
            json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
        } ?: return@withContext

        val postReq = Request.Builder()
            .url(unassignUrl)
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(XSRF_HEADER, xsrf)
            .build()
        controlHttpClient.executeCancellable(postReq).close()
    }

    suspend fun ensureDirectory(
        session: ColabRuntimeSession,
        remoteDirPath: String,
    ) = withContext(Dispatchers.IO) {
        val cleanPath = remoteDirPath.trim('/').ifBlank { return@withContext }
        val url = "${session.proxyUrl}/api/contents/$cleanPath?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val payload = buildJsonObject {
            put("type", "directory")
        }.toString()
        val req = Request.Builder()
            .url(url)
            .put(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()
        controlHttpClient.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful && resp.code != 409) {
                AppLogger.w(TAG, "ensureDirectory($remoteDirPath) returned ${resp.code}")
            }
        }
    }

    suspend fun uploadBytes(
        session: ColabRuntimeSession,
        bytes: ByteArray,
        remotePath: String,
    ) = withContext(Dispatchers.IO) {
        val cleanPath = remotePath.trimStart('/')
        val url = "${session.proxyUrl}/api/contents/$cleanPath?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val filename = cleanPath.substringAfterLast('/')

        val chunkSize = 4 * 1024 * 1024
        if (bytes.size <= chunkSize) {
            putContentChunk(url, session.proxyToken, remotePath, filename, bytes, chunk = 1)
        } else {
            var offset = 0
            var chunkNum = 1
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                val slice = bytes.copyOfRange(offset, end)
                val isLast = end >= bytes.size
                putContentChunk(
                    url = url,
                    proxyToken = session.proxyToken,
                    remotePath = remotePath,
                    filename = filename,
                    slice = slice,
                    chunk = if (isLast) -1 else chunkNum,
                )
                offset = end
                chunkNum++
            }
        }
    }

    suspend fun uploadFile(
        session: ColabRuntimeSession,
        localFile: File,
        remotePath: String,
    ) = uploadBytes(session, localFile.readBytes(), remotePath)

    suspend fun uploadText(
        session: ColabRuntimeSession,
        text: String,
        remotePath: String,
    ) = uploadBytes(session, text.toByteArray(Charsets.UTF_8), remotePath)

    private suspend fun putContentChunk(
        url: String,
        proxyToken: String,
        remotePath: String,
        filename: String,
        slice: ByteArray,
        chunk: Int,
    ) {
        val b64 = Base64.encodeToString(slice, Base64.NO_WRAP)
        val payload = buildJsonObject {
            put("name", filename)
            put("path", remotePath)
            put("type", "file")
            put("format", "base64")
            put("content", b64)
            put("chunk", chunk)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .put(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, proxyToken)
            .build()

        httpClient.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Colab file upload failed ($remotePath, ${resp.code}): ${resp.body?.string()}")
            }
        }
    }

    suspend fun downloadRemoteFile(
        session: ColabRuntimeSession,
        remotePath: String,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val cleanPath = remotePath.trimStart('/')
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) destFile.delete()

        val filesUrl = "${session.proxyUrl}/files/$cleanPath?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val filesReq = Request.Builder()
            .url(filesUrl)
            .get()
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()

        val directOk = runCatching {
            mediaHttpClient.executeCancellable(filesReq).use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastReportedPct = -1
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) {
                                output.write(buf, 0, n)
                                downloaded += n
                                if (totalBytes > 0L && onProgress != null) {
                                    val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                }
                destFile.exists() && destFile.length() > 0L
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            AppLogger.w(TAG, "Direct /files download failed for $remotePath (${err.message}); trying /api/contents...")
        }.getOrDefault(false)

        if (directOk) {
            onProgress?.invoke(100)
            return@withContext destFile
        }

        if (destFile.exists()) destFile.delete()
        val contentsUrl = "${session.proxyUrl}/api/contents/$cleanPath?type=file&format=base64&content=1&authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val contentsReq = Request.Builder()
            .url(contentsUrl)
            .get()
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()

        mediaHttpClient.executeCancellable(contentsReq).use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Colab file download failed ($remotePath, ${resp.code}): ${raw.take(400)}")
            }
            val b64 = json.parseToJsonElement(raw).jsonObject["content"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Colab file download missing content for $remotePath")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            destFile.writeBytes(bytes)
        }
        if (!destFile.exists() || destFile.length() == 0L) {
            throw IllegalStateException("Downloaded Colab file was empty: $remotePath")
        }
        onProgress?.invoke(100)
        destFile
    }

    suspend fun deleteRemoteFile(
        session: ColabRuntimeSession,
        remotePath: String,
    ) = withContext(Dispatchers.IO) {
        val cleanPath = remotePath.trimStart('/')
        val url = "${session.proxyUrl}/api/contents/$cleanPath?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val req = Request.Builder()
            .url(url)
            .delete()
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()
        runCatching { controlHttpClient.executeCancellable(req).close() }
    }

    suspend fun ensureKernel(session: ColabRuntimeSession): String = withContext(Dispatchers.IO) {
        session.kernelId?.let { existingId ->
            val checkUrl = "${session.proxyUrl}/api/kernels/$existingId?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
            val checkReq = Request.Builder()
                .url(checkUrl)
                .get()
                .header(AGENT_HEADER, AGENT_VALUE)
                .header(PROXY_TOKEN_HEADER, session.proxyToken)
                .build()
            val alive = try {
                controlHttpClient.executeCancellable(checkReq).use { it.isSuccessful }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                false
            }
            if (alive) return@withContext existingId
        }

        val createUrl = "${session.proxyUrl}/api/kernels?authuser=0&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"
        val req = Request.Builder()
            .url(createUrl)
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()

        val kernelId = controlHttpClient.executeCancellable(req).use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ColabConnectionLostException("Failed to start Jupyter kernel (${resp.code}): $raw")
            }
            json.parseToJsonElement(raw).jsonObject["id"]?.jsonPrimitive?.content
                ?: throw ColabConnectionLostException("Missing kernel id in response: $raw")
        }
        session.kernelId = kernelId
        if (session.sessionId == null) {
            session.sessionId = UUID.randomUUID().toString()
        }
        kernelId
    }

    suspend fun executeWorkerWithAutoRecover(
        workerScript: String,
        envVars: Map<String, String>,
        onStatus: (String) -> Unit,
        onBeforeExecute: (suspend (ColabRuntimeSession) -> Unit)? = null,
        onOutputLine: (String) -> Unit,
    ) {
        val maxRetries = 2
        var lastError: Throwable? = null

        for (attempt in 0..maxRetries) {
            val session = ensureWarmT4Runtime(onStatus)
            try {
                onBeforeExecute?.invoke(session)
                executeOnRuntime(session, workerScript, envVars, onOutputLine)
                return
            } catch (e: ColabConnectionLostException) {
                lastError = e
                AppLogger.w(TAG, "Colab connection lost on attempt ${attempt + 1}", e)
                if (attempt < maxRetries) {
                    onStatus("Colab runtime connection lost (${e.message}) — allocating fresh T4 (retry ${attempt + 1}/$maxRetries)...")
                    runCatching { unassignActiveRuntime() }
                    delay(1_500L)
                }
            }
        }
        throw lastError ?: ColabConnectionLostException("Failed to execute worker on Colab T4 runtime.")
    }

    private suspend fun executeOnRuntime(
        session: ColabRuntimeSession,
        workerScript: String,
        envVars: Map<String, String>,
        onOutputLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val kernelId = ensureKernel(session)
        val jupyterSessionId = session.sessionId ?: UUID.randomUUID().toString().also { session.sessionId = it }

        val wsSchemeUrl = session.proxyUrl
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val wsUrl = "$wsSchemeUrl/api/kernels/$kernelId/channels" +
            "?session_id=${urlEncode(jupyterSessionId)}" +
            "&colab-runtime-proxy-token=${urlEncode(session.proxyToken)}"

        val prelude = buildPythonPrelude(envVars)
        val fullCode = prelude + "\n" + workerScript
        val msgId = UUID.randomUUID().toString().replace("-", "")

        val executeMsg = buildJsonObject {
            put("header", buildJsonObject {
                put("msg_id", msgId)
                put("session", jupyterSessionId)
                put("username", "openscribe")
                put("date", Instant.now().toString())
                put("msg_type", "execute_request")
                put("version", "5.3")
            })
            put("parent_header", buildJsonObject {})
            put("metadata", buildJsonObject {})
            put("content", buildJsonObject {
                put("code", fullCode)
                put("silent", false)
                put("store_history", false)
                put("user_expressions", buildJsonObject {})
                put("allow_stdin", false)
                put("stop_on_error", true)
            })
            put("buffers", buildJsonArray {})
            put("channel", "shell")
        }.toString()

        val done = CompletableDeferred<Unit>()
        val lineBuffer = StringBuilder()

        fun flushBuffer(forceRemaining: Boolean = false) {
            while (true) {
                val nl = lineBuffer.indexOf("\n")
                if (nl < 0) break
                val line = lineBuffer.substring(0, nl)
                lineBuffer.delete(0, nl + 1)
                onOutputLine(line)
            }
            if (forceRemaining && lineBuffer.isNotEmpty()) {
                onOutputLine(lineBuffer.toString())
                lineBuffer.clear()
            }
        }

        val wsReq = Request.Builder()
            .url(wsUrl)
            .header(AGENT_HEADER, AGENT_VALUE)
            .header(PROXY_TOKEN_HEADER, session.proxyToken)
            .build()

        val ws = wsHttpClient.newWebSocket(
            wsReq,
            object : WebSocketListener() {
                private var sawIdle = false
                private var executionError: String? = null

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    AppLogger.i(TAG, "Jupyter WebSocket opened (kernel=$kernelId)")
                    webSocket.send(executeMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching {
                        val root = json.parseToJsonElement(text).jsonObject
                        val parentMsgId = root["parent_header"]?.jsonObject
                            ?.get("msg_id")?.jsonPrimitive?.content
                        val headerType = root["header"]?.jsonObject
                            ?.get("msg_type")?.jsonPrimitive?.content
                        val content = root["content"]?.jsonObject

                        if (headerType == "status") {
                            val state = content?.get("execution_state")?.jsonPrimitive?.content
                            if ((state == "dead" || state == "restarting") && !done.isCompleted) {
                                AppLogger.w(TAG, "Jupyter kernel entered $state state")
                                done.completeExceptionally(
                                    ColabConnectionLostException("Connection was lost: kernel entered $state state"),
                                )
                                return
                            }
                            if (parentMsgId == msgId && state == "idle") {
                                sawIdle = true
                                flushBuffer(forceRemaining = true)
                                val err = executionError
                                if (!done.isCompleted) {
                                    if (err != null) done.completeExceptionally(RuntimeException(err))
                                    else done.complete(Unit)
                                }
                            }
                        }

                        if (parentMsgId != msgId || content == null) return@runCatching

                        when (headerType) {
                            "stream" -> {
                                val chunk = content["text"]?.jsonPrimitive?.content.orEmpty()
                                lineBuffer.append(chunk)
                                flushBuffer(forceRemaining = false)
                            }
                            "error" -> {
                                val ename = content["ename"]?.jsonPrimitive?.content ?: "Error"
                                val evalue = content["evalue"]?.jsonPrimitive?.content ?: "Worker failed"
                                executionError = "$ename: $evalue"
                                AppLogger.e(TAG, "Jupyter execution error: $executionError")
                            }
                            "execute_reply" -> {
                                val status = content["status"]?.jsonPrimitive?.content
                                if (status == "error" && executionError == null) {
                                    val evalue = content["evalue"]?.jsonPrimitive?.content ?: "Execution error"
                                    executionError = evalue
                                    AppLogger.e(TAG, "Jupyter execute_reply error: $executionError")
                                }
                                if (sawIdle && !done.isCompleted) {
                                    flushBuffer(forceRemaining = true)
                                    val err = executionError
                                    if (err != null) done.completeExceptionally(RuntimeException(err))
                                    else done.complete(Unit)
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (done.isCompleted) {
                        AppLogger.d(TAG, "Jupyter WebSocket closed after completion (${t::class.simpleName})")
                        return
                    }
                    AppLogger.e(TAG, "Jupyter WebSocket failure: ${response?.code} ${response?.message}", t)
                    done.completeExceptionally(
                        ColabConnectionLostException(
                            "Connection was lost: ${t.message ?: response?.message ?: "WebSocket failure"}",
                            t,
                        ),
                    )
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    AppLogger.i(TAG, "Jupyter WebSocket closed ($code $reason, sawIdle=$sawIdle)")
                    if (!done.isCompleted) {
                        if (sawIdle) {
                            flushBuffer(forceRemaining = true)
                            done.complete(Unit)
                        } else {
                            done.completeExceptionally(
                                ColabConnectionLostException("Connection was lost (WebSocket closed: $code $reason)"),
                            )
                        }
                    }
                }
            },
        )

        try {
            done.await()
        } finally {
            ws.close(1000, "done")
        }
    }

    suspend fun executeViaCustomTunnel(
        tunnelBaseUrl: String,
        tunnelToken: String,
        workerScript: String,
        envVars: Map<String, String>,
        onOutputLine: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val base = tunnelBaseUrl.trimEnd('/')
        val payload = buildJsonObject {
            put("script", workerScript)
            put("env", buildJsonObject {
                envVars.forEach { (k, v) -> put(k, v) }
            })
        }.toString()

        val reqBuilder = Request.Builder()
            .url("$base/run")
            .post(payload.toRequestBody(JSON_MEDIA))
            .header("Accept", "text/event-stream")
        if (tunnelToken.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $tunnelToken")
        }

        wsHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Custom tunnel /run failed (${resp.code}): ${resp.body?.string()}")
            }
            val source = resp.body?.source() ?: return@use
            while (!source.exhausted()) {
                val rawLine = source.readUtf8Line() ?: break
                val line = if (rawLine.startsWith("data:")) rawLine.removePrefix("data:").trimStart() else rawLine
                if (line.isNotEmpty()) onOutputLine(line)
            }
        }
    }

    suspend fun sendSeekSignal(
        targetSec: Double,
        useCustomTunnel: Boolean,
        customTunnelUrl: String,
        customTunnelToken: String,
    ) {
        val token = UUID.randomUUID().toString().take(8)
        val payload = "$token:${"%.2f".format(java.util.Locale.US, targetSec)}"
        if (useCustomTunnel && customTunnelUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val body = buildJsonObject { put("seek", payload) }.toString()
                val req = Request.Builder()
                    .url("${customTunnelUrl.trimEnd('/')}/seek")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .apply { if (customTunnelToken.isNotBlank()) header("Authorization", "Bearer $customTunnelToken") }
                    .build()
                runCatching { httpClient.newCall(req).execute().close() }
            }
            return
        }
        val session = activeSession ?: return
        runCatching {
            uploadBytes(session, payload.toByteArray(Charsets.UTF_8), "/content/openscribe_seek.txt")
        }
    }

    suspend fun sendAbortSignal(
        useCustomTunnel: Boolean,
        customTunnelUrl: String,
        customTunnelToken: String,
    ) {
        if (useCustomTunnel && customTunnelUrl.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("${customTunnelUrl.trimEnd('/')}/abort")
                    .post("{}".toRequestBody(JSON_MEDIA))
                    .apply { if (customTunnelToken.isNotBlank()) header("Authorization", "Bearer $customTunnelToken") }
                    .build()
                runCatching { httpClient.newCall(req).execute().close() }
            }
            return
        }
        val session = activeSession ?: return
        runCatching {
            uploadBytes(session, "1\n".toByteArray(Charsets.UTF_8), "/content/openscribe_abort")
        }
    }

    private fun buildPythonPrelude(envVars: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("import os, sys, warnings\n")
        sb.append("sys.argv = ['colab_worker.py']\n")
        sb.append("__name__ = '__main__'\n")
        sb.append("warnings.filterwarnings('ignore', message='To exit: use')\n")
        sb.append("for _os_k in [k for k in list(os.environ.keys()) if k.startswith('OS_')]:\n")
        sb.append("    os.environ.pop(_os_k, None)\n")
        envVars.forEach { (k, v) ->
            val escapedKey = k.replace("\\", "\\\\").replace("'", "\\'")
            val escapedVal = v.replace("\\", "\\\\").replace("'", "\\'")
            sb.append("os.environ['$escapedKey'] = '$escapedVal'\n")
        }
        return sb.toString()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
