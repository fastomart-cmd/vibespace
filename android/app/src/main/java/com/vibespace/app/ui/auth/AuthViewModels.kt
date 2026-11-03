package com.vibespace.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibespace.app.R
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.ui.common.Validators
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthFormState(
    val email: String = "",
    val username: String = "",
    val identifier: String = "",
    val password: String = "",
    val displayName: String = "",
    val token: String = "",
    /** Field -> string resource id of its validation error. */
    val fieldErrors: Map<String, Int> = emptyMap(),
    val serverError: AppError? = null,
    val loading: Boolean = false,
    /** Forgot password: the reset email was requested. */
    val resetRequested: Boolean = false,
)

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()

    fun onIdentifier(v: String) = _state.update { it.copy(identifier = v, fieldErrors = it.fieldErrors - "identifier", serverError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", serverError = null) }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val errors = buildMap {
            if (s.identifier.trim().length < 3) put("identifier", R.string.field_required)
            if (s.password.isEmpty()) put("password", R.string.field_required)
        }
        if (errors.isNotEmpty()) { _state.update { it.copy(fieldErrors = errors) }; return }
        _state.update { it.copy(loading = true, serverError = null) }
        viewModelScope.launch {
            auth.login(s.identifier.trim().lowercase(), s.password)
                .onSuccess { _state.update { it.copy(loading = false, password = "") }; _done.tryEmit(Unit) }
                .onFailure { e -> _state.update { it.copy(loading = false, serverError = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }
}

class SignupViewModel(private val auth: AuthRepository, private val language: () -> String) : ViewModel() {
    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done.asSharedFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, fieldErrors = it.fieldErrors - "email", serverError = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v.lowercase().filterNot(Char::isWhitespace).take(30), fieldErrors = it.fieldErrors - "username", serverError = null) }
    fun onDisplayName(v: String) = _state.update { it.copy(displayName = v.take(60)) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", serverError = null) }

    fun validate(s: AuthFormState): Map<String, Int> = buildMap {
        if (!Validators.isValidEmail(s.email)) put("email", R.string.invalid_email)
        if (!Validators.isValidUsername(s.username)) put("username", R.string.invalid_username)
        if (!Validators.isValidPassword(s.password)) put("password", R.string.invalid_password)
    }

    fun submit() {
        val s = _state.value
        if (s.loading) return
        val errors = validate(s)
        if (errors.isNotEmpty()) { _state.update { it.copy(fieldErrors = errors) }; return }
        _state.update { it.copy(loading = true, serverError = null) }
        viewModelScope.launch {
            auth.signup(s.email, s.username, s.password, s.displayName, language())
                .onSuccess { _state.update { it.copy(loading = false, password = "") }; _done.tryEmit(Unit) }
                .onFailure { e -> _state.update { it.copy(loading = false, serverError = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }
}

class ForgotPasswordViewModel(private val auth: AuthRepository, initialToken: String?) : ViewModel() {
    private val _state = MutableStateFlow(AuthFormState(token = initialToken.orEmpty()))
    val state: StateFlow<AuthFormState> = _state.asStateFlow()
    private val _resetDone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetDone: SharedFlow<Unit> = _resetDone.asSharedFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, fieldErrors = it.fieldErrors - "email", serverError = null) }
    fun onToken(v: String) = _state.update { it.copy(token = v.trim(), fieldErrors = it.fieldErrors - "token", serverError = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, fieldErrors = it.fieldErrors - "password", serverError = null) }

    fun requestReset() {
        val s = _state.value
        if (s.loading) return
        if (!Validators.isValidEmail(s.email)) { _state.update { it.copy(fieldErrors = mapOf("email" to R.string.invalid_email)) }; return }
        _state.update { it.copy(loading = true, serverError = null) }
        viewModelScope.launch {
            auth.forgotPassword(s.email)
                .onSuccess { _state.update { it.copy(loading = false, resetRequested = true) } }
                .onFailure { e -> _state.update { it.copy(loading = false, serverError = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }

    fun resetPassword() {
        val s = _state.value
        if (s.loading) return
        val errors = buildMap {
            if (s.token.length < 20) put("token", R.string.field_required)
            if (!Validators.isValidPassword(s.password)) put("password", R.string.invalid_password)
        }
        if (errors.isNotEmpty()) { _state.update { it.copy(fieldErrors = errors) }; return }
        _state.update { it.copy(loading = true, serverError = null) }
        viewModelScope.launch {
            auth.resetPassword(s.token, s.password)
                .onSuccess { _state.update { it.copy(loading = false, password = "") }; _resetDone.tryEmit(Unit) }
                .onFailure { e -> _state.update { it.copy(loading = false, serverError = e as? AppError ?: AppError.Unknown(e)) } }
        }
    }
}
