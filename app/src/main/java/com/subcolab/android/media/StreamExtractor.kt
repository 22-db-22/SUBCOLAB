package com.subcolab.android.media

import android.content.Context
import com.subcolab.android.model.SubtitleCue
import com.subcolab.android.model.VideoChunkInfo
import com.subcolab.android.util.AppLogger
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class SubtitleTrackCandidate(
    val lang: String,
    val ext: String,
    val url: String,
    val name: String? = null,
)

sealed class SubtitleStrategy {
    data object UseWhisperGpu : SubtitleStrategy()
    data class UseDirectEnglish(val track: SubtitleTrackCandidate) : SubtitleStrategy()
    data class TranslateManualSubtitles(val track: SubtitleTrackCandidate, val sourceLang: String) : SubtitleStrategy()
}

data class ResolvedStreamInfo(
    val title: String?,
    val durationSec: Double,
    val videoUrl: String,
    val audioUrl: String?,
    val httpHeaders: Map<String, String>,
    val canUseMpegTs: Boolean,
    val clientUsed: String,
    val manualSubtitles: List<SubtitleTrackCandidate> = emptyList(),
    val thumbnailUrl: String? = null,
)

class StreamExtractor {

    companion object {
        private const val TAG = "StreamExtractor"
        // Ordered fastest-working first based on real mobile carrier/Wi-Fi results (skip deprecated tv_embedded)
        private val FAST_CLIENTS = listOf("default", "mweb", "android_vr")
        private val ytdlUpdateMutex = Mutex()
        private const val PREF_YTDLP = "subcolab_ytdlp_meta"
        private const val KEY_LAST_UPDATE_MS = "last_ytdlp_update_ms"
        private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val FAILED_RETRY_COOLDOWN_MS = 60_000L
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        @Volatile
        private var lastFailedUpdateAttemptMs: Long = 0L

        internal fun parseSegmentsCsvLine(line: String): Triple<String, Double, Double>? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split(',')
            if (parts.size < 3) return null
            val filename = parts.dropLast(2).joinToString(",").trim()
            val start = parts[parts.size - 2].trim().toDoubleOrNull() ?: return null
            val end = parts[parts.size - 1].trim().toDoubleOrNull() ?: return null
            if (filename.isBlank() || end <= start) return null
            return Triple(filename, start, end)
        }

        internal fun isCodecMpegTsCompatible(vcodec: String?, acodec: String?): Boolean {
            val v = vcodec.orEmpty().lowercase()
            val a = acodec.orEmpty().lowercase()
            val vOk = v.startsWith("avc") || v.startsWith("h264") || v.startsWith("hevc") || v.startsWith("hev1") || v.startsWith("mp4v")
            val aOk = a.isBlank() || a == "none" || a.startsWith("mp4a") || a.startsWith("aac") || a.startsWith("mp3")
            return vOk && aOk
        }

        /**
         * Chooses an adaptive audio chunk duration (in seconds) based on total video duration:
         * - Short videos (<=3 min): 15s for ultra-fast initial subtitles
         * - Medium videos (3-15 min): 25s balanced latency & Whisper context
         * - Long videos (15-45 min): 40s fewer chunks & better translation continuity
         * - Very long videos (>45 min): 60s efficient batching
         * - Unknown duration (<=0): 20s safe default
         */
        fun computeAdaptiveAudioChunkSec(durationSec: Double): Int {
            return when {
                durationSec <= 0.0 -> 20
                durationSec <= 180.0 -> 15
                durationSec <= 900.0 -> 25
                durationSec <= 2700.0 -> 40
                else -> 60
            }
        }

        private fun baseLangCode(raw: String): String {
            val clean = raw.trim().lowercase()
            return when {
                clean.startsWith("zh") -> clean.substringBefore('-').substringBefore('_')
                else -> clean.substringBefore('-').substringBefore('_')
            }
        }

        fun selectSubtitleStrategy(
            manualSubs: List<SubtitleTrackCandidate>,
            requestedSourceLang: String,
        ): SubtitleStrategy {
            if (manualSubs.isEmpty()) return SubtitleStrategy.UseWhisperGpu

            val enCandidates = manualSubs.filter { baseLangCode(it.lang) == "en" }
            if (enCandidates.isNotEmpty()) {
                val bestEn = enCandidates.sortedWith(
                    compareBy<SubtitleTrackCandidate> {
                        if (it.lang.equals("en", ignoreCase = true)) 0 else 1
                    }.thenBy {
                        if (it.ext.equals("srt", ignoreCase = true)) 0 else 1
                    },
                ).first()
                return SubtitleStrategy.UseDirectEnglish(bestEn)
            }

            val nonEnCandidates = manualSubs.filter {
                val base = baseLangCode(it.lang)
                base.isNotBlank() && base != "en" && base != "live_chat" && base != "danmaku"
            }
            if (nonEnCandidates.isEmpty()) return SubtitleStrategy.UseWhisperGpu

            val req = requestedSourceLang.trim().lowercase()
            val chosen = if (req.isNotBlank() && req != "auto" && req != "detect") {
                val reqBase = baseLangCode(req)
                nonEnCandidates.filter { baseLangCode(it.lang) == reqBase }
                    .minByOrNull { if (it.ext.equals("srt", ignoreCase = true)) 0 else 1 }
                    ?: nonEnCandidates.minByOrNull { if (it.ext.equals("srt", ignoreCase = true)) 0 else 1 }!!
            } else {
                nonEnCandidates.minByOrNull { if (it.ext.equals("srt", ignoreCase = true)) 0 else 1 }!!
            }
            return SubtitleStrategy.TranslateManualSubtitles(chosen, chosen.lang)
        }

        internal fun flattenCueText(text: String): String =
            text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

