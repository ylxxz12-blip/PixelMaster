package com.example.pixelmaster.ui.batch

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pixelmaster.data.model.Adjustments
import com.example.pixelmaster.data.model.EditRecipe
import com.example.pixelmaster.data.model.FilterPresets
import com.example.pixelmaster.data.model.LastRecipeStore
import com.example.pixelmaster.data.model.PortraitParams
import com.example.pixelmaster.data.model.UserPresetStore
import com.example.pixelmaster.image.FaceDetectorBridge
import com.example.pixelmaster.image.ImageExporter
import com.example.pixelmaster.image.ImageLoader
import com.example.pixelmaster.image.ImagePipeline
import com.example.pixelmaster.image.RenderSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BatchViewModel : ViewModel() {

    enum class Status { PENDING, RUNNING, DONE, FAILED }

    data class BatchItem(
        val uri: Uri,
        val status: Status = Status.PENDING,
        val thumb: ImageBitmap? = null,
    )

    /** 可套用的配方：上次编辑 / 任意自定义预设 / 不应用。 */
    data class RecipeOption(val label: String, val recipe: EditRecipe?)

    data class UiState(
        val started: Boolean = false,
        val items: List<BatchItem> = emptyList(),
        val options: List<RecipeOption> = emptyList(),
        val selectedOption: Int = 0,
        val running: Boolean = false,
        val doneCount: Int = 0,
        val failedCount: Int = 0,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var processJob: Job? = null

    fun setup(context: Context, uris: List<Uri>) {
        if (_uiState.value.started) return
        _uiState.update {
            it.copy(started = true, items = uris.map { uri -> BatchItem(uri) })
        }
        val options = buildList {
            LastRecipeStore.load(context)?.let { add(RecipeOption("上次 · ${it.describe()}", it)) }
            UserPresetStore.load(context).forEach { preset ->
                add(RecipeOption("预设 · ${preset.name}", preset.toRecipe()))
            }
            add(RecipeOption("不应用参数", null))
        }
        _uiState.update { it.copy(options = options, selectedOption = 0) }
        // 异步加载缩略图（仅用于展示）
        uris.forEach { uri ->
            viewModelScope.launch {
                ImageLoader.loadThumbnail(context, uri)
                    .onSuccess { bmp ->
                        _uiState.update { st ->
                            st.copy(
                                items = st.items.map {
                                    if (it.uri == uri) it.copy(thumb = bmp.asImageBitmap()) else it
                                }
                            )
                        }
                    }
            }
        }
    }

    fun selectOption(index: Int) {
        if (!_uiState.value.running && index in _uiState.value.options.indices) {
            _uiState.update { it.copy(selectedOption = index) }
        }
    }

    fun start(context: Context) {
        val st = _uiState.value
        if (st.running || st.items.isEmpty()) return
        val recipe = st.options.getOrNull(st.selectedOption)?.recipe
        _uiState.update { it.copy(running = true) }

        processJob = viewModelScope.launch {
            try {
                for (index in st.items.indices) {
                    val item = _uiState.value.items[index]
                    if (item.status != Status.PENDING) continue
                    setStatus(index, Status.RUNNING)
                    val ok = processOne(context, item.uri, recipe)
                    setStatus(index, if (ok) Status.DONE else Status.FAILED)
                    _uiState.update {
                        it.copy(
                            doneCount = it.doneCount + if (ok) 1 else 0,
                            failedCount = it.failedCount + if (ok) 0 else 1,
                        )
                    }
                }
            } finally {
                _uiState.update { st2 ->
                    st2.copy(
                        running = false,
                        items = st2.items.map {
                            if (it.status == Status.RUNNING) it.copy(status = Status.PENDING) else it
                        },
                    )
                }
            }
        }
    }

    fun cancel() {
        processJob?.cancel()
    }

    private fun setStatus(index: Int, status: Status) {
        _uiState.update { st ->
            st.copy(items = st.items.mapIndexed { i, item ->
                if (i == index) item.copy(status = status) else item
            })
        }
    }

    /** 处理一张：解码 →（按需）人脸检测 → 管线渲染 → 存入图库。 */
    private suspend fun processOne(
        context: Context,
        uri: Uri,
        recipe: EditRecipe?,
    ): Boolean {
        val bitmap: Bitmap = ImageLoader.load(context, uri).getOrElse { return false }
        try {
            val spec = buildSpec(recipe, bitmap)
            val rendered = ImagePipeline.render(bitmap, spec)
            val result = ImageExporter.saveBitmap(
                context, rendered, asPng = false, quality = 92, sourceUri = uri
            )
            if (rendered !== bitmap) rendered.recycle()
            return result.isSuccess
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun buildSpec(recipe: EditRecipe?, bitmap: Bitmap): RenderSpec {
        if (recipe == null) {
            return RenderSpec(
                adjustments = Adjustments(),
                channelMatrix = null,
                portrait = PortraitParams(),
                faces = emptyList(),
            )
        }
        val preset = FilterPresets.byId(recipe.presetId)
        val faces = if (recipe.portrait.anyActive) {
            FaceDetectorBridge.detect(bitmap)
        } else {
            emptyList()
        }
        return RenderSpec(
            adjustments = preset.base + recipe.adjustments,
            channelMatrix = preset.channelMatrix,
            portrait = recipe.portrait,
            faces = faces,
            curves = recipe.curves,
            hsl = recipe.hsl,
        )
    }
}
