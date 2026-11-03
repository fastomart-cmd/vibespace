package com.vibespace.app.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

data class Tokens(val accessToken: String, val refreshToken: String)

/**
 * Holds the current access + refresh token. [current] is an in-memory mirror that OkHttp
 * interceptors can read synchronously; [save]/[clear] also persist it.
 */
interface TokenStore {
    val tokens: StateFlow<Tokens?>
    val current: Tokens? get() = tokens.value
    suspend fun save(tokens: Tokens)
    suspend fun clear()
}

private val Context.authDataStore by preferencesDataStore(name = "vibespace_auth")

class DataStoreTokenStore(private val context: Context) : TokenStore {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val state = MutableStateFlow<Tokens?>(null)
    override val tokens: StateFlow<Tokens?> = state.asStateFlow()

    /** Load persisted tokens once at startup. */
    suspend fun load() {
        val prefs = context.authDataStore.data.first()
        val a = prefs[accessKey]
        val r = prefs[refreshKey]
        state.value = if (a != null && r != null) Tokens(a, r) else null
    }

    override suspend fun save(tokens: Tokens) {
        state.value = tokens
        context.authDataStore.edit {
            it[accessKey] = tokens.accessToken
            it[refreshKey] = tokens.refreshToken
        }
    }

    override suspend fun clear() {
        state.value = null
        context.authDataStore.edit { it.clear() }
    }
}

/** For tests and previews. */
class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    override val tokens: StateFlow<Tokens?> = state.asStateFlow()
    override suspend fun save(tokens: Tokens) { state.value = tokens }
    override suspend fun clear() { state.value = null }
}
