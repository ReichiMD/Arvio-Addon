// use an integer for version numbers
version = 51


cloudstream {
    language = "de"

    description = "Serienstream.to - Serien (Ventix-Arvio-Addon, TmdbProvider)"
    authors = listOf("ReichiMD")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     *
     * ARVIO sets manifestEnabled = (plugin.status == 1). With status != 1 the
     * PluginDataStore.setScraperEnabled() silently refuses to persist an enable,
     * so the scraper never runs. GermanProviders uses status = 1 for all plugins.
     *
     * RE-ENABLED (v40): the /r redirect gate requires Cloudflare Turnstile + ALTCHA-PoW.
     * We now solve Turnstile via a real Android WebView (TurnstileSolver.kt) — ARVIO runs on a
     * real TV with residential IP, which Turnstile rates "high trust", so the widget usually
     * passes silently. This is the mobile-app use-case Cloudflare itself documents.
     * See AGENTS.md "RECHERCHE (17.08.2026): Browser-CAPTCHA-Bypass" Kategorie 3.
     */
    status = 1

    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://serienstream.to&sz=%size%"
}
