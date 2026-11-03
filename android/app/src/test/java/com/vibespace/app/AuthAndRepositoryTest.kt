package com.vibespace.app

import com.vibespace.app.data.api.ApiErrors
import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.auth.InMemoryTokenStore
import com.vibespace.app.data.auth.TokenRefresher
import com.vibespace.app.data.auth.Tokens
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.BlobUploader
import com.vibespace.app.data.repo.ImagePayload
import com.vibespace.app.data.repo.MediaRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.ui.auth.SignupViewModel
import com.vibespace.app.ui.common.TimeFormat
import com.vibespace.app.ui.common.Validators
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthAndRepositoryTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun loginStoresTokensAndProfile() = runTest {
        val api = FakeVibeApi()
        val store = InMemoryTokenStore()
        val auth = AuthRepository(api, store, backgroundScope)
        assertFalse(auth.isLoggedInNow())
        val r = auth.login("  Pushkar ", "secret123")
        assertTrue(r.isSuccess)
        assertEquals(Tokens("access-1", "refresh-1"), store.current)
        assertEquals("pushkar", auth.me.value?.username)
        assertTrue("login:Pushkar" in api.calls) // trimmed; the server lowercases
        auth.logout()
        assertNull(store.current)
        assertTrue("logout" in api.calls)
    }

    @Test fun badCredentialsSurfaceServerCode() = runTest {
        val api = FakeVibeApi().apply {
            failNext = FakeVibeApi.httpError(401, """{"error":{"code":"INVALID_CREDENTIALS","message":"Email/username or password is incorrect."},"requestId":"x"}""")
        }
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val e = auth.login("a@b.in", "nope").exceptionOrNull() as AppError.Api
        assertEquals("INVALID_CREDENTIALS", e.code)
        assertEquals("Email/username or password is incorrect.", e.message)
        assertFalse(auth.isLoggedInNow())
    }

    @Test fun patchMeSendsOnlyChangedFieldsAndNullToRemoveAvatar() = runTest {
        val api = FakeVibeApi()
        val auth = AuthRepository(api, InMemoryTokenStore(Tokens("a", "r")), backgroundScope)
        auth.updateProfile(isPrivate = true)
        assertEquals(setOf("isPrivate"), api.lastPatch!!.keys)
        assertTrue(auth.me.value!!.isPrivate)
        auth.updateProfile(avatarMediaId = "")
        assertEquals(JsonNull, api.lastPatch!!["avatarMediaId"])
        auth.updateProfile(displayName = " Pushkar K ", language = "hi")
        assertEquals("Pushkar K", api.lastPatch!!["displayName"]!!.jsonPrimitive.content)
        assertEquals("hi", api.lastPatch!!["language"]!!.jsonPrimitive.content)
    }

    @Test fun imagePostUploadsToPresignedUrlThenCreatesPost() = runTest {
        val api = FakeVibeApi()
        val auth = AuthRepository(api, InMemoryTokenStore(Tokens("a", "r")), backgroundScope)
        var putUrl: String? = null
        var putHeaders: Map<String, String> = emptyMap()
        val media = MediaRepository(api, BlobUploader { url, headers, _, _ -> putUrl = url; putHeaders = headers }, auth)
        val id = media.upload(ImagePayload(ByteArray(1234), "image/jpeg", 800, 600), MediaRepository.PURPOSE_POST).getOrThrow()
        assertEquals("m1", id)
        assertEquals(1234L, api.lastUpload!!.sizeBytes)
        assertEquals("post_image", api.lastUpload!!.purpose)
        assertEquals("https://r2.example/put", putUrl)
        assertEquals("1234", putHeaders["Content-Length"])
        PostRepository(api, auth).createImagePost(" caption ", listOf(id)).getOrThrow()
        assertEquals("image", api.lastCreatePost!!.type)
        assertEquals(listOf("m1"), api.lastCreatePost!!.mediaIds)
        assertEquals("caption", api.lastCreatePost!!.caption)
    }

    @Test fun guestCannotUploadOrPost() = runTest {
        val api = FakeVibeApi()
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val media = MediaRepository(api, BlobUploader { _, _, _, _ -> error("must not upload") }, auth)
        assertEquals(AppError.LoginRequired, media.upload(ImagePayload(ByteArray(1), "image/png", null, null), "post_image").exceptionOrNull())
        assertEquals(AppError.LoginRequired, PostRepository(api, auth).createTextPost("hi").exceptionOrNull())
        assertNull(api.lastUpload)
    }

    @Test fun refreshRotatesOnceForConcurrentFailures() {
        val store = InMemoryTokenStore(Tokens("old-access", "old-refresh"))
        var refreshCalls = 0
        val refresher = TokenRefresher(store, refreshCall = { rt ->
            refreshCalls++
            assertEquals("old-refresh", rt)
            Tokens("new-access", "new-refresh")
        }, onSessionExpired = {})
        assertEquals("new-access", refresher.refreshAfter401("old-access"))
        // A second request that also failed with the old token must not reuse the (now revoked) refresh token.
        assertEquals("new-access", refresher.refreshAfter401("old-access"))
        assertEquals(1, refreshCalls)
        assertEquals(Tokens("new-access", "new-refresh"), store.current)
    }

    @Test fun rejectedRefreshEndsSession() {
        val store = InMemoryTokenStore(Tokens("a", "r"))
        var expired = false
        val refresher = TokenRefresher(store, refreshCall = { null }, onSessionExpired = { expired = true })
        assertNull(refresher.refreshAfter401("a"))
        assertNull(store.current)
        assertTrue(expired)
    }

    @Test fun errorEnvelopeParsing() {
        val e = ApiErrors.parse(409, """{"error":{"code":"USERNAME_TAKEN","message":"यह यूज़रनेम पहले से लिया जा चुका है।","details":[{"path":"/username"}]},"requestId":"req-9"}""")
        assertEquals("USERNAME_TAKEN", e.code)
        assertEquals("req-9", e.requestId)
        assertEquals("यह यूज़रनेम पहले से लिया जा चुका है।", e.message)
        val plain = ApiErrors.parse(502, "<html>Bad gateway</html>")
        assertEquals("INTERNAL", plain.code)
        assertTrue(ApiErrors.parse(401, """{"error":{"code":"INVALID_TOKEN","message":""}}""").isUnauthenticated)
    }

    @Test fun validatorsMirrorBackendRules() {
        assertTrue(Validators.isValidUsername("pushkar.k_1"))
        assertFalse(Validators.isValidUsername("ab"))
        assertFalse(Validators.isValidUsername(".dot"))
        assertFalse(Validators.isValidUsername("a..b"))
        assertFalse(Validators.isValidUsername("hindi_नाम"))
        assertTrue(Validators.isValidEmail("p@vibespace.in"))
        assertFalse(Validators.isValidEmail("p@"))
        assertFalse(Validators.isValidPassword("short"))
    }

    @Test fun signupValidationBlocksBadInputBeforeNetwork() = runTest {
        val api = FakeVibeApi()
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val vm = SignupViewModel(auth) { "hi" }
        vm.onEmail("bad"); vm.onUsername("A b"); vm.onPassword("123")
        vm.submit()
        assertEquals(setOf("email", "username", "password"), vm.state.value.fieldErrors.keys)
        assertFalse("signup" in api.calls)
        vm.onEmail("new@vibespace.in"); vm.onUsername("newbie"); vm.onPassword("longenough")
        vm.submit()
        assertTrue("signup" in api.calls)
        assertTrue(auth.isLoggedInNow())
    }

    @Test fun timeFormatting() {
        val t = TimeFormat.parseIsoMillis("2026-11-02T08:27:00.123Z")!!
        assertEquals(1793608020123L, t)
        assertEquals(t, TimeFormat.parseIsoMillis("2026-11-02T13:57:00.123+05:30"))
        assertEquals(TimeFormat.Unit.HOURS to 2, TimeFormat.relative("2026-11-02T08:27:00.123Z", t + 2 * 3600_000L + 5000))
        assertEquals("1.2K", TimeFormat.compact(1234))
        assertEquals("999", TimeFormat.compact(999))
        assertEquals("2M", TimeFormat.compact(2_000_000))
    }
}
