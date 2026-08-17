package com.reichi.arflioaddon.serienstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[SerienstreamPlugin]", "load() — provider ENABLED (Turnstile WebView solver)")
        // Initialise the WebView-based Turnstile solver with the Activity context ARVIO hands us.
        // Cloudflare Turnstile validates residential IP + real browser fingerprint + behaviour
        // simultaneously; a token from a 2captcha-style solver (minted on a different IP) is rejected.
        // ARVIO runs on a real TV with a residential IP -> "high trust" -> the widget usually
        // passes silently in a WebView, which is the use-case Cloudflare documents for mobile apps.
        TurnstileSolver.init(context)
        registerMainAPI(SerienstreamProvider())
    }
}
