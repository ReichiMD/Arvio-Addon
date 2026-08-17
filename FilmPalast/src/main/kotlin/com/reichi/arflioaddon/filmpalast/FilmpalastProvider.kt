package com.reichi.arflioaddon.filmpalast

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
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

/**
 * Filmpalast.to provider for ARVIO, implemented as a TmdbProvider so ARVIO takes its
 * direct load() path (load({"id":<tmdbId>,"type":...})) instead of the fragile
 * search-based title-matching path. See AGENTS.md "Recherche: ARVIO-Plugin-Integration".
 *
 * Filmpalast indexes series per-episode (/stream/<slug>-s03e06), not as a series page with
 * seasons. So load() resolves the TMDB title via TMDB metadata, searches Filmpalast, and for
 * series collects every matching episode into a TvSeriesLoadResponse.
 *
 * `dataUrl`/Episode.data carries the full Filmpalast stream URL; loadLinks() fetches that
 * page and resolves each hoster via our own non-suspend extractors (java.net + jsoup).
 */
class FilmpalastProvider : TmdbProvider() {
    override var mainUrl = "https://filmpalast.to"
    override var name = "Filmpalast"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "Filmpalast"
    // Per-network-call timeout. ARVIO's scraper has a total timeout that covers load() +
    // loadLinks(); if a single call hangs, it would consume the whole budget and loadLinks
    // would never run. Keep each call well under ARVIO's total budget.
    private val NET_TIMEOUT_MS = 8000L

    /**
     * Plain-Java HTTP GET using java.net.HttpURLConnection (no okhttp / no suspend).
     * ARVIO's R8 obfuscates okhttp3 + kotlin.coroutines, which breaks cloudstream3's suspend
     * `app.get` for external .cs3 plugins (ClassCastException / NoSuchMethodError). Using
     * java.net sidesteps that entirely: the JDK is never obfuscated, and we avoid inner
     * suspend calls so the coroutine state machine of load()/loadLinks() stays trivial.
     * jsoup (kept unobfuscated by ARVIO, 330 classes) parses the returned HTML.
     */
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
            conn = openDohConnection(fullUrl).apply {
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
            DebugLog.t(dbg, "httpGet: $fullUrl -> $code (${text.length} bytes)")
            HttpResp(code, text)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "httpGet: $fullUrl threw ${t.javaClass.name}: ${t.message}")
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

    // Movie dataUrl payload: hand-built JSON (org.json) instead of AppUtils.toJson, to avoid
    // Jackson + kotlin-reflect dependency (see TmdbMeta note above).
    private fun linksToJson(links: List<String>): String {
        val sb = StringBuilder("{\"links\":[")
        links.forEachIndexed { i, link ->
            if (i > 0) sb.append(',')
            sb.append('"')
            // minimal JSON string escaping
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
            DebugLog.e(dbg, "loadLinks: could not parse movie links JSON: ${t.message}")
            emptyList()
        }
    }

