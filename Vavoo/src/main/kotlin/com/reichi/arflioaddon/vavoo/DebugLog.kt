package com.reichi.arflioaddon.vavoo

import android.util.Log

/**
 * Minimal logcat-only logger for the Arvio addon (KinoGer module).
 *
 * See Filmpalast/DebugLog.kt for the rationale: plain android.util.Log (always available),
 * no kotlin-stdlib IO/enum/thread classes that ARVIO's R8-shrunk classloader lacks.
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
