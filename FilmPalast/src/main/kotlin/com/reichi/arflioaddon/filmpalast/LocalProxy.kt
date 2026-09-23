package com.reichi.arflioaddon.filmpalast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Tiny pass-through proxy on 127.0.0.1, living inside the ARVIO process.
 *
 * Why (two ARVIO problems the plugin cannot fix from outside):
 *  1. ARVIO's isPendingDebridStream() (AutoPlaySourcePlanner.kt) blocks any stream whose URL
 *     contains words like "caching" or "queued" ("Dieser Debrid-Torrent wird noch heruntergeladen").
 *     VOE's CDN hosts are literally called "ugc-cdn-caching-....cloudwindow-route.com".
 *  2. The headers of an ExtractorLink (Referer) never reach ARVIO's player when the source is picked
 *     on the details page: the player is opened with the bare URL only (PlayerViewModel builds a new
 *     StreamSource from the navigation argument, without behaviorHints). The player then asks the CDN
 *     with its own Windows-Chrome User-Agent and no Referer, and VOE refused it (Buero docs/themen/61).
 *
 * So the plugin hands ARVIO "http://127.0.0.1:<port>/p/<session>/<hex url>/<name>" instead. This
 * server fetches the real URL itself - with the Referer and the same User-Agent the plugin used to
 * load the embed page - and streams the answer back:
 *  - MP4: the player's Range header is passed on, 206/Content-Range come back unchanged.
 *  - HLS: playlists are rewritten so every variant playlist, segment, key and init segment is
 *    fetched through this server as well (not just the first file).
 * If the CDN refuses the request with our Referer (4xx), it is retried once without Referer.
 * The first request of every stream also logs a comparison (with / without Referer / player UA),
 * because the release build of ARVIO does not log the player's HTTP status.
 *
 * The URL is carried hex-encoded in the path, so nothing grows with the number of segments; only
 * the header set per stream ("session") is kept, at most MAX_SESSIONS of them.
 */
object LocalProxy {
    private const val TAG = "LocalProxy"

    // Same words ARVIO checks (lower-case). URLs containing one of them must be proxied.
    private val BLOCKED_WORDS = arrayOf(
        "torrent being downloaded", "being downloaded", "still downloading", "queued", "not cached",
        "uncached", "cache pending", "caching", "processing torrent", "download in progress"
    )
    private const val MAX_SESSIONS = 100
    private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    private const val PLAYER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private class Session(val headers: Map<String, String>) {
        @Volatile var probed = false
        @Volatile var dropReferer = false
    }

    private val sessions = java.util.LinkedHashMap<String, Session>()
    private var server: ServerSocket? = null
    private var counter = 0

    fun needsWrap(url: String): Boolean {
        val lower = url.lowercase()
        for (w in BLOCKED_WORDS) if (lower.contains(w)) return true
        return false
    }

    /**
     * Returns a local URL that serves [target] fetched with [headers], or [target] itself if the
     * server cannot start (then the player at least gets the plain URL as before).
     */
    @Synchronized
    fun wrap(target: String, headers: Map<String, String>, isM3u8: Boolean): String {
        return try {
            val port = ensureServer()
            counter++
            val sid = "s" + counter + "x" + (System.nanoTime() and 0xffffff).toString(16)
            sessions[sid] = Session(headers)
            while (sessions.size > MAX_SESSIONS) sessions.remove(sessions.keys.iterator().next())
            // Fixed file name for the URL ARVIO sees: it must not contain one of the blocked words.
            val local = localUrl(port, sid, target, if (isM3u8) "master.m3u8" else "video.mp4")
            DebugLog.t(TAG, "wrap: $local -> $target (headers: ${headers.keys})")
            local
        } catch (t: Throwable) {
            DebugLog.w(TAG, "wrap: server unavailable (${t.javaClass.name}: ${t.message}) -> unwrapped")
            target
        }
    }

    @Synchronized
    private fun session(sid: String): Session? = sessions[sid]

