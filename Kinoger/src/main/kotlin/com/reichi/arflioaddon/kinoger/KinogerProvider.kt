package com.reichi.arflioaddon.kinoger

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Kinoger.com provider for ARVIO, implemented as a TmdbProvider so ARVIO takes its direct
 * load() path (load({"id":<tmdbId>,"type":...})) instead of the fragile search-based
 * title-matching path. See AGENTS.md "Recherche: ARVIO-Plugin-Integration".
 *
 * KinoGer stream pages embed their host links in <script> calls of the form
 * `VAR.init(); VAR.show(SEASON, [[[S1E1_hosters...],[S1E2_hosters...],...],[S2...]], 0.2);`
 * — a 3D array: seasons x episodes-per-season x hosters-per-episode. For movies this collapses
 * to `show(1, [['url']], 0.2)` (1 season, 1 episode, 1 hoster). The page's default season in
 * show() is the highest one; ALL seasons are present in the array though.
 *
 * Host links point at KinoGer's own embed domains (fsst.online -> incvideo1.online, kinoger.pw,
 * kinoger.re, kinoger.embed4me.vip, kinoger.seekplays.pro). fsst.online/incvideo exposes direct
 * MP4 URLs in a Playerjs `file:` config (`[360p]url/,[720p]url/,[1080p]url/`), no JS execution
 * needed — extracted by regex. The other hosters fall back to a generic page-scrape. All HTTP is
 * plain java.net (ARVIO's R8 obfuscates okhttp3 + kotlin.coroutines, breaking cloudstream3's
 * suspend `app.get`/`loadExtractor` for external .cs3 plugins — see FilmpalastProvider).
 */
class KinogerProvider : TmdbProvider() {
    override var mainUrl = "https://kinoger.com"
    override var name = "Kinoger"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "Kinoger"
    private val NET_TIMEOUT_MS = 8000L

    // A desktop UA is required: KinoGer serves a JS-only mobile template to mobile UAs (the
    // hoster <script> arrays are absent). With a desktop UA the full hoster arrays are inline.
    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private data class HttpResp(val code: Int, val text: String)

