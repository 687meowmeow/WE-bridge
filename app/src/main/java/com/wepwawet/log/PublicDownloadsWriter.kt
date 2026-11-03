package com.wepwawet.log

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * Write files to the user-visible Downloads/WepWawet/ directory.
 *
 * Uses MediaStore on Android 10+ (no permission needed for our own files
 * in the Downloads collection), and the legacy File API on Android 9 and
 * below (requires WRITE_EXTERNAL_STORAGE, declared with maxSdkVersion=28).
 *
 * Reused by:
 *   - CrashHandler (writes crash-<timestamp>.txt and crash-latest.txt)
 *   - WepWawetApplication (writes boot.txt breadcrumb)
 *   - LogsScreen "Download logs" button (writes logs-<timestamp>.txt)
 *   - WallpaperDetailScreen "Download .mpkg" debug button (saves the .mpkg
 *     so the user can inspect it with `xxd` / `strings` / `binwalk`)
 */
object PublicDownloadsWriter {

    private const val TAG = "PublicDownloadsWriter"
    private const val SUBDIR = "WepWawet"  // relative to public Downloads/

    /**
     * Write [content] bytes to Downloads/WepWawet/[fileName].
     *
     * @return the URI of the written file, or null on failure (error logged via WepWawetLogger).
     */
    fun writeBytes(context: Context, fileName: String, content: ByteArray): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, fileName, content)
            } else {
                writeLegacy(fileName, content)
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "writeBytes failed for $fileName", e)
            runCatching {
                WepWawetLogger.appError("PublicDownloadsWriter.writeBytes failed",
                    "file" to fileName,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""),
                    exception = e)
            }
            null
        }
    }

    /**
     * Write [text] to Downloads/WepWawet/[fileName] (UTF-8 encoded).
     */
    fun writeText(context: Context, fileName: String, text: String): Uri? {
        return writeBytes(context, fileName, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Append [extra] to an existing file at Downloads/WepWawet/[fileName].
     * If the file doesn't exist, does nothing (returns null).
     *
     * Used by the boot.txt breadcrumb pattern.
     */
    fun appendText(context: Context, fileName: String, extra: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val existing = findFile(context, collection, fileName) ?: return
                resolver.openOutputStream(existing, "wa")?.use { out ->
                    out.write(extra.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, "$SUBDIR/$fileName")
                if (file.exists()) file.appendText(extra)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "appendText failed for $fileName", e)
        }
    }

    // ====== Internals ======

    /**
     * Find an existing file in Downloads/WepWawet/ by display name.
     * Returns the MediaStore Uri, or null if not found.
     */
    private fun findFile(context: Context, collection: Uri, displayName: String): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        // v0.7.24 (H-A2 BUG FIX): the query filtered ONLY on DISPLAY_NAME — a
        // file with the same name anywhere under Download/ (created by any
        // other app, or the user) matched, and appendText then opened THAT
        // foreign URI in "wa" mode and appended WepWawet's text to it,
        // silently corrupting another app's file. Scope the query to OUR
        // subdirectory (the same fix WepWawetApplication.findBootFile got).
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(displayName, "Download/$SUBDIR/")
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun writeViaMediaStore(context: Context, fileName: String, content: ByteArray): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // If the file already exists, delete it first so we can re-insert
        // cleanly (avoids IS_PENDING update issues on Android 10).
        findFile(context, collection, fileName)?.let { existing ->
            runCatching { resolver.delete(existing, null, null) }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, guessMimeType(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$SUBDIR")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(collection, values)
            ?: throw java.io.IOException("MediaStore.insert returned null for $fileName")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(content)
                out.flush()
            } ?: throw java.io.IOException("openOutputStream returned null for $uri")

            // Mark as no longer pending so the file becomes visible to the user
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
        } catch (e: Exception) {
            // Clean up partial file
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        return uri
    }

    @Suppress("DEPRECATION")
    private fun writeLegacy(fileName: String, content: ByteArray) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, SUBDIR).apply { mkdirs() }
        File(dir, fileName).writeBytes(content)
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".txt") -> "text/plain"
            lower.endsWith(".log") -> "text/plain"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".mpkg") -> "application/octet-stream"
            lower.endsWith(".pkg") -> "application/octet-stream"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
