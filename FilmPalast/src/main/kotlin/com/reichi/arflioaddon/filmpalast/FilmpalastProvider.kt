package com.reichi.arflioaddon.filmpalast

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.withTimeoutOrNull
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
 * page and resolves each registered hoster via loadExtractor.
 */
class FilmpalastProvider : TmdbProvider() {
    override var mainUrl = "https://filmpalast.to"
    override var name = "Filmpalast"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "Filmpalast"
    // Per-network-call timeout. ARVIO's scraper has a total timeout that covers load() +
    // loadLinks(); if a single app.get() hangs, it would consume the whole budget and loadLinks
    // would never run. Keep each call well under ARVIO's total budget.
    private val NET_TIMEOUT_MS = 8000L

    data class LoadData(val links: List<String> = emptyList())

    // search() is still implemented so the classic CloudStream app (and ARVIOs search path
    // fallback) can use it. Returns the Filmpalast stream URL as the SearchResponse url.
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/title/$query").document
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
        if (url.trimStart().startsWith("{")) {
            return try {
                val parsed = parseJson<TmdbInput>(url)
                val id = parsed.id ?: return null
                val isTv = parsed.type.equals("tv", ignoreCase = true)
                id to isTv
            } catch (_: Exception) { null }
        }
        // URL form: https://www.themoviedb.org/tv/123 or /movie/123
        val regex = Regex("""themoviedb\.org/(movie|tv)/(\d+)""")
        val m = regex.find(url) ?: return null
        val isTv = m.groupValues[1].equals("tv", ignoreCase = true)
        return m.groupValues[2].toIntOrNull()?.let { it to isTv }
    }

    data class TmdbInput(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("type") val type: String? = null
    )

    // ---- TMDB metadata (lightweight, only what we need for matching) ----

    data class TmdbMeta(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,        // movies
        @JsonProperty("name") val name: String? = null,           // tv
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name") val originalName: String? = null,
        @JsonProperty("release_date") val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null
    ) {
        val displayTitle: String get() = (title ?: name ?: originalTitle ?: originalName ?: "").trim()
        val year: Int? get() = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    }

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"

    private suspend fun fetchTmdbMeta(tmdbId: Int, isTv: Boolean): TmdbMeta? {
        val path = if (isTv) "/tv/$tmdbId" else "/movie/$tmdbId"
        val full = "$tmdbApiUrl$path"
        return try {
            val res = withTimeoutOrNull(NET_TIMEOUT_MS) {
                app.get(full, params = mapOf("api_key" to tmdbApiKey, "language" to "de-DE"))
            }
            if (res == null) {
                DebugLog.e(dbg, "fetchTmdbMeta: TIMED OUT after ${NET_TIMEOUT_MS}ms (network hang?)")
                return null
            }
            DebugLog.t(dbg, "fetchTmdbMeta: GET $full -> ${res.code}")
            parseJson<TmdbMeta>(res.text)
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

    private suspend fun searchFilmpalast(query: String): List<FilmpalastEntry> {
        val searchUrl = "$mainUrl/search/title/${query.encode()}"
        return try {
            val res = withTimeoutOrNull(NET_TIMEOUT_MS) { app.get(searchUrl) }
            if (res == null) {
                DebugLog.e(dbg, "searchFilmpalast: TIMED OUT after ${NET_TIMEOUT_MS}ms (network hang?)")
                return emptyList()
            }
            DebugLog.t(dbg, "searchFilmpalast: GET $searchUrl -> ${res.code}")
            val document = res.document
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

    private suspend fun buildMovieResponse(entry: FilmpalastEntry, meta: TmdbMeta): LoadResponse? {
        return try {
            DebugLog.t(dbg, "buildMovieResponse: GET ${entry.url}")
            val res = app.get(entry.url)
            DebugLog.t(dbg, "buildMovieResponse: -> ${res.code}")
            val doc = res.document.select("#content")
            val detailTitle = doc.select("h2.rb.bgDark").text().ifEmpty { meta.displayTitle }
            val imagePath = doc.select(".detail.rb img.cover2").attr("src")
            val description = doc.select("span[itemprop=description]").text()
            val links = collectHosterLinks(doc)
            DebugLog.t(dbg, "buildMovieResponse: collected ${links.size} hoster links")
            links.take(20).forEach { DebugLog.t(dbg, "  hoster link: $it") }

            newMovieLoadResponse(detailTitle, entry.url, TvType.Movie, LoadData(links).toJson()) {
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
     * For each hoster link we first try cloudstream3's loadExtractor (matches built-in
     * extractors by domain: Voe, Firestream, FileMoonSx, Supervideo, VidHidePro, ...).
     * If no registered extractor handles the domain (Filmpalast rotates obscure hosters
     * like vidaraa.cc, vidsonic.net, jabturfembitter.com), we fall back to a generic
     * page-scrape that looks for direct mp4/m3u8 URLs in the embed page.
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
                try { parseJson<LoadData>(data).links } catch (e: Exception) {
                    DebugLog.e(dbg, "loadLinks: could not parse movie LoadData JSON: ${e.message}")
                    emptyList()
                }
            }
            data.startsWith("http") -> {
                try {
                    DebugLog.t(dbg, "loadLinks: series path, fetching episode page $data")
                    val res = app.get(data)
                    DebugLog.t(dbg, "loadLinks: episode page -> ${res.code}")
                    collectHosterLinks(res.document.select("#content"))
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
            try {
                val matched = loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                DebugLog.t(dbg, "loadLinks: loadExtractor('$fixed') -> matched=$matched")
                if (matched) {
                    any = true
                } else {
                    // No registered extractor for this domain: generic fallback.
                    val fallback = genericResolve(fixed, "$mainUrl/", callback)
                    DebugLog.t(dbg, "loadLinks: genericResolve('$fixed') -> found=$fallback")
                    any = fallback || any
                }
            } catch (e: Exception) {
                DebugLog.w(dbg, "loadLinks: loadExtractor('$fixed') threw ${e.javaClass.simpleName}: ${e.message} -> trying generic")
                any = genericResolve(fixed, "$mainUrl/", callback) || any
            }
        }
        DebugLog.t(dbg, "loadLinks: DONE, any=$any (any=true means at least one source emitted)")
        return any
    }

    /**
     * Best-effort generic resolver for hoster embed pages that no cloudstream3 extractor
     * covers. Fetches the embed page and scans for direct video URLs (mp4, m3u8) in
     * common patterns: JSON sources arrays, hls.js player config, source tags.
     */
    private suspend fun genericResolve(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val res = app.get(
                url,
                headers = mapOf("Referer" to referer, "User-Agent" to mobileUA)
            )
            val text = res.text
            val base = url.substringBeforeLast("/")
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
            // 3. Relative m3u8/mp4 paths resolved against base
            Regex("""["'(]((?:/[^"')\s]+|\.\./[^"')\s]+|[^"')\s]+\.(?:m3u8|mp4))["')]""").findAll(text).forEach { m ->
                val p = m.groupValues[1]
                if (p.endsWith(".m3u8") || p.endsWith(".mp4")) {
                    val abs = if (p.startsWith("http")) p else if (p.startsWith("/")) url.substringBefore("/").dropLastWhile { it != '/' } + p else "$base/$p"
                    emitLink("Generic", abs, callback)
                    found = true
                }
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
