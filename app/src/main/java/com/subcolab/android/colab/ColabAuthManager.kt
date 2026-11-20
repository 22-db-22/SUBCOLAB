package com.subcolab.android.colab

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

@Serializable
data class StoredOAuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long,
    val userEmail: String? = null,
    val clientIdUsed: String? = null,
    val clientSecretUsed: String? = null,
)

data class AuthUiState(
    val isSignedIn: Boolean = false,
    val userEmail: String? = null,
    val isSigningIn: Boolean = false,
    val awaitingManualCode: Boolean = false,
    val error: String? = null,
)

/**
 * Implements Google Colab OAuth2 matching googlecolab/google-colab-cli (`auth.py` + `oauth_config.json`):
 *   - Built-in client ID (`764086051850-...`) uses `REMOTE_REDIRECT_URI`
 *     (`https://sdk.cloud.google.com/applicationdefaultauthcode.html`) + `token_usage=remote`
 *     so it works cleanly out of the box without `redirect_uri_mismatch`.
 *   - Optional Custom OAuth Client ID & Secret (Desktop App client) can also use automatic
 *     `http://localhost:<ephemeral_port>` loopback via Chrome Custom Tabs.
 */
class ColabAuthManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    companion object {
        // Default OAuth config from googlecolab/google-colab-cli (src/colab_cli/oauth_config.json)
        const val CLIENT_ID = "764086051850-6qr4p6gpi6hn506pt8ejuq83di341hur.apps.googleusercontent.com"
        const val CLIENT_SECRET = "d-FL95Q19q7MQmFpd7hHD0Ty"
        const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
        const val TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val REMOTE_REDIRECT_URI = "https://sdk.cloud.google.com/applicationdefaultauthcode.html"

        val PUBLIC_SCOPES = listOf(
            "openid",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/colaboratory",
            "https://www.googleapis.com/auth/drive.file",
        )

        private const val PREFS_NAME = "subcolab_colab_auth_encrypted"
        private const val KEY_TOKENS = "oauth_tokens_json"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _authState = MutableStateFlow(loadInitialAuthState())
    val authState: StateFlow<AuthUiState> = _authState.asStateFlow()

    private var pendingManualVerifier: String? = null
    private var pendingManualClientId: String = CLIENT_ID
    private var pendingManualClientSecret: String = CLIENT_SECRET

    fun isSignedIn(): Boolean = _authState.value.isSignedIn || loadTokens() != null

    private fun resolveClientPair(
        customClientId: String,
        customClientSecret: String,
    ): Pair<String, String> {
        val cid = customClientId.trim()
        return if (cid.isNotEmpty()) {
            cid to customClientSecret.trim()
        } else {
            CLIENT_ID to CLIENT_SECRET
        }
    }

    private fun loadInitialAuthState(): AuthUiState {
        val tokens = loadTokens()
        return AuthUiState(
            isSignedIn = tokens != null,
            userEmail = tokens?.userEmail,
        )
    }

    private fun loadTokens(): StoredOAuthTokens? {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return null
        return runCatching { json.decodeFromString<StoredOAuthTokens>(raw) }.getOrNull()
    }

