package com.vibespace.app.di

import android.content.Context
import com.vibespace.app.BuildConfig
import com.vibespace.app.data.api.RefreshRequest
import com.vibespace.app.data.api.VibeApi
import com.vibespace.app.data.auth.AuthHeaderInterceptor
import com.vibespace.app.data.auth.DataStoreTokenStore
import com.vibespace.app.data.auth.TokenAuthenticator
import com.vibespace.app.data.auth.TokenRefresher
import com.vibespace.app.data.auth.Tokens
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.MediaRepository
import com.vibespace.app.data.repo.OkHttpBlobUploader
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.data.repo.UserRepository
import com.vibespace.app.data.repo.ViewTracker
import com.vibespace.app.data.device.DataStoreDeviceIdProvider
import com.vibespace.app.data.device.DeviceIdInterceptor
import com.vibespace.app.data.settings.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Manual dependency injection: one instance per process, created in [com.vibespace.app.VibeSpaceApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** "https://api.example.com/api/v1/" built from BuildConfig.API_BASE_URL. */
    val apiBaseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/') + "/api/v1/"

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    private val tokenStore = DataStoreTokenStore(context.applicationContext).also { store ->
        runBlocking(Dispatchers.IO) { store.load() } // tiny read, once, before the first request
    }

    /** Random per-install id sent as X-Device-Id so guest views are counted once per device. */
    private val deviceIds = DataStoreDeviceIdProvider(context.applicationContext).also { store ->
        runBlocking(Dispatchers.IO) { store.load() }
    }

    private val _sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the server rejected the refresh token (the user is now a guest again). */
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val logging = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
    }

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun retrofit(client: OkHttpClient) = Retrofit.Builder()
        .baseUrl(apiBaseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /** Used only for POST /auth/refresh, so it can never recurse into the authenticator. */
    private val refreshApi: VibeApi = retrofit(
        baseClient.newBuilder()
            .addInterceptor(AuthHeaderInterceptor(tokenStore = com.vibespace.app.data.auth.InMemoryTokenStore(), language = LanguageManager::current))
            .addInterceptor(DeviceIdInterceptor(deviceIds))
            .build(),
    ).create(VibeApi::class.java)

    private val refresher = TokenRefresher(
        tokenStore = tokenStore,
        refreshCall = { refreshToken ->
            try {
                val t = refreshApi.refresh(RefreshRequest(refreshToken))
                Tokens(t.accessToken, t.refreshToken)
            } catch (e: HttpException) {
                if (e.code() == 401 || e.code() == 403) null else throw e
            }
        },
        onSessionExpired = { _sessionExpired.tryEmit(Unit) },
    )

    val okHttp: OkHttpClient = baseClient.newBuilder()
        .addInterceptor(AuthHeaderInterceptor(tokenStore, LanguageManager::current))
        .addInterceptor(DeviceIdInterceptor(deviceIds))
        .authenticator(TokenAuthenticator(refresher))
        .addInterceptor(logging)
        .build()

    val api: VibeApi = retrofit(okHttp).create(VibeApi::class.java)

    /** For Coil and presigned R2 uploads: no API headers. */
    val plainClient: OkHttpClient = baseClient

    val authRepository = AuthRepository(api, tokenStore, appScope)
    val postRepository = PostRepository(api, authRepository)
    val userRepository = UserRepository(api, authRepository)
    /** App-wide: feeds report visible posts, post detail records opens; batches go out every 5 s or on pause. */
    val viewTracker = ViewTracker(
        scope = appScope,
        sendBatch = postRepository::recordViews,
        sendOne = postRepository::recordView,
    )
    val mediaRepository = MediaRepository(api, OkHttpBlobUploader(plainClient), authRepository)
}
