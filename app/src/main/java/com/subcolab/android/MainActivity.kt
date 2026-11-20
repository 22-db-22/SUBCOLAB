package com.subcolab.android

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.subcolab.android.model.JobPhase
import com.subcolab.android.model.WorkflowMode
import com.subcolab.android.service.SubcolabForegroundService
import com.subcolab.android.ui.MainViewModel
import com.subcolab.android.ui.components.DebugLogsDialog
import com.subcolab.android.ui.screens.HomeScreen
import com.subcolab.android.ui.screens.PlayerScreen
import com.subcolab.android.ui.screens.SettingsScreen
import com.subcolab.android.ui.theme.SubcolabColors
import com.subcolab.android.ui.theme.SubcolabTheme
import com.subcolab.android.ui.theme.isSubcolabDarkTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.subcolab.android.extra.OPEN_PLAYER"
        const val EXTRA_SWITCH_QUEUE_ID = "com.subcolab.android.extra.SWITCH_QUEUE_ID"

        fun createOpenPlayerIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_PLAYER, true)
            }
        }

        fun createSwitchToQueuedIntent(context: Context, queueId: String): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SWITCH_QUEUE_ID, queueId)
            }
        }
    }

    private val viewModel: MainViewModel by viewModels()
    private var openPlayerSignal by mutableStateOf(false)
    private var switchQueueIdSignal by mutableStateOf<String?>(null)
    private var isInPipMode by mutableStateOf(false)
    private var currentRoute by mutableStateOf("home")

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Best-effort runtime permission on Android 13+ */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)

        val app = application as SubcolabApp

        setContent {
            val settings by viewModel.settings.collectAsState()
            val enginesReady by app.enginesReady.collectAsState()
            val isDarkTheme = isSubcolabDarkTheme(settings.themeMode)

            SubcolabTheme(themeMode = settings.themeMode) {
                val rootView = LocalView.current
                LaunchedEffect(currentRoute, isDarkTheme) {
                    val isLightBars = !isDarkTheme && currentRoute != "player"
                    val insetsController = WindowCompat.getInsetsController(window, rootView)
                    insetsController.isAppearanceLightStatusBars = isLightBars
                    insetsController.isAppearanceLightNavigationBars = isLightBars
                }

                Crossfade(
                    targetState = enginesReady,
                    animationSpec = tween(durationMillis = 280),
                    label = "startup_crossfade",
                ) { ready ->
                    if (!ready) {
                        StartupLoadingScreen()
                    } else {
                        val navController = rememberNavController()
                        val urlInput by viewModel.urlInput.collectAsState()
                        val selectedLang by viewModel.selectedLang.collectAsState()
                        val selectedLocalVideoUri by viewModel.selectedLocalVideoUri.collectAsState()
                        val selectedLocalVideoName by viewModel.selectedLocalVideoName.collectAsState()
                        val jobState by viewModel.jobState.collectAsState()
                        val playerJobState by viewModel.playerState.collectAsState()
                        val liveSrtRevision by viewModel.liveSrtRevision.collectAsState()
                        val playerSrtRevision by viewModel.playerSrtRevision.collectAsState()
                        val authState by viewModel.authState.collectAsState()
                        val lastCrashText by viewModel.lastCrashText.collectAsState()
                        val debugLogs by viewModel.debugLogs.collectAsState()
                        var showDebugLogsDialog by remember { mutableStateOf(false) }

                        val hasPlayableMedia = jobState.playableVideoSource != null || jobState.playableChunks.isNotEmpty()
                        var autoOpenedJobKey by remember { mutableStateOf<String?>(null) }

                        // Switch to a queued video when user taps "Watch Now" on its top Android notification
                        LaunchedEffect(switchQueueIdSignal) {
                            val queueId = switchQueueIdSignal ?: return@LaunchedEffect
                            switchQueueIdSignal = null
                            if (viewModel.switchToQueuedOffer(this@MainActivity, queueId)) {
                                if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                    currentRoute = "player"
                                    navController.navigate("player") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }

                        // Open player when user taps "Watch Now" on Ready notification
                        LaunchedEffect(openPlayerSignal, hasPlayableMedia) {
                            if (openPlayerSignal && hasPlayableMedia) {
                                openPlayerSignal = false
                                viewModel.onPlayerOpened(this@MainActivity)
                                if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                    currentRoute = "player"
                                    navController.navigate("player") {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }

                        // Auto-open PlayerScreen once initial subtitles arrive in Watch Now (non-queued jobs only!)
                        LaunchedEffect(
                            jobState.phase,
                            jobState.isPlayerReady,
                            jobState.isActiveJobQueued,
                            jobState.isLiveStreaming,
                            jobState.playableVideoSource,
                            jobState.playableChunks.size,
                            jobState.cueCount,
                            jobState.mode,
                            jobState.activePackId,
                            jobState.currentUrl,
                        ) {
                            val activePhaseOk = jobState.phase !in setOf(
                                JobPhase.IDLE,
                                JobPhase.CANCELLED,
                                JobPhase.ERROR,
                            )
                            val activeKey = "${jobState.activePackId ?: "done"}|${jobState.currentUrl}"
                            if (!jobState.isPlayerReady || !activePhaseOk) {
                                autoOpenedJobKey = null
                            } else if (activePhaseOk &&
                                !jobState.isActiveJobQueued &&
                                jobState.mode == WorkflowMode.WATCH_NOW &&
                                hasPlayableMedia &&
                                jobState.cueCount > 0 &&
                                currentRoute == "home" &&
                                autoOpenedJobKey != activeKey
                            ) {
                                autoOpenedJobKey = activeKey
                                viewModel.onPlayerOpened(this@MainActivity)
                                currentRoute = "player"
                                navController.navigate("player") {
                                    launchSingleTop = true
                                }
                            }
                        }

                        NavHost(navController = navController, startDestination = "home") {
                            composable("home") {
                                currentRoute = "home"
                                HomeScreen(
                                    urlInput = urlInput,
                                    selectedLang = selectedLang,
                                    selectedLocalVideoUri = selectedLocalVideoUri,
                                    selectedLocalVideoName = selectedLocalVideoName,
                                    jobState = jobState,
                                    authState = authState,
                                    settings = settings,
                                    lastCrashText = lastCrashText,
                                    onUrlChange = viewModel::setUrlInput,
                                    onSelectLang = viewModel::setSelectedLang,
                                    onStartWatchNowJob = viewModel::startWatchNowJob,
                                    onStartDownloadJob = viewModel::startDownloadJob,
                                    onPickLocalVideo = viewModel::selectLocalVideo,
                                    onClearLocalVideo = viewModel::clearSelectedLocalVideo,
                                    onStartLocalVideoJob = viewModel::startLocalVideoJob,
                                    onRunPrevious = viewModel::runPrevious,
                                    onResumePrevious = viewModel::resumePreviousJob,
                                    onPrepareNewLink = viewModel::prepareNewLink,
                                    onCancelJob = viewModel::cancelCurrentJob,
                                    onStopT4Gpu = viewModel::stopT4Gpu,
                                    onOpenPlayer = {
                                        viewModel.onPlayerOpened(this@MainActivity)
                                        if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                            currentRoute = "player"
                                            navController.navigate("player") { launchSingleTop = true }
                                        }
                                    },
                                    onRedownloadPackVideo = viewModel::redownloadPackVideo,
                                    onRetranslatePackSubtitles = viewModel::retranslatePackSubtitles,
                                    onPlaySavedPack = { packId ->
                                        if (viewModel.openSavedPackInPlayer(packId)) {
                                            viewModel.onPlayerOpened(this@MainActivity)
                                            if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                                currentRoute = "player"
                                                navController.navigate("player") { launchSingleTop = true }
                                            }
                                        }
                                    },
                                    onDeleteSavedPack = viewModel::deletePackPermanently,
                                    onEnsurePackThumbnail = viewModel::ensurePackThumbnail,
                                    onOpenSettings = {
                                        currentRoute = "settings"
                                        navController.navigate("settings") { launchSingleTop = true }
                                    },
                                    onOpenDebugLogs = { showDebugLogsDialog = true },
                                    onCopyAllLogs = viewModel::buildFullLogReport,
                                    onDismissLastCrash = viewModel::clearLastCrash,
                                    onDismissQuickTips = viewModel::dismissQuickTips,
                                    onRemoveQueuedJob = viewModel::removeQueuedJob,
                                    onSwitchToQueuedOffer = { queueId ->
                                        if (viewModel.switchToQueuedOffer(this@MainActivity, queueId)) {
                                            if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                                currentRoute = "player"
                                                navController.navigate("player") { launchSingleTop = true }
                                            }
                                        }
                                    },
                                    onCancelQueuedOffer = { queueId ->
                                        viewModel.cancelQueuedOffer(this@MainActivity, queueId)
                                    },
                                )
                            }

                            composable("player") {
                                currentRoute = "player"
                                PlayerScreen(
                                    jobState = playerJobState,
                                    liveSrtRevision = playerSrtRevision,
                                    readyQueuedOffers = jobState.readyQueuedOffers,
                                    subtitleFontSize = settings.subtitleFontSize,
                                    subtitleDelaySec = settings.subtitleDelaySec,
                                    subtitleBorderSize = settings.subtitleBorderSize,
                                    subtitleBoxEnabled = settings.subtitleBoxEnabled,
                                    isInPictureInPicture = isInPipMode,
                                    onEnterPip = { enterPipIfSupported() },
                                    onOpenDebugLogs = { showDebugLogsDialog = true },
                                    onPlayerVisibilityChange = viewModel::setPlayerScreenOpen,
                                    onUpdateSubtitleDelay = viewModel::setSubtitleDelaySec,
                                    onUpdateSubtitleFontSize = viewModel::setSubtitleFontSize,
                                    onUpdateSubtitleStyle = { border, box ->
                                        viewModel.setSubtitleBorderSize(border)
                                        viewModel.setSubtitleBoxEnabled(box)
                                    },
                                    onRetryStream = viewModel::retryPlayback,
                                    onSwitchToQueuedOffer = { queueId ->
                                        viewModel.switchToQueuedOffer(this@MainActivity, queueId)
                                    },
                                    onCancelQueuedOffer = { queueId ->
                                        viewModel.cancelQueuedOffer(this@MainActivity, queueId)
                                    },
                                    onBack = {
                                        currentRoute = "home"
                                        navController.popBackStack()
                                    },
                                )
                            }

                            composable("settings") {
                                currentRoute = "settings"
                                SettingsScreen(
                                    authState = authState,
                                    settings = settings,
                                    lastCrashText = lastCrashText,
                                    onSignInLoopback = { viewModel.signInWithLoopback(this@MainActivity) },
                                    onLaunchCopyPasteAuth = { viewModel.launchCopyPasteAuth(this@MainActivity) },
                                    onCompleteCopyPasteAuth = viewModel::completeCopyPasteAuth,
                                    onDismissCopyPasteAuth = viewModel::dismissCopyPasteAuth,
                                    onSignOut = viewModel::signOut,
                                    onImportCookies = viewModel::importCookies,
                                    onClearCookies = viewModel::clearCookies,
                                    onSetCleanAudio = viewModel::setCleanAudio,
                                    onSetUseCustomTunnel = viewModel::setUseCustomTunnel,
                                    onSetCustomTunnelConfig = viewModel::setCustomTunnelConfig,
                                    onSetCustomOauthConfig = viewModel::setCustomOauthConfig,
                                    onSetSubtitleFontSize = viewModel::setSubtitleFontSize,
                                    onSetModelId = viewModel::setModelId,
                                    onSetThemeMode = viewModel::setThemeMode,
                                    onOpenDebugLogs = { showDebugLogsDialog = true },
                                    onCopyAllLogs = viewModel::buildFullLogReport,
                                    onBack = {
                                        currentRoute = "home"
                                        navController.popBackStack()
                                    },
                                )
                            }
                        }

                        // In-app Ready to Watch prompt (Watch Now / Later) when user remained in SUBCOLAB
                        if (jobState.isReadyPromptVisible &&
                            hasPlayableMedia &&
                            currentRoute != "player"
                        ) {
                            val isStreamingLive = jobState.isLiveStreaming || (jobState.playableChunks.isNotEmpty() && jobState.playableVideoSource == null)
                            AlertDialog(
                                onDismissRequest = {
                                    viewModel.dismissReadyPrompt()
                                    SubcolabForegroundService.cancelReadyNotification(this@MainActivity)
                                },
                                containerColor = SubcolabColors.SurfaceDark,
                                titleContentColor = SubcolabColors.TextPrimary,
                                textContentColor = SubcolabColors.TextSecondary,
                                title = {
                                    Text(
                                        text = if (jobState.isLiveStreaming) "Live Stream Ready" else if (isStreamingLive) "Ready to Watch" else "Download Ready",
                                        fontWeight = FontWeight.Bold,
                                    )
                                },
                                text = {
                                    Text(
                                        text = if (jobState.isLiveStreaming) {
                                            "${jobState.titleSlug.replace('_', ' ')}\n\n" +
                                                "${jobState.cueCount} English cues ready. Remaining subtitles will stream live in the background."
                                        } else if (isStreamingLive) {
                                            "${jobState.titleSlug.replace('_', ' ')}\n\n" +
                                                "${jobState.cueCount} English cues ready. Remaining chunks & subtitles will continue in the background."
                                        } else {
                                            "${jobState.titleSlug.replace('_', ' ')}\n\n" +
                                                "${jobState.cueCount} English subtitle cues ready. Saved to Downloads/SUBCOLAB/."
                                        },
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            viewModel.onPlayerOpened(this@MainActivity)
                                            if (currentRoute != "player" && navController.currentDestination?.route != "player") {
                                                currentRoute = "player"
                                                navController.navigate("player") { launchSingleTop = true }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SubcolabColors.SagePrimary,
                                            contentColor = Color.White,
                                        ),
                                    ) {
                                        Text("Watch Now", fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            viewModel.dismissReadyPrompt()
                                            SubcolabForegroundService.cancelReadyNotification(this@MainActivity)
                                        },
                                    ) {
                                        Text("Later", color = SubcolabColors.TextPrimary)
                                    }
                                },
                            )
                        }

                        if (showDebugLogsDialog) {
                            DebugLogsDialog(
                                lastCrashText = lastCrashText,
                                debugLogs = debugLogs,
                                onCopyAllLogs = viewModel::buildFullLogReport,
                                onClearLastCrash = viewModel::clearLastCrash,
                                onClearLogs = viewModel::clearDebugLogs,
                                onDismiss = { showDebugLogsDialog = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (currentRoute == "player") {
            enterPipIfSupported()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val switchQueueId = intent.getStringExtra(EXTRA_SWITCH_QUEUE_ID)
        if (!switchQueueId.isNullOrBlank()) {
            switchQueueIdSignal = switchQueueId
        } else if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            openPlayerSignal = true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun StartupLoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "startup_pulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring_scale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring_alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SubcolabColors.Obsidian),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(116.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .size(104.dp)
                        .scale(ringScale)
                        .alpha(ringAlpha),
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(2.dp, SubcolabColors.SagePrimary),
                ) {}

                CircularProgressIndicator(
                    modifier = Modifier.size(92.dp),
                    color = SubcolabColors.SagePrimary,
                    trackColor = SubcolabColors.SurfaceElevated,
                    strokeWidth = 3.5.dp,
                )

                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SubcolabColors.CardSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.ClosedCaption,
                        contentDescription = "SUBCOLAB",
                        tint = SubcolabColors.DangerRed,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "SUBCOLAB",
                color = SubcolabColors.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Starting SUBCOLAB...",
                color = SubcolabColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
