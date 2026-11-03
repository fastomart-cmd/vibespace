package com.vibespace.app

import com.vibespace.app.data.api.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** In-memory stand-in for the backend, enough for ViewModel / repository tests. */
class FakeVibeApi : VibeApi {
    val calls = mutableListOf<String>()
    var trending: MutableList<Post> = mutableListOf()
    var explore: MutableList<Post> = mutableListOf()
    var following: MutableList<Post> = mutableListOf()
    var comments: MutableList<Comment> = mutableListOf()
    var replies: MutableMap<String, MutableList<Comment>> = mutableMapOf()
    var pageSize = 2
    var failNext: Throwable? = null
    var lastPatch: JsonObject? = null
    var lastCreatePost: CreatePostRequest? = null
    var lastUpload: UploadRequest? = null
    /** Server-side view counts and the batches the app sent. */
    val viewCounts = mutableMapOf<String, Long>()
    val viewBatches = mutableListOf<List<String>>()
    var failViews: Throwable? = null

    private fun <T> page(all: List<T>, cursor: String?): Page<T> {
        val start = cursor?.toInt() ?: 0
        val end = minOf(all.size, start + pageSize)
        return Page(all.subList(start, end), if (end < all.size) end.toString() else null)
    }

    private fun maybeFail() { failNext?.let { failNext = null; throw it } }

    private val meProfile: Profile = Profile(id = "u1", username = "pushkar", displayName = "Pushkar", language = "en")

