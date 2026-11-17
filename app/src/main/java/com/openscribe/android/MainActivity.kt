package com.openscribe.android

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.openscribe.android.service.OpenScribeForegroundService
import com.openscribe.android.ui.MainViewModel
import com.openscribe.android.ui.components.DebugLogsDialog
import com.openscribe.android.ui.screens.HomeScreen
import com.openscribe.android.ui.screens.PlayerScreen
import com.openscribe.android.ui.screens.SettingsScreen
import com.openscribe.android.ui.theme.OpenScribeColors
import com.openscribe.android.ui.theme.OpenScribeTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.openscribe.android.extra.OPEN_PLAYER"

        fun createOpenPlayerIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_PLAYER, true)
            }
        }
    }

    private val viewModel: MainViewModel by viewModels()
    private var openPlayerSignal by mutableStateOf(false)
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

        setContent {
            OpenScribeTheme {
                val rootView = LocalView.current
                LaunchedEffect(currentRoute) {
                    val isLightBars = currentRoute != "player"
                    val insetsController = WindowCompat.getInsetsController(window, rootView)
                    insetsController.isAppearanceLightStatusBars = isLightBars
                    insetsController.isAppearanceLightNavigationBars = isLightBars
                }
                val navController = rememberNavController()
                val urlInput by viewModel.urlInput.collectAsState()
                val selectedLang by viewModel.selectedLang.collectAsState()
                val selectedLocalVideoUri by viewModel.selectedLocalVideoUri.collectAsState()
                val selectedLocalVideoName by viewModel.selectedLocalVideoName.collectAsState()
                val jobState by viewModel.jobState.collectAsState()
                val liveSrtRevision by viewModel.liveSrtRevision.collectAsState()
                val authState by viewModel.authState.collectAsState()
                val settings by viewModel.settings.collectAsState()
                val lastCrashText by viewModel.lastCrashText.collectAsState()
                val debugLogs by viewModel.debugLogs.collectAsState()
                var showDebugLogsDialog by remember { mutableStateOf(false) }

                val hasPlayableMedia = jobState.playableVideoSource != null || jobState.playableChunks.isNotEmpty()
                var autoOpenedJobKey by remember { mutableStateOf<String?>(null) }

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

                // Auto-open PlayerScreen once initial subtitles arrive in Watch Now (live stream or fallback chunks)
                LaunchedEffect(
                    jobState.isPlayerReady,
                    jobState.isLiveStreaming,
                    jobState.playableVideoSource,
                    jobState.playableChunks.size,
                    jobState.cueCount,
                    jobState.mode,
                    jobState.activePackId,
                    jobState.currentUrl,
                ) {
                    val activeKey = "${jobState.activePackId ?: ""}|${jobState.currentUrl}"
                    if (!jobState.isPlayerReady) {
                        autoOpenedJobKey = null
                    } else if (jobState.mode == com.openscribe.android.model.WorkflowMode.WATCH_NOW &&
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
                            onOpenSettings = {
                                currentRoute = "settings"
                                navController.navigate("settings") { launchSingleTop = true }
                            },
                            onOpenDebugLogs = { showDebugLogsDialog = true },
                            onCopyAllLogs = viewModel::buildFullLogReport,
                            onDismissLastCrash = viewModel::clearLastCrash,
                        )
                    }

                    composable("player") {
                        currentRoute = "player"
                        PlayerScreen(
                            jobState = jobState,
                            liveSrtRevision = liveSrtRevision,
                            subtitleFontSize = settings.subtitleFontSize,
                            isInPictureInPicture = isInPipMode,
                            onEnterPip = { enterPipIfSupported() },
                            onOpenDebugLogs = { showDebugLogsDialog = true },
                            onPlayerVisibilityChange = viewModel::setPlayerScreenOpen,
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
                            onOpenDebugLogs = { showDebugLogsDialog = true },
                            onCopyAllLogs = viewModel::buildFullLogReport,
                            onBack = {
                                currentRoute = "home"
                                navController.popBackStack()
                            },
                        )
                    }
                }

                // In-app Ready to Watch prompt (Watch Now / Later) when user remained in OpenScribe
                if (jobState.isReadyPromptVisible &&
                    hasPlayableMedia &&
                    currentRoute != "player"
                ) {
                    val isStreamingLive = jobState.isLiveStreaming || (jobState.playableChunks.isNotEmpty() && jobState.playableVideoSource == null)
                    AlertDialog(
                        onDismissRequest = {
                            viewModel.dismissReadyPrompt()
                            OpenScribeForegroundService.cancelReadyNotification(this@MainActivity)
                        },
                        containerColor = OpenScribeColors.SurfaceDark,
                        titleContentColor = OpenScribeColors.TextPrimary,
                        textContentColor = OpenScribeColors.TextSecondary,
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
                                        "${jobState.cueCount} English subtitle cues ready. Saved to Downloads/OpenScribe/."
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
                                    containerColor = OpenScribeColors.SagePrimary,
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
                                    OpenScribeForegroundService.cancelReadyNotification(this@MainActivity)
                                },
                            ) {
                                Text("Later", color = OpenScribeColors.TextPrimary)
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
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
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
