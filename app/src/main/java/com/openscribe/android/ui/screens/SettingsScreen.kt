package com.openscribe.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openscribe.android.colab.AuthUiState
import com.openscribe.android.model.AppSettings
import com.openscribe.android.ui.theme.OpenScribeColors

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
    onOpenDebugLogs: () -> Unit = {},
    onCopyAllLogs: () -> String = { "" },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
        containerColor = OpenScribeColors.Obsidian,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OpenScribeColors.Obsidian,
                    titleContentColor = OpenScribeColors.TextPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = OpenScribeColors.TextPrimary,
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
            // 1. Google Colab OAuth2 Account
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = OpenScribeColors.GpuCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Colab Account", style = MaterialTheme.typography.titleMedium)
                    }

                    if (authState.isSignedIn) {
                        Text(
                            text = "Signed in as ${authState.userEmail ?: "Google Colab user"}",
                            color = OpenScribeColors.SuccessGreen,
                            fontSize = 13.sp,
                        )
                        OutlinedButton(
                            onClick = onSignOut,
                            border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.6f)),
                        ) {
                            Text("Sign Out", color = OpenScribeColors.DangerRed)
                        }
                    } else {
                        Text(
                            text = "One-time Google Colab OAuth2 sign-in (matches google-colab-cli's sdk.cloud.google.com flow — works out of the box with zero redirect errors).",
                            color = OpenScribeColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                        Button(
                            onClick = onLaunchCopyPasteAuth,
                            enabled = !authState.isSigningIn,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OpenScribeColors.GpuCyan,
                                contentColor = OpenScribeColors.Obsidian,
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
                                border = BorderStroke(1.dp, OpenScribeColors.SubtitleYellow.copy(alpha = 0.7f)),
                            ) {
                                Text(
                                    text = "Sign in via Custom OAuth Loopback",
                                    color = OpenScribeColors.SubtitleYellow,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                )
                            }
                        }

                        TextButton(onClick = { showCustomOauth = !showCustomOauth }) {
                            Text(
                                text = if (showCustomOauth) "Hide Optional Custom OAuth Client" else "Optional: Use Custom OAuth Client ID",
                                color = OpenScribeColors.TextSecondary,
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
                                focusedBorderColor = OpenScribeColors.GpuCyan,
                                unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                                focusedTextColor = OpenScribeColors.TextPrimary,
                                unfocusedTextColor = OpenScribeColors.TextPrimary,
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
                                focusedBorderColor = OpenScribeColors.GpuCyan,
                                unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                                focusedTextColor = OpenScribeColors.TextPrimary,
                                unfocusedTextColor = OpenScribeColors.TextPrimary,
                            ),
                        )
                    }

                    if (authState.awaitingManualCode) {
                        Text(
                            text = "Approve in the browser tab, copy the authorization code shown on Google's page, and paste it below:",
                            color = OpenScribeColors.SubtitleYellow,
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
                                        tint = OpenScribeColors.SubtitleYellow,
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = OpenScribeColors.SubtitleYellow,
                                unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                                focusedTextColor = OpenScribeColors.TextPrimary,
                                unfocusedTextColor = OpenScribeColors.TextPrimary,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onCompleteCopyPasteAuth(manualCodeInput) },
                                enabled = !authState.isSigningIn && manualCodeInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OpenScribeColors.SubtitleYellow,
                                    contentColor = OpenScribeColors.Obsidian,
                                ),
                            ) {
                                Text("Verify Code", fontWeight = FontWeight.Bold)
                            }
                            TextButton(onClick = onDismissCopyPasteAuth) {
                                Text("Cancel", color = OpenScribeColors.TextSecondary)
                            }
                        }
                    }

                    if (!authState.error.isNullOrBlank()) {
                        Text(authState.error, color = OpenScribeColors.DangerRed, fontSize = 12.sp)
                    }
                }
            }

            // 2. YouTube cookies.txt Picker (No All Files Access needed!)
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cookie, contentDescription = null, tint = OpenScribeColors.SubtitleYellow)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("YouTube cookies.txt (Optional)", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = if (settings.hasCookiesFile) {
                            "cookies.txt loaded in private app storage. Uploaded automatically to /content/cookies.txt on Colab."
                        } else {
                            "Only needed if YouTube requires sign-in for age-restricted or heavily rate-limited videos."
                        },
                        color = if (settings.hasCookiesFile) OpenScribeColors.SuccessGreen else OpenScribeColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    cookiesStatusMsg?.let {
                        Text(it, color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { cookiesPicker.launch(arrayOf("text/plain", "*/*")) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OpenScribeColors.SurfaceElevated,
                                contentColor = OpenScribeColors.TextPrimary,
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
                                border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.5f)),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = OpenScribeColors.DangerRed)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Remove", color = OpenScribeColors.DangerRed)
                            }
                        }
                    }
                }
            }

            // 3. Whisper & Audio Engine Settings
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = OpenScribeColors.GpuCyan)
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
                                color = OpenScribeColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = settings.cleanAudio,
                            onCheckedChange = onSetCleanAudio,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OpenScribeColors.Obsidian,
                                checkedTrackColor = OpenScribeColors.SubtitleYellow,
                            ),
                        )
                    }

                    Column {
                        Text(
                            text = "Subtitle Font Size: ${settings.subtitleFontSize}",
                            fontSize = 13.sp,
                            color = OpenScribeColors.TextPrimary,
                        )
                        Slider(
                            value = settings.subtitleFontSize.toFloat(),
                            onValueChange = { onSetSubtitleFontSize(it.toInt()) },
                            valueRange = 28f..72f,
                            colors = SliderDefaults.colors(
                                thumbColor = OpenScribeColors.SubtitleYellow,
                                activeTrackColor = OpenScribeColors.SubtitleYellow,
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
                            focusedBorderColor = OpenScribeColors.GpuCyan,
                            unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                            focusedTextColor = OpenScribeColors.TextPrimary,
                            unfocusedTextColor = OpenScribeColors.TextPrimary,
                        ),
                    )
                }
            }

            // 4. Optional Custom Colab Tunnel URL Fallback
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
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
                                color = OpenScribeColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = settings.useCustomTunnel,
                            onCheckedChange = onSetUseCustomTunnel,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OpenScribeColors.Obsidian,
                                checkedTrackColor = OpenScribeColors.GpuCyan,
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
                                focusedBorderColor = OpenScribeColors.GpuCyan,
                                unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                                focusedTextColor = OpenScribeColors.TextPrimary,
                                unfocusedTextColor = OpenScribeColors.TextPrimary,
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
                                focusedBorderColor = OpenScribeColors.GpuCyan,
                                unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                                focusedTextColor = OpenScribeColors.TextPrimary,
                                unfocusedTextColor = OpenScribeColors.TextPrimary,
                            ),
                        )
                    }
                }
            }

            // 4b. Auto-Open Player Over Other Apps (Android 10+ BAL exemption)
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    if (canDrawOverlays) OpenScribeColors.SuccessGreen.copy(alpha = 0.5f)
                    else OpenScribeColors.SubtitleYellow.copy(alpha = 0.5f),
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
                            "Enabled — when you share a link and tap Watch Now, OpenScribe will automatically pop open over YouTube or any other app as soon as initial subtitles are ready."
                        } else {
                            "Allow 'Display over other apps' so Watch Now can automatically open the video player over YouTube/browsers when initial subtitles arrive."
                        },
                        color = if (canDrawOverlays) OpenScribeColors.SuccessGreen else OpenScribeColors.TextSecondary,
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
                            border = BorderStroke(1.dp, OpenScribeColors.GpuCyan.copy(alpha = 0.7f)),
                        ) {
                            Text(
                                text = if (canDrawOverlays) "Manage Overlay Permission" else "Enable Auto-Open Permission",
                                color = OpenScribeColors.GpuCyan,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // 5. Last Crash & Debug Logs
            Card(
                colors = CardDefaults.cardColors(containerColor = OpenScribeColors.SurfaceDark),
                border = BorderStroke(
                    1.dp,
                    if (!lastCrashText.isNullOrBlank()) OpenScribeColors.DangerRed.copy(alpha = 0.6f)
                    else OpenScribeColors.BorderSubtle,
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
                            tint = if (!lastCrashText.isNullOrBlank()) OpenScribeColors.DangerRed else OpenScribeColors.GpuCyan,
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
                        color = if (!lastCrashText.isNullOrBlank()) OpenScribeColors.DangerRed else OpenScribeColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onOpenDebugLogs,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OpenScribeColors.SurfaceElevated,
                                contentColor = OpenScribeColors.TextPrimary,
                            ),
                        ) {
                            Text("View Logs")
                        }
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(onCopyAllLogs())) },
                            border = BorderStroke(1.dp, OpenScribeColors.SubtitleYellow.copy(alpha = 0.7f)),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = OpenScribeColors.SubtitleYellow,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy All Logs", color = OpenScribeColors.SubtitleYellow)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
