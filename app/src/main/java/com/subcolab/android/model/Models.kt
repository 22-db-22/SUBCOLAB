package com.subcolab.android.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkflowMode(
    val title: String,
    val subtitle: String,
    val isLocalVideo: Boolean,
) {
    WATCH_NOW(
        title = "Watch Now (Live)",
        subtitle = "Smooth live video stream + adaptive chunked subtitles, auto-opens when ready",
        isLocalVideo = false,
    ),
    DOWNLOAD_AND_TRANSLATE(
        title = "Download + Translate",
        subtitle = "Save full <=720p MP4 + English & original subtitles to Downloads/SUBCOLAB",
        isLocalVideo = false,
    ),
    LOCAL_VIDEO_SUBTITLES(
        title = "Saved Device Video",
        subtitle = "Generate English & original subtitles for a video already saved on phone/Drive",
        isLocalVideo = true,
    ),
}

data class WhisperLanguage(
    val code: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

object WhisperLanguages {
    val quickChips: List<WhisperLanguage> = listOf(
        WhisperLanguage("auto", "Auto-Detect", listOf("detect", "automatic")),
        WhisperLanguage("zh", "Chinese (Mandarin)", listOf("mandarin", "cn", "chinese")),
        WhisperLanguage("ko", "Korean", listOf("kr", "hangul")),
        WhisperLanguage("ja", "Japanese", listOf("jp", "nihongo")),
    )

    val all: List<WhisperLanguage> = listOf(
        WhisperLanguage("auto", "Auto-Detect (~99 languages)", listOf("detect", "automatic")),
        WhisperLanguage("af", "Afrikaans"),
        WhisperLanguage("am", "Amharic"),
        WhisperLanguage("ar", "Arabic"),
        WhisperLanguage("as", "Assamese"),
        WhisperLanguage("az", "Azerbaijani"),
        WhisperLanguage("ba", "Bashkir"),
        WhisperLanguage("be", "Belarusian"),
        WhisperLanguage("bg", "Bulgarian"),
        WhisperLanguage("bn", "Bengali", listOf("bangla")),
        WhisperLanguage("bo", "Tibetan"),
        WhisperLanguage("br", "Breton"),
        WhisperLanguage("bs", "Bosnian"),
        WhisperLanguage("yue", "Cantonese"),
        WhisperLanguage("ca", "Catalan"),
        WhisperLanguage("zh", "Chinese", listOf("mandarin", "cn")),
        WhisperLanguage("hr", "Croatian"),
        WhisperLanguage("cs", "Czech"),
        WhisperLanguage("da", "Danish"),
        WhisperLanguage("nl", "Dutch", listOf("flemish")),
        WhisperLanguage("en", "English"),
        WhisperLanguage("et", "Estonian"),
        WhisperLanguage("fo", "Faroese"),
        WhisperLanguage("fi", "Finnish"),
        WhisperLanguage("fr", "French", listOf("francais")),
        WhisperLanguage("gl", "Galician"),
        WhisperLanguage("ka", "Georgian"),
        WhisperLanguage("de", "German", listOf("deutsch")),
        WhisperLanguage("el", "Greek"),
        WhisperLanguage("gu", "Gujarati"),
        WhisperLanguage("ht", "Haitian Creole", listOf("haitian")),
        WhisperLanguage("ha", "Hausa"),
        WhisperLanguage("haw", "Hawaiian"),
        WhisperLanguage("he", "Hebrew", listOf("iw")),
        WhisperLanguage("hi", "Hindi"),
        WhisperLanguage("hu", "Hungarian"),
        WhisperLanguage("is", "Icelandic"),
        WhisperLanguage("id", "Indonesian", listOf("in")),
        WhisperLanguage("it", "Italian", listOf("italiano")),
        WhisperLanguage("ja", "Japanese", listOf("jp", "nihongo")),
        WhisperLanguage("jw", "Javanese"),
        WhisperLanguage("kn", "Kannada"),
        WhisperLanguage("kk", "Kazakh"),
        WhisperLanguage("km", "Khmer"),
        WhisperLanguage("ko", "Korean", listOf("kr", "hangul")),
        WhisperLanguage("lo", "Lao"),
        WhisperLanguage("la", "Latin"),
        WhisperLanguage("lv", "Latvian"),
        WhisperLanguage("ln", "Lingala"),
        WhisperLanguage("lt", "Lithuanian"),
        WhisperLanguage("lb", "Luxembourgish"),
        WhisperLanguage("mk", "Macedonian"),
        WhisperLanguage("mg", "Malagasy"),
        WhisperLanguage("ms", "Malay"),
        WhisperLanguage("ml", "Malayalam"),
        WhisperLanguage("mt", "Maltese"),
        WhisperLanguage("mi", "Maori"),
        WhisperLanguage("mr", "Marathi"),
        WhisperLanguage("mn", "Mongolian"),
        WhisperLanguage("my", "Myanmar (Burmese)", listOf("burmese")),
        WhisperLanguage("ne", "Nepali"),
        WhisperLanguage("no", "Norwegian"),
        WhisperLanguage("nn", "Nynorsk"),
        WhisperLanguage("oc", "Occitan"),
        WhisperLanguage("ps", "Pashto"),
        WhisperLanguage("fa", "Persian", listOf("farsi")),
        WhisperLanguage("pl", "Polish"),
        WhisperLanguage("pt", "Portuguese"),
        WhisperLanguage("pa", "Punjabi", listOf("panjabi")),
        WhisperLanguage("ro", "Romanian"),
        WhisperLanguage("ru", "Russian"),
        WhisperLanguage("sa", "Sanskrit"),
        WhisperLanguage("sr", "Serbian"),
        WhisperLanguage("sn", "Shona"),
        WhisperLanguage("sd", "Sindhi"),
        WhisperLanguage("si", "Sinhala"),
        WhisperLanguage("sk", "Slovak"),
        WhisperLanguage("sl", "Slovenian"),
        WhisperLanguage("so", "Somali"),
        WhisperLanguage("es", "Spanish", listOf("espanol")),
        WhisperLanguage("su", "Sundanese"),
        WhisperLanguage("sw", "Swahili"),
        WhisperLanguage("sv", "Swedish"),
        WhisperLanguage("tl", "Tagalog", listOf("filipino")),
        WhisperLanguage("tg", "Tajik"),
        WhisperLanguage("ta", "Tamil"),
        WhisperLanguage("tt", "Tatar"),
        WhisperLanguage("te", "Telugu"),
        WhisperLanguage("th", "Thai"),
        WhisperLanguage("tr", "Turkish"),
        WhisperLanguage("tk", "Turkmen"),
        WhisperLanguage("uk", "Ukrainian"),
        WhisperLanguage("ur", "Urdu"),
        WhisperLanguage("uz", "Uzbek"),
        WhisperLanguage("vi", "Vietnamese", listOf("viet", "tiengviet")),
        WhisperLanguage("cy", "Welsh"),
        WhisperLanguage("yi", "Yiddish"),
        WhisperLanguage("yo", "Yoruba"),
    )

    fun search(query: String): List<WhisperLanguage> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { lang ->
            lang.code.lowercase().contains(q) ||
                lang.name.lowercase().contains(q) ||
                lang.aliases.any { it.lowercase().contains(q) }
        }
    }
}

