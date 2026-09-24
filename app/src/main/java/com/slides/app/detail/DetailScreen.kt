package com.slides.app.detail

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.slides.app.data.MediaItem as Media
import com.slides.app.theme.SlidesAccent
import com.slides.app.theme.SlidesOnAccent
import com.slides.app.theme.SlidesTextSecondary
import com.slides.app.ui.formatDateMs
import kotlin.math.abs

/**
 * 详情页（P2，只读版本）：图片缩放 / 视频播放、左右切换（按钮替代）、返回。
 * 进入即固定来源有序会话快照（PRD §6.2）；不实现收藏/删除。
 */
@Composable
fun DetailScreen(
    items: List<Media>,
    startIndex: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    var index by rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    val item = items[index]

    BackHandler(enabled = true) { onBack() }

    Box(modifier.background(Color(0xFF131521))) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Surface(color = Color(0xFF1B1C22)) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("‹", color = Color.White, fontSize = 24.sp) }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        item.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${index + 1}/${items.size}",
                        color = Color(0xFFAAAAAA),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            // 媒体区
            key(item.localId) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (item.isVideo) {
                        VideoPlayerView(item.uri)
                    } else {
                        ZoomableImageView(
                            uri = item.uri,
                            onSwipeNext = { if (index < items.lastIndex) index++ },
                            onSwipePrev = { if (index > 0) index-- },
                        )
                    }
                }
            }

            // 底栏：目录 + 日期 + 上一项/下一项
            Surface(color = Color(0xFF1B1C22)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.bucketName, color = Color(0xFFDDDDDD), fontSize = 13.sp, maxLines = 1)
                        Text(
                            formatDateMs(item.effectiveTakenAtMs),
                            color = SlidesTextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Surface(
                        color = if (index > 0) SlidesAccent else Color(0xFF3A3B40),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clickable(enabled = index > 0) { index-- }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("‹", color = if (index > 0) SlidesOnAccent else Color(0xFF777777), fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = if (index < items.lastIndex) SlidesAccent else Color(0xFF3A3B40),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .clickable(enabled = index < items.lastIndex) { index++ }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("›", color = if (index < items.lastIndex) SlidesOnAccent else Color(0xFF777777), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

/**
 * 图片查看器：未放大时左右滑动切换，双指缩放；放大时平移优先；
 * 双击放大/还原；加载失败显示可恢复占位（不误报永久删除）。
 */
@Composable
private fun ZoomableImageView(
    uri: Uri,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var failed by remember { mutableStateOf(false) }
    var tryKey by remember { mutableIntStateOf(0) }
    var dragAcc by remember { mutableFloatStateOf(0f) }
    val zoomed = scale > 1f

    fun clamp() {
        if (scale <= 1f) {
            scale = 1f; offsetX = 0f; offsetY = 0f
        } else {
            val maxX = (scale - 1f) * size.width / 2f
            val maxY = (scale - 1f) * size.height / 2f
            offsetX = offsetX.coerceIn(-maxX, maxX)
            offsetY = offsetY.coerceIn(-maxY, maxY)
        }
    }

    val gestureModifier = if (!zoomed) {
        Modifier
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = {
                    scale = 2.5f
                    clamp()
                })
            }
            .pointerInput(uri) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAcc += dragAmount
                    },
                    onDragEnd = {
                        if (dragAcc <= -120f) onSwipeNext()      // 左滑 → 下一项
                        else if (dragAcc >= 120f) onSwipePrev()  // 右滑 → 上一项
                        dragAcc = 0f
                    },
                    onDragCancel = { dragAcc = 0f },
                )
            }
    } else {
        Modifier.pointerInput(uri) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offsetX += pan.x
                offsetY += pan.y
                clamp()
            }
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds().then(gestureModifier)) {
        key(tryKey) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size = it }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
                onError = { failed = true },
                onSuccess = { failed = false },
            )
        }
        if (failed) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("无法加载该媒体", color = Color(0xFFCCCCCC))
                TextButton(onClick = { failed = false; tryKey++ }) {
                    Text("重试", color = SlidesAccent)
                }
            }
        }
    }
}

/** 视频播放：Media3 ExoPlayer，默认控制器（播放/暂停/进度），离开即释放。 */
@Composable
private fun VideoPlayerView(uri: Uri) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
            playWhenReady = true
        }
    }
    DisposableEffect(uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        onDispose {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                controllerAutoShow = true
                controllerShowTimeoutMs = 4000
            }
        },
        update = { view -> view.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}