package com.vibespace.app.ui.common

import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.repo.PostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** VibeClap / save / report / delete on a post, shared by every screen that lists posts. */
class PostActions(
    private val scope: CoroutineScope,
    private val repo: PostRepository,
    private val isLoggedIn: () -> Boolean,
    private val events: MutableSharedFlow<UiEvent>,
    private val onUpdated: (Post) -> Unit,
    private val onRemoved: (String) -> Unit,
) {
    private val inFlight = mutableSetOf<String>()

    fun clap(post: Post) = mutate(post) { repo.toggleClap(it) }

    fun save(post: Post) = mutate(post) { repo.toggleSave(it) }

    private fun mutate(post: Post, op: suspend (Post) -> Result<Post>) {
        if (!isLoggedIn()) { events.tryEmit(UiEvent.LoginRequired); return }
        if (!inFlight.add(post.id)) return
        scope.launch {
            op(post).onSuccess(onUpdated).onFailure(::emitError)
            inFlight.remove(post.id)
        }
    }

    fun report(targetType: String, targetId: String, reason: String) {
        if (!isLoggedIn()) { events.tryEmit(UiEvent.LoginRequired); return }
        scope.launch {
            repo.report(targetType, targetId, reason)
                .onSuccess { events.tryEmit(UiEvent.Message(R.string.report_sent)) }
                .onFailure(::emitError)
        }
    }

    fun delete(post: Post) {
        scope.launch {
            repo.deletePost(post.id)
                .onSuccess { onRemoved(post.id); events.tryEmit(UiEvent.Message(R.string.post_deleted)) }
                .onFailure(::emitError)
        }
    }

    private fun emitError(t: Throwable) {
        val e = t as? AppError ?: AppError.Unknown(t)
        events.tryEmit(if (e.isUnauthenticated) UiEvent.LoginRequired else UiEvent.Error(e))
    }
}
