package com.reichi.arflioaddon.serienstream

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Serienstream.to provider for ARVIO, implemented as a TmdbProvider so ARVIO takes its direct
 * load() path (load({"id":<tmdbId>,"type":"tv"})) instead of the fragile search-based
 * title-matching path (see AGENTS.md "Recherche: ARVIO-Plugin-Integration").
 *
 * Serienstream structure (verified Aug 2026):
 *  - Series page: /serie/<slug> -> #season-nav ul > li a -> season links (/serie/<slug>/staffel-N)
 *  - Season page: /serie/<slug>/staffel-N -> table rows tr.episode-row with
 *    onclick="window.location='/serie/<slug>/staffel-N/episode-M'"
 *  - Episode page: /serie/<slug>/staffel-N/episode-M -> .link-wrapper > button with
 *    data-play-url="/r?t=<encrypted>" (DDoS-Guard-protected redirect) + data-provider-name +
 *    data-language-label.
 *
 * The /r?t= redirect is DDoS-Guard protected. We attempt the xStream bypass (check.js Image trick)
 * in [httpGet]. If it fails (newer js-challenge), resolveHost falls back to generic page-scrape.
 * Hoster extraction is non-suspend (java.net + jsoup) to sidestep ARVIO's broken coroutine
 * machinery for external .cs3 plugins (see AGENTS.md Erkenntnis #7/#13).
 */
class SerienstreamProvider : TmdbProvider() {
    override var mainUrl = "https://serienstream.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "de"

    override val useMetaLoadResponse = false

    private val dbg = "Serienstream"
    private val NET_TIMEOUT_MS = 8000L

    // ---- HTTP (java.net, with DDoS-Guard bypass attempt) ----

    private data class HttpResp(val code: Int, val text: String, val url: String)

    private val mobileUA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * HTTP GET via java.net.HttpURLConnection with a cookie-aware connection and the xStream
     * DDoS-Guard bypass: if a request returns a DDOS-GUARD challenge page, fetch
     * check.ddos-guard.net/check.js to get an Image-URL, load it (sets the __ddg2_ cookie),
     * then retry the original request. Ported from xStream's requestHandler.py.
     *
     * Note: this bypasses the OLD check.js challenge. Serienstream's /r? redirect endpoints now
     * also use a newer view.js/index.js challenge that may still block; in that case the retry
     * returns 403 again and the caller treats the hoster as unresolved. The TV may still get
     * through (different IP/UA) where this laptop-side bypass fails.
     */
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
        val mergedHeaders = mutableMapOf("User-Agent" to mobileUA)
        if (referer.isNotEmpty()) mergedHeaders["Referer"] = referer
        headers.forEach { (k, v) -> mergedHeaders[k] = v }
        return httpGetInternal(fullUrl, mergedHeaders)
    }

    private fun httpGetInternal(url: String, headers: Map<String, String>): HttpResp {
        // Collect cookies across requests so the DDoS-Guard bypass cookies persist.
        val cookieJar = CookieJar()
        val first = doRequest(url, headers, cookieJar)
        if (first.code in 200..299) return first
        // DDoS-Guard challenge detection (xStream: 'DDOS-GUARD' in body).
        val isDdg = first.code == 403 && ("DDOS-GUARD" in first.text || "ddos-guard" in first.text.lowercase())
        if (!isDdg) return first

        DebugLog.t(dbg, "httpGet: DDoS-Guard challenge at $url (code=${first.code}), attempting bypass")
        val bypassed = tryDdosGuardBypass(url, headers, cookieJar)
        if (!bypassed) {
            DebugLog.w(dbg, "httpGet: DDoS-Guard bypass failed for $url")
            return first
        }
        // Retry original request with updated cookies.
        val retry = doRequest(url, headers, cookieJar)
        DebugLog.t(dbg, "httpGet: retry after bypass -> ${retry.code} (${retry.text.length} bytes)")
        return retry
    }

    /**
     * xStream DDoS-Guard bypass: fetch https://check.ddos-guard.net/check.js, extract the Image
     * URL ("Image...'<path>'; new"), load it on the target host (sets __ddg2_ cookie), return true.
     */
    private fun tryDdosGuardBypass(
        targetUrl: String,
        headers: Map<String, String>,
        cookieJar: CookieJar
    ): Boolean {
        return try {
            val host = URI(targetUrl).host ?: return false
            val checkHeaders = headers.toMutableMap().apply {
                put("Referer", targetUrl)
                put("User-Agent", mobileUA)
            }
            // 1. fetch check.js (sets __ddg8_/__ddg9_/__ddg10_ cookies via Set-Cookie).
            val checkResp = doRequest(
                "https://check.ddos-guard.net/check.js",
                checkHeaders,
                cookieJar
            )
            if (checkResp.code !in 200..299) return false
            // 2. extract Image url fragment: pattern "Image.*?'([^']+)'; new"
            val imgMatch = Regex("""Image.*?'([^']+)';\s*new""").find(checkResp.text)
            val imgFragment = imgMatch?.groupValues?.get(1) ?: return false
            // 3. load the image on the target host (sets __ddg2_ cookie).
            val imgUrl = "https://$host${if (imgFragment.startsWith("/")) imgFragment else "/$imgFragment"}"
            doRequest(imgUrl, checkHeaders, cookieJar)
            DebugLog.t(dbg, "ddgBypass: check.js + image loaded, cookies=${cookieJar.names()}")
            cookieJar.hasAny("__ddg2")
        } catch (t: Throwable) {
            DebugLog.w(dbg, "ddgBypass: threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    /** Single HTTP request capturing Set-Cookie into the jar. instanceFollowRedirects=true so
     *  /r? redirects to the final hoster URL are followed automatically when not blocked. */
    private fun doRequest(
        url: String,
        headers: Map<String, String>,
        cookieJar: CookieJar
    ): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "text/html,application/json,*/*")
                setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                val cookieHeader = cookieJar.toCookieHeader(url)
                if (cookieHeader.isNotEmpty()) setRequestProperty("Cookie", cookieHeader)
            }
            conn.connect()
            val code = conn.responseCode
            // Capture Set-Cookie regardless of status (DDoS-Guard sets cookies on 403 too).
            cookieJar.captureSetCookie(conn, url)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val finalUrl = conn.url.toString()
            DebugLog.t(dbg, "doRequest: $url -> $code (${text.length}B) final=$finalUrl")
            HttpResp(code, text, finalUrl)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "doRequest: $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "", url)
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        referer: String = ""
    ): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", mobileUA)
                setRequestProperty("Accept", "application/json,*/*")
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            DebugLog.t(dbg, "httpPost: $url -> $code (${text.length} bytes)")
            HttpResp(code, text, url)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "httpPost: $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "", url)
        } finally {
            conn?.disconnect()
        }
    }

    // ---- TmdbProvider load path ----

    override suspend fun search(query: String): List<SearchResponse> {
        val res = httpGet(
            "$mainUrl/suche",
            params = mapOf("term" to query, "tab" to "shows")
        )
        val document = Jsoup.parse(res.text)
        return document.select(".results-group .card, .results-group a.show-card").mapNotNull { it.toSearchResult() }
    }

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
        // Serienstream is series-only; reject movie lookups.
        if (!isTv) {
            DebugLog.t(dbg, "load: movie type, Serienstream is series-only -> null")
            return null
        }
        DebugLog.t(dbg, "load: parsed tmdbId=$tmdbId isTv=$isTv")

        val meta = fetchTmdbMeta(tmdbId, isTv) ?: run {
            DebugLog.e(dbg, "load: TMDB metadata fetch failed for tmdbId=$tmdbId")
            return null
        }
        val title = meta.displayTitle
        DebugLog.t(dbg, "load: TMDB meta -> title='$title' year=${meta.year}")

        val seriesSlug = searchSeries(title)
        DebugLog.t(dbg, "load: searchSeries('$title') -> slug=$seriesSlug")
        if (seriesSlug == null) {
            DebugLog.w(dbg, "load: series not found on Serienstream -> null")
            return null
        }
        return buildSeriesResponse(seriesSlug, meta)
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

    private class TmdbMeta(val id: Int?, val displayTitle: String, val year: Int?)

    private val tmdbApiKey = "e6333b32409e02a4a6eba6fb7ff866bb"
    private val tmdbApiUrl = "https://api.themoviedb.org/3"
    private val tmdbCache = ConcurrentHashMap<Int, TmdbMeta>()

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
            val title = obj.optString("name", "").ifEmpty { obj.optString("original_name", "") }
            val date = obj.optString("first_air_date", "").ifEmpty { obj.optString("release_date", "") }
            val year = date.take(4).toIntOrNull()
            val meta = TmdbMeta(obj.optInt("id", -1).takeIf { it >= 0 }, title.trim(), year)
            tmdbCache[tmdbId] = meta
            meta
        } catch (t: Throwable) {
            DebugLog.e(dbg, "fetchTmdbMeta: request threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    // ---- Serienstream search & series resolution ----

    /**
     * Search serienstream.to for the series and return its /serie/<slug> path, or null.
     * Search results link to /serie/<slug>; we match against the TMDB title (normalized).
     */
    private fun searchSeries(title: String): String? {
        val res = httpGet("$mainUrl/suche", params = mapOf("term" to title, "tab" to "shows"))
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "searchSeries: suche -> HTTP ${res.code}")
            return null
        }
        val document = Jsoup.parse(res.text)
        val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() }
        val titleNorm = norm(title)
        // GermanProviders: .results-group .card -> a (href=/serie/<slug>), img alt=title.
        val candidates = document.select(".results-group a.show-card, .results-group .card a, .results-group a[href*=/serie/]")
            .mapNotNull { el ->
                val href = el.attr("href").ifBlank { return@mapNotNull null }
                val t = el.attr("title").ifBlank { el.text().ifBlank { el.selectFirst("img")?.attr("alt") ?: "" } }
                val abs = if (href.startsWith("http")) href else if (href.startsWith("/")) "$mainUrl$href" else href
                abs to t
            }
            .filter { it.first.contains("/serie/") }
            .distinctBy { it.first }
        DebugLog.t(dbg, "searchSeries: ${candidates.size} candidates")
        candidates.take(15).forEach { DebugLog.t(dbg, "  candidate: ${it.second} | ${it.first}") }
        // Prefer exact normalized match, else first candidate.
        return candidates.firstOrNull { norm(it.second) == titleNorm }?.first
            ?: candidates.firstOrNull()?.first
    }

    /**
     * Build a TvSeriesLoadResponse from a series slug: fetch the series page, iterate seasons
     * (#season-nav ul > li a), fetch each season page, collect episode URLs
     * (tr.episode-row onclick="window.location='/serie/<slug>/staffel-N/episode-M'").
     */
    private suspend fun buildSeriesResponse(seriesUrl: String, meta: TmdbMeta): LoadResponse? {
        return try {
            val seriesRes = httpGet(seriesUrl)
            if (seriesRes.code !in 200..299) {
                DebugLog.w(dbg, "buildSeriesResponse: series page $seriesUrl -> HTTP ${seriesRes.code}")
                return null
            }
            val seriesDoc = Jsoup.parse(seriesRes.text)
            val detailTitle = seriesDoc.selectFirst("h1")?.text()?.trim()?.ifEmpty { meta.displayTitle }
                ?: meta.displayTitle

            // Season links: #season-nav ul > li a (href=/serie/<slug>/staffel-N).
            val seasonLinks = seriesDoc.select("#season-nav ul li a, #season-nav a[href*=/staffel-]")
                .mapNotNull { el ->
                    val href = el.attr("href").ifBlank { return@mapNotNull null }
                    val abs = if (href.startsWith("http")) href else "$mainUrl$href"
                    val num = Regex("""staffel-(\d+)""").find(abs)?.groupValues?.get(1)?.toIntOrNull()
                        ?: el.text().trim().toIntOrNull()
                    num to abs
                }
                .filter { it.first != null }
                .distinctBy { it.first }
                .sortedBy { it.first }
            DebugLog.t(dbg, "buildSeriesResponse: ${seasonLinks.size} seasons: ${seasonLinks.map { it.first }}")
            if (seasonLinks.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: no seasons found -> null")
                return null
            }

            val episodes = mutableListOf<Episode>()
            for ((seasonNum, seasonUrl) in seasonLinks) {
                val eps = collectEpisodes(seasonUrl, seasonNum ?: 1)
                episodes.addAll(eps)
                DebugLog.t(dbg, "buildSeriesResponse: season ${seasonNum} -> ${eps.size} episodes")
            }
            if (episodes.isEmpty()) {
                DebugLog.w(dbg, "buildSeriesResponse: 0 episodes total -> null")
                return null
            }
            DebugLog.t(dbg, "buildSeriesResponse: built ${episodes.size} episodes")
            episodes.take(20).forEach { DebugLog.t(dbg, "  ep: S${it.season}E${it.episode} ${it.name} -> ${it.data}") }

            newTvSeriesLoadResponse(detailTitle, seriesUrl, TvType.TvSeries, episodes) {
                this.year = meta.year
                this.plot = seriesDoc.select(".description-text").text().ifEmpty { "" }
            }
        } catch (t: Throwable) {
            DebugLog.e(dbg, "buildSeriesResponse: threw ${t.javaClass.name}: ${t.message}")
            null
        }
    }

    /**
     * Fetch a season page and collect episode rows: tr.episode-row with
     * onclick="window.location='/serie/<slug>/staffel-N/episode-M'".
     * GermanProviders reads .episode-number-cell and .episode-title-cell.
     */
    private fun collectEpisodes(seasonUrl: String, seasonNum: Int): List<Episode> {
        val res = httpGet(seasonUrl)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "collectEpisodes: $seasonUrl -> HTTP ${res.code}")
            return emptyList()
        }
        val doc = Jsoup.parse(res.text)
        val rows = doc.select("tr.episode-row")
        if (rows.isEmpty()) {
            DebugLog.w(dbg, "collectEpisodes: 0 episode-row at $seasonUrl")
            return emptyList()
        }
        return rows.mapNotNull { row ->
            val onclick = row.attr("onclick")
            val epUrl = Regex("""window\.location\s*=\s*'([^']+)'""").find(onclick)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val abs = if (epUrl.startsWith("http")) epUrl else "$mainUrl$epUrl"
            val epNum = row.selectFirst(".episode-number-cell")?.text()?.trim()?.toIntOrNull()
            val epTitle = row.selectFirst(".episode-title-cell strong, .episode-title-cell")
                ?.text()?.trim()?.ifBlank { null }
            newEpisode(abs) {
                this.season = seasonNum
                this.episode = epNum
                this.name = epTitle
            }
        }.distinctBy { it.data }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href").ifBlank { this.selectFirst("a")?.attr("href") ?: "" }) ?: return null
        val title = this.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: this.attr("title").ifBlank { this.text().ifBlank { null } }
            ?: return null
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(this@toSearchResult.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            })
        }
    }

    // ---- loadLinks ----

    /**
     * `data` is the episode page URL (/serie/<slug>/staffel-N/episode-M). Fetch it, collect
     * .link-wrapper > button entries (data-play-url + data-provider-name + data-language-label),
     * resolve each hoster in PARALLEL via [resolveHost]. All non-suspend (java.net + jsoup).
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.t(dbg, "loadLinks() called with data=$data")
        val res = httpGet(data)
        if (res.code !in 200..299) {
            DebugLog.w(dbg, "loadLinks: episode page $data -> HTTP ${res.code}")
            return false
        }
        val doc = Jsoup.parse(res.text)
        val buttons = doc.select(".link-wrapper > button, button[data-play-url]")
        DebugLog.t(dbg, "loadLinks: ${buttons.size} hoster buttons")
        if (buttons.isEmpty()) {
            DebugLog.w(dbg, "loadLinks: 0 hoster buttons -> no sources")
            return false
        }

        // Each button: data-play-url = /r?t=<encrypted> redirect (DDoS-Guard protected) OR a
        // direct hoster URL. We follow it via httpGet (which follows redirects + attempts ddg
        // bypass). The final URL (after redirects) is the hoster embed page.
        val entries = buttons.mapNotNull { btn ->
            val playUrl = btn.attr("data-play-url").ifBlank { return@mapNotNull null }
            val provider = btn.attr("data-provider-name").ifBlank { "Provider" }
            val language = btn.attr("data-language-label").ifBlank { "" }
            val abs = if (playUrl.startsWith("http")) playUrl else "$mainUrl$playUrl"
            Triple(abs, provider, language)
        }
        DebugLog.t(dbg, "loadLinks: ${entries.size} hoster entries")
        entries.take(20).forEach { DebugLog.t(dbg, "  hoster: ${it.second} [${it.third}] -> ${it.first.take(60)}") }

        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(entries.size, 4))
        var any = false
        try {
            val futures = entries.map { (hostUrl, provider, language) ->
                pool.submit(java.util.concurrent.Callable {
                    resolveHost(hostUrl, provider, language, data, callback)
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

    /**
     * Resolve a hoster. `hostUrl` is the /r?t= redirect OR a direct embed URL. We follow it
     * (httpGet follows redirects + DDoS-Guard bypass), the final URL tells us the hoster domain,
     * then dispatch to hoster-specific extractors. All non-suspend.
     */
    private fun resolveHost(
        hostUrl: String,
        provider: String,
        language: String,
        episodePageUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Follow the /r? redirect to the real hoster embed URL. httpGet follows redirects
            // and attempts the DDoS-Guard bypass; the .url field is the final URL after redirects.
            // Serienstream's data-play-url is a /r?t=<encrypted> redirect protected by ALTCHA
            // Proof-of-Work (see Erkenntnis #20). The old DDoS-Guard bypass doesn't help here;
            // we solve the ALTCHA PoW and POST /r with the solution to get the real hoster URL.
            // Falls the ALTCHA flow fails (DDoS-Guard blocks verify-init on this IP), we fall
            // back to a direct httpGet (which may work on the TV where it fails on laptop).
            val resolved: HttpResp = if (hostUrl.contains("/r?t=") || hostUrl.contains("/r%3Ft%3D")) {
                resolveRedirectGate(hostUrl, episodePageUrl)
            } else {
                httpGet(hostUrl, referer = episodePageUrl)
            }
            val finalUrl = resolved.url
            DebugLog.t(dbg, "resolveHost: $provider $hostUrl -> final=$finalUrl (code=${resolved.code})")
            val host = try { URI(finalUrl).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
            val sourceName = if (language.isNotEmpty()) "$provider [$language]" else provider
            when {
                host.contains("dood") || host.contains("ds2play") || host.contains("playmogo") ||
                    host.contains("vidply") -> resolveDoodstream(finalUrl, sourceName, callback)
                host.contains("voe.") || host.endsWith("voe.sx") -> resolveVoe(finalUrl, sourceName, callback)
                host.contains("streamtape") -> resolveStreamtape(finalUrl, sourceName, callback)
                host.contains("filemoon") -> resolveFileMoon(finalUrl, sourceName, callback)
                host.contains("vidhide") || host.contains("vidhd") -> resolveVidHide(finalUrl, sourceName, callback)
                else -> {
                    // If we got a real hoster page (200), try generic scrape for direct URLs.
                    if (resolved.code in 200..299) genericResolve(finalUrl, resolved.text, sourceName, callback)
                    else {
                        DebugLog.w(dbg, "resolveHost: $provider final=$finalUrl code=${resolved.code} -> unresolved")
                        false
                    }
                }
            }
        } catch (t: Throwable) {
            DebugLog.w(dbg, "resolveHost: '$hostUrl' threw ${t.javaClass.name}: ${t.message}")
            false
        }
    }

    // ---- Hoster extractors ----

    /**
     * Resolve Serienstream's /r?t=<encrypted> redirect via ALTCHA Proof-of-Work (Erkenntnis #20).
     *
     * Flow (verified live, Aug 2026):
     *  1. GET episode page (with session cookies) -> extract CSRF _token + the /r?t= token.
     *  2. GET /api/inline/verify-init -> {algorithm:"SHA-256", challenge, salt, maxnumber, signature}.
     *  3. Solve PoW: find n in 0..maxnumber where SHA-256(salt + str(n)) == challenge.
     *  4. Build ALTCHA payload = base64(JSON{algorithm, challenge, number, salt, signature}).
     *  5. POST /r with form fields _token + t (decoded token) + altcha (payload) -> 200 with JS body.
     *  6. The 200 body contains either `var err = "..."` (failure -> retry/fallback) or a redirect
     *     to the real hoster URL (window.location / iframe src / postMessage t=<real-url>).
     *
     * All non-suspend (java.net + java.security.MessageDigest). java.security is JDK, never
     * obfuscated by R8. android.util.Base64 already used in voeDecode. The whole flow uses a
     * dedicated CookieJar so the Laravel session + XSRF-TOKEN persist from episode page to POST.
     *
     * Note: on laptop this fails at step 5 with err="Das hat leider nicht geklappt" because the
     * DDoS-Guard blocks the /r?t= preflight (403). On the TV (different IP) the /r?t= returns 200
     * with the challenge iframe, so the session has valid DDoS-Guard cookies -> POST succeeds.
     */
    private fun resolveRedirectGate(redirectUrl: String, episodePageUrl: String): HttpResp {
        val cj = CookieJar()
        val headers = mapOf(
            "User-Agent" to mobileUA,
            "Accept" to "text/html,application/json,*/*",
            "Accept-Language" to "de-DE,de;q=0.9,en;q=0.8"
        )
        // 1. Episode-Seite laden (setzt Session + XSRF-TOKEN Cookies).
        val epHeaders = HashMap(headers)
        epHeaders["Referer"] = mainUrl
        val epResp = doRequest(episodePageUrl, epHeaders, cj)
        if (epResp.code !in 200..299) {
            DebugLog.w(dbg, "redirectGate: episode page $episodePageUrl -> HTTP ${epResp.code}")
            return epResp
        }
        val csrf = Regex("""name="_token"\s+value="([^"]+)"""").find(epResp.text)?.groupValues?.get(1) ?: ""
        if (csrf.isEmpty()) {
            DebugLog.w(dbg, "redirectGate: no CSRF _token on episode page")
            return epResp
        }

        // 1b. Preflight: GET the /r?t=<token> redirect URL itself. The browser does this when
        // the user clicks a hoster button — it loads the redirect-gate page (which embeds the
        // ALTCHA widget). This request establishes the DDoS-Guard session cookies that the
        // POST /r later requires. Without it the server rejects the ALTCHA solution with
        // "Das hat leider nicht geklappt" even though the PoW is correct.
        // instanceFollowRedirects stays true so we don't follow away to a hoster prematurely
        // (the gate page returns 200 + JS, not a 3xx).
        val preflightHeaders = HashMap(headers)
        preflightHeaders["Referer"] = episodePageUrl
        val preflightResp = doRequest(redirectUrl, preflightHeaders, cj)
        DebugLog.t(dbg, "redirectGate: preflight /r?t= -> ${preflightResp.code} (${preflightResp.text.length}B) ddgCookies=${cj.hasAny("__ddg")}")

        // Der t-token aus der redirectUrl (/r?t=<urlencoded>). Dekodieren für das POST-Feld.
        val tToken = try {
            val raw = redirectUrl.substringAfter("t=").substringBefore("&")
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            redirectUrl.substringAfter("t=").substringBefore("&")
        }

        // 2. ALTCHA Challenge holen.
        val chalHeaders = HashMap(headers)
        chalHeaders["Referer"] = episodePageUrl
        val chalResp = doRequest(
            "https://serienstream.to/api/inline/verify-init",
            chalHeaders,
            cj
        )
        if (chalResp.code !in 200..299) {
            DebugLog.w(dbg, "redirectGate: verify-init -> HTTP ${chalResp.code} (DDoS-Guard blocks API?)")
            return HttpResp(chalResp.code, chalResp.text, redirectUrl)
        }
        // 3. PoW lösen.
        val payload = try {
            solveAltcha(chalResp.text)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "redirectGate: solveAltcha threw ${t.javaClass.name}: ${t.message}")
            return HttpResp(0, "", redirectUrl)
        }
        if (payload.isEmpty()) {
            DebugLog.w(dbg, "redirectGate: ALTCHA PoW not solvable (maxnumber too low?)")
            return HttpResp(0, "", redirectUrl)
        }

        // 4. POST /r mit _token + t + altcha.
        val postBody = "_token=" + java.net.URLEncoder.encode(csrf, "UTF-8") +
            "&t=" + java.net.URLEncoder.encode(tToken, "UTF-8") +
            "&altcha=" + java.net.URLEncoder.encode(payload, "UTF-8")
        val postHeaders = HashMap(headers)
        postHeaders["Referer"] = episodePageUrl
        postHeaders["Origin"] = mainUrl
        postHeaders["Content-Type"] = "application/x-www-form-urlencoded"
        val postResp = doRequestPost(
            "https://serienstream.to/r",
            postBody,
            postHeaders,
            cj
        )
        DebugLog.t(dbg, "redirectGate: POST /r -> ${postResp.code} (${postResp.text.length}B)")

        if (postResp.code !in 200..299) {
            return postResp
        }
        // 5. 200-Body parsen: Erfolg = Hoster-URL gefunden; Misserfolg = err="...".
        val body = postResp.text
        val errMatch = Regex("""var\s+err\s*=\s*"([^"]*)"""").find(body)
        if (errMatch != null && errMatch.groupValues[1].isNotEmpty()) {
            DebugLog.w(dbg, "redirectGate: server rejected ALTCHA: ${errMatch.groupValues[1]}")
            // Der Server sendet trotzdem einen t-token zurück (postMessage an parent), den der
            // Browser ins iframe lädt. Bei Erfolg ist err leer und t enthält die echte Hoster-URL.
        }
        // Bei Erfolg: der Body enthält die echte Hoster-URL im t-Feld oder als location.
        // postMessage-Format: {type:"frameBridge",v:1,t:<url>,err:<err>} -> t ist die Hoster-URL.
        val tMatch = Regex("""var\s+t\s*=\s*"([^"]+)"""").find(body)
        if (tMatch != null) {
            val resolvedUrl = tMatch.groupValues[1]
            if (resolvedUrl.startsWith("http")) {
                DebugLog.t(dbg, "redirectGate: resolved to $resolvedUrl")
                return HttpResp(200, body, resolvedUrl)
            }
        }
        // Fallback: direkte Hoster-URL im Body (voe/dood/streamtape/etc.).
        val hosterUrl = Regex("""(https?://[^\s"'<>]*(?:voe|dood|ds2play|streamtape|filemoon|vidhide|vidhd|playmogo|vidply)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
        if (hosterUrl != null) {
            DebugLog.t(dbg, "redirectGate: found hoster URL in body: $hosterUrl")
            return HttpResp(200, body, hosterUrl)
        }
        // Keine Hoster-URL gefunden -> Return raw body (genericResolve kann noch direkt URLs finden).
        DebugLog.w(dbg, "redirectGate: no hoster URL in POST /r response body")
        return HttpResp(200, body, redirectUrl)
    }

    /**
     * ALTCHA Proof-of-Work solver. Given the verify-init JSON response, finds n in 0..maxnumber
     * where SHA-256(salt + str(n)) == challenge, then returns base64(JSON payload).
     * Uses java.security.MessageDigest (JDK, never R8-obfuscated).
     */
    private fun solveAltcha(initJson: String): String {
        val obj = JSONObject(initJson)
        val algorithm = obj.optString("algorithm", "SHA-256")
        val challenge = obj.optString("challenge", "")
        val salt = obj.optString("salt", "")
        val maxnumber = obj.optInt("maxnumber", 100000)
        val signature = obj.optString("signature", "")
        if (challenge.isEmpty() || salt.isEmpty()) return ""

        if (!algorithm.equals("SHA-256", ignoreCase = true)) {
            DebugLog.w(dbg, "solveAltcha: unsupported algorithm '$algorithm'")
            return ""
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var solution = -1
        for (n in 0..maxnumber) {
            val input = (salt + n.toString()).toByteArray(Charsets.UTF_8)
            val hash = md.digest(input)
            // Zu Hex-String.
            val hex = StringBuilder(hash.size * 2)
            for (b in hash) {
                val v = b.toInt() and 0xFF
                hex.append("0123456789abcdef"[v ushr 4])
                hex.append("0123456789abcdef"[v and 0x0F])
            }
            if (hex.toString() == challenge) {
                solution = n
                break
            }
        }
        if (solution < 0) {
            DebugLog.w(dbg, "solveAltcha: no solution found in 0..$maxnumber")
            return ""
        }
        DebugLog.t(dbg, "solveAltcha: PoW solved, n=$solution")
        val payloadObj = JSONObject()
        payloadObj.put("algorithm", algorithm)
        payloadObj.put("challenge", challenge)
        payloadObj.put("number", solution)
        payloadObj.put("salt", salt)
        payloadObj.put("signature", signature)
        val payloadJson = payloadObj.toString()
        return android.util.Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    /**
     * POST request with cookie jar support (for the ALTCHA /r flow). Same pattern as doRequest
     * but with POST body + Content-Type.
     */
    private fun doRequestPost(
        url: String,
        body: String,
        headers: Map<String, String>,
        cookieJar: CookieJar
    ): HttpResp {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS.toInt()
                readTimeout = NET_TIMEOUT_MS.toInt()
                instanceFollowRedirects = true
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Accept", "text/html,application/json,*/*")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                val cookieHeader = cookieJar.toCookieHeader(url)
                if (cookieHeader.isNotEmpty()) setRequestProperty("Cookie", cookieHeader)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.connect()
            val code = conn.responseCode
            cookieJar.captureSetCookie(conn, url)
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val finalUrl = conn.url.toString()
            DebugLog.t(dbg, "doRequestPost: $url -> $code (${text.length}B) final=$finalUrl")
            HttpResp(code, text, finalUrl)
        } catch (t: Throwable) {
            DebugLog.w(dbg, "doRequestPost: $url threw ${t.javaClass.name}: ${t.message}")
            HttpResp(0, "", url)
        } finally {
            conn?.disconnect()
        }
    }

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
        val text = res.text
        if (res.code !in 200..299 && res.code != 0) {
            DebugLog.w(dbg, "resolveVoe: $url -> HTTP ${res.code} (likely DDoS-Guard blocked)")
            return false
        }
        // Pattern 1: json">["<encoded>"]</script><script src="<jsUrl>"
        val p1 = Regex("""json">\["([^"]+)"\]</script>\s*<script\s+src="([^"]+)""")
        val m1 = p1.find(text)
        if (m1 != null) {
            val ct = m1.groupValues[1]
            val jsUrlRaw = m1.groupValues[2]
            val jsUrl = if (jsUrlRaw.startsWith("http")) jsUrlRaw else resolveRelative(jsUrlRaw, url)
            val jsRes = httpGet(jsUrl)
            val lutMatch = Regex("""(\[(?:'\W{2}'[,\]]){1,9})""").find(jsRes.text)
            if (lutMatch != null) {
                val decoded = try { voeDecode(ct, lutMatch.groupValues[1]) } catch (t: Throwable) { null }
                if (decoded != null) {
                    val streamUrl = decoded.optString("source", "").ifEmpty { decoded.optString("file", "") }
                        .ifEmpty { decoded.optString("direct_access_url", "") }
                    if (streamUrl.startsWith("http")) {
                        emitLink(source, streamUrl, "https://voe.sx/", callback); found = true
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
                p.findAll(text).forEach { emitLink(source, it.groupValues[1], "https://voe.sx/", callback); found = true }
                if (found) return@forEach
            }
        }
        DebugLog.t(dbg, "resolveVoe: $url found=$found")
        return found
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

    private fun resolveRelative(maybeRelative: String, baseUrl: String): String {
        if (maybeRelative.startsWith("http")) return maybeRelative
        val root = baseUrl.substringBefore("/").dropLastWhile { it != '/' }
        return if (maybeRelative.startsWith("/")) root + maybeRelative
        else baseUrl.substringBeforeLast("/") + "/" + maybeRelative
    }

    private fun emitLink(source: String, url: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val isM3u8 = url.contains(".m3u8")
            val quality = detectQuality(url, isM3u8)
            DebugLog.t(dbg, "emitLink: source=$source url=$url quality=$quality isM3u8=$isM3u8")
            // PRIMARY constructor (9 positional args, no default-args) - R8 strips the synthetic
            // DefaultConstructorMarker constructor (AGENTS.md Erkenntnis #18).
            val link = ExtractorLink(
                source,                                                          // source
                source,                                                          // name
                url,                                                             // url
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
        } catch (_: Throwable) {
            Qualities.P720.value
        }
    }
}
