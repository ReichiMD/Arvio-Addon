package com.reichi.arflioaddon.kinoger

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KinogerPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[KinogerPlugin]", "load() — registering provider")
        registerMainAPI(KinogerProvider())
    }
}
