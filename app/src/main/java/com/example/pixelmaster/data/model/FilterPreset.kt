package com.example.pixelmaster.data.model

import android.graphics.ColorMatrix

/**
 * 滤镜预设 = 基础调色偏移 + 可选的通道颜色矩阵（在调色之后应用）。
 */
data class FilterPreset(
    val id: String,
    val name: String,
    val base: Adjustments = Adjustments(),
    val channelMatrix: ColorMatrix? = null,
)

object FilterPresets {

    private fun matrix(vararg rows: FloatArray): ColorMatrix {
        require(rows.size == 4) { "需要 4 行（RGBA）" }
        val values = FloatArray(20)
        rows.forEachIndexed { i, row ->
            require(row.size == 5)
            row.copyInto(values, i * 5)
        }
        return ColorMatrix(values)
    }

    val original = FilterPreset("original", "原图")

    val all: List<FilterPreset> = listOf(
        original,
        FilterPreset("fresh", "清新", base = Adjustments(exposure = 5f, temperature = -8f, saturation = 8f)),
        FilterPreset(
            id = "film", name = "胶片",
            base = Adjustments(exposure = 4f, contrast = -8f, saturation = -18f),
            channelMatrix = matrix(
                floatArrayOf(0.94f, 0f, 0f, 0f, 0.050f),
                floatArrayOf(0f, 0.94f, 0f, 0f, 0.050f),
                floatArrayOf(0f, 0f, 0.94f, 0f, 0.070f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
        FilterPreset("mono", "黑白", base = Adjustments(saturation = -100f, contrast = 12f)),
        FilterPreset(
            id = "retro", name = "复古",
            base = Adjustments(temperature = 10f, contrast = 5f, saturation = -10f),
            channelMatrix = matrix(
                floatArrayOf(0.90f, 0.08f, 0.02f, 0f, 0.030f),
                floatArrayOf(0.05f, 0.85f, 0.03f, 0f, 0.020f),
                floatArrayOf(0.00f, 0.05f, 0.75f, 0f, 0.020f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
        FilterPreset("cool", "冷调", base = Adjustments(temperature = -22f, saturation = 4f, contrast = 4f)),
        FilterPreset("warm", "暖阳", base = Adjustments(temperature = 22f, exposure = 3f)),
        FilterPreset(
            id = "japanese", name = "日系",
            base = Adjustments(exposure = 8f, contrast = -12f, saturation = -8f, temperature = -4f),
            channelMatrix = matrix(
                floatArrayOf(0.96f, 0f, 0f, 0f, 0.030f),
                floatArrayOf(0f, 0.98f, 0f, 0f, 0.030f),
                floatArrayOf(0f, 0f, 0.94f, 0f, 0.050f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
        FilterPreset(
            id = "cyber", name = "赛博",
            base = Adjustments(contrast = 12f, saturation = 10f),
            channelMatrix = matrix(
                floatArrayOf(1.15f, 0f, 0.05f, 0f, 0f),
                floatArrayOf(0f, 0.90f, 0f, 0f, 0f),
                floatArrayOf(0.05f, 0f, 1.25f, 0f, -0.02f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
        FilterPreset(
            id = "cream", name = "奶油",
            base = Adjustments(exposure = 6f, temperature = 6f, saturation = -8f),
            channelMatrix = matrix(
                floatArrayOf(1.02f, 0f, 0f, 0f, 0.020f),
                floatArrayOf(0f, 1.00f, 0f, 0f, 0.015f),
                floatArrayOf(0f, 0f, 0.95f, 0f, 0.040f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
        FilterPreset(
            id = "hongkong", name = "港风",
            base = Adjustments(contrast = 15f, saturation = 5f, temperature = 5f),
            channelMatrix = matrix(
                floatArrayOf(1.10f, 0f, 0f, 0f, 0.010f),
                floatArrayOf(0f, 0.95f, 0.05f, 0f, 0f),
                floatArrayOf(0f, 0.10f, 0.85f, 0f, 0f),
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
            ),
        ),
    )

    fun byId(id: String): FilterPreset = all.firstOrNull { it.id == id } ?: original
}
