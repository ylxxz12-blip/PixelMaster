package com.example.pixelmaster.image

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * 人脸检测结果（坐标基准：传入的原始位图）。
 */
data class FaceInfo(
    val box: RectF,
    val leftCheek: PointF?,
    val rightCheek: PointF?,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val noseBase: PointF?,
    val mouthLeft: PointF?,
    val mouthRight: PointF?,
    val mouthBottom: PointF?,
    val faceWidth: Float,
) {
    fun scaled(s: Float): FaceInfo = FaceInfo(
        box = RectF(box.left * s, box.top * s, box.right * s, box.bottom * s),
        leftCheek = leftCheek?.let { PointF(it.x * s, it.y * s) },
        rightCheek = rightCheek?.let { PointF(it.x * s, it.y * s) },
        leftEye = leftEye?.let { PointF(it.x * s, it.y * s) },
        rightEye = rightEye?.let { PointF(it.x * s, it.y * s) },
        noseBase = noseBase?.let { PointF(it.x * s, it.y * s) },
        mouthLeft = mouthLeft?.let { PointF(it.x * s, it.y * s) },
        mouthRight = mouthRight?.let { PointF(it.x * s, it.y * s) },
        mouthBottom = mouthBottom?.let { PointF(it.x * s, it.y * s) },
        faceWidth = faceWidth * s,
    )
}

/**
 * ML Kit 人脸检测封装（离线模型）。检测失败时返回空列表而不是抛异常，
 * 人像功能对无人脸照片静默降级。
 */
object FaceDetectorBridge {

    suspend fun detect(source: Bitmap): List<FaceInfo> = withContext(Dispatchers.IO) {
        val maxDim = 1280
        val longest = max(source.width, source.height)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            val faces = suspendCancellableCoroutine { cont ->
                detector.process(InputImage.fromBitmap(input, 0))
                    .addOnSuccessListener { list -> if (cont.isActive) cont.resume(list) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
            }
            val inv = 1f / scale
            faces.mapNotNull { it.toFaceInfo(inv) }
        } finally {
            detector.close()
        }
    }

    private fun Face.toFaceInfo(inv: Float): FaceInfo? {
        val b = boundingBox ?: return null
        return FaceInfo(
            box = RectF(b.left * inv, b.top * inv, b.right * inv, b.bottom * inv),
            leftCheek = landmark(FaceLandmark.LEFT_CHEEK, inv),
            rightCheek = landmark(FaceLandmark.RIGHT_CHEEK, inv),
            leftEye = landmark(FaceLandmark.LEFT_EYE, inv),
            rightEye = landmark(FaceLandmark.RIGHT_EYE, inv),
            noseBase = landmark(FaceLandmark.NOSE_BASE, inv),
            mouthLeft = landmark(FaceLandmark.MOUTH_LEFT, inv),
            mouthRight = landmark(FaceLandmark.MOUTH_RIGHT, inv),
            mouthBottom = landmark(FaceLandmark.MOUTH_BOTTOM, inv),
            faceWidth = b.width() * inv,
        )
    }

    private fun Face.landmark(type: Int, inv: Float): PointF? =
        getLandmark(type)?.position?.let { PointF(it.x * inv, it.y * inv) }
}
