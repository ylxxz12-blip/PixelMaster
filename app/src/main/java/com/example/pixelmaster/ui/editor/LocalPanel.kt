package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.LocalAdjust
import kotlin.math.roundToInt

/**
 * 径向局部调色面板：启用后在图片上拖动调整作用中心，
 * 蒙版内（或反选外部）单独应用曝光/饱和度/色温。
 */
@Composable
fun LocalPanel(
    local: LocalAdjust,
    onLiveChange: (LocalAdjust) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = local.enabled,
                onClick = {
                    onLiveChange(local.copy(enabled = !local.enabled))
                    onCommit()
                },
                label = { Text("启用局部调色") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = local.invert,
                onClick = {
                    onLiveChange(local.copy(invert = !local.invert))
                    onCommit()
                },
                enabled = local.enabled,
                label = { Text("作用于外部") },
            )
        }
        if (!local.enabled) {
            Text(
                text = "启用后：在图片上拖动可移动作用中心",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            return
        }

        LocalSlider(
            label = "曝光", value = local.exposure, range = -100f..100f,
            onChange = { onLiveChange(local.copy(exposure = it)) }, onCommit = onCommit,
        )
        LocalSlider(
            label = "饱和度", value = local.saturation, range = -100f..100f,
            onChange = { onLiveChange(local.copy(saturation = it)) }, onCommit = onCommit,
        )
        LocalSlider(
            label = "色温", value = local.temperature, range = -100f..100f,
            onChange = { onLiveChange(local.copy(temperature = it)) }, onCommit = onCommit,
        )
        LocalSlider(
            label = "大小", value = local.radius, range = 0.1f..0.8f,
            onChange = { onLiveChange(local.copy(radius = it)) }, onCommit = onCommit,
            display = { "${(it * 100).roundToInt()}%" },
        )
        LocalSlider(
            label = "羽化", value = local.feather, range = 0.05f..0.9f,
            onChange = { onLiveChange(local.copy(feather = it)) }, onCommit = onCommit,
            display = { "${(it * 100).roundToInt()}%" },
        )
        Text(
            text = "在图片上拖动可移动作用中心",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LocalSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    display: (Float) -> String = {
        val i = it.roundToInt()
        if (i == 0) "0" else if (i > 0) "+$i" else "$i"
    },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            onValueChangeFinished = onCommit,
        )
        Text(
            text = display(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
    }
}
