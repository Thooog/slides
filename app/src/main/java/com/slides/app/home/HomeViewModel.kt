package com.slides.app.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.slides.app.data.MediaDirectory
import com.slides.app.data.MediaItem
import com.slides.app.data.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val allItems: List<MediaItem> = emptyList(),
    val directories: List<MediaDirectory> = emptyList(),
    val selectedBucketId: String? = null, // null = 全部
    val columns: Int = 3,
) {
    val empty: Boolean get() = !loading && !loadError && allItems.isEmpty()
    val visibleItems: List<MediaItem>
        get() = if (selectedBucketId == null) allItems else allItems.filter { it.bucketId == selectedBucketId }
}

/**
 * 首页状态：加载可访问媒体、派生动态目录、目录/网格列数。
 * 只读浏览；此 ViewModel 不触发任何媒体写操作。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = true, loadError = false) }
            MediaRepository.queryAllAccessible(getApplication()).fold(
                onSuccess = { items ->
                    val dirs = deriveDirectories(items)
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = false,
                            allItems = items,
                            directories = dirs,
                        )
                    }
                },
                onFailure = {
                    _state.update { it.copy(loading = false, loadError = true) }
                }
            )
        }
    }

    fun selectBucket(bucketId: String?) {
        _state.update { it.copy(selectedBucketId = bucketId) }
    }

    fun setColumns(c: Int) {
        _state.update { it.copy(columns = c.coerceIn(2, 4)) }
    }

    fun refresh() = load()

    private fun deriveDirectories(items: List<MediaItem>): List<MediaDirectory> =
        items.groupBy { it.bucketId to it.bucketName }
            .map { (key, list) ->
                MediaDirectory(
                    bucketId = key.first,
                    name = key.second,
                    imageCount = list.count { !it.isVideo },
                    videoCount = list.count { it.isVideo },
                )
            }
            .sortedWith(compareByDescending<MediaDirectory> { it.totalCount }.thenBy { it.name })
}