package com.subcolab.android.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.subcolab.android.MainActivity
import com.subcolab.android.SubcolabApp
import com.subcolab.android.colab.ColabClient
import com.subcolab.android.colab.ColabRuntimeSession
import com.subcolab.android.data.SettingsRepository
import com.subcolab.android.media.LocalAudioExtractor
import com.subcolab.android.media.ResolvedStreamInfo
import com.subcolab.android.media.StreamExtractor
import com.subcolab.android.model.JobPhase
import com.subcolab.android.model.JobProgressState
import com.subcolab.android.model.PersistedJobSession
import com.subcolab.android.model.PreviousJobCache
import com.subcolab.android.model.SavePackStatus
import com.subcolab.android.model.SavedPack
import com.subcolab.android.model.SubtitleRetranslateOptions
import com.subcolab.android.model.VideoChunkInfo
import com.subcolab.android.model.WorkflowMode
import com.subcolab.android.util.AppLogger
import com.subcolab.android.util.MediaStoreSaver
import com.subcolab.android.util.SavedMediaResult
import com.subcolab.android.util.SubtitleManager
import com.subcolab.android.util.UrlUnwrapper
import com.subcolab.android.util.WorkerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Central orchestrator shared between MainActivity / SharePromptActivity / ViewModel
 * and SubcolabForegroundService.
 * Runs parallel MP4 + phone audio extraction + Colab T4 dual-language Whisper translation,
 * manages dedicated Downloads/SUBCOLAB/<shortTitle>/ folders, and supports background
 * re-download / subtitle improvement / instant permanent delete in the Downloads tab.
 */
