package com.openscribe.android.util

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UrlUnwrapper {

    private val URL_REGEX = Regex("""https?://[^\s<>"'\)\]]+""")
    private val SHORTS_REGEX = Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{6,})""")
    private val LIVE_REGEX = Regex("""youtube\.com/live/([A-Za-z0-9_-]{6,})""")

    /**
     * Extracts the first HTTP(S) URL from arbitrary shared text (e.g. YouTube Share sheet text)
     * and unwraps redirect wrappers (`google.com/goto?url=...`, `l.instagram.com`, etc.)
     * as well as normalizing `youtube.com/shorts/...` links.
     */
    fun extractAndUnwrap(rawInput: String?): String {
        if (rawInput.isNullOrBlank()) return ""
        val trimmed = rawInput.trim()
        val extracted = URL_REGEX.find(trimmed)?.value ?: trimmed
        return unwrap(extracted)
    }

    fun unwrap(rawUrl: String): String {
        var current = rawUrl.trim()
        if (current.isEmpty()) return ""

        // Unwrap nested redirect URLs up to 3 hops
        repeat(3) {
            val unwrapped = unwrapSingleHop(current) ?: return@repeat
            if (unwrapped == current) return@repeat
            current = unwrapped
        }

        // Normalize YouTube Shorts -> standard watch?v=... URL
        SHORTS_REGEX.find(current)?.let { match ->
            val id = match.groupValues[1]
            return "https://www.youtube.com/watch?v=$id"
        }

        // Normalize YouTube Live URLs -> standard watch?v=... URL
        LIVE_REGEX.find(current)?.let { match ->
            val id = match.groupValues[1]
            return "https://www.youtube.com/watch?v=$id"
        }

        return current
    }

    private fun unwrapSingleHop(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path?.lowercase() ?: ""

        val isRedirectWrapper =
            (host.contains("google.") && (path.startsWith("/goto") || path.startsWith("/url"))) ||
                host == "l.instagram.com" ||
                host == "l.facebook.com" ||
                host == "lm.facebook.com" ||
                (host == "www.youtube.com" && path == "/redirect")

        if (!isRedirectWrapper) return null

        val query = uri.rawQuery ?: return null
        val params = query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else pair.substring(0, idx) to URLDecoder.decode(
                pair.substring(idx + 1),
                StandardCharsets.UTF_8.name(),
            )
        }.toMap()

        val target = params["url"] ?: params["q"] ?: params["u"]
        return target?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    fun isValidVideoUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return !uri.host.isNullOrBlank()
    }
}
