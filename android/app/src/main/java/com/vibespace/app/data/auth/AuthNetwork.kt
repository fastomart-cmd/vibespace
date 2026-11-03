package com.vibespace.app.data.auth

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** Adds Accept-Language (so server errors come back in Hindi or English) and the Bearer token when logged in. */
class AuthHeaderInterceptor(
    private val tokenStore: TokenStore,
    private val language: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val b = chain.request().newBuilder().header("Accept-Language", language())
        val token = tokenStore.current?.accessToken
        if (token != null && chain.request().header("Authorization") == null && chain.request().header(NO_AUTH) == null) {
            b.header("Authorization", "Bearer $token")
        }
        b.removeHeader(NO_AUTH)
        return chain.proceed(b.build())
    }

    companion object {
        /** Internal marker header: send this request without a token. */
        const val NO_AUTH = "X-VibeSpace-No-Auth"
    }
}

/**
 * Refresh-token rotation, shared between concurrent requests.
 *
 * The backend's refresh tokens are single use, and re-using a rotated one revokes every session,
 * so exactly one refresh may be in flight: a request that fails with the token that was already
 * replaced simply retries with the newer one.
 */
class TokenRefresher(
    private val tokenStore: TokenStore,
    /** Calls POST /auth/refresh. Returns new tokens, null when the server rejected the refresh token, or throws on network errors. */
    private val refreshCall: suspend (refreshToken: String) -> Tokens?,
    private val onSessionExpired: suspend () -> Unit,
) {
    private val lock = Any()

    /**
     * Called after a 401 for a request that used [failedAccessToken].
     * Returns the access token to retry with, or null when the session is over.
     */
    fun refreshAfter401(failedAccessToken: String?): String? = synchronized(lock) {
        val current = tokenStore.current ?: return null
        if (failedAccessToken != null && current.accessToken != failedAccessToken) {
            return current.accessToken // someone else already rotated
        }
        val next = try {
            runBlocking { refreshCall(current.refreshToken) }
        } catch (e: Exception) {
            return null // network failure: keep the session, fail this request
        }
        if (next == null) {
            runBlocking {
                tokenStore.clear()
                onSessionExpired()
            }
            return null
        }
        runBlocking { tokenStore.save(next) }
        next.accessToken
    }
}

/** OkHttp hook: on 401 from a request that carried a Bearer token, rotate once and retry. */
class TokenAuthenticator(private val refresher: TokenRefresher) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val req = response.request
        val auth = req.header("Authorization") ?: return null
        if (responseCount(response) >= 2) return null
        if (req.url.encodedPath.endsWith("/auth/refresh")) return null
        val used = auth.removePrefix("Bearer ").trim()
        val fresh = refresher.refreshAfter401(used)
        return when {
            fresh != null -> req.newBuilder().header("Authorization", "Bearer $fresh").build()
            // Session ended: guest-first, so retry read-only requests as a guest instead of failing the screen.
            req.method == "GET" -> req.newBuilder().removeHeader("Authorization").header(AuthHeaderInterceptor.NO_AUTH, "1").build()
            else -> null
        }
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 1
        while (r?.priorResponse != null) { n++; r = r.priorResponse }
        return n
    }
}
