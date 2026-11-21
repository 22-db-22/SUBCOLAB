package com.subcolab.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.subcolab.android.model.SubtitleRetranslateOptions
import com.subcolab.android.model.WhisperLanguages
import com.subcolab.android.model.WhisperModels
import com.subcolab.android.model.WorkflowMode
import com.subcolab.android.ui.theme.SubcolabColors
import com.subcolab.android.ui.theme.SubcolabTheme
import com.subcolab.android.util.UrlUnwrapper
import kotlinx.coroutines.delay

/**
 * Single-frame compact dialog shown over Dailymotion, Bilibili, browsers, or YouTube when sharing a link.
 * Fits App Name -> Language (Auto/Chinese/Korean/Jap in 1 line) -> Whisper Model + toggles -> Download/Watch Now without scrolling.
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
            var queuedNoticePosition by remember { mutableStateOf<Int?>(null) }

            SubcolabTheme(themeMode = settings.themeMode) {
                val qPos = queuedNoticePosition
                if (qPos != null) {
                    LaunchedEffect(qPos) {
                        delay(2_200L)
                        finish()
                    }
                    QueuedJobNotice(
                        queuePosition = qPos,
                        onDismiss = { finish() },
                    )
                } else if (showWatchNowNotice) {
                    LaunchedEffect(Unit) {
                        delay(2_000L)
                        finish()
                    }
                    WatchNowStartedNotice(onDismiss = { finish() })
                } else {
                    ShareOverlayCard(
                        initialLang = initialLang,
                        defaultCleanAudio = settings.cleanAudio,
                        defaultModelId = WhisperModels.normalize(settings.modelId),
                        onCancel = { finish() },
                        onStart = { selectedOptions, workflowMode ->
                            val queuePos = app.coordinator.startUrlJob(
                                rawUrl = cleanUrl,
                                sourceLang = selectedOptions.sourceLang,
                                workflowMode = workflowMode,
                                fromExternalShare = true,
                                reusePreviousCache = false,
                                subtitleOptions = selectedOptions,
                            )
                            when {
                                queuePos > 0 -> queuedNoticePosition = queuePos
                                workflowMode == WorkflowMode.WATCH_NOW -> showWatchNowNotice = true
                                else -> finish()
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
private fun QueuedJobNotice(
    queuePosition: Int,
    onDismiss: () -> Unit,
) {
    Surface(
        color = SubcolabColors.SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, SubcolabColors.GpuCyan.copy(alpha = 0.7f)),
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
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = SubcolabColors.GpuCyan,
                modifier = Modifier.size(28.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Added as Queued No. $queuePosition",
                    color = SubcolabColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Text(
                    text = "Starts automatically after the current job finishes. A Watch Now / Cancel Job notification will appear when ready.",
                    color = SubcolabColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
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

private data class QuickLangChip(
    val code: String,
    val label: String,
    val weight: Float,
)

private val SHARE_QUICK_CHIPS = listOf(
    QuickLangChip("auto", "Auto", 0.88f),
    QuickLangChip("zh", "Chinese", 1.15f),
    QuickLangChip("ko", "Korean", 1.08f),
    QuickLangChip("ja", "Jap", 0.78f),
)

@Composable
private fun ShareOverlayCard(
    initialLang: String,
    defaultCleanAudio: Boolean,
    defaultModelId: String,
    onCancel: () -> Unit,
    onStart: (SubtitleRetranslateOptions, WorkflowMode) -> Unit,
) {
    var selectedLang by remember { mutableStateOf(initialLang) }
    var modelId by remember { mutableStateOf(WhisperModels.normalize(defaultModelId)) }
    var cleanAudio by remember { mutableStateOf(defaultCleanAudio) }
    var vocalBoost by remember { mutableStateOf(false) }
    var highPrecisionBeam by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    fun buildOptions(): SubtitleRetranslateOptions = SubtitleRetranslateOptions(
        sourceLang = selectedLang.ifBlank { "auto" },
        cleanAudio = cleanAudio,
        vocalBoost = vocalBoost,
        includeOriginal = false,
        modelId = WhisperModels.normalize(modelId),
        beamSize = if (highPrecisionBeam) 8 else 5,
    )

    val isCustomLangSelected = SHARE_QUICK_CHIPS.none { it.code.equals(selectedLang, ignoreCase = true) }

    Surface(
        color = SubcolabColors.SurfaceDark,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SubcolabColors.BorderSubtle),
        modifier = Modifier
            .widthIn(min = 310.dp, max = 420.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // 1. App Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.ClosedCaption,
                    contentDescription = null,
                    tint = SubcolabColors.SubtitleYellow,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SUBCOLAB",
                    color = SubcolabColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text("Cancel", color = SubcolabColors.TextSecondary, fontSize = 12.sp)
                }
            }

            // 2. Language Header + Single-Row Chips (Auto, Chinese, Korean, Jap)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Language",
                    color = SubcolabColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isCustomLangSelected) {
                        Surface(
                            color = SubcolabColors.GpuCyanDark,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = selectedLang,
                                color = SubcolabColors.GpuCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .clickable { showLanguagePicker = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = SubcolabColors.SkyTextStrong,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("All ~99", color = SubcolabColors.SkyTextStrong, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SHARE_QUICK_CHIPS.forEach { chip ->
                    val selected = selectedLang.equals(chip.code, ignoreCase = true)
                    Surface(
                        onClick = { selectedLang = chip.code },
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) SubcolabColors.SubtitleYellowDark else SubcolabColors.SurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (selected) SubcolabColors.SubtitleYellow else SubcolabColors.BorderSubtle.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .weight(chip.weight)
                            .height(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                            Text(
                                text = chip.label,
                                color = if (selected) SubcolabColors.SubtitleYellow else SubcolabColors.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = SubcolabColors.BorderSubtle.copy(alpha = 0.4f))

            // 3. Whisper Model + 3 Subtitle Toggles (no scrolling needed, Include Original removed)
            Text(
                text = "Whisper Model",
                color = SubcolabColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WhisperModels.allowed.forEach { (id, label) ->
                    val selected = modelId == id
                    Surface(
                        onClick = { modelId = id },
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) SubcolabColors.GpuCyanDark else SubcolabColors.SurfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (selected) SubcolabColors.GpuCyan else SubcolabColors.BorderSubtle.copy(alpha = 0.6f),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                            Text(
                                text = label,
                                color = if (selected) SubcolabColors.GpuCyan else SubcolabColors.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

            ShareOptionSwitchRow(
                title = "Clean Audio",
                subtitle = "Noise reduction + loudness boost",
                checked = cleanAudio,
                activeTrackColor = SubcolabColors.GpuCyan,
                onCheckedChange = { cleanAudio = it },
            )

            ShareOptionSwitchRow(
                title = "Vocal Boost / Cut Music",
                subtitle = "Isolates voice frequencies in songs/BGM",
                checked = vocalBoost,
                activeTrackColor = SubcolabColors.GpuCyan,
                onCheckedChange = { vocalBoost = it },
            )

            ShareOptionSwitchRow(
                title = "High-Precision Beam Search",
                subtitle = "Beam width 8 + higher accuracy",
                checked = highPrecisionBeam,
                activeTrackColor = SubcolabColors.SubtitleYellow,
                onCheckedChange = { highPrecisionBeam = it },
            )

            HorizontalDivider(color = SubcolabColors.BorderSubtle.copy(alpha = 0.45f))

            // 4. Download / Watch Now
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onStart(buildOptions(), WorkflowMode.DOWNLOAD_AND_TRANSLATE) },
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, SubcolabColors.SkyAccent.copy(alpha = 0.8f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = SubcolabColors.SkyTextStrong,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
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
                    onClick = { onStart(buildOptions(), WorkflowMode.WATCH_NOW) },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SubcolabColors.SagePrimary,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
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

@Composable
private fun ShareOptionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    activeTrackColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = SubcolabColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = SubcolabColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.84f),
            colors = SwitchDefaults.colors(checkedTrackColor = activeTrackColor),
        )
    }
}
