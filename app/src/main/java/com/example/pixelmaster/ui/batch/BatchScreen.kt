package com.example.pixelmaster.ui.batch

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.pixelmaster.ui.theme.EditorBackground

/**
 * 批量修图：多选照片，沿用上次编辑配方（滤镜/调色/曲线/人像）逐张出片到相册。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(uris: List<Uri>, onExit: () -> Unit) {
    val context = LocalContext.current
    val viewModel: BatchViewModel = viewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(uris) { viewModel.setup(context, uris) }
    BackHandler(enabled = !state.running, onBack = onExit)

    val total = state.items.size
    val processed = state.doneCount + state.failedCount

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = { Text(text = "批量修图（${total}张）") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(EditorBackground)
        ) {
            // 配方选择
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "套用配方",
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.options.size) { index ->
                        val option = state.options[index]
                        FilterChip(
                            selected = index == state.selectedOption,
                            onClick = { viewModel.selectOption(index) },
                            enabled = !state.running,
                            label = { Text(option.label, maxLines = 1) },
                        )
                    }
                }
            }

            // 图片网格
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.uri.toString() }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                        when (item.status) {
                            BatchViewModel.Status.PENDING -> {}

                            BatchViewModel.Status.RUNNING -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 3.dp,
                                    color = Color.White,
                                )
                            }

                            BatchViewModel.Status.DONE -> StatusIcon(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )

                            BatchViewModel.Status.FAILED -> StatusIcon(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                color = Color(0xFFE57373),
                            )
                        }
                    }
                }
            }

            // 底部操作条
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else processed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "处理中 $processed/$total" +
                            if (state.failedCount > 0) " · 失败${state.failedCount}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text("取消")
                    }
                } else {
                    val pending = total - processed
                    Button(
                        onClick = { viewModel.start(context) },
                        enabled = pending > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = when {
                                processed == 0 -> "开始处理（$total 张）"
                                pending > 0 -> "继续处理剩余 $pending 张"
                                else -> "全部完成 ✓"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(modifier: Modifier = Modifier, color: Color) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(16.dp),
        )
    }
}
