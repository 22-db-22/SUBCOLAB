package com.openscribe.android.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.openscribe.android.model.VideoChunkInfo
import com.openscribe.android.util.AppLogger
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class PlayerUiState(
    val isInitialized: Boolean = false,
    val isFileLoaded: Boolean = false,
    val isPaused: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val subtitlesEnabled: Boolean = true,
    val hasSubtitleTrack: Boolean = false,
    val hasOrigSubtitleTrack: Boolean = false,
    val activeSubtitleTrack: String = "en", // "en" | "orig" | "off"
    val origLangCode: String? = null,
    val bufferingMessage: String? = null,
    val error: String? = null,
)

class MpvPlayerController(private val context: Context) {

    companion object {
        private const val TAG = "MpvPlayerController"
        private const val MPV_FORMAT_FLAG = 3
        private const val MPV_FORMAT_DOUBLE = 5
        private const val MPV_EVENT_START_FILE = 6
        private const val MPV_EVENT_END_FILE = 7
        private const val MPV_EVENT_FILE_LOADED = 8

        private val mpvLock = Any()

        @Volatile
        private var activeController: MpvPlayerController? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var isCreated = false
    @Volatile
    private var isSurfaceAttached = false
    @Volatile
    private var isFileLoaded = false
    @Volatile
    private var loadRequested = false
    @Volatile
    private var hwdecNoRetried = false
    @Volatile
    private var awaitingStartFile = false

    private var activePfd: ParcelFileDescriptor? = null
    private var loadedVideoSource: String? = null
    private var loadedPlaybackTarget: String? = null
    private var pendingLoadVideo: String? = null

    private var currentSubLoadedPath: String? = null
    private var pendingEnSrtPath: String? = null
    private var pendingEnCueCount: Int = 0
    private var pendingOrigSrtPath: String? = null
    private var pendingOrigCueCount: Int = 0
    private var desiredSubMode: String = "en" // "en" | "orig" | "off"

    // Chunked streaming state
    private var chunkList: List<VideoChunkInfo> = emptyList()
    private var currentChunkIndex: Int = -1
    private var totalDurationHintSec: Double = 0.0
    private var stitchedVideoSource: String? = null
    private var allChunksDownloaded: Boolean = false
    private var isPlayingStitched: Boolean = false
    private var waitingForNextChunkIndex: Int? = null
    private var pendingSeekInChunkSec: Double? = null
    private var eofHandledForCurrentChunk: Boolean = false

    @Volatile
    private var lastMpvErrorLog: String? = null

    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val logObserver = object : MPVLib.LogObserver {
        override fun logMessage(prefix: String, level: Int, text: String) {
            val clean = text.trim()
            if (clean.isEmpty()) return
            if (level <= 20) {
                if (loadRequested) {
                    lastMpvErrorLog = "[$prefix] $clean"
                }
                AppLogger.e(TAG, "mpv[$prefix]: $clean")
            } else if (level <= 30) {
                AppLogger.w(TAG, "mpv[$prefix]: $clean")
            }
        }
    }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit

        override fun eventProperty(property: String, value: Long) = Unit

        override fun eventProperty(property: String, value: Double) {
            mainHandler.post {
                if (!isCreated || activeController !== this@MpvPlayerController) return@post
                when (property) {
                    "time-pos" -> {
                        val globalPos = if (currentChunkIndex >= 0 && !isPlayingStitched) {
                            val chunkOffset = chunkList.getOrNull(currentChunkIndex)?.startSec ?: 0.0
                            chunkOffset + value
                        } else {
                            value
                        }
                        _uiState.update { it.copy(positionSec = globalPos) }
                    }
                    "duration" -> {
                        val effectiveDuration = if (currentChunkIndex >= 0 && !isPlayingStitched) {
                            val chunkEnd = (chunkList.getOrNull(currentChunkIndex)?.startSec ?: 0.0) + value
                            maxOf(totalDurationHintSec, chunkList.lastOrNull()?.endSec ?: 0.0, chunkEnd)
                        } else {
                            value
                        }
                        _uiState.update { it.copy(durationSec = effectiveDuration) }
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            mainHandler.post {
                if (!isCreated || activeController !== this@MpvPlayerController) return@post
                when (property) {
                    "pause" -> _uiState.update { it.copy(isPaused = value) }
                    "eof-reached" -> {
                        if (value && isFileLoaded && currentChunkIndex >= 0 && !isPlayingStitched && !eofHandledForCurrentChunk) {
                            eofHandledForCurrentChunk = true
                            handleChunkEndReached()
                        }
                    }
                }
            }
        }

        override fun eventProperty(property: String, value: String) = Unit

        override fun event(eventId: Int) {
            when (eventId) {
                MPV_EVENT_START_FILE -> {
                    mainHandler.post {
                        if (!isCreated || activeController !== this@MpvPlayerController) return@post
                        awaitingStartFile = false
                        AppLogger.i(TAG, "MPV_EVENT_START_FILE received")
                    }
                }
                MPV_EVENT_FILE_LOADED -> {
                    mainHandler.post {
                        if (!isCreated || activeController !== this@MpvPlayerController) return@post
                        awaitingStartFile = false
                        AppLogger.i(TAG, "MPV_EVENT_FILE_LOADED received (chunkIdx=$currentChunkIndex)")
                        isFileLoaded = true
                        eofHandledForCurrentChunk = false
                        if (currentChunkIndex >= 0 && !isPlayingStitched) {
                            val activeChunk = chunkList.getOrNull(currentChunkIndex)
                            if (activeChunk != null) {
                                runCatching {
                                    MPVLib.setPropertyDouble("sub-delay", -activeChunk.startSec)
                                }
                            }
                            pendingSeekInChunkSec?.let { targetSec ->
                                pendingSeekInChunkSec = null
                                runCatching {
                                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute"))
                                }
                            }
                        } else {
                            runCatching { MPVLib.setPropertyDouble("sub-delay", 0.0) }
                        }
                        _uiState.update { it.copy(isFileLoaded = true, bufferingMessage = null, error = null) }
                        applyPendingSubtitlesInternal()
                    }
                }
                MPV_EVENT_END_FILE -> {
                    mainHandler.post {
                        if (!isCreated || activeController !== this@MpvPlayerController) return@post
                        if (awaitingStartFile) {
                            AppLogger.d(TAG, "Ignoring MPV_EVENT_END_FILE from previous chunk during loadfile transition")
                            return@post
                        }
                        val wasLoaded = isFileLoaded
                        val hadRequested = loadRequested
                        val errSnapshot = lastMpvErrorLog
                        AppLogger.i(
                            TAG,
                            "MPV_EVENT_END_FILE received (wasLoaded=$wasLoaded, requested=$hadRequested, chunkIdx=$currentChunkIndex, hwdecRetried=$hwdecNoRetried, err=$errSnapshot)",
                        )
                        if (wasLoaded && currentChunkIndex >= 0 && !isPlayingStitched && !eofHandledForCurrentChunk) {
                            eofHandledForCurrentChunk = true
                            handleChunkEndReached()
                            return@post
                        }
                        if (hadRequested && !wasLoaded) {
                            val currentPlaybackTarget = loadedPlaybackTarget
                            if (!hwdecNoRetried && !currentPlaybackTarget.isNullOrBlank()) {
                                hwdecNoRetried = true
                                AppLogger.w(TAG, "Retrying local loadfile once with hwdec=no software fallback...")
                                runCatching {
                                    MPVLib.setPropertyString("hwdec", "no")
                                    MPVLib.command(arrayOf("loadfile", currentPlaybackTarget))
                                    MPVLib.setPropertyBoolean("pause", false)
                                }.onFailure { retryErr ->
                                    _uiState.update {
                                        it.copy(
                                            isFileLoaded = false,
                                            error = "Playback failed: ${errSnapshot ?: retryErr.message ?: "unknown"}",
                                        )
                                    }
                                }
                            } else {
                                _uiState.update {
                                    it.copy(
                                        isFileLoaded = false,
                                        error = errSnapshot
                                            ?: "Could not open video file in libmpv (MPV_EVENT_END_FILE).",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleChunkEndReached() {
        synchronized(mpvLock) {
            if (!isCreated || activeController !== this || currentChunkIndex < 0 || isPlayingStitched) return
            val nextIdx = currentChunkIndex + 1
            if (nextIdx < chunkList.size) {
                AppLogger.i(TAG, "Advancing seamlessly to video chunk ${nextIdx + 1}/${chunkList.size}")
                loadChunkLocked(nextIdx, seekLocalSec = 0.0)
            } else if (!allChunksDownloaded && stitchedVideoSource == null) {
                waitingForNextChunkIndex = nextIdx
                _uiState.update {
                    it.copy(bufferingMessage = "Buffering chunk ${nextIdx + 1}...")
                }
                AppLogger.i(TAG, "Waiting for chunk ${nextIdx + 1} to finish downloading...")
            } else {
                waitingForNextChunkIndex = null
                _uiState.update { it.copy(bufferingMessage = null) }
            }
        }
    }

    fun initializeIfNeeded(subtitleFontSize: Int = 46) {
        synchronized(mpvLock) {
            if (isCreated && activeController === this) return
            activeController?.let { prior ->
                if (prior !== this) {
                    AppLogger.w(TAG, "Releasing prior active MpvPlayerController before init")
                    prior.releaseInternalLocked()
                }
            }

            val fontsDir = prepareMpvFontsDir()
            val primaryOk = tryInitMpv(
                fontsDir = fontsDir,
                subtitleFontSize = subtitleFontSize,
                hwdecMode = "mediacodec-copy",
            )
            if (!primaryOk) {
                AppLogger.w(TAG, "Retrying libmpv init with hwdec=auto-safe fallback")
                runCatching { MPVLib.destroy() }
                val fallbackOk = tryInitMpv(
                    fontsDir = fontsDir,
                    subtitleFontSize = subtitleFontSize,
                    hwdecMode = "auto-safe",
                )
                if (!fallbackOk) {
                    return
                }
            }

            isCreated = true
            activeController = this
            _uiState.update { it.copy(isInitialized = true, error = null) }
            AppLogger.i(TAG, "libmpv initialized successfully (fontDir=${fontsDir.absolutePath})")

            val surf = pendingSurface
            if (surf != null && surf.isValid) {
                attachSurfaceInternalLocked(surf, pendingSurfaceWidth, pendingSurfaceHeight)
            }
        }
    }

    private fun tryInitMpv(
        fontsDir: File,
        subtitleFontSize: Int,
        hwdecMode: String,
    ): Boolean {
        return try {
            MPVLib.create(context.applicationContext)
            setOpt("vo", "gpu")
            setOpt("gpu-context", "android")
            setOpt("gpu-api", "opengl")
            setOpt("opengl-es", "yes")
            setOpt("hwdec", hwdecMode)
            setOpt("ao", "audiotrack,opensles")
            setOpt("ytdl", "no")
            setOpt("keep-open", "yes")
            setOpt("cache", "yes")
            setOpt("demuxer-max-bytes", "32MiB")
            setOpt("demuxer-max-back-bytes", "16MiB")
            setOpt("sub-auto", "no")
            setOpt("sub-fix-timing", "yes")

            // Point ONLY to private single-font directory so libass/fontconfig NEVER scans /system/fonts
            setOpt("config-dir", fontsDir.absolutePath)
            setOpt("sub-fonts-dir", fontsDir.absolutePath)
            setOpt("sub-font", "Roboto")
            setOpt("sub-font-provider", "none")

            // Crisp yellow subtitles
            setOpt("sub-color", "#FFFF00")
            setOpt("sub-border-color", "#000000")
            setOpt("sub-border-size", "3")
            setOpt("sub-shadow-color", "#80000000")
            setOpt("sub-shadow-offset", "1")
            setOpt("sub-font-size", subtitleFontSize.toString())
            setOpt("sub-margin-y", "36")

            MPVLib.init()
            runCatching { MPVLib.addLogObserver(logObserver) }
            MPVLib.addObserver(observer)
            MPVLib.observeProperty("time-pos", MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("duration", MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("pause", MPV_FORMAT_FLAG)
            MPVLib.observeProperty("eof-reached", MPV_FORMAT_FLAG)
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "libmpv init failed (hwdec=$hwdecMode)", t)
            _uiState.update { it.copy(error = t.message ?: "libmpv init failed ($hwdecMode)") }
            false
        }
    }

    private fun setOpt(name: String, value: String) {
        runCatching {
            MPVLib.setOptionString(name, value)
        }.onFailure { err ->
            AppLogger.w(TAG, "setOptionString($name=$value) failed", err)
        }
    }

    private fun prepareMpvFontsDir(): File {
        val fontsDir = File(context.filesDir, "mpv_fonts").apply { mkdirs() }
        val targetRoboto = File(fontsDir, "Roboto-Regular.ttf")
        val targetSubfont = File(fontsDir, "subfont.ttf")

        if (targetRoboto.exists() && targetRoboto.length() > 0L &&
            targetSubfont.exists() && targetSubfont.length() > 0L
        ) {
            return fontsDir
        }

        val candidates = listOf(
            File("/system/fonts/Roboto-Regular.ttf"),
            File("/system/fonts/DroidSans.ttf"),
            File("/system/fonts/NotoSans-Regular.ttf"),
        )
        val sourceFont = candidates.firstOrNull { it.exists() && it.isFile && it.length() > 0L }
        if (sourceFont != null) {
            runCatching {
                if (!targetRoboto.exists() || targetRoboto.length() == 0L) {
                    sourceFont.copyTo(targetRoboto, overwrite = true)
                }
                if (!targetSubfont.exists() || targetSubfont.length() == 0L) {
                    sourceFont.copyTo(targetSubfont, overwrite = true)
                }
                AppLogger.i(TAG, "Prepared private mpv font from ${sourceFont.name} (${sourceFont.length()} bytes)")
            }.onFailure { err ->
                AppLogger.e(TAG, "Failed to copy single font ${sourceFont.absolutePath}", err)
            }
        } else {
            AppLogger.w(TAG, "No Roboto-Regular.ttf or DroidSans.ttf found in /system/fonts")
        }
        return fontsDir
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        synchronized(mpvLock) {
            pendingSurface = surface
            pendingSurfaceWidth = width
            pendingSurfaceHeight = height
            if (!isCreated || activeController !== this) {
                AppLogger.d(TAG, "attachSurface deferred until libmpv init completes (${width}x${height})")
                return
            }
            attachSurfaceInternalLocked(surface, width, height)
        }
    }

    private fun attachSurfaceInternalLocked(surface: Surface, width: Int, height: Int) {
        if (!isCreated || activeController !== this) return
        if (!surface.isValid) {
            AppLogger.w(TAG, "attachSurface skipped: Surface is invalid")
            return
        }
        runMpvSafe("attachSurface") {
            MPVLib.attachSurface(surface)
            if (width > 0 && height > 0) {
                MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
            }
            isSurfaceAttached = true
            AppLogger.i(TAG, "Surface attached (${width}x${height})")
            flushPendingLoadLocked()
        }
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        synchronized(mpvLock) {
            pendingSurfaceWidth = width
            pendingSurfaceHeight = height
            if (!isCreated || activeController !== this || !isSurfaceAttached || width <= 0 || height <= 0) return
            runMpvSafe("updateSurfaceSize(${width}x${height})") {
                MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
            }
        }
    }

    fun detachSurface() {
        synchronized(mpvLock) {
            pendingSurface = null
            isSurfaceAttached = false
            if (!isCreated || activeController !== this) return
            runMpvSafe("detachSurface") {
                MPVLib.detachSurface()
            }
        }
    }

    /**
     * Coordinates both full-file playback (local/stitched/cached) and live chunk-by-chunk playback
     * while the rest of the video is still downloading and stitching in the background.
     */
    fun syncMediaState(
        stitchedSource: String?,
        chunks: List<VideoChunkInfo>,
        totalDurationSec: Double,
        chunksDownloadComplete: Boolean = false,
    ) {
        synchronized(mpvLock) {
            if (!isCreated || activeController !== this) return
            stitchedVideoSource = stitchedSource
            allChunksDownloaded = chunksDownloadComplete || !stitchedSource.isNullOrBlank()
            if (totalDurationSec > 0.0) {
                totalDurationHintSec = maxOf(totalDurationHintSec, totalDurationSec)
            }

            val validChunks = chunks.filter { File(it.path).let { f -> f.exists() && f.length() > 0L } }
            if (validChunks.isNotEmpty()) {
                chunkList = validChunks
                val maxDur = maxOf(totalDurationHintSec, validChunks.last().endSec)
                if (_uiState.value.durationSec < maxDur) {
                    _uiState.update { it.copy(durationSec = maxDur) }
                }

                if (currentChunkIndex < 0 && !isPlayingStitched) {
                    loadChunkLocked(0, seekLocalSec = 0.0)
                } else {
                    val waitingIdx = waitingForNextChunkIndex
                    if (waitingIdx != null && waitingIdx < chunkList.size) {
                        AppLogger.i(TAG, "Buffered chunk ${waitingIdx + 1} arrived — resuming playback")
                        loadChunkLocked(waitingIdx, seekLocalSec = 0.0)
                    } else if (waitingIdx != null && waitingIdx >= chunkList.size && allChunksDownloaded) {
                        waitingForNextChunkIndex = null
                        _uiState.update { it.copy(bufferingMessage = null) }
                    }
                }
            } else if (!stitchedSource.isNullOrBlank()) {
                chunkList = emptyList()
                currentChunkIndex = -1
                isPlayingStitched = true
                waitingForNextChunkIndex = null
                loadMediaInternalLocked(stitchedSource)
            }
        }
    }

    fun loadMedia(videoSource: String) {
        synchronized(mpvLock) {
            if (!isCreated || activeController !== this) return
            chunkList = emptyList()
            currentChunkIndex = -1
            isPlayingStitched = true
            waitingForNextChunkIndex = null
            loadMediaInternalLocked(videoSource)
        }
    }

    private fun loadChunkLocked(index: Int, seekLocalSec: Double) {
        val chunk = chunkList.getOrNull(index) ?: return
        currentChunkIndex = index
        isPlayingStitched = false
        waitingForNextChunkIndex = null
        pendingSeekInChunkSec = seekLocalSec.takeIf { it > 0.2 }
        eofHandledForCurrentChunk = false
        loadMediaInternalLocked(chunk.path)
    }

    private fun loadMediaInternalLocked(videoSource: String) {
        if (loadedVideoSource == videoSource && _uiState.value.error == null) return

        pendingLoadVideo = videoSource
        if (!isSurfaceAttached) {
            AppLogger.i(TAG, "Queued loadMedia until Surface attaches (${videoSource.take(72)})")
            return
        }
        flushPendingLoadLocked()
    }

    private fun flushPendingLoadLocked() {
        val videoSource = pendingLoadVideo ?: return
        if (!isCreated || activeController !== this || !isSurfaceAttached) return
        if (loadedVideoSource == videoSource && _uiState.value.error == null && loadRequested) return

        pendingLoadVideo = null
        loadedVideoSource = videoSource
        isFileLoaded = false
        loadRequested = true
        hwdecNoRetried = false
        currentSubLoadedPath = null
        lastMpvErrorLog = null

        closeActivePfdLocked()
        _uiState.update { it.copy(isFileLoaded = false, error = null) }

        if (videoSource.startsWith("content://")) {
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(Uri.parse(videoSource), "r")
            }.getOrNull()

            if (pfd != null && pfd.statSize > 0L) {
                activePfd = pfd
                val fdTarget = "fd://${pfd.fd}"
                loadedPlaybackTarget = fdTarget
                AppLogger.i(TAG, "Opening seekable content URI via $fdTarget (size=${pfd.statSize})")
                loadTargetInMpvLocked(fdTarget)
            } else {
                runCatching { pfd?.close() }
                AppLogger.i(TAG, "Staging streamed content URI to cacheDir before opening in libmpv...")
                ioScope.launch {
                    val staged = File(context.cacheDir, "openscribe_local_play.mp4")
                    val stagedOk = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(videoSource))?.use { input ->
                            staged.outputStream().use { out ->
                                input.copyTo(out, bufferSize = 64 * 1024)
                            }
                        }
                        staged.exists() && staged.length() > 0L
                    }.getOrDefault(false)

                    synchronized(mpvLock) {
                        if (!isCreated || activeController !== this@MpvPlayerController) return@synchronized
                        if (loadedVideoSource != videoSource) return@synchronized
                        if (!stagedOk) {
                            _uiState.update {
                                it.copy(
                                    isFileLoaded = false,
                                    error = "Cannot read selected device video.",
                                )
                            }
                            return@synchronized
                        }
                        loadedPlaybackTarget = staged.absolutePath
                        loadTargetInMpvLocked(staged.absolutePath)
                    }
                }
            }
        } else {
            loadedPlaybackTarget = videoSource
            AppLogger.i(TAG, "Opening local media: ${videoSource.take(80)}")
            loadTargetInMpvLocked(videoSource)
        }
    }

    private fun loadTargetInMpvLocked(target: String) {
        awaitingStartFile = true
        runMpvSafe("loadMedia") {
            MPVLib.command(arrayOf("loadfile", target))
            MPVLib.setPropertyBoolean("pause", false)
        }
    }

    fun retryLoad() {
        synchronized(mpvLock) {
            val src = loadedVideoSource ?: pendingLoadVideo ?: return
            loadedVideoSource = null
            loadedPlaybackTarget = null
            loadRequested = false
            hwdecNoRetried = false
            lastMpvErrorLog = null
            _uiState.update { it.copy(error = null) }
            loadMediaInternalLocked(src)
        }
    }

    fun syncLiveSubtitles(
        enSrtPath: String?,
        enCueCount: Int,
        origSrtPath: String? = null,
        origCueCount: Int = 0,
        origLangCode: String? = null,
    ) {
        pendingEnSrtPath = enSrtPath
        pendingEnCueCount = enCueCount
        pendingOrigSrtPath = origSrtPath
        pendingOrigCueCount = origCueCount

        val hasEn = !enSrtPath.isNullOrBlank() && enCueCount > 0 && File(enSrtPath).let { it.exists() && it.length() > 0L }
        val hasOrig = !origSrtPath.isNullOrBlank() && origCueCount > 0 && File(origSrtPath).let { it.exists() && it.length() > 0L }

        if (desiredSubMode == "orig" && !hasOrig && hasEn) {
            desiredSubMode = "en"
        }

        _uiState.update {
            it.copy(
                hasSubtitleTrack = hasEn,
                hasOrigSubtitleTrack = hasOrig,
                origLangCode = origLangCode ?: it.origLangCode,
                activeSubtitleTrack = desiredSubMode,
                subtitlesEnabled = desiredSubMode != "off",
            )
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyPendingSubtitlesInternal()
        } else {
            mainHandler.post { applyPendingSubtitlesInternal() }
        }
    }

    private fun applyPendingSubtitlesInternal() {
        if (!isCreated || activeController !== this || !isFileLoaded) return

        val enPath = pendingEnSrtPath?.takeIf { pendingEnCueCount > 0 && File(it).let { f -> f.exists() && f.length() > 0L } }
        val origPath = pendingOrigSrtPath?.takeIf { pendingOrigCueCount > 0 && File(it).let { f -> f.exists() && f.length() > 0L } }

        val targetPath = when (desiredSubMode) {
            "orig" -> origPath ?: enPath
            "off" -> currentSubLoadedPath ?: enPath ?: origPath
            else -> enPath ?: origPath
        } ?: return

        runMpvSafe("applyPendingSubtitles") {
            if (currentSubLoadedPath != targetPath) {
                if (currentSubLoadedPath != null) {
                    runCatching { MPVLib.command(arrayOf("sub-remove")) }
                }
                AppLogger.i(TAG, "sub-add $targetPath (mode=$desiredSubMode)")
                MPVLib.command(arrayOf("sub-add", targetPath, "select"))
                currentSubLoadedPath = targetPath
            } else {
                MPVLib.command(arrayOf("sub-reload"))
            }
            if (currentChunkIndex >= 0 && !isPlayingStitched) {
                val activeChunk = chunkList.getOrNull(currentChunkIndex)
                if (activeChunk != null) {
                    MPVLib.setPropertyDouble("sub-delay", -activeChunk.startSec)
                }
            }
            val visible = desiredSubMode != "off"
            MPVLib.setPropertyBoolean("sub-visibility", visible)
            _uiState.update {
                it.copy(
                    hasSubtitleTrack = enPath != null,
                    hasOrigSubtitleTrack = origPath != null,
                    subtitlesEnabled = visible,
                    activeSubtitleTrack = desiredSubMode,
                )
            }
        }
    }

    fun cycleSubtitles() {
        val st = _uiState.value
        val nextMode = when {
            st.hasOrigSubtitleTrack -> when (desiredSubMode) {
                "en" -> "orig"
                "orig" -> "off"
                else -> "en"
            }
            else -> if (desiredSubMode == "off") "en" else "off"
        }
        setSubtitleTrack(nextMode)
    }

    fun setSubtitleTrack(mode: String) {
        desiredSubMode = when (mode) {
            "orig" -> if (_uiState.value.hasOrigSubtitleTrack) "orig" else "en"
            "off" -> "off"
            else -> "en"
        }
        val visible = desiredSubMode != "off"
        _uiState.update {
            it.copy(
                subtitlesEnabled = visible,
                activeSubtitleTrack = desiredSubMode,
            )
        }
        if (isCreated && activeController === this && isFileLoaded) {
            applyPendingSubtitlesInternal()
        }
    }

    fun toggleSubtitles() {
        cycleSubtitles()
    }

    fun setPaused(paused: Boolean) {
        if (!isCreated || activeController !== this) return
        runMpvSafe("setPaused($paused)") {
            MPVLib.setPropertyBoolean("pause", paused)
        }
    }

    fun togglePlayPause() {
        val currentlyPaused = _uiState.value.isPaused
        setPaused(!currentlyPaused)
    }

    fun seekAbsolute(seconds: Double) {
        synchronized(mpvLock) {
            if (!isCreated || activeController !== this) return
            val target = maxOf(0.0, seconds)
            if (currentChunkIndex < 0 || isPlayingStitched || chunkList.isEmpty()) {
                runMpvSafe("seekAbsolute($target)") {
                    MPVLib.command(arrayOf("seek", target.toString(), "absolute"))
                }
                return
            }

            val maxReadySec = chunkList.last().endSec
            val clampedTarget = target.coerceAtMost(maxOf(0.0, maxReadySec - 0.3))
            val targetIdx = chunkList.indexOfFirst { clampedTarget <= it.endSec + 0.05 }
                .takeIf { it >= 0 } ?: chunkList.lastIndex
            val targetChunk = chunkList[targetIdx]
            val localSec = (clampedTarget - targetChunk.startSec)
                .coerceIn(0.0, maxOf(0.0, targetChunk.endSec - targetChunk.startSec - 0.3))

            if (targetIdx == currentChunkIndex) {
                eofHandledForCurrentChunk = false
                runMpvSafe("seekAbsolute($localSec)") {
                    MPVLib.command(arrayOf("seek", localSec.toString(), "absolute"))
                }
            } else {
                AppLogger.i(TAG, "Cross-chunk seek -> ${clampedTarget.toInt()}s (chunk ${targetIdx + 1}, local=${localSec.toInt()}s)")
                loadChunkLocked(targetIdx, seekLocalSec = localSec)
            }
        }
    }

    fun release() {
        synchronized(mpvLock) {
            releaseInternalLocked()
        }
    }

    private fun closeActivePfdLocked() {
        runCatching { activePfd?.close() }
        activePfd = null
    }

    private fun releaseInternalLocked() {
        AppLogger.i(TAG, "Releasing libmpv controller (wasCreated=$isCreated, isActive=${activeController === this})")
        loadedVideoSource = null
        loadedPlaybackTarget = null
        pendingLoadVideo = null
        isFileLoaded = false
        loadRequested = false
        awaitingStartFile = false
        isSurfaceAttached = false
        currentSubLoadedPath = null
        pendingEnSrtPath = null
        pendingEnCueCount = 0
        pendingOrigSrtPath = null
        pendingOrigCueCount = 0
        desiredSubMode = "en"
        chunkList = emptyList()
        currentChunkIndex = -1
        totalDurationHintSec = 0.0
        stitchedVideoSource = null
        allChunksDownloaded = false
        isPlayingStitched = false
        waitingForNextChunkIndex = null
        pendingSeekInChunkSec = null
        eofHandledForCurrentChunk = false
        pendingSurface = null
        lastMpvErrorLog = null

        val wasActiveInstance = isCreated && activeController === this
        isCreated = false
        if (wasActiveInstance) {
            activeController = null
            runCatching { MPVLib.command(arrayOf("stop")) }
            runCatching { MPVLib.removeObserver(observer) }
            runCatching { MPVLib.removeLogObserver(logObserver) }
            runCatching { MPVLib.detachSurface() }
            runCatching { MPVLib.destroy() }
        }
        closeActivePfdLocked()
        ioScope.cancel()
        _uiState.value = PlayerUiState()
    }

    private inline fun runMpvSafe(opName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "MPVLib.$opName failed", t)
            _uiState.update { it.copy(error = "$opName failed: ${t.message ?: t::class.simpleName}") }
        }
    }
}
