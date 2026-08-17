// use an integer for version numbers
version = 39


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
     * DISABLED (status 0): the /r redirect gate requires Cloudflare Turnstile (browser
     * CAPTCHA, sitekey 0x4AAAAAAAFBfchmT6XFij7y) + ALTCHA-PoW. We solve the PoW but not
     * Turnstile -> 0 sources. Re-enable (status 1 + registerMainAPI) when a Turnstile
     * bypass exists. The provider code is kept for later re-use.
     */
    status = 0

    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://serienstream.to&sz=%size%"
}
