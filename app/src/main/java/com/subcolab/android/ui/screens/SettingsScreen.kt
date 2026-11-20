package com.subcolab.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.subcolab.android.colab.AuthUiState
import com.subcolab.android.model.AppSettings
import com.subcolab.android.model.ThemeMode
import com.subcolab.android.ui.theme.SubcolabColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    authState: AuthUiState,
    settings: AppSettings,
    lastCrashText: String? = null,
    onSignInLoopback: () -> Unit,
    onLaunchCopyPasteAuth: () -> Unit,
    onCompleteCopyPasteAuth: (String) -> Unit,
    onDismissCopyPasteAuth: () -> Unit,
    onSignOut: () -> Unit,
    onImportCookies: (Uri) -> Boolean,
    onClearCookies: () -> Unit,
    onSetCleanAudio: (Boolean) -> Unit,
    onSetUseCustomTunnel: (Boolean) -> Unit,
    onSetCustomTunnelConfig: (String, String) -> Unit,
    onSetCustomOauthConfig: (String, String) -> Unit,
    onSetSubtitleFontSize: (Int) -> Unit,
    onSetModelId: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onOpenDebugLogs: () -> Unit = {},
    onCopyAllLogs: () -> String = { "" },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    var manualCodeInput by remember { mutableStateOf("") }
    var cookiesStatusMsg by remember { mutableStateOf<String?>(null) }
    var tunnelUrlInput by remember(settings.customTunnelUrl) { mutableStateOf(settings.customTunnelUrl) }
    var tunnelTokenInput by remember(settings.customTunnelToken) { mutableStateOf(settings.customTunnelToken) }
    var customClientIdInput by remember(settings.customOauthClientId) { mutableStateOf(settings.customOauthClientId) }
    var customClientSecretInput by remember(settings.customOauthClientSecret) { mutableStateOf(settings.customOauthClientSecret) }
    var showCustomOauth by remember(settings.customOauthClientId) { mutableStateOf(settings.customOauthClientId.isNotBlank()) }
    var modelInput by remember(settings.modelId) { mutableStateOf(settings.modelId) }

    val cookiesPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val ok = onImportCookies(uri)
            cookiesStatusMsg = if (ok) "cookies.txt imported to app internal storage" else "Failed to read selected file"
        }
    }

    Scaffold(
        containerColor = SubcolabColors.Obsidian,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SubcolabColors.Obsidian,
                    titleContentColor = SubcolabColors.TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SubcolabColors.TextPrimary,
                        )
                    }
                },
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 0. Appearance / Theme Mode Selector (Soft Dark / Light / Dark / System)
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = SubcolabColors.SagePrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Appearance", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Choose Soft Dark (default), Light, Dark, or System mode (player stays cinema-black).",
                                color = SubcolabColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.chunked(2).forEach { rowModes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowModes.forEach { mode ->
                                    val selected = settings.themeMode == mode
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSetThemeMode(mode) },
                                        label = {
                                            Text(
                                                text = mode.label,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SubcolabColors.SagePrimary,
                                            selectedLabelColor = Color.White,
                                            containerColor = SubcolabColors.SurfaceElevated,
                                            labelColor = SubcolabColors.TextPrimary,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Google Colab OAuth2 Account
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = SubcolabColors.GpuCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Colab Account", style = MaterialTheme.typography.titleMedium)
                    }

                    if (authState.isSignedIn) {
                        Text(
                            text = "Signed in as ${authState.userEmail ?: "Google Colab user"}",
                            color = SubcolabColors.SuccessGreen,
                            fontSize = 13.sp,
                        )
                        OutlinedButton(
                            onClick = onSignOut,
                            border = BorderStroke(1.dp, SubcolabColors.DangerRed.copy(alpha = 0.6f)),
                        ) {
                            Text("Sign Out", color = SubcolabColors.DangerRed)
                        }
                    } else {
                        Text(
                            text = "One-time Google Colab OAuth2 sign-in (matches google-colab-cli's sdk.cloud.google.com flow — works out of the box with zero redirect errors).",
                            color = SubcolabColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = onLaunchCopyPasteAuth,
                            enabled = !authState.isSigningIn,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SubcolabColors.SagePrimary,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                text = if (authState.isSigningIn) "Completing sign-in..." else "Sign in with Google (Colab)",
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        if (customClientIdInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = onSignInLoopback,
                                enabled = !authState.isSigningIn,
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, SubcolabColors.SubtitleYellow.copy(alpha = 0.7f)),
                            ) {
                                Text(
                                    text = "Sign in via Custom OAuth Loopback",
                                    color = SubcolabColors.SubtitleYellow,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        TextButton(onClick = { showCustomOauth = !showCustomOauth }) {
                            Text(
                                text = if (showCustomOauth) "Hide Optional Custom OAuth Client" else "Optional: Use Custom OAuth Client ID",
                                color = SubcolabColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    if (showCustomOauth && !authState.isSignedIn) {
                        OutlinedTextField(
                            value = customClientIdInput,
                            onValueChange = {
                                customClientIdInput = it
                                onSetCustomOauthConfig(it, customClientSecretInput)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom Desktop OAuth Client ID") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SubcolabColors.GpuCyan,
                                unfocusedBorderColor = SubcolabColors.BorderSubtle,
                                focusedTextColor = SubcolabColors.TextPrimary,
                                unfocusedTextColor = SubcolabColors.TextPrimary,
                            ),
                        )
                        OutlinedTextField(
                            value = customClientSecretInput,
                            onValueChange = {
                                customClientSecretInput = it
                                onSetCustomOauthConfig(customClientIdInput, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom OAuth Client Secret (if required)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SubcolabColors.GpuCyan,
                                unfocusedBorderColor = SubcolabColors.BorderSubtle,
                                focusedTextColor = SubcolabColors.TextPrimary,
                                unfocusedTextColor = SubcolabColors.TextPrimary,
                            ),
                        )
                    }

                    if (authState.awaitingManualCode) {
                        Text(
                            text = "Approve in the browser tab, copy the authorization code shown on Google's page, and paste it below:",
                            color = SubcolabColors.SubtitleYellow,
                            fontSize = 12.sp,
                        )
                        OutlinedTextField(
                            value = manualCodeInput,
                            onValueChange = { manualCodeInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Paste Google Authorization Code") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        clipboard.getText()?.text?.let { pasted ->
                                            manualCodeInput = pasted.trim()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste Code",
                                        tint = SubcolabColors.SubtitleYellow,
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SubcolabColors.SubtitleYellow,
                                unfocusedBorderColor = SubcolabColors.BorderSubtle,
                                focusedTextColor = SubcolabColors.TextPrimary,
                                unfocusedTextColor = SubcolabColors.TextPrimary,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onCompleteCopyPasteAuth(manualCodeInput) },
                                enabled = !authState.isSigningIn && manualCodeInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SubcolabColors.SagePrimary,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Verify Code", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = onDismissCopyPasteAuth) {
                                Text("Cancel", color = SubcolabColors.TextSecondary)
                            }
                        }
                    }

                    if (!authState.error.isNullOrBlank()) {
                        Text(authState.error, color = SubcolabColors.DangerRed, fontSize = 12.sp)
                    }
                }
            }

            // 2. YouTube cookies.txt Picker (No All Files Access needed!)
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cookie, contentDescription = null, tint = SubcolabColors.SubtitleYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("YouTube cookies.txt (Optional)", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = if (settings.hasCookiesFile) {
                            "cookies.txt loaded in private app storage. Uploaded automatically to /content/cookies.txt on Colab."
                        } else {
                            "Only needed if YouTube requires sign-in for age-restricted or heavily rate-limited videos."
                        },
                        color = if (settings.hasCookiesFile) SubcolabColors.SuccessGreen else SubcolabColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    cookiesStatusMsg?.let {
                        Text(it, color = SubcolabColors.GpuCyan, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { cookiesPicker.launch(arrayOf("text/plain", "*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SubcolabColors.SurfaceElevated,
                                contentColor = SubcolabColors.TextPrimary,
                            ),
                        ) {
                            Text("Import cookies.txt")
                        }
                        if (settings.hasCookiesFile) {
                            OutlinedButton(
                                onClick = {
                                    onClearCookies()
                                    cookiesStatusMsg = "Removed cookies.txt"
                                },
                                border = BorderStroke(1.dp, SubcolabColors.DangerRed.copy(alpha = 0.5f)),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = SubcolabColors.DangerRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove", color = SubcolabColors.DangerRed)
                            }
                        }
                    }
                }
            }

            // 3. Whisper & Audio Engine Settings
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = SubcolabColors.GpuCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Whisper & Subtitle Engine", style = MaterialTheme.typography.titleMedium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clean Noisy Audio (afftdn + loudnorm)",
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                            )
                            Text(
                                text = "OFF by default for fast ~15-25s start even on 2-hour videos. Turn ON only for noisy mic recordings.",
                                color = SubcolabColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = settings.cleanAudio,
                            onCheckedChange = onSetCleanAudio,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SubcolabColors.SagePrimary,
                            ),
                        )
                    }

                    Column {
                        Text(
                            text = "Subtitle Font Size: ${settings.subtitleFontSize}",
                            fontSize = 13.sp,
                            color = SubcolabColors.TextPrimary,
                        )
                        Slider(
                            value = settings.subtitleFontSize.toFloat(),
                            onValueChange = { onSetSubtitleFontSize(it.toInt()) },
                            valueRange = 28f..72f,
                            colors = SliderDefaults.colors(
                                thumbColor = SubcolabColors.SubtitleYellow,
                                activeTrackColor = SubcolabColors.SubtitleYellow,
                            ),
                        )
                    }

                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = {
                            modelInput = it
                            onSetModelId(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("faster-whisper Model ID") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SubcolabColors.GpuCyan,
                            unfocusedBorderColor = SubcolabColors.BorderSubtle,
                            focusedTextColor = SubcolabColors.TextPrimary,
                            unfocusedTextColor = SubcolabColors.TextPrimary,
                        ),
                    )
                }
            }

            // 4. Optional Custom Colab Tunnel URL Fallback
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Custom Colab Tunnel URL (Fallback)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "Use a direct HTTP/SSE worker endpoint instead of Colab OAuth APIs.",
                                color = SubcolabColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = settings.useCustomTunnel,
                            onCheckedChange = onSetUseCustomTunnel,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SubcolabColors.SkyAccent,
                            ),
                        )
                    }

                    if (settings.useCustomTunnel) {
                        OutlinedTextField(
                            value = tunnelUrlInput,
                            onValueChange = {
                                tunnelUrlInput = it
                                onSetCustomTunnelConfig(it, tunnelTokenInput)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Tunnel Base URL (e.g. https://xxx.trycloudflare.com)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SubcolabColors.GpuCyan,
                                unfocusedBorderColor = SubcolabColors.BorderSubtle,
                                focusedTextColor = SubcolabColors.TextPrimary,
                                unfocusedTextColor = SubcolabColors.TextPrimary,
                            ),
                        )
                        OutlinedTextField(
                            value = tunnelTokenInput,
                            onValueChange = {
                                tunnelTokenInput = it
                                onSetCustomTunnelConfig(tunnelUrlInput, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Bearer Token (Optional)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SubcolabColors.GpuCyan,
                                unfocusedBorderColor = SubcolabColors.BorderSubtle,
                                focusedTextColor = SubcolabColors.TextPrimary,
                                unfocusedTextColor = SubcolabColors.TextPrimary,
                            ),
                        )
                    }
                }
            }

            // 4b. Auto-Open Player Over Other Apps (Android 10+ BAL exemption)
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    if (canDrawOverlays) SubcolabColors.SuccessGreen.copy(alpha = 0.5f)
                    else SubcolabColors.SubtitleYellow.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Auto-Open Player Over Other Apps", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (canDrawOverlays) {
                            "Enabled — when you share a link and tap Watch Now, SUBCOLAB will automatically pop open over Dailymotion, Bilibili, browsers, or YouTube as soon as initial subtitles are ready."
                        } else {
                            "Allow 'Display over other apps' so Watch Now can automatically open the video player over Dailymotion, Bilibili, browsers, or YouTube when initial subtitles arrive."
                        },
                        color = if (canDrawOverlays) SubcolabColors.SuccessGreen else SubcolabColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                    context.startActivity(intent)
                                }
                            },
                            border = BorderStroke(1.dp, SubcolabColors.SkyAccent.copy(alpha = 0.7f)),
                        ) {
                            Text(
                                text = if (canDrawOverlays) "Manage Overlay Permission" else "Enable Auto-Open Permission",
                                color = SubcolabColors.SkyTextStrong,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // 5. Last Crash & Debug Logs
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    if (!lastCrashText.isNullOrBlank()) SubcolabColors.DangerRed.copy(alpha = 0.6f)
                    else SubcolabColors.BorderSubtle,
                ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = if (!lastCrashText.isNullOrBlank()) SubcolabColors.DangerRed else SubcolabColors.GpuCyan,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Last Crash / Debug Logs", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = if (!lastCrashText.isNullOrBlank()) {
                            "A crash log was captured in filesDir/last_crash.txt. Tap below to inspect or copy all logs."
                        } else {
                            "Inspect live Colab, yt-dlp, ForegroundService, and libmpv logs or copy a full report."
                        },
                        color = if (!lastCrashText.isNullOrBlank()) SubcolabColors.DangerRed else SubcolabColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenDebugLogs,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SubcolabColors.SurfaceElevated,
                                contentColor = SubcolabColors.TextPrimary,
                            ),
                        ) {
                            Text("View Logs")
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(onCopyAllLogs())) },
                            border = BorderStroke(1.dp, SubcolabColors.SubtitleYellow.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = SubcolabColors.SubtitleYellow,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy All Logs", color = SubcolabColors.SubtitleYellow)
                        }
                    }
                }
            }

            // 6. About SUBCOLAB & Credits
            Card(
                colors = CardDefaults.cardColors(containerColor = SubcolabColors.CardSurface),
                border = BorderStroke(1.dp, SubcolabColors.WarmBorder),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SubcolabColors.SagePrimary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("About SUBCOLAB", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "SUBCOLAB v1.0",
                                color = SubcolabColors.SagePrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Text(
                        text = "Live AI video translation powered by Whisper large-v3 on Google Colab T4 & libmpv",
                        color = SubcolabColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )

                    HorizontalDivider(color = SubcolabColors.WarmBorder.copy(alpha = 0.7f))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Created by",
                            color = SubcolabColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "22-db-22",
                            color = SubcolabColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Surface(
                        color = SubcolabColors.SkyContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SubcolabColors.SkyAccent.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    uriHandler.openUri("https://github.com/22-db-22/SUBCOLAB")
                                }
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "GitHub Repository",
                                    color = SubcolabColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "github.com/22-db-22/SUBCOLAB",
                                    color = SubcolabColors.SkyTextStrong,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open GitHub Repository",
                                tint = SubcolabColors.SkyTextStrong,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    HorizontalDivider(color = SubcolabColors.WarmBorder.copy(alpha = 0.7f))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Credits & Open Source Licenses",
                            color = SubcolabColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "• Based on & inspired by OpenScribe Colab workflow by Faisal Saddique (https://github.com/faisal-saddique/openscribe, MIT License)\n" +
                                "• OpenAI Whisper & SYSTRAN faster-whisper (MIT)\n" +
                                "• google-colab-cli runtime protocol\n" +
                                "• libmpv embedded hardware video player (LGPL/GPL)\n" +
                                "• youtubedl-android — yt-dlp & FFmpeg (GPL/Unlicense)",
                            color = SubcolabColors.TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
