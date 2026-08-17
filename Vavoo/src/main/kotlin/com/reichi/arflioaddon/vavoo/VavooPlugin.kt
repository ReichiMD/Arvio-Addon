package com.reichi.arflioaddon.vavoo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VavooPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[VavooPlugin]", "load() — registering provider")
        registerMainAPI(VavooProvider())
    }
}
