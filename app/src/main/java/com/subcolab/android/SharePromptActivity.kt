package com.subcolab.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.subcolab.android.model.WhisperLanguages
import com.subcolab.android.model.WorkflowMode
import com.subcolab.android.ui.theme.SubcolabColors
import com.subcolab.android.ui.theme.SubcolabTheme
import com.subcolab.android.util.UrlUnwrapper
import kotlinx.coroutines.delay

/**
 * Compact floating dialog shown directly on top of Dailymotion, Bilibili, browsers, or YouTube when sharing a link.
 * Pre-warms Colab T4 GPU while the user picks the source language and mode:
 * - Watch Now: smooth live video stream + adaptive chunked audio subtitles, auto-opens player
 * - Download: full <=720p MP4 + subtitles saved quietly in background
 */
class SharePromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)

        val rawInput = extractSharedText(intent)
        val cleanUrl = UrlUnwrapper.extractAndUnwrap(rawInput)
        if (!UrlUnwrapper.isValidVideoUrl(cleanUrl)) {
            finish()
            return
        }

        val app = application as SubcolabApp
        app.prewarmT4IfSignedIn("share_overlay")
        val initialLang = app.coordinator.state.value.sourceLang.ifBlank { "auto" }

        setContent {
            val settings by app.settingsRepository.settings.collectAsState()
            var showWatchNowNotice by remember { mutableStateOf(false) }

            SubcolabTheme(themeMode = settings.themeMode) {
                if (showWatchNowNotice) {
                    LaunchedEffect(Unit) {
                        delay(3_500L)
                        finish()
                    }
                    WatchNowStartedNotice(onDismiss = { finish() })
                } else {
                    ShareOverlayCard(
                        cleanUrl = cleanUrl,
                        initialLang = initialLang,
                        onCancel = { finish() },
                        onStart = { selectedLang, workflowMode ->
                            app.coordinator.startUrlJob(
                                rawUrl = cleanUrl,
                                sourceLang = selectedLang,
                                workflowMode = workflowMode,
                                fromExternalShare = true,
                                reusePreviousCache = false,
                            )
                            if (workflowMode == WorkflowMode.WATCH_NOW) {
                                showWatchNowNotice = true
                            } else {
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }

    private fun extractSharedText(intent: Intent?): String {
        if (intent == null) return ""
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() else ""
            }
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
    }
}

@Composable
private fun WatchNowStartedNotice(
    onDismiss: () -> Unit,
) {
    Surface(
        color = SubcolabColors.SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, SubcolabColors.SagePrimary.copy(alpha = 0.65f)),
        modifier = Modifier
            .widthIn(min = 300.dp, max = 400.dp)
            .fillMaxWidth()
            .clickable { onDismiss() },
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = SubcolabColors.SagePrimary,
                strokeWidth = 3.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "SUBCOLAB is translating...",
                    color = SubcolabColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Please wait — the video will open automatically once initial subtitles are downloaded.",
                    color = SubcolabColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareOverlayCard(
    cleanUrl: String,
    initialLang: String,
    onCancel: () -> Unit,
    onStart: (String, WorkflowMode) -> Unit,
) {
    var selectedLang by remember { mutableStateOf(initialLang) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Surface(
        color = SubcolabColors.SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
        modifier = Modifier
            .widthIn(min = 310.dp, max = 420.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.ClosedCaption,
                    contentDescription = null,
                    tint = SubcolabColors.SubtitleYellow,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SUBCOLAB",
                        color = SubcolabColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Watch Now (live stream) or Download full MP4",
                        color = SubcolabColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = SubcolabColors.TextSecondary, fontSize = 12.sp)
                }
            }

            Text(
                text = cleanUrl,
                color = SubcolabColors.SkyTextStrong,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Video Language",
                    color = SubcolabColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                TextButton(onClick = { showLanguagePicker = true }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = SubcolabColors.SkyTextStrong,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("All ~99", color = SubcolabColors.SkyTextStrong, fontSize = 12.sp)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WhisperLanguages.quickChips.forEach { lang ->
                    val selected = selectedLang.equals(lang.code, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { selectedLang = lang.code },
                        label = {
                            Text(
                                text = if (lang.code == "auto") "Auto" else lang.name.substringBefore(" ("),
                                fontSize = 12.sp,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SubcolabColors.SubtitleYellowDark,
                            selectedLabelColor = SubcolabColors.SubtitleYellow,
                            containerColor = SubcolabColors.SurfaceElevated,
                            labelColor = SubcolabColors.TextPrimary,
                        ),
                    )
                }
                if (WhisperLanguages.quickChips.none { it.code.equals(selectedLang, ignoreCase = true) }) {
                    FilterChip(
                        selected = true,
                        onClick = { showLanguagePicker = true },
                        label = { Text(selectedLang, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SubcolabColors.GpuCyanDark,
                            selectedLabelColor = SubcolabColors.GpuCyan,
                        ),
                    )
                }
            }

            HorizontalDivider(color = SubcolabColors.BorderSubtle.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onStart(selectedLang, WorkflowMode.DOWNLOAD_AND_TRANSLATE) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SubcolabColors.SkyAccent.copy(alpha = 0.8f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = SubcolabColors.SkyTextStrong,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Download",
                        color = SubcolabColors.SkyTextStrong,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { onStart(selectedLang, WorkflowMode.WATCH_NOW) },
                    modifier = Modifier
                        .weight(1.2f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SubcolabColors.SagePrimary,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Watch Now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text = "Watch Now streams smoothly & auto-opens once initial subs arrive. Download saves full MP4 quietly in background.",
                color = SubcolabColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }

    if (showLanguagePicker) {
        var searchQuery by remember { mutableStateOf("") }
        val filtered = remember(searchQuery) { WhisperLanguages.search(searchQuery) }

        Dialog(onDismissRequest = { showLanguagePicker = false }) {
            Surface(
                color = SubcolabColors.SurfaceDark,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Whisper Source Language",
                        color = SubcolabColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Type language or ISO code") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SubcolabColors.GpuCyan,
                            unfocusedBorderColor = SubcolabColors.BorderSubtle,
                            focusedTextColor = SubcolabColors.TextPrimary,
                            unfocusedTextColor = SubcolabColors.TextPrimary,
                        ),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(filtered) { lang ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedLang = lang.code
                                        showLanguagePicker = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = lang.name,
                                    color = SubcolabColors.TextPrimary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = lang.code,
                                    color = SubcolabColors.GpuCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            HorizontalDivider(color = SubcolabColors.BorderSubtle.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}
