package com.openscribe.android

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openscribe.android.model.WhisperLanguages
import com.openscribe.android.ui.theme.OpenScribeColors
import com.openscribe.android.ui.theme.OpenScribeTheme
import com.openscribe.android.util.UrlUnwrapper

/**
 * Compact floating dialog shown directly on top of YouTube/browsers when sharing a link.
 * Lets the user pick the source language and immediately closes so background download
 * and translation proceed uninterrupted without switching apps.
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

        val app = application as OpenScribeApp
        val initialLang = app.coordinator.state.value.sourceLang.ifBlank { "auto" }

        setContent {
            OpenScribeTheme {
                ShareOverlayCard(
                    cleanUrl = cleanUrl,
                    initialLang = initialLang,
                    onCancel = { finish() },
                    onStart = { selectedLang ->
                        app.coordinator.startUrlJob(
                            rawUrl = cleanUrl,
                            sourceLang = selectedLang,
                            fromExternalShare = true,
                            reusePreviousCache = false,
                        )
                        finish()
                    },
                )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareOverlayCard(
    cleanUrl: String,
    initialLang: String,
    onCancel: () -> Unit,
    onStart: (String) -> Unit,
) {
    var selectedLang by remember { mutableStateOf(initialLang) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Surface(
        color = OpenScribeColors.SurfaceDark,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
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
                    tint = OpenScribeColors.SubtitleYellow,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "OpenScribe",
                        color = OpenScribeColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = "Download <=720p MP4 & English subtitles",
                        color = OpenScribeColors.TextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }

            Text(
                text = cleanUrl,
                color = OpenScribeColors.GpuCyan,
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
                    color = OpenScribeColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                TextButton(onClick = { showLanguagePicker = true }) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("All ~99", color = OpenScribeColors.GpuCyan, fontSize = 12.sp)
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
                        onClick = { showLanguagePicker = true },
                        label = { Text(selectedLang, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OpenScribeColors.GpuCyanDark,
                            selectedLabelColor = OpenScribeColors.GpuCyan,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                ) {
                    Text("Cancel", color = OpenScribeColors.TextPrimary, fontSize = 13.sp)
                }
                Button(
                    onClick = { onStart(selectedLang) },
                    modifier = Modifier.weight(1.4f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OpenScribeColors.SubtitleYellow,
                        contentColor = OpenScribeColors.Obsidian,
                    ),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Start", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    if (showLanguagePicker) {
        var searchQuery by remember { mutableStateOf("") }
        val filtered = remember(searchQuery) { WhisperLanguages.search(searchQuery) }

        Dialog(onDismissRequest = { showLanguagePicker = false }) {
            Surface(
                color = OpenScribeColors.SurfaceDark,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Whisper Source Language",
                        color = OpenScribeColors.TextPrimary,
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
                            focusedBorderColor = OpenScribeColors.GpuCyan,
                            unfocusedBorderColor = OpenScribeColors.BorderSubtle,
                            focusedTextColor = OpenScribeColors.TextPrimary,
                            unfocusedTextColor = OpenScribeColors.TextPrimary,
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
                                    color = OpenScribeColors.TextPrimary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = lang.code,
                                    color = OpenScribeColors.GpuCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            HorizontalDivider(color = OpenScribeColors.BorderSubtle.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}
