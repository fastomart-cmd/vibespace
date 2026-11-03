package com.vibespace.app.data.repo

import com.vibespace.app.data.api.ClapRequest
import com.vibespace.app.data.api.Comment
import com.vibespace.app.data.api.CommentsPage
import com.vibespace.app.data.api.CreateCommentRequest
import com.vibespace.app.data.api.CreatePostRequest
import com.vibespace.app.data.api.Page
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.api.ReportRequest
import com.vibespace.app.data.api.VibeApi
import com.vibespace.app.data.api.ViewBatchRequest
import com.vibespace.app.data.api.ViewResult
import com.vibespace.app.data.api.apiCall

enum class FeedType { FOR_YOU, FOLLOWING, TRENDING, EXPLORE }

class PostRepository(private val api: VibeApi, private val auth: AuthRepository) {

    /** "For You" is the trending feed for now. Following needs login. Explore can filter by hashtag. */
    suspend fun feed(type: FeedType, cursor: String?, tag: String? = null, limit: Int = 20): Result<Page<Post>> = apiCall {
        when (type) {
            FeedType.FOR_YOU, FeedType.TRENDING -> api.trendingFeed(cursor, limit)
            FeedType.FOLLOWING -> { auth.requireLogin(); api.followingFeed(cursor, limit) }
            FeedType.EXPLORE -> api.exploreFeed(cursor, limit, normalizeTag(tag))
        }
    }

    suspend fun post(id: String): Result<Post> = apiCall { api.post(id) }

    /** Views work for guests too (counted per device via X-Device-Id). */
    suspend fun recordView(id: String): Result<ViewResult> = apiCall { api.recordView(id) }

    suspend fun recordViews(ids: List<String>): Result<List<ViewResult>> = apiCall {
        api.recordViews(ViewBatchRequest(ids.distinct().take(ViewTracker.MAX_BATCH))).items
    }

    /** Toggle my VibeClap; returns the post with server-side counts. */
    suspend fun toggleClap(post: Post): Result<Post> = apiCall {
        auth.requireLogin()
        val clapped = post.viewer?.hasClapped == true
        val r = if (clapped) api.unclap(post.id) else api.clap(post.id, ClapRequest("clap"))
        val v = post.viewer ?: com.vibespace.app.data.api.PostViewer()
        post.copy(counts = r.counts, viewer = v.copy(hasClapped = r.hasClapped, clapKind = r.clapKind))
    }

    suspend fun toggleSave(post: Post): Result<Post> = apiCall {
        auth.requireLogin()
        val saved = post.viewer?.hasSaved == true
        val r = if (saved) api.unsave(post.id) else api.save(post.id)
        val v = post.viewer ?: com.vibespace.app.data.api.PostViewer()
        post.copy(counts = r.counts, viewer = v.copy(hasSaved = r.hasSaved))
    }

    suspend fun createTextPost(caption: String): Result<Post> = apiCall {
        auth.requireLogin()
        api.createPost(CreatePostRequest(type = "text", caption = caption.trim()))
    }

    suspend fun createImagePost(caption: String, mediaIds: List<String>): Result<Post> = apiCall {
        auth.requireLogin()
        api.createPost(CreatePostRequest(type = "image", caption = caption.trim(), mediaIds = mediaIds))
    }

    suspend fun deletePost(id: String): Result<Unit> = apiCall {
        val r = api.deletePost(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    suspend fun comments(postId: String, cursor: String?): Result<CommentsPage> = apiCall { api.comments(postId, cursor) }

    suspend fun replies(commentId: String, cursor: String?): Result<Page<Comment>> = apiCall { api.replies(commentId, cursor) }

    suspend fun addComment(postId: String, body: String, parentId: String?): Result<Comment> = apiCall {
        auth.requireLogin()
        api.addComment(postId, CreateCommentRequest(body.trim(), parentId))
    }

    suspend fun deleteComment(id: String): Result<Unit> = apiCall {
        val r = api.deleteComment(id)
        if (!r.isSuccessful) throw retrofit2.HttpException(r)
    }

    /** targetType: post | comment | user. Reporting twice is a no-op on the server. */
    suspend fun report(targetType: String, targetId: String, reason: String): Result<Unit> = apiCall {
        auth.requireLogin()
        api.report(ReportRequest(targetType, targetId, reason))
        Unit
    }

    companion object {
        val REPORT_REASONS = listOf("spam", "harassment", "hate", "nudity", "violence", "misinformation", "impersonation", "other")

        fun normalizeTag(tag: String?): String? = tag?.trim()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }
    }
}
