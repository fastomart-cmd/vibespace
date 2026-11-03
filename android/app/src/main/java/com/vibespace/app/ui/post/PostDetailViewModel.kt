package com.vibespace.app.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Comment
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.data.repo.ViewTracker
import com.vibespace.app.ui.common.PostActions
import com.vibespace.app.ui.common.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Replies under one comment (loaded on demand, paged). */
data class RepliesState(
    val items: List<Comment> = emptyList(),
    val nextCursor: String? = null,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val error: AppError? = null,
)

data class PostDetailState(
    val post: Post? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val pinned: Comment? = null,
    val comments: List<Comment> = emptyList(),
    val commentsCursor: String? = null,
    val commentsLoading: Boolean = false,
    val commentsLoaded: Boolean = false,
    val commentsError: AppError? = null,
    val replies: Map<String, RepliesState> = emptyMap(),
    /** The comment being replied to, or null for a top-level comment. */
    val replyTo: Comment? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val deleted: Boolean = false,
)

class PostDetailViewModel(
    private val postId: String,
    private val posts: PostRepository,
    private val auth: AuthRepository,
    /** Records the view when the post opens; null in tests that don't care. */
    private val views: ViewTracker? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(PostDetailState())
    val state: StateFlow<PostDetailState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val isLoggedIn: StateFlow<Boolean> = auth.isLoggedIn

    val actions = PostActions(
        scope = viewModelScope,
        repo = posts,
        isLoggedIn = auth::isLoggedInNow,
        events = _events,
        onUpdated = { p -> _state.update { it.copy(post = p) } },
        onRemoved = { _state.update { it.copy(deleted = true) } },
    )

    private var viewRecorded = false

    init { load() }

    /** Opening a post's detail counts as a view (once per screen, guests included). Failures are silent. */
    private fun recordView() {
        val tracker = views ?: return
        if (viewRecorded) return
        viewRecorded = true
        viewModelScope.launch {
            tracker.recordOpen(postId).onSuccess { r ->
                _state.update { s -> s.copy(post = s.post?.let { p -> if (r.viewCount > p.viewCount) p.copy(viewCount = r.viewCount) else p }) }
            }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            posts.post(postId)
                .onSuccess { p -> _state.update { it.copy(post = p, loading = false) }; recordView(); loadComments(reset = true) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }

    fun loadComments(reset: Boolean = false) {
        val s = _state.value
        if (s.commentsLoading) return
        val cursor = if (reset) null else (s.commentsCursor ?: return)
        _state.update { it.copy(commentsLoading = true, commentsError = null) }
        viewModelScope.launch {
            posts.comments(postId, cursor)
                .onSuccess { page ->
                    _state.update { cur ->
                        cur.copy(
                            pinned = if (reset) page.pinned else cur.pinned,
                            comments = if (reset) page.items else (cur.comments + page.items).distinctBy { it.id },
                            commentsCursor = page.nextCursor,
                            commentsLoading = false,
                            commentsLoaded = true,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(commentsLoading = false, commentsError = e as? AppError ?: AppError.Unknown(e), commentsLoaded = true) } }
        }
    }

    /** Show/hide replies; the first expand loads them. */
    fun toggleReplies(comment: Comment) {
        val r = _state.value.replies[comment.id] ?: RepliesState()
        if (r.expanded) {
            setReplies(comment.id) { it.copy(expanded = false) }
            return
        }
        setReplies(comment.id) { it.copy(expanded = true) }
        if (r.items.isEmpty()) loadReplies(comment.id, more = false)
    }

    fun loadReplies(commentId: String, more: Boolean) {
        val r = _state.value.replies[commentId] ?: RepliesState()
        if (r.loading) return
        val cursor = if (more) (r.nextCursor ?: return) else null
        setReplies(commentId) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            posts.replies(commentId, cursor)
                .onSuccess { page ->
                    setReplies(commentId) { cur ->
                        cur.copy(items = if (more) (cur.items + page.items).distinctBy { it.id } else page.items, nextCursor = page.nextCursor, loading = false)
                    }
                }
                .onFailure { e -> setReplies(commentId) { it.copy(loading = false, error = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }

    private fun setReplies(id: String, f: (RepliesState) -> RepliesState) =
        _state.update { s -> s.copy(replies = s.replies + (id to f(s.replies[id] ?: RepliesState()))) }

    fun onDraftChange(text: String) = _state.update { it.copy(draft = text.take(com.vibespace.app.ui.common.Validators.MAX_COMMENT)) }

    fun startReply(comment: Comment) {
        if (!auth.isLoggedInNow()) { _events.tryEmit(UiEvent.LoginRequired); return }
        _state.update { it.copy(replyTo = comment) }
    }

    fun cancelReply() = _state.update { it.copy(replyTo = null) }

    fun send() {
        val s = _state.value
        val body = s.draft.trim()
        if (body.isEmpty() || s.sending) return
        if (!auth.isLoggedInNow()) { _events.tryEmit(UiEvent.LoginRequired); return }
        val parent = s.replyTo
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            posts.addComment(postId, body, parent?.id)
                .onSuccess { c ->
                    _state.update { cur ->
                        val post = cur.post?.let { p -> p.copy(counts = p.counts.copy(vibeTalk = p.counts.vibeTalk + 1)) }
                        if (parent == null) {
                            cur.copy(post = post, comments = cur.comments + c, draft = "", sending = false)
                        } else {
                            val r = cur.replies[parent.id] ?: RepliesState()
                            cur.copy(
                                post = post,
                                comments = cur.comments.map { if (it.id == parent.id) it.copy(replyCount = it.replyCount + 1) else it },
                                replies = bumpReplyCount(cur.replies, parent.id) + (parent.id to r.copy(items = r.items + c, expanded = true)),
                                pinned = cur.pinned?.let { if (it.id == parent.id) it.copy(replyCount = it.replyCount + 1) else it },
                                draft = "",
                                replyTo = null,
                                sending = false,
                            )
                        }
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(sending = false) }
                    val err = e as? AppError ?: AppError.Unknown(e)
                    _events.tryEmit(if (err.isUnauthenticated) UiEvent.LoginRequired else UiEvent.Error(err))
                }
        }
    }

    /** Nested replies live in other comments' reply lists; bump the parent's count wherever it appears. */
    private fun bumpReplyCount(map: Map<String, RepliesState>, parentId: String) = map.mapValues { (_, r) ->
        r.copy(items = r.items.map { if (it.id == parentId) it.copy(replyCount = it.replyCount + 1) else it })
    }

    fun deleteComment(c: Comment) {
        viewModelScope.launch {
            posts.deleteComment(c.id)
                .onSuccess {
                    _state.update { cur ->
                        val tomb: (Comment) -> Comment? = { x -> if (x.id != c.id) x else if (x.replyCount > 0) x.copy(isDeleted = true, body = null, author = null) else null }
                        cur.copy(
                            comments = cur.comments.mapNotNull(tomb),
                            pinned = cur.pinned?.let(tomb),
                            replies = cur.replies.mapValues { (_, r) -> r.copy(items = r.items.mapNotNull(tomb)) },
                            post = cur.post?.let { p -> p.copy(counts = p.counts.copy(vibeTalk = (p.counts.vibeTalk - 1).coerceAtLeast(0))) },
                        )
                    }
                }
                .onFailure { e -> _events.tryEmit(UiEvent.Error(e as? AppError ?: AppError.Unknown(e))) }
        }
    }
}
