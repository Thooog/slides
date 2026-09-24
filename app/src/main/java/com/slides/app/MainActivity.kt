package com.slides.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.slides.app.detail.DetailScreen
import com.slides.app.home.DetailSession
import com.slides.app.home.HomeScreen
import com.slides.app.home.HomeViewModel
import com.slides.app.permissions.Permissions
import com.slides.app.theme.SlidesAccent
import com.slides.app.theme.SlidesOnAccent
import com.slides.app.theme.SlidesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SlidesTheme {
                SlidesRoot()
            }
        }
    }
}

/** 权限界面状态（P0 说明 / 全拒绝 / 部分 / 就绪）。 */
private sealed interface PermUi {
    data object Explaining : PermUi
    data object None : PermUi
    data class Partial(val imagesGranted: Boolean, val videosGranted: Boolean) : PermUi
    data object Ready : PermUi
}

private fun currentPermUi(context: Context): PermUi {
    val im = Permissions.imagesGranted(context)
    val vd = Permissions.videosGranted(context)
    return when {
        im && vd -> PermUi.Ready
        im || vd -> PermUi.Partial(im, vd)
        else -> PermUi.None
    }
}

@Composable
private fun SlidesRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("slides_prefs", Context.MODE_PRIVATE) }
    var firstRunShown by remember { mutableStateOf(prefs.getBoolean("first_run_shown", false)) }
    var permUi by remember { mutableStateOf(currentPermUi(context)) }

    // 冷启 / 回前台 / 从设置返回：重查权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permUi = currentPermUi(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val grantedImages = grants[Permissions.READ_IMAGES] == true
        val grantedVideos = grants[Permissions.READ_VIDEOS] == true
        permUi = if (!Permissions.supportsGranular()) {
            if (grants[Permissions.READ_STORAGE] == true) PermUi.Ready else PermUi.None
        } else {
            when {
                grantedImages && grantedVideos -> PermUi.Ready
                grantedImages || grantedVideos -> PermUi.Partial(grantedImages, grantedVideos)
                else -> PermUi.None
            }
        }
        prefs.edit().putBoolean("first_run_shown", true).apply()
    }

    fun requestPermissions() = launcher.launch(Permissions.requiredPermissions())
    fun openSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    when (val ui = permUi) {
        PermUi.Explaining ->
            FirstUseScreen(onContinue = {
                prefs.edit().putBoolean("first_run_shown", true).apply()
                firstRunShown = true
                requestPermissions()
            })

        PermUi.None ->
            PermissionDeniedScreen(onRequest = ::requestPermissions, onSettings = ::openSettings)

        is PermUi.Partial ->
            HomeContainer(partial = true, onReSelect = ::requestPermissions)

        PermUi.Ready ->
            HomeContainer(partial = false, onReSelect = {})
    }

    // 首次启动但未授权：进入说明页；已说明且未授权则直接进入拒绝态
    if (permUi != PermUi.Explaining && !firstRunShown && permUi is PermUi.None) {
        LaunchedEffect(Unit) { permUi = PermUi.Explaining }
    }
}

/** 首页 + 详情覆盖层：首页常驻以保留滚动锚点（PRD 返回顺序：退出详情→回来源目录）。 */
@Composable
private fun HomeContainer(partial: Boolean, onReSelect: () -> Unit) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<DetailSession?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    BackHandler(enabled = detail != null) { detail = null }

    androidx.compose.foundation.layout.Box {
        HomeScreen(
            state = state,
            partial = partial,
            onReSelect = onReSelect,
            onSelectBucket = vm::selectBucket,
            onSetColumns = vm::setColumns,
            onOpenDetail = { items, idx -> detail = DetailSession(items, idx) },
            onRetry = vm::refresh,
        )
        detail?.let { d ->
            DetailScreen(
                items = d.items,
                startIndex = d.index,
                onBack = { detail = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** P0 首次用途说明：本地处理、媒体不上传、AI 分类。 */
@Composable
private fun FirstUseScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("SLIDES", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = SlidesAccent)
        Spacer(Modifier.height(12.dp))
        Text("整理你的相册，全程在设备本地完成", fontSize = 16.sp)
        Spacer(Modifier.height(24.dp))
        Text("· 仅本地处理，媒体不上传", fontSize = 15.sp)
        Text("· 媒体不出设备", fontSize = 15.sp)
        Text("· 支持 AI 自动分类（后续版本）", fontSize = 15.sp)
        Spacer(Modifier.height(36.dp))
        Button(onClick = onContinue) { Text("继续") }
    }
}

/** 全拒绝空态：允许访问 / 去设置；不显示任何越权缩略图。 */
@Composable
private fun PermissionDeniedScreen(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要访问你的照片和视频", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text("SLIDES 只在设备本地整理媒体，不会上传。", color = Color(0xFF5E5D62))
        Spacer(Modifier.height(28.dp))
        Button(onClick = onRequest) { Text("允许访问", color = SlidesOnAccent) }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSettings) { Text("去设置") }
    }
}