package com.slides.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slides.app.data.FavoriteEntity
import com.slides.app.data.FavoriteRepository
import com.slides.app.data.MediaDirectory
import com.slides.app.data.MediaIdentityService
import com.slides.app.data.MediaItem
import com.slides.app.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 顶部视图：全部 / 收藏。 */
enum class HomeTab { ALL, FAVORITES }

data class HomeUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val allItems: List<MediaItem> = emptyList(),
    val directories: List<MediaDirectory> = emptyList(),
    val selectedDirectoryKey: String? = null, // null = 全部
    val columns: Int = 3,
    // 收藏状态
    val favoriteKeys: Set<String> = emptySet(),
    val favoriteByTime: List<FavoriteEntity> = emptyList(),
    val tab: HomeTab = HomeTab.ALL,
    /** 收藏操作失败反馈（非空表示最近一次提交失败，UI 可短暂提示）。 */
    val favoriteError: Boolean = false,
) {
    val empty: Boolean get() = !loading && !loadError && allItems.isEmpty()

    /** 当前收藏的可访问媒体（按收藏时间倒序，Spec §3）。 */
    val favoriteItems: List<MediaItem>
        get() {
            val byKey = allItems.associateBy { it.stableKey }
            return favoriteByTime.mapNotNull { byKey[it.stableKey] }
        }

    /** 当前 Tab 下的可见媒体。 */
    val visibleItems: List<MediaItem>
        get() = when (tab) {
            HomeTab.FAVORITES -> favoriteItems
            HomeTab.ALL -> if (selectedDirectoryKey == null) allItems
            else allItems.filter { it.directoryKey == selectedDirectoryKey }
        }
}

