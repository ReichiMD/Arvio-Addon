// use an integer for version numbers
version = 31


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
     */
    status = 1

    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://serienstream.to&sz=%size%"
}
