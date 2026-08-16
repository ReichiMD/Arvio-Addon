package com.reichi.arflioaddon.serienstream

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Minimal in-memory cookie jar (java.net, no okhttp/kotlin-stdlib-IO deps). DDoS-Guard sets
 * __ddg* cookies on 403 responses that must be replayed on the retry. HttpURLConnection does not
 * manage cookies automatically, so we capture Set-Cookie headers here and send them back via the
 * Cookie request header.
 *
 * Scoped per httpGetInternal call (one jar per resolution attempt) — sufficient for the DDoS-Guard
 * check.js + image + retry sequence, which all target the same host within one resolution.
 */
internal class CookieJar {
    private val cookies: MutableMap<String, String> = mutableMapOf()

    fun captureSetCookie(conn: HttpURLConnection, requestUrl: String) {
        val fields = conn.headerFields ?: return
        val setCookies = fields["Set-Cookie"] ?: fields["set-cookie"] ?: return
        val host = try { URI(requestUrl).host?.lowercase() } catch (_: Throwable) { "" }
        for (raw in setCookies) {
            // Format: name=value; Path=/; Domain=.host; ...
            val pair = raw.substringBefore(";").trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isNotEmpty()) cookies[name] = value
        }
        if (cookies.isNotEmpty()) {
            // keep host for domain scoping if needed later
        }
    }

    fun toCookieHeader(requestUrl: String): String {
        if (cookies.isEmpty()) return ""
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun names(): Set<String> = cookies.keys.toSet()

    fun hasAny(namePrefix: String): Boolean = cookies.keys.any { it.startsWith(namePrefix) }
}
