package com.example.pixelmaster.data.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户自定义预设 = 名称 + 完整配方（滤镜、调色、曲线、HSL、人像参数）。
 * 不含液化/局部调色等与画面位置绑定的操作。以 JSON 持久化在应用私有目录。
 */
data class UserPreset(
    val id: String,
    val name: String,
    val basePresetId: String,
    val adjustments: Adjustments,
    val curves: Curves = Curves(),
    val portrait: PortraitParams = PortraitParams(),
    val hsl: HslAdjustments = HslAdjustments(),
) {
    fun toRecipe(): EditRecipe =
        EditRecipe(basePresetId, adjustments, curves, portrait, hsl)
}

object UserPresetStore {

    private const val FILE_NAME = "user_presets.json"

    fun load(context: Context): List<UserPreset> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@runCatching emptyList<UserPreset>()
        val array = JSONArray(file.readText())
        List(array.length()) { index -> array.getJSONObject(index).toUserPreset() }
    }.getOrDefault(emptyList())

    fun save(context: Context, presets: List<UserPreset>) {
        val array = JSONArray()
        presets.forEach { array.put(it.toJson()) }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }

    private fun UserPreset.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("basePresetId", basePresetId)
        put("adjustments", RecipeJson.adjustmentsToJson(adjustments))
        put("curves", RecipeJson.curvesToJson(curves))
        put("portrait", RecipeJson.portraitToJson(portrait))
        put("hsl", RecipeJson.hslToJson(hsl))
    }

    private fun JSONObject.toUserPreset(): UserPreset = UserPreset(
        id = getString("id"),
        name = getString("name"),
        basePresetId = optString("basePresetId", FilterPresets.original.id),
        adjustments = RecipeJson.adjustmentsFromJson(optJSONObject("adjustments")),
        curves = RecipeJson.curvesFromJson(optJSONObject("curves")),
        portrait = RecipeJson.portraitFromJson(optJSONObject("portrait")),
        hsl = RecipeJson.hslFromJson(optJSONObject("hsl")),
    )
}
