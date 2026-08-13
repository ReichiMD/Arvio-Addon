// use an integer for version numbers
version = 1


cloudstream {
    language = "de"

    description = "Filmpalast.to - Filme & Serien (Ventix-Arvio-Addon, TmdbProvider)"
    authors = listOf("ReichiMD")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // Beta only - proof of concept

    tvTypes = listOf("TvSeries", "Movie")

    iconUrl = "https://www.google.com/s2/favicons?domain=https://filmpalast.to&sz=%size%"
}
