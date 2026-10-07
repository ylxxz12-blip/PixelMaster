package com.example.pixelmaster.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HslAndLocalTest {

    @Test
    fun `HSL 默认状态与分区读写`() {
        val hsl = HslAdjustments()
        assertTrue(hsl.isDefault)
        assertEquals(8, hsl.bands.size)

        val modified = hsl.with(HslBands.RED, HslBand(hueShift = 20f, saturation = -10f))
        assertFalse(modified.isDefault)
        assertEquals(20f, modified.band(HslBands.RED).hueShift, 0.001f)
        assertEquals(-10f, modified.band(HslBands.RED).saturation, 0.001f)
        // 其他分区不受影响
        assertTrue(modified.band(HslBands.BLUE).isDefault)
    }

    @Test
    fun `局部调色生效条件`() {
        // 未启用
        assertFalse(LocalAdjust(exposure = 50f).isActive)
        // 启用但无效果参数
        assertFalse(LocalAdjust(enabled = true).isActive)
        // 启用且有任一效果
        assertTrue(LocalAdjust(enabled = true, exposure = 10f).isActive)
        assertTrue(LocalAdjust(enabled = true, saturation = -5f).isActive)
        assertTrue(LocalAdjust(enabled = true, temperature = 3f).isActive)
    }

    @Test
    fun `局部调色参数夹取`() {
        val c = LocalAdjust(
            enabled = true, exposure = 10f,
            centerX = 1.5f, centerY = -0.3f, radius = 2f, feather = 0f,
        ).coerced()
        assertEquals(1f, c.centerX, 0.001f)
        assertEquals(0f, c.centerY, 0.001f)
        assertEquals(0.9f, c.radius, 0.001f)
        assertEquals(0.02f, c.feather, 0.001f)
    }

    @Test
    fun `人像参数生效判断`() {
        assertFalse(PortraitParams().anyActive)
        assertTrue(PortraitParams(smooth = 10f).anyActive)
        assertTrue(PortraitParams(slimFace = 1f).anyActive)
    }
}
