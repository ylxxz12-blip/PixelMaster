package com.example.pixelmaster.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 曲线 LUT（单调三次样条）单元测试。
 */
class CurvesTest {

    @Test
    fun `默认对角线曲线生成恒等 LUT`() {
        val lut = monotoneLut(Curves.defaultLine())
        for (i in 0..255) {
            assertTrue("LUT[$i] 偏离恒等", abs(lut[i] - i / 255f) < 0.001f)
        }
    }

    @Test
    fun `端点值与控制点一致`() {
        val points = listOf(
            CurvePoint(0f, 0.1f),
            CurvePoint(0.5f, 0.55f),
            CurvePoint(1f, 0.95f),
        )
        val lut = monotoneLut(points)
        assertTrue(abs(lut[0] - 0.1f) < 0.01f)
        assertTrue(abs(lut[255] - 0.95f) < 0.01f)
        // 中间点 x=0.5 → bin 127/128 应接近 0.55
        val mid = (lut[127] + lut[128]) / 2f
        assertTrue(abs(mid - 0.55f) < 0.02f)
    }

    @Test
    fun `单调输入生成单调 LUT（无过冲）`() {
        val points = listOf(
            CurvePoint(0f, 0f),
            CurvePoint(0.3f, 0.4f),
            CurvePoint(0.7f, 0.6f),
            CurvePoint(1f, 1f),
        )
        val lut = monotoneLut(points)
        for (i in 1 until lut.size) {
            assertTrue("LUT 在 $i 处下降", lut[i] >= lut[i - 1] - 0.0005f)
        }
        // 不过冲：上界 1，下界 0
        lut.forEach { v -> assertTrue(v in 0f..1f) }
    }

    @Test
    fun `单点曲线为常量`() {
        val lut = monotoneLut(listOf(CurvePoint(0.4f, 0.7f)))
        lut.forEach { assertEquals(0.7f, it, 0.0001f) }
    }

    @Test
    fun `isDefault 与 withPoints`() {
        val curves = Curves()
        assertTrue(curves.isDefault)
        val modified = curves.withPoints(CurveChannel.RED, listOf(CurvePoint(0f, 0.05f), CurvePoint(1f, 1f)))
        assertFalse(modified.isDefault)
        // 其他通道不受影响
        Curves.defaultLine().forEachIndexed { i, p ->
            assertEquals(p.x, modified.blue[i].x, 0.0001f)
            assertEquals(p.y, modified.blue[i].y, 0.0001f)
        }
    }
}
