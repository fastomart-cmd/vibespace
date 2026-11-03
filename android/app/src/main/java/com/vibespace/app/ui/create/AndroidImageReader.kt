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
 * Turns a picked image into bytes the backend accepts (jpeg/png/webp/heic). Large or unsupported images
 * are re-encoded as JPEG with the long edge capped, which also keeps uploads small on mobile data.
 */
class AndroidImageReader(private val context: Context, private val maxEdge: Int = 2048, private val maxBytes: Int = 4 * 1024 * 1024) : ImageReader {
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
        // Downscale + JPEG.
        var sample = 1
        val longEdge = maxOf(w ?: 0, h ?: 0)
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Unsupported image")
        val scaled = if (maxOf(bmp.width, bmp.height) > maxEdge) {
            val f = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
        } else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        ImagePayload(out.toByteArray(), "image/jpeg", scaled.width, scaled.height)
    }
}