data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Serializable
data class ChunkRange(
    val startSec: Double,
    val endSec: Double,
)

enum class JobPhase {
    IDLE,
    BOOTING_GPU,
    EXTRACTING_STREAM,
    LOADING_MODEL,
    TRANSLATING,
    SAVING_PACK,
    COMPLETED,
    CANCELLED,
    ERROR,
}

object WhisperModels {
    const val DEFAULT_MODEL_ID = "large-v3"
    const val TURBO_MODEL_ID = "large-v3-turbo"

    val allowed: List<Pair<String, String>> = listOf(
        DEFAULT_MODEL_ID to "large-v3 (Best)",
        TURBO_MODEL_ID to "turbo (Fast)",
    )

    fun normalize(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.equals(TURBO_MODEL_ID, ignoreCase = true) || trimmed.equals("turbo", ignoreCase = true)) {
            TURBO_MODEL_ID
        } else {
            DEFAULT_MODEL_ID
        }
    }
}

@Serializable
data class SubtitleRetranslateOptions(
    val sourceLang: String = "auto",
    val cleanAudio: Boolean = false,
    val vocalBoost: Boolean = false,
    val includeOriginal: Boolean = false,
    val modelId: String = WhisperModels.DEFAULT_MODEL_ID,
    val beamSize: Int = 5,
    val initialPrompt: String = "",
)

