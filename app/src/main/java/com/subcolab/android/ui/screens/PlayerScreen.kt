package com.subcolab.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.subcolab.android.model.JobProgressState
import com.subcolab.android.player.MpvPlayerController
import com.subcolab.android.ui.theme.SubcolabColors
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PlayerScreen(
    jobState: JobProgressState,
    liveSrtRevision: Long,
    subtitleFontSize: Int,
    isInPictureInPicture: Boolean,
    onEnterPip: () -> Unit,
    onOpenDebugLogs: () -> Unit = {},
    onPlayerVisibilityChange: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val controller = remember { MpvPlayerController(context.applicationContext) }
    val playerState by controller.uiState.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubValueSec by remember { mutableFloatStateOf(0f) }

    var gestureHudLabel by remember { mutableStateOf<String?>(null) }
    var gestureHudIsBrightness by remember { mutableStateOf(true) }

    // Apply true immersive sticky fullscreen (landscape + hidden status/navigation bars)
    LaunchedEffect(isFullscreen, isInPictureInPicture) {
        val window = activity?.window ?: return@LaunchedEffect
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isFullscreen && !isInPictureInPicture) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            if (!isInPictureInPicture) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Initialize libmpv, keep screen awake during playback, and restore portrait + system bars on exit
    DisposableEffect(Unit) {
        onPlayerVisibilityChange(true)
        controller.initializeIfNeeded(subtitleFontSize)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            onPlayerVisibilityChange(false)
            val window = activity?.window
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.release()
        }
    }

    // Load offline MP4, smooth live stream (+ optional DASH audio), chunked stream, or local device content:// URI
    LaunchedEffect(
        playerState.isInitialized,
        jobState.playableVideoSource,
        jobState.playableAudioSource,
        jobState.playableChunks.size,
        jobState.isChunkDownloadComplete,
        jobState.totalDurationSec,
    ) {
        if (playerState.isInitialized && (!jobState.playableVideoSource.isNullOrBlank() || jobState.playableChunks.isNotEmpty())) {
            controller.syncMediaState(
                stitchedSource = jobState.playableVideoSource,
                liveAudioUrl = jobState.playableAudioSource,
                chunks = jobState.playableChunks,
                totalDurationSec = jobState.totalDurationSec,
                chunksDownloadComplete = jobState.isChunkDownloadComplete,
            )
        }
    }

    // Attach/reload English + Original subtitles cleanly
    LaunchedEffect(
        playerState.isInitialized,
        liveSrtRevision,
        jobState.cueCount,
        jobState.origCueCount,
        jobState.liveSrtPath,
        jobState.liveOrigSrtPath,
    ) {
        if (playerState.isInitialized && (jobState.cueCount > 0 || jobState.origCueCount > 0)) {
            controller.syncLiveSubtitles(
                enSrtPath = jobState.liveSrtPath,
                enCueCount = jobState.cueCount,
                origSrtPath = jobState.liveOrigSrtPath,
                origCueCount = jobState.origCueCount,
                origLangCode = jobState.detectedOrigLang,
            )
        }
    }

    // Auto-hide controls after 3.5s
    LaunchedEffect(controlsVisible, playerState.isPaused, isScrubbing) {
        if (controlsVisible && !playerState.isPaused && !isScrubbing) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // Clear brightness/volume gesture HUD
    LaunchedEffect(gestureHudLabel) {
        if (gestureHudLabel != null) {
            delay(1100L)
            gestureHudLabel = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Native libmpv rendering SurfaceView
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            val w = if (width > 0) width else holder.surfaceFrame.width()
                            val h = if (height > 0) height else holder.surfaceFrame.height()
                            controller.attachSurface(holder.surface, w, h)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            controller.updateSurfaceSize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            controller.detachSurface()
                        }
                    })
                }
            },
        )

        // Gesture capture layer (tap to toggle HUD, double-tap left/right +/-10s, vertical drag brightness/volume)
        if (!isInPictureInPicture) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { offset ->
                                val target = if (offset.x < size.width / 2f) {
                                    (playerState.positionSec - 10.0).coerceAtLeast(0.0)
                                } else {
                                    (playerState.positionSec + 10.0)
                                }
                                controller.seekAbsolute(target)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { change, dragAmount ->
                            change.consume()
                            val isLeftHalf = change.position.x < size.width / 2f
                            val deltaRatio = -dragAmount / (size.height.coerceAtLeast(400).toFloat() * 0.7f)
                            if (isLeftHalf) {
                                val win = activity?.window ?: return@detectVerticalDragGestures
                                runCatching {
                                    val lp = win.attributes
                                    val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                                    val updated = (current + deltaRatio).coerceIn(0.05f, 1.0f)
                                    lp.screenBrightness = updated
                                    win.attributes = lp
                                    gestureHudIsBrightness = true
                                    gestureHudLabel = "${(updated * 100).toInt()}%"
                                }
                            } else {
                                runCatching {
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val step = if (deltaRatio > 0.03f) 1 else if (deltaRatio < -0.03f) -1 else 0
                                    if (step != 0) {
                                        val nextVol = (currentVol + step).coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                        gestureHudIsBrightness = false
                                        gestureHudLabel = "${(nextVol * 100 / maxVol)}%"
                                    } else {
                                        gestureHudIsBrightness = false
                                        gestureHudLabel = "${(currentVol * 100 / maxVol)}%"
                                    }
                                }
                            }
                        }
                    },
            )
        }

        // Brightness / Volume Gesture OSD
        AnimatedVisibility(
            visible = gestureHudLabel != null && !isInPictureInPicture,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                color = Color(0xCC0B0F17),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (gestureHudIsBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = SubcolabColors.CinemaSubtitleYellow,
                    )
                    Text(
                        text = gestureHudLabel.orEmpty(),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Loading / next-chunk buffering overlay
        if (playerState.isInitialized &&
            (!playerState.isFileLoaded || !playerState.bufferingMessage.isNullOrBlank()) &&
            playerState.error.isNullOrBlank() &&
            !isInPictureInPicture
        ) {
            Surface(
                color = Color(0xCC0B0F17),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = SubcolabColors.CinemaSubtitleYellow,
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        text = playerState.bufferingMessage ?: "Opening video...",
                        color = Color.White,
                        fontSize = 13.sp,
                    )
                }
            }
        }

        // Player Error Overlay
        if (!playerState.error.isNullOrBlank() && !isInPictureInPicture) {
            Surface(
                color = Color(0xEE131A26),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Player Error",
                        color = SubcolabColors.DangerRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = playerState.error.orEmpty(),
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { controller.retryLoad() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SubcolabColors.CinemaSubtitleYellow,
                                contentColor = Color(0xFF1A1200),
                            ),
                        ) {
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = onOpenDebugLogs) {
                            Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp), tint = SubcolabColors.CinemaSkyBlue)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Logs", color = SubcolabColors.CinemaSkyBlue)
                        }
                        TextButton(onClick = onBack) {
                            Text("Back", color = Color.White)
                        }
                    }
                }
            }
        }

        // Player Controls HUD
        AnimatedVisibility(
            visible = controlsVisible && !isInPictureInPicture,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xB3000000),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xCC000000),
                            ),
                        ),
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = jobState.titleSlug.replace('_', ' '),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                        )
                        val baseTrackLabel = when (playerState.activeSubtitleTrack) {
                            "orig" -> "${(playerState.origLangCode ?: "ORIG").uppercase()} subtitles (${jobState.origCueCount} cues)"
                            "off" -> "Subtitles OFF"
                            else -> "EN subtitles (${jobState.cueCount} cues)"
                        }
                        val streamSuffix = when {
                            jobState.isLiveStreaming -> " • Live Stream"
                            jobState.isStitching -> " • Stitching MP4"
                            jobState.playableChunks.isNotEmpty() && jobState.playableVideoSource == null ->
                                " • ${jobState.playableChunks.size} chunks"
                            else -> ""
                        }
                        val trackLabel = baseTrackLabel + streamSuffix
                        Text(
                            text = trackLabel,
                            color = if (playerState.activeSubtitleTrack == "off") {
                                Color(0xFFCBD5E1)
                            } else if (playerState.activeSubtitleTrack == "orig") {
                                SubcolabColors.CinemaSkyBlue
                            } else {
                                SubcolabColors.CinemaSubtitleYellow
                            },
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { controller.cycleSubtitles() }) {
                        Icon(
                            imageVector = if (playerState.subtitlesEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                            contentDescription = "Cycle Subtitles (EN / Orig / Off)",
                            tint = when (playerState.activeSubtitleTrack) {
                                "orig" -> SubcolabColors.CinemaSkyBlue
                                "off" -> Color.White
                                else -> SubcolabColors.CinemaSubtitleYellow
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when (playerState.activeSubtitleTrack) {
                                "orig" -> (playerState.origLangCode ?: "ORIG").uppercase()
                                "off" -> "OFF"
                                else -> "EN"
                            },
                            color = when (playerState.activeSubtitleTrack) {
                                "orig" -> SubcolabColors.CinemaSkyBlue
                                "off" -> Color.White
                                else -> SubcolabColors.CinemaSubtitleYellow
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = onEnterPip) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Picture in Picture",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { isFullscreen = !isFullscreen },
                    ) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isFullscreen) "Exit Fullscreen" else "Enter Fullscreen",
                            tint = Color.White,
                        )
                    }
                }

                // Center Transport Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            controller.seekAbsolute((playerState.positionSec - 10.0).coerceAtLeast(0.0))
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0x66000000), CircleShape),
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10s", tint = Color.White)
                    }

                    IconButton(
                        onClick = { controller.togglePlayPause() },
                        modifier = Modifier
                            .size(68.dp)
                            .background(SubcolabColors.CinemaSubtitleYellow, CircleShape),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "Play or Pause",
                            tint = Color(0xFF1A1200),
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    IconButton(
                        onClick = { controller.seekAbsolute(playerState.positionSec + 10.0) },
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0x66000000), CircleShape),
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                    }
                }

                // Bottom Seek Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    val duration = playerState.durationSec.coerceAtLeast(1.0).toFloat()
                    val current = if (isScrubbing) scrubValueSec else playerState.positionSec.toFloat().coerceIn(0f, duration)

                    Slider(
                        value = current,
                        onValueChange = { next ->
                            isScrubbing = true
                            scrubValueSec = next
                        },
                        onValueChangeFinished = {
                            isScrubbing = false
                            controller.seekAbsolute(scrubValueSec.toDouble())
                        },
                        valueRange = 0f..duration,
                        colors = SliderDefaults.colors(
                            thumbColor = SubcolabColors.CinemaSubtitleYellow,
                            activeTrackColor = SubcolabColors.CinemaSubtitleYellow,
                            inactiveTrackColor = Color(0x55FFFFFF),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatMmSs(current.toDouble()),
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = formatMmSs(playerState.durationSec),
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

private fun formatMmSs(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
