package com.vibespace.app.data.repo

import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.ForgotPasswordRequest
import com.vibespace.app.data.api.LoginRequest
import com.vibespace.app.data.api.Profile
import com.vibespace.app.data.api.RefreshRequest
import com.vibespace.app.data.api.ResetPasswordRequest
import com.vibespace.app.data.api.SignupRequest
import com.vibespace.app.data.api.VibeApi
import com.vibespace.app.data.api.apiCall
import com.vibespace.app.data.auth.TokenStore
import com.vibespace.app.data.auth.Tokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Who is using the app: a guest, or a logged-in user (tokens + their profile). */
class AuthRepository(
    private val api: VibeApi,
    private val tokenStore: TokenStore,
    scope: CoroutineScope,
) {
    private val _me = MutableStateFlow<Profile?>(null)

    /** The logged-in user's profile, null for guests (or before /me has loaded). */
    val me: StateFlow<Profile?> = _me.asStateFlow()

    val isLoggedIn: StateFlow<Boolean> = tokenStore.tokens
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, tokenStore.current != null)

    fun isLoggedInNow(): Boolean = tokenStore.current != null

    suspend fun login(identifier: String, password: String): Result<Profile> = apiCall {
        val r = api.login(LoginRequest(identifier.trim(), password))
        tokenStore.save(Tokens(r.accessToken, r.refreshToken))
        _me.value = r.user
        r.user
    }

    suspend fun signup(email: String, username: String, password: String, displayName: String?, language: String): Result<Profile> = apiCall {
        val r = api.signup(
            SignupRequest(
                email = email.trim(),
                username = username.trim().lowercase(),
                password = password,
                displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                language = language,
            ),
        )
        tokenStore.save(Tokens(r.accessToken, r.refreshToken))
        _me.value = r.user
        r.user
    }

    suspend fun usernameAvailable(username: String): Result<Boolean> = apiCall { api.usernameAvailable(username.trim().lowercase()).available }

    /** Loads /me when logged in. A rejected session is cleared by the token authenticator. */
    suspend fun refreshMe(): Result<Profile?> {
        if (!isLoggedInNow()) {
            _me.value = null
            return Result.success(null)
        }
        return apiCall { api.me() }.onSuccess { _me.value = it }.map { it }
    }

    suspend fun logout() {
        val t = tokenStore.current
        if (t != null) apiCall { api.logout(RefreshRequest(t.refreshToken)) } // best effort
        clearLocalSession()
    }

    /** Called when the server rejects our refresh token. */
    suspend fun clearLocalSession() {
        tokenStore.clear()
        _me.value = null
    }

    suspend fun forgotPassword(email: String): Result<Unit> = apiCall { api.forgotPassword(ForgotPasswordRequest(email.trim())); Unit }

    suspend fun resetPassword(token: String, newPassword: String): Result<Unit> = apiCall {
        val r = api.resetPassword(ResetPasswordRequest(token.trim(), newPassword))
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    /**
     * PATCH /me. Only the fields given are sent (the endpoint rejects unknown keys).
     * [avatarMediaId]: null = leave as is, "" = remove the photo, otherwise the uploaded media id.
     */
    suspend fun updateProfile(
        displayName: String? = null,
        bio: String? = null,
        isPrivate: Boolean? = null,
        language: String? = null,
        avatarMediaId: String? = null,
    ): Result<Profile> = apiCall {
        val body = buildMap {
            displayName?.let { put("displayName", JsonPrimitive(it.trim())) }
            bio?.let { put("bio", JsonPrimitive(it)) }
            isPrivate?.let { put("isPrivate", JsonPrimitive(it)) }
            language?.let { put("language", JsonPrimitive(it)) }
            avatarMediaId?.let { put("avatarMediaId", if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }
        }
        val p = api.updateMe(JsonObject(body))
        _me.value = p
        p
    }

    /** Throws [AppError.LoginRequired] for guests. */
    fun requireLogin() {
        if (!isLoggedInNow()) throw AppError.LoginRequired
    }
}
