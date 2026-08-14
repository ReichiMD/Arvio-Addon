package com.reichi.arflioaddon.filmpalast

import android.util.Log

/**
 * Minimal logcat-only logger for the Arvio addon.
 *
 * Earlier versions kept an in-memory ring buffer, mirrored the trace to files, ran a local
 * HTTP server, and emitted the trace as fake ARVIO "sources". All of that depended on
 * Kotlin-stdlib classes (kotlin.io.FilesKt, kotlin.enums.EnumEntriesKt, kotlin.text.Channels,
 * kotlin.concurrent) that ARVIO's R8-shrunk release APK does NOT ship in the plugin's
 * parent classloader, causing NoClassDefFoundError that crashed ARVIO. We now have logcat via
 * WLAN-ADB, so the whole diagnostic machinery was removed in favor of plain android.util.Log
 * (always available) with no enum, no file IO, no threads.
 *
 * The methods intentionally do NOT reference any kotlin-stdlib extension type in their
 * signatures, so calling them cannot trigger a missing-class resolution.
 */
object DebugLog {
    private const val PREFIX = "ArvioAddon"

    fun t(tag: String, message: String) = Log.d("$PREFIX[$tag]", message)
    fun w(tag: String, message: String) = Log.w("$PREFIX[$tag]", message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e("$PREFIX[$tag]", "$message :: ${throwable.javaClass.simpleName}: ${throwable.message}")
        else Log.e("$PREFIX[$tag]", message)
    }
}
