package com.reichi.arflioaddon.filmpalast

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny local HTTP server that serves the DebugLog trace so the user can read it in a
 * browser on the device (or via curl over ADB/WLAN) without needing READ_LOGS or root.
 *
 *   http://localhost:8420/            -> latest trace, auto-refresh every 3s
 *   http://localhost:8420/clear      -> clears the trace (POST or GET)
 *
 * No external library: plain blocking ServerSocket on a background thread. The plugin
 * runs inside the ARVIO process, so we bind to loopback only (no network permission
 * needed beyond INTERNET, which ARVIO already declares).
 */
object DebugServer {
    private const val PORT = 8420
    private const val TAG = "DebugServer"
    private var serverThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        // Plain java.lang.Thread — NOT kotlin.concurrent.thread (ARVIO's release APK may
        // shrink unused kotlin-stdlib classes; a NoClassDefFoundError here would abort
        // plugin.load()). See DebugLog.kt.
        val t = Thread {
            try {
                // Bind explicitly to loopback (127.0.0.1). Binding to the wildcard
                // address can be blocked by Android's network security config on some
                // devices; loopback is always allowed and is all we need since the user
                // opens http://localhost:8420 on the same device.
                val server = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                DebugLog.t(TAG, "listening on http://localhost:$PORT")
                while (running) {
                    val client = try { server.accept() } catch (e: Exception) {
                        if (running) DebugLog.w(TAG, "accept failed: ${e.message}")
                        continue
                    }
                    handle(client)
                }
                try { server.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                DebugLog.e(TAG, "could not start server on port $PORT", e)
                running = false
            }
        }
        t.isDaemon = true
        t.name = "ArvioAddon-DebugServer"
        t.start()
        serverThread = t
    }

    private fun handle(client: Socket) {
        val t = Thread {
            try {
                processRequest(client)
            } catch (e: Exception) {
                Log.w("ArvioAddon[$TAG]", "request failed: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun processRequest(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = input.readLine() ?: return
        // drain headers
        var line: String?
        do { line = input.readLine() } while (!line.isNullOrEmpty())
        val out: OutputStream = client.getOutputStream()
        val (contentType, body) = when {
            requestLine.startsWith("GET /clear") || requestLine.startsWith("POST /clear") -> {
                DebugLog.clear()
                DebugLog.t(TAG, "trace cleared by user")
                "text/html; charset=utf-8" to htmlPage("Trace cleared. <a href=\"/\">back</a>", autoRefresh = false)
            }
            requestLine.startsWith("GET /raw") -> {
                "text/plain; charset=utf-8" to
                    (DebugLog.snapshot().joinToString("\n") { DebugLog.format(it) }).ifEmpty { "(empty trace)" }
            }
            else -> "text/html; charset=utf-8" to htmlPage(renderTrace(), autoRefresh = true)
        }
        // Plain java String.getBytes(UTF_8) — NOT kotlin.text.toByteArray (same risk as
        // kotlin/io/FilesKt: ARVIO's shrunk classloader may not expose the stdlib class).
        val utf8 = java.nio.charset.StandardCharsets.UTF_8
        val bytes = body.getBytes(utf8)
        out.write("HTTP/1.1 200 OK\r\n".getBytes(utf8))
        out.write("Content-Type: $contentType\r\n".getBytes(utf8))
        out.write("Content-Length: ${bytes.size}\r\n".getBytes(utf8))
        out.write("Connection: close\r\n\r\n".getBytes(utf8))
        out.write(bytes)
        out.flush()
    }

    private fun renderTrace(): String {
        val entries = DebugLog.snapshot()
        if (entries.isEmpty()) {
            return "<p style=\"color:#888\">No trace entries yet. Trigger a source search in ARVIO " +
                "(e.g. open a movie/series and search for sources) to produce a trace.</p>"
        }
        val sb = StringBuilder()
        sb.append("<div style=\"font-family:monospace;font-size:13px;line-height:1.4\">")
        entries.forEach { e ->
            val color = when (e.level) {
                DebugLog.Level.ERROR -> "#ff6b6b"
                DebugLog.Level.WARN -> "#ffd166"
                DebugLog.Level.TRACE -> "#e0e0e0"
            }
            val esc = DebugLog.format(e)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("<div style=\"color:$color;white-space:pre-wrap\">$esc</div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun htmlPage(body: String, autoRefresh: Boolean): String {
        val refresh = if (autoRefresh) "<meta http-equiv=\"refresh\" content=\"3\">" else ""
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            $refresh
            <title>Arvio Addon Debug</title>
            <style>
                body { background:#1e1e1e; color:#e0e0e0; font-family:monospace; margin:12px; }
                a { color:#6cb6ff; }
                .bar a { margin-right:14px; text-decoration:none; padding:6px 10px; border:1px solid #444; border-radius:6px; }
            </style></head>
            <body>
            <div class="bar" style="margin-bottom:12px">
                <a href="/">refresh</a>
                <a href="/raw">raw text</a>
                <a href="/clear">clear log</a>
            </div>
            <div style="color:#888;font-size:12px;margin-bottom:8px">Filmpalast self-diagnosis trace. Newest at bottom. Auto-refresh 3s.</div>
            $body
            </body></html>""".trimIndent()
    }

}
