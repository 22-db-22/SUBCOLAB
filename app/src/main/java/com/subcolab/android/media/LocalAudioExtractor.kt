package com.subcolab.android.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import com.subcolab.android.util.AppLogger
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ExtractedLocalAudio(
    val audioFile: File,
    val displayTitle: String,
    val titleSlug: String,
)

/**
 * Extracts a compact audio track from any user-picked device/Drive video URI
 * or an already-downloaded local MP4 file without uploading a large video to Colab.
 */
object LocalAudioExtractor {

    private const val TAG = "LocalAudioExtractor"
    private const val MAX_FAST_MUX_BYTES = 2_500_000L
    private const val COMPACT_SOURCE_THRESHOLD_BYTES = 1_500_000L

    fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)?.trim()
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Local_Video"
    }

    fun buildTitleSlug(displayName: String): String {
        val noExt = displayName.substringBeforeLast('.').ifBlank { displayName }
        val cleaned = noExt.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        return cleaned.take(72).ifBlank { "SUBCOLAB_Local_Video" }
    }

    /**
     * Extracts ultra-compact 16 kHz mono 24 kbps AAC audio from a single video chunk (~105 KB per 35s chunk).
     */
    fun extractChunkAudio(
        context: Context,
        chunkFile: File,
        destAudioFile: File,
    ): File? {
        destAudioFile.parentFile?.mkdirs()
        if (destAudioFile.exists()) destAudioFile.delete()
        val ok = runCatching {
            val proc = StreamExtractor.startFfmpegProcess(
                context,
                listOf(
                    "-nostdin", "-y",
                    "-err_detect", "ignore_err",
                    "-i", chunkFile.absolutePath,
                    "-vn", "-sn", "-dn",
                    "-ac", "1", "-ar", "16000",
                    "-c:a", "aac", "-b:a", "24k",
                    destAudioFile.absolutePath,
                ),
            )
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0 && destAudioFile.exists() && destAudioFile.length() > 0L
        }.getOrDefault(false)
        if (ok) return destAudioFile
        return runCatching {
            tryFastMuxFromFile(
                chunkFile,
                destAudioFile.parentFile ?: chunkFile.parentFile!!,
                baseName = destAudioFile.nameWithoutExtension,
            )
        }.getOrNull()
    }

    /**
     * Fast path for local File (e.g. already-downloaded MP4 or progressive relay video).
     * Compresses to 16 kHz mono 24 kbps AAC when >1.5 MB so Colab uploads take seconds instead of ~45s.
     */
    fun extractAudioFromFile(
        context: Context,
        inputFile: File,
        workDir: File,
    ): File {
        workDir.mkdirs()

        val uniqueBase = "subcolab_extracted_${UUID.randomUUID().toString().take(8)}"

        // If the input is already compact, fast-mux without re-encoding
        if (inputFile.length() <= COMPACT_SOURCE_THRESHOLD_BYTES) {
            val fastMuxed = runCatching { tryFastMuxFromFile(inputFile, workDir, baseName = uniqueBase) }.getOrNull()
            if (fastMuxed != null && fastMuxed.exists() && fastMuxed.length() in 1L..MAX_FAST_MUX_BYTES) {
                AppLogger.i(TAG, "Extracted compact audio via MediaMuxer: ${fastMuxed.name} (${fastMuxed.length() / 1024} KB)")
                return fastMuxed
            }
            fastMuxed?.delete()
        }

        val ffmpegOut = File(workDir, "$uniqueBase.m4a")
        val ok = runCatching {
            val proc = StreamExtractor.startFfmpegProcess(
                context,
                listOf(
                    "-nostdin", "-y",
                    "-err_detect", "ignore_err",
                    "-i", inputFile.absolutePath,
                    "-vn", "-sn", "-dn",
                    "-af", "aresample=async=1:first_pts=0",
                    "-ac", "1", "-ar", "16000",
                    "-c:a", "aac", "-b:a", "24k",
                    ffmpegOut.absolutePath,
                ),
            )
            proc.inputStream.bufferedReader().readText()
            proc.waitFor() == 0 && ffmpegOut.exists() && ffmpegOut.length() > 0L
        }.getOrDefault(false)
        if (ok) {
            AppLogger.i(TAG, "Extracted speech-optimized mono AAC via libffmpeg.so (${ffmpegOut.length() / 1024} KB)")
            return ffmpegOut
        }
        ffmpegOut.delete()

        val fallbackMux = runCatching { tryFastMuxFromFile(inputFile, workDir, baseName = uniqueBase) }.getOrNull()
        if (fallbackMux != null && fallbackMux.exists() && fallbackMux.length() > 0L) {
            AppLogger.i(TAG, "Extracted audio from file via MediaMuxer fallback: ${fallbackMux.name} (${fallbackMux.length() / 1024} KB)")
            return fallbackMux
        }
        return inputFile
    }

    fun extractAudioForColab(
        context: Context,
        uri: Uri,
        workDir: File,
        onStatus: (String) -> Unit = {},
    ): ExtractedLocalAudio {
        workDir.mkdirs()
        workDir.listFiles()?.filter { it.name.startsWith("subcolab_local_") }?.forEach { it.delete() }

        val displayName = resolveDisplayName(context, uri)
        val slug = buildTitleSlug(displayName)
        onStatus("Inspecting audio track in $displayName...")

        val fastMuxed = runCatching {
            tryFastMuxFromUri(context, uri, workDir)
        }.onFailure { err ->
            AppLogger.w(TAG, "Fast MediaMuxer audio copy failed (will try ffmpeg fallback)", err)
        }.getOrNull()

        if (fastMuxed != null && fastMuxed.exists() && fastMuxed.length() > 0L) {
            if (fastMuxed.length() <= MAX_FAST_MUX_BYTES) {
                AppLogger.i(TAG, "Extracted local audio via MediaMuxer: ${fastMuxed.name} (${fastMuxed.length() / 1024} KB)")
                return ExtractedLocalAudio(fastMuxed, displayName, slug)
            }
            // Compress the fast-muxed audio track directly (avoids copying multi-hundred-MB video)
            onStatus("Compressing audio (${fastMuxed.length() / 1024} KB -> ~16kbps mono)...")
            val compressed = File(workDir, "subcolab_local_audio_compact.m4a")
            val ok = runCatching {
                val proc = StreamExtractor.startFfmpegProcess(
                    context,
                    listOf(
                        "-nostdin", "-y",
                        "-err_detect", "ignore_err",
                        "-i", fastMuxed.absolutePath,
                        "-vn", "-sn", "-dn",
                        "-af", "aresample=async=1:first_pts=0",
                        "-ac", "1", "-ar", "16000",
                        "-c:a", "aac", "-b:a", "24k",
                        compressed.absolutePath,
                    ),
                )
                proc.inputStream.bufferedReader().readText()
                proc.waitFor() == 0 && compressed.exists() && compressed.length() > 0L
            }.getOrDefault(false)
            if (ok) {
                fastMuxed.delete()
                AppLogger.i(TAG, "Compressed local audio via libffmpeg.so (${compressed.length() / 1024} KB)")
                return ExtractedLocalAudio(compressed, displayName, slug)
            }
            if (fastMuxed.length() <= 45L * 1024L * 1024L) {
                return ExtractedLocalAudio(fastMuxed, displayName, slug)
            }
            fastMuxed.delete()
        }

        onStatus("Compressing audio track for fast upload...")
        val tempVideo = File(workDir, "subcolab_local_src.bin")
        val ffmpegOut = File(workDir, "subcolab_local_audio.m4a")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempVideo.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Cannot read selected video file.")

            val ffmpegOk = runCatching {
                val proc = StreamExtractor.startFfmpegProcess(
                    context,
                    listOf(
                        "-nostdin", "-y",
                        "-err_detect", "ignore_err",
                        "-i", tempVideo.absolutePath,
                        "-vn", "-sn", "-dn",
                        "-af", "aresample=async=1:first_pts=0",
                        "-ac", "1", "-ar", "16000",
                        "-c:a", "aac", "-b:a", "24k",
                        ffmpegOut.absolutePath,
                    ),
                )
                proc.inputStream.bufferedReader().readText()
                proc.waitFor() == 0 && ffmpegOut.exists() && ffmpegOut.length() > 0L
            }.getOrDefault(false)
            if (ffmpegOk) {
                tempVideo.delete()
                AppLogger.i(TAG, "Extracted compressed mono AAC via libffmpeg.so (${ffmpegOut.length() / 1024} KB)")
                return ExtractedLocalAudio(ffmpegOut, displayName, slug)
            }

            val secondMux = runCatching { tryFastMuxFromUri(context, uri, workDir) }.getOrNull()
            if (secondMux != null && secondMux.exists() && secondMux.length() > 0L) {
                tempVideo.delete()
                return ExtractedLocalAudio(secondMux, displayName, slug)
            }
            if (tempVideo.exists() && tempVideo.length() > 0L) {
                return ExtractedLocalAudio(tempVideo, displayName, slug)
            }
            throw IllegalStateException("No audio track found in $displayName")
        } catch (t: Throwable) {
            tempVideo.delete()
            ffmpegOut.delete()
            throw t
        }
    }

    private fun tryFastMuxFromFile(
        file: File,
        workDir: File,
        baseName: String = "subcolab_extracted_audio",
    ): File? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            muxFirstAudioTrack(extractor, workDir, baseName)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun tryFastMuxFromUri(context: Context, uri: Uri, workDir: File): File? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            muxFirstAudioTrack(extractor, workDir, "subcolab_local_audio")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun muxFirstAudioTrack(extractor: MediaExtractor, workDir: File, baseName: String): File? {
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        var mime = ""

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val m = fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
            if (m.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = fmt
                mime = m
                break
            }
        }
        if (audioTrackIndex < 0 || audioFormat == null) return null

        val (muxFormat, ext) = when {
            mime == "audio/mp4a-latm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
            mime == "audio/opus" || mime == "audio/vorbis" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
            else -> return null
        }

        val outFile = File(workDir, "$baseName.$ext")
        if (outFile.exists()) outFile.delete()

        val muxer = MediaMuxer(outFile.absolutePath, muxFormat)
        var muxerStarted = false
        try {
            val writeTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true

            extractor.selectTrack(audioTrackIndex)
            val maxInputSize = runCatching {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(256 * 1024).coerceIn(64 * 1024, 1024 * 1024)

            val buffer = ByteBuffer.allocate(maxInputSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(writeTrackIndex, buffer, info)
                extractor.advance()
            }
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
        return outFile.takeIf { it.exists() && it.length() > 0L }
    }
}
