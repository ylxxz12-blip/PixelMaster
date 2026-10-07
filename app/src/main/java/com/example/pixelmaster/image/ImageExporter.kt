package com.example.pixelmaster.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把渲染完成的位图保存到系统相册 Pictures/PixelMaster（MediaStore，无需权限）。
 * JPEG 输出时从原始照片拷贝 EXIF（方向归一，因为像素已内嵌旋转）。
 */
object ImageExporter {

    private val COPY_TAGS = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        asPng: Boolean,
        quality: Int,
        sourceUri: Uri? = null,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val extension = if (asPng) "png" else "jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PixelMaster_$timestamp.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, if (asPng) "image/png" else "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/PixelMaster"
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IOException("无法创建图库条目")

            context.contentResolver.openOutputStream(uri)?.use { stream ->
                val format = if (asPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                if (!bitmap.compress(format, quality, stream)) {
                    throw IOException("图片压缩失败")
                }
            } ?: throw IOException("无法打开输出流")

            if (!asPng && sourceUri != null) {
                copyExif(context, sourceUri, uri)
            }
            uri
        }
    }

    /** EXIF 拷贝失败不影响导出结果。 */
    private fun copyExif(context: Context, sourceUri: Uri, targetUri: Uri) {
        runCatching {
            val source = context.contentResolver.openInputStream(sourceUri)?.use {
                ExifInterface(it)
            } ?: return
            val fd = context.contentResolver.openFileDescriptor(targetUri, "rw")
                ?: return
            fd.use { pfd ->
                val target = ExifInterface(pfd.fileDescriptor)
                COPY_TAGS.forEach { tag ->
                    source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
                }
                // 像素已按 EXIF 方向转正，输出标记为正常方向
                target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                target.saveAttributes()
            }
        }
    }
}
