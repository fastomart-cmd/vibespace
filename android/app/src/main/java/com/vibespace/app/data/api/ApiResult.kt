package com.vibespace.app.data.api

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException

/** A failure from the API, already decoded from the backend's error envelope. */
sealed class AppError(message: String?) : Exception(message) {
    /** The server answered with { error: { code, message } }. [message] is localised by the server (Accept-Language). */
    class Api(val status: Int, val code: String, override val message: String, val requestId: String? = null) : AppError(message)

    /** No connection, timeout, DNS failure, etc. */
    class Network(cause: Throwable? = null) : AppError(cause?.message)

    /** The user must log in for this action (guest tapped a member-only action). */
    object LoginRequired : AppError("login required") {
        private fun readResolve(): Any = LoginRequired
    }

    class Unknown(cause: Throwable? = null) : AppError(cause?.message)

    val isUnauthenticated: Boolean
        get() = this is LoginRequired || (this is Api && (code == "UNAUTHENTICATED" || code == "INVALID_TOKEN"))
}

object ApiErrors {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse the backend's error envelope; falls back to a generic error when the body is not JSON. */
    fun parse(status: Int, body: String?): AppError.Api {
        val env = body?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(ErrorEnvelope.serializer(), it) }.getOrNull()
        }
        return if (env != null) {
            AppError.Api(status, env.error.code, env.error.message, env.requestId)
        } else {
            AppError.Api(status, if (status >= 500) "INTERNAL" else "HTTP_$status", "")
        }
    }

    fun from(t: Throwable): AppError = when (t) {
        is AppError -> t
        is HttpException -> parse(t.code(), runCatching { t.response()?.errorBody()?.string() }.getOrNull())
        is IOException -> AppError.Network(t)
        else -> AppError.Unknown(t)
    }
}

/** Run an API call and map any failure to [AppError]. */
suspend fun <T> apiCall(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (t: Throwable) {
    Result.failure(ApiErrors.from(t))
}
