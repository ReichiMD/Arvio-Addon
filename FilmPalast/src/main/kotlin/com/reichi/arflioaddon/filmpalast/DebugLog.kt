package com.reichi.arflioaddon.filmpalast

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-diagnosis trace logger for the Arvio addon.
 *
 * ARVIO has no in-app log export and writes no scraper logs to files (see AGENTS.md
 * "Recherche: ARVIO Test-Funktion"). Reading Android's logcat from within the app is
 * blocked without READ_LOGS (only granted to debuggable/system apps; the sideload APK is
 * a release build). So instead of reading logcat, we instrument our own scraper code and
 * keep a trace that the user can read through [DebugServer] at http://localhost:8420.
 *
 * The trace is kept in memory (ring buffer) and mirrored to a file under ARVIO's
 * app-specific external storage dir (no permission required since API 19):
 *   Android/data/com.arflix.tv/files/arvio-addon-logs/filmpalast-trace.log
 */
object DebugLog {
    private const val MAX_ENTRIES = 2000
    private const val TAG_PREFIX = "ArvioAddon"

    private val entries = ArrayList<Entry>(256)
    private val lock = Any()
    private var logFile: File? = null
    private var markerFile: File? = null
    private val tsFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)

    data class Entry(val time: Long, val tag: String, val level: Level, val message: String)

    enum class Level { TRACE, WARN, ERROR }

    fun init(context: Context) {
        try {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, "arvio-addon-logs")
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "filmpalast-trace.log")
            // Write a startup marker immediately so the user can confirm the plugin
            // loaded at all, even if no search has been triggered yet.
            markerFile = File(dir, "PLUGIN_LOADED.txt")
            try {
                markerFile?.writeText(
                    "Arvio Filmpalast plugin loaded at ${java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", java.util.Locale.GERMANY
                    ).format(java.util.Date())}\n" +
                        "App-specific dir: ${base.absolutePath}\n" +
                        "If this file exists, plugin.load() ran. The trace file " +
                        "filmpalast-trace.log only gets entries after a source search.\n"
                )
            } catch (_: Exception) {}
            // Also mirror everything into the PUBLIC Downloads folder so the user can
            // read it with any file manager on Android 13+ (no permission needed).
            DownloadsLogWriter.init(context)
            add(Level.TRACE, "DebugLog", "init ok, log dir=${base.absolutePath}")
        } catch (e: Exception) {
            logFile = null
        }
    }

    fun t(tag: String, message: String) = add(Level.TRACE, tag, message)
    fun w(tag: String, message: String) = add(Level.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$message :: ${throwable.javaClass.simpleName}: ${throwable.message}"
            else message
        add(Level.ERROR, tag, full)
    }

    private fun add(level: Level, tag: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, level, message)
        synchronized(lock) {
            entries.add(entry)
            if (entries.size > MAX_ENTRIES) entries.removeAt(0)
            logFile?.let { f ->
                try {
                    // append + flush so entries survive even if the process is killed
                    f.appendText(format(entry) + "\n")
                    f.setReadable(true, false)
                } catch (_: Exception) {
                }
            }
            // Mirror the full trace to the public Downloads folder (throttled inside).
            DownloadsLogWriter.flush(entries.map { format(it) })
        }
        // also surface to logcat so ADB users see it too
        when (level) {
            Level.ERROR -> Log.e("$TAG_PREFIX[$tag]", message)
            Level.WARN -> Log.w("$TAG_PREFIX[$tag]", message)
            Level.TRACE -> Log.d("$TAG_PREFIX[$tag]", message)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            try { logFile?.delete() } catch (_: Exception) {}
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun format(e: Entry): String {
        val lvl = when (e.level) {
            Level.TRACE -> "TRACE"
            Level.WARN -> "WARN "
            Level.ERROR -> "ERROR"
        }
        return "${tsFormat.format(Date(e.time))} $lvl [${e.tag}] ${e.message}"
    }
}
