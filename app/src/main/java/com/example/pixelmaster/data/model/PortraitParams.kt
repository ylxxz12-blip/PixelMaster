package com.example.pixelmaster.data.model

import kotlin.math.roundToInt

/**
 * 人像精修参数，全部 0..100，0 为不启用。
 * 需要人脸检测结果配合；未检测到人脸时所有参数不生效。
 */
data class PortraitParams(
    val smooth: Float = 0f,
    val slimFace: Float = 0f,
    val brightEyes: Float = 0f,
    val whitenTeeth: Float = 0f,
    val evenTone: Float = 0f,
) {
    val anyActive: Boolean
        get() = smooth > 0f || slimFace > 0f || brightEyes > 0f || whitenTeeth > 0f || evenTone > 0f
}

enum class PortraitParam(
    val label: String,
    val read: (PortraitParams) -> Float,
    val write: (PortraitParams, Float) -> PortraitParams,
) {
    SMOOTH("磨皮", { it.smooth }, { p, v -> p.copy(smooth = v) }),
    SLIM_FACE("瘦脸", { it.slimFace }, { p, v -> p.copy(slimFace = v) }),
    BRIGHT_EYES("亮眼", { it.brightEyes }, { p, v -> p.copy(brightEyes = v) }),
    WHITEN_TEETH("白牙", { it.whitenTeeth }, { p, v -> p.copy(whitenTeeth = v) }),
    EVEN_TONE("匀肤", { it.evenTone }, { p, v -> p.copy(evenTone = v) }),
    ;

    fun displayValue(p: PortraitParams): String = read(p).roundToInt().toString()
}
