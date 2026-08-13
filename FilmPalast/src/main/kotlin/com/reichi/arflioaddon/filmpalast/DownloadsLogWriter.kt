package com.reichi.arflioaddon.filmpalast

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * Writes the trace into the PUBLIC Downloads folder so the user can read it with any
 * file manager on Android 13+ without special permissions.
 *
 * On Android 10+ (API 29+) we must use the MediaStore API to write to Downloads; direct
 * File access to /sdcard/Download is blocked by scoped storage. MediaStore.Downloads
 * inserts do not require any runtime permission. On older API levels we fall back to
 * direct File writes to the legacy Downloads directory.
 *
 * A MediaStore item cannot be efficiently appended to, so we keep an in-memory snapshot
 * of the full trace and rewrite the whole file on each [flush] call. Flushes are
 * throttled to avoid hammering the content provider.
 */
object DownloadsLogWriter {
    private const val TAG = "DownloadsLogWriter"
    private const val TRACE_FILE = "arvio-filmpalast-trace.log"
    private const val MARKER_FILE = "arvio-plugin-loaded.txt"
    private const val MIN_FLUSH_INTERVAL_MS = 1500L

    private var ctx: Context? = null
    private var useMediaStore = false
    @Volatile private var lastFlushAt = 0L

    fun init(context: Context) {
        ctx = context.applicationContext
        useMediaStore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        writeMarker(context)
    }

    private fun writeMarker(context: Context) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.GERMANY)
            .format(java.util.Date())
        val text = "Arvio Filmpalast plugin loaded at $now\n" +
            "If you can read this file in Downloads, the plugin loaded successfully.\n" +
            "arvio-filmpalast-trace.log (same folder) gets entries after a source search.\n"
        try {
            if (useMediaStore) {
                writeViaMediaStore(context, MARKER_FILE, text, mimeType = "text/plain")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "arvio-addon-logs")
                if (!dir.exists()) dir.mkdirs()
                File(dir, MARKER_FILE).writeText(text)
            }
        } catch (e: Exception) {
            Log.w("ArvioAddon[$TAG]", "writeMarker failed: ${e.message}")
        }
    }

    /** Rewrite the whole trace to the Downloads file. Throttled. */
    fun flush(lines: List<String>) {
        val context = ctx ?: return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < MIN_FLUSH_INTERVAL_MS) return
        lastFlushAt = now
        try {
            val text = lines.joinToString("\n") + "\n"
            if (useMediaStore) {
                writeViaMediaStore(context, TRACE_FILE, text, mimeType = "text/plain")
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "arvio-addon-logs")
                if (!dir.exists()) dir.mkdirs()
                File(dir, TRACE_FILE).writeText(text)
            }
        } catch (e: Exception) {
            Log.w("ArvioAddon[$TAG]", "flush failed: ${e.message}")
        }
    }

    private fun writeViaMediaStore(context: Context, fileName: String, content: String, mimeType: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_URI
            ?: MediaStore.Files.getContentUri("external")

        // Try to find an existing item with the same name in Downloads and overwrite it.
        val existing = findExistingUri(resolver, collection, fileName)
        val uri: Uri = if (existing != null) {
            // truncate then rewrite
            try { resolver.openOutputStream(existing, "wt")?.use { it.write(ByteArray(0)) } } catch (_: Exception) {}
            existing
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/arvio-addon-logs")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            resolver.insert(collection, values) ?: return
        }

        resolver.openOutputStream(uri, "wt")?.use { os: OutputStream ->
            os.write(content.toByteArray())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fin = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, fin, null, null)
        }
    }

    private fun findExistingUri(resolver: android.content.ContentResolver, collection: Uri, name: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selArgs = arrayOf(name)
        resolver.query(collection, projection, selection, selArgs, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return android.content.ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }
}
