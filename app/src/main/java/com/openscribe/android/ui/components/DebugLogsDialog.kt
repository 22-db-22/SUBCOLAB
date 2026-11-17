package com.openscribe.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openscribe.android.ui.theme.OpenScribeColors

@Composable
fun DebugLogsDialog(
    lastCrashText: String?,
    debugLogs: List<String>,
    onCopyAllLogs: () -> String,
    onClearLastCrash: () -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = OpenScribeColors.SurfaceDark,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = if (lastCrashText != null) OpenScribeColors.DangerRed else OpenScribeColors.GpuCyan,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Last Crash & Debug Logs",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = OpenScribeColors.TextSecondary,
                        )
                    }
                }

                // Action Bar: Copy All Logs + Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val report = onCopyAllLogs()
                            clipboard.setText(AnnotatedString(report))
                            copied = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OpenScribeColors.SubtitleYellow,
                            contentColor = OpenScribeColors.Obsidian,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (copied) "Copied All Logs!" else "Copy All Logs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            onClearLogs()
                            copied = false
                        },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = OpenScribeColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear", color = OpenScribeColors.TextSecondary, fontSize = 12.sp)
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!lastCrashText.isNullOrBlank()) {
                        Surface(
                            color = OpenScribeColors.DangerRed.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, OpenScribeColors.DangerRed.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Last Uncaught Crash (filesDir/last_crash.txt)",
                                        color = OpenScribeColors.DangerRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                    TextButton(onClick = onClearLastCrash) {
                                        Text("Clear Crash", color = OpenScribeColors.DangerRed, fontSize = 11.sp)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        text = lastCrashText.trim(),
                                        color = OpenScribeColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = OpenScribeColors.BorderSubtle)

                    Text(
                        text = "Live Event Log (${debugLogs.size} entries)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OpenScribeColors.GpuCyan,
                    )

                    Surface(
                        color = OpenScribeColors.Obsidian,
                        border = BorderStroke(1.dp, OpenScribeColors.BorderSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = if (debugLogs.isEmpty()) {
                                    "No log events recorded yet."
                                } else {
                                    debugLogs.asReversed().joinToString("\n")
                                },
                                color = OpenScribeColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}
