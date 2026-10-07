package com.example.pixelmaster.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pixelmaster.data.model.AdjustParam
import com.example.pixelmaster.data.model.CurveChannel
import com.example.pixelmaster.data.model.Curves
import com.example.pixelmaster.data.model.FilterPresets
import com.example.pixelmaster.data.model.HslAdjustments
import com.example.pixelmaster.data.model.HslBand
import com.example.pixelmaster.data.model.HslBands
import com.example.pixelmaster.data.model.UserPreset
import com.example.pixelmaster.image.ColorMatrixFactory
import com.example.pixelmaster.image.LiquifyStroke
import com.example.pixelmaster.ui.theme.EditorBackground
import com.example.pixelmaster.ui.theme.EditorSurface
import kotlin.math.min

private enum class EditorPanel { ADJUST, FILTER, PORTRAIT, CURVE, HSL, LOCAL }

/** 缩放手势状态提升到编辑器层，供点击修复/液化/局部的坐标换算使用。 */
private class ZoomVars(
    val scale: androidx.compose.runtime.MutableState<Float>,
    val offset: androidx.compose.runtime.MutableState<Offset>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(uri: Uri, onExit: () -> Unit) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(uri) { viewModel.load(context, uri) }
    LaunchedEffect(Unit) { viewModel.ensurePresetsLoaded(context) }
    BackHandler(enabled = !state.cropMode, onBack = onExit)
    BackHandler(enabled = state.cropMode, onBack = viewModel::cancelCrop)

    var panel by remember { mutableStateOf(EditorPanel.ADJUST) }
    var activeParam by remember { mutableStateOf(AdjustParam.EXPOSURE) }
    var curveChannel by remember { mutableStateOf(CurveChannel.RGB) }
    var hslBand by remember { mutableStateOf(HslBands.RED) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var deletePresetTarget by remember { mutableStateOf<UserPreset?>(null) }
    var compareOn by remember { mutableStateOf(false) }
    var blemishOn by remember { mutableStateOf(false) }
    var liquifyOn by remember { mutableStateOf(false) }
    var brushPercent by remember { mutableFloatStateOf(0.07f) }
    var gestureId by remember { mutableLongStateOf(0L) }
    // 液化笔刷圆圈的屏幕位置（拖动期间显示）
    var liquifyPointer by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val zoom = ZoomVars(
        remember { mutableFloatStateOf(1f) },
        remember { mutableStateOf(Offset.Zero) },
    )
    val snackbarHostState = remember { SnackbarHostState() }

    val preset = FilterPresets.byId(state.presetId)
    val effective = remember(state.adjustments, state.presetId) {
        preset.base + state.adjustments
    }
    val previewFilter = remember(effective, state.presetId) {
        ColorFilter.colorMatrix(
            ColorMatrix(ColorMatrixFactory.build(effective, preset.channelMatrix).array)
        )
    }

    // 打开人像面板时触发一次人脸检测
    LaunchedEffect(panel) {
        if (panel == EditorPanel.PORTRAIT) viewModel.ensureFaces()
    }

    // 液化/局部拖拽模式禁用缩放：进入时复位
    LaunchedEffect(liquifyOn) {
        if (liquifyOn) {
            zoom.scale.value = 1f
            zoom.offset.value = Offset.Zero
        }
    }

    LaunchedEffect(state.savedUri) {
        if (state.savedUri != null) {
            showExportDialog = false
            snackbarHostState.showSnackbar("已保存到相册")
            viewModel.consumeSavedUri()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val imageReady = state.bitmap != null && !state.loading && !state.loadFailed && !state.cropMode
    val displayProcessed = state.processed != null && !compareOn
    val blemishActive = blemishOn && imageReady
    val liquifyActive = liquifyOn && imageReady
    val localDragActive = imageReady && panel == EditorPanel.LOCAL && state.local.enabled

    /** 屏幕坐标 → 源图坐标（当前显示位图可能是降采样渲染结果，需按比例换算）。 */
    fun imageCoordsOf(pos: Offset): Offset? {
        val src = state.bitmap ?: return null
        val processedBmp = state.processed
        val dispW: Int
        val dispH: Int
        if (displayProcessed && processedBmp != null) {
            dispW = processedBmp.width
            dispH = processedBmp.height
        } else {
            dispW = src.width
            dispH = src.height
        }
        val p = screenToImage(pos, canvasSize, dispW, dispH, zoom) ?: return null
        val ratio = src.width.toFloat() / dispW
        return Offset(p.x * ratio, p.y * ratio)
    }

    fun handleTap(pos: Offset) {
        if (!blemishActive) return
        imageCoordsOf(pos)?.let { viewModel.healAt(it.x, it.y) }
    }

    Scaffold(
        topBar = {
            if (!state.cropMode) {
                EditorTopBar(
                    canUndo = state.canUndo,
                    canRedo = state.canRedo,
                    onBack = onExit,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onReset = viewModel::resetAll,
                    onSave = { showExportDialog = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (!state.cropMode && !state.loading && !state.loadFailed) {
                Surface(color = EditorSurface) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            TabChip("调整", panel == EditorPanel.ADJUST) { panel = EditorPanel.ADJUST }
                            Spacer(Modifier.width(8.dp))
                            TabChip("滤镜", panel == EditorPanel.FILTER) { panel = EditorPanel.FILTER }
                            Spacer(Modifier.width(8.dp))
                            TabChip("人像", panel == EditorPanel.PORTRAIT) { panel = EditorPanel.PORTRAIT }
                            Spacer(Modifier.width(8.dp))
                            TabChip("曲线", panel == EditorPanel.CURVE) { panel = EditorPanel.CURVE }
                            Spacer(Modifier.width(8.dp))
                            TabChip("HSL", panel == EditorPanel.HSL) { panel = EditorPanel.HSL }
                            Spacer(Modifier.width(8.dp))
                            TabChip("局部", panel == EditorPanel.LOCAL) { panel = EditorPanel.LOCAL }
                            Spacer(Modifier.width(8.dp))
                            TabChip("裁剪", false) { viewModel.beginCrop() }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (panel) {
                            EditorPanel.ADJUST -> AdjustPanel(
                                adjustments = state.adjustments,
                                activeParam = activeParam,
                                onActiveParamChange = { activeParam = it },
                                onLiveChange = viewModel::updateLive,
                                onCommit = viewModel::commit,
                            )

                            EditorPanel.FILTER -> FilterPanel(
                                thumb = state.thumb,
                                selectedId = state.presetId,
                                userPresets = state.userPresets,
                                onPresetChange = viewModel::applyPreset,
                                onUserPresetSelect = viewModel::applyUserPreset,
                                onSaveCurrent = { showSavePresetDialog = true },
                                onDeleteUserPreset = { deletePresetTarget = it },
                            )

                            EditorPanel.PORTRAIT -> PortraitPanel(
                                portrait = state.portrait,
                                faceCount = state.faces.size,
                                detecting = state.detectingFaces,
                                blemishOn = blemishOn,
                                onBlemishChange = { on ->
                                    blemishOn = on
                                    if (on) liquifyOn = false
                                },
                                onLiveChange = viewModel::updatePortraitLive,
                                onCommit = viewModel::commit,
                                liquifyOn = liquifyOn,
                                onLiquifyChange = { on ->
                                    liquifyOn = on
                                    if (on) blemishOn = false
                                },
                                brushPercent = brushPercent,
                                onBrushChange = { brushPercent = it },
                                liquifyCount = state.liquifyCount,
                                onUndoLiquify = viewModel::undoLiquifyGesture,
                                onClearLiquify = viewModel::clearLiquify,
                            )

                            EditorPanel.CURVE -> CurvePanel(
                                curves = state.curves,
                                activeChannel = curveChannel,
                                onChannelChange = { curveChannel = it },
                                onLiveChange = viewModel::updateCurvesLive,
                                onCommit = viewModel::commit,
                                histogram = state.histogram,
                                onResetChannel = {
                                    viewModel.updateCurvesLive(
                                        state.curves.withPoints(curveChannel, Curves.defaultLine())
                                    )
                                    viewModel.commit()
                                },
                            )

                            EditorPanel.HSL -> HslPanel(
                                hsl = state.hsl,
                                activeBand = hslBand,
                                onBandChange = { hslBand = it },
                                onLiveChange = viewModel::updateHslLive,
                                onCommit = viewModel::commit,
                                onResetBand = {
                                    viewModel.updateHslLive(state.hsl.with(hslBand, HslBand()))
                                    viewModel.commit()
                                },
                            )

                            EditorPanel.LOCAL -> LocalPanel(
                                local = state.local,
                                onLiveChange = viewModel::updateLocalLive,
                                onCommit = viewModel::commit,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(EditorBackground)
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) },
        ) {
            when {
                state.loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )

                state.loadFailed -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "图片加载失败",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onExit) { Text("返回") }
                }

                state.cropMode -> CropView(
                    bitmap = state.cropPreview ?: state.bitmap!!,
                    onApply = viewModel::applyCrop,
                    onCancel = viewModel::cancelCrop,
                    onRotate = viewModel::rotateCropWorking,
                    onFlip = viewModel::flipCropWorking,
                )

                else -> {
                    val processedBmp = state.processed
                    val displayBmp: ImageBitmap
                    val displayFilter: ColorFilter?
                    if (displayProcessed && processedBmp != null) {
                        displayBmp = processedBmp
                        displayFilter = null
                    } else {
                        displayBmp = state.bitmap!!
                        displayFilter = if (compareOn) null else previewFilter
                    }

                    if (liquifyActive) {
                        DragImage(
                            bitmap = displayBmp,
                            convert = ::imageCoordsOf,
                            onDragStart = { screenPos ->
                                gestureId++
                                liquifyPointer = screenPos
                            },
                            onDrag = { cur, delta, screenPos ->
                                liquifyPointer = screenPos
                                val src = state.bitmap ?: return@DragImage
                                val radius = brushPercent * min(src.width, src.height)
                                viewModel.appendLiquifyStroke(
                                    gestureId,
                                    LiquifyStroke(
                                        cx = cur.x,
                                        cy = cur.y,
                                        radius = radius,
                                        dx = delta.x * 0.7f,
                                        dy = delta.y * 0.7f,
                                    ),
                                )
                            },
                            onDragEnd = { liquifyPointer = null },
                        )
                    } else if (localDragActive) {
                        DragImage(
                            bitmap = displayBmp,
                            convert = ::imageCoordsOf,
                            onDrag = { cur, _, _ ->
                                val src = state.bitmap ?: return@DragImage
                                viewModel.updateLocalLive(
                                    state.local.copy(
                                        centerX = (cur.x / src.width).coerceIn(0.02f, 0.98f),
                                        centerY = (cur.y / src.height).coerceIn(0.02f, 0.98f),
                                    )
                                )
                            },
                            onDragEnd = viewModel::commit,
                        )
                    } else {
                        ZoomableImage(
                            bitmap = displayBmp,
                            colorFilter = displayFilter,
                            zoom = zoom,
                            doubleTapEnabled = !blemishOn,
                            onTap = ::handleTap,
                        )
                    }

                    ComparePill(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        onChange = { compareOn = it },
                    )
                    if (compareOn) {
                        StatusBadge(
                            text = "原图",
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                        )
                    }
                    if (state.processing) {
                        StatusBadge(
                            text = "渲染中…",
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    if (blemishOn) {
                        StatusBadge(
                            text = "点击图中瑕疵处修复",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }
                    if (liquifyOn) {
                        StatusBadge(
                            text = "拖动图片推挤变形 · 笔刷 ${(brushPercent * 100).toInt()}%",
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 12.dp),
                        )
                    }

                    // 液化笔刷指示圈
                    val pointer = liquifyPointer
                    val src = state.bitmap
                    if (liquifyOn && pointer != null && src != null) {
                        BrushOverlay(
                            screenPos = pointer,
                            radiusImg = brushPercent * min(src.width, src.height),
                            dispW = displayBmp.width,
                            dispH = displayBmp.height,
                            srcW = src.width,
                            canvas = canvasSize,
                        )
                    }
                    // 局部调色蒙版圈
                    if (localDragActive && src != null) {
                        LocalRingOverlay(
                            local = state.local,
                            dispW = displayBmp.width,
                            dispH = displayBmp.height,
                            srcW = src.width,
                            srcH = src.height,
                            canvas = canvasSize,
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportDialog(
            saving = state.saving,
            onDismiss = { showExportDialog = false },
            onExport = { asPng, quality -> viewModel.save(context, asPng, quality) },
        )
    }
    if (showSavePresetDialog) {
        SavePresetDialog(
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                viewModel.saveUserPreset(context, name)
                showSavePresetDialog = false
            },
        )
    }
    deletePresetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletePresetTarget = null },
            title = { Text("删除预设") },
            text = { Text("确定删除「${target.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteUserPreset(context, target)
                    deletePresetTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePresetTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

/** 液化笔刷圆圈指示（跟随手指）。 */
@Composable
private fun BrushOverlay(
    screenPos: Offset,
    radiusImg: Float,
    dispW: Int,
    dispH: Int,
    srcW: Int,
    canvas: Size,
) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvas == Size.Zero || dispW <= 0) return@Canvas
        val fit = min(canvas.width / dispW, canvas.height / dispH)
        val radius = radiusImg * (dispW.toFloat() / srcW) * fit
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius,
            center = screenPos,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = radius * 0.12f,
            center = screenPos,
        )
    }
}

/** 局部调色蒙版圈与中心点。 */
@Composable
private fun LocalRingOverlay(
    local: com.example.pixelmaster.data.model.LocalAdjust,
    dispW: Int,
    dispH: Int,
    srcW: Int,
    srcH: Int,
    canvas: Size,
) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvas == Size.Zero || dispW <= 0 || srcW <= 0) return@Canvas
        val fit = min(canvas.width / dispW, canvas.height / dispH)
        val origin = Offset(
            (canvas.width - dispW * fit) / 2f,
            (canvas.height - dispH * fit) / 2f,
        )
        val scaleDisp = dispW.toFloat() / srcW
        val center = Offset(
            origin.x + local.centerX * srcW * scaleDisp * fit,
            origin.y + local.centerY * srcH * (dispH.toFloat() / srcH) * fit,
        )
        val radius = local.radius * min(srcW, srcH) * scaleDisp * fit
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 5.dp.toPx(),
            center = center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                )
            }
        },
        title = {
            Text(text = "编辑", style = MaterialTheme.typography.titleMedium)
        },
        actions = {
            TextButton(onClick = onUndo, enabled = canUndo) { Text("撤销") }
            TextButton(onClick = onRedo, enabled = canRedo) { Text("重做") }
            IconButton(onClick = onReset) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "重置")
            }
            Button(onClick = onSave) { Text("保存") }
        },
    )
}

