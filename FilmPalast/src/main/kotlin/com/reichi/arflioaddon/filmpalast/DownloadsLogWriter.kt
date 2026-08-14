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
                // java.io (not kotlin.io writeText) — FilesKt is missing in ARVIO's
                // plugin classloader at runtime (release APK shrinks it); see DebugLog.kt.
                writeTextJava(File(dir, MARKER_FILE), text)
            }
        } catch (e: Throwable) {
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
                // java.io (not kotlin.io writeText) — see DebugLog.kt re. FilesKt linkage.
                writeTextJava(File(dir, TRACE_FILE), text)
            }
        } catch (e: Throwable) {
            Log.w("ArvioAddon[$TAG]", "flush failed: ${e.message}")
        }
    }

    private fun writeViaMediaStore(context: Context, fileName: String, content: String, mimeType: String) {
        val resolver = context.contentResolver
        // MediaStore.Downloads.EXTERNAL_URI is only available from API 29 and may be
        // absent from older compile SDKs; MediaStore.Files.getContentUri("external")
        // works universally and, combined with RELATIVE_PATH=Downloads on API 29+,
        // lands the file in the public Downloads folder.
        val collection = MediaStore.Files.getContentUri("external")

        // Try to find an existing item with the same name in Downloads and overwrite it.
        val existing = findExistingUri(resolver, collection, fileName)
        val uri: Uri = if (existing != null) {
            // truncate then rewrite (plain java.io — no kotlin-stdlib .use/.toByteArray)
            truncateAndClose(resolver.openOutputStream(existing, "wt"))
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

        writeAndClose(resolver.openOutputStream(uri, "wt"), content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fin = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, fin, null, null)
        }
    }

    private fun findExistingUri(resolver: android.content.ContentResolver, collection: Uri, name: String): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selArgs = arrayOf(name)
        val cursor = resolver.query(collection, projection, selection, selArgs, null)
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return android.content.ContentUris.withAppendedId(collection, id)
                }
            } finally {
                cursor.close()
            }
        }
        return null
    }
}

// Plain java.io helpers — avoid kotlin-stdlib extension functions (.use, .toByteArray,
// writeText) which can be missing from ARVIO's shrunk plugin classloader at runtime
// (NoClassDefFoundError: kotlin/io/FilesKt and similar). See DebugLog.kt.
private fun writeTextJava(file: File, text: String) {
    val out = java.io.FileOutputStream(file)
    try {
        out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    } finally {
        out.close()
    }
}

private fun writeAndClose(os: OutputStream?, content: String) {
    if (os == null) return
    try {
        os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    } finally {
        os.close()
    }
}

private fun truncateAndClose(os: OutputStream?) {
    if (os == null) return
    try {
        os.write(ByteArray(0))
    } finally {
        os.close()
    }
}
