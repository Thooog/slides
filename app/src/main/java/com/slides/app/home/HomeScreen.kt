package com.slides.app.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slides.app.data.MediaItem
import com.slides.app.theme.SlidesAccent
import com.slides.app.theme.SlidesOnAccent
import com.slides.app.theme.SlidesTextPrimary
import com.slides.app.theme.SlidesTextSecondary
import com.slides.app.theme.SlidesTopBar
import com.slides.app.ui.formatDuration

/** 详情会话：进入时对来源有序列表做快照，避免新媒体/重排让当前项跳变（PRD §6.2）。 */
data class DetailSession(val items: List<MediaItem>, val index: Int)

/**
 * 首页（P1，只读版本）：顶栏 + 目录Tab + 网格。
 * 默认位置分类；不实现收藏/标签/删除/AI。不做可误触的假按钮。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    partial: Boolean,
    onReSelect: () -> Unit,
    onSelectBucket: (String?) -> Unit,
    onSetColumns: (Int) -> Unit,
    onOpenDetail: (List<MediaItem>, Int) -> Unit,
    onRetry: () -> Unit,
) {
    val gridState = rememberSaveable(state.selectedBucketId, saver = LazyGridState.Saver) {
        LazyGridState()
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // 顶栏：深色
        Surface(color = SlidesTopBar) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SLIDES", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ColumnSwitch(state.columns, onSetColumns)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { /* 本只读任务无设置弹层 */ }) {
                    Text("设置", color = Color(0xFFDDDDDD))
                }
            }
        }

        // 部分授权提示条（API33+ 仅图片或仅视频）
        if (partial) {
            Surface(color = Color(0xFFF3EBD3)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("仅可访问部分照片和视频", color = Color(0xFF6B5A1E), fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onReSelect) {
                        Text("重新选择", color = Color(0xFF4A4A4A), fontSize = 13.sp)
                    }
                }
            }
        }

        // 目录Tab（动态目录；全部 + 各目录 + 其他）
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                DirectoryChip(
                    name = "全部",
                    count = state.allItems.size,
                    selected = state.selectedBucketId == null,
                    onClick = { onSelectBucket(null) },
                )
            }
            items(state.directories, key = { it.bucketId }) { d ->
                DirectoryChip(
                    name = d.name,
                    count = d.totalCount,
                    selected = state.selectedBucketId == d.bucketId,
                    onClick = { onSelectBucket(d.bucketId) },
                )
            }
        }

        // 内容区：加载 / 错误 / 空 / 网格
        when {
            state.loading -> CenterBox { CircularProgressIndicator() }
            state.loadError -> CenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败", color = SlidesTextPrimary)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            state.visibleItems.isEmpty() -> CenterBox {
                Text(
                    if (state.allItems.isEmpty()) "暂无媒体" else "该目录暂无媒体",
                    color = SlidesTextSecondary,
                )
            }
            else -> MediaGrid(
                items = state.visibleItems,
                columns = state.columns,
                gridState = gridState,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ColumnSwitch(columns: Int, onSetColumns: (Int) -> Unit) {
    val label = when (columns) { 2 -> "2列"; 3 -> "3列"; else -> "4列" }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SlidesAccent,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
            onSetColumns(if (columns >= 4) 2 else columns + 1)
        },
    ) {
        Text(
            label,
            color = SlidesOnAccent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DirectoryChip(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) SlidesAccent else Color(0xFFF0F1F2),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Text(
            "$name ($count)",
            color = if (selected) SlidesOnAccent else SlidesTextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    gridState: LazyGridState,
    onOpenDetail: (List<MediaItem>, Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items = items, key = { _, it -> it.localId }) { index, item ->
            MediaCell(item = item, onClick = { onOpenDetail(items, index) })
        }
    }
}

@Composable
private fun MediaCell(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val model = remember(item.uri) {
        ImageRequest.Builder(context).data(item.uri).crossfade(true).build()
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFE8E8E8))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = model,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("▶", color = Color.White, fontSize = 10.sp)
                Text(
                    formatDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.background(Color(0x88000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}