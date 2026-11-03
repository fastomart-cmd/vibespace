package com.vibespace.app.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/**
 * A random id for this install, used only so the server can count guest views once per device.
 * It is not tied to the phone's hardware or advertising id, and the server stores only a hash of it.
 */
interface DeviceIdProvider {
    val deviceId: String
}

private val Context.deviceDataStore by preferencesDataStore(name = "vibespace_device")

/** Kept in its own DataStore so logging out (which clears the auth store) keeps the same id. */
class DataStoreDeviceIdProvider(private val context: Context) : DeviceIdProvider {
    private val key = stringPreferencesKey("device_id")

    @Volatile
    private var cached: String? = null

    override val deviceId: String
        get() = cached ?: error("DataStoreDeviceIdProvider.load() was not called")

    /** Read the stored id, or create and store one on first launch. Call once at startup. */
    suspend fun load() {
        val stored = context.deviceDataStore.data.first()[key]
        if (stored != null && isValid(stored)) {
            cached = stored
            return
        }
        val fresh = newId()
        context.deviceDataStore.edit { it[key] = fresh }
        cached = fresh
    }

    companion object {
        /** Same rule as the server (backend/src/lib/postViews.ts): 16–64 of [A-Za-z0-9_-]. */
        private val VALID = Regex("^[A-Za-z0-9_-]{16,64}$")
        fun isValid(id: String) = VALID.matches(id)
        fun newId(): String = UUID.randomUUID().toString()
    }
}

class FixedDeviceIdProvider(override val deviceId: String) : DeviceIdProvider

/** Sends X-Device-Id on every API request (not on media uploads or image loads, which use another client). */
class DeviceIdInterceptor(private val provider: DeviceIdProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(HEADER, provider.deviceId).build())

    companion object {
        const val HEADER = "X-Device-Id"
    }
}
