package com.example.pixelmaster.data.model

import kotlin.math.roundToInt

/**
 * 全部调色参数，取值 -100..100（锐化/降噪/暗角/颗粒为 0..100），0 为不动。
 *
 * 线性参数（曝光..色调）走 GPU ColorMatrix 实时预览；
 * 进阶参数（高光..颗粒、降噪）由软件渲染管线处理（[hasAdvanced] 为真时切换到管线预览）。
 */
data class Adjustments(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpen: Float = 0f,
    val denoise: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
) {
    val isDefault: Boolean get() = this == Adjustments()

    /** 进阶参数是否生效（决定预览/导出走软件管线）。 */
    val hasAdvanced: Boolean
        get() = highlights != 0f || shadows != 0f || sharpen > 0f || denoise > 0f ||
            vignette > 0f || grain > 0f

    /** 逐项相加并夹取，用于「滤镜基础值 + 用户手动值」合成。 */
    operator fun plus(other: Adjustments): Adjustments = Adjustments(
        exposure = clamp(exposure + other.exposure),
        contrast = clamp(contrast + other.contrast),
        saturation = clamp(saturation + other.saturation),
        temperature = clamp(temperature + other.temperature),
        tint = clamp(tint + other.tint),
        highlights = clamp(highlights + other.highlights),
        shadows = clamp(shadows + other.shadows),
        sharpen = (sharpen + other.sharpen).coerceIn(0f, 100f),
        denoise = (denoise + other.denoise).coerceIn(0f, 100f),
        vignette = (vignette + other.vignette).coerceIn(0f, 100f),
        grain = (grain + other.grain).coerceIn(0f, 100f),
    )

    companion object {
        const val MIN = -100f
        const val MAX = 100f

        fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)
    }
}

/**
 * 可调节参数元信息：展示名、取值范围与读写函数，驱动调整面板滑块生成。
 */
enum class AdjustParam(
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val read: (Adjustments) -> Float,
    val write: (Adjustments, Float) -> Adjustments,
) {
    EXPOSURE("曝光", -100f..100f, { it.exposure }, { a, v -> a.copy(exposure = v) }),
    CONTRAST("对比度", -100f..100f, { it.contrast }, { a, v -> a.copy(contrast = v) }),
    SATURATION("饱和度", -100f..100f, { it.saturation }, { a, v -> a.copy(saturation = v) }),
    TEMPERATURE("色温", -100f..100f, { it.temperature }, { a, v -> a.copy(temperature = v) }),
    TINT("色调", -100f..100f, { it.tint }, { a, v -> a.copy(tint = v) }),
    HIGHLIGHTS("高光", -100f..100f, { it.highlights }, { a, v -> a.copy(highlights = v) }),
    SHADOWS("阴影", -100f..100f, { it.shadows }, { a, v -> a.copy(shadows = v) }),
    SHARPEN("锐化", 0f..100f, { it.sharpen }, { a, v -> a.copy(sharpen = v) }),
    DENOISE("降噪", 0f..100f, { it.denoise }, { a, v -> a.copy(denoise = v) }),
    VIGNETTE("暗角", 0f..100f, { it.vignette }, { a, v -> a.copy(vignette = v) }),
    GRAIN("颗粒", 0f..100f, { it.grain }, { a, v -> a.copy(grain = v) }),
    ;

    fun displayValue(a: Adjustments): String {
        val v = read(a).roundToInt()
        return when {
            v == 0 -> "0"
            v > 0 -> "+$v"
            else -> "$v"
        }
    }
}
