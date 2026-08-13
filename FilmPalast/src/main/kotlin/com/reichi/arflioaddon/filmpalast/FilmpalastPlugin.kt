package com.reichi.arflioaddon.filmpalast

import android.content.Context
import com.lagradost.cloudstream3.extractors.FileMoonSx
import com.lagradost.cloudstream3.extractors.Voe1
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmpalastPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmpalastProvider())

        // Hoster extractors seen on Filmpalast stream pages.
        registerExtractorAPI(Voe1())
        registerExtractorAPI(Ryderjet())
        registerExtractorAPI(FileMoonSx())
        registerExtractorAPI(AbstreamTo())
    }
}