        internal fun parseGoogleTranslateBatchJson(rawJson: String): String? {
            return runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(rawJson).jsonArray
                val segments = root.firstOrNull()?.jsonArray ?: return null
                buildString {
                    for (seg in segments) {
                        val piece = seg.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull
                        if (piece != null) append(piece)
                    }
                }
            }.getOrNull()
        }

        @Volatile
        private var loggedFfmpegLdPath = false

        private fun buildFfmpegLdLibraryPath(context: Context): String {
            val dirs = LinkedHashSet<String>()
            val baseRoots = listOfNotNull(
                File(context.noBackupFilesDir, "youtubedl-android/packages"),
                File(context.filesDir, "youtubedl-android/packages"),
            )
            for (pkgRoot in baseRoots) {
                if (!pkgRoot.isDirectory) continue
                // Prioritize ffmpeg's own usr/lib where libavdevice.so.61 lives
                listOf("ffmpeg/usr/lib", "ffmpeg/lib", "python/usr/lib", "python/lib").forEach { rel ->
                    val candidate = File(pkgRoot, rel)
                    if (candidate.isDirectory) dirs.add(candidate.absolutePath)
                }
                pkgRoot.listFiles()?.forEach { pkgDir ->
                    if (pkgDir.isDirectory) {
                        val usrLib = File(pkgDir, "usr/lib")
                        if (usrLib.isDirectory) dirs.add(usrLib.absolutePath)
                        val lib = File(pkgDir, "lib")
                        if (lib.isDirectory) dirs.add(lib.absolutePath)
                    }
                }
            }
            val nativeDir = context.applicationInfo.nativeLibraryDir
            if (!nativeDir.isNullOrBlank()) {
                dirs.add(nativeDir)
            }
            return dirs.joinToString(":")
        }

        internal fun startFfmpegProcess(context: Context, args: List<String>): Process {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val ffmpegBin = File(nativeDir, "libffmpeg.so")
            if (!ffmpegBin.exists() || !ffmpegBin.canExecute()) {
                throw IllegalStateException("libffmpeg.so binary unavailable.")
            }
            val cmd = ArrayList<String>(args.size + 1)
            cmd.add(ffmpegBin.absolutePath)
            cmd.addAll(args)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val env = pb.environment()
            val extraLd = buildFfmpegLdLibraryPath(context)
            val prevLd = env["LD_LIBRARY_PATH"]
            val finalLd = when {
                extraLd.isBlank() -> prevLd.orEmpty()
                prevLd.isNullOrBlank() -> extraLd
                else -> "$extraLd:$prevLd"
            }
            if (finalLd.isNotBlank()) {
                env["LD_LIBRARY_PATH"] = finalLd
            }
            if (!loggedFfmpegLdPath) {
                loggedFfmpegLdPath = true
                AppLogger.i(TAG, "libffmpeg.so LD_LIBRARY_PATH=$finalLd")
            }
            return pb.start()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val activeNativeProcesses = ConcurrentHashMap<String, Process>()

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
        }
    }

    private val proxyHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .protocols(listOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Fast 5s HTTP probe client so dead/throttled CDN URLs fail quickly instead of stalling 35s
    private val probeHttpClient: OkHttpClient = proxyHttpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun updateYtDlpIfDue(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_YTDLP, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_UPDATE_MS, 0L) < UPDATE_INTERVAL_MS) return@withContext
        if (now - lastFailedUpdateAttemptMs < FAILED_RETRY_COOLDOWN_MS) return@withContext

        ytdlUpdateMutex.withLock {
            val checkNow = System.currentTimeMillis()
            if (checkNow - prefs.getLong(KEY_LAST_UPDATE_MS, 0L) < UPDATE_INTERVAL_MS) return@withLock
            if (checkNow - lastFailedUpdateAttemptMs < FAILED_RETRY_COOLDOWN_MS) return@withLock

            val updated = runCatching {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            }.recoverCatching {
                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY)
            }.onFailure { err ->
                lastFailedUpdateAttemptMs = System.currentTimeMillis()
                AppLogger.w(TAG, "yt-dlp auto-update failed (will retry later): ${err.message}", err)
            }.getOrNull()

            if (updated != null) {
                lastFailedUpdateAttemptMs = 0L
                prefs.edit().putLong(KEY_LAST_UPDATE_MS, System.currentTimeMillis()).apply()
                AppLogger.i(TAG, "yt-dlp auto-updated: $updated")
            }
        }
    }

    private suspend fun waitForAnyPendingUpdate() {
        // Never block an active share/playback job waiting on a slow GitHub yt-dlp binary check
        withTimeoutOrNull(1_500L) {
            ytdlUpdateMutex.withLock { }
        }
    }

    fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    /**
     * Quick metadata title fetch so folder names in Downloads/SUBCOLAB/<shortTitle>/ are clean.
     */
    suspend fun fetchTitleHint(url: String, cookiesFile: File?): String? = withContext(Dispatchers.IO) {
        waitForAnyPendingUpdate()
        val cookiesArg = cookiesFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        val isYt = isYouTubeUrl(url)
        runCatching {
            val req = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--skip-download")
                addOption("--print", "%(title).120s")
                addOption("--socket-timeout", "10")
                addOption("--force-ipv4")
                if (!isYt) {
                    addOption("--geo-bypass")
                    addOption("--user-agent", DESKTOP_USER_AGENT)
                }
                if (cookiesArg != null) addOption("--cookies", cookiesArg)
            }
            val out = YoutubeDL.getInstance().execute(req, null, null).out
            out.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
        }.getOrNull()
    }

    private data class ResolveAttempt(
        val client: String,
        val forceIpv4: Boolean,
        val desktopUa: Boolean,
        val fmt: String,
    )

    /**
     * Resolves direct video (+ optional separate audio) stream URLs, headers, title, and duration in one fast JSON call.
     */
    suspend fun resolveStreamInfo(
        url: String,
        cookiesFile: File?,
        maxHeight: Int = 720,
        processId: String = "resolve_stream",
    ): ResolvedStreamInfo? = withContext(Dispatchers.IO) {
        waitForAnyPendingUpdate()
        val cookiesArg = cookiesFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        val h = maxHeight.coerceIn(360, 1080)
        val isYt = isYouTubeUrl(url)
        val primaryFmt = "bestvideo[height<=$h][vcodec^=avc1]+bestaudio[acodec^=mp4a]/" +
            "bestvideo[height<=$h]+bestaudio/" +
            "best[height<=$h][vcodec!=none][acodec!=none]/18/best"
        val attempts = if (isYt) {
            FAST_CLIENTS.map { ResolveAttempt(client = it, forceIpv4 = true, desktopUa = false, fmt = primaryFmt) }
        } else {
            listOf(
                ResolveAttempt(client = "default", forceIpv4 = true, desktopUa = false, fmt = primaryFmt),
                ResolveAttempt(client = "default", forceIpv4 = false, desktopUa = true, fmt = "bestvideo[height<=$h]+bestaudio/best[height<=$h]/best"),
            )
        }

        var fallbackResolved: ResolvedStreamInfo? = null
        for (attempt in attempts) {
            currentCoroutineContext().ensureActive()
            val resolved = runCatching {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("-J")
                    addOption("--socket-timeout", "10")
                    if (attempt.forceIpv4) addOption("--force-ipv4")
                    if (!isYt) addOption("--geo-bypass")
                    if (attempt.desktopUa) addOption("--user-agent", DESKTOP_USER_AGENT)
                    addOption("-f", attempt.fmt)
                    if (attempt.client != "default") {
                        addOption("--extractor-args", "youtube:player_client=${attempt.client}")
                    }
                    if (cookiesArg != null) addOption("--cookies", cookiesArg)
                }
                val rawOut = YoutubeDL.getInstance().execute(req, processId, null).out
                val jsonLine = rawOut.lineSequence()
                    .map { it.trim() }
                    .lastOrNull { it.startsWith("{") && it.endsWith("}") }
                    ?: return@runCatching null
                parseResolvedJson(jsonLine, attempt.client)
            }.onFailure { err ->
                AppLogger.w(TAG, "resolveStreamInfo(${attempt.client}, ipv4=${attempt.forceIpv4}, ua=${attempt.desktopUa}) failed: ${err.message}")
            }.getOrNull()

            if (resolved != null) {
                if (fallbackResolved == null) fallbackResolved = resolved
                val videoCode = probeStreamUrlCode(resolved.videoUrl, resolved.httpHeaders)
                val videoOk = videoCode in 200..299 || videoCode == 304
                val audioCode = resolved.audioUrl?.let { probeStreamUrlCode(it, resolved.httpHeaders) } ?: 200
                val audioOk = audioCode in 200..299 || audioCode == 304
                if (videoOk && audioOk) {
                    AppLogger.i(
                        TAG,
                        "Resolved stream (client=${attempt.client}, dur=${resolved.durationSec.toInt()}s, split=${resolved.audioUrl != null}, mpegts=${resolved.canUseMpegTs}, http=$videoCode/$audioCode)",
                    )
                    return@withContext resolved
                }
                AppLogger.w(
                    TAG,
                    "Resolved URL from client=${attempt.client} returned HTTP v=$videoCode a=$audioCode; trying next client...",
                )
            }
        }
        fallbackResolved
    }

    fun isResolvedStreamReachable(streamInfo: ResolvedStreamInfo): Boolean {
        val videoCode = probeStreamUrlCode(streamInfo.videoUrl, streamInfo.httpHeaders)
        val videoOk = videoCode in 200..299 || videoCode == 304
        if (!videoOk) return false
        val audioCode = streamInfo.audioUrl?.let { probeStreamUrlCode(it, streamInfo.httpHeaders) } ?: 200
        return audioCode in 200..299 || audioCode == 304
    }

    private fun probeStreamUrlCode(url: String, headers: Map<String, String>): Int {
        return runCatching {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Range", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    builder.header(k, v)
                }
            }
            if (!url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                builder.header("Range", "bytes=0-0")
            }
            builder.header("Accept-Encoding", "identity")
            probeHttpClient.newCall(builder.build()).execute().use { it.code }
        }.getOrDefault(0)
    }

    suspend fun downloadTextUrl(url: String, headers: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    builder.header(k, v)
                }
            }
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                builder.header("User-Agent", DESKTOP_USER_AGENT)
            }
            proxyHttpClient.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull()
    }

    suspend fun translateCuesToEnglish(
        cues: List<SubtitleCue>,
        sourceLang: String,
    ): List<SubtitleCue>? = withContext(Dispatchers.IO) {
        if (cues.isEmpty()) return@withContext null
        val sl = baseLangCode(sourceLang).takeIf { it.isNotBlank() && it != "auto" } ?: "auto"
        val outCues = ArrayList<SubtitleCue>(cues.size)
        val batchSize = 15

        for (startIdx in cues.indices step batchSize) {
            currentCoroutineContext().ensureActive()
            val batch = cues.subList(startIdx, minOf(startIdx + batchSize, cues.size))
            val lines = batch.map { flattenCueText(it.text) }
            val joined = lines.joinToString("\n")
            val translatedBlock = fetchGoogleTranslateText(joined, sl) ?: return@withContext null
            val translatedLines = translatedBlock.split('\n').map { it.trim() }

            if (translatedLines.size == batch.size) {
                for (i in batch.indices) {
                    val text = translatedLines[i].ifBlank { lines[i] }
                    outCues.add(batch[i].copy(index = outCues.size + 1, text = text))
                }
            } else {
                // Fallback 1-by-1 only for this mismatched batch to guarantee exact timestamp alignment
                for (i in batch.indices) {
                    currentCoroutineContext().ensureActive()
                    val single = fetchGoogleTranslateText(lines[i], sl) ?: return@withContext null
                    outCues.add(batch[i].copy(index = outCues.size + 1, text = single.trim().ifBlank { lines[i] }))
                }
            }
        }
        outCues
    }

    private fun fetchGoogleTranslateText(queryText: String, sourceLang: String): String? {
        if (queryText.isBlank()) return ""
        return runCatching {
            val encoded = URLEncoder.encode(queryText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=en&dt=t&q=$encoded"
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", DESKTOP_USER_AGENT)
                .build()
            proxyHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                parseGoogleTranslateBatchJson(body)
            }
        }.getOrNull()
    }

    private fun parseResolvedJson(jsonText: String, clientUsed: String): ResolvedStreamInfo? {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        val durationSec = root["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val topHeaders = extractHeaders(root["http_headers"] as? JsonObject)
        // Strictly read creator-uploaded `subtitles` only; ignore `automatic_captions`
        val manualSubs = extractManualSubtitles(root["subtitles"] as? JsonObject)
        val thumbnailUrl = extractThumbnailUrl(root)

        val reqFormats = root["requested_formats"]?.jsonArray
        if (reqFormats != null && reqFormats.isNotEmpty()) {
            val formats = reqFormats.mapNotNull { it as? JsonObject }
            val videoFmt = formats.firstOrNull {
                val v = it["vcodec"]?.jsonPrimitive?.contentOrNull.orEmpty()
                v.isNotBlank() && v != "none"
            } ?: formats.firstOrNull()
            val audioFmt = formats.firstOrNull {
                val a = it["acodec"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val v = it["vcodec"]?.jsonPrimitive?.contentOrNull.orEmpty()
                a.isNotBlank() && a != "none" && (v.isBlank() || v == "none")
            } ?: formats.getOrNull(1)

            val vUrl = videoFmt?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                ?: return null
            val aUrl = audioFmt?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
            val vCodec = videoFmt["vcodec"]?.jsonPrimitive?.contentOrNull
            val aCodec = audioFmt?.get("acodec")?.jsonPrimitive?.contentOrNull
            val mergedHeaders = LinkedHashMap<String, String>(topHeaders)
            mergedHeaders.putAll(extractHeaders(videoFmt["http_headers"] as? JsonObject))

            return ResolvedStreamInfo(
                title = title,
                durationSec = durationSec,
                videoUrl = vUrl,
                audioUrl = aUrl,
                httpHeaders = mergedHeaders,
                canUseMpegTs = isCodecMpegTsCompatible(vCodec, aCodec),
                clientUsed = clientUsed,
                manualSubtitles = manualSubs,
                thumbnailUrl = thumbnailUrl,
            )
        }

        val singleUrl = root["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
            ?: return null
        val vCodec = root["vcodec"]?.jsonPrimitive?.contentOrNull
        val aCodec = root["acodec"]?.jsonPrimitive?.contentOrNull
        return ResolvedStreamInfo(
            title = title,
            durationSec = durationSec,
            videoUrl = singleUrl,
            audioUrl = null,
            httpHeaders = topHeaders,
            canUseMpegTs = isCodecMpegTsCompatible(vCodec, aCodec),
            clientUsed = clientUsed,
            manualSubtitles = manualSubs,
            thumbnailUrl = thumbnailUrl,
        )
    }

    private fun extractThumbnailUrl(root: JsonObject): String? {
        val direct = root["thumbnail"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (direct != null) return direct
        val list = runCatching { root["thumbnails"]?.jsonArray }.getOrNull() ?: return null
        for (i in list.size - 1 downTo 0) {
            val obj = list[i] as? JsonObject ?: continue
            val candidate = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            if (candidate != null) return candidate
        }
        return null
    }

    private fun extractManualSubtitles(subObj: JsonObject?): List<SubtitleTrackCandidate> {
        if (subObj == null || subObj.isEmpty()) return emptyList()
        val list = ArrayList<SubtitleTrackCandidate>()
        for ((lang, tracksEl) in subObj) {
            val cleanLang = lang.trim()
            if (cleanLang.isBlank() || cleanLang.equals("live_chat", ignoreCase = true)) continue
            val tracks = runCatching { tracksEl.jsonArray }.getOrNull() ?: continue
            val candidates = tracks.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val ext = o["ext"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
                val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                if (ext != "srt" && ext != "vtt") return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull
                SubtitleTrackCandidate(lang = cleanLang, ext = ext, url = url, name = name)
            }
            val preferred = candidates.minByOrNull { if (it.ext == "srt") 0 else 1 }
            if (preferred != null) {
                list.add(preferred)
            }
        }
        return list
    }

    private fun extractHeaders(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for ((k, v) in obj) {
            val valStr = v.jsonPrimitive.contentOrNull
            if (!valStr.isNullOrBlank()) {
                map[k] = valStr
            }
        }
        return map
    }

    /**
     * Creates a local HTTP/1.1 loopback proxy backed by Android OkHttp (Conscrypt TLS, HTTP/2, IPv4-first DNS,
     * full header forwarding, byte Range support, and HLS M3U8 URI rewriting) so libmpv and libffmpeg.so
     * can live-stream remote URLs smoothly without native OpenSSL/CA cert issues.
     */
    fun createLoopbackProxy(headers: Map<String, String>): LoopbackMediaProxy = LoopbackMediaProxy(headers)

    inner class LoopbackMediaProxy(
        private val headers: Map<String, String>,
    ) : Closeable {
        private val serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val activeCalls = Collections.synchronizedSet(HashSet<Call>())
        @Volatile
        private var closed = false

        val port: Int = serverSocket.localPort

        init {
            executor.submit {
                while (!closed && !serverSocket.isClosed) {
                    val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                    executor.submit { handleClient(client) }
                }
            }
        }

        fun proxiedUrl(remoteUrl: String): String {
            return "http://127.0.0.1:$port/proxy?u=${URLEncoder.encode(remoteUrl, "UTF-8")}"
        }

        private fun handleClient(socket: Socket) {
            socket.use { sock ->
                runCatching {
                    sock.soTimeout = 35_000
                    val input = BufferedInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()

                    val reqLines = readHttpHeaderLines(input)
                    if (reqLines.isEmpty()) return@runCatching
                    val requestParts = reqLines[0].split(' ')
                    if (requestParts.size < 2) return@runCatching
                    val method = requestParts[0].uppercase()
                    val rawPath = requestParts[1]
                    val targetUrl = extractQueryParam(rawPath, "u")
                    if (targetUrl.isNullOrBlank() || !targetUrl.startsWith("http")) {
                        out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        return@runCatching
                    }

                    var rangeHeader: String? = null
                    for (i in 1 until reqLines.size) {
                        val line = reqLines[i]
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            val name = line.substring(0, colon).trim()
                            if (name.equals("Range", ignoreCase = true)) {
                                rangeHeader = line.substring(colon + 1).trim()
                            }
                        }
                    }

                    val reqBuilder = Request.Builder().url(targetUrl)
                    if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
                    headers.forEach { (k, v) ->
                        if (!k.equals("Host", ignoreCase = true) &&
                            !k.equals("Connection", ignoreCase = true) &&
                            !k.equals("Content-Length", ignoreCase = true) &&
                            !k.equals("Accept-Encoding", ignoreCase = true) &&
                            !k.equals("Range", ignoreCase = true)
                        ) {
                            reqBuilder.header(k, v)
                        }
                    }
                    reqBuilder.header("Accept-Encoding", "identity")
                    if (!rangeHeader.isNullOrBlank()) {
                        reqBuilder.header("Range", rangeHeader)
                    }

                    val call = proxyHttpClient.newCall(reqBuilder.build())
                    activeCalls.add(call)
                    try {
                        call.execute().use { resp ->
                            val cType = resp.header("Content-Type").orEmpty()
                            val finalUrl = resp.request.url.toString()
                            val isM3u8 = finalUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
                                cType.contains("mpegurl", ignoreCase = true)

                            if (resp.isSuccessful && isM3u8) {
                                val rawText = resp.body?.string().orEmpty()
                                val rewritten = rewriteM3u8Playlist(rawText, finalUrl).toByteArray(Charsets.UTF_8)
                                val respHeader = buildString {
                                    append("HTTP/1.1 200 OK\r\n")
                                    append("Content-Type: application/vnd.apple.mpegurl\r\n")
                                    append("Content-Length: ").append(rewritten.size).append("\r\n")
                                    append("Connection: close\r\n\r\n")
                                }.toByteArray(Charsets.ISO_8859_1)
                                out.write(respHeader)
                                if (method != "HEAD") {
                                    out.write(rewritten)
                                }
                                out.flush()
                                return@use
                            }

                            val respHeader = buildString {
                                append("HTTP/1.1 ").append(resp.code).append(' ')
                                    .append(resp.message.ifBlank { if (resp.code == 206) "Partial Content" else "OK" })
                                    .append("\r\n")
                                if (cType.isNotBlank()) append("Content-Type: ").append(cType).append("\r\n")
                                val contentLen = resp.body?.contentLength() ?: -1L
                                if (contentLen >= 0L) append("Content-Length: ").append(contentLen).append("\r\n")
                                resp.header("Content-Range")?.let { cr ->
                                    append("Content-Range: ").append(cr).append("\r\n")
                                }
                                val acceptRanges = resp.header("Accept-Ranges")
                                    ?: if (resp.code == 206) "bytes" else null
                                if (!acceptRanges.isNullOrBlank()) {
                                    append("Accept-Ranges: ").append(acceptRanges).append("\r\n")
                                }
                                append("Connection: close\r\n\r\n")
                            }.toByteArray(Charsets.ISO_8859_1)
                            out.write(respHeader)

                            if (method != "HEAD") {
                                resp.body?.byteStream()?.use { stream ->
                                    val buf = ByteArray(32 * 1024)
                                    while (!closed) {
                                        val n = stream.read(buf)
                                        if (n < 0) break
                                        if (n > 0) out.write(buf, 0, n)
                                    }
                                }
                            }
                            out.flush()
                        }
                    } finally {
                        activeCalls.remove(call)
                    }
                }
            }
        }

        private fun readHttpHeaderLines(input: BufferedInputStream): List<String> {
            val bytes = ByteArrayOutputStream()
            var prev3 = -1
            var prev2 = -1
            var prev1 = -1
            while (bytes.size() < 32 * 1024) {
                val b = input.read()
                if (b < 0) break
                bytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) break
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }
            val raw = bytes.toString("ISO-8859-1")
            return raw.split("\r\n").filter { it.isNotEmpty() }
        }

        private fun extractQueryParam(pathAndQuery: String, key: String): String? {
            val query = pathAndQuery.substringAfter('?', "")
            if (query.isEmpty()) return null
            for (pair in query.split('&')) {
                val k = pair.substringBefore('=')
                if (k == key) {
                    val v = pair.substringAfter('=', "")
                    return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrNull()
                }
            }
            return null
        }

        private fun rewriteM3u8Playlist(content: String, baseUrl: String): String {
            val baseUri = runCatching { URI(baseUrl) }.getOrNull() ?: return content
            val uriAttrRegex = Regex("""URI="([^"]+)"""")
            return content.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> uriAttrRegex.replace(line) { m ->
                        val resolved = runCatching { baseUri.resolve(m.groupValues[1]).toString() }
                            .getOrNull()
                        if (resolved != null && resolved.startsWith("http")) {
                            """URI="${proxiedUrl(resolved)}""""
                        } else {
                            m.value
                        }
                    }
                    else -> {
                        val resolved = runCatching { baseUri.resolve(trimmed).toString() }.getOrNull()
                        if (resolved != null && resolved.startsWith("http")) proxiedUrl(resolved) else line
                    }
                }
            }
        }

        override fun close() {
            closed = true
            synchronized(activeCalls) {
                activeCalls.forEach { runCatching { it.cancel() } }
                activeCalls.clear()
            }
            runCatching { serverSocket.close() }
            runCatching { executor.shutdownNow() }
        }
    }

    /**
     * Streams a remote video into sequential time-sliced chunks via libffmpeg.so + LoopbackMediaProxy,
     * emitting each chunk as soon as it is finalized on disk so chunk 1 playback + chunk-by-chunk subtitle translation start immediately.
     */
    suspend fun downloadVideoInChunks(
        context: Context,
        streamInfo: ResolvedStreamInfo,
        workDir: File,
        chunkDurationSec: Int = 20,
        processId: String = "save_mp4",
        onProgress: (Float, String) -> Unit,
        onChunkReady: suspend (VideoChunkInfo) -> Unit,
    ): List<VideoChunkInfo> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        val segmentsCsv = File(workDir, "segments.csv")
        val useTs = streamInfo.canUseMpegTs
        val segFormat = if (useTs) "mpegts" else "matroska"
        val ext = if (useTs) "ts" else "mkv"

        val localProxy = LoopbackMediaProxy(streamInfo.httpHeaders)
        val localVideoUrl = localProxy.proxiedUrl(streamInfo.videoUrl)
        val localAudioUrl = streamInfo.audioUrl?.takeIf { it.isNotBlank() }?.let { localProxy.proxiedUrl(it) }

        fun MutableList<String>.addProxyInput(url: String) {
            add("-reconnect")
            add("1")
            add("-reconnect_streamed")
            add("1")
            add("-reconnect_delay_max")
            add("5")
            add("-i")
            add(url)
        }

        val args = mutableListOf(
            "-nostdin", "-y",
            "-hide_banner", "-loglevel", "warning",
            "-stats_period", "1",
        )
        args.addProxyInput(localVideoUrl)
        if (!localAudioUrl.isNullOrBlank()) {
            args.addProxyInput(localAudioUrl)
            args.addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
        } else {
            args.addAll(listOf("-map", "0:v:0", "-map", "0:a:0?"))
        }
        args.addAll(
            listOf(
                "-c", "copy",
                "-f", "segment",
                "-segment_time", chunkDurationSec.coerceIn(15, 90).toString(),
                "-segment_format", segFormat,
                "-reset_timestamps", "1",
                "-segment_list_type", "csv",
                "-segment_list", segmentsCsv.absolutePath,
                File(workDir, "chunk_%03d.$ext").absolutePath,
            ),
        )

        AppLogger.i(TAG, "Starting chunked ffmpeg download (${chunkDurationSec}s slices, fmt=$segFormat, proxyPort=${localProxy.port})")
        val proc = startFfmpegProcess(context, args)
        activeNativeProcesses[processId] = proc

        val outputTail = ArrayList<String>(20)
        val drainJob = launch {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val s = raw.trim()
                        if (s.isNotEmpty()) {
                            synchronized(outputTail) {
                                outputTail.add(s)
                                if (outputTail.size > 20) outputTail.removeAt(0)
                            }
                        }
                    }
                }
            }
        }

        val emittedFiles = HashSet<String>()
        val chunks = ArrayList<VideoChunkInfo>()

        suspend fun pollNewChunks() {
            if (!segmentsCsv.exists()) return
            val lines = runCatching { segmentsCsv.readLines() }.getOrDefault(emptyList())
            for (line in lines) {
                val parsed = parseSegmentsCsvLine(line) ?: continue
                val (filename, startSec, endSec) = parsed
                if (!emittedFiles.add(filename)) continue
                val chunkFile = File(workDir, filename)
                if (!chunkFile.exists() || chunkFile.length() <= 0L) {
                    emittedFiles.remove(filename)
                    continue
                }
                val chunkInfo = VideoChunkInfo(
                    index = chunks.size,
                    path = chunkFile.absolutePath,
                    startSec = startSec,
                    endSec = endSec,
                )
                chunks.add(chunkInfo)
                val pct = if (streamInfo.durationSec > 0.0) {
                    ((endSec / streamInfo.durationSec) * 100.0).toFloat().coerceIn(1f, 99f)
                } else {
                    (chunks.size * 10f).coerceIn(5f, 95f)
                }
                onProgress(pct, "Chunk ${chunkInfo.index + 1} ready (${endSec.toInt()}s)")
                onChunkReady(chunkInfo)
            }
        }

        try {
            while (proc.isAlive) {
                currentCoroutineContext().ensureActive()
                pollNewChunks()
                delay(200L)
            }
            drainJob.join()
            pollNewChunks()
            val rc = proc.exitValue()
            if (rc != 0 || chunks.isEmpty()) {
                val errTail = synchronized(outputTail) { outputTail.joinToString("\n") }
                throw IllegalStateException("Chunked download failed (ffmpeg rc=$rc): $errTail")
            }
            onProgress(100f, "All ${chunks.size} video chunks downloaded")
            AppLogger.i(TAG, "Chunked download complete: ${chunks.size} chunks")
            chunks
        } finally {
            activeNativeProcesses.remove(processId, proc)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
            drainJob.cancel()
            localProxy.close()
        }
    }

    /**
     * Downloads ONLY the audio track into adaptive sequential 16 kHz mono 24 kbps ADTS AAC chunks (~3 KB/sec)
     * so live video streaming in libmpv is never slowed down or interrupted by video chunking.
     */
    suspend fun downloadAudioInChunks(
        context: Context,
        streamInfo: ResolvedStreamInfo,
        workDir: File,
        existingProxy: LoopbackMediaProxy? = null,
        chunkDurationSec: Int = computeAdaptiveAudioChunkSec(streamInfo.durationSec),
        startOffsetSec: Double = 0.0,
        processId: String = "relay_audio",
        onProgress: (Float, String) -> Unit,
        onAudioChunkReady: suspend (VideoChunkInfo) -> Unit,
    ): List<VideoChunkInfo> = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        val segmentsCsv = File(workDir, "audio_segments.csv")
        val ownedProxy = if (existingProxy == null) LoopbackMediaProxy(streamInfo.httpHeaders) else null
        val proxy = existingProxy ?: ownedProxy!!
        val sourceUrl = streamInfo.audioUrl?.takeIf { it.isNotBlank() } ?: streamInfo.videoUrl
        val localAudioUrl = proxy.proxiedUrl(sourceUrl)
        val segSec = chunkDurationSec.coerceIn(12, 60)
        val safeOffsetSec = if (startOffsetSec >= 1.0) startOffsetSec else 0.0

        val args = buildList {
            add("-nostdin")
            add("-y")
            add("-hide_banner")
            add("-loglevel")
            add("warning")
            add("-err_detect")
            add("ignore_err")
            add("-reconnect")
            add("1")
            add("-reconnect_streamed")
            add("1")
            add("-reconnect_delay_max")
            add("5")
            if (safeOffsetSec > 0.0) {
                add("-ss")
                add(String.format(Locale.US, "%.2f", safeOffsetSec))
            }
            add("-i")
            add(localAudioUrl)
            add("-vn")
            add("-sn")
            add("-dn")
            add("-ac")
            add("1")
            add("-ar")
            add("16000")
            add("-c:a")
            add("aac")
            add("-b:a")
            add("24k")
            add("-f")
            add("segment")
            add("-segment_time")
            add(segSec.toString())
            add("-segment_format")
            add("adts")
            add("-reset_timestamps")
            add("1")
            add("-segment_list_type")
            add("csv")
            add("-segment_list")
            add(segmentsCsv.absolutePath)
            add(File(workDir, "audio_%03d.aac").absolutePath)
        }

        AppLogger.i(TAG, "Starting adaptive audio-only chunk stream (${segSec}s slices, offset=${safeOffsetSec.toInt()}s, dur=${streamInfo.durationSec.toInt()}s, proxyPort=${proxy.port})")
        val proc = startFfmpegProcess(context, args)
        activeNativeProcesses[processId] = proc

        val outputTail = ArrayList<String>(20)
        val drainJob = launch {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val s = raw.trim()
                        if (s.isNotEmpty()) {
                            synchronized(outputTail) {
                                outputTail.add(s)
                                if (outputTail.size > 20) outputTail.removeAt(0)
                            }
                        }
                    }
                }
            }
        }

        val emittedFiles = HashSet<String>()
        val chunks = ArrayList<VideoChunkInfo>()

        suspend fun pollNewAudioChunks() {
            if (!segmentsCsv.exists()) return
            val lines = runCatching { segmentsCsv.readLines() }.getOrDefault(emptyList())
            for (line in lines) {
                val parsed = parseSegmentsCsvLine(line) ?: continue
                val (filename, relStartSec, relEndSec) = parsed
                if (!emittedFiles.add(filename)) continue
                val chunkFile = File(workDir, filename)
                if (!chunkFile.exists() || chunkFile.length() <= 0L) {
                    emittedFiles.remove(filename)
                    continue
                }
                val absStartSec = relStartSec + safeOffsetSec
                val absEndSec = relEndSec + safeOffsetSec
                val chunkInfo = VideoChunkInfo(
                    index = chunks.size,
                    path = chunkFile.absolutePath,
                    startSec = absStartSec,
                    endSec = absEndSec,
                )
                chunks.add(chunkInfo)
                val pct = if (streamInfo.durationSec > 0.0) {
                    ((absEndSec / streamInfo.durationSec) * 100.0).toFloat().coerceIn(1f, 99f)
                } else {
                    (chunks.size * 10f).coerceIn(5f, 95f)
                }
                onProgress(pct, "Audio chunk ${chunkInfo.index + 1} (${absEndSec.toInt()}s) queued")
                onAudioChunkReady(chunkInfo)
            }
        }

        try {
            while (proc.isAlive) {
                currentCoroutineContext().ensureActive()
                pollNewAudioChunks()
                delay(180L)
            }
            drainJob.join()
            pollNewAudioChunks()
            val rc = proc.exitValue()
            if (rc != 0 || chunks.isEmpty()) {
                val errTail = synchronized(outputTail) { outputTail.joinToString("\n") }
                throw IllegalStateException("Audio-only chunk stream failed (ffmpeg rc=$rc): $errTail")
            }
            onProgress(100f, "All ${chunks.size} audio chunks queued for Whisper")
            AppLogger.i(TAG, "Audio-only chunk stream complete: ${chunks.size} chunks (${segSec}s adaptive)")
            chunks
        } finally {
            activeNativeProcesses.remove(processId, proc)
            if (proc.isAlive) {
                proc.destroyForcibly()
            }
            drainJob.cancel()
            ownedProxy?.close()
        }
    }

    /**
     * Losslessly stitches all downloaded video chunks into a single fast-start MP4 file in the background.
     */
    suspend fun stitchChunksToMp4(
        context: Context,
        chunks: List<VideoChunkInfo>,
        outputMp4: File,
        canUseMpegTs: Boolean,
    ): File = withContext(Dispatchers.IO) {
        require(chunks.isNotEmpty()) { "No chunks available to stitch." }
        outputMp4.parentFile?.mkdirs()
        if (outputMp4.exists()) outputMp4.delete()

        val concatFile = File(outputMp4.parentFile, "${outputMp4.nameWithoutExtension}_concat.txt")
        val listContent = chunks.joinToString("\n") { chunk ->
            val escaped = chunk.path.replace("'", "'\\''")
            "file '$escaped'"
        }
        concatFile.writeText(listContent, Charsets.UTF_8)

        fun runConcat(extraArgs: List<String>): Pair<Int, String> {
            val args = mutableListOf(
                "-nostdin", "-y",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.absolutePath,
                "-c", "copy",
            )
            args.addAll(extraArgs)
            args.addAll(listOf("-movflags", "+faststart", outputMp4.absolutePath))
            val proc = startFfmpegProcess(context, args)
            val out = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            return rc to out
        }

        try {
            var (rc, out) = if (canUseMpegTs) {
                runConcat(listOf("-bsf:a", "aac_adtstoasc"))
            } else {
                runConcat(emptyList())
            }
            if ((rc != 0 || !outputMp4.exists() || outputMp4.length() == 0L) && canUseMpegTs) {
                AppLogger.w(TAG, "Concat with aac_adtstoasc failed (rc=$rc), retrying plain -c copy...")
                if (outputMp4.exists()) outputMp4.delete()
                val retry = runConcat(emptyList())
                rc = retry.first
                out = retry.second
            }
            if (rc != 0 || !outputMp4.exists() || outputMp4.length() == 0L) {
                throw IllegalStateException("Failed to stitch chunks into MP4 (rc=$rc): ${out.takeLast(600)}")
            }
            AppLogger.i(TAG, "Stitched ${chunks.size} chunks -> ${outputMp4.name} (${outputMp4.length() / (1024 * 1024)} MB)")
            outputMp4
        } finally {
            runCatching { concatFile.delete() }
        }
    }

    private data class DownloadAttempt(
        val forceIpv4: Boolean,
        val client: String,
        val fmt: String,
        val desktopUa: Boolean,
    )

    suspend fun downloadAudioForRelay(
        url: String,
        destDir: File,
        cookiesFile: File?,
        processId: String = "relay_audio",
    ): File = withContext(Dispatchers.IO) {
        waitForAnyPendingUpdate()
        destDir.mkdirs()
        destDir.listFiles()?.filter { it.name.startsWith("subcolab_relay_") }?.forEach { it.delete() }

        val isYt = isYouTubeUrl(url)
        val clients = if (isYt) FAST_CLIENTS else listOf("default")
        val cookiesArg = cookiesFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        var lastError: Throwable? = null

        val selectors = listOf(
            "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio",
            "18/best[vcodec!=none][acodec!=none]/best",
        )

        val attempts = buildList {
            for (selector in selectors) {
                for (client in clients) {
                    add(DownloadAttempt(forceIpv4 = true, client = client, fmt = selector, desktopUa = false))
                }
            }
            if (!isYt) {
                add(DownloadAttempt(forceIpv4 = false, client = "default", fmt = selectors.last(), desktopUa = true))
            }
        }

        for (attempt in attempts) {
            val result = runCatching {
                val outTemplate = File(destDir, "subcolab_relay_audio.%(ext)s").absolutePath
                val req = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--force-overwrites")
                    addOption("--newline")
                    addOption("--socket-timeout", "15")
                    addOption("--retries", "2")
                    addOption("--fragment-retries", "2")
                    addOption("--concurrent-fragments", "4")
                    if (attempt.forceIpv4) addOption("--force-ipv4")
                    if (!isYt) addOption("--geo-bypass")
                    if (attempt.desktopUa) addOption("--user-agent", DESKTOP_USER_AGENT)
                    addOption("-f", attempt.fmt)
                    addOption("-o", outTemplate)
                    if (attempt.client != "default") {
                        addOption("--extractor-args", "youtube:player_client=${attempt.client}")
                    }
                    if (cookiesArg != null) addOption("--cookies", cookiesArg)
                }
                YoutubeDL.getInstance().execute(req, processId, null)
                destDir.listFiles()?.firstOrNull {
                    it.name.startsWith("subcolab_relay_audio.") && it.length() > 0
                } ?: throw IllegalStateException("Relayed audio file not found after yt-dlp finished.")
            }.onFailure { err ->
                AppLogger.w(TAG, "Audio relay (${attempt.client}, sel=${attempt.fmt.take(18)}) failed: ${err.message}")
            }
            if (result.isSuccess) return@withContext result.getOrThrow()
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Phone audio relay download failed.")
    }

    suspend fun downloadMp4ForSave(
        url: String,
        outputMp4: File,
        cookiesFile: File?,
        maxHeight: Int = 720,
        processId: String = "save_mp4",
        onProgress: (Float, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        waitForAnyPendingUpdate()
        outputMp4.parentFile?.mkdirs()
        val isYt = isYouTubeUrl(url)
        val clients = if (isYt) FAST_CLIENTS else listOf("default")
        val cookiesArg = cookiesFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
        var lastError: Throwable? = null

        val h = maxHeight.coerceIn(360, 1080)
        // Prefer progressive/unified on default when DASH 403s, while attempting requested height first
        val formatFallbacks = listOf(
            "bestvideo[height<=$h][vcodec^=avc1]+bestaudio[acodec^=mp4a]/bestvideo[height<=$h]+bestaudio/best[height<=$h][vcodec!=none][acodec!=none]",
            "best[height<=$h][vcodec!=none][acodec!=none]/18/best/worst",
        )

        val attempts = buildList {
            if (isYt) {
                for (fmt in formatFallbacks) {
                    for (client in clients) {
                        add(DownloadAttempt(forceIpv4 = true, client = client, fmt = fmt, desktopUa = false))
                    }
                }
                add(DownloadAttempt(forceIpv4 = false, client = "default", fmt = formatFallbacks.last(), desktopUa = false))
            } else {
                add(DownloadAttempt(forceIpv4 = true, client = "default", fmt = formatFallbacks[0], desktopUa = false))
                add(DownloadAttempt(forceIpv4 = false, client = "default", fmt = formatFallbacks.last(), desktopUa = true))
            }
        }

        for (attempt in attempts) {
            if (outputMp4.exists()) outputMp4.delete()
            AppLogger.i(TAG, "Starting MP4 download client=${attempt.client} maxH=$h ipv4=${attempt.forceIpv4} ua=${attempt.desktopUa}")
            val result = runCatching {
                val req = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-warnings")
                    addOption("--force-overwrites")
                    addOption("--no-part")
                    addOption("--newline")
                    addOption("--progress-delta", "2")
                    addOption("--concurrent-fragments", "4")
                    addOption("--socket-timeout", "15")
                    addOption("--retries", "3")
                    addOption("--fragment-retries", "3")
                    if (attempt.forceIpv4) addOption("--force-ipv4")
                    if (!isYt) addOption("--geo-bypass")
                    if (attempt.desktopUa) addOption("--user-agent", DESKTOP_USER_AGENT)
                    addOption("-f", attempt.fmt)
                    addOption("--merge-output-format", "mp4")
                    addOption("-o", outputMp4.absolutePath)
                    if (attempt.client != "default") {
                        addOption("--extractor-args", "youtube:player_client=${attempt.client}")
                    }
                    if (cookiesArg != null) addOption("--cookies", cookiesArg)
                }
                YoutubeDL.getInstance().execute(req, processId) { progress, _, line ->
                    onProgress(progress, line)
                }
                if (!outputMp4.exists() || outputMp4.length() == 0L) {
                    throw IllegalStateException("Merged MP4 output file was empty.")
                }
                outputMp4
            }.onFailure { err ->
                AppLogger.w(TAG, "MP4 download (${attempt.client}, ipv4=${attempt.forceIpv4}, ua=${attempt.desktopUa}) failed: ${err.message}")
            }
            if (result.isSuccess) {
                val file = result.getOrThrow()
                AppLogger.i(TAG, "MP4 download complete (${file.length() / (1024 * 1024)} MB, client=${attempt.client}, ipv4=${attempt.forceIpv4})")
                return@withContext file
            }
            lastError = result.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Failed to download <=${h}p MP4.")
    }

    fun cancelProcess(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
        activeNativeProcesses.remove(processId)?.let { proc ->
            runCatching { proc.destroyForcibly() }
        }
    }
}
