package com.openscribe.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openscribe.android.colab.AuthUiState
import com.openscribe.android.model.AppSettings
import com.openscribe.android.model.JobPhase
import com.openscribe.android.model.JobProgressState
import com.openscribe.android.model.SavePackStatus
import com.openscribe.android.model.SavedPack
import com.openscribe.android.model.SubtitleRetranslateOptions
import com.openscribe.android.model.WhisperLanguages
import com.openscribe.android.model.WorkflowMode
import com.openscribe.android.ui.theme.OpenScribeColors
import com.openscribe.android.util.UrlUnwrapper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    urlInput: String,
    selectedLang: String,
    selectedLocalVideoUri: String?,
    selectedLocalVideoName: String?,
    jobState: JobProgressState,
    authState: AuthUiState,
    settings: AppSettings,
    lastCrashText: String? = null,
    onUrlChange: (String) -> Unit,
    onSelectLang: (String) -> Unit,
    onStartWatchNowJob: () -> Unit,
    onStartDownloadJob: () -> Unit,
    onPickLocalVideo: (Uri) -> Unit,
    onClearLocalVideo: () -> Unit,
    onStartLocalVideoJob: () -> Unit,
    onRunPrevious: () -> Unit,
    onPrepareNewLink: () -> Unit,
    onCancelJob: () -> Unit,
    onStopT4Gpu: () -> Unit,
    onOpenPlayer: () -> Unit,
    onRedownloadPackVideo: (packId: String, maxHeight: Int) -> Unit = { _, _ -> },
    onRetranslatePackSubtitles: (packId: String, options: SubtitleRetranslateOptions) -> Unit = { _, _ -> },
    onPlaySavedPack: (packId: String) -> Unit = {},
    onDeleteSavedPack: (packId: String) -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenDebugLogs: () -> Unit = {},
    onCopyAllLogs: () -> String = { "" },
    onDismissLastCrash: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })

    var showLanguagePickerDialog by remember { mutableStateOf(false) }
    var languagePickerTargetForPack by remember { mutableStateOf<((String) -> Unit)?>(null) }

    var videoResolutionDialogPack by remember { mutableStateOf<SavedPack?>(null) }
    var subtitleOptionsDialogPack by remember { mutableStateOf<SavedPack?>(null) }

    val localVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPickLocalVideo(uri)
        }
    }

    val isRunning = jobState.phase in setOf(
        JobPhase.BOOTING_GPU,
        JobPhase.EXTRACTING_STREAM,
        JobPhase.LOADING_MODEL,
        JobPhase.TRANSLATING,
        JobPhase.SAVING_PACK,
    )

    Scaffold(
        containerColor = OpenScribeColors.Obsidian,
        topBar = {
            Column(modifier = Modifier.background(OpenScribeColors.Obsidian)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = OpenScribeColors.Obsidian,
                        titleContentColor = OpenScribeColors.TextPrimary,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OpenScribeColors.SubtitleYellow),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ClosedCaption,
                                    contentDescription = null,
                                    tint = OpenScribeColors.Obsidian,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "OpenScribe",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 19.sp,
                                )
                                Text(
                                    text = if (jobState.isGpuWarm) {
                                        "T4 GPU Warm • ${jobState.gpuRuntimeLabel ?: "Connected"}"
                                    } else {
                                        "Offline Video + Dual Subtitles"
                                    },
                                    fontSize = 11.sp,
                                    color = if (jobState.isGpuWarm) OpenScribeColors.SuccessGreen else OpenScribeColors.TextSecondary,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenDebugLogs) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = "Crash & Debug Logs",
                                tint = if (!lastCrashText.isNullOrBlank()) OpenScribeColors.DangerRed else OpenScribeColors.GpuCyan,
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = OpenScribeColors.TextPrimary,
                            )
                        }
                    },
                )

                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = OpenScribeColors.Obsidian,
                    contentColor = OpenScribeColors.SubtitleYellow,
                    indicator = { tabPositions ->
                        if (pagerState.currentPage < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                                height = 3.dp,
                                color = OpenScribeColors.SubtitleYellow,
                            )
                        }
                    },
                    divider = {
                        HorizontalDivider(color = OpenScribeColors.BorderSubtle.copy(alpha = 0.6f))
                    },
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(0) } },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Translate",
                                    fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        },
                        selectedContentColor = OpenScribeColors.SubtitleYellow,
                        unselectedContentColor = OpenScribeColors.TextSecondary,
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = { coroutineScope.launch { pagerState.animateScrollToPage(1) } },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                                val count = jobState.savedPacks.size
                                Text(
                                    text = if (count > 0) "Downloads ($count)" else "Downloads",
                                    fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Medium,
                                )
                            }
                        },
                        selectedContentColor = OpenScribeColors.GpuCyan,
                        unselectedContentColor = OpenScribeColors.TextSecondary,
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            if (page == 0) {
                TranslateTabContent(
                    urlInput = urlInput,
                    selectedLang = selectedLang,
                    selectedLocalVideoUri = selectedLocalVideoUri,
                    selectedLocalVideoName = selectedLocalVideoName,
                    jobState = jobState,
                    authState = authState,
                    settings = settings,
                    lastCrashText = lastCrashText,
                    isRunning = isRunning,
                    onUrlChange = onUrlChange,
                    onSelectLang = onSelectLang,
                    onOpenAllLanguages = {
                        languagePickerTargetForPack = null
                        showLanguagePickerDialog = true
                    },
                    onStartWatchNowJob = onStartWatchNowJob,
                    onStartDownloadJob = onStartDownloadJob,
                    onPickLocalVideo = { localVideoPicker.launch(arrayOf("video/*")) },
                    onClearLocalVideo = onClearLocalVideo,
                    onStartLocalVideoJob = onStartLocalVideoJob,
                    onRunPrevious = onRunPrevious,
                    onPrepareNewLink = onPrepareNewLink,
                    onCancelJob = onCancelJob,
                    onStopT4Gpu = onStopT4Gpu,
                    onOpenPlayer = onOpenPlayer,
                    onOpenSettings = onOpenSettings,
                    onOpenDebugLogs = onOpenDebugLogs,
                    onCopyAllLogs = {
                        clipboard.setText(AnnotatedString(onCopyAllLogs()))
                    },
                    onPasteClipboard = {
                        clipboard.getText()?.text?.let { pasted ->
                            onUrlChange(UrlUnwrapper.extractAndUnwrap(pasted))
                        }
                    },
                    onDismissLastCrash = onDismissLastCrash,
                    onJumpToDownloads = {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
            } else {
                DownloadsTabContent(
                    savedPacks = jobState.savedPacks,
                    activePackId = jobState.activePackId,
                    isRunning = isRunning,
                    jobStatusText = jobState.translationProgressText ?: jobState.saveStatusText ?: jobState.statusMessage,
                    onClickVideo = { pack ->
                        if (pack.hasVideo) {
                            if (!pack.isLocalVideo) {
                                videoResolutionDialogPack = pack
                            }
                        } else if (!pack.isLocalVideo) {
                            onRedownloadPackVideo(pack.effectiveId, 720)
                        }
                    },
                    onClickSubtitles = { pack ->
                        subtitleOptionsDialogPack = pack
                    },
                    onPlayPack = { pack -> onPlaySavedPack(pack.effectiveId) },
                    onDeletePack = { pack -> onDeleteSavedPack(pack.effectiveId) },
                    onCancelJob = onCancelJob,
                )
            }
        }
    }

    // Video Resolution Picker Dialog (triggered when clicking Lit Video badge)
    videoResolutionDialogPack?.let { targetPack ->
        VideoResolutionDialog(
            pack = targetPack,
            isRunning = isRunning,
            onDismiss = { videoResolutionDialogPack = null },
            onStartDownload = { resHeight ->
                onRedownloadPackVideo(targetPack.effectiveId, resHeight)
                videoResolutionDialogPack = null
            },
        )
    }

    // Subtitle Options / Improve Dialog (triggered when clicking Lit or Dull Subtitles badge)
    subtitleOptionsDialogPack?.let { targetPack ->
        SubtitleOptionsDialog(
            pack = targetPack,
            defaultCleanAudio = settings.cleanAudio,
            defaultModelId = settings.modelId,
            isRunning = isRunning,
            onDismiss = { subtitleOptionsDialogPack = null },
            onPickAllLanguages = { setter ->
                languagePickerTargetForPack = setter
                showLanguagePickerDialog = true
            },
            onStartRetranslate = { options ->
                onRetranslatePackSubtitles(targetPack.effectiveId, options)
                subtitleOptionsDialogPack = null
            },
        )
    }

    // Searchable / Free-Text Whisper Language Selector Dialog (~99 languages)
    if (showLanguagePickerDialog) {
        var searchQuery by remember { mutableStateOf("") }
        val filtered = remember(searchQuery) { WhisperLanguages.search(searchQuery) }
        val targetCallback = languagePickerTargetForPack ?: onSelectLang

        Dialog(onDismissRequest = { showLanguagePickerDialog = false }) {
            Surface(
                color = OpenScribeColors.SurfaceDark,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Whisper Source Language",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type language name or ISO code (e.g. es, fr, yue)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = OpenScribeColors.GpuCyan,
                            unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                            focusedTextColor = OpenScribeColors.TextPrimary,
                            unfocusedTextColor = OpenScribeColors.TextPrimary,
                        ),
                    )

                    if (searchQuery.isNotBlank()) {
                        TextButton(
                            onClick = {
                                targetCallback(searchQuery.trim())
                                showLanguagePickerDialog = false
                            },
                        ) {
                            Text(
                                text = "Use custom Whisper code/name: \"${searchQuery.trim()}\"",
                                color = OpenScribeColors.SubtitleYellow,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(filtered) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        targetCallback(lang.code)
                                        showLanguagePickerDialog = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = lang.name,
                                    color = OpenScribeColors.TextPrimary,
                                    fontSize = 14.sp,
                                )
                                Surface(
                                    color = OpenScribeColors.SurfaceElevated,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = lang.code,
                                        color = OpenScribeColors.GpuCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            HorizontalDivider(color = OpenScribeColors.BorderSubtle.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslateTabContent(
    urlInput: String,
    selectedLang: String,
    selectedLocalVideoUri: String?,
    selectedLocalVideoName: String?,
    jobState: JobProgressState,
    authState: AuthUiState,
    settings: AppSettings,
    lastCrashText: String?,
    isRunning: Boolean,
    onUrlChange: (String) -> Unit,
    onSelectLang: (String) -> Unit,
    onOpenAllLanguages: () -> Unit,
    onStartWatchNowJob: () -> Unit,
    onStartDownloadJob: () -> Unit,
    onPickLocalVideo: () -> Unit,
    onClearLocalVideo: () -> Unit,
    onStartLocalVideoJob: () -> Unit,
    onRunPrevious: () -> Unit,
    onPrepareNewLink: () -> Unit,
    onCancelJob: () -> Unit,
    onStopT4Gpu: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    onCopyAllLogs: () -> Unit,
    onPasteClipboard: () -> Unit,
    onDismissLastCrash: () -> Unit,
    onJumpToDownloads: () -> Unit,
) {
    val context = LocalContext.current
    var overlayBannerDismissed by remember { mutableStateOf(false) }
    val needsOverlayPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        !runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Last Uncaught Crash Banner
        if (!lastCrashText.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.DangerRed.copy(alpha = 0.14f)),
                border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = OpenScribeColors.DangerRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Previous Crash Captured",
                            color = OpenScribeColors.DangerRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissLastCrash) {
                            Text("Dismiss", color = OpenScribeColors.TextSecondary, fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = lastCrashText.lineSequence()
                            .firstOrNull { it.contains("Exception") || it.contains("Error") || it.startsWith("Caused by") }
                            ?: lastCrashText.lineSequence().take(2).joinToString(" "),
                        color = OpenScribeColors.TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenDebugLogs,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OpenScribeColors.DangerRed,
                                contentColor = OpenScribeColors.Obsidian,
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("View Crash & Logs", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = onCopyAllLogs,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.6f)),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = OpenScribeColors.TextPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy All Logs", color = OpenScribeColors.TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Colab Sign-In Banner
        if (!authState.isSignedIn && !settings.useCustomTunnel) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SubtitleYellowDark),
                border = BorderStroke(1.dp, OpenScribeColors.SubtitleYellow.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.WarningAmber, contentDescription = null, tint = OpenScribeColors.SubtitleYellow)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "One-Time Colab Sign-In Needed",
                            color = OpenScribeColors.SubtitleYellow,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = "Sign in with Google once in Settings to run Whisper large-v3 on a free T4 GPU.",
                            color = OpenScribeColors.TextPrimary,
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Sign In", color = OpenScribeColors.SubtitleYellow, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Auto-Open Over Other Apps Permission Banner (needed on Android 10+ so Watch Now pops over YouTube)
        if (needsOverlayPermission && !overlayBannerDismissed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.GpuCyanDark.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OpenScribeColors.GpuCyan)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Open Over Other Apps",
                            color = OpenScribeColors.GpuCyan,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "Allow once so Watch Now opens automatically over YouTube when initial subs arrive.",
                            color = OpenScribeColors.TextPrimary,
                            fontSize = 11.sp,
                        )
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                context.startActivity(intent)
                            }
                        },
                    ) {
                        Text("Allow", color = OpenScribeColors.GpuCyan, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { overlayBannerDismissed = true }) {
                        Text("Later", color = OpenScribeColors.TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        // Source Language Selector Card
        Card(
            colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Spoken Language -> English",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onOpenAllLanguages) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("All ~99", color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WhisperLanguages.quickChips.forEach { lang ->
                        val isSelected = selectedLang.equals(lang.code, ignoreCase = true)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectLang(lang.code) },
                            label = {
                                Text(
                                    text = if (lang.code == "auto") "auto (Default)" else "${lang.code} • ${lang.name}",
                                    fontSize = 12.sp,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OpenScribeColors.SubtitleYellowDark,
                                selectedLabelColor = OpenScribeColors.SubtitleYellow,
                                containerColor = OpenScribeColors.SurfaceElevated,
                                labelColor = OpenScribeColors.TextPrimary,
                            ),
                        )
                    }
                    if (WhisperLanguages.quickChips.none { it.code.equals(selectedLang, ignoreCase = true) }) {
                        FilterChip(
                            selected = true,
                            onClick = onOpenAllLanguages,
                            label = { Text("Selected: $selectedLang", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OpenScribeColors.GpuCyanDark,
                                selectedLabelColor = OpenScribeColors.GpuCyan,
                            ),
                        )
                    }
                }
            }
        }

        // Card 1: Paste Video Link
        Card(
            colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "1. Paste Video Link",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Watch Now live-streams smoothly with adaptive audio-only subtitle chunks. Download saves a full <=720p MP4 quietly in the background.",
                    fontSize = 12.sp,
                    color = OpenScribeColors.TextSecondary,
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Paste YouTube or video URL (or Share -> OpenScribe)",
                            fontSize = 13.sp,
                            color = OpenScribeColors.TextSecondary,
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = OpenScribeColors.SubtitleYellow,
                        unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                        focusedTextColor = OpenScribeColors.TextPrimary,
                        unfocusedTextColor = OpenScribeColors.TextPrimary,
                        cursorColor = OpenScribeColors.SubtitleYellow,
                    ),
                    trailingIcon = {
                        IconButton(onClick = onPasteClipboard) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste URL",
                                tint = OpenScribeColors.GpuCyan,
                            )
                        }
                    },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onStartDownloadJob,
                        enabled = urlInput.isNotBlank() && !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.7f)),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = OpenScribeColors.GpuCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", color = OpenScribeColors.GpuCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = onStartWatchNowJob,
                        enabled = urlInput.isNotBlank() && !isRunning,
                        modifier = Modifier
                            .weight(1.25f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpenScribeColors.SubtitleYellow,
                            contentColor = OpenScribeColors.Obsidian,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Watch Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Card 2: Already Saved Video on Device / Drive
        Card(
            colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "2. Saved Video on Device / Drive",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Already saved a video? Pick it to generate English subtitles and watch offline in OpenScribe.",
                    fontSize = 12.sp,
                    color = OpenScribeColors.TextSecondary,
                )

                if (!selectedLocalVideoName.isNullOrBlank()) {
                    Surface(
                        color = OpenScribeColors.SurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.VideoFile, contentDescription = null, tint = OpenScribeColors.GpuCyan)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = selectedLocalVideoName,
                                color = OpenScribeColors.TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onClearLocalVideo, enabled = !isRunning) {
                                Text("Clear", color = OpenScribeColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onPickLocalVideo,
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.7f)),
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = OpenScribeColors.GpuCyan,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedLocalVideoUri == null) "Choose Video" else "Change",
                            color = OpenScribeColors.GpuCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Button(
                        onClick = onStartLocalVideoJob,
                        enabled = selectedLocalVideoUri != null && !isRunning,
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpenScribeColors.GpuCyan,
                            contentColor = OpenScribeColors.Obsidian,
                        ),
                    ) {
                        Icon(Icons.Default.ClosedCaption, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Generate Subtitles", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Active / Latest Job Progress Card
        if (jobState.phase != JobPhase.IDLE) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    when (jobState.phase) {
                        JobPhase.ERROR -> OpenScribeColors.DangerRed.copy(alpha = 0.6f)
                        JobPhase.COMPLETED -> OpenScribeColors.SuccessGreen.copy(alpha = 0.6f)
                        else -> OpenScribeColors.GpuCyan.copy(alpha = 0.5f)
                    },
                ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when {
                            isRunning -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = OpenScribeColors.GpuCyan,
                                strokeWidth = 2.5.dp,
                            )
                            jobState.phase == JobPhase.COMPLETED -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = OpenScribeColors.SuccessGreen,
                            )
                            jobState.phase == JobPhase.ERROR -> Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = OpenScribeColors.DangerRed,
                            )
                            else -> Icon(
                                Icons.Default.Memory,
                                contentDescription = null,
                                tint = OpenScribeColors.SubtitleYellow,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = jobState.statusMessage,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = OpenScribeColors.TextPrimary,
                            )
                            val origPart = if (jobState.origCueCount > 0) {
                                " + ${jobState.origCueCount} ${jobState.detectedOrigLang?.uppercase() ?: "orig"}"
                            } else ""
                            Text(
                                text = "${jobState.titleSlug.replace('_', ' ')} • ${jobState.cueCount} EN$origPart cues",
                                fontSize = 12.sp,
                                color = OpenScribeColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (jobState.mode == WorkflowMode.DOWNLOAD_AND_TRANSLATE &&
                        (isRunning || jobState.savePackStatus == SavePackStatus.SAVING)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = jobState.saveStatusText ?: "Video download",
                                    fontSize = 11.sp,
                                    color = OpenScribeColors.TextSecondary,
                                )
                                Text(
                                    text = "${jobState.saveProgressPercent}%",
                                    fontSize = 11.sp,
                                    color = OpenScribeColors.SubtitleYellow,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (jobState.saveProgressPercent.coerceIn(0, 100) / 100f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(4.dp)),
                                color = OpenScribeColors.SubtitleYellow,
                                trackColor = OpenScribeColors.SurfaceElevated,
                            )
                        }
                    }

                    if (!jobState.translationProgressText.isNullOrBlank() && isRunning) {
                        Text(
                            text = "Subtitles: ${jobState.translationProgressText}",
                            fontSize = 11.sp,
                            color = OpenScribeColors.GpuCyan,
                        )
                        if (!jobState.chunkDownloadStatus.isNullOrBlank()) {
                            Text(
                                text = "Stream: ${jobState.chunkDownloadStatus}",
                                fontSize = 11.sp,
                                color = OpenScribeColors.SubtitleYellow,
                            )
                        }
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = OpenScribeColors.GpuCyan,
                            trackColor = OpenScribeColors.SurfaceElevated,
                        )
                    }

                    if (!jobState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = jobState.errorMessage,
                            color = OpenScribeColors.DangerRed,
                            fontSize = 12.sp,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (jobState.isPlayerReady &&
                            (jobState.playableVideoSource != null || jobState.playableChunks.isNotEmpty()) &&
                            jobState.cueCount > 0
                        ) {
                            val isStreamingLive = jobState.playableChunks.isNotEmpty() && jobState.playableVideoSource == null
                            Button(
                                onClick = onOpenPlayer,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OpenScribeColors.SubtitleYellow,
                                    contentColor = OpenScribeColors.Obsidian,
                                ),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isStreamingLive) "Watch Now (Live)" else "Watch Now",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (jobState.savedPacks.isNotEmpty() && !isRunning) {
                            OutlinedButton(
                                onClick = onJumpToDownloads,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.6f)),
                            ) {
                                Text("Downloads (${jobState.savedPacks.size})", color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
                            }
                        }
                        if (isRunning) {
                            OutlinedButton(
                                onClick = onCancelJob,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                            ) {
                                Text("Cancel Job", color = OpenScribeColors.TextPrimary)
                            }
                        }
                        if (jobState.phase == JobPhase.ERROR) {
                            OutlinedButton(
                                onClick = onOpenDebugLogs,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.6f)),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BugReport,
                                    contentDescription = null,
                                    tint = OpenScribeColors.DangerRed,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Debug Logs", color = OpenScribeColors.DangerRed, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Warm Session Reuse Menu (with Stop T4 GPU button cleanly placed BELOW text)
        if (jobState.isGpuWarm || jobState.previousJob != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Warm T4 Session",
                            style = MaterialTheme.typography.titleMedium,
                            color = OpenScribeColors.GpuCyan,
                        )
                        Text(
                            text = if (jobState.isGpuWarm) {
                                "Whisper ${settings.modelId} weights stay cached in Colab VRAM for instant next jobs."
                            } else {
                                "Previous job cached for instant replay"
                            },
                            fontSize = 12.sp,
                            color = OpenScribeColors.TextSecondary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val prev = jobState.previousJob
                        if (prev != null) {
                            OutlinedButton(
                                onClick = onRunPrevious,
                                enabled = !isRunning,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, OpenScribeColors.SubtitleYellow.copy(alpha = 0.7f)),
                            ) {
                                Icon(
                                    Icons.Default.Replay,
                                    contentDescription = null,
                                    tint = OpenScribeColors.SubtitleYellow,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Previous", color = OpenScribeColors.SubtitleYellow, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = onPrepareNewLink,
                            enabled = !isRunning,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                Icons.Default.AddLink,
                                contentDescription = null,
                                tint = OpenScribeColors.GpuCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("New Video", color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
                        }
                    }

                    if (jobState.isGpuWarm) {
                        OutlinedButton(
                            onClick = onStopT4Gpu,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Icon(
                                Icons.Default.StopCircle,
                                contentDescription = null,
                                tint = OpenScribeColors.DangerRed,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stop T4 GPU Runtime",
                                color = OpenScribeColors.DangerRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    jobState.previousJob?.let { prev ->
                        val cacheNote = buildList {
                            if (prev.subtitlesCompleted) add("Subtitles cached")
                            if (!prev.savedMp4Uri.isNullOrBlank() || !prev.savedMp4Path.isNullOrBlank()) add("MP4 cached")
                        }.joinToString(" • ")
                        if (cacheNote.isNotEmpty()) {
                            Text(
                                text = "Smart Cache: $cacheNote",
                                fontSize = 11.sp,
                                color = OpenScribeColors.SuccessGreen,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun DownloadsTabContent(
    savedPacks: List<SavedPack>,
    activePackId: String?,
    isRunning: Boolean,
    jobStatusText: String,
    onClickVideo: (SavedPack) -> Unit,
    onClickSubtitles: (SavedPack) -> Unit,
    onPlayPack: (SavedPack) -> Unit,
    onDeletePack: (SavedPack) -> Unit,
    onCancelJob: () -> Unit,
) {
    if (savedPacks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = OpenScribeColors.SurfaceElevated,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = OpenScribeColors.GpuCyan,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Text(
                    text = "No Downloads Yet",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Each downloaded video and its English + original subtitles will live in its own subfolder inside Downloads/OpenScribe/.",
                    fontSize = 13.sp,
                    color = OpenScribeColors.TextSecondary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Saved Packs (Downloads/OpenScribe/<title>/)",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = OpenScribeColors.TextSecondary,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }

        items(savedPacks, key = { it.effectiveId }) { pack ->
            val isThisPackRunning = isRunning && activePackId == pack.effectiveId
            val hasVideo = pack.hasVideo
            val hasSubs = pack.hasSubtitles

            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    if (isThisPackRunning) OpenScribeColors.GpuCyan.copy(alpha = 0.7f)
                    else OpenScribeColors.BorderSubtle,
                ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header: Short Name + Folder Meta
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pack.titleSlug.replace('_', ' '),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = OpenScribeColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val langSummary = buildList {
                                add("Folder: ${pack.effectiveFolder}")
                                if (pack.cueCount > 0) add("${pack.cueCount} EN")
                                if (pack.origCueCount > 0) {
                                    add("${pack.origCueCount} ${(pack.detectedLang ?: "ORIG").uppercase()}")
                                }
                            }.joinToString(" • ")
                            Text(
                                text = langSummary,
                                fontSize = 11.sp,
                                color = OpenScribeColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isThisPackRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = OpenScribeColors.GpuCyan,
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }

                    if (isThisPackRunning) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = jobStatusText,
                                fontSize = 11.sp,
                                color = OpenScribeColors.GpuCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onCancelJob,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("Stop", color = OpenScribeColors.DangerRed, fontSize = 11.sp)
                            }
                        }
                    }

                    // Side-by-side Video & Subtitles Status/Action Buttons (Lit vs Dull)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Video Button
                        Surface(
                            onClick = { onClickVideo(pack) },
                            enabled = !isRunning && !(pack.isLocalVideo && hasVideo),
                            shape = RoundedCornerShape(12.dp),
                            color = if (hasVideo) {
                                OpenScribeColors.GpuCyanDark.copy(alpha = 0.75f)
                            } else {
                                OpenScribeColors.SurfaceElevated.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (hasVideo) OpenScribeColors.GpuCyan.copy(alpha = 0.7f)
                                else OpenScribeColors.BorderSubtle.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = if (hasVideo) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = null,
                                    tint = if (hasVideo) OpenScribeColors.GpuCyan else OpenScribeColors.TextSecondary.copy(alpha = 0.65f),
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        pack.isLocalVideo && hasVideo -> "Device Video"
                                        hasVideo -> "Video • ${pack.videoResolution}p"
                                        else -> "Get Video"
                                    },
                                    color = if (hasVideo) OpenScribeColors.GpuCyan else OpenScribeColors.TextSecondary.copy(alpha = 0.75f),
                                    fontWeight = if (hasVideo) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Subtitles Button
                        Surface(
                            onClick = { onClickSubtitles(pack) },
                            enabled = !isRunning,
                            shape = RoundedCornerShape(12.dp),
                            color = if (hasSubs) {
                                OpenScribeColors.SubtitleYellowDark.copy(alpha = 0.85f)
                            } else {
                                OpenScribeColors.SurfaceElevated.copy(alpha = 0.55f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (hasSubs) OpenScribeColors.SubtitleYellow.copy(alpha = 0.75f)
                                else OpenScribeColors.BorderSubtle.copy(alpha = 0.6f),
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ClosedCaption,
                                    contentDescription = null,
                                    tint = if (hasSubs) OpenScribeColors.SubtitleYellow else OpenScribeColors.TextSecondary.copy(alpha = 0.65f),
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        hasSubs && pack.origCueCount > 0 -> "Subs • EN+${(pack.detectedLang ?: "ORIG").uppercase()}"
                                        hasSubs -> "Subs • ${pack.cueCount} EN"
                                        else -> "Get Subtitles"
                                    },
                                    color = if (hasSubs) OpenScribeColors.SubtitleYellow else OpenScribeColors.TextSecondary.copy(alpha = 0.75f),
                                    fontWeight = if (hasSubs) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Bottom Row: Play + Permanent Delete (no confirmation dialog)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onPlayPack(pack) },
                            enabled = hasVideo,
                            modifier = Modifier
                                .weight(1.4f)
                                .height(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OpenScribeColors.SubtitleYellow,
                                contentColor = OpenScribeColors.Obsidian,
                                disabledContainerColor = OpenScribeColors.SurfaceElevated,
                                disabledContentColor = OpenScribeColors.TextSecondary,
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { onDeletePack(pack) },
                            enabled = !isThisPackRunning,
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp),
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.65f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = "Delete Permanently",
                                tint = OpenScribeColors.DangerRed,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", color = OpenScribeColors.DangerRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(20.dp)) }
    }
}

@Composable
private fun VideoResolutionDialog(
    pack: SavedPack,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onStartDownload: (maxHeight: Int) -> Unit,
) {
    var selectedHeight by remember { mutableIntStateOf(pack.videoResolution.takeIf { it in listOf(360, 480, 720, 1080) } ?: 720) }
    val options = listOf(
        360 to "360p (Compact & Fastest)",
        480 to "480p (Balanced Size)",
        720 to "720p HD (Default)",
        1080 to "1080p Full HD (Largest)",
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = OpenScribeColors.SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Re-download Video",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = pack.titleSlug.replace('_', ' '),
                    fontSize = 12.sp,
                    color = OpenScribeColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                options.forEach { (height, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selectedHeight = height }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedHeight == height,
                            onClick = { selectedHeight = height },
                            colors = RadioButtonDefaults.colors(selectedColor = OpenScribeColors.GpuCyan),
                        )
                        Text(text = label, color = OpenScribeColors.TextPrimary, fontSize = 14.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = OpenScribeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onStartDownload(selectedHeight) },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpenScribeColors.GpuCyan,
                            contentColor = OpenScribeColors.Obsidian,
                        ),
                    ) {
                        Text("Download in Background", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleOptionsDialog(
    pack: SavedPack,
    defaultCleanAudio: Boolean,
    defaultModelId: String,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onPickAllLanguages: (setter: (String) -> Unit) -> Unit,
    onStartRetranslate: (SubtitleRetranslateOptions) -> Unit,
) {
    var lang by remember { mutableStateOf(pack.sourceLang.ifBlank { "auto" }) }
    var modelId by remember { mutableStateOf(defaultModelId.ifBlank { "large-v3" }) }
    var cleanAudio by remember { mutableStateOf(defaultCleanAudio) }
    var vocalBoost by remember { mutableStateOf(false) }
    var includeOriginal by remember { mutableStateOf(false) }
    var highPrecisionBeam by remember { mutableStateOf(pack.hasSubtitles) }
    var promptText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = OpenScribeColors.SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = OpenScribeColors.SubtitleYellow)
                    Text(
                        text = if (pack.hasSubtitles) "Download Again / Improve Subtitles" else "Generate Subtitles",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // Language selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Source Language", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { onPickAllLanguages { lang = it } }) {
                        Text("Browse All ~99", color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhisperLanguages.quickChips.forEach { item ->
                        FilterChip(
                            selected = lang.equals(item.code, ignoreCase = true),
                            onClick = { lang = item.code },
                            label = { Text(item.code, fontSize = 12.sp) },
                        )
                    }
                    if (WhisperLanguages.quickChips.none { it.code.equals(lang, ignoreCase = true) }) {
                        FilterChip(
                            selected = true,
                            onClick = { onPickAllLanguages { lang = it } },
                            label = { Text(lang, fontSize = 12.sp) },
                        )
                    }
                }

                // Model selector
                Text("Whisper Model", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "large-v3" to "large-v3 (Best)",
                        "large-v3-turbo" to "turbo (Fast)",
                        "medium" to "medium",
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = modelId == id,
                            onClick = { modelId = id },
                            label = { Text(label, fontSize = 11.sp) },
                        )
                    }
                }

                // Audio / Accuracy Toggles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clean Audio", fontSize = 13.sp, color = OpenScribeColors.TextPrimary)
                        Text("Noise reduction + loudness boost", fontSize = 11.sp, color = OpenScribeColors.TextSecondary)
                    }
                    Switch(
                        checked = cleanAudio,
                        onCheckedChange = { cleanAudio = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = OpenScribeColors.GpuCyan),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vocal Boost / Cut Music", fontSize = 13.sp, color = OpenScribeColors.TextPrimary)
                        Text("Isolates voice frequencies in songs or BGM", fontSize = 11.sp, color = OpenScribeColors.TextSecondary)
                    }
                    Switch(
                        checked = vocalBoost,
                        onCheckedChange = { vocalBoost = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = OpenScribeColors.GpuCyan),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("High-Precision Beam Search", fontSize = 13.sp, color = OpenScribeColors.TextPrimary)
                        Text("Beam width 8 + higher patience for accuracy", fontSize = 11.sp, color = OpenScribeColors.TextSecondary)
                    }
                    Switch(
                        checked = highPrecisionBeam,
                        onCheckedChange = { highPrecisionBeam = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = OpenScribeColors.SubtitleYellow),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Include Original Language Subtitles", fontSize = 13.sp, color = OpenScribeColors.TextPrimary)
                        Text("Also transcribe & save original-language .srt/.ass/.vtt", fontSize = 11.sp, color = OpenScribeColors.TextSecondary)
                    }
                    Switch(
                        checked = includeOriginal,
                        onCheckedChange = { includeOriginal = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = OpenScribeColors.SubtitleYellow),
                    )
                }

                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Context / Character Names (Optional)", fontSize = 12.sp) },
                    placeholder = { Text("e.g. Ryu Sun-jae, Im Sol, Eclipse band", fontSize = 12.sp) },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = OpenScribeColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onStartRetranslate(
                                SubtitleRetranslateOptions(
                                    sourceLang = lang.ifBlank { "auto" },
                                    cleanAudio = cleanAudio,
                                    vocalBoost = vocalBoost,
                                    includeOriginal = includeOriginal,
                                    modelId = modelId,
                                    beamSize = if (highPrecisionBeam) 8 else 5,
                                    initialPrompt = promptText.trim(),
                                ),
                            )
                        },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpenScribeColors.SubtitleYellow,
                            contentColor = OpenScribeColors.Obsidian,
                        ),
                    ) {
                        Text("Run in Background", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
