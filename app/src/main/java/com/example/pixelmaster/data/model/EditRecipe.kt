package com.example.pixelmaster.data.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次完整编辑的「配方」：滤镜 + 调色 + 曲线 + HSL + 人像参数。
 * （不含液化、局部调色等与具体画面位置绑定的操作）
 * 编辑器每次导出时自动保存，批量修图沿用；也可从自定义预设生成。
 */
data class EditRecipe(
    val presetId: String,
    val adjustments: Adjustments,
    val curves: Curves,
    val portrait: PortraitParams,
    val hsl: HslAdjustments = HslAdjustments(),
) {
    /** 人话描述，用于批量页展示。 */
    fun describe(): String {
        val parts = mutableListOf<String>()
        val preset = FilterPresets.byId(presetId)
        if (preset.id != FilterPresets.original.id) parts.add("滤镜·${preset.name}")
        val adjCount = listOf(
            adjustments.exposure, adjustments.contrast, adjustments.saturation,
            adjustments.temperature, adjustments.tint, adjustments.highlights,
            adjustments.shadows, adjustments.sharpen, adjustments.denoise,
            adjustments.vignette, adjustments.grain,
        ).count { it != 0f }
        if (adjCount > 0) parts.add("调整${adjCount}项")
        if (!curves.isDefault) parts.add("曲线")
        if (!hsl.isDefault) parts.add("HSL")
        val portraitCount = listOf(
            portrait.smooth, portrait.slimFace, portrait.brightEyes,
            portrait.whitenTeeth, portrait.evenTone,
        ).count { it > 0f }
        if (portraitCount > 0) parts.add("人像${portraitCount}项")
        return if (parts.isEmpty()) "原图（无调整）" else parts.joinToString(" · ")
    }
}

/** Adjustments / Curves / PortraitParams / HslAdjustments 的 JSON 读写，预设与配方共用。 */
internal object RecipeJson {

    fun adjustmentsToJson(a: Adjustments): JSONObject = JSONObject().apply {
        put("exposure", a.exposure)
        put("contrast", a.contrast)
        put("saturation", a.saturation)
        put("temperature", a.temperature)
        put("tint", a.tint)
        put("highlights", a.highlights)
        put("shadows", a.shadows)
        put("sharpen", a.sharpen)
        put("denoise", a.denoise)
        put("vignette", a.vignette)
        put("grain", a.grain)
    }

    fun adjustmentsFromJson(o: JSONObject?): Adjustments = if (o == null) Adjustments() else Adjustments(
        exposure = o.optDouble("exposure", 0.0).toFloat(),
        contrast = o.optDouble("contrast", 0.0).toFloat(),
        saturation = o.optDouble("saturation", 0.0).toFloat(),
        temperature = o.optDouble("temperature", 0.0).toFloat(),
        tint = o.optDouble("tint", 0.0).toFloat(),
        highlights = o.optDouble("highlights", 0.0).toFloat(),
        shadows = o.optDouble("shadows", 0.0).toFloat(),
        sharpen = o.optDouble("sharpen", 0.0).toFloat().coerceIn(0f, 100f),
        denoise = o.optDouble("denoise", 0.0).toFloat().coerceIn(0f, 100f),
        vignette = o.optDouble("vignette", 0.0).toFloat().coerceIn(0f, 100f),
        grain = o.optDouble("grain", 0.0).toFloat().coerceIn(0f, 100f),
    )

    fun curvesToJson(c: Curves): JSONObject = JSONObject().apply {
        put("rgb", pointsToJson(c.rgb))
        put("red", pointsToJson(c.red))
        put("green", pointsToJson(c.green))
        put("blue", pointsToJson(c.blue))
    }

    fun curvesFromJson(o: JSONObject?): Curves = if (o == null) Curves() else Curves(
        rgb = o.optJSONArray("rgb").toPoints(),
        red = o.optJSONArray("red").toPoints(),
        green = o.optJSONArray("green").toPoints(),
        blue = o.optJSONArray("blue").toPoints(),
    )

    fun portraitToJson(p: PortraitParams): JSONObject = JSONObject().apply {
        put("smooth", p.smooth)
        put("slimFace", p.slimFace)
        put("brightEyes", p.brightEyes)
        put("whitenTeeth", p.whitenTeeth)
        put("evenTone", p.evenTone)
    }

    fun portraitFromJson(o: JSONObject?): PortraitParams = if (o == null) PortraitParams() else PortraitParams(
        smooth = o.optDouble("smooth", 0.0).toFloat(),
        slimFace = o.optDouble("slimFace", 0.0).toFloat(),
        brightEyes = o.optDouble("brightEyes", 0.0).toFloat(),
        whitenTeeth = o.optDouble("whitenTeeth", 0.0).toFloat(),
        evenTone = o.optDouble("evenTone", 0.0).toFloat(),
    )

    fun hslToJson(h: HslAdjustments): JSONObject = JSONObject().apply {
        val arr = JSONArray()
        h.bands.forEach { b ->
            arr.put(JSONArray().put(b.hueShift.toDouble()).put(b.saturation.toDouble()).put(b.luminance.toDouble()))
        }
        put("bands", arr)
    }

    fun hslFromJson(o: JSONObject?): HslAdjustments = if (o == null) HslAdjustments() else {
        val arr = o.optJSONArray("bands")
        val bands = HslBands.entries.map { i ->
            val a = arr?.optJSONArray(i.ordinal)
            if (a != null) {
                HslBand(
                    hueShift = a.optDouble(0, 0.0).toFloat(),
                    saturation = a.optDouble(1, 0.0).toFloat(),
                    luminance = a.optDouble(2, 0.0).toFloat(),
                )
            } else {
                HslBand()
            }
        }
        HslAdjustments(bands)
    }

    private fun pointsToJson(points: List<CurvePoint>): JSONArray = JSONArray().apply {
        points.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
    }

    private fun JSONArray?.toPoints(): List<CurvePoint> =
        if (this == null) Curves.defaultLine()
        else List(length()) { i ->
            val a = getJSONArray(i)
            CurvePoint(
                a.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f),
                a.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f),
            )
        }
}

object LastRecipeStore {

    private const val FILE_NAME = "last_recipe.json"

    fun load(context: Context): EditRecipe? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching null
        val json = JSONObject(file.readText())
        EditRecipe(
            presetId = json.optString("presetId", FilterPresets.original.id),
            adjustments = RecipeJson.adjustmentsFromJson(json.optJSONObject("adjustments")),
            curves = RecipeJson.curvesFromJson(json.optJSONObject("curves")),
            portrait = RecipeJson.portraitFromJson(json.optJSONObject("portrait")),
            hsl = RecipeJson.hslFromJson(json.optJSONObject("hsl")),
        )
    }.getOrNull()

    fun save(context: Context, recipe: EditRecipe) {
        val json = JSONObject().apply {
            put("presetId", recipe.presetId)
            put("adjustments", RecipeJson.adjustmentsToJson(recipe.adjustments))
            put("curves", RecipeJson.curvesToJson(recipe.curves))
            put("portrait", RecipeJson.portraitToJson(recipe.portrait))
            put("hsl", RecipeJson.hslToJson(recipe.hsl))
        }
        File(context.filesDir, FILE_NAME).writeText(json.toString())
    }
}
