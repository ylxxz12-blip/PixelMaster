package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.CurveChannel
import com.example.pixelmaster.data.model.CurvePoint
import com.example.pixelmaster.data.model.Curves
import com.example.pixelmaster.data.model.monotoneLut

/**
 * 曲线面板：通道选择 + 可拖拽曲线编辑器（带亮度直方图）。
 * 点按空白处添加控制点，拖动控制点调整；松手提交撤销历史。
 */
@Composable
fun CurvePanel(
    curves: Curves,
    activeChannel: CurveChannel,
    onChannelChange: (CurveChannel) -> Unit,
    onLiveChange: (Curves) -> Unit,
    onCommit: () -> Unit,
    histogram: FloatArray?,
    onResetChannel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurveChannel.entries.forEach { channel ->
                FilterChip(
                    selected = channel == activeChannel,
                    onClick = { onChannelChange(channel) },
                    label = { Text(channel.label) },
                )
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onResetChannel) { Text("重置") }
        }
        CurveEditor(
            points = curves.pointsOf(activeChannel),
            onChange = { onLiveChange(curves.withPoints(activeChannel, it)) },
            onCommit = onCommit,
            histogram = histogram,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(vertical = 4.dp),
        )
        Text(
            text = "点按空白添加控制点，拖动调整，长按删除；首尾端点仅可上下移动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CurveEditor(
    points: List<CurvePoint>,
    onChange: (List<CurvePoint>) -> Unit,
    onCommit: () -> Unit,
    histogram: FloatArray?,
    modifier: Modifier = Modifier,
) {
    // 稳定的 state 宿主，保证手势 lambda 里读到的始终是最新控制点
    val ptsState = remember { mutableStateOf(points) }
    LaunchedEffect(points) { ptsState.value = points }
    var draggingIndex by remember { mutableIntStateOf(-1) }

    val accent = MaterialTheme.colorScheme.primary
    val gridColor = Color.White.copy(alpha = 0.10f)
    val diagColor = Color.White.copy(alpha = 0.22f)
    val histColor = Color.White.copy(alpha = 0.10f)
    val lut = remember(points) { monotoneLut(points) }

    Canvas(
        modifier = modifier
            // 长按删除中间控制点（端点不可删）
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { pos ->
                        val pad = 12.dp.toPx()
                        val innerW = size.width - pad * 2
                        val innerH = size.height - pad * 2
                        val hitR = 24.dp.toPx()
                        val current = ptsState.value
                        var bestIdx = -1
                        var bestDist = Float.MAX_VALUE
                        current.forEachIndexed { i, p ->
                            val px = pad + p.x * innerW
                            val py = size.height - pad - p.y * innerH
                            val d = (pos.x - px) * (pos.x - px) + (pos.y - py) * (pos.y - py)
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = i
                            }
                        }
                        if (bestIdx > 0 && bestIdx < current.lastIndex && bestDist <= hitR * hitR) {
                            val newPts = current.toMutableList().also { it.removeAt(bestIdx) }
                            onChange(newPts)
                            ptsState.value = newPts
                            onCommit()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { pos ->
                    val pad = 12.dp.toPx()
                    val innerW = size.width - pad * 2
                    val innerH = size.height - pad * 2
                    val hitR = 22.dp.toPx()
                    val current = ptsState.value
                    var bestIdx = -1
                    var bestDist = Float.MAX_VALUE
                    current.forEachIndexed { i, p ->
                        val px = pad + p.x * innerW
                        val py = size.height - pad - p.y * innerH
                        val d = (pos.x - px) * (pos.x - px) + (pos.y - py) * (pos.y - py)
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = i
                        }
                    }
                    draggingIndex = if (bestIdx >= 0 && bestDist <= hitR * hitR) {
                        bestIdx
                    } else {
                        // 空白处：添加新控制点并立即进入拖动
                        val nx = ((pos.x - pad) / innerW).coerceIn(0.02f, 0.98f)
                        val ny = ((size.height - pad - pos.y) / innerH).coerceIn(0f, 1f)
                        val newPoint = CurvePoint(nx, ny)
                        val newPts = (current + newPoint).sortedBy { it.x }
                        onChange(newPts)
                        ptsState.value = newPts
                        newPts.indexOfFirst { it == newPoint }
                    }
                },
                onDrag = { change, _ ->
                    change.consume()
                    val idx = draggingIndex
                    if (idx >= 0) {
                        val pad = 12.dp.toPx()
                        val innerW = size.width - pad * 2
                        val innerH = size.height - pad * 2
                        val nx = ((change.position.x - pad) / innerW).coerceIn(0f, 1f)
                        val ny = ((size.height - pad - change.position.y) / innerH).coerceIn(0f, 1f)
                        onChange(movePoint(ptsState.value, idx, nx, ny).also { ptsState.value = it })
                    }
                },
                onDragEnd = {
                    draggingIndex = -1
                    onCommit()
                },
                onDragCancel = {
                    draggingIndex = -1
                    onCommit()
                },
            )
        }
    ) {
        val pad = 12.dp.toPx()
        val innerW = size.width - pad * 2
        val innerH = size.height - pad * 2
        if (innerW <= 0f || innerH <= 0f) return@Canvas

        // 直方图
        histogram?.let { hist ->
            val path = Path()
            path.moveTo(pad, size.height - pad)
            for (i in 0..255) {
                path.lineTo(
                    pad + i / 255f * innerW,
                    size.height - pad - hist[i] * innerH,
                )
            }
            path.lineTo(size.width - pad, size.height - pad)
            path.close()
            drawPath(path, histColor)
        }

        // 网格与对角线
        for (i in 1..3) {
            val x = pad + innerW * i / 4f
            drawLine(gridColor, Offset(x, pad), Offset(x, size.height - pad), 1f)
            val y = pad + innerH * i / 4f
            drawLine(gridColor, Offset(pad, y), Offset(size.width - pad, y), 1f)
        }
        drawLine(diagColor, Offset(pad, size.height - pad), Offset(size.width - pad, pad), 1.5f)

        // 曲线
        val curve = Path()
        curve.moveTo(pad, size.height - pad - lut[0] * innerH)
        for (i in 1..255) {
            curve.lineTo(pad + i / 255f * innerW, size.height - pad - lut[i] * innerH)
        }
        drawPath(curve, accent, 2.dp.toPx())

        // 控制点
        ptsState.value.forEachIndexed { i, p ->
            val px = pad + p.x * innerW
            val py = size.height - pad - p.y * innerH
            drawCircle(Color.White, 6.dp.toPx() / 2 + 2.dp.toPx(), Offset(px, py))
            drawCircle(if (i == draggingIndex) accent else Color.White, 6.dp.toPx() / 2, Offset(px, py))
        }
    }
}

/** 移动控制点：首尾端点锁定 x，中间点限制在相邻点之间（保持最小间距）。 */
private fun movePoint(points: List<CurvePoint>, index: Int, nx: Float, ny: Float): List<CurvePoint> {
    if (index < 0 || index >= points.size) return points
    val minGap = 1f / 64f
    val isStart = index == 0
    val isEnd = index == points.lastIndex
    val clampedX = when {
        isStart -> 0f
        isEnd -> 1f
        else -> nx.coerceIn(points[index - 1].x + minGap, points[index + 1].x - minGap)
    }
    val clampedY = ny.coerceIn(0f, 1f)
    return points.toMutableList().also { it[index] = CurvePoint(clampedX, clampedY) }
}