    private fun localUrl(port: Int, sid: String, target: String, name: String): String =
        "http://127.0.0.1:" + port + "/p/" + sid + "/" + hexEncode(target) + "/" + name

    private fun ensureServer(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        val t = Thread({
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    Thread({ handle(c) }, "ArvioAddon-proxy-conn").apply { isDaemon = true }.start()
                } catch (e: Throwable) {
                    if (s.isClosed) break
                }
            }
        }, "ArvioAddon-proxy")
        t.isDaemon = true
        t.start()
        DebugLog.t(TAG, "server started on 127.0.0.1:${s.localPort}")
        return s.localPort
    }

    // --------------------------------------------------------------------------------------------
    // Request handling
    // --------------------------------------------------------------------------------------------

    private fun handle(c: Socket) {
        var conn: HttpURLConnection? = null
        try {
            c.soTimeout = 15_000
            val input = c.getInputStream()
            val requestLine = readLine(input) ?: return
            var range: String? = null
            val seen = StringBuilder()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val name = h.substringBefore(":").trim().lowercase()
                if (name == "range") range = h.substringAfter(":").trim()
                if (name == "user-agent" || name == "referer" || name == "range") seen.append(" | ").append(h)
            }
            // "GET /p/<sid>/<hex>/<name> HTTP/1.1"
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: ""
            val segs = path.substringBefore("?").split("/")
            val sid = segs.getOrNull(2) ?: ""
            val target = if (segs.getOrNull(1) == "p") hexDecode(segs.getOrNull(3) ?: "") else null
            val sess = if (sid.isNotEmpty()) session(sid) else null
            val out = c.getOutputStream()
            if (target == null || sess == null) {
                DebugLog.w(TAG, "unknown stream in '$requestLine'")
                writeHead(out, 404, "Not Found", listOf("Content-Length" to "0"))
                return
            }
            val isTop = segs.getOrNull(4) == "master.m3u8" || segs.getOrNull(4) == "video.mp4"
            if (isTop) DebugLog.t(TAG, "request: $requestLine$seen")
            if (isTop && !sess.probed) {
                sess.probed = true
                Thread({ probe(target, sess.headers) }, "ArvioAddon-proxy-probe").apply { isDaemon = true }.start()
            }

            conn = open(target, sess.headers, method, range, !sess.dropReferer)
            var code = conn.responseCode
            if (code in 400..499 && !sess.dropReferer && sess.headers.containsKey("Referer")) {
                DebugLog.w(TAG, "upstream $code with Referer -> retry without Referer: ${target.take(120)}")
                conn.disconnect()
                conn = open(target, sess.headers, method, range, false)
                code = conn.responseCode
                if (code in 200..299) sess.dropReferer = true
            }
            if (isTop || code !in 200..299) {
                DebugLog.t(TAG, "upstream: $code ${conn.contentType} len=${conn.getHeaderField("Content-Length")} for ${target.take(120)}")
            }

            val finalUrl = conn.url
            val contentType = conn.contentType ?: ""
            val looksLikePlaylist = contentType.lowercase().contains("mpegurl") ||
                finalUrl.path.lowercase().endsWith(".m3u8") || target.substringBefore("?").lowercase().endsWith(".m3u8")
            val body: InputStream? = try {
                if (code >= 400) conn.errorStream else conn.inputStream
            } catch (_: Throwable) { null }

            if (looksLikePlaylist && code in 200..299 && method != "HEAD" && body != null) {
                val raw = readLimited(body, MAX_PLAYLIST_BYTES)
                val text = String(raw, Charsets.UTF_8)
                val bytes = if (text.trimStart('﻿', ' ', '\r', '\n').startsWith("#EXTM3U")) {
                    rewritePlaylist(text, finalUrl, c.localPort, sid).toByteArray(Charsets.UTF_8)
                } else raw
                writeHead(out, 200, "OK", listOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to bytes.size.toString()
                ))
                out.write(bytes)
                out.flush()
                return
            }

            val head = ArrayList<Pair<String, String>>()
            for (h in arrayOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                conn.getHeaderField(h)?.let { head.add(h to it) }
            }
            writeHead(out, code, conn.responseMessage ?: "OK", head)
            if (method != "HEAD" && body != null) {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        } catch (t: java.net.SocketException) {
            // The player closed the connection (seek, stop, next segment) - normal.
        } catch (t: Throwable) {
            DebugLog.w(TAG, "handle: ${t.javaClass.name}: ${t.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
            try { c.close() } catch (_: Throwable) {}
        }
    }

    private fun open(target: String, headers: Map<String, String>, method: String, range: String?, withReferer: Boolean): HttpURLConnection {
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.requestMethod = if (method == "HEAD") "HEAD" else "GET"
        // Byte-exact pass-through: no transparent gzip, or Content-Length/Range would not match.
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Accept", "*/*")
        for ((k, v) in headers) {
            if (!withReferer && (k.equals("Referer", true) || k.equals("Origin", true))) continue
            conn.setRequestProperty(k, v)
        }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    /**
     * Diagnostics for Buero docs/themen/61: does the CDN really refuse a missing Referer, or the
     * player's User-Agent? One tiny request per variant (Range 0-1), status codes into the log.
     */
    private fun probe(target: String, headers: Map<String, String>) {
        val variants = listOf(
            "plugin-UA + Referer" to headers,
            "plugin-UA, ohne Referer" to headers.filterKeys { !it.equals("Referer", true) && !it.equals("Origin", true) },
            "Player-UA, ohne Referer (wie ARVIO)" to mapOf("User-Agent" to PLAYER_UA)
        )
        val sb = StringBuilder("probe ${target.take(100)}:")
        for ((label, h) in variants) {
            var conn: HttpURLConnection? = null
            val code = try {
                conn = open(target, h, "GET", "bytes=0-1", true)
                conn.responseCode
            } catch (t: Throwable) {
                -1
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
            sb.append(" [").append(label).append(" -> ").append(code).append("]")
        }
        DebugLog.t(TAG, sb.toString())
    }

    // --------------------------------------------------------------------------------------------
    // HLS playlist rewriting
    // --------------------------------------------------------------------------------------------

    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    private fun rewritePlaylist(text: String, base: URL, port: Int, sid: String): String {
        val sb = StringBuilder(text.length * 2)
        for (line in text.split("\n")) {
            val t = line.trimEnd('\r').trim()
            val rewritten = when {
                t.isEmpty() -> t
                t.startsWith("#") -> URI_ATTR.replace(t) { m ->
                    val p = proxied(m.groupValues[1], base, port, sid)
                    if (p == null) m.value else "URI=\"" + p + "\""
                }
                else -> proxied(t, base, port, sid) ?: t
            }
            sb.append(rewritten).append('\n')
        }
        return sb.toString()
    }

    private fun proxied(ref: String, base: URL, port: Int, sid: String): String? {
        val abs = try { URL(base, ref).toString() } catch (_: Throwable) { return null }
        if (!abs.startsWith("http://") && !abs.startsWith("https://")) return null
        return localUrl(port, sid, abs, fileName(abs))
    }

    /** Last path segment of [url] (keeps the extension for the player's format detection). */
    private fun fileName(url: String): String {
        val last = url.substringBefore("?").substringBefore("#").substringAfterLast("/")
        val clean = StringBuilder()
        for (ch in last) if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') clean.append(ch)
        return if (clean.isEmpty()) "file" else clean.toString().take(80)
    }

    // --------------------------------------------------------------------------------------------
    // Small helpers (plain java.io, no okhttp)
    // --------------------------------------------------------------------------------------------

    private fun writeHead(out: OutputStream, code: Int, message: String, headers: List<Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(message).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Reads one CRLF/LF-terminated line byte by byte (the body must stay unread). */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16 * 1024) break
        }
        return sb.toString()
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (bos.size() < max) {
            val n = input.read(buf)
            if (n < 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private const val HEX = "0123456789abcdef"

    private fun hexEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun hexDecode(s: String): String? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = HEX.indexOf(s[2 * i])
            val lo = HEX.indexOf(s[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
