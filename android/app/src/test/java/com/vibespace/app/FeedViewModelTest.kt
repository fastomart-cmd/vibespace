package com.vibespace.app

import com.vibespace.app.data.api.PostViewer
import com.vibespace.app.data.auth.InMemoryTokenStore
import com.vibespace.app.data.auth.Tokens
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.FeedType
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.ui.common.UiEvent
import com.vibespace.app.ui.feed.FeedViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FeedViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun setup(loggedIn: Boolean, scope: CoroutineScope): Triple<FakeVibeApi, AuthRepository, FeedViewModel> {
        val api = FakeVibeApi().apply {
            trending = mutableListOf(FakeVibeApi.post("p1"), FakeVibeApi.post("p2"), FakeVibeApi.post("p3"))
            explore = mutableListOf(FakeVibeApi.post("e1", tags = listOf("holi")), FakeVibeApi.post("e2", tags = listOf("diwali")))
        }
        val store = InMemoryTokenStore(if (loggedIn) Tokens("a", "r") else null)
        val auth = AuthRepository(api, store, scope)
        return Triple(api, auth, FeedViewModel(PostRepository(api, auth), auth))
    }

    @Test fun guestOpensStraightToTrendingFeed() = runTest {
        val (api, _, vm) = setup(loggedIn = false, scope = backgroundScope)
        val s = vm.pager(FeedType.FOR_YOU).state.value
        assertEquals(listOf("p1", "p2"), s.items.map { it.id })
        assertEquals("trending:-", api.calls.first())
        assertFalse(s.endReached)
    }

    @Test fun loadMoreFollowsCursorAndStopsAtEnd() = runTest {
        val (api, _, vm) = setup(false, backgroundScope)
        val pager = vm.pager(FeedType.FOR_YOU)
        pager.loadMore()
        assertEquals(listOf("p1", "p2", "p3"), pager.state.value.items.map { it.id })
        assertTrue(pager.state.value.endReached)
        assertTrue("trending:2" in api.calls)
        pager.loadMore() // no cursor left: no extra request
        assertEquals(2, api.calls.count { it.startsWith("trending") })
    }

    @Test fun followingTabIsHiddenForGuestsAndAsksToLogIn() = runTest {
        val (api, _, vm) = setup(false, backgroundScope)
        assertFalse(FeedType.FOLLOWING in vm.tabs(loggedIn = false))
        assertTrue(FeedType.FOLLOWING in vm.tabs(loggedIn = true))
        val event = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { assertEquals(UiEvent.LoginRequired, vm.events.first()) }
        vm.select(FeedType.FOLLOWING)
        event.join()
        assertEquals(FeedType.FOR_YOU, vm.selected.value)
        assertFalse("following" in api.calls)
    }

    @Test fun guestClapPromptsLoginWithoutCallingApi() = runTest {
        val (api, _, vm) = setup(false, backgroundScope)
        val post = vm.pager(FeedType.FOR_YOU).state.value.items.first()
        val event = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { assertEquals(UiEvent.LoginRequired, vm.events.first()) }
        vm.actions.clap(post)
        event.join()
        assertTrue(api.calls.none { it.startsWith("clap") })
    }

    @Test fun loggedInClapUpdatesCountsFromServer() = runTest {
        val (api, _, vm) = setup(true, backgroundScope)
        val post = vm.pager(FeedType.FOR_YOU).state.value.items.first().copy(viewer = PostViewer())
        vm.actions.clap(post)
        val updated = vm.pager(FeedType.FOR_YOU).state.value.items.first()
        assertTrue("clap:p1" in api.calls)
        assertEquals(1, updated.counts.vibeClaps)
        assertTrue(updated.viewer!!.hasClapped)
    }

    @Test fun hashtagSearchNormalisesAndFiltersExplore() = runTest {
        val (api, _, vm) = setup(false, backgroundScope)
        vm.select(FeedType.EXPLORE)
        vm.searchTag("  #holi ")
        assertEquals("holi", vm.tag.value)
        assertTrue("explore:holi" in api.calls)
        assertEquals(listOf("e1"), vm.pager(FeedType.EXPLORE).state.value.items.map { it.id })
        vm.searchTag("")
        assertNull(vm.tag.value)
    }

    @Test fun serverErrorIsShownWithItsLocalisedMessage() = runTest {
        val api = FakeVibeApi().apply {
            failNext = FakeVibeApi.httpError(429, """{"error":{"code":"RATE_LIMITED","message":"बहुत ज़्यादा अनुरोध। कृपया थोड़ा रुकें।"},"requestId":"r1"}""")
        }
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val vm = FeedViewModel(PostRepository(api, auth), auth)
        val err = vm.pager(FeedType.FOR_YOU).state.value.error
        assertTrue(err is com.vibespace.app.data.api.AppError.Api)
        err as com.vibespace.app.data.api.AppError.Api
        assertEquals("RATE_LIMITED", err.code)
        assertEquals("बहुत ज़्यादा अनुरोध। कृपया थोड़ा रुकें।", err.message)
        assertEquals(429, err.status)
    }
}
