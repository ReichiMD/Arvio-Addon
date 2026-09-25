package com.reichi.arflioaddon.aniworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AniworldPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[AniworldPlugin]", "load() — provider ENABLED (login + WebView gate)")
        // Same approach as Serienstream: a hidden WebView gets through the site's browser check
        // on the user's own (residential) connection. See AniworldGate.
        AniworldGate.init(context)
        registerMainAPI(AniworldProvider())
    }
}
