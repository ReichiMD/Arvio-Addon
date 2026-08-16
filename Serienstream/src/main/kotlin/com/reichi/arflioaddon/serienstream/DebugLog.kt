package com.reichi.arflioaddon.serienstream

/**
 * Minimal android.util.Log wrapper. No enum Level (enum <clinit> needs EnumEntriesKt which
 * ARVIO's R8 strips), no file IO (FilesKt stripped), no threads. Methods t/w/e forward directly
 * to Log.d/w/e. See AGENTS.md Erkenntnis #3/#4 for why this must stay dependency-free.
 */
object DebugLog {
    private const val TAG = "ArvioAddon[Serienstream]"

    fun t(tag: String, msg: String) {
        android.util.Log.d("$TAG[$tag]", msg)
    }

    fun w(tag: String, msg: String) {
        android.util.Log.w("$TAG[$tag]", msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e("$TAG[$tag]", msg, t)
    }
}
