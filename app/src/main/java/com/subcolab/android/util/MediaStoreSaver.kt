package com.subcolab.android.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class SavedMediaResult(
    val displayPath: String,
    val uriString: String?,
)

/**
 * Saves MP4 and SRT/ASS/VTT files into per-video subfolders under Downloads/SUBCOLAB/<shortTitle>/
 * using scoped MediaStore APIs on Android 10+ (API 29+) — requiring ZERO MANAGE_EXTERNAL_STORAGE.
 */
object MediaStoreSaver {

    private const val ROOT_FOLDER_NAME = "SUBCOLAB"
    private const val ROOT_RELATIVE = "Download/$ROOT_FOLDER_NAME"

    fun buildShortFolderName(rawSlug: String): String {
        val cleaned = rawSlug
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .replace(Regex("_+"), "_")
        if (cleaned.isBlank()) return "SUBCOLAB_Video"
        if (cleaned.length <= 40) return cleaned
        val cut = cleaned.take(40).substringBeforeLast('_')
        return (if (cut.length >= 12) cut else cleaned.take(40)).trim('_').ifBlank { "SUBCOLAB_Video" }
    }

    private fun resolveRelativePath(subfolder: String): String {
        val cleanSub = subfolder.trim().trim('/').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return if (cleanSub.isBlank()) ROOT_RELATIVE else "$ROOT_RELATIVE/$cleanSub"
    }

    private fun resolveDisplayPath(subfolder: String, fileName: String): String {
        val cleanSub = subfolder.trim().trim('/').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return if (cleanSub.isBlank()) {
            "Downloads/$ROOT_FOLDER_NAME/$fileName"
        } else {
            "Downloads/$ROOT_FOLDER_NAME/$cleanSub/$fileName"
        }
    }

    fun saveTextFile(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String,
        subfolder: String = "",
    ): SavedMediaResult? {
        if (content.isBlank()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, mimeType, subfolder) { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            }
        } else {
            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ROOT_FOLDER_NAME,
            )
            val targetDir = if (subfolder.isNotBlank()) File(baseDir, subfolder) else baseDir
            targetDir.mkdirs()
            val out = File(targetDir, fileName)
            out.writeText(content, Charsets.UTF_8)
            SavedMediaResult(resolveDisplayPath(subfolder, fileName), Uri.fromFile(out).toString())
        }
    }

    fun saveBinaryFile(
        context: Context,
        sourceFile: File,
        targetFileName: String,
        mimeType: String = "video/mp4",
        subfolder: String = "",
    ): SavedMediaResult? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, targetFileName, mimeType, subfolder) { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } else {
            val baseDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ROOT_FOLDER_NAME,
            )
            val targetDir = if (subfolder.isNotBlank()) File(baseDir, subfolder) else baseDir
            targetDir.mkdirs()
            val out = File(targetDir, targetFileName)
            sourceFile.copyTo(out, overwrite = true)
            SavedMediaResult(resolveDisplayPath(subfolder, targetFileName), Uri.fromFile(out).toString())
        }
    }

    fun deleteSavedEntry(context: Context, uriString: String?, displayPath: String? = null) {
        if (!uriString.isNullOrBlank()) {
            runCatching {
                val uri = Uri.parse(uriString)
                when (uri.scheme) {
                    "content" -> context.contentResolver.delete(uri, null, null)
                    "file" -> uri.path?.let { File(it).delete() }
                    else -> Unit
                }
            }
        }
        if (!displayPath.isNullOrBlank() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runCatching {
                val rel = displayPath.removePrefix("Downloads/")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    rel,
                )
                if (file.exists()) file.delete()
            }
        }
    }

    fun deleteSubfolderIfEmpty(subfolder: String) {
        if (subfolder.isBlank() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        runCatching {
            val dir = File(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ROOT_FOLDER_NAME),
                subfolder,
            )
            if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) {
                dir.delete()
            }
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        subfolder: String,
        writer: (java.io.OutputStream) -> Unit,
    ): SavedMediaResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val relPath = resolveRelativePath(subfolder)

        // Remove any previous copy with the same display name in this subfolder
        val existingSelection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            existingSelection,
            arrayOf(fileName, "$relPath/"),
        )

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                writer(out)
            } ?: throw IllegalStateException("Failed to open output stream for $fileName")

            val doneValues = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, doneValues, null, null)
            SavedMediaResult(resolveDisplayPath(subfolder, fileName), uri.toString())
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }
}
