package com.example.pixelmaster.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 图片加载：
 * - 使用 ImageDecoder（自动应用 EXIF 方向，不会出现横竖颠倒）
 * - 强制软件位图，保证后续可用 Canvas 绘制导出
 * - 最长边限制在 maxDim 内，防止大图 OOM
 */
object ImageLoader {

    // 编辑源图上限：4096 已覆盖绝大多数手机直出照片（≈1200 万像素），兼顾内存安全
    const val MAX_DIMENSION = 4096

    suspend fun load(context: Context, uri: Uri, maxDim: Int = MAX_DIMENSION): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxDim) {
                        decoder.setTargetSampleSize(longest / maxDim)
                    }
                }
            }
        }

    /** 从源图中心裁出一个正方形小图，用作滤镜缩略图。 */
    fun thumbnail(source: Bitmap, size: Int = 128): Bitmap {
        val edge = minOf(source.width, source.height)
        val x = (source.width - edge) / 2
        val y = (source.height - edge) / 2
        val square = Bitmap.createBitmap(source, x, y, edge, edge)
        if (edge <= size) return square
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        if (scaled != square) square.recycle()
        return scaled
    }

    /** 不经编辑器、直接按 Uri 生成小图（批量修图的列表缩略图）。 */
    suspend fun loadThumbnail(context: Context, uri: Uri, size: Int = 240): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > size) {
                        decoder.setTargetSampleSize(longest / size)
                    }
                }
            }
        }
}
