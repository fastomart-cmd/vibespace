package com.vibespace.app.data.repo

import com.vibespace.app.data.api.AppError
import com.vibespace.app.data.api.UploadRequest
import com.vibespace.app.data.api.VibeApi
import com.vibespace.app.data.api.apiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** An image ready to upload: bytes plus the content type and size the backend signs into the URL. */
class ImagePayload(val bytes: ByteArray, val contentType: String, val width: Int?, val height: Int?)

/** PUTs bytes to a presigned URL. Separate so tests can fake it. */
fun interface BlobUploader {
    /** Throws on failure. */
    suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String)
}

/** Uses a bare OkHttp client: no Bearer token or app headers may go to R2 (the URL is signed). */
class OkHttpBlobUploader(private val client: OkHttpClient) : BlobUploader {
    override suspend fun put(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).put(bytes.toRequestBody(contentType.toMediaType()))
        // Content-Length is set by OkHttp from the body and must equal the signed size; Content-Type comes from the body.
        headers.forEach { (k, v) -> if (!k.equals("Content-Length", true) && !k.equals("Content-Type", true)) b.header(k, v) }
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw AppError.Api(resp.code, "MEDIA_INVALID", "Upload failed (${resp.code}).")
        }
    }
}

/**
 * Presigned upload flow (backend routes/media.ts):
 * 1) POST /media/uploads → media.id + upload.url  2) PUT bytes to upload.url  3) use media.id in POST /posts or PATCH /me.
 */
class MediaRepository(private val api: VibeApi, private val uploader: BlobUploader, private val auth: AuthRepository) {
    suspend fun upload(image: ImagePayload, purpose: String): Result<String> = apiCall {
        auth.requireLogin()
        require(image.contentType in ALLOWED) { "Unsupported type ${image.contentType}" }
        val r = api.createUpload(UploadRequest(purpose, image.contentType, image.bytes.size.toLong(), image.width, image.height))
        uploader.put(r.upload.url, r.upload.headers, image.bytes, image.contentType)
        r.media.id
    }

    companion object {
        const val PURPOSE_POST = "post_image"
        const val PURPOSE_AVATAR = "avatar"
        val ALLOWED = setOf("image/jpeg", "image/png", "image/webp", "image/heic")
    }
}
