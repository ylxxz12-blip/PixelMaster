package com.example.pixelmaster.data.model

/**
 * 径向局部调色：椭圆羽化蒙版内（或反向选中外部）单独调整曝光/饱和度/色温。
 * 位置与半径为归一化坐标，随图片分辨率自适应。
 */
data class LocalAdjust(
    val enabled: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radius: Float = 0.35f,
    val feather: Float = 0.35f,
    val invert: Boolean = false,
    val exposure: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
) {
    val isActive: Boolean
        get() = enabled && (exposure != 0f || saturation != 0f || temperature != 0f)

    fun coerced(): LocalAdjust = copy(
        centerX = centerX.coerceIn(0f, 1f),
        centerY = centerY.coerceIn(0f, 1f),
        radius = radius.coerceIn(0.05f, 0.9f),
        feather = feather.coerceIn(0.02f, 1f),
        exposure = Adjustments.clamp(exposure),
        saturation = Adjustments.clamp(saturation),
        temperature = Adjustments.clamp(temperature),
    )
}
