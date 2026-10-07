package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.HslAdjustments
import com.example.pixelmaster.data.model.HslBands
import kotlin.math.roundToInt

/**
 * HSL 分离调色面板：8 个色相分区，各自调整色相 / 饱和度 / 明度。
 * 分区圆点右下的小白点表示该分区已有调整。
 */
@Composable
fun HslPanel(
    hsl: HslAdjustments,
    activeBand: HslBands,
    onBandChange: (HslBands) -> Unit,
    onLiveChange: (HslAdjustments) -> Unit,
    onCommit: () -> Unit,
    onResetBand: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                HslBands.entries.forEach { kind ->
                    val band = hsl.band(kind)
                    val selected = kind == activeBand
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(kind.colorHex))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                                },
                                shape = CircleShape,
                            )
                            .clickable { onBandChange(kind) },
                    ) {
                        if (!band.isDefault) {
                            Box(
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onResetBand) { Text("重置") }
        }

        val band = hsl.band(activeBand)
        LabeledSlider(
            label = "色相",
            value = band.hueShift,
            range = -100f..100f,
            onChange = { v -> onLiveChange(hsl.with(activeBand, band.copy(hueShift = v))) },
            onCommit = onCommit,
        )
        LabeledSlider(
            label = "饱和度",
            value = band.saturation,
            range = -100f..100f,
            onChange = { v -> onLiveChange(hsl.with(activeBand, band.copy(saturation = v))) },
            onCommit = onCommit,
        )
        LabeledSlider(
            label = "明度",
            value = band.luminance,
            range = -100f..100f,
            onChange = { v -> onLiveChange(hsl.with(activeBand, band.copy(luminance = v))) },
            onCommit = onCommit,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
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
            text = formatSigned(value),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp),
        )
    }
}

private fun formatSigned(v: Float): String {
    val i = v.roundToInt()
    return if (i == 0) "0" else if (i > 0) "+$i" else "$i"
}
