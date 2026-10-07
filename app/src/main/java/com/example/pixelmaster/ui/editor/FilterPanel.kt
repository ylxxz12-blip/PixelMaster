package com.example.pixelmaster.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.pixelmaster.data.model.FilterPreset
import com.example.pixelmaster.data.model.FilterPresets
import com.example.pixelmaster.data.model.UserPreset
import com.example.pixelmaster.image.ColorMatrixFactory

/**
 * 滤镜面板：官方预设 + 我的预设 + 保存当前效果。
 * 缩略图用线性 ColorMatrix 实时预览每个滤镜在当前照片上的效果。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterPanel(
    thumb: ImageBitmap?,
    selectedId: String,
    userPresets: List<UserPreset>,
    onPresetChange: (String) -> Unit,
    onUserPresetSelect: (UserPreset) -> Unit,
    onSaveCurrent: () -> Unit,
    onDeleteUserPreset: (UserPreset) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(FilterPresets.all) { preset ->
            FilterItem(
                name = preset.name,
                thumb = thumb,
                colorFilter = rememberColorFilter(preset.base, preset.channelMatrix),
                selected = preset.id == selectedId,
                onClick = { onPresetChange(preset.id) },
            )
        }
        items(userPresets, key = { it.id }) { preset ->
            val base = FilterPresets.byId(preset.basePresetId)
            FilterItem(
                name = preset.name,
                thumb = thumb,
                colorFilter = rememberColorFilter(base.base + preset.adjustments, base.channelMatrix),
                selected = false,
                onClick = { onUserPresetSelect(preset) },
                onLongClick = { onDeleteUserPreset(preset) },
            )
        }
        item {
            SavePresetItem(onClick = onSaveCurrent)
        }
    }
}

@Composable
private fun rememberColorFilter(
    base: com.example.pixelmaster.data.model.Adjustments,
    channelMatrix: android.graphics.ColorMatrix?,
): ColorFilter? = remember(base, channelMatrix) {
    val matrix = ColorMatrixFactory.build(base, channelMatrix)
    ColorFilter.colorMatrix(ColorMatrix(matrix.array))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilterItem(
    name: String,
    thumb: ImageBitmap?,
    colorFilter: ColorFilter?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    },
                    shape = shape,
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null && colorFilter != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    colorFilter = colorFilter,
                )
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SavePresetItem(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = shape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "＋\n保存\n当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "存预设",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
