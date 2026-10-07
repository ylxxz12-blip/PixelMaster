package com.example.pixelmaster.data.model

/**
 * HSL 分离调色：8 个色相分区，各自独立调整色相偏移 / 饱和度 / 明度。
 */
data class HslBand(
    val hueShift: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    val isDefault: Boolean get() = this == HslBand()
}

data class HslAdjustments(
    val bands: List<HslBand> = List(HslBands.entries.size) { HslBand() },
) {
    val isDefault: Boolean get() = bands.all { it.isDefault }

    fun band(kind: HslBands): HslBand =
        bands.getOrElse(kind.ordinal) { HslBand() }

    fun with(kind: HslBands, band: HslBand): HslAdjustments = copy(
        bands = bands.toMutableList().also { it[kind.ordinal] = band }
    )
}

enum class HslBands(val label: String, val hue: Float, val colorHex: Long) {
    RED("红", 0f, 0xFFE53935),
    ORANGE("橙", 30f, 0xFFFB8C00),
    YELLOW("黄", 60f, 0xFFFDD835),
    GREEN("绿", 120f, 0xFF43A047),
    AQUA("浅绿", 180f, 0xFF26C6DA),
    BLUE("蓝", 240f, 0xFF1E88E5),
    PURPLE("紫", 285f, 0xFF8E24AA),
    MAGENTA("洋红", 320f, 0xFFD81B60),
}
