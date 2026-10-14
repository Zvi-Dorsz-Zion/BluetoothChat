package com.btchat.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/** "Reduced quality" for photos: max 1600px on the long side, JPEG 75%. */
object ImageTools {
    private const val MAX_SIDE = 1600
    private const val JPEG_QUALITY = 75

    fun isImage(context: Context, uri: Uri, name: String?): Boolean {
        val mime = context.contentResolver.getType(uri)
            ?: FileStore.mimeOf(name ?: uri.lastPathSegment ?: "")
        return mime.startsWith("image/") && mime != "image/gif"
    }

    /** Returns a smaller JPEG copy in the cache, or null if the image could not be decoded. */
    fun reduce(context: Context, uri: Uri, name: String): File? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
            )
        }

        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (_: Exception) {
            0
        }
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val dir = File(context.cacheDir, "outgoing/${System.currentTimeMillis()}").apply { mkdirs() }
        val out = File(dir, FileStore.sanitize(name.substringBeforeLast('.')) + ".jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        return out
    }
}
