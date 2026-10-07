package com.example.pixelmaster.ui.home

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.ui.theme.EditorSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onImagePicked: (Uri) -> Unit,
    onBatchPicked: (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onImagePicked(uri)
    }
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) onBatchPicked(uris)
    }
    // RAW/DNG：相册选择器不显示 RAW，用系统文件选择器
    val rawLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImagePicked(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "像",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "像素大师",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "PixelMaster · 一秒初修，三秒精修",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(
            onClick = {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "导入照片",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        text = "从系统相册选择图片开始修图",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                }
            }
        }

        SecondaryEntry(
            title = "批量修图",
            description = "一次选多张，套用配方一键出片",
        ) {
            batchLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        SecondaryEntry(
            title = "RAW 导入转档",
            description = "选择 DNG 等 RAW 文件，解码后一键调色导出",
        ) {
            rawLauncher.launch(arrayOf("image/*"))
        }

        RecentExports(onOpen = onImagePicked)

        Spacer(Modifier.height(4.dp))
        Text(text = "功能模块", style = MaterialTheme.typography.titleMedium)
        FeatureCard("基础调色", "曝光/对比/饱和/色温/色调 + 高光/阴影/锐化/降噪/暗角/颗粒", available = true)
        FeatureCard("曲线调整", "RGB 主曲线 + 红绿蓝通道曲线（带直方图）", available = true)
        FeatureCard("HSL 分离调色", "红/橙/黄/绿/浅绿/蓝/紫/洋红 八区色相·饱和·明度", available = true)
        FeatureCard("滤镜预设", "11 款风格 + 我的预设（完整配方一键复用）", available = true)
        FeatureCard("人像精修", "磨皮 · 瘦脸 · 亮眼 · 白牙 · 匀肤 · 去瑕疵 · 手动液化", available = true)
        FeatureCard("局部调色", "径向羽化蒙版，圈内/圈外单独调整曝光饱和色温", available = true)
        FeatureCard("裁剪旋转", "自由/固定比例裁剪、90° 旋转、水平镜像（可撤销）", available = true)
        FeatureCard("批量修图", "多选照片，套用上次配方或自定义预设队列处理", available = true)
        FeatureCard("RAW 转档", "DNG 等格式解码（依赖设备解码能力）", available = true)
        FeatureCard("一键导出", "JPEG/PNG，最长边 4096，保留拍摄 EXIF", available = true)

        Spacer(Modifier.height(8.dp))
        Text(
            text = "对标像素蛋糕 PixCake · 分期实现中，详见 ROADMAP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SecondaryEntry(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "＞",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class RecentExport(val uri: Uri, val thumb: ImageBitmap?)

/** 最近导出（本应用保存到 Pictures/PixelMaster 的图片，点击继续编辑）。 */
@Composable
private fun RecentExports(onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<RecentExport>>(emptyList()) }

    LaunchedEffect(Unit) {
        val resolver = context.contentResolver
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("%Pictures/PixelMaster%")
                val uris = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val list = mutableListOf<Uri>()
                    while (cursor.moveToNext() && list.size < 10) {
                        list.add(
                            ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                cursor.getLong(0),
                            )
                        )
                    }
                    list
                } ?: emptyList()
                uris.map { uri ->
                    RecentExport(
                        uri = uri,
                        thumb = runCatching {
                            resolver.loadThumbnail(uri, Size(200, 200), null).asImageBitmap()
                        }.getOrNull(),
                    )
                }
            }.getOrDefault(emptyList())
        }
        items = loaded
    }

    if (items.isEmpty()) return
    Text(text = "最近导出", style = MaterialTheme.typography.titleMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.uri.toString() }) { item ->
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(EditorSurfaceVariant)
                    .clickable { onOpen(item.uri) },
            ) {
                val thumb = item.thumb
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, description: String, available: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = if (available) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                },
            ) {
                Text(
                    text = if (available) "可用" else "开发中",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (available) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
