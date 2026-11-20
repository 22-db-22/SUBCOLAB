package com.subcolab.android.util

import com.subcolab.android.model.ChunkRange
import com.subcolab.android.model.SubtitleCue
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed class WorkerEvent {
    data class Status(val message: String) : WorkerEvent()
    data class Title(val slug: String) : WorkerEvent()
    data class OrigLang(val langCode: String) : WorkerEvent()
    data class CueArrived(val cue: SubtitleCue, val totalCount: Int) : WorkerEvent()
    data class OrigCueArrived(val cue: SubtitleCue, val totalCount: Int) : WorkerEvent()
    data class ChunkCompleted(val range: ChunkRange) : WorkerEvent()
    data class RemoteChunkReady(
        val index: Int,
        val startSec: Double,
        val endSec: Double,
        val canUseMpegTs: Boolean,
        val remotePath: String,
    ) : WorkerEvent()
    data class RemoteChunksDone(
        val totalCount: Int,
        val totalDurationSec: Double,
    ) : WorkerEvent()
    data object WorkerReady : WorkerEvent()
    data object BotBlocked : WorkerEvent()
    data object Heartbeat : WorkerEvent()
    data object AllDone : WorkerEvent()
}

/**
 * Stateful parser and exporter for SUBCOLAB's colab_worker.py streaming protocol.
 * Tracks BOTH English-translated subtitles and original-language subtitles, keeps cues
 * sorted by timestamp, and writes live SRT files inside private filesDir.
 */
class SubtitleManager {

    private val cuesByStartMs = ConcurrentHashMap<Long, SubtitleCue>()
    private val origCuesByStartMs = ConcurrentHashMap<Long, SubtitleCue>()
    private val completedChunks = mutableListOf<ChunkRange>()

    private var inEnBlock = false
    private var inOrigBlock = false
    private var currentStartMs: Long = 0L
    private val blockLines = mutableListOf<String>()

    @Volatile
    var latestTitleSlug: String? = null
        private set

    @Volatile
    var detectedOrigLang: String? = null
        private set

    @Synchronized
    fun reset() {
        cuesByStartMs.clear()
        origCuesByStartMs.clear()
        completedChunks.clear()
        inEnBlock = false
        inOrigBlock = false
        currentStartMs = 0L
        blockLines.clear()
        latestTitleSlug = null
        detectedOrigLang = null
    }

