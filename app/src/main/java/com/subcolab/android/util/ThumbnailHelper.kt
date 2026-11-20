package com.subcolab.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Resolves, downloads, or extracts the 1st video frame as a lightweight cached JPEG thumbnail
 * for SavedPack cards in the Downloads tab. Zero extra Gradle dependencies required.
 */
object ThumbnailHelper {

    private const val TAG = "ThumbnailHelper"
    private const val MAX_THUMB_WIDTH = 360

    private val thumbHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun thumbsDir(context: Context): File =
        File(context.filesDir, "thumbs").apply { mkdirs() }

    fun thumbFileFor(context: Context, packId: String): File {
        val safeId = packId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "pack" }
        return File(thumbsDir(context), "$safeId.jpg")
    }

    fun thumbPathIfExists(context: Context, packId: String, storedPath: String?): String? {
        if (!storedPath.isNullOrBlank()) {
            val f = File(storedPath)
            if (f.exists() && f.length() > 0L) return f.absolutePath
        }
        val standard = thumbFileFor(context, packId)
        return standard.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    fun deleteThumbnail(context: Context, packId: String, storedPath: String?) {
        if (!storedPath.isNullOrBlank()) {
            runCatching { File(storedPath).delete() }
        }
        runCatching { thumbFileFor(context, packId).delete() }
    }

    /**
     * Ensures a thumbnail JPEG exists on disk for the pack:
     * 1) Already cached on disk
     * 2) Direct remote thumbnailUrl (from yt-dlp)
     * 3) YouTube hqdefault or HTML og:image when video isn't downloaded locally
     * 4) 1st video frame extracted via MediaMetadataRetriever from local MP4 / content URI
     */
    fun resolveOrCreateThumbnail(
        context: Context,
        packId: String,
        storedPath: String? = null,
        thumbnailUrl: String? = null,
        sourceUrl: String = "",
        isLocalVideo: Boolean = false,
        mp4UriString: String? = null,
        internalVideoPath: String? = null,
    ): String? {
        thumbPathIfExists(context, packId, storedPath)?.let { return it }

        val targetFile = thumbFileFor(context, packId)

        // 1. Remote thumbnail URL from yt-dlp JSON
        if (!thumbnailUrl.isNullOrBlank() && downloadAndSaveBitmap(thumbnailUrl, targetFile)) {
            return targetFile.absolutePath
        }

        // 2. Local video / downloaded MP4 -> extract 1st frame
        val videoSourceUri = mp4UriString?.takeIf { it.isNotBlank() }
            ?: if (isLocalVideo) sourceUrl.takeIf { it.isNotBlank() } else null
        if (extractFirstFrameToFile(context, internalVideoPath, videoSourceUri, targetFile)) {
            return targetFile.absolutePath
        }

        // 3. Fallback remote thumbnail for URL-only / early-cancelled packs (YouTube or og:image)
        if (!isLocalVideo && UrlUnwrapper.isValidVideoUrl(sourceUrl)) {
            deriveYoutubeThumbnailUrl(sourceUrl)?.let { ytThumb ->
                if (downloadAndSaveBitmap(ytThumb, targetFile)) {
                    return targetFile.absolutePath
                }
            }
            fetchOgImageUrl(sourceUrl)?.let { ogUrl ->
                if (downloadAndSaveBitmap(ogUrl, targetFile)) {
                    return targetFile.absolutePath
                }
            }
        }

        return null
    }

    private fun downloadAndSaveBitmap(imageUrl: String, destFile: File): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url(imageUrl)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .build()
            thumbHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val bytes = resp.body?.bytes() ?: return@use false
                if (bytes.isEmpty()) return@use false
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use false
                saveScaledBitmap(decoded, destFile)
            }
        }.getOrDefault(false)
    }

    private fun extractFirstFrameToFile(
        context: Context,
        internalVideoPath: String?,
        videoUriString: String?,
        destFile: File,
    ): Boolean {
        val hasInternal = !internalVideoPath.isNullOrBlank() &&
            File(internalVideoPath).let { it.exists() && it.length() > 0L }
        val hasUri = !videoUriString.isNullOrBlank() &&
            (videoUriString.startsWith("content://") || videoUriString.startsWith("file://"))
        if (!hasInternal && !hasUri) return false

        val retriever = MediaMetadataRetriever()
        return try {
            if (hasInternal) {
                retriever.setDataSource(internalVideoPath)
            } else {
                retriever.setDataSource(context, Uri.parse(videoUriString))
            }
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            if (frame != null) {
                saveScaledBitmap(frame, destFile)
            } else {
                false
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "First-frame thumbnail extraction failed: ${t.message}")
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveScaledBitmap(bitmap: Bitmap, destFile: File): Boolean {
        return try {
            destFile.parentFile?.mkdirs()
            val scaled = if (bitmap.width > MAX_THUMB_WIDTH && bitmap.height > 0) {
                val targetHeight = ((bitmap.height.toFloat() / bitmap.width.toFloat()) * MAX_THUMB_WIDTH)
                    .toInt()
                    .coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, MAX_THUMB_WIDTH, targetHeight, true)
            } else {
                bitmap
            }
            FileOutputStream(destFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            destFile.exists() && destFile.length() > 0L
        } catch (_: Throwable) {
            runCatching { bitmap.recycle() }
            false
        }
    }

    private fun deriveYoutubeThumbnailUrl(url: String): String? {
        val match = Regex(
            "(?:v=|youtu\\.be/|youtube\\.com/(?:shorts|live|embed)/)([A-Za-z0-9_-]{11})",
        ).find(url) ?: return null
        val videoId = match.groupValues.getOrNull(1)?.takeIf { it.length == 11 } ?: return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun fetchOgImageUrl(pageUrl: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url(pageUrl)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .build()
            thumbHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val stream = resp.body?.byteStream() ?: return@use null
                val buf = ByteArray(48 * 1024)
                val read = stream.read(buf)
                if (read <= 0) return@use null
                val html = String(buf, 0, read, Charsets.UTF_8)
                val ogRegex = Regex(
                    """<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']""",
                    RegexOption.IGNORE_CASE,
                )
                val revRegex = Regex(
                    """<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|twitter:image)["']""",
                    RegexOption.IGNORE_CASE,
                )
                val candidate = ogRegex.find(html)?.groupValues?.getOrNull(1)
                    ?: revRegex.find(html)?.groupValues?.getOrNull(1)
                candidate?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }
        }.getOrNull()
    }
}
