package com.vibespace.app.ui.create

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vibespace.app.data.repo.ImagePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns a picked image into a JPEG of at most [maxBytes] (default 100 KB, the VibeSpace limit for
 * profile photos, post images and thumbnails). Shrinks quality first, then dimensions, until it fits.
 */
class AndroidImageReader(private val context: Context, private val maxEdge: Int = 1080, private val maxBytes: Int = MAX_IMAGE_BYTES) : ImageReader {
    companion object { const val MAX_IMAGE_BYTES = 100 * 1024 }

    override suspend fun read(uri: String): ImagePayload = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        val cr = context.contentResolver
        val type = cr.getType(u) ?: "image/jpeg"
        val raw = cr.openInputStream(u)?.use { it.readBytes() } ?: error("Cannot open image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth.takeIf { it > 0 }
        val h = bounds.outHeight.takeIf { it > 0 }
        val supported = type in setOf("image/jpeg", "image/png", "image/webp")
        if (supported && raw.size <= maxBytes && (w ?: 0) <= maxEdge && (h ?: 0) <= maxEdge) {
            return@withContext ImagePayload(raw, type, w, h)
        }
        var sample = 1
        val longEdge = maxOf(w ?: 0, h ?: 0)
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Unsupported image")
        var edge = minOf(maxEdge, maxOf(bmp.width, bmp.height))
        while (true) {
            val f = edge.toFloat() / maxOf(bmp.width, bmp.height)
            val scaled = if (f < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true) else bmp
            for (q in intArrayOf(85, 75, 65, 55, 45, 35)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= maxBytes || (edge <= 160 && q == 35)) {
                    return@withContext ImagePayload(out.toByteArray(), "image/jpeg", scaled.width, scaled.height)
                }
            }
            edge = (edge * 0.8f).toInt()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
