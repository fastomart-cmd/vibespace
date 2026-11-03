package com.vibespace.app

import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.auth.InMemoryTokenStore
import com.vibespace.app.data.device.DataStoreDeviceIdProvider
import com.vibespace.app.data.device.DeviceIdInterceptor
import com.vibespace.app.data.device.FixedDeviceIdProvider
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.data.repo.ViewTracker
import com.vibespace.app.data.repo.ViewVisibility
import com.vibespace.app.ui.common.ViewCountFormat
import com.vibespace.app.ui.common.ViewCountFormat.System.INDIAN
import com.vibespace.app.ui.common.ViewCountFormat.System.INTERNATIONAL
import com.vibespace.app.ui.post.PostDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ViewCountFormatTest {
    private fun en(n: Long) = ViewCountFormat.format(n, INTERNATIONAL)
    private fun hi(n: Long) = ViewCountFormat.format(n, INDIAN, lakh = "लाख", crore = "करोड़")

    @Test fun englishUsesKMB() {
        assertEquals("0", en(0))
        assertEquals("999", en(999))
        assertEquals("1K", en(1_000))
        assertEquals("1.2K", en(1_250))
        assertEquals("1.2K", en(1_299)) // rounds down, never up
        assertEquals("999.9K", en(999_999))
        assertEquals("1M", en(1_000_000))
        assertEquals("1.2M", en(1_234_567))
        assertEquals("3.4B", en(3_456_000_000))
        assertEquals("0", en(-5))
    }

    @Test fun hindiUsesLakhAndCrore() {
        assertEquals("999", hi(999))
        assertEquals("1.2K", hi(1_234))
        assertEquals("99.9K", hi(99_999))
        assertEquals("1 लाख", hi(1_00_000))
        assertEquals("3.4 लाख", hi(3_45_678))
        assertEquals("99.9 लाख", hi(99_99_999))
        assertEquals("1 करोड़", hi(1_00_00_000))
        assertEquals("1.2 करोड़", hi(1_23_45_678))
        assertEquals("250 करोड़", hi(2_500_000_000))
        // default words when no resources are given
        assertEquals("3.4 lakh", ViewCountFormat.format(3_40_000, INDIAN))
        assertEquals("1.2 Cr", ViewCountFormat.format(1_20_00_000, INDIAN))
    }

    @Test fun numberSystemComesFromTheStringResourceValue() {
        assertEquals(INDIAN, ViewCountFormat.systemFor("indian"))
        assertEquals(INTERNATIONAL, ViewCountFormat.systemFor("international"))
        assertEquals(INTERNATIONAL, ViewCountFormat.systemFor("anything else"))
    }
}

class ViewVisibilityTest {
    @Test fun fractionOfItemInsideViewport() {
        assertEquals(1f, ViewVisibility.fraction(0, 100, 0, 1000), 0.001f)
        assertEquals(0.5f, ViewVisibility.fraction(950, 100, 0, 1000), 0.001f)
        assertEquals(0.25f, ViewVisibility.fraction(-75, 100, 0, 1000), 0.001f)
        assertEquals(0f, ViewVisibility.fraction(1200, 100, 0, 1000), 0.001f)
        assertEquals(0f, ViewVisibility.fraction(0, 0, 0, 1000), 0.001f)
        // item taller than the screen: half of it showing counts as half
        assertEquals(0.5f, ViewVisibility.fraction(-500, 2000, 0, 1000), 0.001f)
    }

    @Test fun onlyPostsAtLeastHalfVisible() {
        val items = listOf(
            ViewVisibility.Item("profile-header", 0, 300),
            ViewVisibility.Item("p1", 300, 400),
            ViewVisibility.Item("p2", 700, 400), // 300 of 400 visible = 75%
            ViewVisibility.Item("p3", 900, 400), // 100 of 400 = 25%
        )
        val ids = ViewVisibility.visibleIds(items, 0, 1000, isPost = { it is String && it.startsWith("p") && it != "profile-header" })
        assertEquals(setOf("p1", "p2"), ids)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ViewTrackerTest {
    private val api = FakeVibeApi()

    private fun TestScope.tracker(): ViewTracker {
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val repo = PostRepository(api, auth)
        return ViewTracker(backgroundScope, repo::recordViews, repo::recordView)
    }

    @Test fun countsAfterOneSecondAtHalfVisibleAndBatchesEveryFiveSeconds() = runTest {
        val t = tracker()
        t.setVisible("feed", setOf("p1", "p2"))
        advanceTimeBy(900); runCurrent()
        t.setVisible("feed", setOf("p2")) // p1 scrolled away before 1 s: not a view
        advanceTimeBy(200); runCurrent()
        assertEquals(listOf("p2"), t.pendingIds())
        assertTrue(api.viewBatches.isEmpty()) // nothing sent until the 5 s batch
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(listOf("p2")), api.viewBatches)
        assertEquals(mapOf("p2" to 1L), t.counts.value)
    }

    @Test fun eachPostIsSentOncePerSessionEvenIfSeenAgain() = runTest {
        val t = tracker()
        t.setVisible("feed", setOf("p1"))
        advanceTimeBy(1_100); runCurrent()
        t.setVisible("feed", emptySet())
        t.setVisible("feed", setOf("p1"))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(listOf("p1")), api.viewBatches)
    }

