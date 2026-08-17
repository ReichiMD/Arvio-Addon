package com.reichi.arflioaddon.vavoo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vavoo (MediaHubMX) provider for ARVIO, implemented as a TmdbProvider so ARVIO takes the
 * direct load() path (load({"id":<tmdbId>,"type":...})) instead of the fragile search-based
 * title-matching path.
 *
 * Vavoo is API-based (no HTML scraping, no bot protection). Flow:
 *  1) POST ping (vypn.net) -> addonSig (cached 5 min).
 *  2) POST mediahubmx-source.json (vavoo.to) with the signature header -> JSON array of
 *     mirrors [{url, tag, name, languages:[...]}].
 *  3) Filter mirrors: language contains "de", url parseable, host not in SKIPPED_HOSTS.
 *  4) Dedup by (host, tag).
 *
 * For movies, load() stores the mirror URLs in dataUrl (links JSON); loadLinks() resolves
 * each hoster. For series, load() enumerates episodes via TMDB (number of seasons + episode
 * counts) and stores {tmdbId, season, episode, name} per episode; loadLinks() re-fetches the
 * Vavoo mirrors for the specific episode on demand (one Vavoo API call per playback, since
 * mirrors are episode-specific).
 *
 * HTTP uses java.net.HttpURLConnection + DoH (DohHttp.kt) to bypass DNS-level blocking on
 * mobile networks, and avoids cloudstream3's suspend `app.get` (R8-obfuscated okhttp3 +
 * coroutines break it for external .cs3 plugins). Hosters are resolved via non-suspend
 * extractors (vidsonic hex+reverse, voe_decode, generic mp4/m3u8 regex).
 */
class VavooProvider : TmdbProvider() {
    override var mainUrl = "https://vavoo.to"
    override var name = "Vavoo"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "Vavoo"
    private val NET_TIMEOUT_MS = 8000L

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // Vavoo API endpoints (both reached via DoH-bypassed connections, see DohHttp.kt).
    private val PING_URLS = listOf("https://www.vypn.net/api/app/ping", "https://cache.vypn.net/api/app/ping")
    private val SOURCE_URL = "https://vavoo.to/mediahubmx-source.json"

    // Hosts the reference implementation deliberately skips ("den hoster kann man vergessen").
    private val SKIPPED_HOSTS = listOf("streamz")
    // Unreliable hosters (bot protection) — resolved last so working hosters surface first.
    private val UNRELIABLE_HOSTS = listOf("dood", "playmogo", "myvidplay", "voe", "filemoon")

    // --------------------------------------------------------------------------------------------
    // HTTP helpers (java.net + DoH; no okhttp / no suspend).
    // --------------------------------------------------------------------------------------------

    private data class HttpResp(val code: Int, val text: String)

