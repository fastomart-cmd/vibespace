package com.vibespace.app.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vibespace.app.data.api.Post
import com.vibespace.app.ui.common.PagedState
import com.vibespace.app.ui.common.PostActions
import com.vibespace.app.ui.components.ErrorState
import com.vibespace.app.ui.components.InlineError
import com.vibespace.app.ui.components.LoadingRow
import com.vibespace.app.ui.components.LoadingState
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.components.PostCard
import com.vibespace.app.ui.components.ReportDialog
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.vibespace.app.data.repo.ViewVisibility
import com.vibespace.app.ui.common.appContainer
import kotlinx.coroutines.flow.distinctUntilChanged

/** Paged list of post cards with pull-to-refresh, infinite scroll and loading / error / empty states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostList(
    state: PagedState<Post>,
    actions: PostActions,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPost: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    onHashtag: (String) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyListScope.() -> Unit)? = null,
) {
    val gate = LocalLoginGate.current
    var reporting by remember { mutableStateOf<Post?>(null) }
    val listState = rememberLazyListState()
    val viewCounts by appContainer().viewTracker.counts.collectAsState()

    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(listState, state.nextCursor) {
        snapshotFlow { nearEnd }.collect { if (it && state.nextCursor != null) onLoadMore() }
    }

    TrackPostViews(listState, state.items)

    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.items.isEmpty() && header == null -> LoadingState()
            state.error != null && state.items.isEmpty() && header == null -> ErrorState(state.error, onRetry = onRefresh)
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                header?.invoke(this)
                if (state.isLoading && state.items.isEmpty()) item { LoadingRow() }
                if (state.error != null && state.items.isEmpty()) item { InlineError(state.error, onRefresh) }
                if (state.isEmpty) item { Box(Modifier.fillMaxSize()) { empty() } }
                items(state.items, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        viewCount = viewCounts[post.id] ?: post.viewCount,
                        onOpen = { onOpenPost(post.id) },
                        onAuthor = { onOpenUser(post.author.username) },
                        onClap = { if (gate.require()) actions.clap(post) },
                        onSave = { if (gate.require()) actions.save(post) },
                        onComments = { onOpenPost(post.id) },
                        onReport = { if (gate.require()) reporting = post },
                        onHashtag = onHashtag,
                        onDelete = { actions.delete(post) },
                    )
                }
                if (state.isLoadingMore) item { LoadingRow() }
                state.loadMoreError?.let { e -> item { InlineError(e, onLoadMore) } }
            }
        }
    }
    reporting?.let { p ->
        ReportDialog(onSubmit = { reason -> actions.report("post", p.id, reason); reporting = null }, onDismiss = { reporting = null })
    }
}

/**
 * Reports which posts are at least 50% on screen to the app's ViewTracker (which counts a view after 1 s).
 * Nothing counts while the screen is paused; queued views are sent when it pauses or the list leaves.
 */
@Composable
private fun TrackPostViews(listState: LazyListState, posts: List<Post>) {
    val tracker = appContainer().viewTracker
    val source = remember { java.util.UUID.randomUUID().toString() }
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateAsState()
    val ids by rememberUpdatedState(posts.mapTo(HashSet()) { it.id })

    LaunchedEffect(listState, tracker) {
        snapshotFlow {
            if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                emptySet()
            } else {
                val info = listState.layoutInfo
                ViewVisibility.visibleIds(
                    items = info.visibleItemsInfo.map { ViewVisibility.Item(it.key, it.offset, it.size) },
                    viewportStart = info.viewportStartOffset,
                    viewportEnd = info.viewportEndOffset,
                    isPost = { key -> key is String && key in ids },
                )
            }
        }.distinctUntilChanged().collect { tracker.setVisible(source, it) }
    }

    DisposableEffect(owner, tracker) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) tracker.flushNow() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            tracker.setVisible(source, emptySet())
            tracker.flushNow()
        }
    }
}