class SubcolabCoordinator(
    private val appContext: Context,
    private val settingsRepo: SettingsRepository,
    private val colabClient: ColabClient,
    private val streamExtractor: StreamExtractor,
    val subtitleManager: SubtitleManager,
) {
    companion object {
        private const val TAG = "SubcolabCoord"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(JobProgressState())
    val state: StateFlow<JobProgressState> = _state.asStateFlow()

    private val _liveSrtRevision = MutableStateFlow(0L)
    val liveSrtRevision: StateFlow<Long> = _liveSrtRevision.asStateFlow()

    private var activeJob: Job? = null
    private val activeJobToken = AtomicLong(0L)
    private var activeLiveStreamProxy: StreamExtractor.LoopbackMediaProxy? = null
    private val liveProxyLock = Any()
    private var notificationCallback: ((title: String, body: String, ongoing: Boolean) -> Unit)? = null
    private var playerReadyNotificationCallback: ((titleSlug: String, cueCount: Int, isComplete: Boolean, isLiveStream: Boolean, useFullScreenIntent: Boolean) -> Unit)? = null

    @Volatile
    private var isPlayerScreenOpen = false
    private val deferredCleanupDirs = mutableListOf<File>()
    @Volatile
    private var promptedReadyForJobToken: Long = -1L

    fun hasActiveJob(): Boolean {
        val phase = _state.value.phase
        return activeJob?.isActive == true ||
            phase in setOf(
                JobPhase.BOOTING_GPU,
                JobPhase.LOADING_MODEL,
                JobPhase.EXTRACTING_STREAM,
                JobPhase.TRANSLATING,
                JobPhase.SAVING_PACK,
            )
    }

    fun onPrewarmedSession(session: ColabRuntimeSession) {
        _state.update {
            it.copy(
                isGpuWarm = true,
                gpuRuntimeLabel = "${session.accelerator} • ${session.endpoint.take(10)}",
            )
        }
    }

    fun resetGpuWarmState() {
        _state.update {
            it.copy(
                isGpuWarm = false,
                gpuRuntimeLabel = null,
            )
        }
    }

    private fun replaceLiveStreamProxy(newProxy: StreamExtractor.LoopbackMediaProxy?) {
        val old = synchronized(liveProxyLock) {
            val prev = activeLiveStreamProxy
            activeLiveStreamProxy = newProxy
            prev
        }
        if (old != null && old !== newProxy) {
            runCatching { old.close() }
        }
    }

    private fun closeLiveStreamProxy() = replaceLiveStreamProxy(null)

    fun setPlayerScreenOpen(open: Boolean) {
        isPlayerScreenOpen = open
        if (!open) {
            val toDelete = synchronized(deferredCleanupDirs) {
                val copy = deferredCleanupDirs.toList()
                deferredCleanupDirs.clear()
                copy
            }
            toDelete.forEach { dir ->
                runCatching { dir.deleteRecursively() }
            }
            _state.update { cur ->
                if (cur.playableVideoSource != null && cur.playableChunks.isNotEmpty()) {
                    cur.copy(playableChunks = emptyList(), isChunkDownloadComplete = false)
                } else cur
            }
        }
    }

    private fun scheduleChunksDirCleanup(dir: File) {
        if (!isPlayerScreenOpen) {
            runCatching { dir.deleteRecursively() }
            _state.update { cur ->
                if (cur.playableChunks.isNotEmpty()) {
                    cur.copy(playableChunks = emptyList(), isChunkDownloadComplete = false)
                } else cur
            }
        } else {
            synchronized(deferredCleanupDirs) {
                deferredCleanupDirs.add(dir)
            }
        }
    }

    private fun launchPlayerFromBackground(): Boolean {
        val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        runCatching {
            val openIntent = MainActivity.createOpenPlayerIntent(appContext).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            appContext.startActivity(openIntent)
            AppLogger.i(TAG, "Auto-launched MainActivity player from background (canDrawOverlays=$canDrawOverlays)")
        }.onFailure { err ->
            AppLogger.w(TAG, "Background startActivity blocked or failed (canDrawOverlays=$canDrawOverlays): ${err.message}")
        }
        return canDrawOverlays
    }

    private fun maybeNotifyPlayerReady(jobToken: Long) {
        if (!isJobCurrent(jobToken)) return
        val cur = _state.value
        if (cur.mode != WorkflowMode.WATCH_NOW) return
        val hasMedia = (cur.isLiveStreaming && !cur.playableVideoSource.isNullOrBlank()) || cur.playableChunks.isNotEmpty()
        if (!hasMedia || cur.cueCount < 1) return

        if (!cur.isPlayerReady) {
            val showInApp = SubcolabApp.isAppInForeground && !isPlayerScreenOpen
            _state.update {
                it.copy(
                    isPlayerReady = true,
                    isReadyPromptVisible = it.isReadyPromptVisible || showInApp,
                )
            }
            if (promptedReadyForJobToken != jobToken) {
                promptedReadyForJobToken = jobToken
                if (!SubcolabApp.isAppInForeground) {
                    val launchedDirectly = launchPlayerFromBackground()
                    val slug = cur.titleSlug.ifBlank { "SUBCOLAB_Video" }
                    playerReadyNotificationCallback?.invoke(
                        slug,
                        cur.cueCount,
                        false,
                        cur.isLiveStreaming,
                        !launchedDirectly,
                    )
                }
            }
        }
    }

    val liveSrtFile: File
        get() = File(appContext.filesDir, "live.srt")

    val liveOrigSrtFile: File
        get() = File(appContext.filesDir, "live_orig.srt")

    private fun packDir(packId: String): File =
        File(File(appContext.filesDir, "packs"), packId).apply { mkdirs() }

    init {
        restorePersistedSession()
    }

    private fun restorePersistedSession() {
        val saved = settingsRepo.loadLastJobSession() ?: return
        val srtFile = liveSrtFile
        val origFile = liveOrigSrtFile

        val loadedCues = if (srtFile.exists() && srtFile.length() > 0L) {
            subtitleManager.loadFromSrtFile(srtFile, intoOriginal = false)
        } else 0
        val loadedOrigCues = if (origFile.exists() && origFile.length() > 0L) {
            subtitleManager.loadFromSrtFile(origFile, intoOriginal = true)
        } else 0

        if (saved.completedChunks.isNotEmpty()) {
            subtitleManager.restoreCompletedChunks(saved.completedChunks)
        }
        val effectiveCueCount = if (loadedCues > 0) loadedCues else saved.cueCount
        val effectiveOrigCount = if (loadedOrigCues > 0) loadedOrigCues else saved.origCueCount

        val restoredVideo = saved.playableVideoSource?.takeIf { src ->
            isVideoSourcePlayable(src)
        } ?: saved.previousJob?.savedMp4Uri?.takeIf { isVideoSourcePlayable(it) }
            ?: saved.previousJob?.savedMp4Path?.takeIf { File(it).exists() }
            ?: saved.previousJob?.localVideoUri?.takeIf { isVideoSourcePlayable(it) }

        val restoredSrtPath = srtFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
            ?: saved.liveSrtPath?.takeIf { File(it).exists() }
        val restoredOrigSrtPath = origFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath
            ?: saved.liveOrigSrtPath?.takeIf { File(it).exists() }

        val cleanedPacks = saved.savedPacks.filter { it.hasVideo || it.hasSubtitles }
        val hasRestorableJob = saved.currentUrl.isNotBlank() || saved.previousJob != null
        _state.update {
            it.copy(
                phase = when {
                    !hasRestorableJob -> JobPhase.IDLE
                    saved.wasCompleted -> JobPhase.COMPLETED
                    else -> JobPhase.CANCELLED
                },
                statusMessage = when {
                    !hasRestorableJob -> "Ready"
                    saved.wasCompleted -> "Previous pack ready ($effectiveCueCount cues)"
                    else -> "Restored last session ($effectiveCueCount cues)"
                },
                detailMessage = saved.selectedLocalVideoName ?: saved.currentUrl,
                currentUrl = saved.currentUrl,
                titleSlug = saved.titleSlug,
                sourceLang = saved.sourceLang,
                detectedOrigLang = saved.detectedOrigLang,
                mode = saved.mode,
                cueCount = effectiveCueCount,
                origCueCount = effectiveOrigCount,
                completedChunks = saved.completedChunks,
                isPlayerReady = restoredVideo != null && effectiveCueCount > 0,
                selectedLocalVideoName = saved.selectedLocalVideoName,
                liveSrtPath = restoredSrtPath,
                liveOrigSrtPath = restoredOrigSrtPath,
                playableVideoSource = restoredVideo,
                previousJob = saved.previousJob,
                savedPacks = cleanedPacks,
            )
        }
        if (loadedCues > 0 || loadedOrigCues > 0) {
            _liveSrtRevision.value = 1L
        }
        AppLogger.i(
            TAG,
            "Restored persisted session: mode=${saved.mode} enCues=$effectiveCueCount origCues=$effectiveOrigCount packs=${cleanedPacks.size}",
        )
    }

    private fun isVideoSourcePlayable(source: String): Boolean {
        return if (source.startsWith("content://")) {
            runCatching {
                appContext.contentResolver.openAssetFileDescriptor(Uri.parse(source), "r")?.use { true }
                    ?: false
            }.getOrDefault(false)
        } else {
            !source.startsWith("http") && File(source).let { it.exists() && it.length() > 0L }
        }
    }

    private fun persistSession(wasCompletedOverride: Boolean? = null) {
        val current = _state.value
        val wasCompleted = wasCompletedOverride
            ?: (current.phase == JobPhase.COMPLETED || current.previousJob?.subtitlesCompleted == true)
        settingsRepo.saveLastJobSession(
            PersistedJobSession(
                currentUrl = current.currentUrl,
                titleSlug = current.titleSlug,
                sourceLang = current.sourceLang,
                mode = current.mode,
                cueCount = current.cueCount,
                origCueCount = current.origCueCount,
                detectedOrigLang = current.detectedOrigLang,
                completedChunks = current.completedChunks,
                liveSrtPath = current.liveSrtPath,
                liveOrigSrtPath = current.liveOrigSrtPath,
                playableVideoSource = current.playableVideoSource,
                selectedLocalVideoName = current.selectedLocalVideoName,
                previousJob = current.previousJob,
                savedPacks = current.savedPacks,
                wasCompleted = wasCompleted,
            ),
        )
    }

    fun attachServiceCallbacks(
        onProgressNotify: (title: String, body: String, ongoing: Boolean) -> Unit,
        onPlayerReadyNotify: (titleSlug: String, cueCount: Int, isComplete: Boolean, isLiveStream: Boolean, useFullScreenIntent: Boolean) -> Unit,
    ) {
        notificationCallback = onProgressNotify
        playerReadyNotificationCallback = onPlayerReadyNotify
    }

    fun detachServiceCallbacks() {
        notificationCallback = null
        playerReadyNotificationCallback = null
    }

    fun startUrlJob(
        rawUrl: String,
        sourceLang: String,
        workflowMode: WorkflowMode = WorkflowMode.WATCH_NOW,
        fromExternalShare: Boolean = false,
        reusePreviousCache: Boolean = false,
    ) {
        val cleanUrl = UrlUnwrapper.extractAndUnwrap(rawUrl)
        if (!UrlUnwrapper.isValidVideoUrl(cleanUrl)) {
            _state.update {
                it.copy(
                    phase = JobPhase.ERROR,
                    statusMessage = "Invalid video link",
                    errorMessage = "Please enter or share a valid video URL.",
                )
            }
            return
        }

        val effectiveMode = if (workflowMode == WorkflowMode.LOCAL_VIDEO_SUBTITLES) {
            WorkflowMode.DOWNLOAD_AND_TRANSLATE
        } else {
            workflowMode
        }

        AppLogger.i(TAG, "Starting URL job: mode=$effectiveMode url=$cleanUrl lang=$sourceLang externalShare=$fromExternalShare reuse=$reusePreviousCache")
        startForegroundServiceSafe()

        val jobToken = activeJobToken.incrementAndGet()
        activeJob?.cancel()
        closeLiveStreamProxy()
        activeJob = scope.launch {
            runUrlPipeline(
                jobToken = jobToken,
                cleanUrl = cleanUrl,
                rawUrl = rawUrl,
                sourceLang = sourceLang.ifBlank { "auto" },
                workflowMode = effectiveMode,
                fromExternalShare = fromExternalShare,
                reusePreviousCache = reusePreviousCache,
            )
        }
    }

    fun startLocalVideoJob(
        videoUriString: String,
        sourceLang: String,
    ) {
        val uri = runCatching { Uri.parse(videoUriString) }.getOrNull()
        if (uri == null || videoUriString.isBlank()) {
            _state.update {
                it.copy(
                    phase = JobPhase.ERROR,
                    statusMessage = "No video selected",
                    errorMessage = "Please select a saved video file from your device.",
                )
            }
            return
        }

        AppLogger.i(TAG, "Starting local device video subtitle job: uri=$videoUriString lang=$sourceLang")
        startForegroundServiceSafe()

        val jobToken = activeJobToken.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
            runLocalVideoPipeline(
                jobToken = jobToken,
                uri = uri,
                videoUriString = videoUriString,
                sourceLang = sourceLang.ifBlank { "auto" },
            )
        }
    }

    fun redownloadPackVideo(
        packId: String,
        maxHeight: Int = 720,
    ) {
        val pack = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: return
        if (pack.isLocalVideo || !UrlUnwrapper.isValidVideoUrl(pack.sourceUrl)) {
            _state.update {
                it.copy(
                    statusMessage = "Local device video cannot be re-downloaded from URL.",
                )
            }
            return
        }

        AppLogger.i(TAG, "Starting background video re-download: pack=${pack.effectiveFolder} res=${maxHeight}p")
        startForegroundServiceSafe()

        val jobToken = activeJobToken.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
            runPackVideoDownload(jobToken, pack, maxHeight)
        }
    }

    fun retranslatePackSubtitles(
        packId: String,
        options: SubtitleRetranslateOptions,
    ) {
        val pack = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: return

        AppLogger.i(
            TAG,
            "Starting background subtitle job: pack=${pack.effectiveFolder} lang=${options.sourceLang} model=${options.modelId} clean=${options.cleanAudio} vocal=${options.vocalBoost} beam=${options.beamSize}",
        )
        startForegroundServiceSafe()

        val jobToken = activeJobToken.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
            runPackSubtitleGeneration(jobToken, pack, options)
        }
    }

    fun openSavedPackInPlayer(packId: String): Boolean {
        val pack = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: return false
        val videoSrc = pack.mp4UriString?.takeIf { isVideoSourcePlayable(it) }
            ?: pack.internalVideoPath?.takeIf { File(it).let { f -> f.exists() && f.length() > 0L } }
            ?: if (pack.isLocalVideo) pack.sourceUrl.takeIf { isVideoSourcePlayable(it) } else null

        if (videoSrc == null) {
            _state.update {
                it.copy(
                    phase = JobPhase.ERROR,
                    statusMessage = "Video file missing from storage",
                    errorMessage = "Tap Video on this pack to re-download it in the background.",
                )
            }
            return false
        }

        subtitleManager.reset()
        val enFile = pack.internalEnSrtPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
        val origFile = pack.internalOrigSrtPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }

        val enCount = if (enFile != null) {
            enFile.copyTo(liveSrtFile, overwrite = true)
            subtitleManager.loadFromSrtFile(liveSrtFile, intoOriginal = false)
        } else {
            if (liveSrtFile.exists()) liveSrtFile.delete()
            0
        }

        val origCount = if (origFile != null) {
            origFile.copyTo(liveOrigSrtFile, overwrite = true)
            subtitleManager.loadFromSrtFile(liveOrigSrtFile, intoOriginal = true)
        } else {
            if (liveOrigSrtFile.exists()) liveOrigSrtFile.delete()
            0
        }

        closeLiveStreamProxy()
        _state.update {
            it.copy(
                titleSlug = pack.titleSlug,
                detectedOrigLang = pack.detectedLang,
                playableVideoSource = videoSrc,
                playableAudioSource = null,
                isLiveStreaming = false,
                playableChunks = emptyList(),
                isChunkDownloadComplete = false,
                totalDurationSec = 0.0,
                isStitching = false,
                chunkDownloadStatus = null,
                liveSrtPath = if (enCount > 0) liveSrtFile.absolutePath else null,
                liveOrigSrtPath = if (origCount > 0) liveOrigSrtFile.absolutePath else null,
                cueCount = enCount,
                origCueCount = origCount,
                isPlayerReady = true,
                isReadyPromptVisible = false,
            )
        }
        _liveSrtRevision.value = _liveSrtRevision.value + 1L
        persistSession(wasCompletedOverride = _state.value.phase == JobPhase.COMPLETED)
        return true
    }

    fun deletePackPermanently(packId: String) {
        val pack = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: return
        AppLogger.i(TAG, "Permanently deleting pack ${pack.effectiveFolder} ($packId)")

        scope.launch {
            MediaStoreSaver.deleteSavedEntry(appContext, pack.mp4UriString, pack.mp4DisplayPath)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.enSrtUriString, pack.srtDisplayPath)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.enAssUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.enVttUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.origSrtUriString, pack.origSrtDisplayPath)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.origAssUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, pack.origVttUriString, null)
            MediaStoreSaver.deleteSubfolderIfEmpty(pack.effectiveFolder)

            pack.internalVideoPath?.let { runCatching { File(it).delete() } }
            runCatching { packDir(packId).deleteRecursively() }
        }

        _state.update { current ->
            val removedPlayable = current.playableVideoSource != null && (
                current.playableVideoSource == pack.mp4UriString ||
                    current.playableVideoSource == pack.internalVideoPath
                )
            val removedPrev = current.previousJob?.let { prev ->
                prev.normalizedUrl == pack.sourceUrl && prev.titleSlug == pack.titleSlug
            } == true
            current.copy(
                savedPacks = current.savedPacks.filterNot { it.effectiveId == packId },
                playableVideoSource = if (removedPlayable) null else current.playableVideoSource,
                isPlayerReady = if (removedPlayable) false else current.isPlayerReady,
                previousJob = if (removedPrev) null else current.previousJob,
            )
        }
        persistSession()
    }

    fun runPreviousJob() {
        val prev = _state.value.previousJob ?: return
        if (prev.mode == WorkflowMode.LOCAL_VIDEO_SUBTITLES && !prev.localVideoUri.isNullOrBlank()) {
            startLocalVideoJob(
                videoUriString = prev.localVideoUri,
                sourceLang = prev.sourceLang,
            )
        } else {
            startUrlJob(
                rawUrl = prev.normalizedUrl,
                sourceLang = prev.sourceLang,
                workflowMode = prev.mode,
                fromExternalShare = false,
                reusePreviousCache = true,
            )
        }
    }

    fun cancelCurrentJob() {
        activeJobToken.incrementAndGet()
        streamExtractor.cancelProcess("resolve_stream")
        streamExtractor.cancelProcess("relay_audio")
        streamExtractor.cancelProcess("save_mp4")
        activeJob?.cancel()
        activeJob = null
        closeLiveStreamProxy()

        _state.update {
            it.copy(
                phase = JobPhase.CANCELLED,
                statusMessage = "Job cancelled (T4 GPU kept warm)",
                detailMessage = "Paste a new link or pick a saved video.",
                translationProgressText = null,
                activePackId = null,
                playableAudioSource = null,
                isLiveStreaming = false,
                playableChunks = emptyList(),
                isStitching = false,
                isChunkDownloadComplete = false,
                chunkDownloadStatus = null,
                isReadyPromptVisible = false,
            )
        }
        persistSession()
        notificationCallback?.invoke(
            "SUBCOLAB — T4 Warm",
            "Job stopped. Colab T4 GPU is ready for your next video.",
            false,
        )

        val s = settingsRepo.settings.value
        scope.launch {
            colabClient.sendAbortSignal(
                useCustomTunnel = s.useCustomTunnel,
                customTunnelUrl = s.customTunnelUrl,
                customTunnelToken = s.customTunnelToken,
            )
        }
    }

    fun stopT4Gpu() {
        activeJobToken.incrementAndGet()
        streamExtractor.cancelProcess("resolve_stream")
        streamExtractor.cancelProcess("relay_audio")
        streamExtractor.cancelProcess("save_mp4")
        activeJob?.cancel()
        activeJob = null
        closeLiveStreamProxy()

        _state.update {
            it.copy(
                phase = JobPhase.IDLE,
                statusMessage = "Colab T4 GPU stopped",
                detailMessage = "",
                translationProgressText = null,
                activePackId = null,
                playableAudioSource = null,
                isLiveStreaming = false,
                playableChunks = emptyList(),
                isChunkDownloadComplete = false,
                isStitching = false,
                chunkDownloadStatus = null,
                isGpuWarm = false,
                gpuRuntimeLabel = null,
                isReadyPromptVisible = false,
            )
        }
        SubcolabForegroundService.cancelReadyNotification(appContext)
        appContext.stopService(Intent(appContext, SubcolabForegroundService::class.java))

        val s = settingsRepo.settings.value
        scope.launch {
            colabClient.sendAbortSignal(
                useCustomTunnel = s.useCustomTunnel,
                customTunnelUrl = s.customTunnelUrl,
                customTunnelToken = s.customTunnelToken,
            )
            colabClient.unassignActiveRuntime()
        }
    }

    fun dismissReadyPrompt() {
        _state.update { it.copy(isReadyPromptVisible = false) }
    }

    private fun startForegroundServiceSafe() {
        SubcolabForegroundService.cancelReadyNotification(appContext)
        runCatching {
            val serviceIntent = Intent(appContext, SubcolabForegroundService::class.java).apply {
                action = SubcolabForegroundService.ACTION_START_JOB
            }
            appContext.startForegroundService(serviceIntent)
        }.onFailure { err ->
            AppLogger.e(TAG, "Failed to start SubcolabForegroundService", err)
        }
    }

    private fun isJobCurrent(jobToken: Long): Boolean = activeJobToken.get() == jobToken

    private fun assertJobCurrent(jobToken: Long) {
        if (!isJobCurrent(jobToken)) {
            throw CancellationException("Job #$jobToken superseded")
        }
    }

    private fun upsertSavedPack(updatedPack: SavedPack) {
        _state.update { current ->
            val filtered = current.savedPacks.filterNot {
                it.effectiveId == updatedPack.effectiveId ||
                    (!updatedPack.isLocalVideo && it.sourceUrl == updatedPack.sourceUrl && it.effectiveFolder == updatedPack.effectiveFolder)
            }
            current.copy(savedPacks = listOf(updatedPack) + filtered)
        }
    }

    private fun saveSubtitlesToDownloads(
        packId: String,
        folder: String,
        titleSlug: String,
        oldPack: SavedPack?,
    ): SavedSubtitleBundle {
        // Delete previous subtitle URIs only right before writing replacement files
        if (oldPack != null) {
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.enSrtUriString, oldPack.srtDisplayPath)
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.enAssUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.enVttUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.origSrtUriString, oldPack.origSrtDisplayPath)
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.origAssUriString, null)
            MediaStoreSaver.deleteSavedEntry(appContext, oldPack.origVttUriString, null)
        }

        val pDir = packDir(packId)
        val internalEnFile = File(pDir, "sub_en.srt")
        subtitleManager.writeLiveSrt(internalEnFile)
        subtitleManager.writeLiveSrt(liveSrtFile)

        val enSrtContent = subtitleManager.buildSrtContent()
        val enAssContent = subtitleManager.buildAssContent(titleSlug)
        val enVttContent = subtitleManager.buildVttContent()

        val savedEnSrt = MediaStoreSaver.saveTextFile(
            appContext, "${folder}.en.srt", "application/x-subrip", enSrtContent, subfolder = folder,
        )
        val savedEnAss = MediaStoreSaver.saveTextFile(
            appContext, "${folder}.en.ass", "text/plain", enAssContent, subfolder = folder,
        )
        val savedEnVtt = MediaStoreSaver.saveTextFile(
            appContext, "${folder}.en.vtt", "text/vtt", enVttContent, subfolder = folder,
        )

        val origCount = subtitleManager.origCueCount()
        val rawOrigLang = subtitleManager.detectedOrigLang
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "auto" && it != "en" }
            ?: if (origCount > 0) "orig" else null

        var savedOrigSrt: SavedMediaResult? = null
        var savedOrigAss: SavedMediaResult? = null
        var savedOrigVtt: SavedMediaResult? = null
        var internalOrigPath: String? = null

        if (origCount > 0) {
            val origSrtContent = subtitleManager.buildOrigSrtContent()
            if (origSrtContent.isNotBlank()) {
                val internalOrigFile = File(pDir, "sub_orig.srt")
                subtitleManager.writeOrigSrt(internalOrigFile)
                subtitleManager.writeOrigSrt(liveOrigSrtFile)
                internalOrigPath = internalOrigFile.absolutePath

                val code = rawOrigLang ?: "orig"
                savedOrigSrt = MediaStoreSaver.saveTextFile(
                    appContext, "${folder}.${code}.srt", "application/x-subrip", origSrtContent, subfolder = folder,
                )
                savedOrigAss = MediaStoreSaver.saveTextFile(
                    appContext, "${folder}.${code}.ass", "text/plain", subtitleManager.buildOrigAssContent(titleSlug), subfolder = folder,
                )
                savedOrigVtt = MediaStoreSaver.saveTextFile(
                    appContext, "${folder}.${code}.vtt", "text/vtt", subtitleManager.buildOrigVttContent(), subfolder = folder,
                )
            }
        } else {
            File(pDir, "sub_orig.srt").takeIf { it.exists() }?.delete()
            if (liveOrigSrtFile.exists()) liveOrigSrtFile.delete()
        }

        _liveSrtRevision.value = _liveSrtRevision.value + 1L

        return SavedSubtitleBundle(
            enCueCount = subtitleManager.cueCount(),
            origCueCount = origCount,
            detectedOrigLang = subtitleManager.detectedOrigLang,
            enSrt = savedEnSrt,
            enAss = savedEnAss,
            enVtt = savedEnVtt,
            origSrt = savedOrigSrt,
            origAss = savedOrigAss,
            origVtt = savedOrigVtt,
            internalEnSrtPath = internalEnFile.takeIf { it.exists() && it.length() > 0L }?.absolutePath,
            internalOrigSrtPath = internalOrigPath,
        )
    }

    private data class SavedSubtitleBundle(
        val enCueCount: Int,
        val origCueCount: Int,
        val detectedOrigLang: String?,
        val enSrt: SavedMediaResult?,
        val enAss: SavedMediaResult?,
        val enVtt: SavedMediaResult?,
        val origSrt: SavedMediaResult?,
        val origAss: SavedMediaResult?,
        val origVtt: SavedMediaResult?,
        val internalEnSrtPath: String?,
        val internalOrigSrtPath: String?,
    )

    private data class QueuedChunkUpload(
        val audioFile: File,
        val readyName: String,
        val readyJson: String,
    )

    private suspend fun runUrlPipeline(
        jobToken: Long,
        cleanUrl: String,
        rawUrl: String,
        sourceLang: String,
        workflowMode: WorkflowMode,
        fromExternalShare: Boolean,
        reusePreviousCache: Boolean,
    ) {
        assertJobCurrent(jobToken)
        val settings = settingsRepo.settings.value
        val prev = _state.value.previousJob
        val isYouTube = streamExtractor.isYouTubeUrl(cleanUrl)
        val isWatchNow = workflowMode == WorkflowMode.WATCH_NOW

        val canReuseSubtitles = reusePreviousCache &&
            prev != null &&
            prev.normalizedUrl == cleanUrl &&
            prev.sourceLang == sourceLang &&
            prev.subtitlesCompleted &&
            subtitleManager.cueCount() > 0 &&
            liveSrtFile.exists()

        val existingPack = _state.value.savedPacks.firstOrNull { !it.isLocalVideo && it.sourceUrl == cleanUrl }
        val packId = existingPack?.effectiveId ?: UUID.randomUUID().toString().take(12)

        val cachedPlayableSource = existingPack?.mp4UriString?.takeIf { isVideoSourcePlayable(it) }
            ?: prev?.savedMp4Uri?.takeIf { reusePreviousCache && prev.normalizedUrl == cleanUrl && isVideoSourcePlayable(it) }
            ?: existingPack?.internalVideoPath?.takeIf { File(it).exists() }
            ?: prev?.savedMp4Path?.takeIf { reusePreviousCache && prev.normalizedUrl == cleanUrl && File(it).exists() }

        if (!canReuseSubtitles) {
            subtitleManager.reset()
            if (liveSrtFile.exists()) liveSrtFile.delete()
            if (liveOrigSrtFile.exists()) liveOrigSrtFile.delete()
            liveSrtFile.writeText("", Charsets.UTF_8)
            _liveSrtRevision.value = 0L
        }

        val initialSlug = if (canReuseSubtitles) prev!!.titleSlug
        else existingPack?.titleSlug ?: "SUBCOLAB_Video_${packId.take(4)}"

        val initialCache = PreviousJobCache(
            rawUrl = rawUrl,
            normalizedUrl = cleanUrl,
            sourceLang = sourceLang,
            mode = workflowMode,
            titleSlug = initialSlug,
            subtitlesCompleted = canReuseSubtitles,
            savedMp4Path = prev?.savedMp4Path,
            savedMp4Uri = cachedPlayableSource?.takeIf { it.startsWith("content://") },
            localVideoUri = null,
        )

        assertJobCurrent(jobToken)
        _state.update {
            it.copy(
                phase = JobPhase.BOOTING_GPU,
                statusMessage = when {
                    canReuseSubtitles && isWatchNow -> "Subtitles cached — opening player..."
                    canReuseSubtitles -> "Subtitles cached — downloading <=720p MP4..."
                    isWatchNow -> "Resolving live stream & booting Colab T4 GPU..."
                    else -> "Downloading <=720p MP4 & booting Colab T4 GPU..."
                },
                detailMessage = cleanUrl,
                currentUrl = cleanUrl,
                titleSlug = initialSlug,
                sourceLang = sourceLang,
                detectedOrigLang = if (canReuseSubtitles) it.detectedOrigLang else null,
                mode = workflowMode,
                cueCount = if (canReuseSubtitles) subtitleManager.cueCount() else 0,
                origCueCount = if (canReuseSubtitles) subtitleManager.origCueCount() else 0,
                completedChunks = if (canReuseSubtitles) subtitleManager.getCompletedChunks() else emptyList(),
                playableChunks = emptyList(),
                isChunkDownloadComplete = false,
                totalDurationSec = 0.0,
                isStitching = false,
                chunkDownloadStatus = null,
                isPlayerReady = false,
                isReadyPromptVisible = false,
                fromExternalShare = fromExternalShare,
                selectedLocalVideoName = null,
                activePackId = packId,
                translationProgressText = if (canReuseSubtitles) {
                    "Reusing ${subtitleManager.cueCount()} cached cues"
                } else if (isWatchNow) {
                    "Booting T4 GPU & resolving smooth live stream..."
                } else {
                    "Booting T4 GPU & starting full MP4 download..."
                },
                liveSrtPath = liveSrtFile.absolutePath,
                liveOrigSrtPath = liveOrigSrtFile.takeIf { f -> f.exists() && f.length() > 0L }?.absolutePath,
                playableVideoSource = cachedPlayableSource,
                playableAudioSource = null,
                isLiveStreaming = false,
                savePackStatus = if (cachedPlayableSource != null) SavePackStatus.SAVED else if (isWatchNow) SavePackStatus.IDLE else SavePackStatus.SAVING,
                saveProgressPercent = if (cachedPlayableSource != null) 100 else 0,
                saveStatusText = if (cachedPlayableSource != null) "Cached MP4 ready" else if (isWatchNow) "Live stream mode (no video download)" else "Downloading full MP4...",
                errorMessage = null,
                previousJob = initialCache,
                logTail = emptyList(),
            )
        }
        persistSession(wasCompletedOverride = canReuseSubtitles)
        updateNotification(
            if (isWatchNow) "SUBCOLAB — Live Stream & Translate" else "SUBCOLAB — Downloading & Translating",
            _state.value.statusMessage,
            ongoing = true,
        )

        val localTempMp4 = File(appContext.cacheDir, "subcolab_pack_$packId.mp4")
        val audioWorkDir = File(appContext.cacheDir, "relay_$packId").apply { mkdirs() }
        val chunksDir = File(appContext.cacheDir, "chunks_$packId")
        var chunksDirScheduledForDeferredDelete = false
        var subtitlesFinishedOk = canReuseSubtitles
        var savedMp4Result: SavedMediaResult? = null
        var internalVideoFallbackPath: String? = null

        try {
            coroutineScope {
                // Warm T4 runtime immediately in parallel with stream resolution/downloads (single-flight with app/share pre-warm)
                val prewarmedRuntimeDeferred: Deferred<Result<ColabRuntimeSession>>? =
                    if (!canReuseSubtitles && !settings.useCustomTunnel) {
                        async {
                            runCatching {
                                colabClient.ensureWarmT4Runtime { statusMsg ->
                                    if (isJobCurrent(jobToken)) {
                                        _state.update {
                                            it.copy(
                                                translationProgressText = statusMsg,
                                            )
                                        }
                                        appendLog(statusMsg)
                                    }
                                }.also { session ->
                                    if (isJobCurrent(jobToken)) {
                                        onPrewarmedSession(session)
                                    }
                                }
                            }
                        }
                    } else null

                // In WATCH_NOW mode, resolve direct stream on phone (works for YouTube & many non-YouTube HLS/MP4 sites!)
                // In DOWNLOAD_AND_TRANSLATE mode, skip stream chunking and download the full regular MP4 in background.
                val resolvedStreamDeferred: Deferred<ResolvedStreamInfo?> = async {
                    if (isWatchNow && cachedPlayableSource == null) {
                        streamExtractor.resolveStreamInfo(
                            url = cleanUrl,
                            cookiesFile = settingsRepo.cookiesFile,
                            maxHeight = 720,
                            processId = "resolve_stream",
                        )
                    } else null
                }

                val resolvedStream = resolvedStreamDeferred.await()
                assertJobCurrent(jobToken)

                var resolvedTitleHint: String? = resolvedStream?.title
                if (resolvedTitleHint.isNullOrBlank()) {
                    resolvedTitleHint = runCatching {
                        streamExtractor.fetchTitleHint(cleanUrl, settingsRepo.cookiesFile)
                    }.getOrNull()
                }
                if (!resolvedTitleHint.isNullOrBlank() && isJobCurrent(jobToken)) {
                    val cleanSlug = LocalAudioExtractor.buildTitleSlug(
                        resolvedTitleHint.replace(Regex("\\s*-\\s*(YouTube|Dailymotion)$", RegexOption.IGNORE_CASE), ""),
                    )
                    if (cleanSlug.length >= 3) {
                        _state.update { cur ->
                            if (cur.titleSlug.startsWith("SUBCOLAB_Video")) {
                                cur.copy(
                                    titleSlug = cleanSlug,
                                    previousJob = cur.previousJob?.copy(titleSlug = cleanSlug),
                                )
                            } else cur
                        }
                    }
                }

                if (resolvedStream != null && resolvedStream.durationSec > 0.0) {
                    _state.update { it.copy(totalDurationSec = resolvedStream.durationSec) }
                }

                suspend fun saveCompletedMp4ToPack(mp4File: File): String {
                    val resolvedSlug = _state.value.titleSlug.ifBlank { "SUBCOLAB_Translated_Video" }
                    val folder = existingPack?.effectiveFolder ?: MediaStoreSaver.buildShortFolderName(resolvedSlug)
                    savedMp4Result = MediaStoreSaver.saveBinaryFile(
                        appContext, mp4File, "${folder}.mp4", "video/mp4", subfolder = folder,
                    )
                    if (savedMp4Result?.uriString == null) {
                        val fallbackFile = File(packDir(packId), "video.mp4")
                        mp4File.copyTo(fallbackFile, overwrite = true)
                        internalVideoFallbackPath = fallbackFile.absolutePath
                    }
                    if (mp4File == localTempMp4) mp4File.delete()

                    val currentPlayable = savedMp4Result?.uriString ?: internalVideoFallbackPath
                        ?: error("Saved MP4 path unavailable.")
                    val intermediatePack = (existingPack ?: SavedPack(
                        id = packId,
                        titleSlug = resolvedSlug,
                        folderName = folder,
                        sourceUrl = cleanUrl,
                        sourceLang = sourceLang,
                        createdAtMs = System.currentTimeMillis(),
                    )).copy(
                        titleSlug = resolvedSlug,
                        folderName = folder,
                        mp4DisplayPath = savedMp4Result?.displayPath,
                        mp4UriString = savedMp4Result?.uriString,
                        internalVideoPath = internalVideoFallbackPath,
                        videoResolution = 720,
                    )
                    upsertSavedPack(intermediatePack)
                    _state.update {
                        it.copy(
                            playableVideoSource = currentPlayable,
                            isStitching = false,
                            savePackStatus = SavePackStatus.SAVED,
                            saveProgressPercent = 100,
                            saveStatusText = "Video saved to Downloads/SUBCOLAB/$folder/",
                        )
                    }
                    persistSession(wasCompletedOverride = false)
                    return currentPlayable
                }

                // WATCH_NOW fast smooth path: live-stream video via LoopbackMediaProxy into libmpv
                // while downloading ONLY tiny adaptive audio-only chunks (15s-60s) for Colab T4 Whisper!
                val canRunSmoothLiveStream = isWatchNow &&
                    !canReuseSubtitles &&
                    !settings.useCustomTunnel &&
                    cachedPlayableSource == null &&
                    resolvedStream != null

                var tryColabChunkStream = false
                var chunkStreamingFallbackNeeded = false
                var finalVideoSource: String? = null

                if (canRunSmoothLiveStream && resolvedStream != null) {
                    val liveProxy = streamExtractor.createLoopbackProxy(resolvedStream.httpHeaders)
                    replaceLiveStreamProxy(liveProxy)
                    val localLiveVideoUrl = liveProxy.proxiedUrl(resolvedStream.videoUrl)
                    val localLiveAudioUrl = resolvedStream.audioUrl?.takeIf { it.isNotBlank() }
                        ?.let { liveProxy.proxiedUrl(it) }
                    val adaptiveAudioSec = StreamExtractor.computeAdaptiveAudioChunkSec(resolvedStream.durationSec)

                    appendLog("Live stream ready (${resolvedStream.clientUsed}, ${adaptiveAudioSec}s adaptive audio chunks)")
                    _state.update {
                        it.copy(
                            playableVideoSource = localLiveVideoUrl,
                            playableAudioSource = localLiveAudioUrl,
                            isLiveStreaming = true,
                            chunkDownloadStatus = "Live stream ready • ${adaptiveAudioSec}s audio chunks",
                            saveStatusText = "Live streaming (audio-only chunking)",
                        )
                    }

                    val workerScript = loadAssetText("colab_worker.py")
                    val remoteQueueDir = "/content/subcolab_queue_$packId"
                    val queueMutex = Mutex()
                    val queuedUploads = mutableListOf<QueuedChunkUpload>()
                    var activeStreamSession: ColabRuntimeSession? = null
                    var remoteDirPreparedForSession: String? = null
                    var allChunksQueued = false

                    suspend fun syncQueueToSession(session: ColabRuntimeSession) {
                        val snapshot: List<QueuedChunkUpload>
                        val shouldSendEof: Boolean
                        queueMutex.withLock {
                            activeStreamSession = session
                            if (remoteDirPreparedForSession != session.proxyUrl) {
                                colabClient.ensureDirectory(session, remoteQueueDir)
                                remoteDirPreparedForSession = session.proxyUrl
                            }
                            snapshot = queuedUploads.toList()
                            shouldSendEof = allChunksQueued
                        }
                        for (item in snapshot) {
                            colabClient.uploadFile(session, item.audioFile, "$remoteQueueDir/${item.audioFile.name}")
                            colabClient.uploadText(session, item.readyJson, "$remoteQueueDir/${item.readyName}")
                        }
                        if (shouldSendEof) {
                            colabClient.uploadText(session, "DONE", "$remoteQueueDir/EOF")
                        }
                    }

                    val chunkStreamEnv = linkedMapOf(
                        "SUBCOLAB_VIDEO_URL" to "",
                        "SUBCOLAB_SOURCE_LANG" to sourceLang,
                        "SUBCOLAB_CLEAN_AUDIO" to if (settings.cleanAudio) "True" else "False",
                        "SUBCOLAB_VOCAL_BOOST" to "False",
                        "SUBCOLAB_BEAM_SIZE" to "5",
                        "SUBCOLAB_PLAYER_CLIENT" to "default",
                        "SUBCOLAB_MODEL_ID" to settings.modelId.ifBlank { "large-v3" },
                        "SUBCOLAB_EMIT_ORIGINAL" to "False",
                        "SUBCOLAB_SKIP_COLAB_YT" to "True",
                        "SUBCOLAB_STREAMING_QUEUE" to "True",
                        "SUBCOLAB_JOB_ID" to packId,
                        "SUBCOLAB_TOTAL_DURATION_SEC" to resolvedStream.durationSec.toInt().toString(),
                    )
                    resolvedTitleHint?.takeIf { it.isNotBlank() }?.let {
                        chunkStreamEnv["SUBCOLAB_VIDEO_TITLE"] = it
                    }

                    var chunk0Produced = false
                    var workerExecutionError: Throwable? = null
                    val pendingAudioChunks = Channel<VideoChunkInfo>(Channel.UNLIMITED)

                    val chunkUploadJob = launch {
                        for (chunkInfo in pendingAudioChunks) {
                            if (!isJobCurrent(jobToken)) break
                            val audioFile = File(chunkInfo.path)
                            if (audioFile.exists() && audioFile.length() > 0L) {
                                val readyName = "chunk_%03d.ready".format(chunkInfo.index)
                                val readyJson = """{"index":${chunkInfo.index},"audio":"${audioFile.name}","offset_sec":${chunkInfo.startSec},"min_emit_sec":0.0}"""
                                val item = QueuedChunkUpload(audioFile, readyName, readyJson)
                                val sessionToUpload: ColabRuntimeSession?
                                queueMutex.withLock {
                                    queuedUploads.add(item)
                                    sessionToUpload = activeStreamSession
                                    if (sessionToUpload != null && remoteDirPreparedForSession != sessionToUpload.proxyUrl) {
                                        colabClient.ensureDirectory(sessionToUpload, remoteQueueDir)
                                        remoteDirPreparedForSession = sessionToUpload.proxyUrl
                                    }
                                }
                                if (sessionToUpload != null) {
                                    runCatching {
                                        colabClient.uploadFile(sessionToUpload, item.audioFile, "$remoteQueueDir/${item.audioFile.name}")
                                        colabClient.uploadText(sessionToUpload, item.readyJson, "$remoteQueueDir/${item.readyName}")
                                    }.onFailure { upErr ->
                                        AppLogger.w(TAG, "Audio chunk ${chunkInfo.index + 1} upload failed (will resync on recover): ${upErr.message}")
                                    }
                                }
                            }
                        }
                    }

                    val workerJob = launch {
                        try {
                            val runtime = prewarmedRuntimeDeferred!!.await().getOrThrow()
                            assertJobCurrent(jobToken)
                            onPrewarmedSession(runtime)
                            colabClient.executeWorkerWithAutoRecover(
                                workerScript = workerScript,
                                envVars = chunkStreamEnv,
                                initialSession = runtime,
                                onStatus = { msg ->
                                    if (!isJobCurrent(jobToken)) return@executeWorkerWithAutoRecover
                                    _state.update { it.copy(statusMessage = msg, translationProgressText = msg) }
                                    updateNotification("SUBCOLAB — Live T4 GPU", msg, ongoing = true)
                                },
                                onBeforeExecute = { session ->
                                    syncQueueToSession(session)
                                },
                                onOutputLine = { line ->
                                    handleWorkerLine(
                                        jobToken = jobToken,
                                        rawLine = line,
                                        onBotBlocked = {},
                                    )
                                },
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            workerExecutionError = err
                            pendingAudioChunks.close()
                            chunkUploadJob.cancel()
                            streamExtractor.cancelProcess("relay_audio")
                            throw err
                        }
                    }

                    val audioChunkResult = runCatching {
                        streamExtractor.downloadAudioInChunks(
                            context = appContext,
                            streamInfo = resolvedStream,
                            workDir = audioWorkDir,
                            existingProxy = liveProxy,
                            chunkDurationSec = adaptiveAudioSec,
                            processId = "relay_audio",
                            onProgress = { progress, label ->
                                val pct = progress.toInt().coerceIn(0, 99)
                                if (!isJobCurrent(jobToken)) return@downloadAudioInChunks
                                _state.update {
                                    it.copy(
                                        saveProgressPercent = pct,
                                        chunkDownloadStatus = label,
                                    )
                                }
                            },
                            onAudioChunkReady = { chunkInfo ->
                                if (!isJobCurrent(jobToken)) return@downloadAudioInChunks
                                chunk0Produced = true
                                pendingAudioChunks.trySend(chunkInfo)
                            },
                        )
                    }

                    pendingAudioChunks.close()

                    if (audioChunkResult.isSuccess) {
                        chunkUploadJob.join()
                        queueMutex.withLock {
                            allChunksQueued = true
                            val session = activeStreamSession
                            if (session != null) {
                                runCatching { colabClient.uploadText(session, "DONE", "$remoteQueueDir/EOF") }
                            }
                        }

                        workerJob.join()
                        workerExecutionError?.let { throw it }
                        if (subtitleManager.cueCount() <= 0) {
                            throw IllegalStateException("Colab Whisper returned 0 English subtitle cues.")
                        }
                        subtitlesFinishedOk = true
                        finalVideoSource = localLiveVideoUrl
                    } else {
                        chunkUploadJob.cancel()
                        val dlErr = audioChunkResult.exceptionOrNull()!!
                        if (dlErr is CancellationException) throw dlErr
                        workerExecutionError?.let { throw it }
                        if (!chunk0Produced) {
                            workerJob.cancel()
                            closeLiveStreamProxy()
                            runCatching {
                                colabClient.sendAbortSignal(
                                    useCustomTunnel = false,
                                    customTunnelUrl = "",
                                    customTunnelToken = "",
                                )
                            }
                            _state.update {
                                it.copy(
                                    playableVideoSource = cachedPlayableSource,
                                    playableAudioSource = null,
                                    isLiveStreaming = false,
                                    chunkDownloadStatus = null,
                                )
                            }
                            if (!isYouTube && !settings.useCustomTunnel) {
                                AppLogger.w(TAG, "Live audio chunking failed before chunk 0 (${dlErr.message}); switching to Colab-assisted chunked relay.")
                                tryColabChunkStream = true
                            } else {
                                AppLogger.w(TAG, "Live audio chunking failed before chunk 0 (${dlErr.message}); falling back to standard MP4 download.")
                                chunkStreamingFallbackNeeded = true
                            }
                        } else {
                            queueMutex.withLock {
                                allChunksQueued = true
                                activeStreamSession?.let { s ->
                                    runCatching { colabClient.uploadText(s, "DONE", "$remoteQueueDir/EOF") }
                                }
                            }
                            workerJob.cancel()
                            throw dlErr
                        }
                    }
                } else if (isWatchNow && !canReuseSubtitles && !settings.useCustomTunnel && cachedPlayableSource == null && !isYouTube) {
                    // Phone resolve failed on geo-blocked/Cloudflare non-YouTube link: fall back to Colab chunk relay
                    tryColabChunkStream = true
                } else {
                    // DOWNLOAD_AND_TRANSLATE (full regular MP4 download) OR cached reuse OR custom tunnel
                    chunkStreamingFallbackNeeded = true
                }

                if (tryColabChunkStream) {
                    appendLog("Starting Colab chunked relay (45s smoother chunks + parallel Whisper)...")
                    val workerScript = loadAssetText("colab_worker.py")
                    val colabStreamEnv = linkedMapOf(
                        "SUBCOLAB_VIDEO_URL" to cleanUrl,
                        "SUBCOLAB_SOURCE_LANG" to sourceLang,
                        "SUBCOLAB_CLEAN_AUDIO" to if (settings.cleanAudio) "True" else "False",
                        "SUBCOLAB_VOCAL_BOOST" to "False",
                        "SUBCOLAB_BEAM_SIZE" to "5",
                        "SUBCOLAB_PLAYER_CLIENT" to "default",
                        "SUBCOLAB_MODEL_ID" to settings.modelId.ifBlank { "large-v3" },
                        "SUBCOLAB_EMIT_ORIGINAL" to "False",
                        "SUBCOLAB_SKIP_COLAB_YT" to "False",
                        "SUBCOLAB_STREAMING_QUEUE" to "False",
                        "SUBCOLAB_COLAB_CHUNK_STREAM" to "True",
                        "SUBCOLAB_MAX_HEIGHT" to "720",
                        "SUBCOLAB_CHUNK_SEC" to "45",
                        "SUBCOLAB_JOB_ID" to packId,
                    )
                    resolvedTitleHint?.takeIf { it.isNotBlank() }?.let {
                        colabStreamEnv["SUBCOLAB_VIDEO_TITLE"] = it
                    }

                    val remoteChunkQueue = Channel<WorkerEvent.RemoteChunkReady>(Channel.UNLIMITED)
                    val chunkOrderLock = Any()
                    val downloadedChunks = mutableListOf<VideoChunkInfo>()
                    val downloadedIndices = HashSet<Int>()
                    val expectedRemoteChunks = AtomicInteger(-1)
                    val remoteUseMpegTs = AtomicBoolean(true)
                    val activeStreamSession = AtomicReference<ColabRuntimeSession?>(null)
                    var workerExecutionError: Throwable? = null
                    var chunkDownloaderError: Throwable? = null

                    val remoteDownloadJob = launch {
                        try {
                            chunksDir.mkdirs()
                            for (rc in remoteChunkQueue) {
                                if (!isJobCurrent(jobToken)) break
                                val alreadyDone = synchronized(chunkOrderLock) { !downloadedIndices.add(rc.index) }
                                if (alreadyDone) continue

                                remoteUseMpegTs.set(rc.canUseMpegTs)
                                val ext = if (rc.canUseMpegTs) "ts" else "mkv"
                                val localChunkFile = File(chunksDir, "chunk_%03d.$ext".format(rc.index))

                                val dlResult = runCatching {
                                    val sess = activeStreamSession.get() ?: colabClient.activeSession
                                        ?: colabClient.ensureWarmT4Runtime { }
                                    colabClient.downloadRemoteFile(sess, rc.remotePath, localChunkFile)
                                    runCatching { colabClient.deleteRemoteFile(sess, rc.remotePath) }
                                }
                                if (dlResult.isFailure) {
                                    val err = dlResult.exceptionOrNull()!!
                                    if (err is CancellationException) throw err
                                    // Retry once against active session in case runtime recovered
                                    val retrySess = colabClient.activeSession ?: activeStreamSession.get()
                                    if (retrySess != null) {
                                        colabClient.downloadRemoteFile(retrySess, rc.remotePath, localChunkFile)
                                        runCatching { colabClient.deleteRemoteFile(retrySess, rc.remotePath) }
                                    } else {
                                        throw err
                                    }
                                }

                                val chunkInfo = VideoChunkInfo(
                                    index = rc.index,
                                    path = localChunkFile.absolutePath,
                                    startSec = rc.startSec,
                                    endSec = rc.endSec,
                                )
                                val orderedSnapshot = synchronized(chunkOrderLock) {
                                    downloadedChunks.add(chunkInfo)
                                    downloadedChunks.sortedBy { it.index }
                                }
                                val totalExpected = expectedRemoteChunks.get()
                                val pct = if (totalExpected > 0) {
                                    ((orderedSnapshot.size * 100) / totalExpected).coerceIn(1, 99)
                                } else {
                                    (orderedSnapshot.size * 8).coerceIn(5, 95)
                                }
                                _state.update { cur ->
                                    cur.copy(
                                        playableChunks = orderedSnapshot,
                                        chunkDownloadStatus = "Chunk ${rc.index + 1} (${rc.endSec.toInt()}s) ready",
                                        savePackStatus = SavePackStatus.SAVING,
                                        saveProgressPercent = pct,
                                        saveStatusText = if (totalExpected > 0) {
                                            "Streaming video chunks (${orderedSnapshot.size}/$totalExpected)..."
                                        } else {
                                            "Streaming video chunk ${orderedSnapshot.size}..."
                                        },
                                    )
                                }
                                maybeNotifyPlayerReady(jobToken)
                            }
                            if (expectedRemoteChunks.get() > 0 && isJobCurrent(jobToken)) {
                                _state.update {
                                    it.copy(
                                        isChunkDownloadComplete = true,
                                        saveProgressPercent = 100,
                                    )
                                }
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            chunkDownloaderError = err
                        }
                    }

                    val colabWorkerJob = launch {
                        try {
                            val runtime = prewarmedRuntimeDeferred!!.await().getOrThrow()
                            assertJobCurrent(jobToken)
                            activeStreamSession.set(runtime)
                            onPrewarmedSession(runtime)
                            colabClient.executeWorkerWithAutoRecover(
                                workerScript = workerScript,
                                envVars = colabStreamEnv,
                                initialSession = runtime,
                                onStatus = { msg ->
                                    if (!isJobCurrent(jobToken)) return@executeWorkerWithAutoRecover
                                    _state.update { it.copy(statusMessage = msg, translationProgressText = msg) }
                                    updateNotification("SUBCOLAB — T4 GPU", msg, ongoing = true)
                                },
                                onBeforeExecute = { session ->
                                    activeStreamSession.set(session)
                                    if (settingsRepo.cookiesFile.exists() && settingsRepo.cookiesFile.length() > 0L) {
                                        runCatching { colabClient.uploadFile(session, settingsRepo.cookiesFile, "/content/cookies.txt") }
                                    }
                                },
                                onOutputLine = { line ->
                                    handleWorkerLine(
                                        jobToken = jobToken,
                                        rawLine = line,
                                        onBotBlocked = {},
                                        onRemoteChunkReady = { rc ->
                                            remoteUseMpegTs.set(rc.canUseMpegTs)
                                            remoteChunkQueue.trySend(rc)
                                        },
                                        onRemoteChunksDone = { done ->
                                            expectedRemoteChunks.set(done.totalCount)
                                            if (done.totalDurationSec > 0.0) {
                                                _state.update { cur ->
                                                    cur.copy(totalDurationSec = maxOf(cur.totalDurationSec, done.totalDurationSec))
                                                }
                                            }
                                            remoteChunkQueue.close()
                                        },
                                    )
                                },
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            workerExecutionError = err
                            remoteChunkQueue.close(err)
                            remoteDownloadJob.cancel()
                            throw err
                        } finally {
                            remoteChunkQueue.close()
                        }
                    }

                    colabWorkerJob.join()
                    workerExecutionError?.let { throw it }
                    if (subtitleManager.cueCount() <= 0) {
                        remoteDownloadJob.cancel()
                        throw IllegalStateException("Colab Whisper returned 0 English subtitle cues.")
                    }
                    subtitlesFinishedOk = true

                    remoteDownloadJob.join()
                    chunkDownloaderError?.let { throw it }
                    val allChunks = synchronized(chunkOrderLock) { downloadedChunks.sortedBy { it.index } }
                    if (allChunks.isEmpty()) {
                        throw IllegalStateException("Colab chunked stream returned 0 video chunks.")
                    }
                    _state.update {
                        it.copy(
                            isChunkDownloadComplete = true,
                            isStitching = true,
                            saveProgressPercent = 100,
                            saveStatusText = "Stitching ${allChunks.size} chunks into MP4...",
                        )
                    }
                    val stitched = streamExtractor.stitchChunksToMp4(
                        context = appContext,
                        chunks = allChunks,
                        outputMp4 = localTempMp4,
                        canUseMpegTs = remoteUseMpegTs.get(),
                    )
                    finalVideoSource = saveCompletedMp4ToPack(stitched)
                    chunksDirScheduledForDeferredDelete = true
                    scheduleChunksDirCleanup(chunksDir)
                }

                if (chunkStreamingFallbackNeeded) {
                    // Standard parallel download + audio extraction fallback (for short videos, custom tunnel, cached reuse, or chunk fallback)
                    val ytAudioWinner = CompletableDeferred<File>()
                    val ytProducersFailed = AtomicInteger(0)

                    if (isYouTube && !canReuseSubtitles) {
                        launch {
                            runCatching {
                                appendLog("Starting phone audio fetch (parallel with MP4 download)...")
                                val relayRaw = streamExtractor.downloadAudioForRelay(
                                    url = cleanUrl,
                                    destDir = audioWorkDir,
                                    cookiesFile = settingsRepo.cookiesFile,
                                    processId = "relay_audio",
                                )
                                val compactAudio = LocalAudioExtractor.extractAudioFromFile(appContext, relayRaw, audioWorkDir)
                                if (compactAudio != relayRaw && relayRaw.exists()) relayRaw.delete()
                                compactAudio
                            }.onSuccess { audio ->
                                if (!ytAudioWinner.complete(audio)) {
                                    runCatching { audio.delete() }
                                } else {
                                    appendLog("Audio ready via fast relay (${audio.length() / 1024} KB)")
                                }
                            }.onFailure { err ->
                                AppLogger.w(TAG, "Parallel relay audio failed (${err.message}); MP4 fallback will supply audio.")
                                if (ytProducersFailed.incrementAndGet() >= 2 && ytAudioWinner.isActive) {
                                    ytAudioWinner.completeExceptionally(err)
                                }
                            }
                        }
                    }

                    var phoneMp4Error: Throwable? = null
                    val mp4DownloadDeferred = async {
                        if (cachedPlayableSource != null) {
                            if (isYouTube && !canReuseSubtitles && ytAudioWinner.isActive) {
                                runCatching {
                                    if (cachedPlayableSource.startsWith("content://")) {
                                        LocalAudioExtractor.extractAudioForColab(
                                            appContext, Uri.parse(cachedPlayableSource), audioWorkDir,
                                        ).audioFile
                                    } else {
                                        LocalAudioExtractor.extractAudioFromFile(
                                            appContext, File(cachedPlayableSource), audioWorkDir,
                                        )
                                    }
                                }.onSuccess { extracted ->
                                    if (ytAudioWinner.complete(extracted)) {
                                        streamExtractor.cancelProcess("relay_audio")
                                    } else {
                                        runCatching { extracted.delete() }
                                    }
                                }.onFailure { err ->
                                    if (ytProducersFailed.incrementAndGet() >= 2 && ytAudioWinner.isActive) {
                                        ytAudioWinner.completeExceptionally(err)
                                    }
                                }
                            }
                            return@async null
                        }
                        appendLog("Downloading <=720p MP4 to phone...")
                        streamExtractor.cancelProcess("save_mp4")
                        try {
                            val downloadedMp4 = streamExtractor.downloadMp4ForSave(
                                url = cleanUrl,
                                outputMp4 = localTempMp4,
                                cookiesFile = settingsRepo.cookiesFile,
                                maxHeight = 720,
                                processId = "save_mp4",
                            ) { progress, _ ->
                                val pct = progress.toInt().coerceIn(0, 99)
                                if (!isJobCurrent(jobToken)) return@downloadMp4ForSave
                                _state.update {
                                    it.copy(
                                        savePackStatus = SavePackStatus.SAVING,
                                        saveProgressPercent = pct,
                                        saveStatusText = "Downloading video ($pct%)...",
                                    )
                                }
                                if (_state.value.phase == JobPhase.SAVING_PACK) {
                                    updateNotification(
                                        "SUBCOLAB — Finishing Video ($pct%)",
                                        "Subtitles ready (${subtitleManager.cueCount()} cues) • Downloading MP4 $pct%",
                                        ongoing = true,
                                    )
                                }
                            }
                            assertJobCurrent(jobToken)

                            if (isYouTube && !canReuseSubtitles && ytAudioWinner.isActive) {
                                runCatching {
                                    LocalAudioExtractor.extractAudioFromFile(appContext, downloadedMp4, audioWorkDir)
                                }.onSuccess { extracted ->
                                    if (ytAudioWinner.complete(extracted)) {
                                        streamExtractor.cancelProcess("relay_audio")
                                        appendLog("Extracted audio from downloaded MP4 (${extracted.length() / 1024} KB) -> sending to Colab!")
                                    } else {
                                        runCatching { extracted.delete() }
                                    }
                                }.onFailure { extractErr ->
                                    if (ytProducersFailed.incrementAndGet() >= 2 && ytAudioWinner.isActive) {
                                        ytAudioWinner.completeExceptionally(extractErr)
                                    }
                                }
                            }

                            saveCompletedMp4ToPack(downloadedMp4)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Throwable) {
                            if (isYouTube && !canReuseSubtitles && ytProducersFailed.incrementAndGet() >= 2 && ytAudioWinner.isActive) {
                                ytAudioWinner.completeExceptionally(err)
                            }
                            if (!isYouTube && !settings.useCustomTunnel && !canReuseSubtitles) {
                                phoneMp4Error = err
                                AppLogger.w(TAG, "Phone MP4 download failed for non-YouTube URL (${err.message}); will fall back to Colab MP4 relay if subtitles succeed.")
                                null
                            } else {
                                throw err
                            }
                        }
                    }

                    if (!canReuseSubtitles) {
                        try {
                            val preparedLocalAudio: File? = if (isYouTube) {
                                _state.update {
                                    it.copy(
                                        translationProgressText = "Preparing YouTube audio on phone Wi-Fi + warming T4...",
                                    )
                                }
                                ytAudioWinner.await()
                            } else null

                            val resolvedTitle = resolvedTitleHint ?: _state.value.titleSlug
                            runWhisperTranslation(
                                jobToken = jobToken,
                                cleanUrl = cleanUrl,
                                sourceLang = sourceLang,
                                videoTitleHint = resolvedTitle,
                                initialLocalAudioFile = preparedLocalAudio,
                                prewarmedRuntimeDeferred = prewarmedRuntimeDeferred,
                                allowPhoneRelayFallback = true,
                                skipColabYouTube = isYouTube,
                                options = SubtitleRetranslateOptions(
                                    sourceLang = sourceLang,
                                    cleanAudio = settings.cleanAudio,
                                    vocalBoost = false,
                                    modelId = settings.modelId.ifBlank { "large-v3" },
                                    beamSize = 5,
                                    initialPrompt = "",
                                ),
                                silentBackground = false,
                            )
                            subtitlesFinishedOk = true
                        } catch (t: Throwable) {
                            streamExtractor.cancelProcess("save_mp4")
                            streamExtractor.cancelProcess("relay_audio")
                            throw t
                        }
                    }

                    assertJobCurrent(jobToken)

                    if (!mp4DownloadDeferred.isCompleted) {
                        val currentPct = _state.value.saveProgressPercent
                        _state.update {
                            it.copy(
                                phase = JobPhase.SAVING_PACK,
                                statusMessage = "Subtitles done — finishing video download ($currentPct%)...",
                                translationProgressText = if (subtitleManager.origCueCount() > 0) {
                                    "${subtitleManager.cueCount()} EN + ${subtitleManager.origCueCount()} orig cues ready"
                                } else {
                                    "${subtitleManager.cueCount()} English cues ready"
                                },
                            )
                        }
                        updateNotification(
                            "SUBCOLAB — Finishing Video ($currentPct%)",
                            "Subtitles ready (${subtitleManager.cueCount()} cues) • Waiting for MP4 download",
                            ongoing = true,
                        )
                    }

                    finalVideoSource = mp4DownloadDeferred.await() ?: cachedPlayableSource
                    if (finalVideoSource == null && !isYouTube && !settings.useCustomTunnel) {
                        val relayMp4 = runCatching {
                            downloadMp4FromColab(
                                jobToken = jobToken,
                                cleanUrl = cleanUrl,
                                maxHeight = 720,
                                destMp4 = localTempMp4,
                            )
                        }.getOrElse { relayErr ->
                            val origMsg = phoneMp4Error?.message
                            if (origMsg != null) {
                                throw IllegalStateException("$origMsg (Colab relay also failed: ${relayErr.message})", relayErr)
                            }
                            throw relayErr
                        }
                        finalVideoSource = saveCompletedMp4ToPack(relayMp4)
                    }
                }

                val resolvedFinalVideo = finalVideoSource ?: cachedPlayableSource
                    ?: error("Downloaded MP4 copy unavailable.")
                assertJobCurrent(jobToken)

                _state.update {
                    it.copy(
                        phase = JobPhase.SAVING_PACK,
                        statusMessage = "Saving subtitles to Downloads/SUBCOLAB/...",
                        saveStatusText = "Writing SRT/ASS/VTT...",
                    )
                }

                val slug = _state.value.titleSlug.ifBlank { "SUBCOLAB_Translated_Video" }
                val latestExisting = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: existingPack
                val folder = latestExisting?.effectiveFolder ?: MediaStoreSaver.buildShortFolderName(slug)

                val subBundle = saveSubtitlesToDownloads(
                    packId = packId,
                    folder = folder,
                    titleSlug = slug,
                    oldPack = latestExisting,
                )

                val finalPack = (latestExisting ?: SavedPack(
                    id = packId,
                    titleSlug = slug,
                    folderName = folder,
                    sourceUrl = cleanUrl,
                    sourceLang = sourceLang,
                    createdAtMs = System.currentTimeMillis(),
                )).copy(
                    titleSlug = slug,
                    folderName = folder,
                    detectedLang = subBundle.detectedOrigLang,
                    cueCount = subBundle.enCueCount,
                    origCueCount = subBundle.origCueCount,
                    videoResolution = latestExisting?.videoResolution ?: 720,
                    mp4DisplayPath = savedMp4Result?.displayPath ?: latestExisting?.mp4DisplayPath,
                    mp4UriString = savedMp4Result?.uriString ?: latestExisting?.mp4UriString
                        ?: resolvedFinalVideo.takeIf { it.startsWith("content://") },
                    srtDisplayPath = subBundle.enSrt?.displayPath,
                    enSrtUriString = subBundle.enSrt?.uriString,
                    enAssUriString = subBundle.enAss?.uriString,
                    enVttUriString = subBundle.enVtt?.uriString,
                    origSrtDisplayPath = subBundle.origSrt?.displayPath,
                    origSrtUriString = subBundle.origSrt?.uriString,
                    origAssUriString = subBundle.origAss?.uriString,
                    origVttUriString = subBundle.origVtt?.uriString,
                    internalEnSrtPath = subBundle.internalEnSrtPath,
                    internalOrigSrtPath = subBundle.internalOrigSrtPath,
                    internalVideoPath = internalVideoFallbackPath ?: latestExisting?.internalVideoPath,
                    createdAtMs = System.currentTimeMillis(),
                )
                upsertSavedPack(finalPack)

                val shouldPromptOrNotify = promptedReadyForJobToken != jobToken
                promptedReadyForJobToken = jobToken
                val showDialogInApp = shouldPromptOrNotify && (!fromExternalShare || SubcolabApp.isAppInForeground)
                val finishedLiveStream = _state.value.isLiveStreaming && finalVideoSource?.startsWith("http") == true

                _state.update {
                    it.copy(
                        phase = JobPhase.COMPLETED,
                        statusMessage = if (finishedLiveStream) {
                            "Live Stream + ${subBundle.enCueCount} EN cues ready (subtitles saved)"
                        } else {
                            "Ready — Video & ${subBundle.enCueCount} EN" +
                                (if (subBundle.origCueCount > 0) " + ${subBundle.origCueCount} orig" else "") +
                                " cues saved"
                        },
                        translationProgressText = if (subBundle.origCueCount > 0) {
                            "${subBundle.enCueCount} EN • ${subBundle.origCueCount} orig cues saved"
                        } else {
                            "${subBundle.enCueCount} English cues saved"
                        },
                        cueCount = subBundle.enCueCount,
                        origCueCount = subBundle.origCueCount,
                        detectedOrigLang = subBundle.detectedOrigLang,
                        liveSrtPath = liveSrtFile.absolutePath,
                        liveOrigSrtPath = liveOrigSrtFile.takeIf { f -> f.exists() && f.length() > 0L }?.absolutePath,
                        isPlayerReady = true,
                        isReadyPromptVisible = showDialogInApp,
                        isStitching = false,
                        chunkDownloadStatus = if (finishedLiveStream) "Live stream • All subtitles ready" else null,
                        playableVideoSource = resolvedFinalVideo,
                        activePackId = null,
                        savePackStatus = SavePackStatus.SAVED,
                        saveProgressPercent = 100,
                        saveStatusText = if (finishedLiveStream) {
                            "Subtitles saved to Downloads/SUBCOLAB/$folder/ (tap Video in Downloads to save MP4)"
                        } else {
                            "Saved to Downloads/SUBCOLAB/$folder/"
                        },
                        previousJob = it.previousJob?.copy(
                            titleSlug = slug,
                            subtitlesCompleted = true,
                            savedMp4Path = internalVideoFallbackPath,
                            savedMp4Uri = finalPack.mp4UriString,
                            savedSrtPath = subBundle.enSrt?.displayPath,
                        ),
                    )
                }
                persistSession(wasCompletedOverride = true)
                AppLogger.i(TAG, "Pack complete: $folder (EN=${subBundle.enCueCount}, Orig=${subBundle.origCueCount}, live=$finishedLiveStream)")
                if (shouldPromptOrNotify) {
                    if (isWatchNow && !SubcolabApp.isAppInForeground) {
                        val launched = launchPlayerFromBackground()
                        playerReadyNotificationCallback?.invoke(slug, subBundle.enCueCount, true, finishedLiveStream, !launched)
                    } else {
                        playerReadyNotificationCallback?.invoke(slug, subBundle.enCueCount, true, finishedLiveStream, false)
                    }
                } else {
                    updateNotification(
                        "SUBCOLAB — Saved ($folder)",
                        if (finishedLiveStream) {
                            "All ${subBundle.enCueCount} EN cues saved to Downloads/SUBCOLAB/$folder/"
                        } else {
                            "Video & ${subBundle.enCueCount} EN cues saved to Downloads/SUBCOLAB/$folder/"
                        },
                        ongoing = false,
                    )
                }
            }
        } catch (ce: CancellationException) {
            if (!chunksDirScheduledForDeferredDelete && chunksDir.exists()) {
                scheduleChunksDirCleanup(chunksDir)
            }
            throw ce
        } catch (t: Throwable) {
            // If subtitles finished even though video download failed, still preserve the subtitle pack in Downloads!
            if (subtitlesFinishedOk && subtitleManager.cueCount() > 0 && isJobCurrent(jobToken)) {
                val slug = _state.value.titleSlug.ifBlank { initialSlug }
                val latestExisting = _state.value.savedPacks.firstOrNull { it.effectiveId == packId } ?: existingPack
                val folder = latestExisting?.effectiveFolder ?: MediaStoreSaver.buildShortFolderName(slug)
                runCatching {
                    val subBundle = saveSubtitlesToDownloads(packId, folder, slug, latestExisting)
                    val partialPack = (latestExisting ?: SavedPack(
                        id = packId,
                        titleSlug = slug,
                        folderName = folder,
                        sourceUrl = cleanUrl,
                        sourceLang = sourceLang,
                        createdAtMs = System.currentTimeMillis(),
                    )).copy(
                        titleSlug = slug,
                        folderName = folder,
                        detectedLang = subBundle.detectedOrigLang,
                        cueCount = subBundle.enCueCount,
                        origCueCount = subBundle.origCueCount,
                        srtDisplayPath = subBundle.enSrt?.displayPath,
                        enSrtUriString = subBundle.enSrt?.uriString,
                        enAssUriString = subBundle.enAss?.uriString,
                        enVttUriString = subBundle.enVtt?.uriString,
                        origSrtDisplayPath = subBundle.origSrt?.displayPath,
                        origSrtUriString = subBundle.origSrt?.uriString,
                        origAssUriString = subBundle.origAss?.uriString,
                        origVttUriString = subBundle.origVtt?.uriString,
                        internalEnSrtPath = subBundle.internalEnSrtPath,
                        internalOrigSrtPath = subBundle.internalOrigSrtPath,
                        createdAtMs = System.currentTimeMillis(),
                    )
                    upsertSavedPack(partialPack)
                }
            }
            handlePipelineFailure(jobToken, t, fromExternalShare)
        } finally {
            runCatching { localTempMp4.delete() }
            runCatching { audioWorkDir.deleteRecursively() }
            if (!chunksDirScheduledForDeferredDelete && chunksDir.exists()) {
                scheduleChunksDirCleanup(chunksDir)
            }
        }
    }

    private suspend fun runLocalVideoPipeline(
        jobToken: Long,
        uri: Uri,
        videoUriString: String,
        sourceLang: String,
    ) {
        assertJobCurrent(jobToken)
        subtitleManager.reset()
        if (liveSrtFile.exists()) liveSrtFile.delete()
        if (liveOrigSrtFile.exists()) liveOrigSrtFile.delete()
        liveSrtFile.writeText("", Charsets.UTF_8)
        _liveSrtRevision.value = 0L

        val settings = settingsRepo.settings.value
        val initialName = LocalAudioExtractor.resolveDisplayName(appContext, uri)
        val initialSlug = LocalAudioExtractor.buildTitleSlug(initialName)
        val existingPack = _state.value.savedPacks.firstOrNull { it.isLocalVideo && it.sourceUrl == videoUriString }
        val packId = existingPack?.effectiveId ?: UUID.randomUUID().toString().take(12)

        val initialCache = PreviousJobCache(
            rawUrl = videoUriString,
            normalizedUrl = videoUriString,
            sourceLang = sourceLang,
            mode = WorkflowMode.LOCAL_VIDEO_SUBTITLES,
            titleSlug = initialSlug,
            subtitlesCompleted = false,
            localVideoUri = videoUriString,
        )

        _state.update {
            it.copy(
                phase = JobPhase.EXTRACTING_STREAM,
                statusMessage = "Extracting audio from $initialName...",
                detailMessage = initialName,
                currentUrl = videoUriString,
                titleSlug = initialSlug,
                sourceLang = sourceLang,
                detectedOrigLang = null,
                mode = WorkflowMode.LOCAL_VIDEO_SUBTITLES,
                cueCount = 0,
                origCueCount = 0,
                completedChunks = emptyList(),
                isPlayerReady = false,
                isReadyPromptVisible = false,
                fromExternalShare = false,
                selectedLocalVideoName = initialName,
                activePackId = packId,
                translationProgressText = "Preparing audio track from device file...",
                liveSrtPath = liveSrtFile.absolutePath,
                liveOrigSrtPath = null,
                playableVideoSource = videoUriString,
                savePackStatus = SavePackStatus.IDLE,
                saveProgressPercent = 0,
                saveStatusText = null,
                errorMessage = null,
                previousJob = initialCache,
                logTail = emptyList(),
            )
        }
        persistSession(wasCompletedOverride = false)
        updateNotification("SUBCOLAB — Local Video", "Extracting audio from $initialName...", ongoing = true)

        var extractedAudioFile: File? = null
        try {
            val localDir = File(appContext.cacheDir, "local_audio")
            val extracted = LocalAudioExtractor.extractAudioForColab(
                context = appContext,
                uri = uri,
                workDir = localDir,
                onStatus = { status ->
                    if (!isJobCurrent(jobToken)) return@extractAudioForColab
                    _state.update { it.copy(statusMessage = status, translationProgressText = status) }
                },
            )
            extractedAudioFile = extracted.audioFile
            assertJobCurrent(jobToken)

            _state.update {
                it.copy(
                    titleSlug = extracted.titleSlug,
                    previousJob = it.previousJob?.copy(titleSlug = extracted.titleSlug),
                )
            }

            runWhisperTranslation(
                jobToken = jobToken,
                cleanUrl = "",
                sourceLang = sourceLang,
                videoTitleHint = extracted.displayTitle,
                initialLocalAudioFile = extracted.audioFile,
                prewarmedRuntimeDeferred = null,
                allowPhoneRelayFallback = false,
                skipColabYouTube = false,
                options = SubtitleRetranslateOptions(
                    sourceLang = sourceLang,
                    cleanAudio = settings.cleanAudio,
                    vocalBoost = false,
                    modelId = settings.modelId.ifBlank { "large-v3" },
                    beamSize = 5,
                    initialPrompt = "",
                ),
                silentBackground = false,
            )

            assertJobCurrent(jobToken)
            _state.update {
                it.copy(
                    phase = JobPhase.SAVING_PACK,
                    statusMessage = "Saving subtitles to Downloads/SUBCOLAB/...",
                    savePackStatus = SavePackStatus.SAVING,
                    saveProgressPercent = 80,
                    saveStatusText = "Writing EN + Original SRT/ASS/VTT...",
                )
            }

            val slug = _state.value.titleSlug.ifBlank { extracted.titleSlug }
            val folder = existingPack?.effectiveFolder ?: MediaStoreSaver.buildShortFolderName(slug)
            val subBundle = saveSubtitlesToDownloads(packId, folder, slug, existingPack)

            val pack = (existingPack ?: SavedPack(
                id = packId,
                titleSlug = slug,
                folderName = folder,
                sourceUrl = videoUriString,
                sourceLang = sourceLang,
                isLocalVideo = true,
                createdAtMs = System.currentTimeMillis(),
            )).copy(
                titleSlug = slug,
                folderName = folder,
                detectedLang = subBundle.detectedOrigLang,
                cueCount = subBundle.enCueCount,
                origCueCount = subBundle.origCueCount,
                mp4DisplayPath = null,
                mp4UriString = videoUriString,
                srtDisplayPath = subBundle.enSrt?.displayPath,
                enSrtUriString = subBundle.enSrt?.uriString,
                enAssUriString = subBundle.enAss?.uriString,
                enVttUriString = subBundle.enVtt?.uriString,
                origSrtDisplayPath = subBundle.origSrt?.displayPath,
                origSrtUriString = subBundle.origSrt?.uriString,
                origAssUriString = subBundle.origAss?.uriString,
                origVttUriString = subBundle.origVtt?.uriString,
                internalEnSrtPath = subBundle.internalEnSrtPath,
                internalOrigSrtPath = subBundle.internalOrigSrtPath,
                createdAtMs = System.currentTimeMillis(),
            )
            upsertSavedPack(pack)

            _state.update {
                it.copy(
                    phase = JobPhase.COMPLETED,
                    statusMessage = "Ready — ${subBundle.enCueCount} EN" +
                        (if (subBundle.origCueCount > 0) " + ${subBundle.origCueCount} orig" else "") +
                        " cues generated",
                    translationProgressText = if (subBundle.origCueCount > 0) {
                        "${subBundle.enCueCount} EN • ${subBundle.origCueCount} orig cues"
                    } else {
                        "${subBundle.enCueCount} English cues"
                    },
                    cueCount = subBundle.enCueCount,
                    origCueCount = subBundle.origCueCount,
                    detectedOrigLang = subBundle.detectedOrigLang,
                    liveSrtPath = liveSrtFile.absolutePath,
                    liveOrigSrtPath = liveOrigSrtFile.takeIf { f -> f.exists() && f.length() > 0L }?.absolutePath,
                    isPlayerReady = true,
                    isReadyPromptVisible = SubcolabApp.isAppInForeground,
                    playableVideoSource = videoUriString,
                    activePackId = null,
                    savePackStatus = SavePackStatus.SAVED,
                    saveProgressPercent = 100,
                    saveStatusText = "Subtitles saved to Downloads/SUBCOLAB/$folder/",
                    previousJob = it.previousJob?.copy(
                        titleSlug = slug,
                        subtitlesCompleted = true,
                        savedSrtPath = subBundle.enSrt?.displayPath,
                        localVideoUri = videoUriString,
                    ),
                )
            }
            persistSession(wasCompletedOverride = true)
            AppLogger.i(TAG, "Local video subtitle job completed (${subBundle.enCueCount} EN cues)")
            playerReadyNotificationCallback?.invoke(slug, subBundle.enCueCount, true, false, false)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            handlePipelineFailure(jobToken, t, fromExternalShare = false)
        } finally {
            runCatching { extractedAudioFile?.delete() }
        }
    }

    private suspend fun runPackVideoDownload(
        jobToken: Long,
        pack: SavedPack,
        maxHeight: Int,
    ) {
        val packId = pack.effectiveId
        val folder = pack.effectiveFolder
        val tempMp4 = File(appContext.cacheDir, "subcolab_pack_${packId}_redl.mp4")

        _state.update {
            it.copy(
                phase = JobPhase.SAVING_PACK,
                statusMessage = "Downloading ${maxHeight}p MP4 for ${pack.titleSlug.replace('_', ' ')}...",
                activePackId = packId,
                savePackStatus = SavePackStatus.SAVING,
                saveProgressPercent = 0,
                saveStatusText = "Downloading ${maxHeight}p video in background...",
                errorMessage = null,
            )
        }
        updateNotification("SUBCOLAB — Downloading ${maxHeight}p Video", pack.titleSlug.replace('_', ' '), ongoing = true)

        try {
            streamExtractor.cancelProcess("save_mp4")
            val isYt = streamExtractor.isYouTubeUrl(pack.sourceUrl)
            val useTunnel = settingsRepo.settings.value.useCustomTunnel
            val file = try {
                streamExtractor.downloadMp4ForSave(
                    url = pack.sourceUrl,
                    outputMp4 = tempMp4,
                    cookiesFile = settingsRepo.cookiesFile,
                    maxHeight = maxHeight,
                    processId = "save_mp4",
                ) { progress, _ ->
                    val pct = progress.toInt().coerceIn(0, 99)
                    if (!isJobCurrent(jobToken)) return@downloadMp4ForSave
                    _state.update {
                        it.copy(
                            saveProgressPercent = pct,
                            saveStatusText = "Downloading ${maxHeight}p MP4 ($pct%)...",
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (phoneErr: Throwable) {
                if (!isYt && !useTunnel) {
                    AppLogger.w(TAG, "Pack MP4 download failed on phone (${phoneErr.message}); retrying via Colab relay.")
                    downloadMp4FromColab(jobToken, pack.sourceUrl, maxHeight, tempMp4)
                } else {
                    throw phoneErr
                }
            }
            assertJobCurrent(jobToken)

            MediaStoreSaver.deleteSavedEntry(appContext, pack.mp4UriString, pack.mp4DisplayPath)
            pack.internalVideoPath?.let { runCatching { File(it).delete() } }

            val savedMp4 = MediaStoreSaver.saveBinaryFile(
                appContext, file, "${folder}.mp4", "video/mp4", subfolder = folder,
            )
            var fallbackPath: String? = null
            if (savedMp4?.uriString == null) {
                val dest = File(packDir(packId), "video.mp4")
                file.copyTo(dest, overwrite = true)
                fallbackPath = dest.absolutePath
            }
            file.delete()

            val updated = pack.copy(
                mp4DisplayPath = savedMp4?.displayPath,
                mp4UriString = savedMp4?.uriString,
                internalVideoPath = fallbackPath,
                videoResolution = maxHeight,
            )
            upsertSavedPack(updated)

            _state.update {
                it.copy(
                    phase = JobPhase.COMPLETED,
                    statusMessage = "Downloaded ${maxHeight}p MP4 to Downloads/SUBCOLAB/$folder/",
                    activePackId = null,
                    savePackStatus = SavePackStatus.SAVED,
                    saveProgressPercent = 100,
                    saveStatusText = "Video saved ($maxHeight p)",
                    playableVideoSource = savedMp4?.uriString ?: fallbackPath ?: it.playableVideoSource,
                )
            }
            persistSession(wasCompletedOverride = true)
            updateNotification(
                "SUBCOLAB — ${maxHeight}p Video Ready",
                "${pack.titleSlug.replace('_', ' ')} saved to Downloads/SUBCOLAB/$folder/",
                ongoing = false,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            handlePipelineFailure(jobToken, t, fromExternalShare = false)
        } finally {
            runCatching { tempMp4.delete() }
        }
    }

    private suspend fun runPackSubtitleGeneration(
        jobToken: Long,
        pack: SavedPack,
        options: SubtitleRetranslateOptions,
    ) {
        val packId = pack.effectiveId
        val folder = pack.effectiveFolder

        subtitleManager.reset()
        if (liveSrtFile.exists()) liveSrtFile.delete()
        if (liveOrigSrtFile.exists()) liveOrigSrtFile.delete()
        liveSrtFile.writeText("", Charsets.UTF_8)
        _liveSrtRevision.value = 0L

        _state.update {
            it.copy(
                phase = JobPhase.BOOTING_GPU,
                statusMessage = "Generating subtitles for ${pack.titleSlug.replace('_', ' ')}...",
                titleSlug = pack.titleSlug,
                sourceLang = options.sourceLang,
                detectedOrigLang = null,
                cueCount = 0,
                origCueCount = 0,
                completedChunks = emptyList(),
                activePackId = packId,
                translationProgressText = "Preparing audio + Colab T4...",
                errorMessage = null,
            )
        }
        updateNotification("SUBCOLAB — Generating Subtitles", pack.titleSlug.replace('_', ' '), ongoing = true)

        val localDir = File(appContext.cacheDir, "retrans_$packId").apply { mkdirs() }
        var tempAudioFile: File? = null

        try {
            // Prefer extracting audio directly from the already-downloaded video on phone!
            val playableLocalUri = pack.mp4UriString?.takeIf { isVideoSourcePlayable(it) }
                ?: if (pack.isLocalVideo) pack.sourceUrl.takeIf { isVideoSourcePlayable(it) } else null
            val internalFile = pack.internalVideoPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }

            if (playableLocalUri != null) {
                val extracted = LocalAudioExtractor.extractAudioForColab(
                    context = appContext,
                    uri = Uri.parse(playableLocalUri),
                    workDir = localDir,
                    onStatus = { msg ->
                        if (isJobCurrent(jobToken)) {
                            _state.update { it.copy(statusMessage = msg, translationProgressText = msg) }
                        }
                    },
                )
                tempAudioFile = extracted.audioFile
            } else if (internalFile != null) {
                tempAudioFile = LocalAudioExtractor.extractAudioFromFile(appContext, internalFile, localDir)
            } else if (!pack.isLocalVideo && UrlUnwrapper.isValidVideoUrl(pack.sourceUrl)) {
                val isYt = streamExtractor.isYouTubeUrl(pack.sourceUrl)
                if (isYt) {
                    _state.update {
                        it.copy(
                            phase = JobPhase.EXTRACTING_STREAM,
                            statusMessage = "Fetching YouTube audio on phone Wi-Fi...",
                            translationProgressText = "Downloading audio on phone...",
                        )
                    }
                    val relayed = streamExtractor.downloadAudioForRelay(
                        url = pack.sourceUrl,
                        destDir = localDir,
                        cookiesFile = settingsRepo.cookiesFile,
                        processId = "relay_audio",
                    )
                    tempAudioFile = LocalAudioExtractor.extractAudioFromFile(appContext, relayed, localDir)
                }
            }

            assertJobCurrent(jobToken)
            val isYt = !pack.isLocalVideo && streamExtractor.isYouTubeUrl(pack.sourceUrl)

            runWhisperTranslation(
                jobToken = jobToken,
                cleanUrl = if (pack.isLocalVideo) "" else pack.sourceUrl,
                sourceLang = options.sourceLang,
                videoTitleHint = pack.titleSlug,
                initialLocalAudioFile = tempAudioFile,
                prewarmedRuntimeDeferred = null,
                allowPhoneRelayFallback = !pack.isLocalVideo,
                skipColabYouTube = isYt,
                options = options,
                silentBackground = true,
            )

            assertJobCurrent(jobToken)
            val subBundle = saveSubtitlesToDownloads(packId, folder, pack.titleSlug, pack)

            val updatedPack = pack.copy(
                sourceLang = options.sourceLang,
                detectedLang = subBundle.detectedOrigLang,
                cueCount = subBundle.enCueCount,
                origCueCount = subBundle.origCueCount,
                srtDisplayPath = subBundle.enSrt?.displayPath,
                enSrtUriString = subBundle.enSrt?.uriString,
                enAssUriString = subBundle.enAss?.uriString,
                enVttUriString = subBundle.enVtt?.uriString,
                origSrtDisplayPath = subBundle.origSrt?.displayPath,
                origSrtUriString = subBundle.origSrt?.uriString,
                origAssUriString = subBundle.origAss?.uriString,
                origVttUriString = subBundle.origVtt?.uriString,
                internalEnSrtPath = subBundle.internalEnSrtPath,
                internalOrigSrtPath = subBundle.internalOrigSrtPath,
                createdAtMs = System.currentTimeMillis(),
            )
            upsertSavedPack(updatedPack)

            _state.update {
                it.copy(
                    phase = JobPhase.COMPLETED,
                    statusMessage = "Subtitles updated: ${subBundle.enCueCount} EN" +
                        (if (subBundle.origCueCount > 0) " + ${subBundle.origCueCount} orig" else "") +
                        " cues",
                    translationProgressText = if (subBundle.origCueCount > 0) {
                        "${subBundle.enCueCount} EN • ${subBundle.origCueCount} orig cues"
                    } else {
                        "${subBundle.enCueCount} English cues"
                    },
                    cueCount = subBundle.enCueCount,
                    origCueCount = subBundle.origCueCount,
                    detectedOrigLang = subBundle.detectedOrigLang,
                    liveSrtPath = liveSrtFile.absolutePath,
                    liveOrigSrtPath = liveOrigSrtFile.takeIf { f -> f.exists() && f.length() > 0L }?.absolutePath,
                    activePackId = null,
                    savePackStatus = SavePackStatus.SAVED,
                    saveStatusText = "Updated in Downloads/SUBCOLAB/$folder/",
                )
            }
            persistSession(wasCompletedOverride = true)
            updateNotification(
                "SUBCOLAB — Subtitles Updated",
                "${pack.titleSlug.replace('_', ' ')} (${subBundle.enCueCount} EN cues)",
                ongoing = false,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            handlePipelineFailure(jobToken, t, fromExternalShare = false)
        } finally {
            runCatching { tempAudioFile?.delete() }
            runCatching { localDir.deleteRecursively() }
        }
    }

    private suspend fun runWhisperTranslation(
        jobToken: Long,
        cleanUrl: String,
        sourceLang: String,
        videoTitleHint: String?,
        initialLocalAudioFile: File?,
        prewarmedRuntimeDeferred: Deferred<Result<ColabRuntimeSession>>?,
        allowPhoneRelayFallback: Boolean,
        skipColabYouTube: Boolean,
        options: SubtitleRetranslateOptions,
        silentBackground: Boolean,
    ) {
        val settings = settingsRepo.settings.value
        val workerScript = loadAssetText("colab_worker.py")
        val envVars = linkedMapOf(
            "SUBCOLAB_VIDEO_URL" to cleanUrl,
            "SUBCOLAB_SOURCE_LANG" to sourceLang,
            "SUBCOLAB_CLEAN_AUDIO" to if (options.cleanAudio) "True" else "False",
            "SUBCOLAB_VOCAL_BOOST" to if (options.vocalBoost) "True" else "False",
            "SUBCOLAB_BEAM_SIZE" to options.beamSize.coerceIn(1, 10).toString(),
            "SUBCOLAB_PLAYER_CLIENT" to "default",
            "SUBCOLAB_MODEL_ID" to options.modelId.ifBlank { "large-v3" },
            "SUBCOLAB_EMIT_ORIGINAL" to if (options.includeOriginal) "True" else "False",
            "SUBCOLAB_SKIP_COLAB_YT" to if (skipColabYouTube) "True" else "False",
            "SUBCOLAB_LOCAL_AUDIO" to "",
            "SUBCOLAB_STREAMING_QUEUE" to "False",
            "SUBCOLAB_JOB_ID" to "",
            "SUBCOLAB_DOWNLOAD_MP4_ONLY" to "False",
        )
        videoTitleHint?.takeIf { it.isNotBlank() }?.let {
            envVars["SUBCOLAB_VIDEO_TITLE"] = it
        }
        options.initialPrompt.takeIf { it.isNotBlank() }?.let {
            envVars["SUBCOLAB_INITIAL_PROMPT"] = it
        }

        var botBlocked = false

        suspend fun runColabPass(extraEnv: Map<String, String>) {
            assertJobCurrent(jobToken)
            val mergedEnv = envVars + extraEnv
            if (settings.useCustomTunnel && settings.customTunnelUrl.isNotBlank()) {
                if (initialLocalAudioFile != null || extraEnv.containsKey("SUBCOLAB_LOCAL_AUDIO")) {
                    throw IllegalStateException("Local/relayed audio translation requires Google Colab T4 mode (disable Custom Tunnel in Settings).")
                }
                _state.update {
                    it.copy(
                        phase = JobPhase.BOOTING_GPU,
                        statusMessage = "Connecting to Custom Colab Tunnel...",
                        translationProgressText = "Connecting to Custom Colab Tunnel...",
                    )
                }
                colabClient.executeViaCustomTunnel(
                    tunnelBaseUrl = settings.customTunnelUrl,
                    tunnelToken = settings.customTunnelToken,
                    workerScript = workerScript,
                    envVars = mergedEnv,
                ) { line ->
                    handleWorkerLine(
                        jobToken = jobToken,
                        rawLine = line,
                        onBotBlocked = { botBlocked = true },
                    )
                }
            } else {
                val runtime = if (prewarmedRuntimeDeferred != null) {
                    prewarmedRuntimeDeferred.await().getOrThrow()
                } else {
                    colabClient.ensureWarmT4Runtime { statusMsg ->
                        if (!isJobCurrent(jobToken)) return@ensureWarmT4Runtime
                        _state.update {
                            it.copy(
                                phase = JobPhase.BOOTING_GPU,
                                statusMessage = statusMsg,
                                translationProgressText = statusMsg,
                                isGpuWarm = true,
                            )
                        }
                        if (!silentBackground) {
                            updateNotification("SUBCOLAB — T4 GPU", statusMsg, ongoing = true)
                        }
                    }
                }
                assertJobCurrent(jobToken)
                onPrewarmedSession(runtime)
                if (initialLocalAudioFile != null && !extraEnv.containsKey("SUBCOLAB_LOCAL_AUDIO")) {
                    val remoteLocalPath = "/content/subcolab_local_${initialLocalAudioFile.name}"
                    val kb = initialLocalAudioFile.length() / 1024
                    _state.update {
                        it.copy(
                            phase = JobPhase.BOOTING_GPU,
                            statusMessage = "Uploading extracted audio ($kb KB) to T4 GPU...",
                            translationProgressText = "Uploading audio ($kb KB) to T4 GPU...",
                        )
                    }
                    updateNotification("SUBCOLAB — T4 GPU", "Uploading extracted audio ($kb KB) to Colab...", ongoing = true)
                    colabClient.uploadFile(runtime, initialLocalAudioFile, remoteLocalPath)
                    initialLocalAudioFile.delete()

                    val localMerged = mergedEnv + ("SUBCOLAB_LOCAL_AUDIO" to remoteLocalPath)
                    assertJobCurrent(jobToken)
                    colabClient.executeWorkerWithAutoRecover(
                        workerScript = workerScript,
                        envVars = localMerged,
                        initialSession = runtime,
                        onStatus = { msg ->
                            if (!isJobCurrent(jobToken)) return@executeWorkerWithAutoRecover
                            _state.update { it.copy(statusMessage = msg, translationProgressText = msg) }
                            updateNotification("SUBCOLAB — T4 GPU", msg, ongoing = true)
                        },
                        onOutputLine = { line ->
                            handleWorkerLine(
                                jobToken = jobToken,
                                rawLine = line,
                                onBotBlocked = { botBlocked = true },
                            )
                        },
                    )
                    return
                }

                if (settingsRepo.cookiesFile.exists() && settingsRepo.cookiesFile.length() > 0L) {
                    appendLog("Uploading cookies.txt to /content/cookies.txt...")
                    colabClient.uploadFile(runtime, settingsRepo.cookiesFile, "/content/cookies.txt")
                }
                assertJobCurrent(jobToken)
                colabClient.executeWorkerWithAutoRecover(
                    workerScript = workerScript,
                    envVars = mergedEnv,
                    initialSession = runtime,
                    onStatus = { msg ->
                        if (!isJobCurrent(jobToken)) return@executeWorkerWithAutoRecover
                        _state.update { it.copy(statusMessage = msg, translationProgressText = msg) }
                        updateNotification("SUBCOLAB — T4 GPU", msg, ongoing = true)
                    },
                    onOutputLine = { line ->
                        handleWorkerLine(
                            jobToken = jobToken,
                            rawLine = line,
                            onBotBlocked = { botBlocked = true },
                        )
                    },
                )
            }
        }

        runColabPass(emptyMap())

        if (botBlocked && allowPhoneRelayFallback && initialLocalAudioFile == null) {
            _state.update {
                it.copy(
                    phase = JobPhase.EXTRACTING_STREAM,
                    statusMessage = "Colab IP blocked — fetching audio on phone Wi-Fi...",
                    translationProgressText = "Downloading audio on phone Wi-Fi...",
                )
            }
            updateNotification(
                "SUBCOLAB — Phone Audio Relay",
                "Bypassing Colab YouTube block via phone Wi-Fi...",
                ongoing = true,
            )
            val relayDir = File(appContext.cacheDir, "relay")
            val rawRelayed = streamExtractor.downloadAudioForRelay(
                url = cleanUrl,
                destDir = relayDir,
                cookiesFile = settingsRepo.cookiesFile,
                processId = "relay_audio",
            )
            val audioFile = LocalAudioExtractor.extractAudioFromFile(appContext, rawRelayed, relayDir)
            if (audioFile != rawRelayed && rawRelayed.exists()) rawRelayed.delete()
            val remoteRelayPath = "/content/subcolab_relay_${audioFile.name}"
            val runtime = colabClient.activeSession
                ?: colabClient.ensureWarmT4Runtime { msg -> appendLog(msg) }
            val kb = audioFile.length() / 1024
            _state.update {
                it.copy(
                    statusMessage = "Uploading relayed audio ($kb KB) to T4...",
                    translationProgressText = "Uploading relayed audio ($kb KB) to T4...",
                )
            }
            colabClient.uploadFile(runtime, audioFile, remoteRelayPath)
            audioFile.delete()
            botBlocked = false
            runColabPass(mapOf("SUBCOLAB_LOCAL_AUDIO" to remoteRelayPath))
        }

        if (subtitleManager.cueCount() <= 0) {
            throw IllegalStateException("Colab Whisper returned 0 English subtitle cues. Try selecting the source language manually or check Debug Logs.")
        }
    }

    private suspend fun downloadMp4FromColab(
        jobToken: Long,
        cleanUrl: String,
        maxHeight: Int,
        destMp4: File,
    ): File {
        assertJobCurrent(jobToken)
        val workerScript = loadAssetText("colab_worker.py")
        var remoteMp4Path = "/content/subcolab_mp4_export.mp4"
        val envVars = linkedMapOf(
            "SUBCOLAB_VIDEO_URL" to cleanUrl,
            "SUBCOLAB_DOWNLOAD_MP4_ONLY" to "True",
            "SUBCOLAB_MAX_HEIGHT" to maxHeight.coerceIn(360, 1080).toString(),
            "SUBCOLAB_LOCAL_AUDIO" to "",
            "SUBCOLAB_STREAMING_QUEUE" to "False",
            "SUBCOLAB_JOB_ID" to "",
        )

        _state.update {
            it.copy(
                phase = JobPhase.SAVING_PACK,
                statusMessage = "Downloading <=${maxHeight}p MP4 via Colab relay...",
                savePackStatus = SavePackStatus.SAVING,
                saveProgressPercent = 5,
                saveStatusText = "Fetching <=${maxHeight}p MP4 on Colab...",
            )
        }
        updateNotification(
            "SUBCOLAB — Colab Video Relay",
            "Downloading <=${maxHeight}p MP4 via Colab relay...",
            ongoing = true,
        )

        val runtimeSession = colabClient.ensureWarmT4Runtime { appendLog(it) }
        onPrewarmedSession(runtimeSession)

        colabClient.executeWorkerWithAutoRecover(
            workerScript = workerScript,
            envVars = envVars,
            initialSession = runtimeSession,
            onStatus = { msg ->
                if (isJobCurrent(jobToken)) {
                    _state.update { it.copy(saveStatusText = msg) }
                    appendLog(msg)
                }
            },
            onBeforeExecute = { session ->
                if (settingsRepo.cookiesFile.exists() && settingsRepo.cookiesFile.length() > 0L) {
                    runCatching { colabClient.uploadFile(session, settingsRepo.cookiesFile, "/content/cookies.txt") }
                }
            },
            onOutputLine = { rawLine ->
                val line = rawLine.trimEnd('\r', '\n')
                if (line.startsWith("@@SUBCOLAB_MP4_READY@@")) {
                    val path = line.removePrefix("@@SUBCOLAB_MP4_READY@@").trim()
                    if (path.isNotBlank()) remoteMp4Path = path
                } else if (line.startsWith("@@SUBCOLAB_STATUS@@")) {
                    val msg = line.removePrefix("@@SUBCOLAB_STATUS@@").trim()
                    if (isJobCurrent(jobToken) && msg.isNotBlank()) {
                        _state.update { it.copy(saveStatusText = msg) }
                        appendLog(msg)
                    }
                }
            },
        )

        assertJobCurrent(jobToken)
        val runtime = colabClient.activeSession ?: colabClient.ensureWarmT4Runtime { appendLog(it) }
        try {
            colabClient.downloadRemoteFile(runtime, remoteMp4Path, destMp4) { pct ->
                if (isJobCurrent(jobToken)) {
                    _state.update {
                        it.copy(
                            saveProgressPercent = pct.coerceIn(0, 99),
                            saveStatusText = "Transferring MP4 from Colab ($pct%)...",
                        )
                    }
                }
            }
        } finally {
            runCatching { colabClient.deleteRemoteFile(runtime, remoteMp4Path) }
        }
        if (!destMp4.exists() || destMp4.length() == 0L) {
            throw IllegalStateException("Colab relay MP4 transfer produced an empty file.")
        }
        return destMp4
    }

    private fun handleWorkerLine(
        jobToken: Long,
        rawLine: String,
        onBotBlocked: () -> Unit,
        onRemoteChunkReady: ((WorkerEvent.RemoteChunkReady) -> Unit)? = null,
        onRemoteChunksDone: ((WorkerEvent.RemoteChunksDone) -> Unit)? = null,
    ) {
        if (!isJobCurrent(jobToken)) return
        val event = subtitleManager.processLine(rawLine) ?: return
        if (!isJobCurrent(jobToken)) return
        when (event) {
            is WorkerEvent.Heartbeat -> Unit
            is WorkerEvent.WorkerReady -> appendLog("Colab Whisper model loaded & ready for streaming chunks")
            is WorkerEvent.BotBlocked -> onBotBlocked()
            is WorkerEvent.RemoteChunkReady -> onRemoteChunkReady?.invoke(event)
            is WorkerEvent.RemoteChunksDone -> onRemoteChunksDone?.invoke(event)
            is WorkerEvent.Title -> {
                _state.update {
                    it.copy(
                        titleSlug = event.slug,
                        previousJob = it.previousJob?.copy(titleSlug = event.slug),
                    )
                }
            }
            is WorkerEvent.OrigLang -> {
                _state.update {
                    it.copy(detectedOrigLang = event.langCode)
                }
            }
            is WorkerEvent.Status -> {
                val nextPhase = when {
                    event.message.contains("Loading faster-whisper", ignoreCase = true) ||
                        event.message.contains("Downloading faster-whisper", ignoreCase = true) -> JobPhase.LOADING_MODEL
                    event.message.contains("Translating speech", ignoreCase = true) ||
                        event.message.contains("Transcribing original", ignoreCase = true) -> JobPhase.TRANSLATING
                    event.message.contains("Downloading audio", ignoreCase = true) -> JobPhase.EXTRACTING_STREAM
                    else -> _state.value.phase
                }
                _state.update {
                    it.copy(
                        phase = nextPhase,
                        statusMessage = event.message,
                        translationProgressText = event.message,
                    )
                }
                appendLog(event.message)
                updateNotification("SUBCOLAB — T4 GPU", event.message, ongoing = true)
            }
            is WorkerEvent.CueArrived -> {
                subtitleManager.writeLiveSrt(liveSrtFile)
                _liveSrtRevision.value = _liveSrtRevision.value + 1L
                _state.update {
                    it.copy(
                        phase = JobPhase.TRANSLATING,
                        cueCount = event.totalCount,
                        liveSrtPath = liveSrtFile.absolutePath,
                        translationProgressText = "Translating (${event.totalCount} EN cues so far)...",
                        previousJob = it.previousJob?.copy(subtitlesCompleted = false),
                    )
                }
                maybeNotifyPlayerReady(jobToken)
                if (event.totalCount == 4 || event.totalCount % 10 == 0) {
                    persistSession()
                }
            }
            is WorkerEvent.OrigCueArrived -> {
                subtitleManager.writeOrigSrt(liveOrigSrtFile)
                _liveSrtRevision.value = _liveSrtRevision.value + 1L
                _state.update {
                    it.copy(
                        phase = JobPhase.TRANSLATING,
                        origCueCount = event.totalCount,
                        liveOrigSrtPath = liveOrigSrtFile.absolutePath,
                        translationProgressText = "Original subtitles (${event.totalCount} cues)...",
                    )
                }
                if (event.totalCount % 10 == 0) {
                    persistSession()
                }
            }
            is WorkerEvent.ChunkCompleted -> {
                val chunks = subtitleManager.getCompletedChunks()
                _state.update { it.copy(completedChunks = chunks) }
                persistSession()
            }
            is WorkerEvent.AllDone -> {
                subtitleManager.writeLiveSrt(liveSrtFile)
                if (subtitleManager.origCueCount() > 0) {
                    subtitleManager.writeOrigSrt(liveOrigSrtFile)
                }
                _liveSrtRevision.value = _liveSrtRevision.value + 1L
                _state.update {
                    it.copy(
                        previousJob = it.previousJob?.copy(subtitlesCompleted = true),
                    )
                }
                persistSession(wasCompletedOverride = false)
            }
        }
    }

    private fun handlePipelineFailure(
        jobToken: Long,
        t: Throwable,
        fromExternalShare: Boolean,
    ) {
        if (!isJobCurrent(jobToken)) {
            AppLogger.i(TAG, "Ignoring failure from superseded job #$jobToken (${t::class.simpleName}: ${t.message})")
            return
        }
        val msg = t.message ?: t::class.simpleName ?: "Unknown error"
        AppLogger.e(TAG, "Pipeline failed", t)
        _state.update {
            it.copy(
                phase = JobPhase.ERROR,
                statusMessage = "Error: $msg",
                errorMessage = msg,
                activePackId = null,
                isReadyPromptVisible = false,
                savePackStatus = if (it.savePackStatus == SavePackStatus.SAVING) SavePackStatus.ERROR else it.savePackStatus,
            )
        }
        persistSession(wasCompletedOverride = false)
        updateNotification(
            if (fromExternalShare) "SUBCOLAB — Download/Translation Failed" else "SUBCOLAB — Error",
            msg,
            ongoing = false,
        )
    }

    private fun appendLog(line: String) {
        AppLogger.i(TAG, line)
        _state.update { current ->
            current.copy(logTail = (current.logTail + line).takeLast(30))
        }
    }

    private fun updateNotification(title: String, body: String, ongoing: Boolean) {
        notificationCallback?.invoke(title, body, ongoing)
    }

    private fun loadAssetText(name: String): String {
        return appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
