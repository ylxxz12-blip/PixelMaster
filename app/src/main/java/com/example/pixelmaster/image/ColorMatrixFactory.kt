package com.example.pixelmaster.image

import android.graphics.ColorMatrix
import com.example.pixelmaster.data.model.Adjustments
import kotlin.math.pow

/**
 * 调色管线：曝光 → 对比度 → 饱和度 → 色温/色调 → 滤镜通道矩阵。
 * 全部为 4x5 线性颜色矩阵运算，预览（GPU ColorFilter）与导出（软件 Canvas）共用同一结果，
 * 保证所见即所得。
 */
object ColorMatrixFactory {

    fun build(adjustments: Adjustments, channelMatrix: ColorMatrix? = null): ColorMatrix {
        var m = ColorMatrix()

        // 曝光：乘性调整，±100 对应约 ±1.5 挡
        val exposureScale = 2f.pow(adjustments.exposure / 100f * 1.5f)
        m = concat(m, scale3(exposureScale, exposureScale, exposureScale))

        // 对比度：绕中灰 0.5 缩放
        val c = (1f + adjustments.contrast / 100f).coerceIn(0f, 2f)
        val offset = 0.5f * (1f - c)
        m = concat(
            m,
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, offset,
                    0f, c, 0f, 0f, offset,
                    0f, 0f, c, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        // 饱和度
        val sat = (1f + adjustments.saturation / 100f).coerceIn(0f, 2f)
        m = concat(m, ColorMatrix().apply { setSaturation(sat) })

        // 色温：暖 => 提 R 压 B；冷反之
        val t = adjustments.temperature / 100f
        if (t != 0f) {
            m = concat(m, scale3(1f + 0.22f * t, 1f, 1f - 0.22f * t))
        }

        // 色调：正值偏品红（压 G），负值偏绿
        val g = adjustments.tint / 100f
        if (g != 0f) {
            m = concat(m, scale3(1f + 0.06f * g, 1f - 0.18f * g, 1f + 0.06f * g))
        }

        channelMatrix?.let { m = concat(m, it) }
        return m
    }

    /** result = next * current，即 current 先应用、next 后应用。 */
    private fun concat(current: ColorMatrix, next: ColorMatrix): ColorMatrix =
        ColorMatrix().apply { setConcat(next, current) }

    private fun scale3(r: Float, g: Float, b: Float): ColorMatrix = ColorMatrix(
        floatArrayOf(
            r, 0f, 0f, 0f, 0f,
            0f, g, 0f, 0f, 0f,
            0f, 0f, b, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}
