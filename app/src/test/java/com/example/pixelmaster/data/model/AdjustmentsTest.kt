package com.example.pixelmaster.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {

    @Test
    fun `默认无任何调整`() {
        assertTrue(Adjustments().isDefault)
        assertFalse(Adjustments().hasAdvanced)
    }

    @Test
    fun `plus 逐项相加并夹取范围`() {
        val a = Adjustments(exposure = 60f, contrast = -30f, sharpen = 40f)
        val b = Adjustments(exposure = 60f, contrast = -30f, sharpen = 80f)
        val sum = a + b
        assertEquals(100f, sum.exposure, 0.001f)   // 120 → 100
        assertEquals(-60f, sum.contrast, 0.001f)
        assertEquals(100f, sum.sharpen, 0.001f)    // 0..100 区间夹取
    }

    @Test
    fun `hasAdvanced 覆盖全部进阶参数`() {
        assertTrue(Adjustments(highlights = 1f).hasAdvanced)
        assertTrue(Adjustments(shadows = -1f).hasAdvanced)
        assertTrue(Adjustments(sharpen = 1f).hasAdvanced)
        assertTrue(Adjustments(denoise = 1f).hasAdvanced)
        assertTrue(Adjustments(vignette = 1f).hasAdvanced)
        assertTrue(Adjustments(grain = 1f).hasAdvanced)
        // 线性参数不触发软件管线
        assertFalse(Adjustments(exposure = 50f, saturation = -40f).hasAdvanced)
    }
}
