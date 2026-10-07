package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.PortraitParam
import com.example.pixelmaster.data.model.PortraitParams

/**
 * 人像精修面板：磨皮/瘦脸/亮眼/白牙/匀肤滑块 + 去瑕疵点选 + 手动液化笔刷。
 * 无人脸时人像滑块不生效并给出提示。
 */
@Composable
fun PortraitPanel(
    portrait: PortraitParams,
    faceCount: Int,
    detecting: Boolean,
    blemishOn: Boolean,
    onBlemishChange: (Boolean) -> Unit,
    onLiveChange: (PortraitParams) -> Unit,
    onCommit: () -> Unit,
    liquifyOn: Boolean,
    onLiquifyChange: (Boolean) -> Unit,
    brushPercent: Float,
    onBrushChange: (Float) -> Unit,
    liquifyCount: Int,
    onUndoLiquify: () -> Unit,
    onClearLiquify: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            detecting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在检测人脸…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            faceCount == 0 -> Text(
                text = "未检测到人脸，人像滑块对无人脸照片不生效（液化/去瑕疵仍可用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Text(
                text = "已识别 $faceCount 张人脸",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        PortraitParam.entries.forEach { param ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = param.label,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = param.read(portrait),
                    onValueChange = { value -> onLiveChange(param.write(portrait, value)) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                    onValueChangeFinished = onCommit,
                    enabled = faceCount > 0,
                )
                Text(
                    text = param.displayValue(portrait),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(30.dp),
                )
            }
        }

        Spacer(Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = blemishOn,
                onClick = { onBlemishChange(!blemishOn) },
                label = { Text("去瑕疵") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = liquifyOn,
                onClick = { onLiquifyChange(!liquifyOn) },
                label = { Text("手动液化") },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    blemishOn -> "点击图中瑕疵处修复"
                    liquifyOn -> "在图片上拖动推挤变形"
                    else -> "开启后对图片操作"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (liquifyOn) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "笔刷",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(44.dp),
                )
                Slider(
                    value = brushPercent,
                    onValueChange = onBrushChange,
                    valueRange = 0.03f..0.12f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(brushPercent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(36.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onUndoLiquify, enabled = liquifyCount > 0) { Text("撤销一笔") }
                TextButton(onClick = onClearLiquify, enabled = liquifyCount > 0) { Text("清空液化") }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "已 $liquifyCount 划",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
