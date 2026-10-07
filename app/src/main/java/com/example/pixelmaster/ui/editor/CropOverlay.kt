package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.ui.theme.EditorSurface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class CropCorner { TL, TR, BL, BR }

private sealed interface DragMode {
    data object None : DragMode
    data object Move : DragMode
    data class Corner(val corner: CropCorner) : DragMode
}

private data class AspectOption(val label: String, val ratio: Float?)

private val aspectOptions = listOf(
    AspectOption("自由", null),
    AspectOption("1:1", 1f),
    AspectOption("4:3", 4f / 3f),
    AspectOption("3:4", 3f / 4f),
    AspectOption("16:9", 16f / 9f),
    AspectOption("9:16", 9f / 16f),
)

/**
 * 裁剪模式：拖角调整裁剪框、拖内部移动、比例约束、旋转/翻转（立即物化到工作位图）。
 * onApply 回调的 Rect 为位图坐标。
 */
@Composable
fun CropView(
    bitmap: ImageBitmap,
    onApply: (Rect) -> Unit,
    onCancel: () -> Unit,
    onRotate: () -> Unit,
    onFlip: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var rect by remember { mutableStateOf(Rect.Zero) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var dragMode by remember { mutableStateOf<DragMode>(DragMode.None) }

    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()

    LaunchedEffect(bitmap, canvasSize) {
        if (canvasSize != Size.Zero && bw > 0f && bh > 0f) {
            rect = initialRect(bw, bh, aspect)
        }
    }
    fun selectAspect(ratio: Float?) {
        aspect = ratio
        rect = initialRect(bw, bh, ratio)
    }

    // 布局映射（在手势回调里按当前状态现算，避免闭包过期）
    fun fitOf(): Float = min(canvasSize.width / bw, canvasSize.height / bh)
    fun originOf(): Offset = Offset(
        (canvasSize.width - bw * fitOf()) / 2f,
        (canvasSize.height - bh * fitOf()) / 2f,
    )

    val fit = if (bw > 0f && bh > 0f && canvasSize.width > 0f && canvasSize.height > 0f) fitOf() else 0f
    val origin = if (fit > 0f) originOf() else Offset.Zero
    val markColor = MaterialTheme.colorScheme.primary
    val screenRect = Rect(
        origin.x + rect.left * fit, origin.y + rect.top * fit,
        origin.x + rect.right * fit, origin.y + rect.bottom * fit,
    )

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, aspect) {
                        val cornerRadius = 28.dp.toPx()
                        detectDragGestures(
                            onDragStart = { pos ->
                                val f = fitOf()
                                if (f <= 0f) return@detectDragGestures
                                val o = originOf()
                                val sr = Rect(
                                    o.x + rect.left * f, o.y + rect.top * f,
                                    o.x + rect.right * f, o.y + rect.bottom * f,
                                )
                                val corner = hitCorner(pos, sr, cornerRadius)
                                dragMode = when {
                                    corner != null -> DragMode.Corner(corner)
                                    sr.contains(pos) -> DragMode.Move
                                    else -> DragMode.None
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val f = fitOf()
                                if (f <= 0f) return@detectDragGestures
                                when (val mode = dragMode) {
                                    DragMode.Move -> {
                                        rect = translateRect(
                                            rect,
                                            Offset(dragAmount.x / f, dragAmount.y / f),
                                            Size(bw, bh),
                                        )
                                    }

                                    is DragMode.Corner -> {
                                        val o = originOf()
                                        val p = Offset(
                                            (change.position.x - o.x) / f,
                                            (change.position.y - o.y) / f,
                                        )
                                        rect = resizeRect(rect, mode.corner, p, Size(bw, bh), aspect)
                                    }

                                    DragMode.None -> Unit
                                }
                            },
                            onDragEnd = { dragMode = DragMode.None },
                            onDragCancel = { dragMode = DragMode.None },
                        )
                    }
            ) {
                if (fit > 0f && rect != Rect.Zero) {
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, screenRect.top))
                    drawRect(
                        dim,
                        topLeft = Offset(0f, screenRect.bottom),
                        size = Size(size.width, size.height - screenRect.bottom),
                    )
                    drawRect(
                        dim,
                        topLeft = Offset(0f, screenRect.top),
                        size = Size(screenRect.left, screenRect.height),
                    )
                    drawRect(
                        dim,
                        topLeft = Offset(screenRect.right, screenRect.top),
                        size = Size(size.width - screenRect.right, screenRect.height),
                    )

                    val border = Color.White
                    drawRect(
                        border,
                        topLeft = screenRect.topLeft,
                        size = screenRect.size,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    val grid = Color.White.copy(alpha = 0.5f)
                    for (i in 1..2) {
                        val x = screenRect.left + screenRect.width * i / 3f
                        drawLine(grid, Offset(x, screenRect.top), Offset(x, screenRect.bottom), 1.dp.toPx())
                        val y = screenRect.top + screenRect.height * i / 3f
                        drawLine(grid, Offset(screenRect.left, y), Offset(screenRect.right, y), 1.dp.toPx())
                    }
                    // 角标
                    val mark = 18.dp.toPx()
                    val stroke = 4.dp.toPx()
                    for (corner in CropCorner.entries) {
                        val cx = when (corner) {
                            CropCorner.TL, CropCorner.BL -> screenRect.left
                            else -> screenRect.right
                        }
                        val cy = when (corner) {
                            CropCorner.TL, CropCorner.TR -> screenRect.top
                            else -> screenRect.bottom
                        }
                        val dx = when (corner) {
                            CropCorner.TL, CropCorner.BL -> 1f
                            else -> -1f
                        }
                        val dy = when (corner) {
                            CropCorner.TL, CropCorner.TR -> 1f
                            else -> -1f
                        }
                        drawLine(markColor, Offset(cx, cy), Offset(cx + dx * mark, cy), stroke)
                        drawLine(markColor, Offset(cx, cy), Offset(cx, cy + dy * mark), stroke)
                    }
                }
            }
        }

        Surface(color = EditorSurface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, false),
                ) {
                    items(aspectOptions) { option ->
                        FilterChip(
                            selected = aspect == option.ratio,
                            onClick = { selectAspect(option.ratio) },
                            label = { Text(option.label) },
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    TextButton(onClick = onRotate) { Text("旋转90°") }
                    TextButton(onClick = onFlip) { Text("镜像") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("取消") }
                    Button(onClick = { onApply(rect) }, enabled = rect.width > 4f && rect.height > 4f) {
                        Text("完成")
                    }
                }
            }
        }
    }
}