    // search() is still implemented so the classic CloudStream app (and ARVIOs search path
    // fallback) can use it. Returns the Filmpalast stream URL as the SearchResponse url.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = Jsoup.parse(httpGet("$mainUrl/search/title/$query").text)
        return document.select("#content .glowliste").mapNotNull { it.toSearchResponse() }
    }

    // ---- TmdbProvider load path ----

    /**
     * ARVIO calls load("{\"id\":<tmdbId>,\"type\":\"movie\"|\"tv\"}") and falls back to
     * load("https://www.themoviedb.org/<type>/<id>"). We accept both: parse the TMDB id/type,
     * fetch TMDB metadata (title/year), search Filmpalast, and build the right LoadResponse.
     */
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

        // Filmpalast search returns episode URLs for series and movie pages for movies.
        val searchResults = searchFilmpalast(title)
        DebugLog.t(dbg, "load: Filmpalast search('$title') returned ${searchResults.size} results")
        searchResults.take(15).forEach { DebugLog.t(dbg, "  search result: ${it.title} | type=${it.type} s=${it.season} e=${it.episode} | ${it.url}") }

        val matches = matchResults(searchResults, title, year, isTv)
        DebugLog.t(dbg, "load: after matchResults -> ${matches.size} matches (isTv=$isTv)")
        matches.take(15).forEach { DebugLog.t(dbg, "  match: ${it.title} | s=${it.season} e=${it.episode} | ${it.url}") }
        if (matches.isEmpty()) {
            DebugLog.w(dbg, "load: no matches -> returning null")
            return null
        }

        return if (isTv) {
            buildSeriesResponse(matches, meta)
        } else {
            buildMovieResponse(matches.first(), meta)
        }
    }

    private fun parseTmdbInput(url: String): Pair<Int, Boolean>? {
        // JSON form: {"id":123,"type":"tv"}
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
                val isTv = type.equals("tv", ignoreCase = true)
                id to isTv
            } else {
                // URL form: https://www.themoviedb.org/<movie|tv>/<id>
                val regex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
                val m = regex.find(url) ?: return null
                val isTv = m.groupValues[1].equals("tv", ignoreCase = true)
                m.groupValues[2].toIntOrNull()?.let { it to isTv }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- TMDB metadata (lightweight, only what we need for matching) ----
    // Parsed with org.json (Android built-in) instead of cloudstream3's AppUtils.parseJson,
    // because the latter uses Jackson + jackson-module-kotlin which needs kotlin-reflect, and
    // ARVIO's R8 shrinking strips kotlin-reflect, breaking default-arg construction
    // ("This callable does not support a default call"). org.json needs no reflection.

    private class TmdbMeta(
        val id: Int?,
        val displayTitle: String,
        val year: Int?
    )

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"

    // JDK ConcurrentHashMap: thread-safe, never obfuscated by ARVIO's R8 (unlike okhttp/coroutines).
    // ARVIO may call load() repeatedly for the same TMDB id during a source search; caching avoids
    // a redundant ~300ms TMDB round-trip on repeated lookups (same movie re-searched, alt-titles retry).
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
            DebugLog.t(dbg, "fetchTmdbMeta: GET $full -> ${res.code}")
            val obj = JSONObject(res.text)
            val title = obj.optString("title", "").ifEmpty { obj.optString("name", "") }
                .ifEmpty { obj.optString("original_title", "") }.ifEmpty { obj.optString("original_name", "") }
            val date = obj.optString("release_date", "").ifEmpty { obj.optString("first_air_date", "") }
            val year = date.take(4).toIntOrNull()
            val meta = TmdbMeta(obj.optInt("id", -1).takeIf { it >= 0 }, title.trim(), year)
            tmdbCache[tmdbId] = meta
            meta
        } catch (t: Throwable) {
            DebugLog.e(dbg, "fetchTmdbMeta: request threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ---- Filmpalast search & match ----

    data class FilmpalastEntry(
        val url: String,
        val title: String,
        val type: TvType,
        val season: Int?,
        val episode: Int?
    )

    private fun Element.toSearchEntry(): FilmpalastEntry? {
        val a = selectFirst("a") ?: return null
        val rawTitle = a.attr("title").ifEmpty { a.text() }
        val href = a.attr("href").ifEmpty { return null }
        val url = if (href.startsWith("//")) "https:$href" else if (href.startsWith("/")) "$mainUrl$href" else href
        val (type, season, episode) = classify(rawTitle)
        return FilmpalastEntry(url, rawTitle, type, season, episode)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val entry = toSearchEntry() ?: return newMovieSearchResponse("", "", TvType.Movie)
        return if (entry.type == TvType.TvSeries) {
            newTvSeriesSearchResponse(entry.title, entry.url, TvType.TvSeries)
        } else {
            newMovieSearchResponse(entry.title, entry.url, TvType.Movie)
        }
    }

    /** Returns TvSeries for "Silo S03E06"-like titles, else Movie. */
    private fun classify(title: String): Triple<TvType, Int?, Int?> {
        val ep = Regex("""S(\d{1,2})E(\d{1,3})""", RegexOption.IGNORE_CASE).find(title)
        if (ep != null) {
            return Triple(TvType.TvSeries, ep.groupValues[1].toIntOrNull(), ep.groupValues[2].toIntOrNull())
        }
        return Triple(TvType.Movie, null, null)
    }

    private fun searchFilmpalast(query: String): List<FilmpalastEntry> {
        val searchUrl = "$mainUrl/search/title/${query.encode()}"
        return try {
            val res = httpGet(searchUrl)
            if (res.code !in 200..299) {
                DebugLog.e(dbg, "searchFilmpalast: GET $searchUrl -> HTTP ${res.code}")
                return emptyList()
            }
            DebugLog.t(dbg, "searchFilmpalast: GET $searchUrl -> ${res.code}")
            val document = Jsoup.parse(res.text)
            val selected = document.select("#content article.liste, #content .glowliste")
            DebugLog.t(dbg, "searchFilmpalast: CSS selector matched ${selected.size} elements")
            if (selected.isEmpty()) {
                DebugLog.w(dbg, "searchFilmpalast: 0 elements matched. Page title/h2: ${document.select("title").text()} | first 300 chars: ${document.body().text().take(300)}")
            }
            selected.mapNotNull { it.toSearchEntry() }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "searchFilmpalast: GET threw ${t.javaClass.name}: ${t.message}")
            emptyList()
        }
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    /**
     * Match Filmpalast search results to the requested title/year/type.
     * For series we keep all episodes of matching seasons (any season), for movies the single
     * movie page whose slug title matches.
     */
    private fun matchResults(
        results: List<FilmpalastEntry>,
        title: String,
        year: Int?,
        isTv: Boolean
    ): List<FilmpalastEntry> {
        if (results.isEmpty()) return emptyList()
        val norm = { s: String -> s.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ").trim() }
        val titleNorm = norm(title)
        val matched = results.filter { entry ->
            // Strip trailing S\dE\d / season markers when comparing the base name.
            val base = entry.title.replace(Regex("""\s*[Ss]\d{1,2}[Ee]\d{1,3}.*$"""), "").trim()
            val baseNorm = norm(base)
            val typeOk = if (isTv) entry.type == TvType.TvSeries else entry.type == TvType.Movie
            typeOk && (baseNorm == titleNorm || baseNorm.contains(titleNorm) || titleNorm.contains(baseNorm))
        }
        // Prefer exact title match first (e.g. "Matrix" over "Matrix Revolutions"), then by
        // year proximity if available, so buildMovieResponse loads the right stream page.
        return matched.sortedWith(compareBy(
            { if (norm(it.title) == titleNorm) 0 else 1 },
            { yearDistance(it.title, year) }
        ))
    }

    private fun yearDistance(title: String, year: Int?): Int {
        if (year == null) return Int.MAX_VALUE
        // Filmpalast titles sometimes embed a year (e.g. "Matrix (1999)").
        val m = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        return if (m != null) kotlin.math.abs(m - year) else Int.MAX_VALUE
    }

    // newMovieLoadResponse/newTvSeriesLoadResponse are themselves suspend cloudstream3 API
    // calls (ARVIO-provided, run ARVIO->ARVIO internally), so these builders stay suspend.
    private suspend fun buildMovieResponse(entry: FilmpalastEntry, meta: TmdbMeta): LoadResponse? {
        return try {
            DebugLog.t(dbg, "buildMovieResponse: GET ${entry.url}")
            val res = httpGet(entry.url)
            DebugLog.t(dbg, "buildMovieResponse: -> ${res.code}")
            val doc = Jsoup.parse(res.text).select("#content")
            val detailTitle = doc.select("h2.rb.bgDark").text().ifEmpty { meta.displayTitle }
            val imagePath = doc.select(".detail.rb img.cover2").attr("src")
            val description = doc.select("span[itemprop=description]").text()
            val links = collectHosterLinks(doc)
            DebugLog.t(dbg, "buildMovieResponse: collected ${links.size} hoster links")
            links.take(20).forEach { DebugLog.t(dbg, "  hoster link: $it") }

            newMovieLoadResponse(detailTitle, entry.url, TvType.Movie, linksToJson(links)) {
                this.posterUrl = fixUrl(imagePath)
                this.plot = description
                this.year = meta.year
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildMovieResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }
    private suspend fun buildSeriesResponse(
        episodes: List<FilmpalastEntry>,
        meta: TmdbMeta
    ): LoadResponse? {
        return try {
            // Group by season/episode. Filmpalast lists episodes individually; each entry already
            // has season/episode parsed from its title.
            val sorted = episodes.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
                .distinctBy { it.season to it.episode }

            val epList: List<Episode> = sorted.mapIndexed { _, e ->
                // dataUrl carries the Filmpalast stream URL; loadLinks resolves it.
                newEpisode(e.url) {
                    this.name = e.title
                    this.season = e.season
                    this.episode = e.episode
                }
            }
            DebugLog.t(dbg, "buildSeriesResponse: built ${epList.size} episodes (seasons: ${epList.mapNotNull { it.season }.distinct()})")
            epList.take(20).forEach { DebugLog.t(dbg, "  episode: S${it.season}E${it.episode} ${it.name} -> ${it.data}") }
            if (epList.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: empty episode list -> null")
                return null
            }

            newTvSeriesLoadResponse(meta.displayTitle, mainUrl, TvType.TvSeries, epList) {
                this.year = meta.year
                this.plot = ""
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildSeriesResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    private fun collectHosterLinks(doc: Elements): List<String> {
        return doc.select(".currentStreamLinks a.iconPlay").mapNotNull { a ->
            a.attr("data-player-url").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() && it != "#" }
        }
    }

    // ---- loadLinks ----

    /**
     * `data` is either:
     *  - a MovieLoadResponse.dataUrl = JSON {"links":[...]} (movie path), or
     *  - an Episode.data = the Filmpalast stream page URL (series path).
     *
     * For each hoster link we resolve it ourselves via resolveHost (hoster-specific extractors
     * like VOE, plus a generic page-scrape for direct mp4/m3u8 URLs), in PARALLEL via a JDK
     * thread pool so total time is bounded by the slowest hoster, not the sum. We do NOT call
     * cloudstream3's loadExtractor: it is an ARVIO-provided suspend function that is broken for
     * external .cs3 plugins (coroutine resume returns a stray obfuscated object instead of
     * Boolean -> ClassCastException, and callback emissions never reach ARVIO). All our hoster
     * extraction is non-suspend (java.net + jsoup), sidestepping the ARVIO coroutine machinery.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.t(dbg, "loadLinks() called with data=${data.take(300)}")

        val links: List<String> = when {
            data.trimStart().startsWith("{") -> {
                try { parseLinksJson(data) } catch (t: Throwable) {
                    DebugLog.e(dbg, "loadLinks: could not parse movie links JSON: ${t.message}")
                    emptyList()
                }
            }
            data.startsWith("http") -> {
                try {
                    DebugLog.t(dbg, "loadLinks: series path, fetching episode page $data")
                    val res = httpGet(data)
                    DebugLog.t(dbg, "loadLinks: episode page -> ${res.code}")
                    collectHosterLinks(Jsoup.parse(res.text).select("#content"))
                } catch (t: Throwable) {
                    DebugLog.e(dbg, "loadLinks: fetching episode page threw ${t.javaClass.name}: ${t.message}")
                    emptyList()
                }
            }
            else -> {
                DebugLog.w(dbg, "loadLinks: data is neither JSON nor http URL -> empty. data='$data'")
                emptyList()
            }
        }
        DebugLog.t(dbg, "loadLinks: resolved ${links.size} hoster links to try")
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

        // Resolve hosters in PARALLEL so the slowest single hoster bounds total time instead of
        // the sum. With 3 hosters (odysseusa+voe+vidsonic) sequential was ~1.45s; parallel ~0.7s,
        // keeping us well under ARVIO's 2s Auto-Play timeout. java.util.concurrent (JDK, never
        // obfuscated by R8). callback is (ExtractorLink)->Unit; ARVIO collects results thread-safely
        // (no ConcurrentModificationException observed in v26). Each future has a 2s timeout so a
        // hung hoster never blocks the whole budget; pool is always shut down in finally.
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
                    // 2s per future; a hung hoster is abandoned but others continue.
                    if (f.get(2, java.util.concurrent.TimeUnit.SECONDS)) any = true
                } catch (t: Throwable) {
                    DebugLog.w(dbg, "loadLinks: hoster future threw ${t.javaClass.name}: ${t.message}")
                }
            }
        } finally {
            pool.shutdownNow()
        }
        DebugLog.t(dbg, "loadLinks: DONE, any=$any (any=true means at least one source emitted)")
        return any
    }

    /**
     * Resolve a hoster embed URL to direct video URLs, emitting ExtractorLinks via callback.
     * Dispatches to hoster-specific extractors by domain, falling back to a generic page-scrape
     * for direct mp4/m3u8 URLs. All non-suspend (java.net + jsoup) to avoid the broken ARVIO
     * coroutine machinery for external .cs3 plugins (see loadExtractor note above).
     */
    private fun resolveHost(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val host = java.net.URI(url).host?.lowercase() ?: ""
            when {
                host.contains("voe.") || host.endsWith("voe.sx") || host.contains("voe.sx") -> resolveVoe(url, callback)
                // odysseusa.cc + similar JWPlayer hosters expose a /api/stream POST endpoint that
                // returns JSON with a direct streaming_url (m3u8). Tested live (Aug 2026).
                host.contains("odysseusa") -> resolveOdysseusa(url, callback)
                // vidsonic.net embeds the direct m3u8 URL as a hex-encoded + reversed string in a
                // `const _0x1 = '...'` JS variable. Algorithm from Gujal00/ResolveURL vidsonic.py.
                host.contains("vidsonic") -> resolveVidsonic(url, callback)
                // firestream.to: token-blob in embed page -> POST /api/videos/<id>/resolve -> signedVideoUrl.
                host.contains("firestream") -> resolveFirestream(url, callback)
                // Add more hoster-specific extractors here as needed.
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
     * vidsonic.net extractor. The embed page at /e/<mediaId> contains a JS variable
     * `const _0x1 = '<hex-with-pipe-separators>'`. Decoding: strip pipes, hex-decode to ASCII,
     * reverse the string -> the direct master.m3u8 URL (with server_id/expires/md5 token).
     * Algorithm verified live (Aug 2026) and matches Gujal00/ResolveURL vidsonic.py.
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
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveVidsonic: regex threw ${t.javaClass.name}: ${t.message}")
            null
        }
        if (hex.isNullOrBlank()) {
            DebugLog.w(dbg, "resolveVidsonic: no _0x1 hex variable found in page")
            return false
        }
        val streamUrl = try {
            // hex-decode (strip pipe separators first), then reverse -> m3u8 URL.
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
            DebugLog.w(dbg, "resolveVidsonic: decoded URL does not start with http: '$streamUrl'")
            return false
        }
        DebugLog.t(dbg, "resolveVidsonic: streaming_url=$streamUrl")
        emitLink("Vidsonic", streamUrl, ref, callback)
        return true
    }

    /**
     * odysseusa.cc (JWPlayer) extractor. The embed page at /e/<filecode> sets up a JWPlayer whose
     * `streaming_url` is fetched via POST /api/stream with JSON body {"filecode":"...","device":"android"}.
     * The response JSON contains `streaming_url` = a direct master.m3u8 URL with token.
     */
    private fun resolveOdysseusa(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val base = url.substringBefore("/e/")
        val filecode = url.substringAfterLast("/e/").substringBefore("?").trim()
        if (filecode.isEmpty()) {
            DebugLog.w(dbg, "resolveOdysseusa: could not extract filecode from $url")
            return false
        }
        val apiUrl = "$base/api/stream"
        val body = """{"filecode":"$filecode","device":"android"}"""
        val res = httpPost(apiUrl, body, headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to url,
            "User-Agent" to mobileUA
        ))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveOdysseusa: POST $apiUrl -> HTTP ${res.code}")
            return false
        }
        val streamUrl = try {
            JSONObject(res.text).optString("streaming_url", "")
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveOdysseusa: could not parse JSON: ${t.message}")
            return false
        }
        if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveOdysseusa: no streaming_url in response")
            return false
        }
        DebugLog.t(dbg, "resolveOdysseusa: streaming_url=$streamUrl")
        emitLink("Odysseusa", streamUrl, callback)
        return true
    }

    /**
     * VOE (voe.sx and mirrors) extractor, ported from Gujal00/ResolveURL voesx.py.
     *
     * Primary path (Pattern 1): the page contains `json">["<encoded>"]</script><script src="<jsUrl>`.
     * The JS file at jsUrl contains a LUT (lookup table). voe_decode(encoded, lut) reverses the
     * obfuscation: ROT-shift letters -> strip LUT elements -> base64-decode -> Caesar -3 ->
     * base64-decode reversed -> JSON {file, source, direct_access_url}. We pick the m3u8 (source)
     * if present, else mp4 (file).
     *
     * Fallback path: direct hls/mp4 regexes in the page (for VOE variants that embed URLs plainly).
     * VOE may return non-200 (DDoS-Guard challenge, soft-404) but still serve a page body; we scan
     * the body regardless of status. Note: DDoS-Guard can block non-browser clients (403 from a
     * laptop); the TV gets a body more often, so detection there may succeed where curl fails.
     */
    private fun resolveVoe(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, headers = mapOf("User-Agent" to mobileUA))
        val text = res.text
        var found = false

        // --- Pattern 1: voe_decode path ---
        // Redirect loop: while 'const currentUrl' in html, follow window.location.href.
        var currentUrl = url
        var currentText = text
        var redirects = 0
        while (currentText.contains("const currentUrl") && redirects < 5) {
            val r = Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(currentText)
            if (r == null) break
            currentUrl = r.groupValues[1]
            val r2 = httpGet(currentUrl, headers = mapOf("User-Agent" to mobileUA))
            currentText = r2.text
            redirects++
        }
        if (redirects > 0) DebugLog.t(dbg, "resolveVoe: followed $redirects redirects to $currentUrl")

        val p1 = Regex("""json">\["([^"]+)"\]</script>\s*<script\s*src="([^"]+)""")
        val m1 = p1.find(currentText)
        if (m1 != null) {
            val ct = m1.groupValues[1]
            val jsUrlRaw = m1.groupValues[2]
            val jsUrl = if (jsUrlRaw.startsWith("http")) jsUrlRaw else "$currentUrl/$jsUrlRaw".replace("//", "/")
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
                    // Prefer m3u8 (source), then mp4 (file), then direct_access_url.
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

        // --- Fallback: direct hls/mp4 URLs in the page (for VOE variants that embed plainly) ---
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

    /**
     * voe_decode: reverse VOE's obfuscation. Ported from voesx.py voe_decode().
     * 1. Parse LUT: luts like "['xx','yy','zz']" -> split inner items, escape regex specials.
     * 2. ROT-shift ct letters: uppercase (x-52)%26+65, lowercase (x-84)%26+97.
     * 3. Remove each LUT element from the shifted text (regex replace with "").
     * 4. base64-decode.
     * 5. Caesar -3 (each char -3).
     * 6. base64-decode reversed.
     * 7. JSON parse -> {file, source, direct_access_url, captions}.
     */
    private fun voeDecode(ct: String, luts: String): org.json.JSONObject {
        // 1. Parse LUT items: strip leading "[ '" and trailing "' ]", split by "','".
        val inner = luts.substringAfter("['").substringBefore("']")
        val lutItems = if (inner.isNotEmpty()) inner.split("','") else emptyList()
        // Escape regex special chars in each LUT item.
        val specials = setOf('.', '*', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '\\')

        // 2. ROT-shift ct letters.
        val shifted = StringBuilder(ct.length)
        for (ch in ct) {
            val x = ch.toInt()
            val nx = when {
                x in 65..90 -> (x - 52).rem(26) + 65   // uppercase
                x in 97..122 -> (x - 84).rem(26) + 97   // lowercase
                else -> x
            }
            shifted.append(nx.toChar())
        }
        var txt = shifted.toString()

        // 3. Remove each LUT element (escaped) from the shifted text.
        for (item in lutItems) {
            if (item.isEmpty()) continue
            val escaped = buildString {
                for (c in item) if (c in specials) { append('\\'); append(c) } else append(c)
            }
            txt = try { Regex(escaped).replace(txt, "") } catch (_: Throwable) { txt }
        }

        // 4. base64-decode.
        val step4 = try { base64Decode(txt) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: base64 step1 failed: ${t.message}")
            return org.json.JSONObject()
        }
        // 5. Caesar -3.
        val step5 = String(CharArray(step4.length) { (step4[it].toInt() - 3).toChar() })
        // 6. base64-decode reversed.
        val reversed = step5.reversed()
        val step6 = try { base64Decode(reversed) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: base64 step2 failed: ${t.message}")
            return org.json.JSONObject()
        }
        // 7. JSON parse.
        return try { org.json.JSONObject(step6) } catch (t: Throwable) {
            DebugLog.w(dbg, "voeDecode: JSON parse failed: ${t.message}")
            org.json.JSONObject()
        }
    }

    private fun base64Decode(s: String): String {
        return try {
            String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (t: Throwable) {
            throw t
        }
    }

    /**
     * firestream.to extractor, ported from Gujal00/ResolveURL firestream.py.
     * The embed page at /e/<mediaId> contains a hidden element `id="token-blob">...</token-blob>`.
     * POST /api/videos/<mediaId>/resolve with JSON {"blob":"<token>"} + Referer/Origin headers ->
     * JSON {signedVideoUrl, signedVideoSdUrl}. signedVideoUrl is a direct m3u8 URL.
     * Verified live (Aug 2026, Inception): token-blob -> POST -> signedVideoUrl (video.m3u8).
     */
    private fun resolveFirestream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaId = url.substringAfterLast("/e/").substringBefore("?").trim()
        if (mediaId.isEmpty()) {
            DebugLog.w(dbg, "resolveFirestream: could not extract mediaId from $url")
            return false
        }
        val res = httpGet(url, headers = mapOf("User-Agent" to mobileUA))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveFirestream: GET $url -> HTTP ${res.code}")
            return false
        }
        val blob = try {
            Regex("""id="token-blob"[^>]+>([^<]+)""").find(res.text)?.groupValues?.get(1)
        } catch (t: Throwable) { null }
        if (blob.isNullOrBlank()) {
            DebugLog.w(dbg, "resolveFirestream: no token-blob found in embed page")
            return false
        }
        DebugLog.t(dbg, "resolveFirestream: token-blob found (len=${blob.length}), mediaId=$mediaId")
        val apiUrl = "https://firestream.to/api/videos/$mediaId/resolve"
        val body = """{"blob":"$blob"}"""
        val apiRes = httpPost(apiUrl, body, headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to "https://firestream.to/",
            "Origin" to "https://firestream.to",
            "User-Agent" to mobileUA
        ))
        if (apiRes.code !in 200..299) {
            DebugLog.w(dbg, "resolveFirestream: POST $apiUrl -> HTTP ${apiRes.code}")
            return false
        }
        val streamUrl = try {
            org.json.JSONObject(apiRes.text).optString("signedVideoUrl", "")
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveFirestream: JSON parse failed: ${t.message}")
            return false
        }
        if (!streamUrl.startsWith("http")) {
            DebugLog.w(dbg, "resolveFirestream: no signedVideoUrl in response")
            return false
        }
        DebugLog.t(dbg, "resolveFirestream: signedVideoUrl=$streamUrl")
        emitLink("Firestream", streamUrl, "https://firestream.to/", callback)
        return true
    }

    /**
     * Best-effort generic resolver for hoster embed pages that no hoster-specific extractor
     * covers. Fetches the embed page and scans for direct video URLs (mp4, m3u8) in common
     * patterns: JSON sources arrays, hls.js player config, source tags.
     */
    private fun genericResolve(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = httpGet(url, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to mobileUA))
            val text = res.text
            val base = url.substringBeforeLast("/")
            val root = url.substringBefore("/").dropLastWhile { it != '/' }
            var found = false

            // 1. Direct m3u8 URLs
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(text).forEach { m ->
                emitLink("Generic", m.groupValues[1], callback)
                found = true
            }
            // 2. Direct mp4 URLs
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(text).forEach { m ->
                emitLink("Generic", m.groupValues[1], callback)
                found = true
            }
            // 3. Relative m3u8/mp4 paths resolved against base (balanced regex).
            Regex("""["'(]([^"'\s)]+\.(?:m3u8|mp4)[^"'\s)]*)["')]""").findAll(text).forEach { m ->
                val p = m.groupValues[1]
                val abs = when {
                    p.startsWith("http") -> p
                    p.startsWith("/") -> root + p
                    else -> "$base/$p"
                }
                emitLink("Generic", abs, callback)
                found = true
            }
            DebugLog.t(dbg, "genericResolve: GET $url -> ${res.code}, len=${text.length}, found=$found")
            found
        } catch (t: Throwable) {
            DebugLog.w(dbg, "genericResolve: GET $url threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    private fun emitLink(source: String, url: String, callback: (ExtractorLink) -> Unit) {
        emitLink(source, url, mainUrl, callback)
    }

    private fun emitLink(source: String, url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val isM3u8 = url.contains(".m3u8")
            val quality = detectQuality(url, isM3u8)
            DebugLog.t(dbg, "emitLink: source=$source url=$url quality=$quality isM3u8=$isM3u8 referer=$referer")
            // Use the PRIMARY constructor (all 9 positional args, no default-args) - R8 strips the
            // synthetic DefaultConstructorMarker constructor (like MainPageData, Erkenntnis #6),
            // so named-arg/default-arg construction throws NoSuchMethodError at runtime.
            val link = ExtractorLink(
                source,                                                          // source
                source,                                                          // name
                url,                                                             // url
                referer,                                                         // referer
                quality,                                                         // quality (detected, see detectQuality)
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
     * Detect the real video quality so ARVIO shows "1080p"/"720p"/"4K" instead of "Unknown" and so
     * ARVIO's Auto-Play scores the stream (qualityScoreForAutoPlay: 2160/4K->4, 1080p->3, 720p->2,
     * 480p->1, else 0). With Qualities.Unknown (400 -> "" -> "Unknown") the Auto-Play score is 0,
     * so ARVIO's 2s Auto-Play wait ignores the stream.
     *
     * For m3u8: fetch the manifest (Range 0-4096 to keep it small) and parse
     * #EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=1920x1080. For mp4/other: default P720 (mp4 is
     * usually DVD/HD). All non-suspend (java.net) and fully guarded - on any error default to P720
     * (better than Unknown, never crashes). Qualities enum values (verified from cloudstream3):
     * Unknown=400, P480=480, P720=720, P1080=1080, P2160=2160.
     */
    private fun detectQuality(url: String, isM3u8: Boolean): Int {
        if (!isM3u8) return Qualities.P720.value
        return try {
            val res = httpGet(url, headers = mapOf("Range" to "bytes=0-8192"))
            if (res.code !in 200..299 && res.code != 206) return Qualities.P720.value
            // A master.m3u8 may list MULTIPLE variants (480p/720p/1080p/4K). Take the HIGHEST
            // resolution available (not the first), so we report the best quality the stream offers.
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

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
