package com.reichi.arflioaddon.filmpalast

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmpalastPlugin : Plugin() {
    override fun load(context: Context) {
        // Self-diagnosis: initialize the trace logger and start a local HTTP server on
        // http://localhost:8420 so the user can read scraper logs in the device browser.
        // See DebugLog.kt / DebugServer.kt.
        DebugLog.init(context)
        DebugServer.start()

        registerMainAPI(FilmpalastProvider())

        // Custom hoster aliases not covered by cloudstream3's built-in extractors.
        // Built-in extractors (Voe, Firestream, FileMoonSx, Supervideo, VidHidePro, ...)
        // are loaded automatically by ARVIO via ExternalExtractorRegistry.installGlobal()
        // and match by domain, so we do NOT re-register Voe1 here (its mainUrl is a
        // rotating VOE mirror domain that does not match voe.sx links from Filmpalast).
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(AbstreamTo())
    }
}
