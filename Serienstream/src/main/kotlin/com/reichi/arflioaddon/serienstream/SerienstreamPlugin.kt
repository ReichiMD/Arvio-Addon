package com.reichi.arflioaddon.serienstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SerienstreamPlugin : Plugin() {
    override fun load(context: Context) {
        android.util.Log.d("ArvioAddon[SerienstreamPlugin]", "load() — provider DISABLED (Turnstile+ALTCHA gate)")
        // Disabled: the /r redirect gate requires BOTH Cloudflare Turnstile (browser-based CAPTCHA,
        // sitekey 0x4AAAAAAAFBfchmT6XFij7y) AND ALTCHA-PoW. We solve the PoW, but cannot solve
        // Turnstile without a browser engine, so the server rejects the POST /r with
        // "Das hat leider nicht geklappt" -> 0 sources. Re-enable when a Turnstile bypass exists.
        // registerMainAPI(SerienstreamProvider())
    }
}
