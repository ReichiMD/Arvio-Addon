// use an integer for version numbers
version = 9


cloudstream {
    language = "de"

    description = "Kinoger.com - Filme & Serien (Ventix-Arvio-Addon, TmdbProvider)"
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
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://kinoger.com&sz=%size%"
}
