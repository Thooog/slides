package com.slides.app.detail

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import kotlin.math.hypot

/**
 * 详情页（P2）：图片缩放 / 视频播放、左右切换（手势+按钮替代）、收藏、返回。
 * 进入即固定来源有序会话快照（PRD §6.2）；不实现删除。
 * 收藏：顶栏 ⭐ 按钮 + 双击切换；取消收藏保持当前项停留；视频收藏不重建播放器。
 */
@Composable
fun DetailScreen(
    items: List<Media>,
    startIndex: Int,
    favoriteKeys: Set<String>,
    onToggleFavorite: (Media) -> Unit,
    onBack: () -> Unit,
    onIndexChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var index by rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    val item = items[index]
    // 乐观本地收藏覆盖：详情页内点击/双击收藏时立即翻转，避免等待异步 Flow 回传导致
    // 顶栏收藏按钮不即时同步（问题3）。以本地覆盖为准，外部 favoriteKeys 作为基准。
    var localFavOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val isFavorite = localFavOverrides[item.stableKey] ?: (item.stableKey in favoriteKeys)

    fun toggleFavorite() {
        val target = !isFavorite
        localFavOverrides = localFavOverrides + (item.stableKey to target)
        onToggleFavorite(item)
    }

    // 当前显示 ID 回传：访问核对/收藏提交以当前项为准，不以进入时下标。
    LaunchedEffect(index, items) { onIndexChanged(index) }

    BackHandler(enabled = true) { onBack() }

    Box(modifier.background(Color(0xFF131521)).systemBarsPadding()) {
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
                    // 收藏按钮（等价双击）
                    TextButton(onClick = { toggleFavorite() }) {
                        Text(
                            if (isFavorite) "★" else "☆",
                            color = if (isFavorite) Color(0xFFFFB300) else Color.White,
                            fontSize = 22.sp,
                        )
                    }
                }
            }

            // 媒体区
            key(item.localId) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (item.isVideo) {
                        VideoPlayerView(
                            uri = item.uri,
                            onDoubleTap = { toggleFavorite() },
                            onSwipeNext = { if (index < items.lastIndex) index++ },
                            onSwipePrev = { if (index > 0) index-- },
                        )
                    } else {
                        ZoomableImageView(
                            uri = item.uri,
                            onDoubleTap = { toggleFavorite() },
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
 * 图片查看器：
 * - 未放大时单指左右滑动切换；放大时单指平移优先。
 * - 双指可直接捏合缩放（不要求先双击）；缩放 1..5 倍，超界钳位。
 * - 双击提交收藏切换（PRD §6.1 双击保留给收藏语义），不绑定缩放。
 * - 加载失败显示可恢复占位（不误报永久删除）。
 */
@Composable
private fun ZoomableImageView(
    uri: Uri,
    onDoubleTap: () -> Unit,
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

    // 回调最新引用：pointerInput 以 uri 为 key，同一图片多次双击不会重启手势块，
    // 需持有最新 onDoubleTap（否则收藏按钮状态因陈旧闭包不同步）。
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentNext by rememberUpdatedState(onSwipeNext)
    val currentPrev by rememberUpdatedState(onSwipePrev)

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

    // 单一自研手势：双指捏合缩放/平移 + 未放大单指翻页 + 放大单指平移 + 双击收藏。
    // 缩放用"相对上次 span 的比值"增量更新，避免固定 initialSpan 导致的倍率累计漂移。
    // lastTapTime 必须放在 pointerInput 块作用域（awaitEachGesture 之外），
    // 否则每次抬手结束手势后局部变量被重置，双击永远无法识别。
    val gestureModifier = Modifier.pointerInput(uri) {
        var lastTapTime = 0L
        awaitEachGesture {
            val down = awaitFirstDown()
            var dragX = 0f
            var lastPoint = down.position
            var previousSpan = 0f
            var pinchMode = false
            var moved = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    // 抬手
                    if (!moved && !pinchMode) {
                        // 轻点：判定单击/双击（用抬手时刻做 300ms 判定窗）
                        val tapTime = SystemClock.uptimeMillis()
                        if (lastTapTime != 0L && tapTime - lastTapTime <= 300L) {
                            // 双击：提交收藏切换
                            lastTapTime = 0L
                            currentDoubleTap()
                        } else {
                            lastTapTime = tapTime
                        }
                    } else if (moved && !pinchMode && abs(dragX) >= 120f) {
                        // 未放大时的单指横向滑动：翻页
                        if (dragX <= -120f) currentNext() else currentPrev()
                    }
                    break
                }
                if (pressed.size >= 2) {
                    // 双指捏合缩放 + 双指平移（scale=1 时也能直接开始）
                    pinchMode = true
                    moved = true
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val span = hypot(b.x - a.x, b.y - a.y)
                    val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    if (previousSpan == 0f) {
                        previousSpan = span
                    } else if (span > 0f) {
                        // 相对上次 span 的增量缩放，避免倍率累积漂移
                        scale = (scale * span / previousSpan).coerceIn(1f, 5f)
                        offsetX += centroid.x - lastPoint.x
                        offsetY += centroid.y - lastPoint.y
                        clamp()
                    }
                    lastPoint = centroid
                    previousSpan = span
                    event.changes.forEach { it.consume() }
                } else {
                    val p = pressed[0]
                    val delta = p.position - lastPoint
                    if (scale > 1f || pinchMode) {
                        // 放大/捏合中：单指平移
                        offsetX += delta.x
                        offsetY += delta.y
                        clamp()
                        moved = true
                    } else {
                        // 未放大：累积横向翻页
                        dragX += delta.x
                        if (delta.x != 0f || delta.y != 0f) moved = true
                    }
                    lastPoint = p.position
                    if (moved) p.consume()
                }
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

/** 视频播放：Media3 ExoPlayer，默认控制器；错误可恢复，前后台暂停/恢复，离开释放；双击收藏、左右滑动切换媒体。 */
@Composable
private fun VideoPlayerView(
    uri: Uri,
    onDoubleTap: () -> Unit,
    onSwipeNext: () -> Unit,
    onSwipePrev: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf(false) }
    var tryKey by remember { mutableIntStateOf(0) }

    key(tryKey) {
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
            }
        }
        DisposableEffect(uri, lifecycleOwner) {
            val listener = object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = true
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true

            val obs = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                    Lifecycle.Event.ON_RESUME ->
                        if (player.playbackState != Player.STATE_ENDED) player.playWhenReady = true
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)

            onDispose {
                player.removeListener(listener)
                lifecycleOwner.lifecycle.removeObserver(obs)
                player.playWhenReady = false
                player.release()
            }
        }

        // 手势层：用原生 OnTouchListener 自行判定「单击 / 双击 / 横向滑动」，避免 Compose
        // pointerInput 在 AndroidView 之上抢占 down 事件导致 PlayerView 收不到单击。
        // 语义：
        //   - 单击（300ms 内无第二次 tap）→ 切换进度条显隐（手动控制控制器）；
        //   - 双击 → 收藏，且不唤起进度条（第一次 tap 不立即 show，等 300ms 判定窗结束）；
        //   - 横向滑动 ≥ 阈值 → 切换媒体。
        // 回调用 rememberUpdatedState 持有最新引用，并在 update 块写回手势监听器，
        // 避免 AndroidView factory 只创建一次导致的陈旧闭包（问题3：多次双击按钮不同步）。
        val currentDoubleTap by rememberUpdatedState(onDoubleTap)
        val currentNext by rememberUpdatedState(onSwipeNext)
        val currentPrev by rememberUpdatedState(onSwipePrev)
        // 持有手势监听器引用：factory 创建 PlayerView 时建立，update 每次写回最新回调。
        var gestureRef by remember { mutableStateOf<VideoTouchGesture?>(null) }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = false // 显隐完全由手势层接管，避免双击时自动唤起
                    controllerShowTimeoutMs = 4000
                    val g = VideoTouchGesture(this)
                    gestureRef = g
                    setOnTouchListener(g)
                }
            },
            update = { view ->
                view.player = player
                // 每次 recompose 同步最新回调，消除陈旧闭包
                gestureRef?.let { g ->
                    g.onDoubleTap = currentDoubleTap
                    g.onSwipeNext = currentNext
                    g.onSwipePrev = currentPrev
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (error) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法播放该视频", color = Color.White)
                    TextButton(onClick = { error = false; tryKey++ }) {
                        Text("重试", color = SlidesAccent)
                    }
                }
            }
        }
    }
}

