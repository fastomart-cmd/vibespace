package com.vibespace.app.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.ImagePayload
import com.vibespace.app.data.repo.MediaRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.ui.common.UiEvent
import com.vibespace.app.ui.common.Validators
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads a picked image (content:// URI) into an upload-ready payload. Android impl in ImageReader.kt. */
fun interface ImageReader {
    suspend fun read(uri: String): ImagePayload
}

enum class CreateStage { EDITING, UPLOADING, PUBLISHING }

data class CreatePostState(
    val caption: String = "",
    val imageUri: String? = null,
    val stage: CreateStage = CreateStage.EDITING,
    /** String resource id of a local validation problem. */
    val validation: Int? = null,
) {
    val busy: Boolean get() = stage != CreateStage.EDITING
    val canPublish: Boolean get() = !busy && (caption.isNotBlank() || imageUri != null) && caption.length <= Validators.MAX_CAPTION
}

class CreatePostViewModel(
    private val posts: PostRepository,
    private val media: MediaRepository,
    private val auth: AuthRepository,
    private val imageReader: ImageReader,
) : ViewModel() {
    private val _state = MutableStateFlow(CreatePostState())
    val state: StateFlow<CreatePostState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _published = MutableSharedFlow<Post>(extraBufferCapacity = 1)
    val published: SharedFlow<Post> = _published.asSharedFlow()

    val isLoggedIn = auth.isLoggedIn

    fun onCaption(text: String) = _state.update {
        it.copy(caption = text, validation = if (text.length > Validators.MAX_CAPTION) R.string.caption_too_long else null)
    }

    fun onImagePicked(uri: String?) = _state.update { it.copy(imageUri = uri) }

    fun removeImage() = _state.update { it.copy(imageUri = null) }

    fun publish() {
        val s = _state.value
        if (s.busy) return
        if (!auth.isLoggedInNow()) { _events.tryEmit(UiEvent.LoginRequired); return }
        if (s.caption.isBlank() && s.imageUri == null) { _state.update { it.copy(validation = R.string.caption_required) }; return }
        if (s.caption.length > Validators.MAX_CAPTION) { _state.update { it.copy(validation = R.string.caption_too_long) }; return }

        viewModelScope.launch {
            val result: Result<Post> = if (s.imageUri != null) {
                _state.update { it.copy(stage = CreateStage.UPLOADING) }
                val payload = try { imageReader.read(s.imageUri) } catch (e: Exception) {
                    fail(AppError.Unknown(e)); return@launch
                }
                val mediaId = media.upload(payload, MediaRepository.PURPOSE_POST).getOrElse { fail(it); return@launch }
                _state.update { it.copy(stage = CreateStage.PUBLISHING) }
                posts.createImagePost(s.caption, listOf(mediaId))
            } else {
                _state.update { it.copy(stage = CreateStage.PUBLISHING) }
                posts.createTextPost(s.caption)
            }
            result
                .onSuccess { p ->
                    _state.value = CreatePostState()
                    _events.tryEmit(UiEvent.Message(R.string.post_published))
                    _published.tryEmit(p)
                }
                .onFailure(::fail)
        }
    }

    private fun fail(t: Throwable) {
        _state.update { it.copy(stage = CreateStage.EDITING) }
        val e = t as? AppError ?: AppError.Unknown(t)
        _events.tryEmit(if (e.isUnauthenticated) UiEvent.LoginRequired else UiEvent.Error(e))
    }
}
