package com.example.pixelmaster.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.example.pixelmaster.data.model.Adjustments
import com.example.pixelmaster.data.model.CurveChannel
import com.example.pixelmaster.data.model.Curves
import com.example.pixelmaster.data.model.HslAdjustments
import com.example.pixelmaster.data.model.HslBands
import com.example.pixelmaster.data.model.LocalAdjust
import com.example.pixelmaster.data.model.PortraitParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 一次完整编辑的渲染规格。faces / liquify / local 的坐标必须与传入 render 的位图一致。
 */
class RenderSpec(
    val adjustments: Adjustments,
    val channelMatrix: ColorMatrix?,
    val portrait: PortraitParams,
    val faces: List<FaceInfo>,
    val curves: Curves = Curves(),
    val liquify: List<LiquifyStroke> = emptyList(),
    val hsl: HslAdjustments = HslAdjustments(),
    val local: LocalAdjust? = null,
)

/**
 * 软件渲染管线：预览（降采样位图）与导出（全分辨率）共用同一实现，保证所见即所得。
 *
 * 顺序：手动液化 → 瘦脸变形 → 磨皮 → 眼部/牙齿/匀肤 → 降噪 → 线性调色(ColorMatrix)
 *       → 曲线(LUT) → HSL → 局部调色 → 高光阴影 → 锐化 → 暗角/颗粒。
 */
object ImagePipeline {

    suspend fun render(source: Bitmap, spec: RenderSpec, maxDim: Int? = null): Bitmap =
        withContext(Dispatchers.Default) {
            var bmp = source
            if (maxDim != null) {
                val longest = max(bmp.width, bmp.height)
                if (longest > maxDim) {
                    val scale = maxDim.toFloat() / longest
                    bmp = Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                }
            }

            if (spec.liquify.isNotEmpty()) {
                bmp = applyLiquify(bmp, spec.liquify)
            }
            val p = spec.portrait
            if (spec.faces.isNotEmpty()) {
                if (p.slimFace > 0f) bmp = slimFaces(bmp, spec.faces, p.slimFace / 100f)
                if (p.smooth > 0f) bmp = smoothSkin(bmp, spec.faces, p.smooth / 100f)
                if (p.brightEyes > 0f) bmp = enhanceEyes(bmp, spec.faces, p.brightEyes / 100f)
                if (p.whitenTeeth > 0f) bmp = whitenTeeth(bmp, spec.faces, p.whitenTeeth / 100f)
                if (p.evenTone > 0f) bmp = evenSkinTone(bmp, spec.faces, p.evenTone / 100f)
            }
            if (spec.adjustments.denoise > 0f) {
                bmp = denoisePass(bmp, spec.adjustments.denoise / 100f)
            }

            if (!(spec.adjustments.isDefault && spec.channelMatrix == null)) {
                bmp = applyMatrix(bmp, ColorMatrixFactory.build(spec.adjustments, spec.channelMatrix))
            }
            if (!spec.curves.isDefault) {
                bmp = applyCurves(bmp, spec.curves)
            }
            if (!spec.hsl.isDefault) {
                bmp = hslPass(bmp, spec.hsl)
            }
            spec.local?.takeIf { it.isActive }?.let {
                bmp = localPass(bmp, it)
            }
            if (spec.adjustments.hasAdvanced) {
                bmp = advancedPass(bmp, spec.adjustments)
            }
            bmp
        }

    // ---------- 曲线（LUT） ----------

    private fun isIdentityLut(lut: FloatArray): Boolean {
        for (i in lut.indices) {
            if (abs(lut[i] - i / 255f) > 0.002f) return false
        }
        return true
    }