    private fun httpGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): HttpResp {
        val fullUrl = if (params.isEmpty()) url else {
            val qs = params.entries.joinToString("&") {
                "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            "$url?$qs"
        }
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", desktopUA)
                setRequestProperty("Accept", "text/html,application/json,*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            DebugLog.t(dbg, "httpGet: $fullUrl -> $code (${text.length} bytes)")
            HttpResp(code, text)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "httpGet: $fullUrl threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "")
        } finally {
            conn?.disconnect()
        }
    }

    // ---- dataUrl payload: hand-built JSON (org.json) instead of AppUtils.toJson, to avoid
    // Jackson + kotlin-reflect (see FilmpalastProvider TmdbMeta note). Each episode/movie
    // carries its own list of hoster embed URLs as {"links":["url1","url2"]}.
    private fun linksToJson(links: List<String>): String {
        val sb = StringBuilder("{\"links\":[")
        links.forEachIndexed { i, link ->
            if (i > 0) sb.append(',')
            sb.append('"')
            link.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun parseLinksJson(data: String): List<String> {
        return try {
            val arr = JSONObject(data).optJSONArray("links") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "loadLinks: could not parse links JSON: ${t.message}")
            emptyList()
        }
    }

    private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")

    // search() kept for the classic CloudStream app / ARVIOs search fallback.
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?do=search&subaction=search&titleonly=3&story=${query.encode()}&x=0&y=0&submit=submit"
        val doc = Jsoup.parse(httpGet(url).text)
        return doc.select("div.titlecontrol").mapNotNull { it.toSearchResponse() }
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val entry = parseSearchEntry() ?: return null
        val tvType = if (entry.title.contains("staffel", ignoreCase = true) ||
            entry.title.contains("serie", ignoreCase = true) ||
            entry.url.contains("-stream-") && !entry.url.endsWith("-stream.html"))
            TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(entry.title, entry.url, tvType)
    }

    // ---- TmdbProvider load path ----

    override suspend fun load(url: String): LoadResponse? {
        DebugLog.t(dbg, "load() called with url=$url")
        return try {
            loadInternal(url)
        } catch (t: Throwable) {
            DebugLog.e(dbg, "load() threw ${t.javaClass.name}: ${t.message}", t)
            null
        }
    }

    private suspend fun loadInternal(url: String): LoadResponse? {
        val (tmdbId, isTv) = parseTmdbInput(url) ?: run {
            DebugLog.w(dbg, "load: could not parse TMDB input from '$url'")
            return null
        }
        DebugLog.t(dbg, "load: parsed tmdbId=$tmdbId isTv=$isTv")

        val meta = fetchTmdbMeta(tmdbId, isTv) ?: run {
            DebugLog.e(dbg, "load: TMDB metadata fetch failed for tmdbId=$tmdbId isTv=$isTv")
            return null
        }
        val title = meta.displayTitle
        val year = meta.year
        DebugLog.t(dbg, "load: TMDB meta -> title='$title' year=$year")

        val searchResults = searchKinoger(title)
        DebugLog.t(dbg, "load: Kinoger search('$title') returned ${searchResults.size} results")
        searchResults.take(15).forEach { DebugLog.t(dbg, "  search result: ${it.title} | ${it.url}") }

        val match = matchResult(searchResults, title, year) ?: run {
            DebugLog.w(dbg, "load: no match -> returning null")
            return null
        }
        DebugLog.t(dbg, "load: matched -> ${match.title} | ${match.url}")

        val streamPage = fetchStreamPage(match.url) ?: run {
            DebugLog.w(dbg, "load: stream page fetch failed")
            return null
        }

        return if (isTv) {
            buildSeriesResponse(streamPage, meta)
        } else {
            buildMovieResponse(streamPage, meta)
        }
    }

    private fun parseTmdbInput(url: String): Pair<Int, Boolean>? {
        return try {
            if (url.trimStart().startsWith("{")) {
                val obj = JSONObject(url)
                val rawId = obj.opt("id") ?: return null
                val id = when (rawId) {
                    is Int -> rawId
                    is Number -> rawId.toInt()
                    else -> rawId.toString().toIntOrNull() ?: return null
                }
                val type = obj.optString("type", "")
                id to type.equals("tv", ignoreCase = true)
            } else {
                val regex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
                val m = regex.find(url) ?: return null
                val isTv = m.groupValues[1].equals("tv", ignoreCase = true)
                m.groupValues[2].toIntOrNull()?.let { it to isTv }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- TMDB metadata (org.json, no Jackson/kotlin-reflect) ----

    private class TmdbMeta(val displayTitle: String, val year: Int?)

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbCache = java.util.concurrent.ConcurrentHashMap<Int, TmdbMeta>()

    private fun fetchTmdbMeta(tmdbId: Int, isTv: Boolean): TmdbMeta? {
        tmdbCache[tmdbId]?.let { return it }
        val path = if (isTv) "/tv/$tmdbId" else "/movie/$tmdbId"
        val full = "$tmdbApiUrl$path"
        return try {
            val res = httpGet(full, params = mapOf("api_key" to tmdbApiKey, "language" to "de-DE"))
            if (res.code !in 200..299) {
                DebugLog.e(dbg, "fetchTmdbMeta: GET $full -> HTTP ${res.code}")
                return null
            }
            val obj = JSONObject(res.text)
            val title = obj.optString("title", "").ifEmpty { obj.optString("name", "") }
                .ifEmpty { obj.optString("original_title", "") }.ifEmpty { obj.optString("original_name", "") }
            val date = obj.optString("release_date", "").ifEmpty { obj.optString("first_air_date", "") }
            val year = date.take(4).toIntOrNull()
            val meta = TmdbMeta(title.trim(), year)
            tmdbCache[tmdbId] = meta
            meta
        } catch (t: Throwable) {
            DebugLog.e(dbg, "fetchTmdbMeta: request threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ---- Kinoger search & match ----

    data class KinogerEntry(val url: String, val title: String)

    private fun searchKinoger(query: String): List<KinogerEntry> {
        val searchUrl = "$mainUrl/?do=search&subaction=search&titleonly=3&story=${query.encode()}&x=0&y=0&submit=submit"
        return try {
            val res = httpGet(searchUrl)
            if (res.code !in 200..299) {
                DebugLog.e(dbg, "searchKinoger: GET $searchUrl -> HTTP ${res.code}")
                return emptyList()
            }
            val doc = Jsoup.parse(res.text)
            // Each search result is wrapped in <div class="titlecontrol"><div class="title"><a href=".../stream/<id>-<slug>.html">TITLE Film</a></div></div>.
            // The sibling <div class="content_text searchresult_img"> only holds the poster image (alt=title), not the link.
            val selected = doc.select("div.titlecontrol")
            DebugLog.t(dbg, "searchKinoger: CSS selector matched ${selected.size} elements")
            selected.mapNotNull { it.parseSearchEntry() }
                .filter { it.title.isNotEmpty() }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "searchKinoger: GET threw ${t.javaClass.name}: ${t.message}")
            emptyList()
        }
    }

    /**
     * Parse one search-result block. KinoGer wraps each result in
     * `<div class="titlecontrol"><div class="title"><a href=".../stream/<id>-<slug>.html">TITLE Film</a></div></div>`.
     * The title is the anchor text (trailing " Film" suffix stripped); the link points to the stream page.
     */
    private fun org.jsoup.nodes.Element.parseSearchEntry(): KinogerEntry? {
        val link = selectFirst("a[href*=/stream/]") ?: return null
        var href = link.attr("href").ifEmpty { return null }
        val hashIdx = href.indexOf('#')
        if (hashIdx >= 0) href = href.substring(0, hashIdx)
        val u = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$mainUrl$href"
            else -> href
        }
        // Title: anchor text, e.g. "Matrix (1999) Film". Strip trailing " Film"/" Serie".
        val rawTitle = link.text().trim()
            .replace(Regex("""\s+(Film|Serie)$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (rawTitle.isEmpty() || rawTitle.equals("Suche", ignoreCase = true) ||
            rawTitle.contains("KinoGer", ignoreCase = true)) return null
        return KinogerEntry(u, rawTitle)
    }

    /**
     * Match a Kinoger search result to the requested title/year. KinoGer titles embed the year
     * (e.g. "Matrix Resurrections (2021)"). Prefer exact normalized title match, then year
     * proximity. KinoGer has ONE page per title that contains ALL seasons, so a single match is
     * enough (unlike Filmpalast which lists per-episode).
     */
    private fun matchResult(results: List<KinogerEntry>, title: String, year: Int?): KinogerEntry? {
        if (results.isEmpty()) return null
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
        val titleNorm = norm(title)
        val matched = results.filter { entry ->
            // Strip trailing "(YYYY)" year suffix when comparing the base name.
            val base = entry.title.replace(Regex("""\s*\(\d{4}\).*$"""), "").trim()
            val baseNorm = norm(base)
            baseNorm == titleNorm || baseNorm.contains(titleNorm) || titleNorm.contains(baseNorm)
        }
        return matched.sortedWith(compareBy(
            { if (norm(it.title.replace(Regex("""\s*\(\d{4}\).*$"""), "").trim()) == titleNorm) 0 else 1 },
            { yearDistance(it.title, year) }
        )).firstOrNull()
    }

    private fun yearDistance(title: String, year: Int?): Int {
        if (year == null) return Int.MAX_VALUE
        val m = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        return if (m != null) kotlin.math.abs(m - year) else Int.MAX_VALUE
    }

    // ---- stream page parsing (show() arrays) ----

    /**
     * Represents the parsed hoster structure of a KinoGer stream page: a list of seasons, each a
     * list of episodes, each a list of hoster embed URLs. For a movie this is [[["url"]]] (one
     * season, one episode, one hoster).
     */
    data class StreamPage(
        val title: String,
        val isSeries: Boolean,
        val seasons: List<List<List<String>>>
    )

    private fun fetchStreamPage(url: String): StreamPage? {
        return try {
            val res = httpGet(url)
            if (res.code !in 200..299) {
                DebugLog.e(dbg, "fetchStreamPage: GET $url -> HTTP ${res.code}")
                return null
            }
            val html = res.text
            val doc = Jsoup.parse(html)
            val title = doc.selectFirst("h1#news-title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: url.substringAfterLast("/").substringBefore("-stream")
                    .replace("-", " ").trim()
            val seasons = parseShowArrays(html)
            if (seasons.isEmpty()) {
                DebugLog.w(dbg, "fetchStreamPage: no hoster show() arrays found on $url")
                return null
            }
            // A movie collapses to 1 season with 1 episode with 1 hoster list. A series has either
            // multiple seasons OR an episode count > 1 in the single season.
            val isSeries = seasons.size > 1 || (seasons.isNotEmpty() && seasons[0].size > 1)
            DebugLog.t(dbg, "fetchStreamPage: title='$title' isSeries=$isSeries seasons=${seasons.size} epPerSeason=${seasons.map { it.size }}")
            StreamPage(title, isSeries, seasons)
        } catch (t: Throwable) {
            DebugLog.e(dbg, "fetchStreamPage: GET $url threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /**
     * Parse every `VAR.init(); VAR.show(SEASON, [ARRAY], 0.x);` call on the page and merge their
     * hoster lists per (season, episode). Different player vars (pw, fsst, go, ollhd) offer
     * DIFFERENT hosters for the SAME episodes — so we union all of them so loadLinks can try each.
     *
     * The array is 3D: [seasons][episodes][hosters]. We union hosters per (seasonIndex,episodeIndex)
     * across all show() calls, preserving first-seen order.
     */
    private fun parseShowArrays(html: String): List<List<List<String>>> {
        // Each show() call: VAR.init(); VAR.show(SEASON, <array>, 0.x);
        // We collect (array) for every call. The SEASON arg is the DEFAULT selected season, but the
        // array itself always contains ALL seasons — so we ignore the SEASON arg and use the array.
        val perCall = mutableListOf<List<List<List<String>>>>()
        val initPattern = Regex("""(\w+)\.init\(\);\s*\1\.show\(""")
        for (m in initPattern.findAll(html)) {
            val after = html.substring(m.range.last + 1)
            val arr = parseBracketArray(after) ?: continue
            perCall.add(arr)
            DebugLog.t(dbg, "parseShowArrays: call ${m.groupValues[1]} -> shape ${arr.map { it.size }}")
        }
        if (perCall.isEmpty()) return emptyList()

        // All calls share the same season/episode layout (verified: Silo 3x[10,10,7] across pw/fsst/go/ollhd).
        // Union hosters per (season,episode) across calls, dedup preserving order.
        val nSeasons = perCall.maxOf { it.size }
        val merged = MutableList(nSeasons) { mutableListOf<MutableList<String>>() }
        for (call in perCall) {
            for (s in call.indices) {
                val eps = call[s]
                while (merged[s].size < eps.size) merged[s].add(mutableListOf())
                for (e in eps.indices) {
                    for (h in eps[e]) {
                        val clean = h.trim()
                        if (clean.isNotEmpty() && clean !in merged[s][e]) merged[s][e].add(clean)
                    }
                }
            }
        }
        return merged
    }

    /**
     * Parse the first balanced bracket-array following `show(SEASON, ` and return it as a 3D list.
     * The array uses single-quoted strings, e.g. [['url1',' url2'],['url3']]. Strings may have
     * leading spaces (KinoGer pads them); we trim during merge.
     */
    private fun parseBracketArray(text: String): List<List<List<String>>>? {
        val start = text.indexOf('[')
        if (start < 0) return null
        val sb = StringBuilder()
        var depth = 0
        var i = start
        while (i < text.length) {
            val c = text[i]
            sb.append(c)
            when (c) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        if (depth != 0) return null
        val arrStr = sb.toString().replace("'", "\"")
        return try {
            val parsed = org.json.JSONArray(arrStr)
            // 3D: [seasons][episodes][hosters]
            val seasons = mutableListOf<MutableList<MutableList<String>>>()
            for (s in 0 until parsed.length()) {
                val seasonArr = parsed.optJSONArray(s) ?: continue
                val episodes = mutableListOf<MutableList<String>>()
                for (e in 0 until seasonArr.length()) {
                    val epArr = seasonArr.optJSONArray(e)
                    val hosters = mutableListOf<String>()
                    if (epArr != null) {
                        for (h in 0 until epArr.length()) {
                            hosters.add(epArr.optString(h))
                        }
                    } else {
                        // Single string fallback (rare): treat the element itself as one hoster.
                        seasonArr.optString(e).takeIf { it.isNotBlank() }?.let { hosters.add(it) }
                    }
                    episodes.add(hosters)
                }
                seasons.add(episodes)
            }
            seasons
        } catch (t: Throwable) {
            DebugLog.w(dbg, "parseBracketArray: JSON parse failed: ${t.message}")
            null
        }
    }

    // ---- LoadResponse builders ----

    private suspend fun buildMovieResponse(page: StreamPage, meta: TmdbMeta): LoadResponse? {
        return try {
            // Movie: season 0, episode 0 hoster list.
            val hosters = page.seasons.firstOrNull()?.firstOrNull() ?: emptyList()
            DebugLog.t(dbg, "buildMovieResponse: ${hosters.size} hosters")
            hosters.take(20).forEach { DebugLog.t(dbg, "  hoster: $it") }
            val dataUrl = linksToJson(hosters)
            newMovieLoadResponse(page.title.ifEmpty { meta.displayTitle }, mainUrl, TvType.Movie, dataUrl) {
                this.year = meta.year
                this.plot = ""
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildMovieResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    private suspend fun buildSeriesResponse(page: StreamPage, meta: TmdbMeta): LoadResponse? {
        return try {
            val epList = mutableListOf<Episode>()
            page.seasons.forEachIndexed { seasonIdx, season ->
                season.forEachIndexed { epIdx, hosters ->
                    if (hosters.isEmpty()) return@forEachIndexed
                    val data = linksToJson(hosters)
                    epList.add(newEpisode(data) {
                        this.name = "S${seasonIdx + 1}E${epIdx + 1}"
                        this.season = seasonIdx + 1
                        this.episode = epIdx + 1
                    })
                }
            }
            DebugLog.t(dbg, "buildSeriesResponse: built ${epList.size} episodes (seasons: ${epList.mapNotNull { it.season }.distinct()})")
            if (epList.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: empty episode list -> null")
                return null
            }
            newTvSeriesLoadResponse(page.title.ifEmpty { meta.displayTitle }, mainUrl, TvType.TvSeries, epList) {
                this.year = meta.year
                this.plot = ""
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildSeriesResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ---- loadLinks ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.t(dbg, "loadLinks() called with data=${data.take(300)}")

        val links: List<String> = if (data.trimStart().startsWith("{")) {
            parseLinksJson(data)
        } else {
            DebugLog.w(dbg, "loadLinks: data is not JSON -> empty. data='$data'")
            emptyList()
        }
        if (links.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: 0 links -> no sources")
            return false
        }

        val fixed = links.mapNotNull { link ->
            val f = fixUrlNull(link)
            if (f == null) DebugLog.w(dbg, "loadLinks: fixUrlNull null for '$link' -> skip")
            f
        }
        if (fixed.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: all hoster links invalid after fixUrlNull -> no sources")
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
                    if (f.get(2, java.util.concurrent.TimeUnit.SECONDS)) any = true
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

    /**
     * Resolve a hoster embed URL to direct video URLs. Dispatches by domain to hoster-specific
     * extractors, falling back to a generic page-scrape. All non-suspend (java.net + jsoup) to
     * avoid the broken ARVIO coroutine machinery for external .cs3 plugins.
     */
    private fun resolveHost(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase() ?: ""
            when {
                // fsst.online redirects to incvideo1.online and serves a Playerjs config with
                // direct MP4 URLs (`[360p]url/,[720p]url/,[1080p]url/`). The most reliable KinoGer
                // hoster; no JS execution needed.
                host.contains("fsst.online") || host.contains("incvideo") -> resolveIncvideo(url, callback)
                // VOE (voe.sx + mirrors) — ported from resolveurl voesx.py (voe_decode).
                host.contains("voe.") || host.endsWith("voe.sx") -> resolveVoe(url, callback)
                // vidsonic.net: hex-encoded + reversed direct m3u8 URL.
                host.contains("vidsonic") -> resolveVidsonic(url, callback)
                // firestream.to: token-blob -> POST /api/videos/<id>/resolve -> signedVideoUrl.
                host.contains("firestream") -> resolveFirestream(url, callback)
                // JWPlayer hosters with /api/stream POST (odysseusa.cc pattern).
                host.contains("odysseusa") -> resolveOdysseusa(url, callback)
                else -> genericResolve(url, callback)
            }
        } catch (t: Throwable) {
            // Catch Throwable (not Exception) - R8-stripping causes NoClassDefFoundError /
            // NoSuchMethodError (Errors, not Exceptions) that would otherwise crash the app.
            DebugLog.w(dbg, "resolveHost: '$url' threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /**
     * fsst.online / incvideo1.online extractor. The embed page contains a Playerjs config with
     * `file:"[360p]<url>/,[720p]<url>/,[1080p]<url>/"`. Each segment is `[quality]<mp4-url>/`
     * separated by commas. We extract every quality variant and emit each with its real quality,
     * so ARVIO shows 1080p/720p/360p and Auto-Play can pick the best.
     */
    private fun resolveIncvideo(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, headers = mapOf("Referer" to "https://kinoger.com/", "User-Agent" to desktopUA))
        if (res.code !in 200..299 && res.code != 0) {
            DebugLog.w(dbg, "resolveIncvideo: GET $url -> HTTP ${res.code}")
        }
        val text = res.text
        if (text.isEmpty()) {
            DebugLog.w(dbg, "resolveIncvideo: empty body from $url")
            return false
        }
        var found = false
        // The fsst.online embed page repeats its playerjs config across multiple player
        // instances, so the same stream URL appears several times. Emit each URL only once.
        val emitted = HashSet<String>()
        // Playerjs file config: file:"[360p]https://...mp4/,[720p]https://...mp4/,..."
        // Capture quality label + url.
        val segPattern = Regex("""\[(\d{3,4}p)?\](https?://[^\s"',]+\.mp4[^\s"',/]*)""")
        segPattern.findAll(text).forEach { m ->
            val label = m.groupValues[1].ifEmpty { "720p" }
            val streamUrl = m.groupValues[2]
            if (!emitted.add(streamUrl)) return@forEach
            val quality = mapQualityLabel(label)
            emitLink("Incvideo", streamUrl, "https://fsst.online/", quality, false, callback)
            found = true
        }
        if (!found) {
            // Fallback: any direct mp4 URL in the page.
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(text).forEach { m ->
                val streamUrl = m.groupValues[1]
                if (!emitted.add(streamUrl)) return@forEach
                emitLink("Incvideo", streamUrl, "https://fsst.online/", Qualities.P720.value, false, callback)
                found = true
            }
        }
        DebugLog.t(dbg, "resolveIncvideo: GET $url -> ${res.code}, len=${text.length}, found=$found (emitted ${emitted.size} unique)")
        return found
    }

    private fun mapQualityLabel(label: String): Int = when (label.lowercase()) {
        "4k", "2160p" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        "240p" -> Qualities.P240.value
        "144p" -> Qualities.P144.value
        else -> Qualities.P720.value
    }

    // ---- shared hoster extractors (ported from FilmpalastProvider) ----

    private fun resolveVoe(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, headers = mapOf("User-Agent" to desktopUA))
        val text = res.text
        var found = false

        // Pattern 1: voe_decode path. Redirect loop first.
        var currentUrl = url
        var currentText = text
        var redirects = 0
        while (currentText.contains("const currentUrl") && redirects < 5) {
            val r = Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(currentText) ?: break
            currentUrl = r.groupValues[1]
            currentText = httpGet(currentUrl, headers = mapOf("User-Agent" to desktopUA)).text
            redirects++
        }

        val p1 = Regex("""json">\["([^"]+)"\]</script>\s*<script\s*src="([^"]+)""")
        val m1 = p1.find(currentText)
        if (m1 != null) {
            val ct = m1.groupValues[1]
            val jsUrlRaw = m1.groupValues[2]
            val jsUrl = if (jsUrlRaw.startsWith("http")) jsUrlRaw else "$currentUrl/$jsUrlRaw".replace("//", "/")
            val jsRes = httpGet(jsUrl, headers = mapOf("User-Agent" to desktopUA))
            val lutMatch = Regex("""(\[(?:'\W{2}'[,\]]){1,9})""").find(jsRes.text)
            if (lutMatch != null) {
                val decoded = try { voeDecode(ct, lutMatch.groupValues[1]) } catch (_: Throwable) { null }
                if (decoded != null) {
                    val source = decoded.optString("source", "")
                    val file = decoded.optString("file", "")
                    val direct = decoded.optString("direct_access_url", "")
                    when {
                        source.startsWith("http") -> { emitLink("VOE", source, "https://voe.sx/", callback); found = true }
                        file.startsWith("http") -> { emitLink("VOE", file, "https://voe.sx/", callback); found = true }
                        direct.startsWith("http") -> { emitLink("VOE", direct, "https://voe.sx/", callback); found = true }
                    }
                }
            }
        }

        if (!found) {
            val urlPatterns = listOf(
                Regex(""""hls"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex(""""mp4"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""""),
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
        DebugLog.t(dbg, "resolveVoe: GET $url -> ${res.code}, found=$found")
        return found
    }

    private fun voeDecode(ct: String, luts: String): org.json.JSONObject {
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
            val escaped = buildString { for (c in item) if (c in specials) { append('\\'); append(c) } else append(c) }
            txt = try { Regex(escaped).replace(txt, "") } catch (_: Throwable) { txt }
        }
        val step4 = try { base64Decode(txt) } catch (_: Throwable) { return org.json.JSONObject() }
        val step5 = String(CharArray(step4.length) { (step4[it].toInt() - 3).toChar() })
        val step6 = try { base64Decode(step5.reversed()) } catch (_: Throwable) { return org.json.JSONObject() }
        return try { org.json.JSONObject(step6) } catch (_: Throwable) { org.json.JSONObject() }
    }

    private fun base64Decode(s: String): String =
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    private fun resolveVidsonic(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val ref = url.substringBefore("/e/") + "/"
        val res = httpGet(url, headers = mapOf("Referer" to ref, "Origin" to ref.trimEnd('/'), "User-Agent" to desktopUA))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveVidsonic: GET $url -> HTTP ${res.code}")
            return false
        }
        val hex = Regex("const\\s*_0x1\\s*=\\s*'([^']+)'").find(res.text)?.groupValues?.get(1)
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
            DebugLog.w(dbg, "resolveVidsonic: hex-decode threw ${t.javaClass.name}: ${t.message}")
            return false
        }
        if (!streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveVidsonic: decoded URL invalid: '$streamUrl'")
            return false
        }
        emitLink("Vidsonic", streamUrl, ref, callback)
        return true
    }

    private fun resolveFirestream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaId = url.substringAfterLast("/e/").substringBefore("?").trim()
        if (mediaId.isEmpty()) return false
        val res = httpGet(url, headers = mapOf("User-Agent" to desktopUA))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveFirestream: GET $url -> HTTP ${res.code}")
            return false
        }
        val blob = Regex("""id="token-blob"[^>]+>([^<]+)""").find(res.text)?.groupValues?.get(1)
        if (blob.isNullOrBlank()) {
            DebugLog.w(dbg, "resolveFirestream: no token-blob found")
            return false
        }
        val apiUrl = "https://firestream.to/api/videos/$mediaId/resolve"
        val body = """{"blob":"$blob"}"""
        val apiRes = httpPost(apiUrl, body, headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to "https://firestream.to/",
            "Origin" to "https://firestream.to",
            "User-Agent" to desktopUA
        ))
        if (apiRes.code !in 200..299) {
            DebugLog.w(dbg, "resolveFirestream: POST $apiUrl -> HTTP ${apiRes.code}")
            return false
        }
        val streamUrl = try { org.json.JSONObject(apiRes.text).optString("signedVideoUrl", "") } catch (_: Throwable) { "" }
        if (!streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveFirestream: no signedVideoUrl")
            return false
        }
        emitLink("Firestream", streamUrl, "https://firestream.to/", callback)
        return true
    }

    private fun resolveOdysseusa(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val base = url.substringBefore("/e/")
        val filecode = url.substringAfterLast("/e/").substringBefore("?").trim()
        if (filecode.isEmpty()) return false
        val apiUrl = "$base/api/stream"
        val body = """{"filecode":"$filecode","device":"android"}"""
        val res = httpPost(apiUrl, body, headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to url,
            "User-Agent" to desktopUA
        ))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveOdysseusa: POST $apiUrl -> HTTP ${res.code}")
            return false
        }
        val streamUrl = try { JSONObject(res.text).optString("streaming_url", "") } catch (_: Throwable) { "" }
        if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveOdysseusa: no streaming_url")
            return false
        }
        emitLink("Odysseusa", streamUrl, callback)
        return true
    }

    private fun httpPost(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResp {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", desktopUA)
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

    private fun genericResolve(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val res = httpGet(url, headers = mapOf("Referer" to "https://kinoger.com/", "User-Agent" to desktopUA))
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

    private fun emitLink(source: String, url: String, callback: (ExtractorLink) -> Unit) =
        emitLink(source, url, mainUrl, callback)

    private fun emitLink(source: String, url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains(".m3u8")
        val quality = if (isM3u8) detectQuality(url) else Qualities.P720.value
        emitLink(source, url, referer, quality, isM3u8, callback)
    }

    /**
     * Emit a single ExtractorLink using the PRIMARY constructor (all 9 positional args, no
     * default-args) — R8 strips the synthetic DefaultConstructorMarker constructor (Erkenntnis
     * #6/#18), so named-arg/default-arg construction throws NoSuchMethodError at runtime.
     */
    private fun emitLink(
        source: String,
        url: String,
        referer: String,
        quality: Int,
        isM3u8: Boolean,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            DebugLog.t(dbg, "emitLink: source=$source url=$url quality=$quality isM3u8=$isM3u8")
            val link = ExtractorLink(
                source,                                                          // source
                source,                                                          // name
                url,                                                             // url
                referer,                                                         // referer
                quality,                                                         // quality
                emptyMap(),                                                      // headers (was default)
                "",                                                              // extractorData (was default)
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, // type
                emptyList()                                                      // audioTracks (was default)
            )
            callback.invoke(link)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "emitLink: threw ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * Detect real video quality from an m3u8 master manifest (RESOLUTION=WxH) so ARVIO shows the
     * real resolution and Auto-Play scores the stream. See FilmpalastProvider.detectQuality.
     */
    private fun detectQuality(url: String): Int {
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
}
