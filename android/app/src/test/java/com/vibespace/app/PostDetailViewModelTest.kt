package com.vibespace.app

import com.vibespace.app.data.api.Comment
import com.vibespace.app.data.api.UserSummary
import com.vibespace.app.data.auth.InMemoryTokenStore
import com.vibespace.app.data.auth.Tokens
import com.vibespace.app.data.repo.AuthRepository
import com.vibespace.app.data.repo.PostRepository
import com.vibespace.app.ui.common.UiEvent
import com.vibespace.app.ui.post.PostDetailViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PostDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun comment(id: String, replies: Int = 0) = Comment(
        id = id, postId = "p1", body = "nice $id", replyCount = replies, createdAt = "2026-11-02T08:00:00.000Z",
        author = UserSummary("u2", "ravi", "Ravi"),
    )

    private fun api() = FakeVibeApi().apply {
        trending = mutableListOf(FakeVibeApi.post("p1"))
        comments = mutableListOf(comment("c1", replies = 3), comment("c2"))
        replies = mutableMapOf("c1" to mutableListOf(comment("r1"), comment("r2"), comment("r3")))
    }

    @Test fun guestCanReadPostCommentsAndReplies() = runTest {
        val api = api()
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val vm = PostDetailViewModel("p1", PostRepository(api, auth), auth)
        val s = vm.state.value
        assertEquals("p1", s.post?.id)
        assertEquals(listOf("c1", "c2"), s.comments.map { it.id })
        vm.toggleReplies(s.comments.first())
        val r = vm.state.value.replies.getValue("c1")
        assertTrue(r.expanded)
        assertEquals(listOf("r1", "r2"), r.items.map { it.id })
        vm.loadReplies("c1", more = true)
        assertEquals(listOf("r1", "r2", "r3"), vm.state.value.replies.getValue("c1").items.map { it.id })
    }

    @Test fun guestCommentAsksToLogIn() = runTest {
        val api = api()
        val auth = AuthRepository(api, InMemoryTokenStore(), backgroundScope)
        val vm = PostDetailViewModel("p1", PostRepository(api, auth), auth)
        vm.onDraftChange("hello")
        val e = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { assertEquals(UiEvent.LoginRequired, vm.events.first()) }
        vm.send()
        e.join()
        assertTrue(api.calls.none { it.startsWith("comment") })
    }

    @Test fun nestedReplyGoesUnderParentAndBumpsCounts() = runTest {
        val api = api()
        val auth = AuthRepository(api, InMemoryTokenStore(Tokens("a", "r")), backgroundScope)
        val vm = PostDetailViewModel("p1", PostRepository(api, auth), auth)
        val parent = vm.state.value.comments.last() // c2, no replies yet
        vm.startReply(parent)
        vm.onDraftChange("  replying  ")
        vm.send()
        val s = vm.state.value
        assertTrue("comment:c2" in api.calls)
        assertEquals(1, s.comments.first { it.id == "c2" }.replyCount)
        assertEquals(1, s.replies.getValue("c2").items.size)
        assertEquals("replying", s.replies.getValue("c2").items.first().body)
        assertEquals(1, s.post!!.counts.vibeTalk)
        assertEquals("", s.draft)
        assertEquals(null, s.replyTo)
    }
}