    private fun applyCurves(src: Bitmap, curves: Curves): Bitmap {
        val lutM = curves.lut(CurveChannel.RGB)
        val lutR = curves.lut(CurveChannel.RED)
        val lutG = curves.lut(CurveChannel.GREEN)
        val lutB = curves.lut(CurveChannel.BLUE)
        val masterIdentity = isIdentityLut(lutM)
        if (masterIdentity && isIdentityLut(lutR) && isIdentityLut(lutG) && isIdentityLut(lutB)) {
            return src
        }
        // 预合成「主曲线 × 通道曲线」，逐像素仅一次查表
        fun compose(channel: FloatArray): IntArray {
            val out = IntArray(256)
            for (i in 0..255) {
                val mid = if (masterIdentity) i else (lutM[i] * 255f).roundToInt().coerceIn(0, 255)
                out[i] = (channel[mid] * 255f).roundToInt().coerceIn(0, 255)
            }
            return out
        }
        val cr = compose(lutR)
        val cg = compose(lutG)
        val cb = compose(lutB)

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = cr[c shr 16 and 0xFF]
            val g = cg[c shr 8 and 0xFF]
            val b = cb[c and 0xFF]
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 手动液化（半分辨率位移场 + 反向采样） ----------

    private fun applyLiquify(src: Bitmap, strokes: List<LiquifyStroke>): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // 位移场缓冲（半分辨率，天然平滑）
        val gw = w / 2 + 1
        val gh = h / 2 + 1
        val dispX = FloatArray(gw * gh)
        val dispY = FloatArray(gw * gh)
        for (s in strokes) {
            if (s.radius <= 1f || (s.dx == 0f && s.dy == 0f)) continue
            val gx0 = ((s.cx - s.radius) / 2f).toInt().coerceIn(0, gw - 1)
            val gx1 = ((s.cx + s.radius) / 2f).toInt().coerceIn(0, gw - 1)
            val gy0 = ((s.cy - s.radius) / 2f).toInt().coerceIn(0, gh - 1)
            val gy1 = ((s.cy + s.radius) / 2f).toInt().coerceIn(0, gh - 1)
            val r2 = s.radius * s.radius
            for (gy in gy0..gy1) {
                for (gx in gx0..gx1) {
                    val x = gx * 2f
                    val y = gy * 2f
                    val ddx = x - s.cx
                    val ddy = y - s.cy
                    val d2 = ddx * ddx + ddy * ddy
                    if (d2 < r2) {
                        val t = 1f - sqrt(d2) / s.radius
                        val f = t * t
                        val i = gy * gw + gx
                        dispX[i] += s.dx * f
                        dispY[i] += s.dy * f
                    }
                }
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val dx = sampleMask(dispX, gw, gh, x / 2f, y / 2f)
                val dy = sampleMask(dispY, gw, gh, x / 2f, y / 2f)
                out[i] = if (abs(dx) < 0.01f && abs(dy) < 0.01f) {
                    px[i]
                } else {
                    // dst(p) = src(p - disp)：像素沿拖拽方向移动
                    sampleBilinear(px, w, h, x - dx, y - dy)
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 降噪 / HSL / 局部调色 ----------

    /** 全图低分辨率模糊层（供降噪等复用），返回与原图同尺寸的像素数组。 */
    private fun blurredLayer(src: Bitmap): IntArray {
        val w = src.width
        val h = src.height
        val smallScale = 512f / max(w, h)
        val sw = max(1, (w * smallScale).toInt())
        val sh = max(1, (h * smallScale).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        boxBlurColor(sp, sw, sh, radius = max(1, min(6, max(sw, sh) / 70)), passes = 2)
        val blurred = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(sp, sw, sh, Bitmap.Config.ARGB_8888),
            w, h, true,
        )
        val bp = IntArray(w * h)
        blurred.getPixels(bp, 0, w, 0, 0, w, h)
        return bp
    }

    /** 降噪：模糊层与原图按边缘差异加权混合，保细节去噪点。 */
    private fun denoisePass(src: Bitmap, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val bp = blurredLayer(src)
        val out = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val b = bp[i]
            val dr = abs(rOf(c) - rOf(b)) / 255f
            val dg = abs(gOf(c) - gOf(b)) / 255f
            val db = abs(bOf(c) - bOf(b)) / 255f
            val diff = max(dr, max(dg, db))
            val weight = strength / (1f + diff * 6f)
            out[i] = pack(
                rOf(c) * (1 - weight) + rOf(b) * weight,
                gOf(c) * (1 - weight) + gOf(b) * weight,
                bOf(c) * (1 - weight) + bOf(b) * weight,
            )
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** HSL：按色相分区加权调整色相/饱和度/明度（带宽 ±25° 平滑过渡）。 */
    private fun hslPass(src: Bitmap, hsl: HslAdjustments): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val centers = FloatArray(HslBands.entries.size) { HslBands.entries[it].hue }
        val shifts = FloatArray(centers.size)
        val satAdj = FloatArray(centers.size)
        val lumAdj = FloatArray(centers.size)
        var anySat = false
        var anyLum = false
        HslBands.entries.forEachIndexed { i, kind ->
            val band = hsl.band(kind)
            shifts[i] = band.hueShift / 100f * 30f
            satAdj[i] = band.saturation / 100f
            lumAdj[i] = band.luminance / 100f * 0.4f
            if (band.saturation != 0f) anySat = true
            if (band.luminance != 0f) anyLum = true
        }

        val hsv = FloatArray(3)
        val rgb = IntArray(3)
        for (i in px.indices) {
            val c = px[i]
            val r = rOf(c) / 255f
            val g = gOf(c) / 255f
            val b = bOf(c) / 255f
            rgbToHsv(r, g, b, hsv)
            val hue = hsv[0] * 360f
            val sat = hsv[1]
            val value = hsv[2]

            var wShift = 0f
            var wSat = 0f
            var wLum = 0f
            for (k in centers.indices) {
                var d = abs(hue - centers[k])
                if (d > 180f) d = 360f - d
                if (d < 25f) {
                    val t = 1f - d / 25f
                    val weight = t * t * (3f - 2f * t)
                    wShift += weight * shifts[k]
                    wSat += weight * satAdj[k]
                    wLum += weight * lumAdj[k]
                }
            }
            if (wShift == 0f && wSat == 0f && wLum == 0f) continue

            var newHue = (hue + wShift) % 360f
            if (newHue < 0f) newHue += 360f
            val newSat = (if (anySat) (sat * (1f + wSat)).coerceIn(0f, 1f) else sat)
            val newValue = (if (anyLum) (value * (1f + wLum)).coerceIn(0f, 1f) else value)
            hsvToRgb(newHue / 360f, newSat, newValue, rgb)
            px[i] = pack(rgb[0] * 255f, rgb[1] * 255f, rgb[2] * 255f)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun rgbToHsv(r: Float, g: Float, b: Float, out: FloatArray) {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val v = maxC
        val s = if (maxC <= 0f) 0f else delta / maxC
        val h: Float = when {
            delta <= 0f -> 0f
            maxC == r -> ((g - b) / delta) % 6f
            maxC == g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        var hue = h * 60f
        if (hue < 0f) hue += 360f
        out[0] = hue / 360f
        out[1] = s
        out[2] = v
    }

    private fun hsvToRgb(hNorm: Float, s: Float, v: Float, out: IntArray) {
        val h = hNorm * 6f
        val i = h.toInt().coerceIn(0, 5)
        val f = h - i
        val p = v * (1f - s)
        val q = v * (1f - s * f)
        val t = v * (1f - s * (1f - f))
        when (i) {
            0 -> { out[0] = (v * 255f).toInt(); out[1] = (t * 255f).toInt(); out[2] = (p * 255f).toInt() }
            1 -> { out[0] = (q * 255f).toInt(); out[1] = (v * 255f).toInt(); out[2] = (p * 255f).toInt() }
            2 -> { out[0] = (p * 255f).toInt(); out[1] = (v * 255f).toInt(); out[2] = (t * 255f).toInt() }
            3 -> { out[0] = (p * 255f).toInt(); out[1] = (q * 255f).toInt(); out[2] = (v * 255f).toInt() }
            4 -> { out[0] = (t * 255f).toInt(); out[1] = (p * 255f).toInt(); out[2] = (v * 255f).toInt() }
            else -> { out[0] = (v * 255f).toInt(); out[1] = (p * 255f).toInt(); out[2] = (q * 255f).toInt() }
        }
    }

    /** 径向局部调色：羽化蒙版内（或反转选中外部）单独应用曝光/饱和度/色温。 */
    private fun localPass(src: Bitmap, local: LocalAdjust): Bitmap {
        val w = src.width
        val h = src.height
        val cx = local.centerX * w
        val cy = local.centerY * h
        val radius = local.radius * min(w, h)
        if (radius <= 1f) return src
        val inner = radius * (1f - local.feather.coerceIn(0.02f, 1f))
        val band = if (inner < radius) radius - inner else 1f

        val expMul = exp2f(local.exposure / 100f * 0.8f)
        val satMul = 1f + local.saturation / 100f
        val tWarm = local.temperature / 100f
        val rMul = 1f + 0.22f * tWarm
        val bMul = 1f - 0.22f * tWarm

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        fun maskAt(x: Float, y: Float): Float {
            val d = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (d >= radius) return if (local.invert) 1f else 0f
            if (d <= inner) return if (local.invert) 0f else 1f
            val t = 1f - (d - inner) / band
            val m = t * t * (3f - 2f * t)
            return if (local.invert) 1f - m else m
        }

        // 蒙版非零区域仅限圆内（或反转时全图），反转时需要全图循环
        val x0 = if (local.invert) 0 else max(0, (cx - radius).toInt())
        val x1 = if (local.invert) w - 1 else min(w - 1, (cx + radius).toInt())
        val y0 = if (local.invert) 0 else max(0, (cy - radius).toInt())
        val y1 = if (local.invert) h - 1 else min(h - 1, (cy + radius).toInt())

        for (y in y0..y1) {
            for (x in x0..x1) {
                val m = maskAt(x.toFloat(), y.toFloat())
                if (m <= 0.002f) continue
                val i = y * w + x
                val c = px[i]
                val r0 = rOf(c); val g0 = gOf(c); val b0 = bOf(c)
                // 局部效果
                var r = r0 * expMul * rMul
                var g = g0 * expMul
                var b = b0 * expMul * bMul
                val luma = lumaOf(c)
                r = luma + (r - luma) * satMul
                g = luma + (g - luma) * satMul
                b = luma + (b - luma) * satMul
                // 与原图按蒙版混合
                px[i] = pack(
                    r0 * (1 - m) + r * m,
                    g0 * (1 - m) + g * m,
                    b0 * (1 - m) + b * m,
                )
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun exp2f(x: Float): Float = kotlin.math.exp(x * 0.6931472f)

    // ---------- 几何变换（裁剪模式用，直接物化进位图） ----------

    fun crop(source: Bitmap, left: Int, top: Int, width: Int, height: Int): Bitmap {
        val l = left.coerceIn(0, source.width - 1)
        val t = top.coerceIn(0, source.height - 1)
        val w = width.coerceIn(1, source.width - l)
        val h = height.coerceIn(1, source.height - t)
        return Bitmap.createBitmap(source, l, t, w, h)
    }

    fun rotate90(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    fun flipHorizontal(source: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** 瑕疵修复：用外圈平均色填充点击处圆盘，边缘渐变混合。 */
    fun heal(source: Bitmap, cx: Float, cy: Float, radius: Float): Bitmap {
        val w = source.width
        val h = source.height
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)

        val x0 = (cx - radius * 2f).toInt().coerceIn(0, w - 1)
        val x1 = (cx + radius * 2f).toInt().coerceIn(0, w - 1)
        val y0 = (cy - radius * 2f).toInt().coerceIn(0, h - 1)
        val y1 = (cy + radius * 2f).toInt().coerceIn(0, h - 1)

        // 外圈环采样均值
        var sr = 0f; var sg = 0f; var sb = 0f; var count = 0
        val ringR = radius * 1.7f
        for (i in 0 until 24) {
            val angle = i / 24f * 2f * Math.PI.toFloat()
            val sx = (cx + ringR * kotlin.math.cos(angle)).toInt().coerceIn(0, w - 1)
            val sy = (cy + ringR * kotlin.math.sin(angle)).toInt().coerceIn(0, h - 1)
            val c = px[sy * w + sx]
            sr += rOf(c); sg += gOf(c); sb += bOf(c); count++
        }
        if (count == 0) return source
        val mr = sr / count; val mg = sg / count; val mb = sb / count

        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d < radius * 1.1f) {
                    val t = (1f - d / (radius * 1.1f)).coerceIn(0f, 1f)
                    val weight = t * t * 0.9f
                    val c = px[y * w + x]
                    val nr = rOf(c) * (1 - weight) + mr * weight
                    val ng = gOf(c) * (1 - weight) + mg * weight
                    val nb = bOf(c) * (1 - weight) + mb * weight
                    px[y * w + x] = pack(nr, ng, nb)
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 人像：瘦脸（位移场变形） ----------

    private class Push(val cx: Float, val cy: Float, val radius: Float, val dx: Float, val dy: Float)

    private fun slimFaces(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val pushes = buildList {
            for (f in faces) {
                val cx = f.box.centerX()
                val cy = f.box.centerY()
                val fw = f.faceWidth
                val amp = 0.16f * fw * strength
                val left = f.leftCheek
                val right = f.rightCheek
                if (left != null) addPush(left, cx, cy, fw, amp)
                if (right != null) addPush(right, cx, cy, fw, amp)
                // 下颌两侧向内推
                val chinY = f.mouthBottom?.let { mb ->
                    f.noseBase?.let { nb -> mb.y + (mb.y - nb.y) * 0.6f }
                }
                if (chinY != null) {
                    val jr = 0.22f * fw
                    add(Push(cx - 0.20f * fw, chinY, jr, -amp * 0.7f, 0f))
                    add(Push(cx + 0.20f * fw, chinY, jr, amp * 0.7f, 0f))
                }
            }
        }
        if (pushes.isEmpty()) return src

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var dx = 0f
                var dy = 0f
                for (p in pushes) {
                    val ddx = x - p.cx
                    val ddy = y - p.cy
                    val d2 = ddx * ddx + ddy * ddy
                    val r2 = p.radius * p.radius
                    if (d2 < r2) {
                        val t = 1f - sqrt(d2) / p.radius
                        val fall = t * t
                        dx += p.dx * fall
                        dy += p.dy * fall
                    }
                }
                val i = y * w + x
                out[i] = if (dx == 0f && dy == 0f) px[i] else sampleBilinear(px, w, h, x + dx, y + dy)
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun MutableList<Push>.addPush(cheek: android.graphics.PointF, cx: Float, cy: Float, fw: Float, amp: Float) {
        var vx = cheek.x - cx
        var vy = cheek.y - cy
        val len = sqrt(vx * vx + vy * vy)
        if (len < 1f) return
        vx /= len; vy /= len
        add(
            Push(
                cx = cheek.x + vx * 0.10f * fw,
                cy = cheek.y + vy * 0.10f * fw,
                radius = 0.34f * fw,
                dx = vx * amp,
                dy = vy * amp,
            )
        )
    }

    // ---------- 人像：磨皮（小图模糊 + 皮肤掩膜边缘保持混合） ----------

    private fun smoothSkin(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val w = src.width
        val h = src.height

        // 低分辨率模糊层
        val smallScale = 480f / max(w, h)
        val sw = max(1, (w * smallScale).toInt())
        val sh = max(1, (h * smallScale).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)
        boxBlurColor(sp, sw, sh, radius = max(1, min(6, max(sw, sh) / 70)), passes = 3)
        val blurred = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(sp, sw, sh, Bitmap.Config.ARGB_8888),
            w, h, true,
        )
        val bp = IntArray(w * h)
        blurred.getPixels(bp, 0, w, 0, 0, w, h)

        // 皮肤掩膜（在低分辨率上计算再放大，足够平滑）
        val fmW = sw
        val fmH = sh
        val maskSmall = FloatArray(fmW * fmH)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val fs = fmW.toFloat() / w
        for (f in faces) {
            val bx0 = (f.box.left * fs).toInt().coerceIn(0, fmW - 1)
            val bx1 = (f.box.right * fs).toInt().coerceIn(0, fmW - 1)
            val by0 = (f.box.top * fs).toInt().coerceIn(0, fmH - 1)
            val by1 = (f.box.bottom * fs).toInt().coerceIn(0, fmH - 1)
            val cx = f.box.centerX() * fs
            val cy = f.box.centerY() * fs
            val rx = f.box.width() * fs * 0.30f
            val ry = f.box.height() * fs * 0.36f
            if (rx <= 0f || ry <= 0f) continue
            for (y in by0..by1) {
                for (x in bx0..bx1) {
                    val dx = (x - cx) / rx
                    val dy = (y - cy) / ry
                    val e = 1f - (dx * dx + dy * dy)
                    if (e > 0f) {
                        val srcX = (x / fs).toInt().coerceIn(0, w - 1)
                        val srcY = (y / fs).toInt().coerceIn(0, h - 1)
                        if (isSkin(px[srcY * w + srcX])) {
                            val i = y * fmW + x
                            if (e > maskSmall[i]) maskSmall[i] = e
                        }
                    }
                }
            }
        }
        boxBlurFloat(maskSmall, fmW, fmH, radius = max(1, min(6, max(fmW, fmH) / 70)), passes = 2)

        // 混合：|原图-模糊| 大的边缘位置保持原样
        val out = IntArray(w * h)
        val mxScale = (fmW - 1).toFloat() / max(1, w - 1)
        val myScale = (fmH - 1).toFloat() / max(1, h - 1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val m = sampleMask(maskSmall, fmW, fmH, x * mxScale, y * myScale)
                if (m > 0.02f) {
                    val c = px[i]
                    val b = bp[i]
                    val dr = (rOf(c) - rOf(b)) / 255f
                    val dg = (gOf(c) - gOf(b)) / 255f
                    val db = (bOf(c) - bOf(b)) / 255f
                    val diff = max(dr * dr, max(dg * dg, db * db)).let { sqrt(it) }
                    val weight = strength * m / (1f + diff * 7f)
                    out[i] = pack(
                        rOf(c) * (1 - weight) + rOf(b) * weight,
                        gOf(c) * (1 - weight) + gOf(b) * weight,
                        bOf(c) * (1 - weight) + bOf(b) * weight,
                    )
                } else {
                    out[i] = px[i]
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 人像：亮眼 / 白牙 / 匀肤 ----------

    private fun enhanceEyes(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (f in faces) {
            for (eye in listOfNotNull(f.leftEye, f.rightEye)) {
                applyRadial(
                    px, w, h, eye.x, eye.y, f.faceWidth * 0.105f,
                    boost = 1f + 0.22f * strength,
                    desat = 0.15f * strength,
                )
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun whitenTeeth(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (f in faces) {
            val nb = f.noseBase ?: continue
            val mb = f.mouthBottom ?: continue
            val ml = f.mouthLeft
            val mr = f.mouthRight
            val mouthWidth = if (ml != null && mr != null) {
                sqrt((ml.x - mr.x) * (ml.x - mr.x) + (ml.y - mr.y) * (ml.y - mr.y))
            } else {
                f.faceWidth * 0.35f
            }
            val tx = f.box.centerX()
            val ty = nb.y + (mb.y - nb.y) * 0.65f
            applyRadial(
                px, w, h, tx, ty, mouthWidth * 0.32f,
                boost = 1f + 0.15f * strength,
                desat = 0.75f * strength,
            )
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 圆形掩膜内的提亮 + 降饱和。 */
    private fun applyRadial(
        px: IntArray, w: Int, h: Int,
        cx: Float, cy: Float, radius: Float,
        boost: Float, desat: Float,
    ) {
        if (radius <= 0f) return
        val x0 = (cx - radius).toInt().coerceIn(0, w - 1)
        val x1 = (cx + radius).toInt().coerceIn(0, w - 1)
        val y0 = (cy - radius).toInt().coerceIn(0, h - 1)
        val y1 = (cy + radius).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = (x - cx) / radius
                val dy = (y - cy) / radius
                val d2 = dx * dx + dy * dy
                if (d2 < 1f) {
                    val f = 1f - d2
                    val i = y * w + x
                    val c = px[i]
                    val r0 = rOf(c); val g0 = gOf(c); val b0 = bOf(c)
                    val luma = lumaOf(c)
                    var r = r0 * (1 + (boost - 1f) * f)
                    var g = g0 * (1 + (boost - 1f) * f)
                    var b = b0 * (1 + (boost - 1f) * f)
                    val s = desat * f
                    r = r * (1 - s) + luma * s
                    g = g * (1 - s) + luma * s
                    b = b * (1 - s) + luma * s
                    px[i] = pack(r, g, b)
                }
            }
        }
    }

    private fun evenSkinTone(src: Bitmap, faces: List<FaceInfo>, strength: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // 每张脸的肤色均值
        data class FaceMean(val f: FaceInfo, val r: Float, val g: Float, val b: Float)
        val means = mutableListOf<FaceMean>()
        for (f in faces) {
            var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            val bx0 = f.box.left.toInt().coerceIn(0, w - 1)
            val bx1 = f.box.right.toInt().coerceIn(0, w - 1)
            val by0 = f.box.top.toInt().coerceIn(0, h - 1)
            val by1 = f.box.bottom.toInt().coerceIn(0, h - 1)
            val cx = f.box.centerX()
            val cy = f.box.centerY()
            val rx = f.box.width() * 0.30f
            val ry = f.box.height() * 0.36f
            if (rx <= 0f || ry <= 0f) continue
            var y = by0
            while (y <= by1) {
                var x = bx0
                while (x <= bx1) {
                    val dx = (x - cx) / rx
                    val dy = (y - cy) / ry
                    if (dx * dx + dy * dy < 1f) {
                        val c = px[y * w + x]
                        if (isSkin(c)) {
                            sr += rOf(c); sg += gOf(c); sb += bOf(c); n++
                        }
                    }
                    x += 4
                }
                y += 4
            }
            if (n > 0) means.add(FaceMean(f, sr / n, sg / n, sb / n))
        }
        if (means.size < 1) return src

        val gr = means.map { it.r }.average().toFloat()
        val gg = means.map { it.g }.average().toFloat()
        val gb = means.map { it.b }.average().toFloat()

        for (m in means) {
            val f = m.f
            val dr = (gr - m.r) * strength
            val dg = (gg - m.g) * strength
            val db = (gb - m.b) * strength
            val bx0 = f.box.left.toInt().coerceIn(0, w - 1)
            val bx1 = f.box.right.toInt().coerceIn(0, w - 1)
            val by0 = f.box.top.toInt().coerceIn(0, h - 1)
            val by1 = f.box.bottom.toInt().coerceIn(0, h - 1)
            val cx = f.box.centerX()
            val cy = f.box.centerY()
            val rx = f.box.width() * 0.32f
            val ry = f.box.height() * 0.38f
            for (y in by0..by1) {
                for (x in bx0..bx1) {
                    val dx = (x - cx) / rx
                    val dy = (y - cy) / ry
                    val e = 1f - (dx * dx + dy * dy)
                    if (e > 0f) {
                        val i = y * w + x
                        val c = px[i]
                        if (isSkin(c)) {
                            px[i] = pack(rOf(c) + dr * e, gOf(c) + dg * e, bOf(c) + db * e)
                        }
                    }
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 线性与进阶调色 ----------

    private fun applyMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun advancedPass(src: Bitmap, a: Adjustments): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val hl = a.highlights / 100f
        val sh = a.shadows / 100f
        var cur = px

        // 高光/阴影：基于亮度的平滑掩膜
        if (hl != 0f || sh != 0f) {
            val toned = IntArray(w * h)
            for (i in cur.indices) {
                val c = cur[i]
                val l = lumaOf(c) / 255f
                val hm = smoothStep((l - 0.45f) / 0.55f)
                val sm = 1f - smoothStep(l / 0.55f)
                val add = (hl * 0.35f * hm + sh * 0.35f * sm) * 255f
                toned[i] = pack(rOf(c) + add, gOf(c) + add, bOf(c) + add)
            }
            cur = toned
        }

        // 锐化：反锐化掩膜（四邻域均值）
        if (a.sharpen > 0f) {
            val k = a.sharpen / 100f * 1.4f
            val sharpened = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val c = cur[i]
                    val cl = cur[y * w + max(0, x - 1)]
                    val cr = cur[y * w + min(w - 1, x + 1)]
                    val cu = cur[max(0, y - 1) * w + x]
                    val cd = cur[min(h - 1, y + 1) * w + x]
                    sharpened[i] = pack(
                        rOf(c) + (rOf(c) - (rOf(cl) + rOf(cr) + rOf(cu) + rOf(cd) + 4f * rOf(c)) / 8f) * k,
                        gOf(c) + (gOf(c) - (gOf(cl) + gOf(cr) + gOf(cu) + gOf(cd) + 4f * gOf(c)) / 8f) * k,
                        bOf(c) + (bOf(c) - (bOf(cl) + bOf(cr) + bOf(cu) + bOf(cd) + 4f * bOf(c)) / 8f) * k,
                    )
                }
            }
            cur = sharpened
        }

        // 暗角 + 颗粒
        if (a.vignette > 0f || a.grain > 0f) {
            val v = a.vignette / 100f
            val g = a.grain / 100f
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    var c = cur[i]
                    var r = rOf(c); var gg = gOf(c); var b = bOf(c)
                    if (v > 0f) {
                        val nx = (x - w / 2f) / (w / 2f)
                        val ny = (y - h / 2f) / (h / 2f)
                        val d = sqrt(nx * nx + ny * ny) / 1.4142f
                        val f = smoothStep((d - 0.55f) / 0.7f)
                        val m = 1f - v * 0.85f * f
                        r *= m; gg *= m; b *= m
                    }
                    if (g > 0f) {
                        val n = hash01(x, y) - 0.5f
                        val l = lumaOf(c) / 255f
                        val wgt = 4f * l * (1f - l)
                        val amount = n * wgt * g * 70f
                        r += amount; gg += amount; b += amount
                    }
                    c = pack(r, gg, b)
                    cur[i] = c
                }
            }
        }

        return Bitmap.createBitmap(cur, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- 基础工具 ----------

    private fun rOf(c: Int): Float = (c shr 16 and 0xFF).toFloat()
    private fun gOf(c: Int): Float = (c shr 8 and 0xFF).toFloat()
    private fun bOf(c: Int): Float = (c and 0xFF).toFloat()
    private fun lumaOf(c: Int): Float = 0.299f * rOf(c) + 0.587f * gOf(c) + 0.114f * bOf(c)

    private fun pack(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun hash01(x: Int, y: Int): Float {
        var v = x * 374761393 + y * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        return ((v xor (v ushr 16)) and 0xFFFF) / 65535f
    }

    private fun isSkin(c: Int): Boolean {
        val r = rOf(c); val g = gOf(c); val b = bOf(c)
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return cb in 77f..135f && cr in 133f..180f && y > 40f
    }

    private fun sampleBilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val c00 = px[y0 * w + x0]
        val c10 = px[y0 * w + x1]
        val c01 = px[y1 * w + x0]
        val c11 = px[y1 * w + x1]
        return pack(
            rOf(c00) * (1 - fx) * (1 - fy) + rOf(c10) * fx * (1 - fy) +
                rOf(c01) * (1 - fx) * fy + rOf(c11) * fx * fy,
            gOf(c00) * (1 - fx) * (1 - fy) + gOf(c10) * fx * (1 - fy) +
                gOf(c01) * (1 - fx) * fy + gOf(c11) * fx * fy,
            bOf(c00) * (1 - fx) * (1 - fy) + bOf(c10) * fx * (1 - fy) +
                bOf(c01) * (1 - fx) * fy + bOf(c11) * fx * fy,
        )
    }

    private fun sampleMask(mask: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val x0 = x.toInt().coerceIn(0, w - 1)
        val y0 = y.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceIn(0, w - 1)
        val y1 = (y0 + 1).coerceIn(0, h - 1)
        val fx = (x - x0).coerceIn(0f, 1f)
        val fy = (y - y0).coerceIn(0f, 1f)
        val m00 = mask[y0 * w + x0]
        val m10 = mask[y0 * w + x1]
        val m01 = mask[y1 * w + x0]
        val m11 = mask[y1 * w + x1]
        return m00 * (1 - fx) * (1 - fy) + m10 * fx * (1 - fy) +
            m01 * (1 - fx) * fy + m11 * fx * fy
    }

    private fun boxBlurColor(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        if (radius < 1) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
                    val x0 = max(0, x - radius)
                    val x1 = min(w - 1, x + radius)
                    for (k in x0..x1) {
                        val c = px[y * w + k]
                        sr += rOf(c); sg += gOf(c); sb += bOf(c); n++
                    }
                    tmp[y * w + x] = pack(sr / n, sg / n, sb / n)
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
                    val y0 = max(0, y - radius)
                    val y1 = min(h - 1, y + radius)
                    for (k in y0..y1) {
                        val c = tmp[k * w + x]
                        sr += rOf(c); sg += gOf(c); sb += bOf(c); n++
                    }
                    px[y * w + x] = pack(sr / n, sg / n, sb / n)
                }
            }
        }
    }

    private fun boxBlurFloat(a: FloatArray, w: Int, h: Int, radius: Int, passes: Int) {
        if (radius < 1) return
        val tmp = FloatArray(a.size)
        repeat(passes) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0f; var n = 0
                    val x0 = max(0, x - radius)
                    val x1 = min(w - 1, x + radius)
                    for (k in x0..x1) {
                        s += a[y * w + k]; n++
                    }
                    tmp[y * w + x] = s / n
                }
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var s = 0f; var n = 0
                    val y0 = max(0, y - radius)
                    val y1 = min(h - 1, y + radius)
                    for (k in y0..y1) {
                        s += tmp[k * w + x]; n++
                    }
                    a[y * w + x] = s / n
                }
            }
        }
    }
}
