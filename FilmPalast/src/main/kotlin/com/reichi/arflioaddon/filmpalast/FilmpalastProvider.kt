package com.reichi.arflioaddon.filmpalast

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
    override val hasMainPage = true

    override val useMetaLoadResponse = false

    override val mainPage = mainPageOf(
        "" to "Neu",
        "/movies/top" to "Filme",
        "/serien/view" to "Serien"
    )

    data class LoadData(val links: List<String> = emptyList())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/${page}").document
        val results = document.select("#content article.liste").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request, results, hasNext = true)
    }

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
        val (tmdbId, isTv) = parseTmdbInput(url) ?: return null

        val meta = fetchTmdbMeta(tmdbId, isTv) ?: return null
        val title = meta.displayTitle
        val year = meta.year

        // Filmpalast search returns episode URLs for series and movie pages for movies.
        val searchResults = searchFilmpalast(title)
        val matches = matchResults(searchResults, title, year, isTv)
        if (matches.isEmpty()) return null

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
        return try {
            parseJson<TmdbMeta>(
                app.get("$tmdbApiUrl$path", params = mapOf("api_key" to tmdbApiKey, "language" to "de-DE")).text
            )
        } catch (_: Exception) { null }
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
        val document = app.get("$mainUrl/search/title/${query.encode()}").document
        return document.select("#content article.liste, #content .glowliste").mapNotNull { it.toSearchEntry() }
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
        val doc = app.get(entry.url).document.select("#content")
        val detailTitle = doc.select("h2.rb.bgDark").text().ifEmpty { meta.displayTitle }
        val imagePath = doc.select(".detail.rb img.cover2").attr("src")
        val description = doc.select("span[itemprop=description]").text()
        val links = collectHosterLinks(doc)

        return newMovieLoadResponse(detailTitle, entry.url, TvType.Movie, LoadData(links).toJson()) {
            this.posterUrl = fixUrl(imagePath)
            this.plot = description
            this.year = meta.year
        }
    }
    private suspend fun buildSeriesResponse(
        episodes: List<FilmpalastEntry>,
        meta: TmdbMeta
    ): LoadResponse? {
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
        if (epList.isEmpty()) return null

        return newTvSeriesLoadResponse(meta.displayTitle, mainUrl, TvType.TvSeries, epList) {
            this.year = meta.year
            this.plot = ""
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
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links: List<String> = when {
            data.trimStart().startsWith("{") -> {
                try { parseJson<LoadData>(data).links } catch (_: Exception) { emptyList() }
            }
            data.startsWith("http") -> {
                // Series: fetch the episode stream page and collect hoster links.
                val doc = app.get(data).document.select("#content")
                collectHosterLinks(doc)
            }
            else -> emptyList()
        }
        if (links.isEmpty()) return false

        var any = false
        for (link in links) {
            val fixed = fixUrlNull(link) ?: continue
            try {
                loadExtractor(fixed, "$mainUrl/", subtitleCallback, callback)
                any = true
            } catch (_: Exception) { /* skip broken hoster */ }
        }
        return any
    }
}