    @Synchronized
    fun processLine(rawLine: String): WorkerEvent? {
        val line = rawLine.trimEnd('\r', '\n')
        return when {
            line == "@@SUBCOLAB_HEARTBEAT@@" -> WorkerEvent.Heartbeat
            line == "@@SUBCOLAB_WORKER_READY@@" -> WorkerEvent.WorkerReady
            line == "@@SUBCOLAB_BOT_BLOCKED@@" -> WorkerEvent.BotBlocked
            line == "@@SUBCOLAB_ALL_DONE@@" -> WorkerEvent.AllDone
            line.startsWith("@@SUBCOLAB_STATUS@@") -> {
                val msg = line.removePrefix("@@SUBCOLAB_STATUS@@").trim()
                WorkerEvent.Status(msg)
            }
            line.startsWith("@@SUBCOLAB_TITLE@@") -> {
                val slug = line.removePrefix("@@SUBCOLAB_TITLE@@").trim()
                if (slug.isNotEmpty()) {
                    latestTitleSlug = slug
                    WorkerEvent.Title(slug)
                } else null
            }
            line.startsWith("@@SUBCOLAB_ORIG_LANG@@") -> {
                val code = line.removePrefix("@@SUBCOLAB_ORIG_LANG@@").trim().lowercase()
                if (code.isNotEmpty()) {
                    detectedOrigLang = code
                    WorkerEvent.OrigLang(code)
                } else null
            }
            line.startsWith("@@SUBCOLAB_CHUNK_END@@") -> {
                val parts = line.removePrefix("@@SUBCOLAB_CHUNK_END@@").trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val start = parts[0].toDoubleOrNull()
                    val end = parts[1].toDoubleOrNull()
                    if (start != null && end != null) {
                        val range = ChunkRange(start, end)
                        completedChunks.add(range)
                        return WorkerEvent.ChunkCompleted(range)
                    }
                }
                null
            }
            line.startsWith("@@SUBCOLAB_REMOTE_CHUNK@@") -> {
                val payload = line.removePrefix("@@SUBCOLAB_REMOTE_CHUNK@@").trim()
                val parts = payload.split('|', limit = 5)
                if (parts.size == 5) {
                    val idx = parts[0].trim().toIntOrNull()
                    val start = parts[1].trim().toDoubleOrNull()
                    val end = parts[2].trim().toDoubleOrNull()
                    val tsFlag = parts[3].trim().toIntOrNull()
                    val remotePath = parts[4].trim()
                    if (idx != null && start != null && end != null && remotePath.isNotBlank()) {
                        return WorkerEvent.RemoteChunkReady(
                            index = idx,
                            startSec = start,
                            endSec = end,
                            canUseMpegTs = (tsFlag ?: 0) == 1,
                            remotePath = remotePath,
                        )
                    }
                }
                null
            }
            line.startsWith("@@SUBCOLAB_REMOTE_CHUNKS_DONE@@") -> {
                val payload = line.removePrefix("@@SUBCOLAB_REMOTE_CHUNKS_DONE@@").trim()
                val parts = payload.split('|', limit = 2)
                if (parts.size == 2) {
                    val count = parts[0].trim().toIntOrNull() ?: 0
                    val dur = parts[1].trim().toDoubleOrNull() ?: 0.0
                    return WorkerEvent.RemoteChunksDone(count, dur)
                }
                null
            }
            line.startsWith("@@SUBCOLAB_SRT_BEGIN@@") -> {
                inEnBlock = true
                inOrigBlock = false
                currentStartMs = line.removePrefix("@@SUBCOLAB_SRT_BEGIN@@").trim().toLongOrNull() ?: 0L
                blockLines.clear()
                null
            }
            line == "@@SUBCOLAB_SRT_END@@" -> {
                inEnBlock = false
                val cue = parseBlock(currentStartMs, blockLines, cuesByStartMs.size + 1)
                blockLines.clear()
                if (cue != null) {
                    val normalized = insertDedupCue(cuesByStartMs, cue)
                    WorkerEvent.CueArrived(normalized, cuesByStartMs.size)
                } else null
            }
            line.startsWith("@@SUBCOLAB_ORIG_SRT_BEGIN@@") -> {
                inOrigBlock = true
                inEnBlock = false
                currentStartMs = line.removePrefix("@@SUBCOLAB_ORIG_SRT_BEGIN@@").trim().toLongOrNull() ?: 0L
                blockLines.clear()
                null
            }
            line == "@@SUBCOLAB_ORIG_SRT_END@@" -> {
                inOrigBlock = false
                val cue = parseBlock(currentStartMs, blockLines, origCuesByStartMs.size + 1)
                blockLines.clear()
                if (cue != null) {
                    val normalized = insertDedupCue(origCuesByStartMs, cue)
                    WorkerEvent.OrigCueArrived(normalized, origCuesByStartMs.size)
                } else null
            }
            inEnBlock || inOrigBlock -> {
                blockLines.add(line)
                null
            }
            else -> null
        }
    }

    private fun insertDedupCue(
        map: ConcurrentHashMap<Long, SubtitleCue>,
        cue: SubtitleCue,
    ): SubtitleCue {
        var key = cue.startMs
        while (map.containsKey(key) && map[key]?.text != cue.text) {
            key++
        }
        val normalizedCue = if (key == cue.startMs) {
            cue
        } else {
            cue.copy(startMs = key, endMs = maxOf(cue.endMs, key + 200L))
        }
        map[normalizedCue.startMs] = normalizedCue
        return normalizedCue
    }

    private fun parseBlock(hintStartMs: Long, lines: List<String>, defaultIdx: Int): SubtitleCue? {
        if (lines.size < 3) return null
        val index = lines[0].trim().toIntOrNull() ?: defaultIdx
        val timingParts = lines[1].split("-->")
        if (timingParts.size != 2) return null
        val startMs = parseSrtTimestampMs(timingParts[0].trim()) ?: hintStartMs
        val endMs = parseSrtTimestampMs(timingParts[1].trim()) ?: (startMs + 1000L)
        val text = lines.drop(2).joinToString("\n").trim()
        if (text.isEmpty()) return null
        return SubtitleCue(index = index, startMs = startMs, endMs = maxOf(endMs, startMs + 200L), text = text)
    }

    @Synchronized
    fun getSortedCues(): List<SubtitleCue> {
        return cuesByStartMs.values
            .sortedBy { it.startMs }
            .mapIndexed { idx, cue -> cue.copy(index = idx + 1) }
    }

    @Synchronized
    fun getSortedOrigCues(): List<SubtitleCue> {
        return origCuesByStartMs.values
            .sortedBy { it.startMs }
            .mapIndexed { idx, cue -> cue.copy(index = idx + 1) }
    }

    @Synchronized
    fun cueCount(): Int = cuesByStartMs.size

    @Synchronized
    fun origCueCount(): Int = origCuesByStartMs.size

    @Synchronized
    fun getCompletedChunks(): List<ChunkRange> = completedChunks.toList()

    @Synchronized
    fun isTimeTranslated(seconds: Double): Boolean {
        if (seconds < 0) return true
        if (completedChunks.any { seconds >= it.startSec - 0.5 && seconds <= it.endSec + 0.5 }) {
            return true
        }
        val targetMs = (seconds * 1000.0).toLong()
        return cuesByStartMs.values.any { cue ->
            targetMs in (cue.startMs - 5_000L)..(cue.endMs + 10_000L)
        }
    }

    @Synchronized
    fun buildSrtContent(): String = buildSrtFromCues(getSortedCues())

    @Synchronized
    fun buildOrigSrtContent(): String = buildSrtFromCues(getSortedOrigCues())

    @Synchronized
    fun buildVttContent(): String = buildVttFromCues(getSortedCues())

    @Synchronized
    fun buildOrigVttContent(): String = buildVttFromCues(getSortedOrigCues())

    @Synchronized
    fun buildAssContent(title: String = "SUBCOLAB"): String = buildAssFromCues(getSortedCues(), title)

    @Synchronized
    fun buildOrigAssContent(title: String = "SUBCOLAB Original"): String =
        buildAssFromCues(getSortedOrigCues(), title)

    private fun buildSrtFromCues(cues: List<SubtitleCue>): String {
        if (cues.isEmpty()) return ""
        return buildString {
            cues.forEach { cue ->
                append(cue.index).append('\n')
                append(formatTimestamp(cue.startMs, ',')).append(" --> ").append(formatTimestamp(cue.endMs, ',')).append('\n')
                append(cue.text).append("\n\n")
            }
        }
    }

    private fun buildVttFromCues(cues: List<SubtitleCue>): String {
        if (cues.isEmpty()) return ""
        return buildString {
            append("WEBVTT\n\n")
            cues.forEach { cue ->
                append(cue.index).append('\n')
                append(formatTimestamp(cue.startMs, '.')).append(" --> ").append(formatTimestamp(cue.endMs, '.')).append('\n')
                append(cue.text).append("\n\n")
            }
        }
    }

    private fun buildAssFromCues(cues: List<SubtitleCue>, title: String): String {
        if (cues.isEmpty()) return ""
        return buildString {
            append("[Script Info]\n")
            append("Title: ").append(title).append('\n')
            append("ScriptType: v4.00+\n")
            append("WrapStyle: 0\n")
            append("ScaledBorderAndShadow: yes\n")
            append("PlayResX: 1920\n")
            append("PlayResY: 1080\n\n")
            append("[V4+ Styles]\n")
            append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
            append("Style: Default,Arial,54,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,48,1\n\n")
            append("[Events]\n")
            append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            cues.forEach { cue ->
                val cleanText = cue.text.replace("\n", "\\N")
                append("Dialogue: 0,")
                    .append(formatAssTimestamp(cue.startMs)).append(',')
                    .append(formatAssTimestamp(cue.endMs)).append(',')
                    .append("Default,,0,0,0,,")
                    .append(cleanText).append('\n')
            }
        }
    }

    fun writeLiveSrt(targetFile: File): File {
        val content = buildSrtContent()
        writeAtomic(targetFile, content)
        return targetFile
    }

    fun writeOrigSrt(targetFile: File): File {
        val content = buildOrigSrtContent()
        writeAtomic(targetFile, content)
        return targetFile
    }

    private fun writeAtomic(targetFile: File, content: String) {
        targetFile.parentFile?.mkdirs()
        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tmpFile.writeText(content, Charsets.UTF_8)
        if (!tmpFile.renameTo(targetFile)) {
            targetFile.writeText(content, Charsets.UTF_8)
            tmpFile.delete()
        }
    }

    @Synchronized
    fun setMetadata(titleSlug: String? = null, detectedOrigLang: String? = null) {
        if (!titleSlug.isNullOrBlank()) latestTitleSlug = titleSlug
        if (!detectedOrigLang.isNullOrBlank()) this.detectedOrigLang = detectedOrigLang
    }

    @Synchronized
    fun loadExternalCues(cues: List<SubtitleCue>, intoOriginal: Boolean = false): Int {
        val targetMap = if (intoOriginal) origCuesByStartMs else cuesByStartMs
        targetMap.clear()
        for (cue in cues) {
            if (cue.text.isNotBlank()) {
                insertDedupCue(targetMap, cue)
            }
        }
        return targetMap.size
    }

    @Synchronized
    fun restoreCompletedChunks(chunks: List<ChunkRange>) {
        completedChunks.clear()
        completedChunks.addAll(chunks)
    }

    @Synchronized
    fun loadFromSrtFile(file: File, intoOriginal: Boolean = false): Int {
        if (!file.exists() || file.length() == 0L) return 0
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return 0

        val targetMap = if (intoOriginal) origCuesByStartMs else cuesByStartMs
        targetMap.clear()
        val blocks = raw.replace("\r\n", "\n").split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
            if (lines.size >= 3) {
                val cue = parseBlock(0L, lines, targetMap.size + 1)
                if (cue != null) {
                    insertDedupCue(targetMap, cue)
                }
            }
        }
        return targetMap.size
    }

    companion object {
        private val INLINE_TAG_REGEX = Regex("<[^>]+>|\\{\\\\[^}]+\\}")
        private val MULTI_SPACE_REGEX = Regex("[ \\t]+")

        fun parseVttOrSrtToCues(rawContent: String): List<SubtitleCue> {
            val cleaned = rawContent
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            if (cleaned.isBlank()) return emptyList()

            val blocks = cleaned.split(Regex("\n{2,}"))
            val result = ArrayList<SubtitleCue>()
            for (rawBlock in blocks) {
                val lines = rawBlock.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) continue
                val first = lines[0].trim()
                if (first.startsWith("WEBVTT", ignoreCase = true) ||
                    first.startsWith("NOTE", ignoreCase = true) ||
                    first.startsWith("STYLE", ignoreCase = true) ||
                    first.startsWith("REGION", ignoreCase = true)
                ) {
                    continue
                }

                val timingIdx = lines.indexOfFirst { it.contains("-->") }
                if (timingIdx < 0) continue

                val timingParts = lines[timingIdx].split("-->", limit = 2)
                if (timingParts.size != 2) continue

                val startToken = timingParts[0].trim().substringBefore(' ')
                val endToken = timingParts[1].trim().substringBefore(' ')
                val startMs = parseSrtTimestampMs(startToken) ?: continue
                val endMs = parseSrtTimestampMs(endToken) ?: (startMs + 1_500L)

                val textLines = lines.drop(timingIdx + 1)
                    .map { line ->
                        line.replace(INLINE_TAG_REGEX, "")
                            .replace("&nbsp;", " ")
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&#39;", "'")
                            .replace("&quot;", "\"")
                            .replace(MULTI_SPACE_REGEX, " ")
                            .trim()
                    }
                    .filter { it.isNotEmpty() }

                if (textLines.isEmpty()) continue
                val dedupedText = textLines.distinct().joinToString("\n")
                if (dedupedText.isBlank()) continue

                val prev = result.lastOrNull()
                if (prev != null && prev.text == dedupedText && kotlin.math.abs(prev.startMs - startMs) <= 250L) {
                    result[result.lastIndex] = prev.copy(endMs = maxOf(prev.endMs, endMs))
                    continue
                }

                result.add(
                    SubtitleCue(
                        index = result.size + 1,
                        startMs = startMs,
                        endMs = maxOf(endMs, startMs + 200L),
                        text = dedupedText,
                    ),
                )
            }
            return result
        }

        fun parseSrtTimestampMs(ts: String): Long? {
            val normalized = ts.trim().replace(',', '.')
            val parts = normalized.split(':')
            val (h, m, secPart) = when (parts.size) {
                3 -> Triple(
                    parts[0].toLongOrNull() ?: return null,
                    parts[1].toLongOrNull() ?: return null,
                    parts[2],
                )
                2 -> Triple(
                    0L,
                    parts[0].toLongOrNull() ?: return null,
                    parts[1],
                )
                else -> return null
            }
            val secParts = secPart.split('.')
            val s = secParts[0].toLongOrNull() ?: return null
            val ms = if (secParts.size > 1) {
                secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            } else 0L
            return (h * 3_600_000L) + (m * 60_000L) + (s * 1_000L) + ms
        }

        fun formatTimestamp(ms: Long, decimalSep: Char = ','): String {
            val clamped = maxOf(0L, ms)
            val h = clamped / 3_600_000L
            val m = (clamped % 3_600_000L) / 60_000L
            val s = (clamped % 60_000L) / 1_000L
            val rem = clamped % 1_000L
            return String.format(Locale.US, "%02d:%02d:%02d%c%03d", h, m, s, decimalSep, rem)
        }

        fun formatAssTimestamp(ms: Long): String {
            val clamped = maxOf(0L, ms)
            val h = clamped / 3_600_000L
            val m = (clamped % 3_600_000L) / 60_000L
            val s = (clamped % 60_000L) / 1_000L
            val cs = (clamped % 1_000L) / 10L
            return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
        }
    }
}
