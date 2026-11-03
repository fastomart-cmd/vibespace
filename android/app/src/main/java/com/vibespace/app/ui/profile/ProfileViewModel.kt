package com.vibespace.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.api.Profile
import com.vibespace.app.data.api.UserSummary
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.data.repo.UserListKind
import com.vibespace.app.data.repo.UserRepository
import com.vibespace.app.ui.common.Paged
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

data class ProfileState(
    val profile: Profile? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val isSelf: Boolean = false,
    val busy: Boolean = false,
)

/** Own profile (username = null → the logged-in user) or someone else's public profile. */
class ProfileViewModel(
    private val username: String?,
    private val users: UserRepository,
    private val posts: PostRepository,
    private val auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val isLoggedIn = auth.isLoggedIn
    val me = auth.me

    private var resolvedUsername: String? = username

    val postsPager = Paged(viewModelScope, Post::id) { cursor ->
        val u = resolvedUsername ?: return@Paged Result.failure(AppError.LoginRequired)
        users.posts(u, cursor)
    }

    val actions = PostActions(viewModelScope, posts, auth::isLoggedInNow, _events, postsPager::replace, postsPager::remove)

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (username == null) {
                if (!auth.isLoggedInNow()) { _state.update { it.copy(loading = false) }; return@launch }
                val me = auth.me.value ?: auth.refreshMe().getOrNull()
                resolvedUsername = me?.username
            }
            val u = resolvedUsername
            if (u == null) { _state.update { it.copy(loading = false, error = AppError.Unknown()) }; return@launch }
            users.profile(u)
                .onSuccess { p ->
                    val self = username == null || auth.me.value?.id == p.id
                    _state.update { it.copy(profile = p, loading = false, isSelf = self) }
                    if (p.canViewContent) postsPager.refresh() else postsPager.reset()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }

    fun toggleVibeMate() = relationOp { users.toggleVibeMate(it) }

    fun toggleVibeStar() = relationOp { users.toggleVibeStar(it) }

    private fun relationOp(op: suspend (Profile) -> Result<Profile>) {
        val p = _state.value.profile ?: return
        if (!auth.isLoggedInNow()) { _events.tryEmit(UiEvent.LoginRequired); return }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            op(p).onSuccess { np -> _state.update { it.copy(profile = np, busy = false) } }
                .onFailure { e -> _state.update { it.copy(busy = false) }; emitError(e) }
        }
    }

    fun toggleBlock() {
        val p = _state.value.profile ?: return
        if (!auth.isLoggedInNow()) { _events.tryEmit(UiEvent.LoginRequired); return }
        val blocked = p.viewer?.isBlocked == true
        viewModelScope.launch {
            (if (blocked) users.unblock(p.username) else users.block(p.username))
                .onSuccess { load() }
                .onFailure(::emitError)
        }
    }

    fun reportUser(reason: String) {
        val p = _state.value.profile ?: return
        actions.report("user", p.id, reason)
    }

    private fun emitError(t: Throwable) {
        val e = t as? AppError ?: AppError.Unknown(t)
        _events.tryEmit(if (e.isUnauthenticated) UiEvent.LoginRequired else UiEvent.Error(e))
    }
}

/** VibeMates / Vibing with / VibeStars lists. */
class UserListViewModel(private val username: String, val kind: UserListKind, users: UserRepository) : ViewModel() {
    val pager = Paged<UserSummary>(viewModelScope, { it.id }) { cursor -> users.list(username, kind, cursor) }
    init { pager.ensureLoaded() }
}

/** Settings → Blocked users. */
class BlockedUsersViewModel(private val users: UserRepository) : ViewModel() {
    val pager = Paged<UserSummary>(viewModelScope, { it.id }) { cursor -> users.blocked(cursor) }
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()
    init { pager.ensureLoaded() }

    fun unblock(u: UserSummary) {
        viewModelScope.launch {
            users.unblock(u.username).onSuccess { pager.remove(u.id) }.onFailure { _events.tryEmit(UiEvent.Error(it as? AppError ?: AppError.Unknown(it))) }
        }
    }
}

data class EditProfileState(
    val displayName: String = "",
    val bio: String = "",
    val avatarUrl: String? = null,
    val newAvatarUri: String? = null,
    val removeAvatar: Boolean = false,
    val saving: Boolean = false,
    val loaded: Boolean = false,
)

class EditProfileViewModel(
    private val auth: AuthRepository,
    private val media: com.vibespace.app.data.repo.MediaRepository,
    private val imageReader: com.vibespace.app.ui.create.ImageReader,
) : ViewModel() {
    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val me = auth.me.value ?: auth.refreshMe().getOrNull()
            if (me != null) _state.value = EditProfileState(me.displayName, me.bio, me.avatarUrl, loaded = true)
        }
    }

    fun onName(v: String) = _state.update { it.copy(displayName = v.take(60)) }
    fun onBio(v: String) = _state.update { it.copy(bio = v.take(300)) }
    fun onAvatarPicked(uri: String) = _state.update { it.copy(newAvatarUri = uri, removeAvatar = false) }
    fun onRemoveAvatar() = _state.update { it.copy(newAvatarUri = null, removeAvatar = true, avatarUrl = null) }

    fun save() {
        val s = _state.value
        if (s.saving || s.displayName.isBlank()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            var avatarId: String? = if (s.removeAvatar) "" else null
            if (s.newAvatarUri != null) {
                val payload = try { imageReader.read(s.newAvatarUri) } catch (e: Exception) { fail(AppError.Unknown(e)); return@launch }
                avatarId = media.upload(payload, com.vibespace.app.data.repo.MediaRepository.PURPOSE_AVATAR).getOrElse { fail(it); return@launch }
            }
            auth.updateProfile(displayName = s.displayName, bio = s.bio, avatarMediaId = avatarId)
                .onSuccess { _state.update { it.copy(saving = false) }; _events.tryEmit(UiEvent.Message(R.string.saved_ok)); _saved.tryEmit(Unit) }
                .onFailure(::fail)
        }
    }

    private fun fail(t: Throwable) {
        _state.update { it.copy(saving = false) }
        _events.tryEmit(UiEvent.Error(t as? AppError ?: AppError.Unknown(t)))
    }
}
