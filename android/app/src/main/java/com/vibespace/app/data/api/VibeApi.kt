package com.vibespace.app.data.api

import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The VibeSpace Phase 1 API under /api/v1 (see backend/src/routes). Relative paths here are resolved
 * against "<BuildConfig.API_BASE_URL>/api/v1/".
 */
interface VibeApi {
    // ---- auth
    @GET("auth/username-available")
    suspend fun usernameAvailable(@Query("username") username: String): UsernameAvailability

    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenPair

    @POST("auth/logout")
    suspend fun logout(@Body body: RefreshRequest): Response<Unit>

    @POST("auth/password/forgot")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): OkResponse

    @POST("auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): Response<Unit>

    // ---- me
    @GET("me")
    suspend fun me(): Profile

    /** Body keys: displayName, bio, isPrivate, language, avatarMediaId (nullable). Unknown keys are rejected. */
    @PATCH("me")
    suspend fun updateMe(@Body body: JsonObject): Profile

    @GET("me/saves")
    suspend fun mySaves(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<Post>

    @GET("me/blocks")
    suspend fun myBlocks(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<UserSummary>

    // ---- users (guest OK)
    @GET("users/{username}")
    suspend fun user(@Path("username") username: String): Profile

    @GET("users/{username}/posts")
    suspend fun userPosts(
        @Path("username") username: String,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
    ): Page<Post>

    @GET("users/{username}/vibemates")
    suspend fun vibeMates(@Path("username") username: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<UserSummary>

    @GET("users/{username}/vibing-with")
    suspend fun vibingWith(@Path("username") username: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<UserSummary>

    @GET("users/{username}/vibestars")
    suspend fun vibeStars(@Path("username") username: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<UserSummary>

    // ---- VibeMates / VibeStars / safety (login required)
    @POST("users/{username}/vibemate")
    suspend fun vibeMate(@Path("username") username: String): VibeMateResponse

    @DELETE("users/{username}/vibemate")
    suspend fun unVibeMate(@Path("username") username: String): VibeMateResponse

    @POST("users/{username}/vibestar")
    suspend fun vibeStar(@Path("username") username: String): VibeStarResponse

    @DELETE("users/{username}/vibestar")
    suspend fun unVibeStar(@Path("username") username: String): VibeStarResponse

    @POST("users/{username}/block")
    suspend fun block(@Path("username") username: String): BlockResponse

    @DELETE("users/{username}/block")
    suspend fun unblock(@Path("username") username: String): BlockResponse

    // ---- feeds
    @GET("feed/following")
    suspend fun followingFeed(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<Post>

    @GET("feed/trending")
    suspend fun trendingFeed(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<Post>

    @GET("feed/explore")
    suspend fun exploreFeed(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("tag") tag: String? = null,
    ): Page<Post>

    // ---- posts
    @POST("posts")
    suspend fun createPost(@Body body: CreatePostRequest): Post

    @GET("posts/{id}")
    suspend fun post(@Path("id") id: String): Post

    @DELETE("posts/{id}")
    suspend fun deletePost(@Path("id") id: String): Response<Unit>

    @PUT("posts/{id}/clap")
    suspend fun clap(@Path("id") id: String, @Body body: ClapRequest = ClapRequest()): ClapResponse

    @DELETE("posts/{id}/clap")
    suspend fun unclap(@Path("id") id: String): ClapResponse

    @PUT("posts/{id}/save")
    suspend fun save(@Path("id") id: String): SaveResponse

    @DELETE("posts/{id}/save")
    suspend fun unsave(@Path("id") id: String): SaveResponse

    // ---- views (guest OK; the device id travels in the X-Device-Id header)
    @POST("posts/{id}/view")
    suspend fun recordView(@Path("id") id: String): ViewResult

    /** Max 50 ids. Posts the viewer can't see are left out of the response. */
    @POST("posts/views")
    suspend fun recordViews(@Body body: ViewBatchRequest): ViewBatchResponse

    // ---- VibeTalk
    @GET("posts/{id}/comments")
    suspend fun comments(@Path("id") postId: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): CommentsPage

    @GET("comments/{id}/replies")
    suspend fun replies(@Path("id") commentId: String, @Query("cursor") cursor: String? = null, @Query("limit") limit: Int = 20): Page<Comment>

    @POST("posts/{id}/comments")
    suspend fun addComment(@Path("id") postId: String, @Body body: CreateCommentRequest): Comment

    @DELETE("comments/{id}")
    suspend fun deleteComment(@Path("id") commentId: String): Response<Unit>

    // ---- reports & media
    @POST("reports")
    suspend fun report(@Body body: ReportRequest): ReportResponse

    @POST("media/uploads")
    suspend fun createUpload(@Body body: UploadRequest): UploadResponse
}