@Serializable
data class QueuedJobItem(
    val queueId: String,
    val rawUrl: String,
    val normalizedUrl: String,
    val titleSlug: String,
    val sourceLang: String = "auto",
    val mode: WorkflowMode = WorkflowMode.WATCH_NOW,
    val fromExternalShare: Boolean = false,
    val subtitleOptions: SubtitleRetranslateOptions = SubtitleRetranslateOptions(),
    val enqueuedAtMs: Long = System.currentTimeMillis(),
)

data class ReadyQueuedOffer(
    val queueId: String,
    val packId: String,
    val titleSlug: String,
    val cueCount: Int,
    val origCueCount: Int = 0,
    val detectedOrigLang: String? = null,
    val isLiveStreaming: Boolean = false,
    val isComplete: Boolean = false,
    val playableVideoSource: String,
    val playableAudioSource: String? = null,
    val enSrtPath: String? = null,
    val origSrtPath: String? = null,
)

@Serializable
data class PreviousJobCache(
    val rawUrl: String,
    val normalizedUrl: String,
    val sourceLang: String,
    val mode: WorkflowMode = WorkflowMode.DOWNLOAD_AND_TRANSLATE,
    val titleSlug: String,
    val subtitlesCompleted: Boolean = false,
    val savedMp4Uri: String? = null,
    val savedMp4Path: String? = null,
    val savedSrtPath: String? = null,
    val localVideoUri: String? = null,
    val resumeFromSec: Double = 0.0,
)

@Serializable
data class SavedPack(
    val id: String = "",
    val titleSlug: String,
    val folderName: String = "",
    val sourceUrl: String,
    val sourceLang: String,
    val detectedLang: String? = null,
    val isLocalVideo: Boolean = false,
    val cueCount: Int = 0,
    val origCueCount: Int = 0,
    val videoResolution: Int = 720,
    val mp4DisplayPath: String? = null,
    val mp4UriString: String? = null,
    val srtDisplayPath: String? = null,
    val enSrtUriString: String? = null,
    val enAssUriString: String? = null,
    val enVttUriString: String? = null,
    val origSrtDisplayPath: String? = null,
    val origSrtUriString: String? = null,
    val origAssUriString: String? = null,
    val origVttUriString: String? = null,
    val internalEnSrtPath: String? = null,
    val internalOrigSrtPath: String? = null,
    val internalVideoPath: String? = null,
    val createdAtMs: Long,
) {
    val effectiveId: String
        get() = id.ifBlank { "${folderName.ifBlank { titleSlug }}_$createdAtMs" }

    val effectiveFolder: String
        get() = folderName.ifBlank { titleSlug.take(42).trim('_').ifBlank { "SUBCOLAB_Video" } }

    val hasVideo: Boolean
        get() = !mp4UriString.isNullOrBlank() || !internalVideoPath.isNullOrBlank() || isLocalVideo

    val hasSubtitles: Boolean
        get() = cueCount > 0 && (!srtDisplayPath.isNullOrBlank() || !internalEnSrtPath.isNullOrBlank())
}

