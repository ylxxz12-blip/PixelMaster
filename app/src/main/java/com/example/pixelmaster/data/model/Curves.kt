package com.example.pixelmaster.data.model

import kotlin.math.sqrt

/**
 * 曲线通道。RGB 为主曲线，其余为单色通道曲线（先应用主曲线再应用通道曲线）。
 */
enum class CurveChannel(val label: String) {
    RGB("RGB"), RED("红"), GREEN("绿"), BLUE("蓝")
}

data class CurvePoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "曲线点必须在 0..1 归一化区间" }
    }
}

/**
 * RGB 曲线调整状态。每条曲线由可拖拽控制点组成，端点固定在 x=0/1。
 */
data class Curves(
    val rgb: List<CurvePoint> = defaultLine(),
    val red: List<CurvePoint> = defaultLine(),
    val green: List<CurvePoint> = defaultLine(),
    val blue: List<CurvePoint> = defaultLine(),
) {
    val isDefault: Boolean get() = this == Curves()

    fun pointsOf(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun withPoints(channel: CurveChannel, points: List<CurvePoint>): Curves = when (channel) {
        CurveChannel.RGB -> copy(rgb = points)
        CurveChannel.RED -> copy(red = points)
        CurveChannel.GREEN -> copy(green = points)
        CurveChannel.BLUE -> copy(blue = points)
    }

    /** 生成 256 项 LUT（0..1 归一化输出）。 */
    fun lut(channel: CurveChannel): FloatArray = monotoneLut(pointsOf(channel))

    companion object {
        fun defaultLine(): List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/**
 * 单调三次 Hermite 插值（Fritsch–Carlson 切线限制），避免控制点间过冲，
 * 保证 LUT 每个区间形状稳定、不产生色带。
 */
fun monotoneLut(points: List<CurvePoint>): FloatArray {
    val lut = FloatArray(256)
    if (points.isEmpty()) {
        for (i in 0..255) lut[i] = i / 255f
        return lut
    }
    if (points.size == 1) {
        java.util.Arrays.fill(lut, points[0].y)
        return lut
    }

    val pts = points.sortedBy { it.x }
    val n = pts.size
    val xs = FloatArray(n) { pts[it].x }
    val ys = FloatArray(n) { pts[it].y }

    // 快速路径：两端点直线即恒等曲线
    if (n == 2 && xs[0] <= 0.001f && xs[1] >= 0.999f && ys[0] <= 0.001f && ys[1] >= 0.999f &&
        kotlin.math.abs(ys[1] - ys[0] - (xs[1] - xs[0])) < 0.002f
    ) {
        for (i in 0..255) lut[i] = i / 255f
        return lut
    }

    // 差分与切线
    val d = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        val h = xs[i + 1] - xs[i]
        d[i] = if (h > 1e-6f) (ys[i + 1] - ys[i]) / h else 0f
    }
    val m = FloatArray(n)
    m[0] = d[0]
    m[n - 1] = d[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    var seg = 0
    for (i in 0..255) {
        val x = i / 255f
        while (seg < n - 2 && x > xs[seg + 1]) seg++
        val h = xs[seg + 1] - xs[seg]
        if (h <= 1e-6f) {
            lut[i] = ys[seg]
            continue
        }
        val t = ((x - xs[seg]) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        val h00 = 2f * t3 - 3f * t2 + 1f
        val h10 = t3 - 2f * t2 + t
        val h01 = -2f * t3 + 3f * t2
        val h11 = t3 - t2
        val y = h00 * ys[seg] + h10 * h * m[seg] + h01 * ys[seg + 1] + h11 * h * m[seg + 1]
        lut[i] = y.coerceIn(0f, 1f)
    }
    return lut
}
