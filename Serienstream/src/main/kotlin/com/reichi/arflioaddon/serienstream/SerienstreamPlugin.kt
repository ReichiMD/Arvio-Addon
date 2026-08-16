package com.reichi.arflioaddon.serienstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[SerienstreamPlugin]", "load() — registering provider")
        registerMainAPI(SerienstreamProvider())
    }
}
