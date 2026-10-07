package com.example.pixelmaster.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelmaster.data.model.Adjustments
import com.example.pixelmaster.data.model.Curves
import com.example.pixelmaster.data.model.EditRecipe
import com.example.pixelmaster.data.model.FilterPresets
import com.example.pixelmaster.data.model.HslAdjustments
import com.example.pixelmaster.data.model.LastRecipeStore
import com.example.pixelmaster.data.model.LocalAdjust
import com.example.pixelmaster.data.model.PortraitParams
import com.example.pixelmaster.data.model.UserPreset
import com.example.pixelmaster.data.model.UserPresetStore
import com.example.pixelmaster.image.FaceDetectorBridge
import com.example.pixelmaster.image.FaceInfo
import com.example.pixelmaster.image.ImageExporter
import com.example.pixelmaster.image.ImageLoader
import com.example.pixelmaster.image.ImagePipeline
import com.example.pixelmaster.image.LiquifyStroke
import com.example.pixelmaster.image.RenderSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class EditorViewModel : ViewModel() {

    data class UiState(
        val loadStarted: Boolean = false,
        val loading: Boolean = true,
        val loadFailed: Boolean = false,
        val bitmap: ImageBitmap? = null,
        val thumb: ImageBitmap? = null,
        val adjustments: Adjustments = Adjustments(),
        val presetId: String = FilterPresets.original.id,
        val portrait: PortraitParams = PortraitParams(),
        val curves: Curves = Curves(),
        val hsl: HslAdjustments = HslAdjustments(),
        val local: LocalAdjust = LocalAdjust(),
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val saving: Boolean = false,
        val savedUri: Uri? = null,
        val error: String? = null,
        val message: String? = null,
        // 软件渲染预览（进阶调色/曲线/HSL/局部/人像/液化生效时替代 GPU ColorFilter 路径）
        val processed: ImageBitmap? = null,
        val processing: Boolean = false,
        // 曲线编辑器的亮度直方图（0..1 归一化）
        val histogram: FloatArray? = null,
        // 人脸检测
        val faces: List<FaceInfo> = emptyList(),
        val facesDetected: Boolean = false,
        val detectingFaces: Boolean = false,
        // 手动液化（独立于参数撤销，有按笔撤销）
        val liquifyCount: Int = 0,
        // 裁剪模式
        val cropMode: Boolean = false,
        val cropPreview: ImageBitmap? = null,
        // 自定义预设
        val userPresets: List<UserPreset> = emptyList(),
        val presetsLoaded: Boolean = false,
    )

    /**
     * 历史快照：参数 + 可选源图引用。
     * bitmap == null 表示该时刻源图未变化（沿用更早状态里的引用）；
     * bitmap != null 表示裁剪/修复等破坏性操作后的新源图。恢复时取 <=index 最近一次非空引用。
     */
    private data class EditSnapshot(
        val adjustments: Adjustments,
        val presetId: String,
        val portrait: PortraitParams,
        val curves: Curves,
        val hsl: HslAdjustments,
        val local: LocalAdjust,
        val bitmap: Bitmap?,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val history = mutableListOf<EditSnapshot>()
    private var historyIndex = -1

    private var source: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var cropWorking: Bitmap? = null
    private var renderJob: Job? = null

    // 当前编辑的图片与破坏性标记（裁剪/修复后无法从原图重建，导出不再重解码原图）
    private var loadedUri: Uri? = null
    private var destructiveApplied = false

    // 液化：按「手势」分组的笔划，一次手指拖拽 = 一笔
    private val liquifyGestures = mutableListOf<Pair<Long, MutableList<LiquifyStroke>>>()

    // ---------- 加载 ----------

    fun load(context: Context, uri: Uri) {
        // 修复：同一 Activity 级 ViewModel 被复用时，换图必须完整复位状态
        if (loadedUri == uri && _uiState.value.loadStarted) return

        renderJob?.cancel()
        liquifyGestures.clear()
        history.clear()
        historyIndex = -1
        cropWorking = null
        loadedUri = uri
        destructiveApplied = false
        _uiState.value = UiState(loadStarted = true, loading = true)

        viewModelScope.launch {
            ImageLoader.load(context, uri)
                .onSuccess { bitmap ->
                    source = bitmap
                    previewSource = downscaleForPreview(bitmap)
                    history.clear()
                    history.add(snapshotOf(bitmap))
                    historyIndex = 0
                    _uiState.update {
                        it.copy(
                            loading = false,
                            bitmap = bitmap.asImageBitmap(),
                            thumb = ImageLoader.thumbnail(bitmap).asImageBitmap(),
                            histogram = histogramOf(previewSource),
                        )
                    }
                    refreshHistoryFlags()
                }
                .onFailure {
                    _uiState.update { it.copy(loading = false, loadFailed = true) }
                }
        }
    }

    fun ensurePresetsLoaded(context: Context) {
        if (_uiState.value.presetsLoaded) return
        _uiState.update {
            it.copy(userPresets = UserPresetStore.load(context), presetsLoaded = true)
        }
    }

    // ---------- 参数更新（历史 + 渲染调度） ----------

    /** 滑块/曲线拖动过程中的实时预览，不进入撤销历史。 */
    fun updateLive(adjustments: Adjustments) {
        _uiState.update { it.copy(adjustments = adjustments) }
        scheduleRender()
    }

    fun updatePortraitLive(portrait: PortraitParams) {
        _uiState.update { it.copy(portrait = portrait) }
        scheduleRender()
    }

    fun updateCurvesLive(curves: Curves) {
        _uiState.update { it.copy(curves = curves) }
        scheduleRender()
    }

    fun updateHslLive(hsl: HslAdjustments) {
        _uiState.update { it.copy(hsl = hsl) }
        scheduleRender()
    }

    fun updateLocalLive(local: LocalAdjust) {
        _uiState.update { it.copy(local = local) }
        scheduleRender()
    }

    /** 一次交互结束（松手 / 切换滤镜 / 拖完曲线点 / 拖完局部中心）提交到撤销历史。 */
    fun commit() {
        pushHistory(snapshotOf(null))
    }

    fun applyPreset(presetId: String) {
        _uiState.update { it.copy(presetId = presetId) }
        pushHistory(snapshotOf(null))
        scheduleRender()
    }

    fun applyUserPreset(preset: UserPreset) {
        _uiState.update {
            it.copy(
                presetId = preset.basePresetId,
                adjustments = preset.adjustments,
                curves = preset.curves,
                portrait = preset.portrait,
                hsl = preset.hsl,
            )
        }
        pushHistory(snapshotOf(null))
        scheduleRender()
    }

    fun saveUserPreset(context: Context, name: String) {
        val st = _uiState.value
        val preset = UserPreset(
            id = "u${System.currentTimeMillis()}",
            name = name.ifBlank { "我的预设" },
            basePresetId = st.presetId,
            adjustments = st.adjustments,
            curves = st.curves,
            portrait = st.portrait,
            hsl = st.hsl,
        )
        val next = st.userPresets + preset
        UserPresetStore.save(context, next)
        _uiState.update { it.copy(userPresets = next, message = "预设「${preset.name}」已保存") }
    }

    fun deleteUserPreset(context: Context, preset: UserPreset) {
        val next = _uiState.value.userPresets.filterNot { it.id == preset.id }
        UserPresetStore.save(context, next)
        _uiState.update { it.copy(userPresets = next, message = "预设已删除") }
    }

    fun resetAll() {
        _uiState.update {
            it.copy(
                adjustments = Adjustments(),
                presetId = FilterPresets.original.id,
                portrait = PortraitParams(),
                curves = Curves(),
                hsl = HslAdjustments(),
                local = LocalAdjust(),
            )
        }
        liquifyGestures.clear()
        _uiState.update { it.copy(liquifyCount = 0) }
        pushHistory(snapshotOf(null))
        scheduleRender()
    }

    fun undo() {
        if (historyIndex <= 0) return
        historyIndex--
        restoreCurrent()
    }

    fun redo() {
        if (historyIndex >= history.lastIndex) return
        historyIndex++
        restoreCurrent()
    }

    // ---------- 人脸检测 ----------

    fun ensureFaces() {
        val src = source ?: return
        val st = _uiState.value
        if (st.facesDetected || st.detectingFaces) return
        _uiState.update { it.copy(detectingFaces = true) }
        viewModelScope.launch {
            val faces = FaceDetectorBridge.detect(src)
            _uiState.update { it.copy(faces = faces, facesDetected = true, detectingFaces = false) }
            scheduleRender()
        }
    }

    // ---------- 手动液化 ----------

    /** 追加一笔划（gestureId 区分不同手势，一次拖拽的所有微笔划属于同一手势）。 */
    fun appendLiquifyStroke(gestureId: Long, stroke: LiquifyStroke) {
        val last = liquifyGestures.lastOrNull()
        if (last != null && last.first == gestureId) {
            last.second.add(stroke)
        } else {
            liquifyGestures.add(gestureId to mutableListOf(stroke))
        }
        _uiState.update { it.copy(liquifyCount = it.liquifyCount + 1) }
        scheduleRender()
    }

    /** 撤销最近一笔（一次完整拖拽）。 */
    fun undoLiquifyGesture() {
        if (liquifyGestures.isEmpty()) return
        val removed = liquifyGestures.removeAt(liquifyGestures.lastIndex).second.size
        _uiState.update { it.copy(liquifyCount = (it.liquifyCount - removed).coerceAtLeast(0)) }
        scheduleRender()
    }

    fun clearLiquify() {
        if (liquifyGestures.isEmpty()) return
        liquifyGestures.clear()
        _uiState.update { it.copy(liquifyCount = 0) }
        scheduleRender()
    }

    // ---------- 软件渲染预览 ----------

    private fun needsSoftware(st: UiState): Boolean =
        st.adjustments.hasAdvanced || !st.curves.isDefault || !st.hsl.isDefault ||
            st.local.isActive || st.liquifyCount > 0 ||
            (st.portrait.anyActive && st.faces.isNotEmpty())

    private fun scheduleRender() {
        val st = _uiState.value
        if (!needsSoftware(st)) {
            renderJob?.cancel()
            _uiState.update { it.copy(processed = null, processing = false) }
            return
        }
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            delay(RENDER_DEBOUNCE_MS)
            renderNow()
        }
    }

    private suspend fun renderNow() {
        val preview = previewSource ?: return
        val st = _uiState.value
        if (!needsSoftware(st)) return
        _uiState.update { it.copy(processing = true) }
        val scale = preview.width.toFloat() / (source?.width ?: preview.width).toFloat()
        val spec = buildSpec(st, scale)
        val rendered = ImagePipeline.render(preview, spec)
        _uiState.update { it.copy(processed = rendered.asImageBitmap(), processing = false) }
    }

    private fun buildSpec(st: UiState, coordScale: Float): RenderSpec {
        val preset = FilterPresets.byId(st.presetId)
        return RenderSpec(
            adjustments = preset.base + st.adjustments,
            channelMatrix = preset.channelMatrix,
            portrait = st.portrait,
            faces = st.faces.map { it.scaled(coordScale) },
            curves = st.curves,
            liquify = liquifyGestures.flatMap { it.second }.map { it.scaled(coordScale) },
            hsl = st.hsl,
            local = if (st.local.isActive) st.local.coerced() else null,
        )
    }

    // ---------- 裁剪（破坏性，但已进入统一撤销历史） ----------

    fun beginCrop() {
        val src = source ?: return
        cropWorking = src
        _uiState.update { it.copy(cropMode = true, cropPreview = src.asImageBitmap()) }
    }

    fun rotateCropWorking() {
        val wk = cropWorking ?: return
        cropWorking = ImagePipeline.rotate90(wk)
        _uiState.update { it.copy(cropPreview = cropWorking!!.asImageBitmap()) }
    }

    fun flipCropWorking() {
        val wk = cropWorking ?: return
        cropWorking = ImagePipeline.flipHorizontal(wk)
        _uiState.update { it.copy(cropPreview = cropWorking!!.asImageBitmap()) }
    }

    fun cancelCrop() {
        cropWorking = null
        _uiState.update { it.copy(cropMode = false, cropPreview = null) }
    }

    fun applyCrop(rect: Rect) {
        val wk = cropWorking ?: return
        val cropped = ImagePipeline.crop(
            wk,
            rect.left.toInt(), rect.top.toInt(),
            rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1),
        )
        cropWorking = null
        // 几何变化后液化坐标失效
        liquifyGestures.clear()
        replaceSource(cropped)
        pushHistory(snapshotOf(cropped))
        destructiveApplied = true
        _uiState.update {
            it.copy(cropMode = false, cropPreview = null, liquifyCount = 0, message = "裁剪完成")
        }
    }

    // ---------- 瑕疵修复（破坏性，进入统一撤销历史） ----------

    fun healAt(x: Float, y: Float) {
        val src = source ?: return
        viewModelScope.launch {
            val nearbyFaceWidth = _uiState.value.faces
                .filter { f ->
                    val dx = x - f.box.centerX()
                    val dy = y - f.box.centerY()
                    dx * dx + dy * dy < f.box.width() * f.box.width()
                }
                .minOfOrNull { it.faceWidth }
            val radius = if (nearbyFaceWidth != null) {
                (nearbyFaceWidth * 0.14f).coerceIn(10f, 64f)
            } else {
                (min(src.width, src.height) * 0.02f).coerceIn(8f, 48f)
            }
            val healed = ImagePipeline.heal(src, x, y, radius)
            replaceSource(healed)
            pushHistory(snapshotOf(healed))
            destructiveApplied = true
            _uiState.update { it.copy(message = "已修复") }
        }
    }

    // ---------- 导出 ----------

    fun save(context: Context, asPng: Boolean, quality: Int) {
        val src = source ?: return
        if (_uiState.value.saving) return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null, savedUri = null) }
            val st = _uiState.value

            // 无破坏性编辑时，从原图按 4096 重新解码导出（编辑预览基于 1280，导出更保真）
            var exportBase = src
            var redecoded: Bitmap? = null
            if (!destructiveApplied && loadedUri != null) {
                ImageLoader.load(context, loadedUri!!, ImageLoader.MAX_DIMENSION)
                    .onSuccess { full ->
                        if (full.width > src.width && !full.isRecycled) {
                            exportBase = full
                            redecoded = full
                        }
                    }
            }

            val scale = exportBase.width.toFloat() / src.width.toFloat()
            val spec = buildSpec(st, scale)
            val rendered = ImagePipeline.render(exportBase, spec)
            ImageExporter.saveBitmap(context, rendered, asPng, quality, sourceUri = loadedUri)
                .onSuccess { uri ->
                    LastRecipeStore.save(
                        context,
                        EditRecipe(
                            st.presetId, st.adjustments, st.curves, st.portrait, st.hsl
                        ),
                    )
                    _uiState.update { it.copy(saving = false, savedUri = uri) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(saving = false, error = "导出失败：${e.message ?: "未知错误"}")
                    }
                }
            if (rendered !== exportBase) rendered.recycle()
            if (redecoded != null && redecoded !== src && !redecoded!!.isRecycled) {
                redecoded!!.recycle()
            }
        }
    }

    // ---------- 一次性事件 ----------

    fun consumeSavedUri() = _uiState.update { it.copy(savedUri = null) }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    // ---------- 内部 ----------

    private fun snapshotOf(bitmap: Bitmap?): EditSnapshot {
        val st = _uiState.value
        return EditSnapshot(
            adjustments = st.adjustments,
            presetId = st.presetId,
            portrait = st.portrait,
            curves = st.curves,
            hsl = st.hsl,
            local = st.local,
            bitmap = bitmap,
        )
    }

    private fun pushHistory(snapshot: EditSnapshot) {
        while (history.size > historyIndex + 1) history.removeAt(history.lastIndex)
        history.add(snapshot)
        historyIndex = history.lastIndex
        trimHistory()
        refreshHistoryFlags()
    }

    /** 历史条数与历史源图引用数量都设上限，防止内存失控。 */
    private fun trimHistory() {
        while (history.size > MAX_HISTORY) {
            history.removeAt(0)
            historyIndex--
        }
        if (historyIndex < 0) historyIndex = 0
        val distinct = history.mapNotNull { it.bitmap }.distinct()
        if (distinct.size > MAX_HISTORY_BITMAPS) {
            var excess = distinct.size - MAX_HISTORY_BITMAPS
            for (i in 0 until history.size) {
                if (excess == 0) break
                val b = history[i].bitmap ?: continue
                if (i < historyIndex) {
                    // 丢弃更早的源图引用：该状态的参数仍可恢复，源图保持现状
                    history[i] = history[i].copy(bitmap = null)
                    excess--
                }
            }
        }
    }

    private fun restoreCurrent() {
        val snapshot = history[historyIndex]
        _uiState.update {
            it.copy(
                adjustments = snapshot.adjustments,
                presetId = snapshot.presetId,
                portrait = snapshot.portrait,
                curves = snapshot.curves,
                hsl = snapshot.hsl,
                local = snapshot.local,
            )
        }
        // 源图取 <=index 最近一次非空引用
        val target = (historyIndex downTo 0).firstNotNullOfOrNull { history[it].bitmap }
        if (target != null && !target.isRecycled && target !== source) {
            // 跨越了破坏性操作边界：其后产生的液化笔划坐标失效
            liquifyGestures.clear()
            _uiState.update { it.copy(liquifyCount = 0) }
            replaceSource(target)
        } else {
            scheduleRender()
        }
        refreshHistoryFlags()
    }

    private fun replaceSource(bitmap: Bitmap) {
        val hadFaces = _uiState.value.facesDetected || _uiState.value.portrait.anyActive
        source = bitmap
        previewSource = downscaleForPreview(bitmap)
        _uiState.update {
            it.copy(
                bitmap = bitmap.asImageBitmap(),
                thumb = ImageLoader.thumbnail(bitmap).asImageBitmap(),
                processed = null,
                faces = emptyList(),
                facesDetected = false,
                histogram = histogramOf(previewSource),
            )
        }
        // 修复：破坏性操作后人脸缓存失效，需要时自动重检（此前只切 tab 才会重检）
        if (hadFaces) ensureFaces()
        scheduleRender()
    }

    private fun downscaleForPreview(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        return if (longest > PREVIEW_MAX_DIM) {
            val s = PREVIEW_MAX_DIM.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * s).toInt().coerceAtLeast(1),
                (bitmap.height * s).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
    }

    private fun histogramOf(bitmap: Bitmap?): FloatArray? {
        if (bitmap == null || bitmap.isRecycled) return null
        return runCatching {
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            val hist = FloatArray(256)
            var i = 0
            while (i < px.size) {
                val c = px[i]
                val luma = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF))
                    .toInt().coerceIn(0, 255)
                hist[luma]++
                i += 2
            }
            var maxV = 1f
            for (v in hist) if (v > maxV) maxV = v
            for (j in hist.indices) hist[j] = sqrt(hist[j] / maxV)
            hist
        }.getOrNull()
    }

    private fun refreshHistoryFlags() {
        _uiState.update {
            it.copy(canUndo = historyIndex > 0, canRedo = historyIndex < history.lastIndex)
        }
    }

    private companion object {
        const val MAX_HISTORY = 100
        const val MAX_HISTORY_BITMAPS = 4
        const val RENDER_DEBOUNCE_MS = 250L
        const val PREVIEW_MAX_DIM = 1280
    }
}
