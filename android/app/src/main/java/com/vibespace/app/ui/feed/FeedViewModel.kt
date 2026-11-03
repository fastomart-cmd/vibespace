package com.vibespace.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.FeedType
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.ui.common.Paged
import com.vibespace.app.ui.common.PostActions
import com.vibespace.app.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** VibeFeed: For You (trending for now), Following (logged in only), Trending, Explore (+ hashtag search). */
class FeedViewModel(
    private val posts: PostRepository,
    private val auth: AuthRepository,
    initialTab: FeedType = FeedType.FOR_YOU,
    initialTag: String? = null,
) : ViewModel() {

    val isLoggedIn: StateFlow<Boolean> = auth.isLoggedIn

    private val _tag = MutableStateFlow(PostRepository.normalizeTag(initialTag))
    val tag: StateFlow<String?> = _tag.asStateFlow()

    private val _selected = MutableStateFlow(initialTab)
    val selected: StateFlow<FeedType> = _selected.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val pagers: Map<FeedType, Paged<Post>> = FeedType.entries.associateWith { type ->
        Paged(viewModelScope, Post::id) { cursor ->
            posts.feed(type, cursor, tag = if (type == FeedType.EXPLORE) _tag.value else null)
        }
    }

    val actions = PostActions(
        scope = viewModelScope,
        repo = posts,
        isLoggedIn = auth::isLoggedInNow,
        events = _events,
        onUpdated = { p -> pagers.values.forEach { it.replace(p) } },
        onRemoved = { id -> pagers.values.forEach { it.remove(id) } },
    )

    init {
        if (initialTab == FeedType.FOLLOWING && !auth.isLoggedInNow()) _selected.value = FeedType.FOR_YOU
        pager(_selected.value).ensureLoaded()
        // Logging in or out changes what every feed returns (viewer state, Following): reload.
        viewModelScope.launch {
            auth.isLoggedIn.drop(1).collect { loggedIn ->
                pagers.values.forEach { it.reset() }
                if (!loggedIn && _selected.value == FeedType.FOLLOWING) _selected.value = FeedType.FOR_YOU
                pager(_selected.value).ensureLoaded()
            }
        }
    }

    fun pager(type: FeedType): Paged<Post> = pagers.getValue(type)

    /** Tabs to show: Following only for logged-in users. */
    fun tabs(loggedIn: Boolean): List<FeedType> =
        if (loggedIn) listOf(FeedType.FOR_YOU, FeedType.FOLLOWING, FeedType.TRENDING, FeedType.EXPLORE)
        else listOf(FeedType.FOR_YOU, FeedType.TRENDING, FeedType.EXPLORE)

    fun select(type: FeedType) {
        if (type == FeedType.FOLLOWING && !auth.isLoggedInNow()) {
            _events.tryEmit(UiEvent.LoginRequired)
            return
        }
        _selected.value = type
        pager(type).ensureLoaded()
    }

    /** Hashtag search on Explore; blank clears the filter. */
    fun searchTag(raw: String?) {
        val t = PostRepository.normalizeTag(raw)
        if (t == _tag.value && pager(FeedType.EXPLORE).state.value.loadedOnce) return
        _tag.value = t
        pager(FeedType.EXPLORE).refresh()
    }

    fun refresh() = pager(_selected.value).refresh()

    /** A post created elsewhere should show up at the top of the user's feeds. */
    fun onPostCreated(post: Post) {
        pager(FeedType.FOLLOWING).let { if (it.state.value.loadedOnce) it.prepend(post) }
    }
}
