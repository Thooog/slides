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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.slides.app.permissions.Permissions.AccessScope
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

/** 请求结果映射到真实授权范围。 */
private fun scopeFromGrants(grants: Map<String, Boolean>): AccessScope {
    if (!Permissions.supportsGranular()) {
        return if (grants[Permissions.READ_STORAGE] == true) AccessScope.FULL else AccessScope.NONE
    }
    val im = grants[Permissions.READ_IMAGES] == true
    val vd = grants[Permissions.READ_VIDEOS] == true
    return when {
        im && vd -> AccessScope.FULL
        im -> AccessScope.IMAGES_ONLY
        vd -> AccessScope.VIDEOS_ONLY
        else -> AccessScope.NONE
    }
}

@Composable
private fun SlidesRoot() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("slides_prefs", Context.MODE_PRIVATE) }
    var firstRunShown by remember { mutableStateOf(prefs.getBoolean("first_run_shown", false)) }
    var scope by remember { mutableStateOf(Permissions.currentScope(context)) }
    var permKey by remember { mutableIntStateOf(0) }
    var showExplain by remember { mutableStateOf(false) }

    // 冷启 / 回前台 / 从设置返回：重查权限；范围变化则递增 key 触发重新核对集合。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope = Permissions.currentScope(context)
                permKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        scope = scopeFromGrants(grants)
        permKey++
        prefs.edit().putBoolean("first_run_shown", true).apply()
    }

    // SELECTED（所选子集）状态下重新选择照片：photo picker 更新 READ_MEDIA_VISUAL_USER_SELECTED。
    // 返回的是用户本次选中的 URI；选择后系统更新授权集合，MediaStore 查询自动反映新子集。
    // 取消（返回空列表）不制造授权成功，也不改变现有授权状态。
    val pickImagesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        // 只有用户实际选择了媒体才重查授权并刷新；取消/空选择保持现状。
        if (uris.isNotEmpty()) {
            scope = Permissions.currentScope(context)
            permKey++
        }
    }

    fun requestFull() = requestLauncher.launch(Permissions.requiredPermissions())

    fun openSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    // 首次启动但未授权：进入说明页；已说明且未授权则直接进入拒绝态。
    if (!firstRunShown && scope == AccessScope.NONE) {
        LaunchedEffect(Unit) { showExplain = true }
    }

    when {
        showExplain ->
            FirstUseScreen(onContinue = {
                firstRunShown = true
                prefs.edit().putBoolean("first_run_shown", true).apply()
                showExplain = false
                requestFull()
            })

        scope == AccessScope.NONE ->
            PermissionDeniedScreen(onRequest = ::requestFull, onSettings = ::openSettings)

        else ->
            HomeContainer(
                scope = scope,
                permKey = permKey,
                onReSelect = { pickImagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onOpenSettings = ::openSettings,
            )
    }
}

/** 首页 + 详情覆盖层：首页常驻保留滚动锚点；授权范围变化时重新核对集合并失效详情。 */
@Composable
private fun HomeContainer(
    scope: AccessScope,
    permKey: Int,
    onReSelect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<DetailSession?>(null) }

    // 授权范围 / 前台恢复变化 → 重新核对可访问集合；范围退化为 NONE 时清掉详情。
    LaunchedEffect(permKey) {
        vm.refresh()
        if (scope == AccessScope.NONE) detail = null
    }

    // 新集合就绪后核对详情项是否仍可访问：失权/缩减时退出失效详情（不把失权当永久删除）。
    LaunchedEffect(state.allItems) {
        val d = detail ?: return@LaunchedEffect
        if (!state.loading && !state.loadError) {
            val accessible = state.allItems.any { it.localId == d.items[d.index].localId }
            if (!accessible) detail = null
        }
    }

    BackHandler(enabled = detail != null) { detail = null }

    Box {
        HomeScreen(
            state = state,
            scope = scope,
            onSelectDirectory = vm::selectDirectory,
            onSetColumns = vm::setColumns,
            onOpenDetail = { items, idx -> detail = DetailSession(items, idx) },
            onRetry = vm::refresh,
            onReSelect = onReSelect,
            onOpenSettings = onOpenSettings,
            onSelectTab = vm::selectTab,
        )
        detail?.let { d ->
            DetailScreen(
                items = d.items,
                startIndex = d.index,
                favoriteKeys = state.favoriteKeys,
                onToggleFavorite = vm::toggleFavorite,
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