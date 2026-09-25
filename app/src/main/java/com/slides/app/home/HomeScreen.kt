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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.slides.app.data.MediaItem
import com.slides.app.theme.SlidesAccent
import com.slides.app.theme.SlidesOnAccent
import com.slides.app.theme.SlidesTextPrimary
import com.slides.app.theme.SlidesTextSecondary
import com.slides.app.theme.SlidesTopBar
import com.slides.app.ui.detectTwoFingerPinch
import com.slides.app.ui.formatDuration

/** 详情会话：进入时对来源有序列表做快照，避免新媒体/重排让当前项跳变（PRD §6.2）。 */
data class DetailSession(val items: List<MediaItem>, val index: Int)

/**
 * 首页（P1）：顶栏 + 视图Tab（全部/收藏）+ 目录Tab + 网格。
 * 收藏：网格单击进详情；收藏切换在详情页（按钮 + 双击）；顶部独立收藏入口跨目录聚合。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSelectDirectory: (String?) -> Unit,
    onSetColumns: (Int) -> Unit,
    onOpenDetail: (List<MediaItem>, Int) -> Unit,
    onRetry: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
) {
    val gridState = rememberSaveable(state.tab, state.selectedDirectoryKey, saver = LazyGridState.Saver) {
        LazyGridState()
    }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        // 顶栏：深色（不含假按钮；只保留真实可用的列数切换）
        Surface(color = SlidesTopBar) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SLIDES", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                ColumnSwitch(state.columns, onSetColumns)
            }
        }

        // 视图Tab：全部 / 收藏（收藏跨目录聚合、清目录限制，按收藏时间倒序）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ViewTabChip("全部", state.allItems.size, state.tab == HomeTab.ALL) { onSelectTab(HomeTab.ALL) }
            ViewTabChip("收藏", state.favoriteItems.size, state.tab == HomeTab.FAVORITES) { onSelectTab(HomeTab.FAVORITES) }
        }

        // 目录Tab（仅「全部」视图显示；收藏视图跨目录聚合，不显示目录筛选）
        if (state.tab == HomeTab.ALL) {
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    DirectoryChip(
                        name = "全部",
                        count = state.allItems.size,
                        selected = state.selectedDirectoryKey == null,
                        onClick = { onSelectDirectory(null) },
                    )
                }
                items(state.directories, key = { it.key }) { d ->
                    DirectoryChip(
                        name = d.name,
                        count = d.totalCount,
                        selected = state.selectedDirectoryKey == d.key,
                        onClick = { onSelectDirectory(d.key) },
                    )
                }
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
            state.visibleItems.isEmpty() -> EmptyState(
                hasAllEmpty = state.allItems.isEmpty(),
                isFavorites = state.tab == HomeTab.FAVORITES,
            )
            else -> MediaGrid(
                items = state.visibleItems,
                favoriteKeys = state.favoriteKeys,
                columns = state.columns,
                gridState = gridState,
                onOpenDetail = onOpenDetail,
                onSetColumns = onSetColumns,
            )
        }
    }
}

@Composable
private fun EmptyState(hasAllEmpty: Boolean, isFavorites: Boolean = false) {
    CenterBox {
        Text(
            when {
                isFavorites -> "暂无收藏"
                hasAllEmpty -> "暂无媒体"
                else -> "该目录暂无媒体"
            },
            color = SlidesTextSecondary,
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 视图 Tab 切换 chip（全部 / 收藏）。 */
@Composable
private fun ViewTabChip(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) SlidesAccent else Color(0xFFF0F1F2),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Text(
            "$name ($count)",
            color = if (selected) SlidesOnAccent else SlidesTextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
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
    favoriteKeys: Set<String>,
    columns: Int,
    gridState: LazyGridState,
    onOpenDetail: (List<MediaItem>, Int) -> Unit,
    onSetColumns: (Int) -> Unit,
) {
    // 双指切 2/3/4 列（PRD）：双指张开 → 列数-1（缩略图变大）；双指捏合 → 列数+1（缩略图变小）。
    var pinchAcc by remember { mutableFloatStateOf(0f) }
    val onPinch = { delta: Float ->
        pinchAcc += delta
        if (pinchAcc >= 120f) {
            onSetColumns(columns - 1); pinchAcc = 0f
        } else if (pinchAcc <= -120f) {
            onSetColumns(columns + 1); pinchAcc = 0f
        }
    }
    // rememberUpdatedState 让 detectTwoFingerPinch 的 pointerInput(Unit) 始终读到最新闭包，
    // 避免切列后仍用旧 columns 值计算。
    val currentOnPinch = rememberUpdatedState(onPinch)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize().detectTwoFingerPinch(currentOnPinch),
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items = items, key = { _, it -> it.stableKey }) { index, item ->
            MediaCell(
                item = item,
                isFavorite = item.stableKey in favoriteKeys,
                onClick = { onOpenDetail(items, index) },
            )
        }
    }
}

/** 网格单元：单击进详情；右上角收藏标记（收藏切换仅在详情页操作，网格不设双击收藏）。 */
@Composable
private fun MediaCell(
    item: MediaItem,
    isFavorite: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val model = remember(item.uri, item.isVideo) {
        val b = ImageRequest.Builder(context).data(item.uri).crossfade(true)
        if (item.isVideo) {
            // 视频缩略图：解码首帧作为封面，叠加 ▶ + 时长角标
            b.decoderFactory(VideoFrameDecoder.Factory())
        }
        b.build()
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
        if (isFavorite) {
            Text(
                "★",
                color = Color(0xFFFFB300),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color(0x88000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}