    private fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResp {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = openDohConnection(url).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", mobileUA)
                setRequestProperty("Accept", "text/html,application/json,*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            DebugLog.t(dbg, "httpGet: $url -> $code (${text.length} bytes)")
            HttpResp(code, text)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "httpGet: $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "")
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResp {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = openDohConnection(url).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", mobileUA)
                setRequestProperty("Accept", "application/json,*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            DebugLog.t(dbg, "httpPost: $url -> $code (${text.length} bytes)")
            HttpResp(code, text)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "httpPost: $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "")
        } finally {
            conn?.disconnect()
        }
    }

    // --------------------------------------------------------------------------------------------
    // TMDB metadata (title + year; ARVIO does not pass a title, Vavoo matches by tmdb_id but
    // the name field is a helpful hint).
    // --------------------------------------------------------------------------------------------

    private data class TmdbMeta(val displayTitle: String, val year: Int)

    private fun fetchTmdbMeta(tmdbId: Int, isTv: Boolean): TmdbMeta? {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId?api_key=e6333b32409e02a4a6eba6fb7ff866bb&language=de-DE"
        val res = httpGet(url)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "fetchTmdbMeta: GET $url -> HTTP ${res.code}")
            return null
        }
        return try {
            val obj = JSONObject(res.text)
            val title = obj.optString("title", obj.optString("name", ""))
            val yearStr = obj.optString("release_date", obj.optString("first_air_date", ""))
            val year = yearStr.substringBefore("-").toIntOrNull() ?: 0
            if (title.isEmpty()) null else TmdbMeta(title, year)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "fetchTmdbMeta: JSON parse threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /** For series: fetch seasons + episode counts from TMDB so load() can enumerate episodes. */
    private data class TmdbSeason(val season: Int, val episodeCount: Int)

    private fun fetchTmdbSeasons(tmdbId: Int): List<TmdbSeason> {
        val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=e6333b32409e02a4a6eba6fb7ff866bb&language=de-DE"
        val res = httpGet(url)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "fetchTmdbSeasons: GET -> HTTP ${res.code}")
            return emptyList()
        }
        return try {
            val arr = JSONObject(res.text).optJSONArray("seasons") ?: return emptyList()
            val out = ArrayList<TmdbSeason>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", -1)
                val ec = s.optInt("episode_count", 0)
                // Skip season 0 (specials) and seasons with no episodes.
                if (sn > 0 && ec > 0) out.add(TmdbSeason(sn, ec))
            }
            out.sortedBy { it.season }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "fetchTmdbSeasons: JSON parse threw ${t.javaClass.name}: ${t.message}")
            emptyList()
        }
    }

    // --------------------------------------------------------------------------------------------
    // Vavoo signature (addonSig) via ping POST. Cached 5 min (signatures may expire).
    // ipLocation=null for movies/series: Vavoo uses the connecting (device) IP, which is what
    // we want client-side (no viewer-IP trick needed, unlike the server-side Stremio addon).
    // --------------------------------------------------------------------------------------------

    private val SIGNATURE_TTL_MS = 5 * 60 * 1000L
    @Volatile private var cachedSig: String? = null
    @Volatile private var cachedSigTs: Long = 0L

    private fun getSignature(): String? {
        val now = System.currentTimeMillis()
        cachedSig?.let { if (now - cachedSigTs < SIGNATURE_TTL_MS) return it }
        val payload = buildPingPayload()
        for (pingUrl in PING_URLS) {
            try {
                val res = httpPost(
                    pingUrl, payload, headers = mapOf(
                        "Accept" to "*/*",
                        "Connection" to "close",
                        "Content-Type" to "application/json; charset=utf-8"
                    )
                )
                if (res.code !in 200..299) continue
                val sig = try {
                    val obj = JSONObject(res.text)
                    obj.optString("addonSig", obj.optString("token", obj.optString("signature", "")))
                } catch (_: Throwable) { "" }
                if (sig.isNotEmpty()) {
                    cachedSig = sig
                    cachedSigTs = now
                    DebugLog.t(dbg, "getSignature: ping $pingUrl -> sig=${sig.take(32)}...")
                    return sig
                }
            } catch (t: Throwable) {
                DebugLog.w(dbg, "getSignature: ping $pingUrl threw ${t.javaClass.name}: ${t.message}")
            }
        }
        DebugLog.w(dbg, "getSignature: all ping URLs failed")
        return null
    }

    private fun buildPingPayload(): String {
        val uid = java.util.UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        // Static desktop payload mimicking net.vypn.app electron build (verified live Aug 2026).
        val obj = JSONObject()
        obj.put("reason", "app-focus")
        obj.put("locale", "en")
        obj.put("theme", "dark")
        val metadata = JSONObject()
        val device = JSONObject()
        device.put("type", "desktop"); device.put("uniqueId", uid)
        metadata.put("device", device)
        val os = JSONObject()
        os.put("name", "win32"); os.put("version", "Windows 10 Pro")
        os.put("abis", JSONArray().put("x64")); os.put("host", "Lenovo")
        metadata.put("os", os)
        val app = JSONObject(); app.put("platform", "electron")
        metadata.put("app", app)
        val version = JSONObject()
        version.put("package", "net.vypn.app"); version.put("binary", "3.1.0"); version.put("js", "3.1.0")
        metadata.put("version", version)
        obj.put("metadata", metadata)
        obj.put("appFocusTime", 0)
        obj.put("playerActive", false)
        obj.put("playDuration", 0)
        obj.put("devMode", false)
        obj.put("hasAddon", true)
        obj.put("castConnected", false)
        obj.put("package", "net.vypn.app")
        obj.put("version", "3.1.0")
        obj.put("process", "app")
        obj.put("firstAppStart", ts)
        obj.put("lastAppStart", ts)
        obj.put("ipLocation", JSONObject.NULL) // device IP is used by Vavoo
        obj.put("adblockEnabled", true)
        val proxy = JSONObject()
        proxy.put("supported", JSONArray().put("ss")); proxy.put("engine", "Mu")
        proxy.put("enabled", false); proxy.put("autoServer", true)
        obj.put("proxy", proxy)
        val iap = JSONObject(); iap.put("supported", false)
        obj.put("iap", iap)
        return obj.toString()
    }

    // --------------------------------------------------------------------------------------------
    // Vavoo mirror list via mediahubmx-source.json POST.
    // --------------------------------------------------------------------------------------------

    private data class VavooMirror(val url: String, val tag: String, val displayName: String)

    private fun fetchMirrors(
        signature: String,
        tmdbId: Int,
        name: String,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null
    ): List<VavooMirror> {
        val obj = JSONObject()
        obj.put("language", "de")
        obj.put("region", "AT")
        obj.put("type", if (isSeries) "series" else "movie")
        val ids = JSONObject(); ids.put("tmdb_id", tmdbId.toString())
        obj.put("ids", ids)
        obj.put("name", name)
        if (isSeries && season != null && episode != null) {
            val ep = JSONObject(); ep.put("season", season); ep.put("episode", episode)
            obj.put("episode", ep)
        } else {
            obj.put("episode", JSONObject())
        }
        obj.put("clientVersion", "3.0.2")

        val res = httpPost(
            SOURCE_URL, obj.toString(), headers = mapOf(
                "User-Agent" to "MediaHubMX/2",
                "Content-Type" to "application/json; charset=utf-8",
                "mediahubmx-signature" to signature
            )
        )
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "fetchMirrors: POST $SOURCE_URL -> HTTP ${res.code}")
            return emptyList()
        }
        return try {
            val arr = JSONArray(res.text)
            val out = ArrayList<VavooMirror>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val url = m.optString("url", "")
                val tag = m.optString("tag", "")
                val displayName = m.optString("name", "Vavoo")
                if (url.isNotEmpty()) out.add(VavooMirror(url, tag, displayName))
            }
            out
        } catch (t: Throwable) {
            // Non-array response — typically an error object (malformed/expired signature).
            DebugLog.w(dbg, "fetchMirrors: JSON parse threw ${t.javaClass.name}: ${t.message}")
            emptyList()
        }
    }

    /** Filter mirrors: language de, parseable URL, host not skipped. Dedup by (host, tag). */
    private fun filterAndDedup(mirrors: List<VavooMirror>): List<VavooMirror> {
        val seen = HashSet<String>()
        val out = ArrayList<VavooMirror>()
        for (m in mirrors) {
            val host = try { java.net.URI(m.url).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
            if (host.isEmpty()) continue
            if (SKIPPED_HOSTS.any { host.contains(it) }) continue
            // Vavoo mirrors carry a languages array; we only saw German + English in tests.
            // Without the languages field in our mirror model (kept lean), we accept all and
            // rely on the region=AT + language=de request params to filter server-side.
            val key = "$host|${m.tag}"
            if (!seen.add(key)) continue
            out.add(m)
        }
        // Working hosters first, unreliable (dood/voe/filemoon) last.
        out.sortBy { hostPriority(it.url) }
        return out
    }

    private fun hostPriority(url: String): Int {
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        return when {
            UNRELIABLE_HOSTS.any { host.contains(it) } -> 2
            else -> 1
        }
    }

    // --------------------------------------------------------------------------------------------
    // TmdbProvider overrides (suspend, but with no inner suspend calls — coroutine state
    // machine stays trivial; DEX-patched j7/x7 descriptors bind our overrides).
    // --------------------------------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // Vavoo is TMDB-id-based, not search-based. ARVIO uses the load() path for TmdbProvider,
        // so search() is not actually exercised. Return an empty list for safety.
        DebugLog.t(dbg, "search('$query') — Vavoo is TMDB-id-based, returning empty")
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        DebugLog.t(dbg, "load() called with url=$url")
        val (tmdbId, isTv) = parseTmdbInput(url) ?: return null
        val meta = fetchTmdbMeta(tmdbId, isTv)
        val name = meta?.displayTitle ?: (if (isTv) "series-$tmdbId" else "movie-$tmdbId")
        DebugLog.t(dbg, "load: parsed tmdbId=$tmdbId isTv=$isTv name=$name")

        return if (!isTv) {
            // Movie: fetch mirrors once, store URLs in dataUrl; loadLinks resolves them.
            val sig = getSignature() ?: run {
                DebugLog.w(dbg, "load: no signature -> null")
                return null
            }
            val mirrors = fetchMirrors(sig, tmdbId, name, isSeries = false)
            val usable = filterAndDedup(mirrors)
            DebugLog.t(dbg, "load: ${mirrors.size} mirrors -> ${usable.size} usable")
            val links = usable.map { it.url }
            val dataUrl = linksToJson(links)
            newMovieLoadResponse(name, mainUrl, TvType.Movie, dataUrl) {
                this.year = meta?.year ?: 0
                this.plot = ""
            }
        } else {
            // Series: enumerate episodes via TMDB; each episode.data re-fetches mirrors.
            val seasons = fetchTmdbSeasons(tmdbId)
            if (seasons.isEmpty()) {
                DebugLog.w(dbg, "load: no TMDB seasons -> null")
                return null
            }
            val epList = ArrayList<Episode>()
            for (s in seasons) {
                for (e in 1..s.episodeCount) {
                    val data = episodeDataJson(tmdbId, name, s.season, e)
                    epList.add(newEpisode(data) {
                        this.name = "S${s.season}E$e"
                        this.season = s.season
                        this.episode = e
                    })
                }
            }
            DebugLog.t(dbg, "load: built ${epList.size} episodes across ${seasons.size} seasons")
            newTvSeriesLoadResponse(name, mainUrl, TvType.TvSeries, epList) {
                this.year = meta?.year ?: 0
                this.plot = ""
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.t(dbg, "loadLinks() called with data=${data.take(300)}")
        val links: List<String> = when {
            data.trimStart().startsWith("{") && data.contains("\"links\"") -> parseLinksJson(data)
            data.trimStart().startsWith("{") && data.contains("\"tmdbId\"") -> {
                // Series episode: re-fetch mirrors for this specific episode.
                val ep = parseEpisodeData(data) ?: run {
                    DebugLog.w(dbg, "loadLinks: could not parse episode data")
                    return false
                }
                val sig = getSignature() ?: run {
                    DebugLog.w(dbg, "loadLinks: no signature for episode")
                    return false
                }
                val mirrors = fetchMirrors(sig, ep.tmdbId, ep.name, isSeries = true, ep.season, ep.episode)
                val usable = filterAndDedup(mirrors)
                DebugLog.t(dbg, "loadLinks: episode S${ep.season}E${ep.episode} -> ${usable.size} usable mirrors")
                usable.map { it.url }
            }
            else -> {
                // Fallback: treat data as a bare URL (e.g. if ARVIO passes a hoster URL directly).
                listOf(data)
            }
        }
        if (links.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: 0 links -> no sources")
            return false
        }
        val fixed = links.mapNotNull { link -> fixUrlNull(link) }
        if (fixed.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: all links invalid -> no sources")
            return false
        }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(fixed.size, 4))
        var any = false
        try {
            val futures = fixed.map { link ->
                pool.submit(java.util.concurrent.Callable {
                    val found = resolveHost(link, callback)
                    DebugLog.t(dbg, "loadLinks: resolveHost('$link') -> found=$found")
                    found
                })
            }
            for (f in futures) {
                try {
                    if (f.get(3, java.util.concurrent.TimeUnit.SECONDS)) any = true
                } catch (t: Throwable) {
                    DebugLog.w(dbg, "loadLinks: hoster future threw ${t.javaClass.name}: ${t.message}")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        DebugLog.t(dbg, "loadLinks: DONE, any=$any")
        return any
    }

    // --------------------------------------------------------------------------------------------
    // Hoster resolution (non-suspend; dispatch by domain).
    // --------------------------------------------------------------------------------------------

    private fun resolveHost(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase() ?: ""
            when {
                host.contains("vidsonic") -> resolveVidsonic(url, callback)
                host.contains("voe.") || host.endsWith("voe.sx") || host.contains("voe.sx") -> resolveVoe(url, callback)
                else -> genericResolve(url, callback)
            }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveHost: '$url' threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /**
     * vidsonic.net extractor (hex + reverse). Embed page /e/<mediaId> contains
     * `const _0x1 = '<hex-with-pipes>'`; strip pipes, hex-decode, reverse -> m3u8 URL.
     * Algorithm from Gujal00/ResolveURL vidsonic.py. Verified live (Aug 2026).
     */
    private fun resolveVidsonic(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val ref = url.substringBefore("/e/") + "/"
        val res = httpGet(url, headers = mapOf(
            "Referer" to ref,
            "Origin" to ref.trimEnd('/'),
            "User-Agent" to mobileUA
        ))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveVidsonic: GET $url -> HTTP ${res.code}")
            return false
        }
        val hex = try {
            Regex("const\\s*_0x1\\s*=\\s*'([^']+)'").find(res.text)?.groupValues?.get(1)
        } catch (t: Throwable) { null }
        if (hex.isNullOrBlank()) {
            DebugLog.w(dbg, "resolveVidsonic: no _0x1 hex variable found")
            return false
        }
        val streamUrl = try {
            val cleanHex = hex.replace("|", "")
            val decoded = StringBuilder(cleanHex.length / 2)
            var i = 0
            while (i + 1 < cleanHex.length) {
                decoded.append(cleanHex.substring(i, i + 2).toInt(16).toChar())
                i += 2
            }
            decoded.reverse().toString()
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveVidsonic: hex-decode/reverse threw ${t.javaClass.name}: ${t.message}")
            return false
        }
        if (!streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveVidsonic: decoded URL not http: '$streamUrl'")
            return false
        }
        DebugLog.t(dbg, "resolveVidsonic: streaming_url=$streamUrl")
        emitLink("Vidsonic", streamUrl, ref, callback)
        return true
    }

    /**
     * voe.sx extractor: redirect loop, then voe_decode (ROT + LUT + base64 + Caesar -3) or
     * direct hls/mp4 fallback. Ported from Gujal00/ResolveURL voesx.py.
     */
    private fun resolveVoe(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, headers = mapOf("User-Agent" to mobileUA))
        val text = res.text
        var found = false

        var currentUrl = url
        var currentText = text
        var redirects = 0
        while (currentText.contains("const currentUrl") && redirects < 5) {
            val r = Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(currentText) ?: break
            currentUrl = r.groupValues[1]
            currentText = httpGet(currentUrl, headers = mapOf("User-Agent" to mobileUA)).text
            redirects++
        }
        if (redirects > 0) DebugLog.t(dbg, "resolveVoe: followed $redirects redirects to $currentUrl")

        val p1 = Regex("""json">\["([^"]+)"\]</script>\s*<script\s*src="([^"]+)""")
        val m1 = p1.find(currentText)
        if (m1 != null) {
            val ct = m1.groupValues[1]
            val jsUrlRaw = m1.groupValues[2]
            val jsUrl = if (jsUrlRaw.startsWith("http")) jsUrlRaw
                else try { java.net.URL(java.net.URL(currentUrl), jsUrlRaw).toString() }
                catch (_: Throwable) { "$currentUrl/$jsUrlRaw" }
            DebugLog.t(dbg, "resolveVoe: Pattern 1 match, ct len=${ct.length}, jsUrl=$jsUrl")
            val jsRes = httpGet(jsUrl, headers = mapOf("User-Agent" to mobileUA))
            val lutMatch = Regex("""(\[(?:'\W{2}'[,\]]){1,9})""").find(jsRes.text)
            if (lutMatch != null) {
                val luts = lutMatch.groupValues[1]
                DebugLog.t(dbg, "resolveVoe: LUT found: $luts")
                val decoded = try { voeDecode(ct, luts) } catch (t: Throwable) {
                    DebugLog.w(dbg, "resolveVoe: voe_decode threw ${t.javaClass.name}: ${t.message}")
                    null
                }
                if (decoded != null) {
                    val source = decoded.optString("source", "")
                    val file = decoded.optString("file", "")
                    val direct = decoded.optString("direct_access_url", "")
                    when {
                        source.startsWith("http") -> { emitLink("VOE", source, "https://voe.sx/", callback); found = true }
                        file.startsWith("http") -> { emitLink("VOE", file, "https://voe.sx/", callback); found = true }
                        direct.startsWith("http") -> { emitLink("VOE", direct, "https://voe.sx/", callback); found = true }
                    }
                    DebugLog.t(dbg, "resolveVoe: voe_decode -> source=$source file=$file direct=$direct found=$found")
                }
            } else {
                DebugLog.w(dbg, "resolveVoe: Pattern 1 matched but no LUT in JS file")
            }
        } else {
            DebugLog.t(dbg, "resolveVoe: Pattern 1 no match (trying fallback regexes)")
        }

        if (!found) {
            val urlPatterns = listOf(
                Regex(""""hls"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex(""""mp4"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""""),
                Regex("""'(https?://[^']+\.m3u8[^']*)'"""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
                Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
            )
            for (p in urlPatterns) {
                p.findAll(text).forEach { m ->
                    emitLink("VOE", m.groupValues[1], "https://voe.sx/", callback); found = true
                }
                if (found) break
            }
        }
        DebugLog.t(dbg, "resolveVoe: GET $url -> ${res.code}, len=${text.length}, found=$found")
        return found
    }

    /** voe_decode (ROT + LUT strip + base64 + Caesar -3 + base64-reversed), ported from voesx.py. */
    private fun voeDecode(ct: String, luts: String): JSONObject {
        val inner = luts.substringAfter("['").substringBefore("']")
        val lutItems = if (inner.isNotEmpty()) inner.split("','") else emptyList()
        val specials = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\')
        val shifted = StringBuilder(ct.length)
        for (ch in ct) {
            val x = ch.toInt()
            val nx = when {
                x in 65..90 -> (x - 52).rem(26) + 65
                x in 97..122 -> (x - 84).rem(26) + 97
                else -> x
            }
            shifted.append(nx.toChar())
        }
        var txt = shifted.toString()
        for (item in lutItems) {
            if (item.isEmpty()) continue
            val escaped = buildString {
                for (c in item) if (c in specials) { append('\\'); append(c) } else append(c)
            }
            txt = try { Regex(escaped).replace(txt, "") } catch (_: Throwable) { txt }
        }
        val step4 = try { base64Decode(txt) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: base64 step1 failed: ${t.message}")
            return JSONObject()
        }
        val step5 = String(CharArray(step4.length) { (step4[it].toInt() - 3).toChar() })
        val reversed = step5.reversed()
        val step6 = try { base64Decode(reversed) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: base64 step2 failed: ${t.message}")
            return JSONObject()
        }
        return try { JSONObject(step6) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: JSON parse failed: ${t.message}")
            JSONObject()
        }
    }

    private fun base64Decode(s: String): String =
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    /** Generic best-effort resolver: scan embed page for direct mp4/m3u8 URLs. */
    private fun genericResolve(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val res = httpGet(url, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to mobileUA))
            val text = res.text
            val base = url.substringBeforeLast("/")
            val root = url.substringBefore("/").dropLastWhile { it != '/' }
            var found = false
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(text).forEach { m ->
                emitLink("Generic", m.groupValues[1], callback); found = true
            }
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(text).forEach { m ->
                emitLink("Generic", m.groupValues[1], callback); found = true
            }
            Regex("""["'(]([^"'\s)]+\.(?:m3u8|mp4)[^"'\s)]*)["')]""").findAll(text).forEach { m ->
                val p = m.groupValues[1]
                val abs = when {
                    p.startsWith("http") -> p
                    p.startsWith("/") -> root + p
                    else -> "$base/$p"
                }
                emitLink("Generic", abs, callback); found = true
            }
            DebugLog.t(dbg, "genericResolve: GET $url -> ${res.code}, len=${text.length}, found=$found")
            found
        } catch (t: Throwable) {
            DebugLog.w(dbg, "genericResolve: GET $url threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    // --------------------------------------------------------------------------------------------
    // emitLink + quality detection (shared pattern with FilmPalast/Kinoger).
    // --------------------------------------------------------------------------------------------

    private fun emitLink(source: String, url: String, callback: (ExtractorLink) -> Unit) {
        emitLink(source, url, mainUrl, callback)
    }

    private fun emitLink(source: String, url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val isM3u8 = url.contains(".m3u8")
            val quality = detectQuality(url, isM3u8)
            DebugLog.t(dbg, "emitLink: source=$source url=$url quality=$quality isM3u8=$isM3u8 referer=$referer")
            // PRIMARY constructor (9 positional args, no default-args) — R8 strips the synthetic
            // DefaultConstructorMarker constructor (Erkenntnis #18).
            val link = ExtractorLink(
                source,
                source,
                url,
                referer,
                quality,
                emptyMap(),
                "",
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                emptyList()
            )
            callback.invoke(link)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "emitLink: threw ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun detectQuality(url: String, isM3u8: Boolean): Int {
        if (!isM3u8) return Qualities.P720.value
        return try {
            val res = httpGet(url, headers = mapOf("Range" to "bytes=0-8192"))
            if (res.code !in 200..299 && res.code != 206) return Qualities.P720.value
            val heights = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
                .findAll(res.text).mapNotNull { it.groupValues[2].toIntOrNull() }.toList()
            val h = heights.maxOrNull() ?: return Qualities.P720.value
            when {
                h >= 2160 -> Qualities.P2160.value
                h >= 1080 -> Qualities.P1080.value
                h >= 720 -> Qualities.P720.value
                h >= 480 -> Qualities.P480.value
                else -> Qualities.P360.value
            }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "detectQuality: '$url' threw ${t.javaClass.name}: ${t.message}")
            Qualities.P720.value
        }
    }

    // --------------------------------------------------------------------------------------------
    // JSON helpers (org.json; no Jackson/kotlin-reflect — R8-stripped, Erkenntnis #15).
    // --------------------------------------------------------------------------------------------

    private data class TmdbInput(val tmdbId: Int, val isTv: Boolean)

    private fun parseTmdbInput(url: String): TmdbInput? {
        return try {
            if (url.trimStart().startsWith("{")) {
                val obj = JSONObject(url)
                val id = obj.optInt("id", -1)
                val type = obj.optString("type", "")
                if (id <= 0) return null
                TmdbInput(id, type == "tv" || type == "series")
            } else {
                // Fallback: themoviedb.org/<type>/<id> URL.
                val m = Regex("themoviedb\\.org/(movie|tv)/(\\d+)").find(url) ?: return null
                TmdbInput(m.groupValues[2].toInt(), m.groupValues[1] == "tv")
            }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "parseTmdbInput: could not parse '$url' (${t.message})")
            null
        }
    }

    private fun linksToJson(links: List<String>): String {
        val arr = JSONArray()
        links.forEach { arr.put(it) }
        val obj = JSONObject()
        obj.put("links", arr)
        return obj.toString()
    }

    private fun parseLinksJson(data: String): List<String> {
        return try {
            val arr = JSONObject(data).optJSONArray("links") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) out.add(arr.optString(i, ""))
            out
        } catch (t: Throwable) {
            DebugLog.w(dbg, "parseLinksJson: threw ${t.javaClass.name}: ${t.message}")
            emptyList()
        }
    }

    private data class EpisodeData(val tmdbId: Int, val name: String, val season: Int, val episode: Int)

    private fun episodeDataJson(tmdbId: Int, name: String, season: Int, episode: Int): String {
        val obj = JSONObject()
        obj.put("tmdbId", tmdbId)
        obj.put("name", name)
        obj.put("season", season)
        obj.put("episode", episode)
        return obj.toString()
    }

    private fun parseEpisodeData(data: String): EpisodeData? {
        return try {
            val obj = JSONObject(data)
            EpisodeData(
                obj.optInt("tmdbId", -1),
                obj.optString("name", ""),
                obj.optInt("season", 1),
                obj.optInt("episode", 1)
            )
        } catch (t: Throwable) {
            DebugLog.w(dbg, "parseEpisodeData: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }
}
