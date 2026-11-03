package com.vibespace.app.ui.common

import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PagedState<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
    /** First load (no items yet). */
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Error of the last first-load/refresh (shown full-screen when there are no items). */
    val error: AppError? = null,
    /** Error while loading the next page (shown as a retry row). */
    val loadMoreError: AppError? = null,
    val loadedOnce: Boolean = false,
) {
    val endReached: Boolean get() = loadedOnce && nextCursor == null
    val isEmpty: Boolean get() = loadedOnce && items.isEmpty() && error == null
}

/** Cursor pagination over the backend's { items, nextCursor } pages. */
class Paged<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> String,
    private val loader: suspend (cursor: String?) -> Result<Page<T>>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()
    private var job: Job? = null

    /** Load the first page if nothing has been loaded yet. */
    fun ensureLoaded() {
        val s = _state.value
        if (!s.loadedOnce && !s.isLoading) refresh()
    }

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(isLoading = !it.loadedOnce || it.items.isEmpty(), isRefreshing = it.loadedOnce && it.items.isNotEmpty(), error = null, loadMoreError = null) }
        job = scope.launch {
            loader(null)
                .onSuccess { page ->
                    _state.value = PagedState(items = page.items.distinctBy(key), nextCursor = page.nextCursor, loadedOnce = true)
                }
                .onFailure { e ->
                    _state.update { it.copy(isLoading = false, isRefreshing = false, error = e as? AppError ?: AppError.Unknown(e), loadedOnce = true) }
                }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.isLoadingMore || s.isLoading || s.isRefreshing) return
        _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        job = scope.launch {
            loader(cursor)
                .onSuccess { page ->
                    _state.update { cur ->
                        val seen = cur.items.map(key).toHashSet()
                        cur.copy(items = cur.items + page.items.filter { key(it) !in seen }, nextCursor = page.nextCursor, isLoadingMore = false)
                    }
                }
                .onFailure { e -> _state.update { it.copy(isLoadingMore = false, loadMoreError = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }

    fun replace(item: T) = _state.update { s -> s.copy(items = s.items.map { if (key(it) == key(item)) item else it }) }

    fun remove(id: String) = _state.update { s -> s.copy(items = s.items.filterNot { key(it) == id }) }

    fun prepend(item: T) = _state.update { s -> s.copy(items = listOf(item) + s.items.filterNot { key(it) == key(item) }) }

    fun reset() {
        job?.cancel()
        _state.value = PagedState()
    }
}
