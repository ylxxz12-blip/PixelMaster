package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.AdjustParam
import com.example.pixelmaster.data.model.Adjustments

/**
 * 基础调色面板：参数选择 Chip 行 + 当前参数滑块。
 * 拖动中通过 onLiveChange 实时预览，松手后 onCommit 进入撤销历史。
 */
@Composable
fun AdjustPanel(
    adjustments: Adjustments,
    activeParam: AdjustParam,
    onActiveParamChange: (AdjustParam) -> Unit,
    onLiveChange: (Adjustments) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(AdjustParam.entries) { param ->
                FilterChip(
                    selected = param == activeParam,
                    onClick = { onActiveParamChange(param) },
                    label = { Text(param.label) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = activeParam.label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                text = activeParam.displayValue(adjustments),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = activeParam.read(adjustments).coerceIn(activeParam.range.start, activeParam.range.endInclusive),
            onValueChange = { value -> onLiveChange(activeParam.write(adjustments, value)) },
            valueRange = activeParam.range,
            onValueChangeFinished = onCommit,
        )
    }
}
