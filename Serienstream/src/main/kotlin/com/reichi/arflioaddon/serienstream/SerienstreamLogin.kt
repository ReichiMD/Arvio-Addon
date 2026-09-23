package com.reichi.arflioaddon.serienstream

import android.util.Log
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * Logs in to serienstream.to with a fixed account and hands the session cookies to the WebView.
 *
 * Why: the episode page carries data-redirect-gate-tier. Anonymous visitors get "turnstile_altcha"
 * (Cloudflare Turnstile + ALTCHA proof-of-work, unsolvable in a hidden WebView, v39-v53). Logged-in
 * visitors get a lower tier: "turnstile" from a datacenter IP, and on the user's phone the stream
 * started immediately (Buero docs/themen/62, 23.09.2026). The /login form itself has no captcha.
 *
 * The account is a throwaway account the user created for this plugin and explicitly asked to be
 * built in. Base64 only keeps it out of plain-text code search - it is NOT a secret.
 */
internal object SerienstreamLogin {
    private const val TAG = "ArvioAddon[SerienstreamLogin]"
    private const val BASE = "https://serienstream.to"
    private const val ACCOUNT_B64 = "c3RlZmZlbkBwYXBpZXJrb3JiLm1l"
    private const val PASSWORD_B64 = "MTIzNDU2"
    private const val SESSION_TTL_MS = 60L * 60L * 1000L

    // name -> value; replaced as a whole on every successful login.
    @Volatile private var cookies: Map<String, String> = emptyMap()
    @Volatile private var loggedInAt = 0L

    private fun decode(s: String) = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    /** Logs in if there is no fresh session yet. Blocking (network thread). Returns true when logged in. */
    @Synchronized
    fun ensureLoggedIn(userAgent: String): Boolean {
        if (cookies.containsKey("laravel_session") && System.currentTimeMillis() - loggedInAt < SESSION_TTL_MS) return true
        return try {
            val jar = LinkedHashMap<String, String>()
            val page = request("$BASE/login", "GET", null, userAgent, jar, "")
            val token = Regex("""name="_token"\s+value="([^"]+)"""").find(page.second)?.groupValues?.get(1)
            if (token == null) {
                Log.w(TAG, "login: no _token on /login (HTTP ${page.first}, ${page.second.length}B) - DDoS-Guard?")
                return false
            }
            val body = "_token=" + enc(token) + "&email=" + enc(decode(ACCOUNT_B64)) + "&password=" + enc(decode(PASSWORD_B64))
            val post = request("$BASE/login", "POST", body, userAgent, jar, "$BASE/login")
            // Success = 302 to /account (a failed login redirects back to /login).
            val ok = post.first in 300..399 && post.third.contains("/account")
            Log.d(TAG, "login: POST -> ${post.first} location=${post.third} ok=$ok cookies=${jar.keys}")
            if (ok) {
                cookies = jar.toMap()
                loggedInAt = System.currentTimeMillis()
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "login threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /** Copies the session cookies into the WebView cookie store. Call on the main thread before loadUrl. */
    fun applyToWebView() {
        val cm = CookieManager.getInstance()
        for ((k, v) in cookies) cm.setCookie(BASE, "$k=$v; path=/")
        try { cm.flush() } catch (_: Throwable) {}
        Log.d(TAG, "applyToWebView: ${cookies.keys}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Single request without redirect following, so the 302 of the login and its cookies are visible. */
    private fun request(
        url: String, method: String, body: String?, userAgent: String,
        jar: MutableMap<String, String>, referer: String
    ): Triple<Int, String, String> {
        var conn: HttpURLConnection? = null
        try {
            conn = openDohConnection(url).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
                requestMethod = method
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                if (referer.isNotEmpty()) {
                    setRequestProperty("Referer", referer)
                    setRequestProperty("Origin", BASE)
                }
                if (jar.isNotEmpty()) setRequestProperty("Cookie", jar.entries.joinToString("; ") { it.key + "=" + it.value })
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val setCookies = conn.headerFields?.get("Set-Cookie") ?: conn.headerFields?.get("set-cookie")
            setCookies?.forEach { raw ->
                val pair = raw.substringBefore(";")
                val eq = pair.indexOf('=')
                if (eq > 0) jar[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Triple(code, text, conn.getHeaderField("Location") ?: "")
        } finally {
            conn?.disconnect()
        }
    }
}
