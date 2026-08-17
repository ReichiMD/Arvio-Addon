package com.reichi.arflioaddon.kinoger

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-HTTPS resolver + connection helper that bypasses DNS-level blocking.
 *
 * HttpURLConnection resolves hostnames via the SYSTEM DNS. On some mobile networks (German
 * ISPs) the system DNS returns a filtered/sinkholed IP for streaming sites, so the TLS
 * handshake breaks with "Unable to parse TLS packet header" and the scraper finds nothing.
 * Cloudstream3 avoids this because it uses OkHttp with a custom DNS / DoH.
 *
 * We resolve the hostname via Cloudflare DoH (https://1.1.1.1/dns-query, reached by IP so it
 * is itself never blocked), cache the result for the TTL, then connect to the resolved IP with
 * the correct SNI + Host header + hostname verification against the ORIGINAL hostname. Falls
 * back to a plain system-DNS connection if DoH is unreachable or SNI setup fails, so we are
 * never worse than the plain HttpURLConnection.
 */
internal object DohResolver {
    private const val TAG = "ArvioAddon[DohResolver]"
    private const val DOH_URL = "https://1.1.1.1/dns-query"
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>() // host -> (ip, expiresAtMs)

    private fun now() = System.currentTimeMillis()

    fun resolve(host: String): String? {
        cache[host]?.let { (ip, exp) -> if (now() < exp && ip.isNotEmpty()) return ip }
        return try {
            val q = "$DOH_URL?name=${URLEncoder.encode(host, "UTF-8")}&type=A"
            val c = (URL(q).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/dns-json")
                setRequestProperty("User-Agent", "ArvioAddon-Doh/1.0")
            }
            c.connect()
            val code = c.responseCode
            val text = if (code in 200..299) c.inputStream?.bufferedReader()?.use { it.readText() } else null
            c.disconnect()
            if (text.isNullOrEmpty()) {
                Log.d(TAG, "resolve: DoH lookup for $host -> HTTP $code (no body), using system DNS")
                return null
            }
            val answers = JSONObject(text).optJSONArray("Answer")
            var ip: String? = null
            var ttl = 300
            if (answers != null) {
                for (i in 0 until answers.length()) {
                    val a = answers.optJSONObject(i) ?: continue
                    if (a.optInt("type") == 1) {
                        val d = a.optString("data")
                        if (d.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) {
                            ip = d
                            ttl = a.optInt("TTL", 300).coerceIn(60, 3600)
                            break
                        }
                    }
                }
            }
            if (ip != null) {
                cache[host] = ip to (now() + ttl * 1000L)
                Log.d(TAG, "resolve: $host -> $ip (ttl ${ttl}s)")
            } else {
                Log.d(TAG, "resolve: no A record for $host, using system DNS")
            }
            ip
        } catch (t: Throwable) {
            Log.d(TAG, "resolve: DoH failed for $host (${t.javaClass.name}: ${t.message}), using system DNS")
            null
        }
    }
}

/**
 * Hosts that are known to be DNS-blocked on some mobile networks (German ISPs) and therefore
 * MUST be resolved via DoH. Everything else (TMDB, hoster embeds, ...) uses the plain system
 * DNS connection, because (a) those are generally not blocked and (b) the custom SNI socket
 * factory breaks some CDNs (e.g. TMDB on CloudFront returns SSLV3_ALERT_HANDSHAKE_FAILURE).
 * Add a host here if a future hoster turns out to be DNS-blocked on mobile too.
 */
private val DOH_HOSTS = setOf("kinoger.com", "filmpalast.to", "serienstream.to")

private fun shouldUseDoh(host: String): Boolean {
    val h = host.lowercase()
    return DOH_HOSTS.any { h == it || h.endsWith(".$it") }
}

/**
 * Opens an HttpURLConnection for [url]. If the host is a known DNS-blocked streaming site,
 * resolves it via DoH and connects to the resolved IP with proper SNI / Host header /
 * hostname verification (bypasses DNS-level blocking). Otherwise uses a plain system-DNS
 * connection (works for non-blocked hosts and avoids breaking CDNs that reject our SNI socket).
 */
internal fun openDohConnection(url: String): HttpURLConnection {
    val u = URL(url)
    val host = u.host
    if (!shouldUseDoh(host)) return u.openConnection() as HttpURLConnection // plain system DNS
    val ip = DohResolver.resolve(host)
    if (ip == null) return u.openConnection() as HttpURLConnection // fallback: system DNS
    val portPart = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
    val ipUrl = URL("${u.protocol}://$ip$portPart${u.file}")
    val conn = ipUrl.openConnection() as HttpURLConnection
    conn.setRequestProperty("Host", host)
    if (conn is HttpsURLConnection && android.os.Build.VERSION.SDK_INT >= 24) {
        try {
            conn.sslSocketFactory = SniSocketFactory(host)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        } catch (t: Throwable) {
            Log.d("ArvioAddon[DohResolver]", "openDohConnection: SNI setup failed for $host (${t.javaClass.name})")
        }
    }
    return conn
}

/** SSLSocketFactory that pins the SNI server name to the original hostname when connecting by IP.
 *  HttpsURLConnection uses the LAYERED createSocket(plainSocket, host, port, autoClose) after it
 *  has already opened a plain TCP socket to the (resolved) IP. The naive approach of setting
 *  sslParameters.serverNames AFTER delegate.createSocket(...) is too late — the handshake has
 *  already started, so SNI is never sent and Cloudflare returns SSLV3_ALERT_HANDSHAKE_FAILURE.
 *  Fix: pass a null host to the delegate so it does NOT auto-start the handshake (and does not
 *  set its own SNI from the IP), then set SNI to the real hostname and call startHandshake()
 *  ourselves. This is the canonical SNI-pinning pattern for java.net HTTPS. */
private class SniSocketFactory(private val sniHost: String) : SSLSocketFactory() {
    private val delegate = javax.net.ssl.SSLContext.getDefault().socketFactory
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = delegate.createSocket()
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        // host is the IP here (the URL host). null host -> delegate does NOT start the handshake
        // and does NOT set SNI; we set the real SNI then start the handshake ourselves.
        val ssl = delegate.createSocket(s, null, port, autoClose) as SSLSocket
        try {
            val p = ssl.sslParameters
            p.serverNames = listOf(SNIHostName(sniHost))
            ssl.sslParameters = p
        } catch (_: Throwable) {
        }
        Log.d("ArvioAddon[DohResolver]", "SniSocketFactory: starting handshake with SNI=$sniHost")
        ssl.startHandshake()
        return ssl
    }
}
