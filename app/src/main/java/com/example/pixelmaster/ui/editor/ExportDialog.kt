package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 导出对话框：选择 JPEG / PNG 与压缩质量，保存到系统相册。
 */
@Composable
fun ExportDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onExport: (asPng: Boolean, quality: Int) -> Unit,
) {
    var asPng by remember { mutableStateOf(false) }
    var quality by remember { mutableFloatStateOf(90f) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(text = "导出图片") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !asPng,
                        onClick = { asPng = false },
                        label = { Text("JPEG") },
                        enabled = !saving,
                    )
                    FilterChip(
                        selected = asPng,
                        onClick = { asPng = true },
                        label = { Text("PNG") },
                        enabled = !saving,
                    )
                }
                if (asPng) {
                    Text(
                        text = "无损画质，文件较大",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Text(
                        text = "质量：${quality.toInt()}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Slider(
                        value = quality,
                        onValueChange = { quality = it },
                        valueRange = 60f..100f,
                        steps = 7,
                        enabled = !saving,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(asPng, quality.toInt()) }, enabled = !saving) {
                Text(text = if (saving) "导出中…" else "保存到相册")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
        },
    )
}