    @Test fun flushNowSendsImmediatelyOnPause() = runTest {
        val t = tracker()
        t.setVisible("feed", setOf("p1", "p2", "p3"))
        advanceTimeBy(1_100); runCurrent()
        t.setVisible("feed", emptySet()) // screen paused
        t.flushNow(); runCurrent()
        assertEquals(1, api.viewBatches.size)
        assertEquals(setOf("p1", "p2", "p3"), api.viewBatches.single().toSet())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, api.viewBatches.size) // the cancelled timer does not send again
    }

    @Test fun batchesHoldAtMostFiftyIds() = runTest {
        val t = tracker()
        t.setVisible("feed", (1..120).map { "p$it" }.toSet())
        advanceTimeBy(1_100); runCurrent()
        t.flushNow(); runCurrent()
        assertEquals(listOf(50, 50, 20), api.viewBatches.map { it.size })
    }

    @Test fun pausedScreensAndTwoListsAreHandled() = runTest {
        val t = tracker()
        t.setVisible("feed", setOf("p1"))
        t.setVisible("profile", setOf("p1", "p2"))
        advanceTimeBy(500); runCurrent()
        t.setVisible("profile", emptySet()) // p1 still visible in the feed, p2 gone
        advanceTimeBy(600); runCurrent()
        assertEquals(listOf("p1"), t.pendingIds())
    }

    @Test fun failedBatchIsKeptForTheNextFlush() = runTest {
        val t = tracker()
        api.failViews = java.io.IOException("offline")
        t.setVisible("feed", setOf("p1"))
        advanceTimeBy(1_100); runCurrent()
        t.flushNow(); runCurrent()
        assertTrue(api.viewBatches.isEmpty())
        assertEquals(listOf("p1"), t.pendingIds())
        api.failViews = null
        t.flushNow(); runCurrent()
        assertEquals(listOf(listOf("p1")), api.viewBatches)
        assertTrue(t.pendingIds().isEmpty())
    }

    @Test fun openingDetailRecordsAtOnceAndIsNotSentAgainFromTheFeed() = runTest {
        val t = tracker()
        t.setVisible("feed", setOf("p1"))
        advanceTimeBy(1_100); runCurrent() // queued from the feed
        val r = t.recordOpen("p1")
        assertTrue(r.isSuccess)
        assertEquals(1L, r.getOrThrow().viewCount)
        assertTrue("view:p1" in api.calls)
        t.flushNow(); runCurrent()
        assertTrue(api.viewBatches.isEmpty())
        assertEquals(1L, t.counts.value["p1"])
    }
}

class PostViewsOnDetailTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun openingPostDetailRecordsAViewAndShowsTheNewCount() = runTest {
        val api = FakeVibeApi().apply {
            trending = mutableListOf(FakeVibeApi.post("p1", viewCount = 41))
            viewCounts["p1"] = 41
        }
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val repo = PostRepository(api, auth)
        val tracker = ViewTracker(backgroundScope, repo::recordViews, repo::recordView)
        val vm = PostDetailViewModel("p1", repo, auth, tracker)
        assertEquals(42L, vm.state.value.post?.viewCount)
        assertEquals(1, api.calls.count { it == "view:p1" })
    }

    @Test fun viewFailureDoesNotBreakTheScreen() = runTest {
        val api = FakeVibeApi().apply { trending = mutableListOf(FakeVibeApi.post("p1", viewCount = 7)); failViews = java.io.IOException("offline") }
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val repo = PostRepository(api, auth)
        val vm = PostDetailViewModel("p1", repo, auth, ViewTracker(backgroundScope, repo::recordViews, repo::recordView))
        val s = vm.state.value
        assertEquals(7L, s.post?.viewCount)
        assertEquals(null, s.error)
    }

    @Test fun recordViewErrorsBecomeAppErrors() = runTest {
        val api = FakeVibeApi().apply { failViews = FakeVibeApi.httpError(429, """{"error":{"code":"RATE_LIMITED","message":"Slow down"},"requestId":"r"}""") }
        val repo = PostRepository(api, AuthRepository(api, InMemoryTokenStore(), backgroundScope))
        val e = repo.recordView("p1").exceptionOrNull()
        assertTrue(e is AppError.Api)
    }
}

class DeviceIdTest {
    @Test fun everyApiRequestCarriesTheDeviceId() {
        var seen: Request? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(DeviceIdInterceptor(FixedDeviceIdProvider("3f2b6c1e-8a4d-4b7e-9c11-2a5f0e6d7b90")))
            .addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{}".toResponseBody(null)).build()
            }
            .build()
        client.newCall(Request.Builder().url("http://10.0.2.2:3000/api/v1/feed/trending").build()).execute().close()
        assertEquals("3f2b6c1e-8a4d-4b7e-9c11-2a5f0e6d7b90", seen?.header(DeviceIdInterceptor.HEADER))
    }

    @Test fun generatedIdsAreRandomAndMatchTheServerRule() {
        val a = DataStoreDeviceIdProvider.newId()
        val b = DataStoreDeviceIdProvider.newId()
        assertTrue(DataStoreDeviceIdProvider.isValid(a))
        assertFalse(a == b)
        assertFalse(DataStoreDeviceIdProvider.isValid("short"))
        assertFalse(DataStoreDeviceIdProvider.isValid("has spaces and !! in it......"))
    }

    @Test fun postJsonCarriesViewCountAndOldResponsesDefaultToZero() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        val base = """"id":"p1","type":"text","author":{"id":"a1","username":"asha","displayName":"Asha"},"createdAt":"2026-11-02T08:00:00Z""""
        assertEquals(3_45_678L, json.decodeFromString<Post>("{$base,\"viewCount\":345678}").viewCount)
        assertEquals(0L, json.decodeFromString<Post>("{$base}").viewCount)
    }
}
