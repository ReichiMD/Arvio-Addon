package com.reichi.arflioaddon.serienstream

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Turnstile solver that uses a real Android WebView to render the Turnstile widget
 * and capture its `cf-turnstile-response` token.
 *
 * Why a WebView (not a token-solver API): Cloudflare Turnstile validates the visitor's identity
 * from THREE signals simultaneously — residential IP, real browser fingerprint (canvas/WebGL/
 * fonts/audio) and behaviour (mouse/timing). A token bought from 2captcha/CapSolver was minted on
 * a DIFFERENT IP/fingerprint and Cloudflare rejects it ("Das hat leider nicht geklappt").
 * ARVIO runs on a real Android TV with a residential IP, which Turnstile rates as "high trust",
 * so the widget usually passes silently in Managed/Non-Interactive mode without any user click.
 * That is the use-case Cloudflare itself documents for native mobile apps (load a small Turnstile
 * page in a WebView, pass the token to native code).
 *
 * Flow:
 *  1. The plugin captures an Activity-Context in load(context) (ARVIO passes activity as Context).
 *  2. [solveTurnstileToken] is called from resolveRedirectGate with the /r?t=<token> redirect URL.
 *  3. A WebView is created on the main thread, JavaScript enabled, cookies enabled.
 *  4. The redirect URL is loaded. The page renders the Turnstile widget inside an iframe.
 *  5. We poll (every 500ms) for the cf-turnstile-response token via evaluateJavascript, checking
 *     both the hidden form input and turnstile.getResponse().
 *  6. On success the token is returned to the caller (non-suspend, blocking via CountDownLatch).
 *  7. Cookies from the WebView (DDoS-Guard session + XSRF) are exported into the caller's CookieJar
 *     so the subsequent java.net POST /r uses the same session.
 *
 * The whole call blocks the calling (network) thread for up to [DEFAULT_TIMEOUT_MS]. This mirrors
 * the other java.net calls in the provider which are also blocking. ARVIO's per-call timeout
 * (LOADLINKS_TIMEOUT_MS=60s) comfortably covers a 20s Turnstile solve.
 */
internal object TurnstileSolver {

    private const val TAG = "ArvioAddon[TurnstileSolver]"
    private const val DEFAULT_TIMEOUT_MS = 45_000L
    private const val POLL_INTERVAL_MS = 500L

    /**
     * Set by SerienstreamPlugin.load(context). ARVIO passes `activity as Context? ?: context`, so
     * when launched from the player this is an Activity context — required to build a WebView.
     * If only an Application context is available WebView creation still works but some Chrome
     * features are limited; for Turnstile it is sufficient.
     */
    @Volatile
    private var context: Context? = null

    fun init(ctx: Context) {
        // Keep the application context to avoid leaking an Activity.
        context = ctx.applicationContext
        Log.d(TAG, "init: context saved (applicationContext)")
    }

    fun isAvailable(): Boolean = context != null

    /**
     * Result of a Turnstile solve: the token (cf-turnstile-response value) plus the cookies that
     * the WebView collected while loading the page (so the caller can reuse the same session).
     */
    data class SolveResult(val token: String, val cookies: String)

