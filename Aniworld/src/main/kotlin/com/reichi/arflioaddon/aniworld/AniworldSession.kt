package com.reichi.arflioaddon.aniworld

import android.util.Log
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URLEncoder

/**
 * The one aniworld.to session of this plugin: cookies (login, browser-check cookies such as
 * cf_clearance / __ddg*) shared between the java.net requests and the hidden WebView, plus the
 * login with the same throwaway account Serienstream uses (user, 25.09.2026: "Die Anmelde-Daten
 * sind die gleichen wie in Serienstream").
 *
 * The cookies live here in memory; the WebView gets a copy before every load and hands its own
 * cookies back afterwards (CookieManager is only touched on the main thread). Browser-check
 * cookies are bound to the User-Agent, so java.net and the WebView both send [USER_AGENT].
 *
 * The login form is read generically (every <input> of the form that holds the password field is
 * sent back with its value), because this session cannot open aniworld.to itself (datacenter
 * block) - the log tells what the form looked like on the device.
 */
internal object AniworldSession {
    private const val TAG = "ArvioAddon[AniworldSession]"
    const val BASE = "https://aniworld.to"
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val ACCOUNT_B64 = "c3RlZmZlbkBwYXBpZXJrb3JiLm1l"
    private const val PASSWORD_B64 = "MTIzNDU2"
    private const val SESSION_TTL_MS = 60L * 60L * 1000L
    private const val RETRY_AFTER_FAIL_MS = 10L * 60L * 1000L

    private val cookies = LinkedHashMap<String, String>()
    @Volatile private var loggedInAt = 0L
    @Volatile private var failedAt = 0L

    class Resp(val code: Int, val text: String, val url: String, val location: String)

    fun isAniworld(url: String): Boolean {
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return host == "aniworld.to" || host.endsWith(".aniworld.to")
    }

    fun cookieHeader(): String = synchronized(cookies) { cookies.entries.joinToString("; ") { it.key + "=" + it.value } }

    fun cookieNames(): String = synchronized(cookies) { cookies.keys.toString() }

    /** Stores the Set-Cookie headers of an aniworld.to answer. */
    fun capture(conn: HttpURLConnection) {
        val setCookies = conn.headerFields?.get("Set-Cookie") ?: conn.headerFields?.get("set-cookie") ?: return
        synchronized(cookies) {
            for (raw in setCookies) {
                val pair = raw.substringBefore(";")
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
    }

    /** Main thread only: give the WebView our cookies before it loads an aniworld.to page. */
    fun copyToWebView() {
        val cm = CookieManager.getInstance()
        val copy = synchronized(cookies) { LinkedHashMap(cookies) }
        for ((k, v) in copy) cm.setCookie(BASE, "$k=$v; path=/")
        try { cm.flush() } catch (_: Throwable) {}
        Log.d(TAG, "copyToWebView: ${copy.keys}")
    }

    /** Main thread only: take over what the WebView collected (browser-check cookies, session). */
    fun takeFromWebView() {
        val header = try { CookieManager.getInstance().getCookie(BASE) } catch (_: Throwable) { null } ?: return
        synchronized(cookies) {
            for (pair in header.split(";")) {
                val eq = pair.indexOf('=')
                if (eq > 0) cookies[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
        Log.d(TAG, "takeFromWebView: now ${cookieNames()}")
    }

    private fun decode(s: String) = String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    /**
     * Logs in if there is no fresh login yet. Blocking (network thread). Returns true when logged in.
     * A failed login is not retried for [RETRY_AFTER_FAIL_MS] (each try costs requests).
     */
    @Synchronized
    fun ensureLoggedIn(): Boolean {
        val now = System.currentTimeMillis()
        if (loggedInAt > 0 && now - loggedInAt < SESSION_TTL_MS) return true
        if (failedAt > 0 && now - failedAt < RETRY_AFTER_FAIL_MS) {
            Log.d(TAG, "login: last attempt failed ${(now - failedAt) / 1000}s ago, not retrying yet")
            return false
        }
        val ok = try { login() } catch (t: Throwable) {
            Log.w(TAG, "login threw ${t.javaClass.name}: ${t.message}"); false
        }
        if (ok) { loggedInAt = System.currentTimeMillis(); failedAt = 0L } else failedAt = System.currentTimeMillis()
        return ok
    }

    private fun login(): Boolean {
        val page = request("$BASE/login", "GET", null, "")
        val doc = org.jsoup.Jsoup.parse(page.text)
        Log.d(TAG, "login: GET /login -> ${page.code} (${page.text.length}B) title='${doc.title().take(80)}' forms=${doc.select("form").size}")
        val form = doc.select("form").firstOrNull { it.selectFirst("input[type=password]") != null }
        if (form == null) {
            Log.w(TAG, "login: no form with a password field (browser check in the way?) body='${doc.body()?.text()?.take(200)}'")
            return false
        }
        val action = form.attr("action").ifBlank { "/login" }
        val target = if (action.startsWith("http")) action else BASE + (if (action.startsWith("/")) action else "/$action")
        val fields = LinkedHashMap<String, String>()
        var userField: String? = null
        var passField: String? = null
        for (inp in form.select("input")) {
            val name = inp.attr("name")
            if (name.isEmpty()) continue
            val type = inp.attr("type").lowercase()
            when {
                type == "password" -> passField = name
                type == "email" || (userField == null && (type == "text" || type.isEmpty()) &&
                    Regex("mail|user|login|name", RegexOption.IGNORE_CASE).containsMatchIn(name)) -> userField = name
                type == "checkbox" -> fields[name] = inp.attr("value").ifEmpty { "on" } // "stay logged in"
                else -> fields[name] = inp.attr("value")
            }
        }
        // A <button name=..> can carry the submit value too.
        form.select("button[name]").forEach { b -> fields.putIfAbsent(b.attr("name"), b.attr("value")) }
        Log.d(TAG, "login: form action=$target userField=$userField passField=$passField other=${fields.keys}")
        if (userField == null || passField == null) return false
        fields[userField!!] = decode(ACCOUNT_B64)
        fields[passField!!] = decode(PASSWORD_B64)
        val body = fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val post = request(target, "POST", body, "$BASE/login")
        val postDoc = org.jsoup.Jsoup.parse(post.text)
        val errText = postDoc.select(".messageAlert, .alert, .error, .errorMessage").text().take(150)
        // Success: a redirect away from /login, or a page that offers "logout".
        val ok = (post.code in 300..399 && !post.location.contains("/login")) ||
            (post.code in 200..299 && Regex("logout|abmelden", RegexOption.IGNORE_CASE).containsMatchIn(post.text))
        Log.d(TAG, "login: POST -> ${post.code} location=${post.location} ok=$ok cookies=${cookieNames()}" +
            (if (errText.isNotEmpty()) " message='$errText'" else ""))
        return ok
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** One request without following redirects (the login's 302 and its cookies stay visible). */
    fun request(url: String, method: String, body: String?, referer: String): Resp {
        var conn: HttpURLConnection? = null
        try {
            conn = openDohConnection(url).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = false
                requestMethod = method
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                if (referer.isNotEmpty()) {
                    setRequestProperty("Referer", referer)
                    setRequestProperty("Origin", BASE)
                }
                val c = cookieHeader()
                if (c.isNotEmpty()) setRequestProperty("Cookie", c)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            capture(conn)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resp(code, text, url, conn.getHeaderField("Location") ?: "")
        } catch (t: Throwable) {
            Log.w(TAG, "request $method $url threw ${t.javaClass.name}: ${t.message}")
            return Resp(0, "", url, "")
        } finally {
            conn?.disconnect()
        }
    }
}
