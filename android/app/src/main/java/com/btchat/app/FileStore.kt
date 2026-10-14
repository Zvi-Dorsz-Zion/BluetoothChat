package com.btchat.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream

/**
 * Where received files go: a folder the user picked in settings, or by default
 * the public "Downloads/BT Chat" folder (visible in every file manager).
 */
class FileStore(private val context: Context, private val prefs: Prefs) {

    class Target(
        val uri: Uri,
        val output: OutputStream,
        val finalName: String,
        val location: String,
        internal val inMediaStore: Boolean
    )

    data class UriInfo(val name: String, val size: Long)

    fun create(rawName: String): Target {
        val name = sanitize(rawName)
        val mime = mimeOf(name)
        val resolver = context.contentResolver

        prefs.saveTreeUri?.let { tree ->
            try {
                val dir = DocumentFile.fromTreeUri(context, Uri.parse(tree))
                if (dir != null && dir.canWrite()) {
                    val file = dir.createFile(mime, name)
                    val out = file?.let { resolver.openOutputStream(it.uri) }
                    if (file != null && out != null) {
                        val finalName = file.name ?: name
                        return Target(file.uri, out, finalName, "${dir.name ?: "התיקייה שנבחרה"}/$finalName", false)
                    }
                }
            } catch (_: Exception) {
                // folder removed or permission revoked - fall back to Downloads
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("לא ניתן ליצור קובץ בתיקיית ההורדות")
        val out = resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            throw IOException("לא ניתן לכתוב לתיקיית ההורדות")
        }
        val finalName = queryInfo(context, uri).name
        return Target(uri, out, finalName, "הורדות/$FOLDER/$finalName", true)
    }

    fun complete(target: Target, success: Boolean) {
        val resolver = context.contentResolver
        try {
            if (target.inMediaStore) {
                if (success) {
                    resolver.update(target.uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                } else {
                    resolver.delete(target.uri, null, null)
                }
            } else if (!success) {
                DocumentsContract.deleteDocument(resolver, target.uri)
            }
        } catch (_: Exception) {
        }
    }

    fun folderLabel(): String {
        val tree = prefs.saveTreeUri ?: return "הורדות/$FOLDER (ברירת מחדל)"
        val name = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree))?.name }.getOrNull()
        return name ?: Uri.parse(tree).lastPathSegment ?: tree
    }

    companion object {
        const val FOLDER = "BT Chat"

        fun sanitize(name: String): String {
            val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim().trimStart('.')
            return cleaned.ifEmpty { "file_${System.currentTimeMillis()}" }.take(150)
        }

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        fun queryInfo(context: Context, uri: Uri): UriInfo {
            var name: String? = null
            var size = -1L
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            if (size < 0) {
                size = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) {
                    -1L
                }
            }
            return UriInfo(name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size)
        }
    }
}