/**
 * 首页状态：加载可访问媒体、派生动态目录、收藏与目录/网格列数。
 * - 代际计数：权限/授权集合变化时旧异步查询结果不得回写覆盖新状态。
 * - 失败时清空旧索引，避免失权后继续显示过期媒体。
 * - 收藏：访问与收藏正交；收藏视图 = 可访问媒体 ∩ favorite；重扫不丢人工数据。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val favoriteRepo = FavoriteRepository(app)
    private val identityService = MediaIdentityService(app)

    private var generation = 0

    init {
        load()
        observeFavorites()
    }

    fun load() {
        val g = ++generation
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, loadError = false) }
            MediaRepository.queryAllAccessible(getApplication()).fold(
                onSuccess = { items ->
                    val dirs = deriveDirectories(items)
                    if (g == generation) {
                        _state.update {
                            it.copy(loading = false, loadError = false, allItems = items, directories = dirs)
                        }
                        // 完整授权下才认定本次扫描为「完整观察」；部分授权/单类型失败时
                        // 「扫描中缺失」不可信，身份同步只增改不判缺失（Spec §1.1）
                        val completeScan =
                            com.slides.app.permissions.Permissions.allGranted(getApplication())
                        // 扫描后：同步身份映射（先核对再更新，ID复用摘除收藏不继承）
                        // + legacy 重绑定（键内元数据一致性校验）
                        // + 失联收藏哈希确认重绑定（完整扫描 + 唯一候选 + 内容哈希一致）
                        runCatching {
                            identityService.syncIdentities(items)
                            identityService.rebindLegacyFavorites(items)
                            identityService.rebindMissingFavorites(items, completeScan)
                            // 收藏证据优先补全：仍可访问的收藏立即持有哈希证据，
                            // 后续 copy+delete 型改名/移动才可自动重绑定（T008 真机回归修复）
                            identityService.ensureHashesForFavorites(items)
                        }
                        // 重绑定会改写 favorites 表（旧 appId → 新 appId）。Room Flow 的
                        // invalidation 是异步的，存在「allItems 已更新为新扫描结果、但
                        // favoriteByTime 仍持旧 appId」的竞态窗口，导致收藏 Tab 短暂/持续
                        // 看不到刚重绑定的收藏。这里在重绑定后主动同步一次收藏状态，
                        // 不依赖 Flow 异步发射，确保改名/移动后切回应用立即可见。
                        runCatching {
                            val fresh = favoriteRepo.currentFavorites()
                            _state.update {
                                it.copy(
                                    favoriteKeys = fresh.mapTo(HashSet()) { f -> f.stableKey },
                                    favoriteByTime = fresh,
                                )
                            }
                        }
                        // 内容哈希补全：预算内后台执行，为后续失联重绑定积累证据
                        runCatching {
                            identityService.backfillContentHashes(items, budgetMs = 4_000L, maxItems = 2_000)
                        }
                        // 小米改名/移动是异步 copy+delete：新文件可能短暂处于 IS_PENDING
                        // （被本查询过滤）或内容流未就绪。两种情况都需要延迟重扫兜底：
                        // 1) 仍有失联收藏（新文件未进 MediaStore）→ 等窗口结束重绑；
                        // 2) 已重绑定但新 appId 不在本次扫描结果里（移动跨目录 IS_PENDING
                        //    更长）→ allItems 缺新文件、收藏 Tab 空白，需重扫刷新 allItems。
                        // 统一：只要本次重绑定发生过、或仍有失联收藏，就安排延迟重扫。
                        scheduleDelayedRescanIfNeeded(items, completeScan)
                    }
                },
                onFailure = {
                    if (g == generation) {
                        // 清空旧索引，避免失权/查询失败后继续展示过期媒体（收藏记录保留）
                        _state.update { it.copy(loading = false, loadError = true, allItems = emptyList(), directories = emptyList()) }
                    }
                }
            )
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoriteRepo.observeFavorites().collect { list ->
                _state.update {
                    it.copy(
                        favoriteKeys = list.mapTo(HashSet()) { f -> f.stableKey },
                        favoriteByTime = list,
                    )
                }
            }
        }
    }

    fun isFavorite(item: MediaItem): Boolean = item.stableKey in _state.value.favoriteKeys

    /**
     * 切换收藏（事务化）：失败时给出可观察反馈（favoriteError），不静默吞异常。
     * 收藏成功后立即对当前文件计算内容哈希并落库（收藏时取证）——这是后续
     * copy+delete 型改名/移动自动重绑定的证据基础；取证失败不影响收藏本身。
     */
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val nowFav = favoriteRepo.toggle(item.stableKey, System.currentTimeMillis())
                if (nowFav) identityService.ensureContentHash(item)
                nowFav
            }.onFailure {
                _state.update { it.copy(favoriteError = true) }
            }
        }
    }

    /** 清除收藏失败提示（UI 提示后调用）。 */
    fun clearFavoriteError() {
        _state.update { it.copy(favoriteError = false) }
    }

    fun selectDirectory(key: String?) {
        _state.update { it.copy(selectedDirectoryKey = key) }
    }

    fun selectTab(tab: HomeTab) {
        _state.update { it.copy(tab = tab) }
    }

    fun setColumns(c: Int) {
        _state.update { it.copy(columns = c.coerceIn(2, 4)) }
    }

    fun refresh() = load()

    /**
     * 失联收藏延迟重扫：小米 copy+delete 型改名/移动是异步的，新文件可能短暂处于
     * IS_PENDING（被扫描过滤）或内容流未就绪，单次扫描无法重绑定。若仍有失联收藏，
     * 每隔 [DELAYED_RESCAN_INTERVAL_MS] 重扫一次，最多 [MAX_DELAYED_RESCANS] 次，
     * 等 MediaStore 异步窗口结束后自动恢复收藏；到达上限仍失联则停止（不无限重扫）。
     */
    private fun scheduleDelayedRescanIfNeeded(
        items: List<MediaItem>,
        completeScan: Boolean,
    ) {
        // 进入条件：仍有失联收藏（新文件未进 MediaStore），或已重绑定但收藏 key 不在
        // 本次扫描结果里（移动跨目录 IS_PENDING 更长，新 appId 未出现在 allItems）。
        // 两者任一成立都需要延迟重扫，否则收藏 Tab 会空白且不再自愈。
        viewModelScope.launch(Dispatchers.IO) {
            val needsRescan = runCatching {
                identityService.hasUnresolvedMissingFavorites(items, completeScan) ||
                    favoritesMissingFromScan(items)
            }.getOrDefault(false)
            if (!needsRescan) return@launch

            // 逐轮重扫：每轮先主动触发系统媒体扫描并等它完成（小米移动目录后 MediaStore
            // 异步延迟，应用进程内反复 query 拿不到新行），扫描完成后再 query，保证每轮都能
            // 拿到最新行。第一轮不 delay（切回应用应立即恢复收藏），后续轮次才间隔等待。
            for (round in 1..MAX_DELAYED_RESCANS) {
                if (round > 1) delay(DELAYED_RESCAN_INTERVAL_MS)
                android.util.Log.d("HomeViewModel", "delayedRescan round=$round")
                // 扫描外部存储并等其完成（强制 MediaStore 刷新移动/改名后的新文件）
                suspendCoroutine<Unit> { cont ->
                    MediaRepository.scanExternalStorage(getApplication()) { cont.resume(Unit) }
                }
                val freshItems = MediaRepository.queryAllAccessible(getApplication())
                    .getOrElse { return@launch }
                // 重新同步 + 重绑定（只做身份恢复，不重设 allItems，避免闪烁）
                runCatching {
                    identityService.syncIdentities(freshItems)
                    identityService.rebindLegacyFavorites(freshItems)
                    identityService.rebindMissingFavorites(freshItems, completeScan)
                    identityService.ensureHashesForFavorites(freshItems)
                }
                // 退出条件：收藏全部可显示（key 都在 freshItems 里），且无失联收藏
                val allVisible = runCatching {
                    val favs = favoriteRepo.currentFavorites()
                    val keys = freshItems.mapTo(HashSet()) { it.stableKey }
                    favs.all { it.stableKey in keys } &&
                        !identityService.hasUnresolvedMissingFavorites(freshItems, completeScan)
                }.getOrDefault(false)
                if (allVisible) {
                    // 收藏全部可显示：同步收藏状态 + 更新 allItems，让收藏立即可见
                    _state.update {
                        val fresh = favoriteRepo.currentFavorites()
                        it.copy(
                            allItems = freshItems,
                            directories = deriveDirectories(freshItems),
                            favoriteKeys = fresh.mapTo(HashSet()) { f -> f.stableKey },
                            favoriteByTime = fresh,
                        )
                    }
                    return@launch
                }
            }
        }
    }

    /** 收藏 key 是否全部出现在本次扫描结果里（false = 有收藏无法显示，需重扫刷新 allItems）。 */
    private suspend fun favoritesMissingFromScan(items: List<MediaItem>): Boolean {
        val favs = favoriteRepo.currentFavorites()
        if (favs.isEmpty()) return false
        val keys = items.mapTo(HashSet()) { it.stableKey }
        return favs.any { it.stableKey !in keys }
    }

    companion object {
        private const val DELAYED_RESCAN_INTERVAL_MS = 2_000L
        private const val MAX_DELAYED_RESCANS = 4
    }

    private fun deriveDirectories(items: List<MediaItem>): List<MediaDirectory> {
        val dirs = items.groupBy { it.directoryKey }
            .map { (key, list) ->
                val first = list.first()
                MediaDirectory(
                    key = key,
                    volumeName = first.volumeName,
                    relativePath = first.relativePath,
                    name = first.bucketName,
                    imageCount = list.count { !it.isVideo },
                    videoCount = list.count { it.isVideo },
                )
            }
            .sortedWith(compareByDescending<MediaDirectory> { it.totalCount }.thenBy { it.name })

        // 同名不同卷/路径：不做显示名后缀区分（按 Reviewer/用户要求移除），但目录身份仍以 卷+路径 隔离
        return dirs
    }
}
