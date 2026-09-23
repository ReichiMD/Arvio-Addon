package com.reichi.arflioaddon.vavoo

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny redirect server on 127.0.0.1, living inside the ARVIO process.
 *
 * Why: ARVIO's isPendingDebridStream() (AutoPlaySourcePlanner.kt) blocks any stream whose URL
 * contains words like "caching" or "queued" ("Dieser Debrid-Torrent wird noch heruntergeladen").
 * VOE's CDN hosts are literally called "ugc-cdn-caching-....cloudwindow-route.com", so every VOE
 * link was blocked before it could play. The plugin cannot rename a CDN host, so for such URLs it
 * hands ARVIO "http://127.0.0.1:<port>/r/<id>.m3u8" instead; this server answers with a 302 to the
 * real URL and the player follows it. HLS relative URIs are resolved against the final
 * (redirected) URL, so variant playlists and segments load straight from the CDN.
 *
 * Remove once ARVIO stops scanning the URL (PR planned, Buero docs/themen/61).
 */
object LocalRedirect {
    private const val TAG = "LocalRedirect"

    // Same words ARVIO checks (lower-case); only URLs containing one of them are wrapped.
    private val BLOCKED_WORDS = arrayOf(
        "torrent being downloaded", "being downloaded", "still downloading", "queued", "not cached",
        "uncached", "cache pending", "caching", "processing torrent", "download in progress"
    )
    private const val MAX_ENTRIES = 200

    private val targets = java.util.LinkedHashMap<String, String>()
    private var server: ServerSocket? = null
    private var counter = 0

    fun needsWrap(url: String): Boolean {
        val lower = url.lowercase()
        for (w in BLOCKED_WORDS) if (lower.contains(w)) return true
        return false
    }

    /** Returns a local URL that redirects to [target], or [target] itself if the server fails. */
    @Synchronized
    fun wrap(target: String, isM3u8: Boolean): String {
        return try {
            val port = ensureServer()
            counter++
            val id = "v" + counter + "x" + (System.nanoTime() and 0xffffff).toString(16)
            targets[id] = target
            while (targets.size > MAX_ENTRIES) targets.remove(targets.keys.iterator().next())
            val local = "http://127.0.0.1:" + port + "/r/" + id + (if (isM3u8) ".m3u8" else ".mp4")
            DebugLog.t(TAG, "wrap: $local -> $target")
            local
        } catch (t: Throwable) {
            DebugLog.w(TAG, "wrap: server unavailable (${t.javaClass.name}: ${t.message}) -> unwrapped")
            target
        }
    }

    @Synchronized
    private fun lookup(id: String): String? = targets[id]

    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        val t = Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread({ handle(c) }, "ArvioAddon-redirect-conn").apply { isDaemon = true }.start()
                } catch (e: Throwable) {
                    if (s.isClosed) break
                }
            }
        }, "ArvioAddon-redirect")
        t.isDaemon = true
        t.start()
        DebugLog.t(TAG, "server started on 127.0.0.1:${s.localPort}")
        return s.localPort
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 10_000
            val reader = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            // "GET /r/<id>.m3u8 HTTP/1.1"
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            val id = path.substringAfter("/r/", "").substringBefore(".").substringBefore("?")
            val target = if (id.isNotEmpty()) lookup(id) else null
            val out = c.getOutputStream()
            val response = if (target != null) {
                "HTTP/1.1 302 Found\r\nLocation: " + target + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            } else {
                DebugLog.w(TAG, "unknown id in '$requestLine'")
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            out.write(response.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        } catch (t: Throwable) {
            DebugLog.w(TAG, "handle: ${t.javaClass.name}: ${t.message}")
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}
