package com.vibespace.app.data.repo

import com.vibespace.app.data.api.Page
import com.vibespace.app.data.api.Post
import com.vibespace.app.data.api.Profile
import com.vibespace.app.data.api.ProfileViewer
import com.vibespace.app.data.api.UserSummary
import com.vibespace.app.data.api.VibeApi
import com.vibespace.app.data.api.apiCall

enum class UserListKind { VIBEMATES, VIBING_WITH, VIBESTARS }

class UserRepository(private val api: VibeApi, private val auth: AuthRepository) {
    suspend fun profile(username: String): Result<Profile> = apiCall { api.user(username) }

    suspend fun posts(username: String, cursor: String?): Result<Page<Post>> = apiCall { api.userPosts(username, cursor) }

    suspend fun list(username: String, kind: UserListKind, cursor: String?): Result<Page<UserSummary>> = apiCall {
        when (kind) {
            UserListKind.VIBEMATES -> api.vibeMates(username, cursor)
            UserListKind.VIBING_WITH -> api.vibingWith(username, cursor)
            UserListKind.VIBESTARS -> api.vibeStars(username, cursor)
        }
    }

    /** Become a VibeMate (or cancel/leave). Private accounts turn this into a pending request. */
    suspend fun toggleVibeMate(p: Profile): Result<Profile> = apiCall {
        auth.requireLogin()
        val status = p.viewer?.vibeMateStatus ?: "none"
        val r = if (status == "none") api.vibeMate(p.username) else api.unVibeMate(p.username)
        val v = p.viewer ?: ProfileViewer()
        p.copy(
            viewer = v.copy(vibeMateStatus = r.vibeMateStatus),
            counts = p.counts.copy(vibeMates = r.counts.vibeMates, vibeStars = r.counts.vibeStars),
        )
    }

    suspend fun toggleVibeStar(p: Profile): Result<Profile> = apiCall {
        auth.requireLogin()
        val r = if (p.viewer?.isVibeStar == true) api.unVibeStar(p.username) else api.vibeStar(p.username)
        val v = p.viewer ?: ProfileViewer()
        p.copy(
            viewer = v.copy(isVibeStar = r.isVibeStar),
            counts = p.counts.copy(vibeMates = r.counts.vibeMates, vibeStars = r.counts.vibeStars),
        )
    }

    suspend fun block(username: String): Result<Boolean> = apiCall { auth.requireLogin(); api.block(username).isBlocked }

    suspend fun unblock(username: String): Result<Boolean> = apiCall { auth.requireLogin(); api.unblock(username).isBlocked }

    suspend fun blocked(cursor: String?): Result<Page<UserSummary>> = apiCall { auth.requireLogin(); api.myBlocks(cursor) }
}
