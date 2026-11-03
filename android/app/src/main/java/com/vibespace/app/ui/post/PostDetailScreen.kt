package com.vibespace.app.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibespace.app.R
import com.vibespace.app.data.api.Comment
import com.vibespace.app.ui.common.CollectEvents
import com.vibespace.app.ui.common.relativeTime
import com.vibespace.app.ui.components.Avatar
import com.vibespace.app.ui.components.ErrorState
import com.vibespace.app.ui.components.InlineError
import com.vibespace.app.ui.components.LoadingRow
import com.vibespace.app.ui.components.LoadingState
import com.vibespace.app.ui.components.LocalLoginGate
import com.vibespace.app.ui.components.PostCard
import com.vibespace.app.ui.components.ReportDialog
import com.vibespace.app.ui.theme.VibeColors

private const val MAX_INDENT_DEPTH = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostDetailScreen(
    vm: PostDetailViewModel,
    onBack: () -> Unit,
    onOpenUser: (String) -> Unit,
    onHashtag: (String) -> Unit,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val gate = LocalLoginGate.current
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle()
    CollectEvents(vm.events)
    var reporting by remember { mutableStateOf<Pair<String, String>?>(null) } // (type, id)

    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }

    Scaffold(
        containerColor = VibeColors.Black,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.post_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VibeColors.Black),
            )
        },
        bottomBar = {
            if (s.post != null) {
                Composer(
                    draft = s.draft,
                    replyTo = s.replyTo,
                    sending = s.sending,
                    onChange = vm::onDraftChange,
                    onCancelReply = vm::cancelReply,
                    loggedIn = loggedIn,
                    onGuest = { gate.prompt() },
                    onSend = vm::send,
                )
            }
        },
    ) { pad ->
        val post = s.post
        when {
            s.loading && post == null -> LoadingState(Modifier.padding(pad))
            s.error != null && post == null -> ErrorState(s.error!!, onRetry = vm::load, modifier = Modifier.padding(pad))
            post != null -> LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item(key = "post") {
                    PostCard(
                        post = post,
                        expanded = true,
                        onOpen = {},
                        onAuthor = { onOpenUser(post.author.username) },
                        onClap = { if (gate.require()) vm.actions.clap(post) },
                        onSave = { if (gate.require()) vm.actions.save(post) },
                        onComments = {},
                        onReport = { if (gate.require()) reporting = "post" to post.id },
                        onHashtag = onHashtag,
                        onDelete = { vm.actions.delete(post) },
                    )
                }
                item(key = "header") {
                    Text(
                        "${stringResource(R.string.vibetalk)} · ${post.counts.vibeTalk}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                s.pinned?.let { pinned ->
                    commentThread(pinned, 0, s, vm, onOpenUser, onReport = { if (gate.require()) reporting = "comment" to it.id }, pinnedBadge = true)
                }
                s.comments.forEach { c ->
                    commentThread(c, 0, s, vm, onOpenUser, onReport = { if (gate.require()) reporting = "comment" to it.id })
                }
                if (s.commentsLoading) item { LoadingRow() }
                s.commentsError?.let { e -> item { InlineError(e) { vm.loadComments(reset = !s.commentsLoaded || s.comments.isEmpty()) } } }
                if (s.commentsLoaded && !s.commentsLoading && s.comments.isEmpty() && s.pinned == null && s.commentsError == null) {
                    item {
                        Text(
                            stringResource(R.string.vibetalk_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                if (s.commentsCursor != null && !s.commentsLoading) {
                    item { TextButton(onClick = { vm.loadComments() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.more)) } }
                }
            }
        }
    }
    reporting?.let { (type, id) ->
        ReportDialog(onSubmit = { r -> vm.actions.report(type, id, r); reporting = null }, onDismiss = { reporting = null })
    }
}

/** A comment and, when expanded, its replies (recursively, so replies to replies nest too). */
private fun LazyListScope.commentThread(
    c: Comment,
    depth: Int,
    s: PostDetailState,
    vm: PostDetailViewModel,
    onOpenUser: (String) -> Unit,
    onReport: (Comment) -> Unit,
    pinnedBadge: Boolean = false,
) {
    item(key = "c-${c.id}-$depth") {
        CommentRow(c, depth, pinnedBadge, onOpenUser, onReply = { vm.startReply(c) }, onDelete = { vm.deleteComment(c) }, onReport = { onReport(c) })
    }
    val r = s.replies[c.id]
    if (c.replyCount > 0) {
        item(key = "t-${c.id}") {
            TextButton(onClick = { vm.toggleReplies(c) }, modifier = Modifier.padding(start = (56 + indent(depth)).dp)) {
                Text(if (r?.expanded == true) stringResource(R.string.hide_replies) else stringResource(R.string.view_replies, c.replyCount), color = VibeColors.NeonCyan)
            }
        }
    }
    if (r?.expanded == true) {
        r.items.forEach { reply -> commentThread(reply, depth + 1, s, vm, onOpenUser, onReport) }
        if (r.loading) item(key = "l-${c.id}") { LoadingRow() }
        r.error?.let { e -> item(key = "e-${c.id}") { InlineError(e) { vm.loadReplies(c.id, more = r.items.isNotEmpty()) } } }
        if (r.nextCursor != null && !r.loading) {
            item(key = "m-${c.id}") {
                TextButton(onClick = { vm.loadReplies(c.id, more = true) }, modifier = Modifier.padding(start = (56 + indent(depth + 1)).dp)) {
                    Text(stringResource(R.string.load_more_replies))
                }
            }
        }
    }
}

private fun indent(depth: Int) = 28 * depth.coerceAtMost(MAX_INDENT_DEPTH)

@Composable
private fun CommentRow(
    c: Comment,
    depth: Int,
    pinned: Boolean,
    onOpenUser: (String) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = (16 + indent(depth)).dp, end = 12.dp, top = 8.dp, bottom = 2.dp)) {
        val author = c.author
        Avatar(author?.avatarUrl, author?.displayName ?: "?", size = if (depth == 0) 36.dp else 28.dp, modifier = Modifier.clickable(enabled = author != null) { author?.let { onOpenUser(it.username) } })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(author?.displayName ?: stringResource(R.string.comment_deleted), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Text(relativeTime(c.createdAt) + if (c.editedAt != null) " · " + stringResource(R.string.edited) else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pinned) {
                    Spacer(Modifier.width(6.dp))
                    Text("📌 " + stringResource(R.string.pinned), style = MaterialTheme.typography.labelSmall, color = VibeColors.NeonCyan)
                }
            }
            if (c.isDeleted || c.body == null) {
                Text(stringResource(R.string.comment_deleted), fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(c.body, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = onReply) { Text(stringResource(R.string.reply), style = MaterialTheme.typography.labelMedium) }
                    if (c.viewer?.isAuthor == true) {
                        TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium) }
                    } else {
                        IconButton(onClick = onReport) { Icon(painterResource(R.drawable.ic_flag), stringResource(R.string.action_report), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    replyTo: Comment?,
    sending: Boolean,
    onChange: (String) -> Unit,
    onCancelReply: () -> Unit,
    loggedIn: Boolean,
    onGuest: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(VibeColors.Surface).navigationBarsPadding().imePadding()) {
        HorizontalDivider(color = VibeColors.Outline)
        if (replyTo != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reply_hint, replyTo.author?.username ?: ""), style = MaterialTheme.typography.bodySmall, color = VibeColors.NeonCyan, modifier = Modifier.weight(1f))
                IconButton(onClick = onCancelReply) { Icon(Icons.Filled.Close, stringResource(R.string.cancel_reply)) }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (loggedIn) onChange(it) else onGuest() },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (replyTo != null) stringResource(R.string.reply_hint, replyTo.author?.username ?: "") else stringResource(R.string.vibetalk_hint)) },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
            )
            if (sending) {
                CircularProgressIndicator(Modifier.padding(12.dp).width(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { if (loggedIn) onSend() else onGuest() }, enabled = draft.isNotBlank() || !loggedIn) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send), tint = VibeColors.NeonPurple)
                }
            }
        }
    }
}