@Serializable
data class PersistedJobSession(
    val currentUrl: String = "",
    val titleSlug: String = "SUBCOLAB_Translated_Video",
    val sourceLang: String = "auto",
    val mode: WorkflowMode = WorkflowMode.DOWNLOAD_AND_TRANSLATE,
    val cueCount: Int = 0,
    val origCueCount: Int = 0,
    val detectedOrigLang: String? = null,
    val completedChunks: List<ChunkRange> = emptyList(),
    val liveSrtPath: String? = null,
    val liveOrigSrtPath: String? = null,
    val playableVideoSource: String? = null,
    val selectedLocalVideoName: String? = null,
    val previousJob: PreviousJobCache? = null,
    val savedPacks: List<SavedPack> = emptyList(),
    val wasCompleted: Boolean = false,
    val resumeFromSec: Double = 0.0,
)

enum class SavePackStatus {
    IDLE,
    SAVING,
    SAVED,
    ERROR,
}

@Serializable
data class VideoChunkInfo(
    val index: Int,
    val path: String,
    val startSec: Double,
    val endSec: Double,
)

data class JobProgressState(
    val phase: JobPhase = JobPhase.IDLE,
    val statusMessage: String = "Ready",
    val detailMessage: String = "",
    val currentUrl: String = "",
    val titleSlug: String = "SUBCOLAB_Translated_Video",
    val sourceLang: String = "auto",
    val detectedOrigLang: String? = null,
    val mode: WorkflowMode = WorkflowMode.DOWNLOAD_AND_TRANSLATE,
    val cueCount: Int = 0,
    val origCueCount: Int = 0,
    val completedChunks: List<ChunkRange> = emptyList(),
    val playableChunks: List<VideoChunkInfo> = emptyList(),
    val isChunkDownloadComplete: Boolean = false,
    val totalDurationSec: Double = 0.0,
    val isStitching: Boolean = false,
    val chunkDownloadStatus: String? = null,
    val isGpuWarm: Boolean = false,
    val gpuRuntimeLabel: String? = null,
    val isPlayerReady: Boolean = false,
    val isReadyPromptVisible: Boolean = false,
    val fromExternalShare: Boolean = false,
    val selectedLocalVideoName: String? = null,
    val translationProgressText: String? = null,
    val liveSrtPath: String? = null,
    val liveOrigSrtPath: String? = null,
    val playableVideoSource: String? = null,
    val playableAudioSource: String? = null,
    val isLiveStreaming: Boolean = false,
    val activePackId: String? = null,
    val savePackStatus: SavePackStatus = SavePackStatus.IDLE,
    val saveProgressPercent: Int = 0,
    val saveStatusText: String? = null,
    val errorMessage: String? = null,
    val previousJob: PreviousJobCache? = null,
    val savedPacks: List<SavedPack> = emptyList(),
    val logTail: List<String> = emptyList(),
    val resumeFromSec: Double = 0.0,
    val queuedJobs: List<QueuedJobItem> = emptyList(),
    val readyQueuedOffers: List<ReadyQueuedOffer> = emptyList(),
    val activeJobQueueId: String? = null,
    val isActiveJobQueued: Boolean = false,
) {
    val resumableFromSec: Double
        get() = maxOf(
            resumeFromSec,
            completedChunks.maxOfOrNull { it.endSec } ?: 0.0,
            previousJob?.resumeFromSec ?: 0.0,
        )
}

@Serializable
enum class ThemeMode(val label: String) {
    SOFT_DARK("Soft Dark"),
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System"),
}

@Serializable
data class AppSettings(
    val cleanAudio: Boolean = false,
    val modelId: String = WhisperModels.DEFAULT_MODEL_ID,
    val useCustomTunnel: Boolean = false,
    val customTunnelUrl: String = "",
    val customTunnelToken: String = "",
    val customOauthClientId: String = "",
    val customOauthClientSecret: String = "",
    val hasCookiesFile: Boolean = false,
    val subtitleFontSize: Int = 46,
    val subtitleDelaySec: Float = 0f,
    val subtitleBorderSize: Int = 3,
    val subtitleBoxEnabled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SOFT_DARK,
    val quickTipsDismissed: Boolean = false,
)
