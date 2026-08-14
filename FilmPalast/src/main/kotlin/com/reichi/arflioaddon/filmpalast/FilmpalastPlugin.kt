package com.reichi.arflioaddon.filmpalast

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmpalastPlugin : Plugin() {
    override fun load(context: Context) {
        // Self-diagnosis is best-effort: a failure here MUST NOT prevent the scraper from
        // registering. ARVIO loads .cs3 via DexClassLoader with a shrunk kotlin-stdlib
        // parent classloader, so any kotlin-stdlib extension we touch in the diagnostic
        // path could throw NoClassDefFoundError (an Error) and abort load(). Wrap it.
        try {
            DebugLog.init(context)
            DebugServer.start()
        } catch (t: Throwable) {
            android.util.Log.e("ArvioAddon[FilmpalastPlugin]", "diagnostic init failed (non-fatal): ${t.javaClass.simpleName}: ${t.message}")
        }

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
