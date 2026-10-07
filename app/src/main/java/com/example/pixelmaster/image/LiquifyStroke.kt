package com.example.pixelmaster.image

/**
 * 液化笔划：以 (cx, cy) 为中心、radius 为半径的圆形邻域内，
 * 像素沿 (dx, dy) 方向位移（位移量随距离平滑衰减到 0）。
 */
data class LiquifyStroke(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val dx: Float,
    val dy: Float,
) {
    fun scaled(s: Float): LiquifyStroke = LiquifyStroke(
        cx = cx * s,
        cy = cy * s,
        radius = radius * s,
        dx = dx * s,
        dy = dy * s,
    )
}
