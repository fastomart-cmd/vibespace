package com.vibespace.app.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// DTOs mirror the backend's JSON exactly (backend/src/lib/views.ts, routes/*.ts).

@Serializable
data class Page<T>(val items: List<T> = emptyList(), val nextCursor: String? = null)

@Serializable
data class UserSummary(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
)

@Serializable
data class PostMedia(
    val id: String,
    val url: String? = null,
    val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class PostCounts(val vibeClaps: Int = 0, val vibeTalk: Int = 0, val saves: Int = 0)

@Serializable
data class PostViewer(
    val hasClapped: Boolean = false,
    val clapKind: String? = null,
    val hasSaved: Boolean = false,
    val isAuthor: Boolean = false,
)

@Serializable
data class Post(
    val id: String,
    val type: String,
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val mentions: List<String> = emptyList(),
    val media: List<PostMedia> = emptyList(),
    val author: UserSummary,
    val counts: PostCounts = PostCounts(),
    /** null for guests */
    val viewer: PostViewer? = null,
    val createdAt: String,
    val updatedAt: String? = null,
    val trendingScore: Double? = null,
    /** Unique viewers (the server counts one view per viewer per post per 24 hours). */
    val viewCount: Long = 0,
)

@Serializable
data class ViewBatchRequest(val postIds: List<String>)

@Serializable
data class ViewResult(val postId: String, val viewCount: Long = 0, val counted: Boolean = false)

@Serializable
data class ViewBatchResponse(val items: List<ViewResult> = emptyList(), val counted: Int = 0)

@Serializable
data class CommentViewer(val isAuthor: Boolean = false, val canPin: Boolean = false)

@Serializable
data class Comment(
    val id: String,
    val postId: String,
    val parentId: String? = null,
    /** null when the comment was deleted but kept as a placeholder because it has replies */
    val body: String? = null,
    val isDeleted: Boolean = false,
    val isPinned: Boolean = false,
    val replyCount: Int = 0,
    val author: UserSummary? = null,
    val editedAt: String? = null,
    val createdAt: String,
    val viewer: CommentViewer? = null,
)

@Serializable
data class CommentsPage(
    val pinned: Comment? = null,
    val items: List<Comment> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class ProfileCounts(
    val vibeMates: Int = 0,
    val vibingWith: Int = 0,
    val vibeStars: Int = 0,
    val starred: Int = 0,
    val posts: Int = 0,
)

@Serializable
data class ProfileViewer(
    /** "none" | "pending" | "accepted" */
    val vibeMateStatus: String = "none",
    val isVibeMateOfYou: Boolean = false,
    val isVibeStar: Boolean = false,
    val isBlocked: Boolean = false,
    val isMuted: Boolean = false,
)

@Serializable
data class Profile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val bio: String = "",
    val isPrivate: Boolean = false,
    val profileUrl: String? = null,
    val counts: ProfileCounts = ProfileCounts(),
    val createdAt: String? = null,
    /** GET /users/:username only */
    val canViewContent: Boolean = true,
    /** GET /users/:username only, null for guests and for your own profile */
    val viewer: ProfileViewer? = null,
    /** /me and auth responses only */
    val language: String? = null,
    val email: String? = null,
    val role: String? = null,
)

// ---------------------------------------------------------------- auth

@Serializable
data class SignupRequest(
    val email: String,
    val username: String,
    val password: String,
    val displayName: String? = null,
    val language: String = "en",
)

@Serializable
data class LoginRequest(val identifier: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)

@Serializable
data class AuthResponse(
    val user: Profile,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 900,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Int = 900,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class UsernameAvailability(val username: String, val available: Boolean)

// ---------------------------------------------------------------- social

@Serializable
data class RelationCounts(val vibeMates: Int = 0, val vibeStars: Int = 0)

@Serializable
data class VibeMateResponse(val username: String, val vibeMateStatus: String, val counts: RelationCounts)

@Serializable
data class VibeStarResponse(val username: String, val isVibeStar: Boolean, val counts: RelationCounts)

@Serializable
data class BlockResponse(val username: String, val isBlocked: Boolean)

// ---------------------------------------------------------------- posts

@Serializable
data class CreatePostRequest(
    /** "text" or "image" */
    val type: String,
    val caption: String,
    val mediaIds: List<String>? = null,
)

@Serializable
data class ClapRequest(val kind: String = "clap")

@Serializable
data class ClapResponse(val hasClapped: Boolean, val clapKind: String? = null, val counts: PostCounts)

@Serializable
data class SaveResponse(val hasSaved: Boolean, val counts: PostCounts)

@Serializable
data class CreateCommentRequest(val body: String, val parentId: String? = null)

@Serializable
data class ReportRequest(
    /** "post" | "comment" | "user" */
    val targetType: String,
    val targetId: String,
    val reason: String,
    val details: String = "",
)

@Serializable
data class ReportResponse(val reported: Boolean, val duplicate: Boolean = false)

// ---------------------------------------------------------------- media

@Serializable
data class UploadRequest(
    /** "avatar" | "post_image" */
    val purpose: String,
    /** image/jpeg | image/png | image/webp | image/heic */
    val contentType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class UploadedMedia(
    val id: String,
    val purpose: String,
    val key: String,
    val status: String,
    val publicUrl: String? = null,
)

@Serializable
data class PresignedUpload(
    val url: String,
    val method: String = "PUT",
    val headers: Map<String, String> = emptyMap(),
    val expiresIn: Int = 600,
)

@Serializable
data class UploadResponse(val media: UploadedMedia, val upload: PresignedUpload)

// ---------------------------------------------------------------- errors

/** { error: { code, message, details? }, requestId } */
@Serializable
data class ErrorEnvelope(val error: ErrorBody, val requestId: String? = null)

@Serializable
data class ErrorBody(val code: String, val message: String = "", val details: JsonElement? = null)