@Composable
private fun StatusBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** 按住期间显示原图，松手恢复。 */
@Composable
private fun ComparePill(modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onChange(true)
                    waitForUpOrCancellation()
                    onChange(false)
                }
            },
    ) {
        Text(
            text = "按住对比",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SavePresetDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存为预设") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("预设名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 双指缩放 + 拖动平移，双击在 1x / 2x 间切换；支持单点点击回调。 */
@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    colorFilter: ColorFilter?,
    zoom: ZoomVars,
    doubleTapEnabled: Boolean = true,
    onTap: ((Offset) -> Unit)? = null,
) {
    LaunchedEffect(bitmap) {
        zoom.scale.value = 1f
        zoom.offset.value = Offset.Zero
    }
    var scale by zoom.scale
    var offset by zoom.offset
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 6f)
        scale = newScale
        offset = if (newScale > 1f) offset + panChange else Offset.Zero
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .transformable(transformState)
            .pointerInput(doubleTapEnabled) {
                detectTapGestures(
                    onTap = onTap?.let { handler -> { pos -> handler(pos) } },
                    onDoubleTap = if (doubleTapEnabled) {
                        {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
    )
}

/** 拖拽模式（无缩放手势）：液化推挤 / 局部调色定位共用。 */
@Composable
private fun DragImage(
    bitmap: ImageBitmap,
    convert: (Offset) -> Offset?,
    onDragStart: ((Offset) -> Unit)? = null,
    onDrag: (curImg: Offset, deltaImg: Offset, screenPos: Offset) -> Unit,
    onDragEnd: (() -> Unit)? = null,
) {
    var lastImgPos by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val p = convert(pos)
                        if (p != null) {
                            lastImgPos = p
                            onDragStart?.invoke(pos)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val p = convert(change.position) ?: return@detectDragGestures
                        onDrag(p, p - lastImgPos, change.position)
                        lastImgPos = p
                    },
                    onDragEnd = { onDragEnd?.invoke() },
                )
            },
        contentScale = ContentScale.Fit,
    )
}

/** 屏幕坐标 → 位图坐标（考虑 Fit 布局 + 缩放手势），越界返回 null。 */
private fun screenToImage(
    pos: Offset,
    canvas: Size,
    bmpW: Int,
    bmpH: Int,
    zoom: ZoomVars,
): Offset? {
    if (bmpW <= 0 || bmpH <= 0 || canvas == Size.Zero) return null
    val fit = min(canvas.width / bmpW, canvas.height / bmpH)
    if (fit <= 0f) return null
    val origin = Offset(
        (canvas.width - bmpW * fit) / 2f,
        (canvas.height - bmpH * fit) / 2f,
    )
    val center = Offset(canvas.width / 2f, canvas.height / 2f)
    val fitPoint = center + (pos - zoom.offset.value - center) / zoom.scale.value
    val p = Offset((fitPoint.x - origin.x) / fit, (fitPoint.y - origin.y) / fit)
    return if (p.x >= 0f && p.x <= bmpW && p.y >= 0f && p.y <= bmpH) p else null
}
