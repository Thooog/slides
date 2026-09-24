package com.slides.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slides.app.data.FavoriteEntity
import com.slides.app.data.FavoriteRepository
import com.slides.app.data.MediaDirectory
import com.slides.app.data.MediaItem
import com.slides.app.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /** 切换收藏：失败时不改变状态（不显示虚假成功）。 */
    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                favoriteRepo.toggle(item.stableKey, System.currentTimeMillis())
            }
            // 失败静默：Room Flow 未变化，UI 状态自然保持一致，不显示虚假成功。
        }
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