    private fun saveTokens(tokens: StoredOAuthTokens) {
        prefs.edit().putString(KEY_TOKENS, json.encodeToString(tokens)).apply()
        _authState.value = AuthUiState(
            isSignedIn = true,
            userEmail = tokens.userEmail,
        )
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKENS).apply()
        pendingManualVerifier = null
        _authState.value = AuthUiState(isSignedIn = false)
    }

    /**
     * Primary out-of-the-box OAuth flow matching googlecolab/google-colab-cli (`auth.py`):
     * Opens Google consent in Chrome Custom Tabs with `REMOTE_REDIRECT_URI`
     * (`https://sdk.cloud.google.com/applicationdefaultauthcode.html`) + `token_usage=remote`,
     * which avoids `redirect_uri_mismatch` on the built-in client ID.
     */
    fun launchCopyPasteFlow(
        activityContext: Context,
        customClientId: String = "",
        customClientSecret: String = "",
    ) {
        val (cid, csecret) = resolveClientPair(customClientId, customClientSecret)
        val verifier = generateCodeVerifier()
        pendingManualVerifier = verifier
        pendingManualClientId = cid
        pendingManualClientSecret = csecret
        val challenge = generateCodeChallenge(verifier)
        val authUrl = buildAuthUrl(
            clientId = cid,
            redirectUri = REMOTE_REDIRECT_URI,
            codeChallenge = challenge,
            state = "subcolab",
            includeRemoteUsage = cid == CLIENT_ID,
        )
        _authState.value = _authState.value.copy(
            awaitingManualCode = true,
            error = null,
        )
        launchBrowser(activityContext, authUrl)
    }

    suspend fun completeCopyPasteFlow(rawCode: String): Result<StoredOAuthTokens> =
        withContext(Dispatchers.IO) {
            val code = rawCode.trim()
            if (code.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Authorization code cannot be empty."))
            }
            _authState.value = _authState.value.copy(isSigningIn = true, error = null)
            return@withContext try {
                val tokens = exchangeCodeForTokens(
                    clientId = pendingManualClientId,
                    clientSecret = pendingManualClientSecret,
                    code = code,
                    redirectUri = REMOTE_REDIRECT_URI,
                    codeVerifier = pendingManualVerifier,
                )
                pendingManualVerifier = null
                saveTokens(tokens)
                Result.success(tokens)
            } catch (t: Throwable) {
                _authState.value = _authState.value.copy(
                    isSigningIn = false,
                    error = t.message ?: "Failed to exchange authorization code",
                )
                Result.failure(t)
            }
        }

    fun dismissCopyPastePrompt() {
        pendingManualVerifier = null
        _authState.value = _authState.value.copy(awaitingManualCode = false)
    }

    /**
     * One-tap localhost loopback OAuth flow for users who configure a Custom Desktop OAuth Client ID.
     * Note: The built-in Google Cloud SDK client ID only guarantees `sdk.cloud.google.com` remote redirect
     * without `redirect_uri_mismatch`, so loopback requires a Custom Desktop OAuth Client ID.
     */
    suspend fun signInWithLoopback(
        activityContext: Context,
        customClientId: String = "",
        customClientSecret: String = "",
    ): Result<StoredOAuthTokens> =
        withContext(Dispatchers.IO) {
            val (cid, csecret) = resolveClientPair(customClientId, customClientSecret)
            if (cid == CLIENT_ID) {
                val err = IllegalStateException(
                    "One-tap loopback requires a Custom Desktop OAuth Client ID. Use the standard Sign in with Google flow above (works out of the box)."
                )
                _authState.value = _authState.value.copy(isSigningIn = false, error = err.message)
                return@withContext Result.failure(err)
            }
            _authState.value = _authState.value.copy(isSigningIn = true, error = null)
            var serverSocket: ServerSocket? = null
            try {
                serverSocket = ServerSocket(0).apply {
                    soTimeout = 180_000 // 3 minutes for user sign-in
                }
                val port = serverSocket.localPort
                val redirectUri = "http://localhost:$port"
                val verifier = generateCodeVerifier()
                val challenge = generateCodeChallenge(verifier)
                val stateToken = generateCodeVerifier().take(24)

                val authUrl = buildAuthUrl(
                    clientId = cid,
                    redirectUri = redirectUri,
                    codeChallenge = challenge,
                    state = stateToken,
                    includeRemoteUsage = false,
                )

                withContext(Dispatchers.Main) {
                    launchBrowser(activityContext, authUrl)
                }

                val code = waitForLoopbackCode(serverSocket, stateToken)
                    ?: throw IllegalStateException("Sign-in cancelled or timed out waiting for OAuth callback.")

                val tokens = exchangeCodeForTokens(
                    clientId = cid,
                    clientSecret = csecret,
                    code = code,
                    redirectUri = redirectUri,
                    codeVerifier = verifier,
                )
                saveTokens(tokens)
                Result.success(tokens)
            } catch (t: Throwable) {
                _authState.value = _authState.value.copy(
                    isSigningIn = false,
                    error = t.message ?: "OAuth sign-in failed",
                )
                Result.failure(t)
            } finally {
                runCatching { serverSocket?.close() }
            }
        }

    /**
     * Returns a valid access token, automatically refreshing via refresh_token if expired or near expiry.
     */
    suspend fun getValidAccessToken(): String = withContext(Dispatchers.IO) {
        val current = loadTokens()
            ?: throw IllegalStateException("Not signed in to Google Colab. Sign in from Settings first.")
        val now = System.currentTimeMillis()
        if (current.expiresAtMs - now > 120_000L) {
            return@withContext current.accessToken
        }
        val refreshToken = current.refreshToken
            ?: throw IllegalStateException("Session expired and no refresh token stored. Please sign in again.")
        val refreshed = refreshAccessToken(
            refreshToken = refreshToken,
            existingEmail = current.userEmail,
            clientId = current.clientIdUsed ?: CLIENT_ID,
            clientSecret = current.clientSecretUsed ?: CLIENT_SECRET,
        )
        saveTokens(refreshed)
        refreshed.accessToken
    }

    private fun launchBrowser(activityContext: Context, url: String) {
        val uri = Uri.parse(url)
        val customTabs = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        runCatching {
            customTabs.launchUrl(activityContext, uri)
        }.onFailure {
            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(fallbackIntent)
        }
    }

    private fun buildAuthUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        state: String,
        includeRemoteUsage: Boolean,
    ): String {
        val params = linkedMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to PUBLIC_SCOPES.joinToString(" "),
            "access_type" to "offline",
            "prompt" to "consent",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        if (includeRemoteUsage) {
            params["token_usage"] = "remote"
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTH_URI?$query"
    }

    private fun waitForLoopbackCode(serverSocket: ServerSocket, expectedState: String): String? {
        val socket = serverSocket.accept()
        socket.use { client ->
            if (!client.inetAddress.isLoopbackAddress) {
                throw IllegalStateException("Rejected non-loopback OAuth callback connection.")
            }
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return null
            // Consume remaining headers quickly
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val pathPart = requestLine.split(" ").getOrNull(1) ?: ""
            val queryPart = pathPart.substringAfter("?", "")
            val params = queryPart.split("&").mapNotNull { kv ->
                val eq = kv.indexOf('=')
                if (eq <= 0) null
                else URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8.name())
            }.toMap()

            val error = params["error"]
            val code = params["code"]
            val state = params["state"]

            val html = if (error == null && !code.isNullOrBlank() && (state == null || state == expectedState)) {
                """
                <!doctype html><html><body style="background:#0B0F17;color:#F8FAFC;font-family:sans-serif;padding:40px;text-align:center">
                <h2 style="color:#FBBF24">SUBCOLAB Signed In</h2>
                <p>Google Colab authorization succeeded. You can close this tab and return to SUBCOLAB.</p>
                </body></html>
                """.trimIndent()
            } else {
                """
                <!doctype html><html><body style="background:#0B0F17;color:#F8FAFC;font-family:sans-serif;padding:40px;text-align:center">
                <h2 style="color:#F87171">Authorization Not Completed</h2>
                <p>${error ?: "Missing authorization code."} Return to SUBCOLAB to retry.</p>
                </body></html>
                """.trimIndent()
            }

            val responseBytes = html.toByteArray(Charsets.UTF_8)
            val httpResp = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(httpResp.toByteArray(Charsets.UTF_8))
                write(responseBytes)
                flush()
            }
            if (error != null) {
                throw IllegalStateException("Google sign-in error: $error")
            }
            return code
        }
    }

    private fun exchangeCodeForTokens(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        codeVerifier: String?,
    ): StoredOAuthTokens {
        val bodyBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
        if (clientSecret.isNotBlank()) {
            bodyBuilder.add("client_secret", clientSecret)
        }
        if (!codeVerifier.isNullOrBlank()) {
            bodyBuilder.add("code_verifier", codeVerifier)
        }

        val req = Request.Builder()
            .url(TOKEN_URI)
            .post(bodyBuilder.build())
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Token exchange failed (${resp.code}): $raw")
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing access_token in OAuth response")
            val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            val email = fetchUserEmail(accessToken)
            return StoredOAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                userEmail = email,
                clientIdUsed = clientId,
                clientSecretUsed = clientSecret.ifBlank { null },
            )
        }
    }

    private fun refreshAccessToken(
        refreshToken: String,
        existingEmail: String?,
        clientId: String,
        clientSecret: String,
    ): StoredOAuthTokens {
        val bodyBuilder = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
        if (clientSecret.isNotBlank()) {
            bodyBuilder.add("client_secret", clientSecret)
        }

        val req = Request.Builder()
            .url(TOKEN_URI)
            .post(bodyBuilder.build())
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Token refresh failed (${resp.code}): $raw")
            }
            val obj = json.parseToJsonElement(raw).jsonObject
            val accessToken = obj["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("Missing access_token in refresh response")
            val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
            return StoredOAuthTokens(
                accessToken = accessToken,
                refreshToken = obj["refresh_token"]?.jsonPrimitive?.content ?: refreshToken,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
                userEmail = existingEmail ?: fetchUserEmail(accessToken),
                clientIdUsed = clientId,
                clientSecretUsed = clientSecret.ifBlank { null },
            )
        }
    }

    private fun fetchUserEmail(accessToken: String): String? {
        val req = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v2/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string().orEmpty()
                json.parseToJsonElement(raw).jsonObject["email"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