/** 视频手势：native OnTouchListener——单击切换进度条显隐、双击收藏（不唤起进度条）、横向滑动切集。 */
private class VideoTouchGesture(
    private val playerView: PlayerView,
) : android.view.View.OnTouchListener {
    // 回调为可变字段：由 VideoPlayerView 的 AndroidView.update 每次 recompose 写入最新值，
    // 避免 factory 只创建一次导致的陈旧闭包（多次双击收藏按钮不同步）。
    var onDoubleTap: () -> Unit = {}
    var onSwipeNext: () -> Unit = {}
    var onSwipePrev: () -> Unit = {}

    private var lastTapTime = 0L
    private var downX = 0f
    private var dragX = 0f
    private var moved = false
    // 自维护控制器显隐状态（Media3 无公开 isControllerVisible）
    private var controllerVisible = false
    // 单击判定的延迟任务：300ms 内若出现第二次 tap 则取消（判定为双击），否则执行单击动作。
    private var pendingSingleTap: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragX = 0f
                moved = false
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                dragX = event.x - downX
                if (kotlin.math.abs(dragX) > 24f) moved = true
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(dragX) >= 120f) {
                    // 横向滑动达到阈值：切换媒体（先取消可能存在的单击任务）
                    pendingSingleTap?.let { handler.removeCallbacks(it) }
                    pendingSingleTap = null
                    if (dragX <= -120f) onSwipeNext() else onSwipePrev()
                    return true
                }
                if (moved) return true // 轻微移动：忽略
                // 无位移的轻点：进入单击/双击判定窗
                val now = SystemClock.uptimeMillis()
                val isDouble = lastTapTime != 0L && now - lastTapTime <= 300L
                if (isDouble) {
                    // 双击：取消未执行的单击任务，收藏，且不唤起进度条
                    lastTapTime = 0L
                    pendingSingleTap?.let { handler.removeCallbacks(it) }
                    pendingSingleTap = null
                    onDoubleTap()
                } else {
                    // 第一次 tap：不立即动作，延迟 300ms 判定是否单击
                    lastTapTime = now
                    pendingSingleTap?.let { handler.removeCallbacks(it) }
                    val task = Runnable {
                        pendingSingleTap = null
                        // 单击：切换进度条显隐
                        if (controllerVisible) { playerView.hideController(); controllerVisible = false }
                        else { playerView.showController(); controllerVisible = true }
                    }
                    pendingSingleTap = task
                    handler.postDelayed(task, 300L)
                }
                return true
            }
        }
        return false
    }
}