    override suspend fun usernameAvailable(username: String) = UsernameAvailability(username, username != "taken")
    override suspend fun signup(body: SignupRequest): AuthResponse { calls += "signup"; maybeFail(); return AuthResponse(meProfile.copy(username = body.username), "access-1", "refresh-1") }
    override suspend fun login(body: LoginRequest): AuthResponse { calls += "login:${body.identifier}"; maybeFail(); return AuthResponse(meProfile, "access-1", "refresh-1") }
    override suspend fun refresh(body: RefreshRequest) = TokenPair("access-2", "refresh-2")
    override suspend fun logout(body: RefreshRequest): Response<Unit> { calls += "logout"; return Response.success(204, Unit) }
    override suspend fun forgotPassword(body: ForgotPasswordRequest) = OkResponse(true)
    override suspend fun resetPassword(body: ResetPasswordRequest): Response<Unit> = Response.success(204, Unit)
    override suspend fun me(): Profile = meProfile
    override suspend fun updateMe(body: JsonObject): Profile {
        lastPatch = body
        return meProfile.copy(isPrivate = body["isPrivate"]?.jsonPrimitive?.content == "true")
    }
    override suspend fun mySaves(cursor: String?, limit: Int) = Page<Post>()
    override suspend fun myBlocks(cursor: String?, limit: Int) = Page<UserSummary>()
    override suspend fun user(username: String): Profile = meProfile.copy(username = username, viewer = ProfileViewer())
    override suspend fun userPosts(username: String, cursor: String?, limit: Int) = page(trending, cursor)
    override suspend fun vibeMates(username: String, cursor: String?, limit: Int) = Page<UserSummary>()
    override suspend fun vibingWith(username: String, cursor: String?, limit: Int) = Page<UserSummary>()
    override suspend fun vibeStars(username: String, cursor: String?, limit: Int) = Page<UserSummary>()
    override suspend fun vibeMate(username: String) = VibeMateResponse(username, "accepted", RelationCounts(1, 0))
    override suspend fun unVibeMate(username: String) = VibeMateResponse(username, "none", RelationCounts(0, 0))
    override suspend fun vibeStar(username: String) = VibeStarResponse(username, true, RelationCounts(0, 1))
    override suspend fun unVibeStar(username: String) = VibeStarResponse(username, false, RelationCounts(0, 0))
    override suspend fun block(username: String) = BlockResponse(username, true)
    override suspend fun unblock(username: String) = BlockResponse(username, false)
    override suspend fun followingFeed(cursor: String?, limit: Int): Page<Post> { calls += "following"; maybeFail(); return page(following, cursor) }
    override suspend fun trendingFeed(cursor: String?, limit: Int): Page<Post> { calls += "trending:${cursor ?: "-"}"; maybeFail(); return page(trending, cursor) }
    override suspend fun exploreFeed(cursor: String?, limit: Int, tag: String?): Page<Post> {
        calls += "explore:${tag ?: "-"}"; maybeFail()
        return page(if (tag == null) explore else explore.filter { tag in it.hashtags }, cursor)
    }
    override suspend fun createPost(body: CreatePostRequest): Post { lastCreatePost = body; return Companion.post("new", caption = body.caption) }
    override suspend fun post(id: String): Post = (trending + explore).firstOrNull { it.id == id } ?: Companion.post(id)
    override suspend fun recordView(id: String): ViewResult {
        calls += "view:$id"; maybeFail()
        failViews?.let { throw it }
        val n = viewCounts.getOrElse(id) { 0L } + 1
        viewCounts[id] = n
        return ViewResult(id, n, counted = true)
    }
    override suspend fun recordViews(body: ViewBatchRequest): ViewBatchResponse {
        calls += "views:${body.postIds.size}"
        failViews?.let { throw it }
        viewBatches += body.postIds
        val items = body.postIds.map { id -> ViewResult(id, viewCounts.getOrElse(id) { 0L }.plus(1).also { viewCounts[id] = it }, counted = true) }
        return ViewBatchResponse(items, items.size)
    }
    override suspend fun deletePost(id: String): Response<Unit> = Response.success(204, Unit)
    override suspend fun clap(id: String, body: ClapRequest): ClapResponse { calls += "clap:$id"; return ClapResponse(true, body.kind, PostCounts(vibeClaps = 1)) }
    override suspend fun unclap(id: String): ClapResponse { calls += "unclap:$id"; return ClapResponse(false, null, PostCounts(vibeClaps = 0)) }
    override suspend fun save(id: String) = SaveResponse(true, PostCounts(saves = 1))
    override suspend fun unsave(id: String) = SaveResponse(false, PostCounts(saves = 0))
    override suspend fun comments(postId: String, cursor: String?, limit: Int): CommentsPage {
        val p = page(comments, cursor)
        return CommentsPage(null, p.items, p.nextCursor)
    }
    override suspend fun replies(commentId: String, cursor: String?, limit: Int) = page(replies[commentId].orEmpty(), cursor)
    override suspend fun addComment(postId: String, body: CreateCommentRequest): Comment {
        calls += "comment:${body.parentId ?: "-"}"
        return Comment(id = "c-new-${calls.size}", postId = postId, parentId = body.parentId, body = body.body, createdAt = "2026-11-02T08:00:00.000Z", author = UserSummary("u1", "pushkar", "Pushkar"))
    }
    override suspend fun deleteComment(id: String): Response<Unit> = Response.success(204, Unit)
    override suspend fun report(body: ReportRequest): ReportResponse { calls += "report:${body.targetType}:${body.reason}"; return ReportResponse(true) }
    override suspend fun createUpload(body: UploadRequest): UploadResponse {
        lastUpload = body
        return UploadResponse(UploadedMedia("m1", body.purpose, "posts/u1/x.jpg", "pending"), PresignedUpload("https://r2.example/put", headers = mapOf("Content-Type" to body.contentType, "Content-Length" to body.sizeBytes.toString())))
    }

    companion object {
        fun post(id: String, caption: String = "hello #holi", tags: List<String> = listOf("holi"), viewer: PostViewer? = null, viewCount: Long = 0) = Post(
            id = id, type = "text", caption = caption, hashtags = tags, viewCount = viewCount,
            author = UserSummary("a1", "asha", "Asha"), createdAt = "2026-11-02T08:00:00.000Z", viewer = viewer,
        )

        fun httpError(status: Int, body: String): HttpException =
            HttpException(Response.error<Any>(status, body.toResponseBody(null)))
    }
}