// ---------- 手势几何 ----------

private fun hitCorner(pos: Offset, r: Rect, radius: Float): CropCorner? {
    val candidates = listOf(
        CropCorner.TL to r.topLeft,
        CropCorner.TR to r.topRight,
        CropCorner.BL to r.bottomLeft,
        CropCorner.BR to r.bottomRight,
    )
    return candidates.firstOrNull { (_, c) ->
        (pos.x - c.x) * (pos.x - c.x) + (pos.y - c.y) * (pos.y - c.y) <= radius * radius
    }?.first
}

private fun initialRect(bw: Float, bh: Float, aspect: Float?): Rect {
    val margin = 0.92f
    val w: Float
    val h: Float
    if (aspect == null) {
        w = bw * margin
        h = bh * margin
    } else {
        w = min(bw * margin, bh * margin * aspect)
        h = w / aspect
    }
    val cx = bw / 2f
    val cy = bh / 2f
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

private fun translateRect(rect: Rect, delta: Offset, bounds: Size): Rect {
    val l = (rect.left + delta.x).coerceIn(0f, bounds.width - rect.width)
    val t = (rect.top + delta.y).coerceIn(0f, bounds.height - rect.height)
    return Rect(l, t, l + rect.width, t + rect.height)
}

private fun resizeRect(
    rect: Rect,
    corner: CropCorner,
    p: Offset,
    bounds: Size,
    aspect: Float?,
): Rect {
    val minSize = max(32f, min(bounds.width, bounds.height) * 0.05f)
    val px = p.x.coerceIn(0f, bounds.width)
    val py = p.y.coerceIn(0f, bounds.height)

    if (aspect == null) {
        var l = rect.left; var t = rect.top; var r = rect.right; var b = rect.bottom
        when (corner) {
            CropCorner.TL -> { l = min(px, r - minSize); t = min(py, b - minSize) }
            CropCorner.TR -> { r = max(px, l + minSize); t = min(py, b - minSize) }
            CropCorner.BL -> { l = min(px, r - minSize); b = max(py, t + minSize) }
            CropCorner.BR -> { r = max(px, l + minSize); b = max(py, t + minSize) }
        }
        return Rect(l, t, r, b)
    }

    // 固定对角锚点，按比例调整
    val anchorX: Float
    val anchorY: Float
    var anchorAtLeft = false
    var anchorAtTop = false
    when (corner) {
        CropCorner.TL -> { anchorX = rect.right; anchorY = rect.bottom }
        CropCorner.TR -> { anchorX = rect.left; anchorY = rect.bottom; anchorAtLeft = true }
        CropCorner.BL -> { anchorX = rect.right; anchorY = rect.top; anchorAtTop = true }
        CropCorner.BR -> { anchorX = rect.left; anchorY = rect.top; anchorAtLeft = true; anchorAtTop = true }
    }
    val dw = abs(if (anchorAtLeft) px - anchorX else anchorX - px)
    val dh = abs(if (anchorAtTop) py - anchorY else anchorY - py)
    var newW = max(dw, minSize)
    var newH = newW / aspect
    if (dh * aspect > dw) {
        newH = max(dh, minSize)
        newW = newH * aspect
    }
    val availW = if (anchorAtLeft) bounds.width - anchorX else anchorX
    val availH = if (anchorAtTop) bounds.height - anchorY else anchorY
    if (newW > availW) {
        newW = availW
        newH = newW / aspect
    }
    if (newH > availH) {
        newH = availH
        newW = newH * aspect
    }
    if (newW < minSize || newH < minSize) return rect

    val l = if (anchorAtLeft) anchorX else anchorX - newW
    val r = if (anchorAtLeft) anchorX + newW else anchorX
    val t = if (anchorAtTop) anchorY else anchorY - newH
    val b = if (anchorAtTop) anchorY + newH else anchorY
    return Rect(l, t, r, b)
}
