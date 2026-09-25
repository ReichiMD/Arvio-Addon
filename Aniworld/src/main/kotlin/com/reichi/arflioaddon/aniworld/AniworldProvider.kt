package com.reichi.arflioaddon.aniworld

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * AniWorld.to provider for ARVIO (Buero docs/themen/70), built like SerienstreamProvider: a
 * TmdbProvider, so ARVIO calls load({"id":<tmdbId>,"type":"tv"}) directly.
 *
 * AniWorld still has the OLD s.to page form (read from Bnyro/GermanProviders' Aniworld plugin,
 * 08/2026 - this session cannot open aniworld.to itself):
 *  - Search: POST /ajax/search keyword=<q> -> JSON [{title, link:"/anime/stream/<slug>"}]
 *  - Series page: /anime/stream/<slug> -> #stream > ul:first-child li a -> seasons (staffel-N, "Filme")
 *  - Season page: table.seasonEpisodesList tbody tr -> a href (episode page) +
 *    meta[itemprop=episodeNumber] + .seasonEpisodeTitle
 *  - Episode page: div.hosterSiteVideo ul li with data-lang-key + data-link-target="/redirect/<id>"
 *    + h4 (hoster name); language names in div.changeLanguageBox img[data-lang-key] title.
 *
 * Getting through: java.net first (cheap). When a browser check stands in front of a page or of
 * /redirect/<id>, a hidden WebView does it (AniworldGate), as Serienstream's TurnstileSolver does.
 * Everything that matters is logged - the first test on the device shows what to adjust.
 * Hosters are tried in page order and the first that works ends the search (like Serienstream;
 * language order is deliberately unchanged, docs/70 E1).
 */
class AniworldProvider : TmdbProvider() {
    override var mainUrl = "https://aniworld.to"
    override var name = "AniWorld"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "AniWorld"
    private val NET_TIMEOUT_MS = 8000L

    private data class HttpResp(val code: Int, val text: String, val url: String)

    // One User-Agent for everything: browser-check cookies are bound to it (AniworldSession).
    private val mobileUA = AniworldSession.USER_AGENT

    // ---- HTTP (java.net; aniworld.to requests carry the session cookies) ----

