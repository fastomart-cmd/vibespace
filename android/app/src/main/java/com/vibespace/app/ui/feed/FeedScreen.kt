package com.vibespace.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibespace.app.R
import com.vibespace.app.data.repo.FeedType
import com.vibespace.app.ui.common.CollectEvents
import com.vibespace.app.ui.components.BrandMark
import com.vibespace.app.ui.components.EmptyState
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.theme.VibeColors

@Composable
fun feedTabLabel(t: FeedType): String = stringResource(
    when (t) {
        FeedType.FOR_YOU -> R.string.tab_for_you
        FeedType.FOLLOWING -> R.string.tab_following
        FeedType.TRENDING -> R.string.tab_trending
        FeedType.EXPLORE -> R.string.tab_explore
    },
)

/**
 * Home (all VibeFeed tabs) or, with [exploreOnly], the Explore destination (hashtag search + Explore feed).
 */
@Composable
fun FeedScreen(
    vm: FeedViewModel,
    onOpenPost: (String) -> Unit,
    onOpenUser: (String) -> Unit,
    exploreOnly: Boolean = false,
) {
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val tag by vm.tag.collectAsStateWithLifecycle()
    val gate = LocalLoginGate.current
    CollectEvents(vm.events)

    val current = if (exploreOnly) FeedType.EXPLORE else selected
    val pager = vm.pager(current)
    val state by pager.state.collectAsStateWithLifecycle()
    val onHashtag: (String) -> Unit = { t -> if (!exploreOnly) vm.select(FeedType.EXPLORE); vm.searchTag(t) }

    Column(Modifier.fillMaxSize().background(VibeColors.backgroundGradient)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (exploreOnly) {
                Text(stringResource(R.string.nav_explore), style = MaterialTheme.typography.headlineMedium)
            } else {
                BrandMark(showTagline = true)
            }
        }
        if (!exploreOnly) {
            val tabs = vm.tabs(loggedIn)
            val index = tabs.indexOf(current).coerceAtLeast(0)
            PrimaryScrollableTabRow(selectedTabIndex = index, edgePadding = 12.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                tabs.forEach { t ->
                    Tab(selected = t == current, onClick = { vm.select(t) }, text = { Text(feedTabLabel(t)) })
                }
            }
        }
        PostList(
            state = state,
            actions = vm.actions,
            onRefresh = pager::refresh,
            onLoadMore = pager::loadMore,
            onOpenPost = onOpenPost,
            onOpenUser = onOpenUser,
            onHashtag = onHashtag,
            header = if (current == FeedType.EXPLORE) {
                { item(key = "search") { HashtagSearch(tag, onSearch = vm::searchTag) } }
            } else null,
            empty = {
                when {
                    current == FeedType.FOLLOWING -> EmptyState(stringResource(R.string.feed_empty_title), stringResource(R.string.following_empty_body))
                    current == FeedType.EXPLORE && tag != null -> EmptyState(stringResource(R.string.explore_tag_results, tag ?: ""), stringResource(R.string.feed_empty_body))
                    else -> EmptyState(
                        stringResource(R.string.feed_empty_title),
                        stringResource(R.string.feed_empty_body),
                        actionLabel = if (!loggedIn) stringResource(R.string.signup) else null,
                        onAction = if (!loggedIn) ({ gate.prompt() }) else null,
                    )
                }
            },
        )
    }
}

@Composable
private fun HashtagSearch(current: String?, onSearch: (String?) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current?.let { "#$it" } ?: "") }
    val focus = LocalFocusManager.current
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(100) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text(stringResource(R.string.search_hashtag_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; onSearch(null); focus.clearFocus() }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear_search))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(text); focus.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VibeColors.NeonCyan, unfocusedBorderColor = VibeColors.Outline),
        )
        if (current != null) {
            Text(
                stringResource(R.string.explore_tag_results, current),
                style = MaterialTheme.typography.labelLarge,
                color = VibeColors.NeonCyan,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
        }
    }
}
