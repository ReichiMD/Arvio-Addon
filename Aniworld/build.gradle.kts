// use an integer for version numbers
version = 2


cloudstream {
    language = "de"

    description = "AniWorld.to - Anime (Arvio-Addon, TmdbProvider)"
    authors = listOf("ReichiMD")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     *
     * ARVIO sets manifestEnabled = (plugin.status == 1); anything else is never enabled.
     */
    status = 1 // 25.09.2026: first version, built like Serienstream (Buero docs/themen/70)

    tvTypes = listOf("TvSeries")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://aniworld.to&sz=%size%"
}