    private fun httpGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        referer: String = ""
    ): HttpResp {
        val fullUrl = if (params.isEmpty()) url else {
            val qs = params.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
            "$url?$qs"
        }
        val merged = HashMap<String, String>()
        merged["User-Agent"] = mobileUA
        if (referer.isNotEmpty()) merged["Referer"] = referer
        headers.forEach { (k, v) -> merged[k] = v }
        return doRequest(fullUrl, "GET", null, merged)
    }

    private fun doRequest(url: String, method: String, body: String?, headers: Map<String, String>): HttpResp {
        var conn: HttpURLConnection? = null
        val own = AniworldSession.isAniworld(url)
        return try {
            conn = openDohConnection(url).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = method
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (own) {
                    val c = AniworldSession.cookieHeader()
                    if (c.isNotEmpty()) setRequestProperty("Cookie", c)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (own) AniworldSession.capture(conn)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val finalUrl = conn.url.toString()
            DebugLog.t(dbg, "doRequest: $method $url -> $code (${text.length}B) final=$finalUrl")
            HttpResp(code, text, finalUrl)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "doRequest: $method $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "", url)
        } finally {
            conn?.disconnect()
        }
    }

    private val CHECK_TITLE = Regex("""<title>\s*(Just a moment|Einen Moment|DDoS-Guard|Attention Required|Access denied)""", RegexOption.IGNORE_CASE)

    /** true when the answer is a browser check instead of the real aniworld.to page. */
    private fun looksLikeCheck(r: HttpResp): Boolean =
        r.code !in 200..299 || CHECK_TITLE.containsMatchIn(r.text) ||
            (r.text.length < 30000 && (r.text.contains("ddos-guard", true) || r.text.contains("challenge-platform", true)))

    private fun titleOf(html: String): String =
        Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()?.take(80) ?: "-"

    /** An aniworld.to page: java.net, and the hidden WebView if a browser check is in the way. */
    private fun aniworldPage(url: String): HttpResp {
        val r = httpGet(url)
        if (!looksLikeCheck(r)) return r
        DebugLog.w(dbg, "page: $url -> HTTP ${r.code} (${r.text.length}B, title='${titleOf(r.text)}') -> hidden browser")
        val html = AniworldGate.fetchPage(url)
        if (html == null) {
            DebugLog.w(dbg, "page: hidden browser got no page either for $url")
            return r
        }
        DebugLog.t(dbg, "page: hidden browser -> ${html.length} chars, title='${titleOf(html)}', cookies now ${AniworldSession.cookieNames()}")
        return HttpResp(200, html, url)
    }

    // ---- TmdbProvider load path ----

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

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
        if (!isTv) {
            // AniWorld films sit in a "Filme" section per series - not built yet (docs/70).
            DebugLog.t(dbg, "load: movie type, AniWorld plugin handles series only for now -> null")
            return null
        }
        val meta = fetchTmdbMeta(tmdbId) ?: run {
            DebugLog.e(dbg, "load: TMDB metadata fetch failed for tmdbId=$tmdbId")
            return null
        }
        DebugLog.t(dbg, "load: TMDB $tmdbId -> titles=${meta.titles} year=${meta.year} seasons=${meta.seasons}")
        val seriesUrl = findSeries(meta)
        if (seriesUrl == null) {
            DebugLog.w(dbg, "load: series not found on AniWorld -> null")
            return null
        }
        return buildSeriesResponse(seriesUrl, meta)
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
                val m = Regex("""themoviedb\.org/(movie|tv)/(\d+)""").find(url) ?: return null
                val isTv = m.groupValues[1].equals("tv", ignoreCase = true)
                m.groupValues[2].toIntOrNull()?.let { it to isTv }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** titles: German, English and original name (anime are often listed under another name, T3). */
    private class TmdbMeta(val titles: List<String>, val year: Int?, val seasons: Map<Int, Int>)

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbCache = ConcurrentHashMap<Int, TmdbMeta>()

    /** Episode page URL -> (found at, links). See loadLinks. */
    private val linkCache = ConcurrentHashMap<String, Pair<Long, List<ExtractorLink>>>()
    private val LINK_CACHE_MS = 10 * 60 * 1000L

    private fun fetchTmdbMeta(tmdbId: Int): TmdbMeta? {
        tmdbCache[tmdbId]?.let { return it }
        return try {
            val de = httpGet("$tmdbApiUrl/tv/$tmdbId", params = mapOf("api_key" to tmdbApiKey, "language" to "de-DE"))
            if (de.code !in 200..299) {
                DebugLog.e(dbg, "fetchTmdbMeta: tv/$tmdbId -> HTTP ${de.code}")
                return null
            }
            val obj = JSONObject(de.text)
            val titles = ArrayList<String>()
            fun add(t: String) { val x = t.trim(); if (x.isNotEmpty() && titles.none { it.equals(x, true) }) titles.add(x) }
            add(obj.optString("name", ""))
            val en = httpGet("$tmdbApiUrl/tv/$tmdbId", params = mapOf("api_key" to tmdbApiKey, "language" to "en-US"))
            if (en.code in 200..299) add(try { JSONObject(en.text).optString("name", "") } catch (_: Throwable) { "" })
            add(obj.optString("original_name", ""))
            val year = obj.optString("first_air_date", "").take(4).toIntOrNull()
            val seasons = LinkedHashMap<Int, Int>()
            val arr = obj.optJSONArray("seasons")
            if (arr != null) for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val n = s.optInt("season_number", -1)
                if (n > 0) seasons[n] = s.optInt("episode_count", 0)
            }
            val meta = TmdbMeta(titles, year, seasons)
            tmdbCache[tmdbId] = meta
            meta
        } catch (t: Throwable) {
            DebugLog.e(dbg, "fetchTmdbMeta: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ---- AniWorld search & series ----

    private fun norm(s: String): String =
        s.lowercase().replace('×', 'x').replace(Regex("<[^>]+>"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    private class Candidate(val url: String, val title: String)

    /** POST /ajax/search. On a browser check: pass it once in the hidden WebView, then ask again. */
    private fun ajaxSearch(query: String): List<Candidate>? {
        var r = ajaxSearchOnce(query)
        if (r == null) {
            DebugLog.w(dbg, "search: no JSON answer -> passing the browser check on the start page, then again")
            AniworldGate.fetchPage("$mainUrl/")
            r = ajaxSearchOnce(query)
        }
        return r
    }

    private fun ajaxSearchOnce(query: String): List<Candidate>? {
        val headers = HashMap<String, String>()
        headers["User-Agent"] = mobileUA
        headers["X-Requested-With"] = "XMLHttpRequest"
        headers["Accept"] = "application/json, text/javascript, */*; q=0.01"
        headers["Referer"] = "$mainUrl/search"
        headers["Origin"] = mainUrl
        val res = doRequest("$mainUrl/ajax/search", "POST", "keyword=" + URLEncoder.encode(query, "UTF-8"), headers)
        val text = res.text.trim()
        if (res.code !in 200..299 || !text.startsWith("[")) {
            DebugLog.w(dbg, "search '$query': HTTP ${res.code}, answer starts '${text.take(120).replace('\n', ' ')}'")
            return null
        }
        val arr = try { JSONArray(text) } catch (_: Throwable) { return null }
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val link = o.optString("link", "")
            // Only series pages, not single episodes/seasons or other result types.
            if (!link.contains("/anime/stream/") || link.contains("episode-") || link.contains("staffel-") || link.contains("/filme")) continue
            val abs = if (link.startsWith("http")) link else "$mainUrl$link"
            out.add(Candidate(abs, o.optString("title", "").replace(Regex("</?em>"), "")))
        }
        DebugLog.t(dbg, "search '$query': ${arr.length()} results, ${out.size} series")
        out.take(10).forEach { DebugLog.t(dbg, "  candidate: ${it.title} | ${it.url}") }
        return out
    }

    /**
     * The AniWorld series page for the TMDB show: search every TMDB title, take exact name matches
     * (several -> the one whose start year matches TMDB), else a result that contains the name.
     */
    private fun findSeries(meta: TmdbMeta): String? {
        var loose: Candidate? = null
        for (title in meta.titles) {
            val cands = ajaxSearch(title) ?: continue
            val t = norm(title)
            val exact = cands.filter { norm(it.title) == t || norm(it.url.substringAfterLast('/').replace('-', ' ')) == t }
            if (exact.size == 1) {
                DebugLog.t(dbg, "findSeries: '$title' -> exact ${exact[0].url}")
                return exact[0].url
            }
            if (exact.size > 1) {
                val byYear = exact.take(4).firstOrNull { c -> seriesYear(c.url) == meta.year }
                DebugLog.t(dbg, "findSeries: '$title' -> ${exact.size} exact matches, year ${meta.year} -> ${byYear?.url}")
                return (byYear ?: exact[0]).url
            }
            if (loose == null && t.length >= 3) {
                loose = cands.firstOrNull { norm(it.title).contains(t) || t.contains(norm(it.title)) }
            }
        }
        // Last resort: AniWorld's slugs are the lower-case title with dashes.
        for (title in meta.titles) {
            val slug = norm(title).replace(' ', '-')
            if (slug.length < 3) continue
            val r = aniworldPage("$mainUrl/anime/stream/$slug")
            if (r.code in 200..299 && (r.text.contains("seasonEpisodesList") || r.text.contains("series-title"))) {
                DebugLog.t(dbg, "findSeries: guessed slug '$slug' exists")
                return "$mainUrl/anime/stream/$slug"
            }
        }
        DebugLog.t(dbg, "findSeries: no exact match, loose match -> ${loose?.title} ${loose?.url}")
        return loose?.url
    }

    private fun seriesYear(url: String): Int? {
        val r = aniworldPage(url)
        val y = Jsoup.parse(r.text).selectFirst("span[itemprop=startDate] a, span[itemprop=startDate]")?.text()?.trim()?.take(4)?.toIntOrNull()
        DebugLog.t(dbg, "seriesYear: $url -> $y")
        return y
    }

    /**
     * Series page -> seasons -> episodes. Season pages are fetched three at a time (long anime have
     * 20+ seasons). If TMDB counts the show as ONE season but AniWorld splits it (T3), the AniWorld
     * episodes are numbered through into season 1, so ARVIO's S1Exx finds the right episode.
     */
    private suspend fun buildSeriesResponse(seriesUrl: String, meta: TmdbMeta): LoadResponse? {
        return try {
            val seriesRes = aniworldPage(seriesUrl)
            if (seriesRes.code !in 200..299) {
                DebugLog.w(dbg, "buildSeriesResponse: $seriesUrl -> HTTP ${seriesRes.code}")
                return null
            }
            val doc = Jsoup.parse(seriesRes.text)
            val detailTitle = doc.selectFirst("div.series-title h1 span, div.series-title span, h1")?.text()?.trim()
                ?.ifEmpty { null } ?: meta.titles.firstOrNull() ?: "AniWorld"
            val links = doc.select("#stream ul li a[href]")
            val seasonLinks = links.mapNotNull { el ->
                val href = el.attr("href")
                val num = Regex("""staffel-(\d+)$""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                num to (if (href.startsWith("http")) href else "$mainUrl$href")
            }.distinctBy { it.first }.sortedBy { it.first }
            val films = links.count { it.attr("href").contains("/filme") }
            DebugLog.t(dbg, "buildSeriesResponse: '$detailTitle' seasons=${seasonLinks.map { it.first }} filmLinks=$films")
            if (seasonLinks.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: no seasons found (links in #stream: ${links.size}, page text '${doc.body()?.text()?.take(200)}')")
                return null
            }

            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            val perSeason = try {
                val futures = seasonLinks.map { (n, u) -> n to pool.submit(java.util.concurrent.Callable { collectEpisodes(u, n) }) }
                futures.map { (n, f) -> n to (try { f.get(40, java.util.concurrent.TimeUnit.SECONDS) } catch (t: Throwable) {
                    DebugLog.w(dbg, "season $n threw ${t.javaClass.name}: ${t.message}"); emptyList<Pair<Int, Episode>>()
                }) }
            } finally {
                pool.shutdown()
            }
            val aw = perSeason.map { (n, eps) -> n to eps.size }
            DebugLog.t(dbg, "buildSeriesResponse: episodes per season AniWorld=$aw TMDB=${meta.seasons}")

            val tmdbSeasons = meta.seasons.keys.size
            val numberThrough = tmdbSeasons == 1 && seasonLinks.size > 1
            if (numberThrough) {
                DebugLog.w(dbg, "buildSeriesResponse: season mismatch (TMDB 1 season, AniWorld ${seasonLinks.size}) -> numbering AniWorld episodes through as season 1")
            } else if (tmdbSeasons > 0 && tmdbSeasons != seasonLinks.size) {
                DebugLog.w(dbg, "buildSeriesResponse: season mismatch (TMDB $tmdbSeasons, AniWorld ${seasonLinks.size}) - kept as is")
            }
            val episodes = ArrayList<Episode>()
            var running = 0
            for ((n, eps) in perSeason) {
                for ((num, ep) in eps.sortedBy { it.first }) {
                    if (numberThrough) {
                        running++
                        ep.season = 1
                        ep.episode = running
                    } else {
                        ep.season = n
                        ep.episode = num
                    }
                    episodes.add(ep)
                }
            }
            if (episodes.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: 0 episodes total -> null")
                return null
            }
            DebugLog.t(dbg, "buildSeriesResponse: built ${episodes.size} episodes")
            episodes.take(12).forEach { DebugLog.t(dbg, "  ep: S${it.season}E${it.episode} ${it.name} -> ${it.data}") }

            newTvSeriesLoadResponse(detailTitle, seriesUrl, TvType.TvSeries, episodes) {
                this.year = meta.year
                this.plot = doc.select("p.seri_des").text().ifEmpty { "" }
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildSeriesResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /** Season page -> (episode number on AniWorld, episode). */
    private fun collectEpisodes(seasonUrl: String, seasonNum: Int): List<Pair<Int, Episode>> {
        val res = aniworldPage(seasonUrl)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "collectEpisodes: $seasonUrl -> HTTP ${res.code}")
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        val rows = doc.select("table.seasonEpisodesList tbody tr")
        if (rows.isEmpty()) {
            DebugLog.w(dbg, "collectEpisodes: 0 rows at $seasonUrl (tables: ${doc.select("table").size})")
            return emptyList()
        }
        val out = ArrayList<Pair<Int, Episode>>()
        for (row in rows) {
            val href = row.selectFirst("a[href*=episode-]")?.attr("href") ?: row.selectFirst("a")?.attr("href") ?: continue
            val abs = if (href.startsWith("http")) href else "$mainUrl$href"
            val num = row.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                ?: Regex("""episode-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val title = row.selectFirst(".seasonEpisodeTitle strong")?.text()?.trim()?.ifEmpty { null }
                ?: row.selectFirst(".seasonEpisodeTitle span")?.text()?.trim()?.ifEmpty { null }
            if (out.isEmpty()) {
                val langs = row.select("img[title]").joinToString(",") { it.attr("title") }
                DebugLog.t(dbg, "collectEpisodes: S$seasonNum first row: #$num '$title' langs=[$langs] -> $abs")
            }
            out.add(num to newEpisode(abs) {
                this.season = seasonNum
                this.episode = num
                this.name = title
            })
        }
        return out.distinctBy { it.second.data }
    }

    // ---- loadLinks ----

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.t(dbg, "loadLinks() called with data=$data")
        // ARVIO asks twice per episode (details page, then the player) - reuse the links for a while.
        linkCache[data]?.let { (at, links) ->
            val ageS = (System.currentTimeMillis() - at) / 1000
            if (ageS * 1000 < LINK_CACHE_MS) {
                DebugLog.t(dbg, "loadLinks: ${links.size} link(s) from memory (${ageS}s old)")
                links.forEach { callback.invoke(it) }
                return true
            }
            linkCache.remove(data)
        }
        val found = ArrayList<ExtractorLink>()
        val collect: (ExtractorLink) -> Unit = { link -> found.add(link); callback.invoke(link) }
        val ok = loadLinksFromBook(data, collect) || loadLinksLive(data, collect)
        if (ok && found.isNotEmpty()) linkCache[data] = System.currentTimeMillis() to ArrayList<ExtractorLink>(found)
        return ok
    }

    /** Linkbuch: the hoster address AniWorld gave for this episode earlier - no AniWorld request at all. */
    private fun loadLinksFromBook(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val entries = LinkBook.get(data)
        if (entries.isEmpty()) return false
        val startedAt = System.currentTimeMillis()
        for (e in entries) {
            val ageH = (System.currentTimeMillis() - e.savedAt) / (60 * 60 * 1000L)
            DebugLog.t(dbg, "linkbuch: try ${e.provider} ${e.hosterUrl} (saved ${ageH}h ago)")
            val ok = try {
                dispatchHoster(e.hosterUrl, HttpResp(200, "", e.hosterUrl), e.provider, e.language, callback)
            } catch (t: Throwable) {
                DebugLog.w(dbg, "linkbuch: threw ${t.javaClass.name}: ${t.message}"); false
            }
            if (ok) {
                GateStats.record("linkbuch", System.currentTimeMillis() - startedAt, "${e.provider} (Eintrag ${ageH} h alt, keine Pruefung)")
                return true
            }
        }
        DebugLog.w(dbg, "linkbuch: entry for $data no longer works -> removed, asking AniWorld again")
        LinkBook.remove(data)
        return false
    }

    // AniWorld's language keys (s.to scheme): used when the language box has no name for a key.
    private val LANG_FALLBACK = mapOf("1" to "Deutsch", "2" to "mit Untertitel Englisch", "3" to "mit Untertitel Deutsch")

    private class HosterEntry(val redirectUrl: String, val provider: String, val language: String)

    private fun loadLinksLive(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = aniworldPage(data)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "loadLinks: episode page $data -> HTTP ${res.code}")
            return false
        }
        val doc = Jsoup.parse(res.text)
        // Language names: <div class="changeLanguageBox"><img data-lang-key="1" title="Deutsch" src="/public/img/german.svg">
        val langNames = HashMap<String, String>()
        doc.select(".changeLanguageBox img[data-lang-key], img[data-lang-key]").forEach { img ->
            val key = img.attr("data-lang-key")
            val t = img.attr("title").ifBlank { img.attr("alt") }.trim()
            if (key.isNotEmpty() && t.isNotEmpty()) langNames.putIfAbsent(key, t)
        }
        DebugLog.t(dbg, "loadLinks: languages on page ${doc.select(".changeLanguageBox img").joinToString(" ") {
            "[key=${it.attr("data-lang-key")} title='${it.attr("title")}' src=${it.attr("src").substringAfterLast('/')}]" }}")

        val entries = ArrayList<HosterEntry>()
        for (li in doc.select(".hosterSiteVideo ul li, li[data-link-target]")) {
            val target = li.attr("data-link-target").ifBlank { li.selectFirst("a[href*=/redirect/]")?.attr("href") ?: "" }
            if (target.isEmpty()) continue
            val key = li.attr("data-lang-key")
            val provider = li.selectFirst("h4")?.text()?.trim()?.ifEmpty { null }
                ?: li.selectFirst("i[title]")?.attr("title")?.removePrefix("Hoster")?.trim() ?: "Provider"
            val language = langNames[key] ?: LANG_FALLBACK[key] ?: key
            val abs = if (target.startsWith("http")) target else "$mainUrl$target"
            if (entries.none { it.redirectUrl == abs }) entries.add(HosterEntry(abs, provider, language))
        }
        // In case AniWorld moved to Serienstream's newer form (button[data-play-url], docs/70 T2).
        if (entries.isEmpty()) {
            for (btn in doc.select("button[data-play-url]")) {
                val u = btn.attr("data-play-url")
                val abs = if (u.startsWith("http")) u else "$mainUrl$u"
                entries.add(HosterEntry(abs, btn.attr("data-provider-name").ifBlank { "Provider" }, btn.attr("data-language-label")))
            }
            if (entries.isNotEmpty()) DebugLog.w(dbg, "loadLinks: page uses the NEW form (button[data-play-url]) - built for the old one")
        }
        DebugLog.t(dbg, "loadLinks: ${entries.size} hoster entries")
        entries.take(20).forEach { DebugLog.t(dbg, "  hoster: ${it.provider} [${it.language}] -> ${it.redirectUrl}") }
        if (entries.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: 0 hosters (hosterSiteVideo=${doc.select(".hosterSiteVideo").size}, title='${titleOf(res.text)}', text '${doc.body()?.text()?.take(200)}')")
            return false
        }

        val loggedIn = AniworldSession.ensureLoggedIn()
        DebugLog.t(dbg, "loadLinks: loggedIn=$loggedIn")
        // One at a time in page order, stop at the first that gives a stream (like Serienstream).
        for ((idx, e) in entries.withIndex()) {
            try {
                if (resolveHost(e, data, idx, callback)) {
                    DebugLog.t(dbg, "loadLinks: DONE with hoster #$idx ${e.provider} [${e.language}]")
                    return true
                }
            } catch (t: Throwable) {
                DebugLog.w(dbg, "loadLinks: hoster threw ${t.javaClass.name}: ${t.message}")
            }
        }
        DebugLog.t(dbg, "loadLinks: DONE, nothing found")
        return false
    }

    /**
     * /redirect/<id> -> hoster embed page. First a plain request without following the redirect: if
     * AniWorld answers with a redirect to the hoster, no browser is needed. Otherwise the hidden
     * WebView loads it (browser check, Turnstile) and reports where it ends up.
     */
    private fun resolveHost(e: HosterEntry, episodeUrl: String, idx: Int, callback: (ExtractorLink) -> Unit): Boolean {
        val startedAt = System.currentTimeMillis()
        var hosterUrl: String? = null
        if (e.redirectUrl.contains("/redirect/")) {
            val r = AniworldSession.request(e.redirectUrl, "GET", null, episodeUrl)
            val loc = r.location
            val absLoc = when {
                loc.startsWith("http") -> loc
                loc.startsWith("/") -> "$mainUrl$loc"
                else -> loc
            }
            DebugLog.t(dbg, "redirect #$idx ${e.provider}: plain request -> ${r.code} location='$loc' title='${titleOf(r.text)}' (${r.text.length}B)")
            if (r.code in 300..399 && absLoc.startsWith("http") && !AniworldSession.isAniworld(absLoc)) {
                hosterUrl = absLoc
                GateStats.record("direkt", System.currentTimeMillis() - startedAt, "#$idx ${e.provider}")
            }
        }
        if (hosterUrl == null) {
            hosterUrl = AniworldGate.resolveRedirect(e.redirectUrl, episodeUrl, "redirect$idx")
        }
        if (hosterUrl == null) {
            DebugLog.w(dbg, "resolveHost: #$idx ${e.provider} -> no hoster address")
            return false
        }
        DebugLog.t(dbg, "resolveHost: #$idx ${e.provider} [${e.language}] -> $hosterUrl")
        val ok = dispatchHoster(hosterUrl, HttpResp(200, "", hosterUrl), e.provider, e.language, callback)
        if (ok) LinkBook.put(episodeUrl, LinkBook.Entry(hosterUrl, e.provider, e.language, System.currentTimeMillis()))
        return ok
    }

    /** Hand a hoster embed URL to the matching extractor. */
    private fun dispatchHoster(
        finalUrl: String,
        resolved: HttpResp,
        provider: String,
        language: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val host = try { URI(finalUrl).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
            val sourceName = if (language.isNotEmpty()) "$provider [$language]" else provider
            when {
                host.contains("dood") || host.contains("ds2play") || host.contains("playmogo") ||
                    host.contains("vidply") -> resolveDoodstream(finalUrl, sourceName, callback)
                // VOE hands out rotating mirror domains (e.g. jamesbornmain.com) -> trust the provider name too.
                host.contains("voe.") || host.endsWith("voe.sx") || provider.equals("VOE", ignoreCase = true) ->
                    resolveVoe(finalUrl, sourceName, callback)
                host.contains("streamtape") -> resolveStreamtape(finalUrl, sourceName, callback)
                host.contains("filemoon") -> resolveFileMoon(finalUrl, sourceName, callback)
                host.contains("vidhide") || host.contains("vidhd") -> resolveVidHide(finalUrl, sourceName, callback)
                else -> {
                    // If we got a real hoster page (200), try generic scrape for direct URLs.
                    // The WebView gate only yields the URL (empty body) -> fetch the hoster page first.
                    if (resolved.code in 200..299) {
                        val page = if (resolved.text.isEmpty()) httpGet(finalUrl, referer = "$mainUrl/") else resolved
                        genericResolve(finalUrl, page.text, sourceName, callback)
                    }
                    else {
                        DebugLog.w(dbg, "resolveHost: $provider final=$finalUrl code=${resolved.code} -> unresolved")
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "dispatchHoster: '$finalUrl' threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    // ---- Hoster extractors (unchanged from SerienstreamProvider v64) ----

    /**
     * DoodStream resolver, ported from Gujal00/ResolveURL doodstream.py (v5.1.206).
     * Algorithm:
     *  1. GET https://<host>/d/<mediaId> -> follows redirect to canonical host.
     *  2. Find <iframe src=...> -> GET that (the /e/ page) else use /e/<mediaId>.
     *  3. Regex dsplayer.hotkeys...'(<url>)'...function makePlay...return...?([^"]+) -> (pageUrl, token).
     *  4. GET pageUrl -> if 'cloudflarestorage.' in body: direct URL, else dood_decode(body)+token+ts.
     *  5. dood_decode = body + 10 random alphanumerics (cache-buster).
     */
    private fun resolveDoodstream(url: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        val mediaId = url.substringAfterLast("/e/").substringAfterLast("/d/").substringBefore("?").trim()
        if (mediaId.isEmpty()) {
            DebugLog.w(dbg, "resolveDoodstream: no mediaId in $url")
            return false
        }
        var host = try { URI(url).host ?: "dood.so" } catch (_: Throwable) { "dood.so" }
        // Resolve canonical host via /d/ redirect.
        val dUrl = "https://$host/d/$mediaId"
        val dRes = httpGet(dUrl, referer = "https://$host/")
        if (dRes.url != dUrl) {
            val newHost = try { URI(dRes.url).host } catch (_: Throwable) { null }
            if (newHost != null) host = newHost
        }
        val webUrl = "https://$host/d/$mediaId"
        var html = dRes.text
        // iframe?
        val iframeMatch = Regex("""<iframe\s+src="([^"]+)""").find(html)
        val embedHtml = if (iframeMatch != null) {
            val iframeUrl = resolveRelative(iframeMatch.groupValues[1], webUrl)
            httpGet(iframeUrl, referer = webUrl).text
        } else {
            val eUrl = webUrl.replace("/d/", "/e/")
            httpGet(eUrl, referer = webUrl).text
        }
        html = embedHtml
        // dsplayer.hotkeys pattern (DOTALL).
        val match = Regex("""dsplayer\.hotkeys[^']+'([^']+).+?function\s+makePlay.+?return[^?]+([^"]+)""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (match == null) {
            DebugLog.w(dbg, "resolveDoodstream: no dsplayer.hotkeys match (mediaId=$mediaId)")
            // fallback: generic scrape for direct mp4/m3u8.
            return genericResolve(url, html, source, callback)
        }
        val token = match.groupValues[2]
        val pageUrl = resolveRelative(match.groupValues[1], webUrl)
        val pageRes = httpGet(pageUrl, referer = webUrl)
        val pageBody = pageRes.text.trim()
        val vidSrc = if (pageBody.contains("cloudflarestorage.")) {
            pageBody
        } else {
            val suffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val ts = System.currentTimeMillis().toString()
            pageBody + suffix + token + ts
        }
        DebugLog.t(dbg, "resolveDoodstream: vidSrc=$vidSrc (mediaId=$mediaId)")
        return emitLink(source, vidSrc, webUrl, callback)
    }

    /**
     * VOE resolver (voe_decode), ported from Gujal00/ResolveURL voesx.py. See FilmPalast provider
     * for the full algorithm. VOE is DDoS-Guard protected and currently blocked on both laptop and
     * TV; included for completeness in case the block lifts.
     */
    private fun resolveVoe(url: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val res = httpGet(url, referer = "https://voe.sx/")
        if (res.code !in 200..299 && res.code != 0) {
            DebugLog.w(dbg, "resolveVoe: $url -> HTTP ${res.code} (likely DDoS-Guard blocked)")
            return false
        }
        // VOE first serves a JS redirect page ("const currentUrl" + window.location.href='...') to a
        // rotating mirror domain; the player page is behind it (same as Vavoo/FilmPalast).
        var currentUrl = url
        var text = res.text
        var redirects = 0
        while (text.contains("const currentUrl") && redirects < 5) {
            val r = Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(text) ?: break
            currentUrl = r.groupValues[1]
            text = httpGet(currentUrl).text
            redirects++
        }
        if (redirects > 0) DebugLog.t(dbg, "resolveVoe: followed $redirects redirects to $currentUrl")
        val voeRef = try {
            val u = java.net.URL(currentUrl); u.protocol + "://" + u.host + "/"
        } catch (_: Throwable) { "https://voe.sx/" }
        // Pattern 1: json">["<encoded>"]</script><script src="<jsUrl>"
        val p1 = Regex("""json">\["([^"]+)"\]</script>\s*<script\s+src="([^"]+)""")
        val m1 = p1.find(text)
        if (m1 != null) {
            val ct = m1.groupValues[1]
            val jsUrlRaw = m1.groupValues[2]
            val jsUrl = if (jsUrlRaw.startsWith("http")) jsUrlRaw else resolveRelative(jsUrlRaw, currentUrl)
            val jsRes = httpGet(jsUrl)
            val lutMatch = Regex("""(\[(?:'\W{2}'[,\]]){1,9})""").find(jsRes.text)
            DebugLog.t(dbg, "resolveVoe: pattern1 jsUrl=$jsUrl -> ${jsRes.code} (${jsRes.text.length}B) lut=${lutMatch != null}")
            if (lutMatch != null) {
                val decoded = try { voeDecode(ct, lutMatch.groupValues[1]) } catch (t: Throwable) { null }
                if (decoded != null) {
                    val streamUrl = decoded.optString("source", "").ifEmpty { decoded.optString("file", "") }
                        .ifEmpty { decoded.optString("direct_access_url", "") }
                    if (streamUrl.startsWith("http")) {
                        emitLink(source, streamUrl, voeRef, callback, true, voeTitle(decoded, text)); found = true
                    }
                }
            }
        }
        if (!found) {
            // Fallback: direct hls/mp4 in page.
            listOf(
                Regex(""""hls"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex(""""mp4"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            ).forEach { p ->
                p.findAll(text).forEach { emitLink(source, it.groupValues[1], voeRef, callback, true, voeTitle(null, text)); found = true }
                if (found) return@forEach
            }
        }
        DebugLog.t(dbg, "resolveVoe: $url found=$found")
        return found
    }

    /**
     * The release file name VOE shows in its player ("Silo.S01E01.German.Forced.720P.WEB.H264-WAYNE.mkv").
     * Tried in order: decoded player config, og:title, <title>. ARVIO shows ExtractorLink.name as the
     * source title and reads resolution/codec badges from it, so the name is worth passing on.
     */
    private fun voeTitle(decoded: JSONObject?, html: String): String? {
        val fromJson = decoded?.let { d ->
            listOf("title", "file_title", "name", "filename").map { d.optString(it, "") }.firstOrNull { it.isNotBlank() }
        }
        val fromOg = Regex("""<meta[^>]+(?:property|name)=["']og:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        val fromTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
        val raw = fromJson ?: fromOg ?: fromTitle
        val cleaned = raw?.trim()
            ?.removePrefix("Watch ")?.removePrefix("watch ")
            ?.replace(Regex("""\s*[-|]\s*VOE.*$""", RegexOption.IGNORE_CASE), "")
            ?.trim()
        val keys = StringBuilder()
        decoded?.keys()?.let { iter -> while (iter.hasNext()) { if (keys.isNotEmpty()) keys.append(','); keys.append(iter.next()) } }
        val from = when { fromJson != null -> "json"; fromOg != null -> "og"; fromTitle != null -> "title"; else -> "none" }
        DebugLog.t(dbg, "resolveVoe: title=$cleaned from=$from keys=[$keys]")
        // Generic page titles ("VOE", "Video") carry no information.
        return cleaned?.takeIf { it.length in 8..150 && !it.equals("VOE", true) }
    }

    /**
     * "Silo.S01E01.German.Forced.720P.WEB.H264-WAYNE.mkv" -> "Silo S01E01 German Forced 720P WEB H264":
     * keeps title/episode (the user checks it is the right stream) and the release tags, drops the file
     * extension and the release group, and turns dots into spaces so ARVIO can wrap it between words.
     */
    private fun displayFileName(fileName: String): String {
        var n = fileName.trim().replace(Regex("""\.(mkv|mp4|avi|m4v|webm|ts)$""", RegexOption.IGNORE_CASE), "")
        // Release group = text after the last '-' at the end of a scene name ("…H264-WAYNE").
        val group = Regex("""-([A-Za-z0-9]+)$""").find(n)?.groupValues?.get(1)
        if (group != null && n.count { it == '.' } >= 3 && !group.equals("DL", true) && !group.equals("Rip", true)) {
            n = n.substring(0, n.length - group.length - 1)
        }
        val cleaned = n.replace('.', ' ').replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        return if (cleaned.length >= 3) cleaned else fileName
    }

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
            val escaped = buildString { for (c in item) if (c in specials) { append('\\'); append(c) } else append(c) }
            txt = try { Regex(escaped).replace(txt, "") } catch (_: Throwable) { txt }
        }
        val step4 = try { base64Decode(txt) } catch (_: Throwable) { return JSONObject() }
        val step5 = String(CharArray(step4.length) { (step4[it].toInt() - 3).toChar() })
        val step6 = try { base64Decode(step5.reversed()) } catch (_: Throwable) { return JSONObject() }
        return try { JSONObject(step6) } catch (_: Throwable) { JSONObject() }
    }

    private fun base64Decode(s: String): String =
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)

    /**
     * Streamtape resolver, ported from Gujal00/ResolveURL streamtape.py.
     * GET embed -> regex link_URL="([^"]+)" (or id=linko) -> GET that with Referer -> redirect to mp4.
     */
    private fun resolveStreamtape(url: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, referer = url)
        if (res.code !in 200..299) return false
        // streamtape: <div id="linko" style="..."><a href="<mp4>" rel="nofollow">...</a></div>
        val match = Regex("""linko[^>]*>\s*<a[^>]+href="([^"]+)""").find(res.text)
            ?: Regex("""id="linko"[^>]*href="([^"]+)""").find(res.text)
            ?: Regex("""<a[^>]+href="([^"]+)"[^>]*>Download</a>""").find(res.text)
        if (match == null) {
            DebugLog.w(dbg, "resolveStreamtape: no linko match")
            return genericResolve(url, res.text, source, callback)
        }
        val vidUrl = resolveRelative(match.groupValues[1], url)
        // The linko URL redirects to the final mp4 (needs Referer).
        val vidRes = httpGet(vidUrl, referer = url)
        val finalUrl = vidRes.url
        DebugLog.t(dbg, "resolveStreamtape: $vidUrl -> $finalUrl")
        return emitLink(source, finalUrl, url, callback)
    }

    /**
     * FileMoon resolver. FileMoon embed pages use an eval/packed script with the m3u8/mp4 URL.
     * We unpack p.a.c.k.e.r'd JS and scrape for direct URLs.
     */
    private fun resolveFileMoon(url: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, referer = url)
        if (res.code !in 200..299) return false
        // FileMoon often has the sources in a JS blob: sources:[{file:"..."}]
        return genericResolve(url, res.text, source, callback)
    }

    /**
     * VidHide/VidHD resolver. Embed page contains player config with m3u8/mp4 in eval'd JS.
     */
    private fun resolveVidHide(url: String, source: String, callback: (ExtractorLink) -> Unit): Boolean {
        val res = httpGet(url, referer = url)
        if (res.code !in 200..299) return false
        return genericResolve(url, res.text, source, callback)
    }

    /**
     * Generic page-scrape for direct mp4/m3u8 URLs (fallback for hosters without a dedicated
     * extractor, or FileMoon/VidHide whose player config embeds plain URLs).
     */
    private fun genericResolve(
        url: String,
        html: String,
        source: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val base = url.substringBeforeLast("/")
        val root = url.substringBefore("/").dropLastWhile { it != '/' }
        // 1. Direct m3u8/mp4.
        Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").findAll(html).forEach {
            emitLink(source, it.groupValues[1], url, callback); found = true
        }
        Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").findAll(html).forEach {
            emitLink(source, it.groupValues[1], url, callback); found = true
        }
        // 2. sources:[{file:"..."}] / {file:"...",type:"hls"} config.
        Regex(""""file"\s*:\s*"(https?://[^"]+)""").findAll(html).forEach {
            emitLink(source, it.groupValues[1], url, callback); found = true
        }
        // 3. Relative m3u8/mp4 paths.
        Regex("""["'(]([^"'\s)]+\.(?:m3u8|mp4)[^"'\s)]*)["')]""").findAll(html).forEach { m ->
            val p = m.groupValues[1]
            val abs = when {
                p.startsWith("http") -> p
                p.startsWith("/") -> root + p
                else -> "$base/$p"
            }
            emitLink(source, abs, url, callback); found = true
        }
        DebugLog.t(dbg, "genericResolve: $url found=$found (html ${html.length} chars)")
        return found
    }

    // java.net.URL does the resolving (like Vavoo): the old string logic turned "/js/loader.js"
    // on https://host/e/x into "" + path -> "no protocol" and every VOE mirror failed (v59, device).
    private fun resolveRelative(maybeRelative: String, baseUrl: String): String {
        if (maybeRelative.startsWith("http")) return maybeRelative
        return try {
            java.net.URL(java.net.URL(baseUrl), maybeRelative).toString()
        } catch (_: Throwable) {
            baseUrl.substringBeforeLast("/") + "/" + maybeRelative.trimStart('/')
        }
    }

    private fun emitLink(source: String, url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean =
        emitLink(source, url, referer, callback, false, null)

    private fun emitLink(
        source: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        viaProxy: Boolean
    ): Boolean = emitLink(source, url, referer, callback, viaProxy, null)

    /** [fileName]: release name from the hoster, shown by ARVIO as the source title next to [source]. */
    private fun emitLink(
        source: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        viaProxy: Boolean,
        fileName: String?
    ): Boolean {
        return try {
            val isM3u8 = url.contains(".m3u8")
            val quality = detectQuality(url, isM3u8)
            // ARVIO blocks URLs containing "caching" (VOE CDN hosts) as pending debrid torrents and
            // drops our User-Agent/Referer on the way to the player -> serve such streams via LocalProxy.
            val playUrl = if (viaProxy || LocalProxy.needsWrap(url)) {
                LocalProxy.wrap(url, mapOf("Referer" to referer, "User-Agent" to mobileUA), isM3u8)
            } else url
            // ARVIO shows a flag chip only for whole-word language codes (GER/GERMAN, ENG/ENGLISH);
            // the site's label "Deutsch" matches neither -> translate it for the display name.
            val label = source.replace("[Deutsch]", "[GER]").replace("[Englisch]", "[ENG]")
            val name = if (fileName != null) {
                val shown = displayFileName(fileName)
                // The file name usually names the language itself ("German") -> drop the duplicate tag.
                val hasLang = Regex("""\b(GER|GERMAN|ENG|ENGLISH)\b""", RegexOption.IGNORE_CASE).containsMatchIn(shown)
                val host = if (hasLang) label.replace(" [GER]", "").replace(" [ENG]", "") else label
                "$host · $shown"
            } else label
            DebugLog.t(dbg, "emitLink: source=$source url=$url quality=$quality isM3u8=$isM3u8 proxy=${playUrl != url} name=$name")
            // PRIMARY constructor (9 positional args, no default-args) - R8 strips the synthetic
            // DefaultConstructorMarker constructor (AGENTS.md Erkenntnis #18).
            val link = ExtractorLink(
                source,                                                          // source
                name,                                                            // name (ARVIO: source title)
                playUrl,                                                         // url (LocalProxy if needed)
                referer,                                                         // referer
                quality,                                                         // quality
                emptyMap(),                                                      // headers
                "",                                                              // extractorData
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, // type
                emptyList()                                                      // audioTracks
            )
            callback.invoke(link)
            true
        } catch (t: Throwable) {
            DebugLog.w(dbg, "emitLink: threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    private fun detectQuality(url: String, isM3u8: Boolean): Int {
        if (!isM3u8) return Qualities.P720.value
        return try {
            val res = httpGet(url, headers = mapOf("Range" to "bytes=0-8192"))
            if (res.code !in 200..299 && res.code != 206) return Qualities.P720.value
            val sizes = Regex("RESOLUTION=(\\d+)x(\\d+)", RegexOption.IGNORE_CASE)
                .findAll(res.text).mapNotNull { m ->
                    val w = m.groupValues[1].toIntOrNull(); val h = m.groupValues[2].toIntOrNull()
                    if (w != null && h != null) w to h else null
                }.toList()
            DebugLog.t(dbg, "detectQuality: resolutions=${sizes.joinToString(",") { "${it.first}x${it.second}" }}")
            if (sizes.isEmpty()) return Qualities.P720.value
            // Width OR height: widescreen releases are flatter than 16:9 (720p Silo = 1280x640,
            // 1080p cinemascope = 1920x800) and were reported one tier too low by height alone.
            sizes.map { (w, h) ->
                when {
                    w >= 3200 || h >= 2000 -> Qualities.P2160.value
                    w >= 1800 || h >= 1000 -> Qualities.P1080.value
                    w >= 1200 || h >= 700 -> Qualities.P720.value
                    w >= 800 || h >= 460 -> Qualities.P480.value
                    else -> Qualities.P360.value
                }
            }.maxOrNull() ?: Qualities.P720.value
        } catch (_: Throwable) {
            Qualities.P720.value
        }
    }
}