    /**
     * STRATEGIE D — der WebView macht den GESAMTEN /r-Gate-Flow in EINER Session:
     *   1. Lädt die Episode-Seite (sammelt Session-Cookies: __ddg*, Laravel session, XSRF-TOKEN).
     *   2. Lädt die /r?t=<token> Gate-Seite (rendert Turnstile-Widget + ALTCHA-Formular).
     *   3. Wartet bis Turnstile durchgelaufen ist (cf-turnstile-response da).
     *   4. Injiziert das vorab berechnete ALTCHA-PoW-Payload + CSRF + t-Token in das Formular und
     *      submittet es (mit dem Turnstile-Token, den das Widget automatisch eingesetzt hat).
     *   5. Die POST /r Antwort ist eine HTML/JS-Seite; bei Erfolg enthält sie `var t = "<hoster-url>"`.
     *      Wir extrahieren die Hoster-URL aus dem Body.
     *
     * Weil alles im selben WebView läuft, gibt es KEINEN Cookie/Session-Mismatch mehr (das war das
     * Problem bei Strategie A: java.net-Preflight hat die Session geholt, aber der WebView hatte
     * eine eigene Session -> /r?t= redirectete auf die Startseite). Der WebView besitzt hier die
     * Session von Anfang an, der /r?t= Token gehört zu IHR, und der POST /r geht aus derselben
     * Session heraus.
     *
     * [altchaPayload] = base64(JSON{algorithm,challenge,number,salt,signature}), vom Provider via
     * solveAltcha() berechnet (java.security.MessageDigest, JDK). Das ALTCHA-PoW machen wir weiterhin
     * in Kotlin, weil die Web Crypto API in evaluateJavascript umständlich wäre (async/await + ArrayBuffer).
     *
     * [csrfToken] = der _token-Wert aus der Episode-Seite (CSRF).
     * [tToken] = der dekodierte t-Token aus der /r?t= URL.
     *
     * Gibt die finale Hoster-URL zurück (z.B. https://voe.sx/e/...), oder null bei Timeout/Fehler.
     */
    fun solveGate(
        episodePageUrl: String,
        hosterIndex: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        val ctx = context ?: run {
            Log.w(TAG, "solveGate: no context (init not called?)")
            return null
        }
        val latch = CountDownLatch(1)
        val resultUrl = arrayOfNulls<String>(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val webViewRef = arrayOfNulls<WebView>(1)
        // Phase 0 = warm-up (episode page), 1 = iframe + gate flow running, 2 = done.
        val phase = java.util.concurrent.atomic.AtomicInteger(0)
        // JS bridge: the injected script calls Bridge.onResult(url) when the gate returns the
        // hoster URL (postMessage from the iframe), or Bridge.onResult("") on error/timeout.
        val bridge = object {
            @android.webkit.JavascriptInterface
            fun onResult(url: String) {
                Log.d(TAG, "bridge.onResult: url=$url")
                if (resultUrl[0] == null) {
                    resultUrl[0] = if (url.isNotEmpty()) url else null
                    latch.countDown()
                }
            }
            @android.webkit.JavascriptInterface
            fun onLog(msg: String) {
                Log.d(TAG, "bridge.log: $msg")
            }
        }

        mainHandler.post {
            try {
                val webView = WebView(ctx)
                webViewRef[0] = webView
                val settings = webView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; TCL C7K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                webView.addJavascriptInterface(bridge, "Bridge")

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                        val msg = consoleMessage.message()
                        val line = consoleMessage.lineNumber()
                        Log.d(TAG, "js:[$line] $msg")
                        return true
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String?) {
                        Log.d(TAG, "onPageFinished: phase=${phase.get()} url=$loadedUrl")
                        when (phase.get()) {
                            0 -> {
                                // Episode-Seite geladen -> echte Seite abwarten (Form + __ddg),
                                // dann den Gate-Flow per injiziertem JS starten (iframe laden).
                                val warmupDeadline = System.currentTimeMillis() + 15_000L
                                checkEpisodePageReadyAndStartGate(view, mainHandler, phase, latch,
                                    hosterIndex, episodePageUrl, warmupDeadline)
                            }
                            else -> {
                                // Gate-Flow lГ¤uft im JS. Bridge.onResult wird den Latch lГ¶sen.
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        Log.w(TAG, "onReceivedError: ${error?.description} (${request?.url})")
                    }
                }
                Log.d(TAG, "solveGate: loadUrl (warm-up): $episodePageUrl")
                webView.loadUrl(episodePageUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "webView create threw ${t.javaClass.name}: ${t.message}")
                latch.countDown()
            }
        }

        try {
            val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!got) {
                Log.w(TAG, "solveGate: TIMEOUT after ${timeoutMs}ms (phase=${phase.get()})")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Destroy the WebView on the main thread.
        val webView = webViewRef[0]
        if (webView != null) {
            mainHandler.post {
                try {
                    (webView as WebView).apply {
                        stopLoading()
                        loadUrl("about:blank")
                        clearHistory()
                        setWebViewClient(WebViewClient())
                        setWebChromeClient(null)
                        removeAllViews()
                        destroy()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "webView destroy threw ${t.javaClass.name}: ${t.message}")
                }
            }
        }

        val resolved = resultUrl[0]
        if (resolved == null) {
            Log.w(TAG, "solveGate: no hoster URL produced (phase=${phase.get()})")
            return null
        }
        Log.d(TAG, "solveGate: resolved to $resolved")
        return resolved
    }

    /**
     * Check whether the episode page is the REAL page (contains player-prepare-form) or a
     * DDoS-Guard challenge page. Once the real page is present, inject a JS that loads the
     * /r?t=<token> URL into the existing hidden player-iframe (this is how Serienstream's own
     * episode-redirect-gate JS works: it sets playerIframe.src = gateUrl, the iframe page does
     * a postMessage back to the parent with the prepare-token, then the parent renders Turnstile +
     * ALTCHA and submits the form, whose response (also loaded in the iframe) postMessages the
     * final hoster URL). We reuse the page's OWN gate JS by setting the iframe src, then poll for
     * the form being submittable and submit it programmatically. The postMessage from the final
     * iframe response is captured by our window message listener and forwarded to Bridge.onResult.
     */
    private fun checkEpisodePageReadyAndStartGate(
        webView: WebView,
        handler: Handler,
        phase: java.util.concurrent.atomic.AtomicInteger,
        latch: CountDownLatch,
        hosterIndex: Int,
        episodePageUrl: String,
        deadlineMs: Long
    ) {
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.w(TAG, "checkEpisodePageReadyAndStartGate: TIMEOUT (no player-prepare-form)")
            latch.countDown()
            return
        }
        // JS: check form present, and if so, return the absolute gate URL for the hosterIndex-th button.
        val probeJs = "(function(){try{var f=document.getElementById('player-prepare-form')||document.querySelector('form[action=\"/r\"]');if(!f)return 'noform';var btns=document.querySelectorAll('[data-play-url]');if(!btns||btns.length<=" + hosterIndex + ")return 'nobtn';var pu=btns[" + hosterIndex + "].getAttribute('data-play-url');return new URL(pu,window.location.origin).href;}catch(e){return 'err:'+e.message;}})();"
        try {
            webView.evaluateJavascript(probeJs) { value ->
                if (value != null && value != "null" && value.length > 10 && value.startsWith("\"")) {
                    val gateUrl = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
                    if (gateUrl.startsWith("http")) {
                        try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                        val hasDdg = try {
                            (CookieManager.getInstance().getCookie("https://serienstream.to") ?: "").contains("__ddg")
                        } catch (_: Throwable) { false }
                        Log.d(TAG, "episode page ready (form found, __ddg=$hasDdg), starting gate flow: $gateUrl")
                        phase.set(1)
                        // Inject the gate-flow driver JS. It:
                        //  1. Installs a window 'message' listener that captures the frameBridge
                        //     postMessages (both the prepare-token from the initial iframe load AND
                        //     the final hoster-URL from the POST /r response).
                        //  2. Sets player-iframe.src = gateUrl (triggers the page's own gate JS,
                        //     which renders Turnstile + ALTCHA into the modal).
                        //  3. Polls until the Turnstile + ALTCHA fields are filled, then submits the
                        //     form programmatically (a.submit()).
                        //  4. The form's POST /r response loads in the same iframe; its postMessage
                        //     carries the final hoster URL, which our listener forwards to Bridge.onResult.
                        val driverJs = buildGateDriverJs(gateUrl)
                        try {
                            webView.evaluateJavascript(driverJs) { _ ->
                                Log.d(TAG, "gate driver JS injected")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "gate driver inject threw ${t.javaClass.name}: ${t.message}")
                            latch.countDown()
                        }
                        return@evaluateJavascript
                    }
                }
                // Not ready yet -> poll again.
                handler.postDelayed({
                    if (phase.get() < 1 && latch.count > 0) {
                        checkEpisodePageReadyAndStartGate(webView, handler, phase, latch, hosterIndex, episodePageUrl, deadlineMs)
                    }
                }, POLL_INTERVAL_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkEpisodePageReadyAndStartGate: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (phase.get() < 1 && latch.count > 0) {
                    checkEpisodePageReadyAndStartGate(webView, handler, phase, latch, hosterIndex, episodePageUrl, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }

    /**
     * Build the JS that drives the entire gate flow inside the episode page:
     *  - install a window 'message' listener for frameBridge messages
     *  - set player-iframe.src = gateUrl (loads the /r?t= page in the iframe, which postMessages
     *    the prepare-token; the page's own gate JS then renders Turnstile + ALTCHA)
     *  - poll for cf-turnstile-response + altcha being filled, then submit the form
     *  - the POST /r response (in the iframe) postMessages the final hoster URL -> Bridge.onResult
     */
    private fun buildGateDriverJs(gateUrl: String): String {
        // Escape gateUrl for embedding in a JS string literal.
        val esc = gateUrl.replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  try{
  function log(m){try{console.log(m);}catch(e){}try{Bridge.onLog(m);}catch(e){}}
  function done(u){try{Bridge.onResult(u||'');}catch(e){try{console.log('DONE:'+(u||''));}catch(e2){}}}
  console.log('driver start');
  log('driver start2');
  var started=false;
  window.addEventListener('message',function(e){
    var d=e.data;
    if(!d||d.type!=='frameBridge'||d.v!==1)return;
    log('frameBridge: t='+(d.t||'').slice(0,40)+' err='+(d.err||''));
    if(d.err&&d.err.length){done('');return;}
    if(d.t&&d.t.indexOf('http')===0){done(d.t);return;}
    if(d.t&&!started){started=true;pollAndSubmit();}
  },false);
  var f=document.getElementById('player-prepare-form')||document.querySelector('form[action="/r"]');
  log('form found='+(!!f));
  if(f){
    if(!f.getAttribute('target')){f.setAttribute('target','player-iframe');}
  }
  var iframe=document.getElementById('player-iframe');
  log('iframe found='+(!!iframe));
  if(!iframe){log('no player-iframe');done('');return;}
  iframe.src='$esc';
  log('iframe.src set, waiting for frameBridge');
  function pollAndSubmit(){
    var tries=0;
    var iv=setInterval(function(){
      tries++;
      if(tries>80){clearInterval(iv);log('submit poll timeout');done('');return;}
      var f=document.getElementById('player-prepare-form')||document.querySelector('form[action="/r"]');
      if(!f){return;}
      var ts=f.querySelector('[name=cf-turnstile-response]');
      var al=f.querySelector('[name=altcha]');
      var tsVal=(ts&&ts.value&&ts.value.length>10)?ts.value:'';
      var alVal=(al&&al.value&&al.value.length>10)?al.value:'';
      if(tries%10===1){
        var tp=document.getElementById('player-prepare-turnstile');
        var ap=document.getElementById('player-prepare-altcha');
        var modal=document.getElementById('playerPrepareModal');
        var tpLen=tp?tp.innerHTML.length:0;
        var tpIframe=tp&&tp.querySelector('iframe')?1:0;
        var apLen=ap?ap.innerHTML.length:0;
        var apWidget=ap&&ap.querySelector('altcha-widget')?1:0;
        var modalVis=modal&&modal.classList.contains('show')?'show':'hidden';
        var errEl=document.getElementById('player-prepare-error');
        var errTxt=errEl?(errEl.textContent||''):'';
        log('diag try='+tries+': ts='+tsVal.slice(0,8)+' al='+alVal.slice(0,8)+' turnstile='+tpLen+'c,if='+tpIframe+' altcha='+apLen+'c,w='+apWidget+' modal='+modalVis+' err='+errTxt.slice(0,30));
      }
      if(tsVal&&alVal){
        clearInterval(iv);
        log('turnstile+altcha ready, submitting form');
        try{f.submit();}catch(e){log('submit threw: '+e.message);done('');}
        return;
      }
    },500);
  }
  }catch(e){try{console.log('driver threw: '+e.message);}catch(e2){}}
})();
""".trimIndent()
    }

    /**
     * Resolve a relative URL (e.g. "/r?t=...") against the episode page URL to build an absolute
     * https://serienstream.to/r?t=... URL. Uses java.net.URI (JDK, never R8-obfuscated).
     */
    private fun resolveAgainstEpisode(relative: String, episodePageUrl: String): String {
        return try {
            java.net.URI(episodePageUrl).resolve(relative).toString()
        } catch (_: Throwable) {
            val base = try {
                val u = java.net.URI(episodePageUrl)
                "${u.scheme}://${u.host}"
            } catch (_: Throwable) { "https://serienstream.to" }
            if (relative.startsWith("/")) base + relative else relative
        }
    }

    /**
     * ALTCHA Proof-of-Work solver. Given the verify-init JSON response, finds n in 0..maxnumber
     * where SHA-256(salt + str(n)) == challenge, then returns base64(JSON payload). Uses
     * java.security.MessageDigest (JDK, never R8-obfuscated). Called from fetchVerifyInitAndSubmit
     * after the WebView fetches verify-init.
     */
    internal fun solveAltcha(initJson: String): String {
        val obj = org.json.JSONObject(initJson)
        val algorithm = obj.optString("algorithm", "SHA-256")
        val challenge = obj.optString("challenge", "")
        val salt = obj.optString("salt", "")
        val maxnumber = obj.optInt("maxnumber", 100000)
        val signature = obj.optString("signature", "")
        if (challenge.isEmpty() || salt.isEmpty()) return ""
        if (!algorithm.equals("SHA-256", ignoreCase = true)) {
            Log.w(TAG, "solveAltcha: unsupported algorithm '$algorithm'")
            return ""
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var solution = -1
        for (n in 0..maxnumber) {
            val input = (salt + n.toString()).toByteArray(Charsets.UTF_8)
            val hash = md.digest(input)
            val hex = StringBuilder(hash.size * 2)
            for (b in hash) {
                val v = b.toInt() and 0xFF
                hex.append("0123456789abcdef"[v ushr 4])
                hex.append("0123456789abcdef"[v and 0x0F])
            }
            if (hex.toString() == challenge) {
                solution = n
                break
            }
        }
        if (solution < 0) {
            Log.w(TAG, "solveAltcha: no solution found in 0..$maxnumber")
            return ""
        }
        Log.d(TAG, "solveAltcha: PoW solved, n=$solution")
        val payloadObj = org.json.JSONObject()
        payloadObj.put("algorithm", algorithm)
        payloadObj.put("challenge", challenge)
        payloadObj.put("number", solution)
        payloadObj.put("salt", salt)
        payloadObj.put("signature", signature)
        val payloadJson = payloadObj.toString()
        return android.util.Base64.encodeToString(payloadJson.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }
}
