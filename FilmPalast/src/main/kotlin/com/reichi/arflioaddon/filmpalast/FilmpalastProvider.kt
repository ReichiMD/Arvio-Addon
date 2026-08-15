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
            conn = (java.net.URL(fullUrl).openConnection() as java.net.HttpURLConnection).apply {
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
        } catch (e: Exception) {
            DebugLog.w(dbg, "httpGet: $fullUrl threw ${e.javaClass.simpleName}: ${e.message}")
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
        } catch (e: Exception) {
            DebugLog.e(dbg, "loadLinks: could not parse movie links JSON: ${e.message}")
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
        } catch (e: Throwable) {
            DebugLog.e(dbg, "load() threw ${e.javaClass.simpleName}: ${e.message}", e)
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

    private fun fetchTmdbMeta(tmdbId: Int, isTv: Boolean): TmdbMeta? {
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
            TmdbMeta(obj.optInt("id", -1).takeIf { it >= 0 }, title.trim(), year)
        } catch (e: Exception) {
            DebugLog.e(dbg, "fetchTmdbMeta: request threw ${e.javaClass.simpleName}: ${e.message}")
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
        } catch (e: Exception) {
            DebugLog.e(dbg, "searchFilmpalast: GET threw ${e.javaClass.simpleName}: ${e.message}")
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
        return results.filter { entry ->
            // Strip trailing S\dE\d / season markers when comparing the base name.
            val base = entry.title.replace(Regex("""\s*[Ss]\d{1,2}[Ee]\d{1,3}.*$"""), "").trim()
            val baseNorm = norm(base)
            val typeOk = if (isTv) entry.type == TvType.TvSeries else entry.type == TvType.Movie
            typeOk && (baseNorm == titleNorm || baseNorm.contains(titleNorm) || titleNorm.contains(baseNorm))
        }
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
        } catch (e: Exception) {
            DebugLog.e(dbg, "buildMovieResponse: threw ${e.javaClass.simpleName}: ${e.message}")
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
        } catch (e: Exception) {
            DebugLog.e(dbg, "buildSeriesResponse: threw ${e.javaClass.simpleName}: ${e.message}")
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
     * like VOE, plus a generic page-scrape for direct mp4/m3u8 URLs). We do NOT call
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
                try { parseLinksJson(data) } catch (e: Exception) {
                    DebugLog.e(dbg, "loadLinks: could not parse movie links JSON: ${e.message}")
                    emptyList()
                }
            }
            data.startsWith("http") -> {
                try {
                    DebugLog.t(dbg, "loadLinks: series path, fetching episode page $data")
                    val res = httpGet(data)
                    DebugLog.t(dbg, "loadLinks: episode page -> ${res.code}")
                    collectHosterLinks(Jsoup.parse(res.text).select("#content"))
                } catch (e: Exception) {
                    DebugLog.e(dbg, "loadLinks: fetching episode page threw ${e.javaClass.simpleName}: ${e.message}")
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

        var any = false
        for (link in links) {
            val fixed = fixUrlNull(link)
            if (fixed == null) {
                DebugLog.w(dbg, "loadLinks: fixUrlNull null for '$link' -> skip")
                continue
            }
            // NOTE: cloudstream3's loadExtractor is an ARVIO-provided suspend function, and like
            // app.get (Erkenntnis #13/#14) it is broken for external .cs3 plugins: the coroutine
            // resume returns a stray obfuscated object (k7.a / d7.d0) instead of Boolean, throwing
            // ClassCastException, and callback emissions never reach ARVIO ("0 links collected"
            // despite any=true). So we resolve hosters ourselves with java.net + jsoup (no suspend,
            // no ARVIO coroutine involvement) via resolveHost.
            val found = resolveHost(fixed, callback)
            DebugLog.t(dbg, "loadLinks: resolveHost('$fixed') -> found=$found")
            any = found || any
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
                // Add more hoster-specific extractors here as needed (odysseusa, vidsonic, ...).
                else -> genericResolve(url, callback)
            }
        } catch (e: Exception) {
            DebugLog.w(dbg, "resolveHost: '$url' threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * VOE (voe.sx and mirrors) extractor. The embed page contains an obfuscated JS blob; the
     * video URL (m3u8) is stored in a JSON-ish `{"hls":"...","mp4":{"...":"..."}}` structure or
     * a `var ... = [...];` array, often inside an eval'd / p.a.c.k.e.r'd script. We look for the
     * hls/mp4 URLs directly in the page text and in base64-decoded / eval-unpacked script bodies.
     */
    private fun resolveVoe(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to mobileUA))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "resolveVoe: GET $url -> HTTP ${res.code}")
            return false
        }
        val text = res.text
        var found = false

        // 1. Direct hls/mp4 URLs in the page (newer VOE pages embed them in a JSON-ish blob).
        //    Patterns: "hls":"https://...m3u8"  or  sources:[{"file":"...m3u8"}]  or  "src":"...mp4"
        val urlPatterns = listOf(
            Regex(""""hls"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
            Regex(""""mp4"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
            Regex(""""file"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""""),
            Regex(""""src"\s*:\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""""),
            Regex("""'(https?://[^']+\.m3u8[^']*)'"""),
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""")
        )
        for (p in urlPatterns) {
            p.findAll(text).forEach { m ->
                emitLink("VOE", m.groupValues[1], callback)
                found = true
            }
            if (found) break
        }

        // 2. P.a.c.k.e.r'd script: `eval(function(p,a,c,k,e,d){...}('...',..,..))` -> unpack.
        if (!found) {
            val packer = Regex("""eval\(function\(p,a,c,k,e,d\)\{[^}]*\}\('([^']+)'[^)]*\)\)""").find(text)
            if (packer != null) {
                val unpacked = unpackPacker(packer.groupValues[1])
                for (p in urlPatterns) {
                    p.findAll(unpacked).forEach { m ->
                        emitLink("VOE", m.groupValues[1], callback)
                        found = true
                    }
                    if (found) break
                }
            }
        }

        // 3. Base64-encoded body: `let xxx = "<base64>";` then `eval(atob(xxx))` or similar.
        if (!found) {
            val b64 = Regex("""['"]([A-Za-z0-9+/=]{200,})['"]""").findAll(text).toList()
            for (m in b64) {
                val decoded = try { String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { continue }
                for (p in urlPatterns) {
                    p.findAll(decoded).forEach { mm ->
                        emitLink("VOE", mm.groupValues[1], callback)
                        found = true
                    }
                    if (found) break
                }
                if (found) break
            }
        }

        DebugLog.t(dbg, "resolveVoe: GET $url -> ${res.code}, len=${text.length}, found=$found")
        return found
    }

    /** Minimal dean-edwards packer payload unpacker (`function(p,a,c,k,e,d)`). */
    private fun unpackPacker(p: String): String {
        return try {
            val payload = Regex("""'([^']+)'\.split\('\|'\)""").find(p)?.groupValues?.get(1) ?: return p
            val words = payload.split("|")
            val sb = StringBuilder()
            // Replace \w+ tokens: the packer replaces each token by words[token-as-base36].
            Regex("""\b(\w+)\b""").findAll(p).forEach { m ->
                val idx = m.groupValues[1].toIntOrNull(36)
                if (idx != null && idx in words.indices && words[idx].isNotEmpty()) sb.append(words[idx])
                else sb.append(m.groupValues[1])
            }
            sb.toString()
        } catch (_: Exception) {
            p
        }
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
        } catch (e: Exception) {
            DebugLog.w(dbg, "genericResolve: GET $url threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun emitLink(source: String, url: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains(".m3u8")
        callback.invoke(
            ExtractorLink(
                source = source,
                name = source,
                url = url,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        )
    }

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
