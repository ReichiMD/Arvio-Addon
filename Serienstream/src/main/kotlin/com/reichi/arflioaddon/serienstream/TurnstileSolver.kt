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
        gateUrl: String,
        csrfToken: String,
        tToken: String,
        altchaPayload: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        val ctx = context ?: run {
            Log.w(TAG, "solveGate: no context (init not called?)")
            return null
        }
        val latch = CountDownLatch(1)
        // resultUrl[0] holds the final hoster URL.
        val resultUrl = arrayOfNulls<String>(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val webViewRef = arrayOfNulls<WebView>(1)
        // Phases: 0 = warm-up (episode), 1 = gate (/r?t=, wait for Turnstile), 2 = submit, 3 = read answer.
        val phase = java.util.concurrent.atomic.AtomicInteger(0)

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

                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String?) {
                        Log.d(TAG, "onPageFinished: phase=${phase.get()} url=$loadedUrl")
                        when (phase.get()) {
                            0 -> {
                                // Episode-Seite geladen -> prГјfen ob es die ECHTE Seite ist (enthГӨlt
                                // player-prepare-form) oder eine DDoS-Guard-Challenge-Seite. Der WebView
                                // hat eine frische Session ohne __ddg-Cookies, deshalb liefert die
                                // Episode-Seite evtl. erst eine DDoS-Guard-Challenge (JS, das __ddg
                                // setzt + weiterleitet). onPageFinished feuert fГјr die Challenge, nicht
                                // die echte Seite -> wГјrden das Gate ohne __ddg laden -> Gate redirectet
                                // auf die Startseite. Also: auf die echte Seite warten (Form vorhanden),
                                // erst dann Gate laden.
                                val warmupDeadline = System.currentTimeMillis() + 15_000L
                                checkEpisodePageReady(view, mainHandler, phase, latch, gateUrl, warmupDeadline)
                            }
                            1 -> {
                                // Gate-Seite geladen. Auf Turnstile-Token warten, dann Form submitten.
                                // deadlineMs = currentTimeMillis + remaining timeout (not the raw
                                // timeoutMs, which would be interpreted as an absolute epoch time).
                                val deadline = System.currentTimeMillis() + 20_000L
                                pollForTurnstileThenSubmit(view, mainHandler, phase, latch, resultUrl,
                                    csrfToken, tToken, altchaPayload, deadline)
                            }
                            2 -> {
                                // Form submitted -> POST /r Antwort geladen. Body nach Hoster-URL durchsuchen.
                                phase.set(3)
                                extractHosterUrlFromBody(view, mainHandler, latch, resultUrl)
                            }
                            else -> {
                                // Already done.
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
     * DDoS-Guard challenge page. If the form is present, the page is ready -> load the gate.
     * If not, poll again every 500ms (the DDoS-Guard JS is still running, setting __ddg cookies
     * and will redirect to the real page). Timeout if the form never appears.
     */
    private fun checkEpisodePageReady(
        webView: WebView,
        handler: Handler,
        phase: java.util.concurrent.atomic.AtomicInteger,
        latch: CountDownLatch,
        gateUrl: String,
        deadlineMs: Long
    ) {
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.w(TAG, "checkEpisodePageReady: TIMEOUT (no player-prepare-form)")
            latch.countDown()
            return
        }
        val js = "(function(){try{return !!document.getElementById('player-prepare-form')||!!document.querySelector('form[action=\"/r\"]');}catch(e){return false;}})();"
        try {
            webView.evaluateJavascript(js) { value ->
                if (value == "true") {
                    // Echte Seite -> Cookies flushen, dann Gate laden.
                    try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                    val hasDdg = try {
                        (CookieManager.getInstance().getCookie("https://serienstream.to") ?: "").contains("__ddg")
                    } catch (_: Throwable) { false }
                    Log.d(TAG, "episode page ready (form found, __ddg=$hasDdg), loading gate")
                    phase.set(1)
                    handler.postDelayed({
                        if (latch.count > 0) {
                            Log.d(TAG, "warm-up done, loading gate: $gateUrl")
                            try { webView.loadUrl(gateUrl) } catch (_: Throwable) {}
                        }
                    }, 300L)
                } else {
                    // Noch Challenge-Seite -> __ddg-Cookies fehlen, weiter pollen.
                    handler.postDelayed({
                        if (phase.get() < 1 && latch.count > 0) {
                            checkEpisodePageReady(webView, handler, phase, latch, gateUrl, deadlineMs)
                        }
                    }, POLL_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkEpisodePageReady: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (phase.get() < 1 && latch.count > 0) {
                    checkEpisodePageReady(webView, handler, phase, latch, gateUrl, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }

    /**
     * Poll the gate page for the cf-turnstile-response token. Once present, inject JS that fills
     * the player-prepare-form with _token + t + altcha (Turnstile already filled its own field)
     * and submits it. The submission triggers a navigation to the POST /r response page.
     */
    private fun pollForTurnstileThenSubmit(
        webView: WebView,
        handler: Handler,
        phase: java.util.concurrent.atomic.AtomicInteger,
        latch: CountDownLatch,
        resultUrl: Array<String?>,
        csrfToken: String,
        tToken: String,
        altchaPayload: String,
        deadlineMs: Long
    ) {
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.w(TAG, "pollForTurnstile: TIMEOUT before token")
            latch.countDown()
            return
        }
        val js = "(function(){try{var el=document.querySelector('[name=cf-turnstile-response]');if(el&&el.value)return el.value;}catch(e){}try{return turnstile.getResponse();}catch(e){}return null;})();"
        try {
            webView.evaluateJavascript(js) { value ->
                if (value != null && value != "null" && value.length > 10) {
                    val cleaned = if (value.startsWith("\"") && value.endsWith("\"")) {
                        value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\/", "/")
                    } else value
                    Log.d(TAG, "Turnstile token erhalten (${cleaned.take(20)}…), submitten")
                    // Inject the form fields + submit. The form id on the gate page is "player-prepare-form".
                    phase.set(2)
                    val submitJs = buildSubmitJs(csrfToken, tToken, altchaPayload)
                    try {
                        webView.evaluateJavascript(submitJs) { _ ->
                            // Submission triggers a navigation; onPageFinished (phase 2) reads the body.
                            Log.d(TAG, "form submit injected")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "submit inject threw ${t.javaClass.name}: ${t.message}")
                        latch.countDown()
                    }
                } else {
                    handler.postDelayed({
                        if (phase.get() < 2 && latch.count > 0) {
                            pollForTurnstileThenSubmit(webView, handler, phase, latch, resultUrl,
                                csrfToken, tToken, altchaPayload, deadlineMs)
                        }
                    }, POLL_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pollForTurnstile: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (phase.get() < 2 && latch.count > 0) {
                    pollForTurnstileThenSubmit(webView, handler, phase, latch, resultUrl,
                        csrfToken, tToken, altchaPayload, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }

    /**
     * Build the JavaScript that fills the player-prepare-form and submits it. The form fields are:
     *   - _token (CSRF, hidden input already present, we overwrite)
     *   - t (hidden input id="player-prepare-token")
     *   - altcha (hidden input name="altcha")
     *   - cf-turnstile-response (already set by the Turnstile widget)
     * We set the fields directly and call form.submit() (not HTMLFormElement.requestSubmit, which
     * needs newer API). escapeJsString guards against quotes/backslashes in the tokens.
     */
    private fun buildSubmitJs(csrfToken: String, tToken: String, altchaPayload: String): String {
        val cs = escapeJsString(csrfToken)
        val ts = escapeJsString(tToken)
        val ap = escapeJsString(altchaPayload)
        return "(function(){" +
            "var f=document.getElementById('player-prepare-form')||document.querySelector('form[action=\"/r\"]');" +
            "if(!f){window.__gateError='no form';return;}" +
            "function setField(name,val){var i=f.querySelector('[name=\"'+name+'\"]');if(!i){i=document.createElement('input');i.type='hidden';i.name=name;f.appendChild(i);}i.value=val;}" +
            "setField('_token','$cs');" +
            "setField('t','$ts');" +
            "setField('altcha','$ap');" +
            "f.submit();" +
            "})();"
    }

    private fun escapeJsString(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    /**
     * The POST /r response is an HTML/JS page. On success it contains `var t = "<hoster-url>"`
     * (a postMessage to the parent iframe). We read document.body.innerHTML and extract the URL.
     * Also check the final loaded URL (in case of a redirect to the hoster) and a generic hoster-URL
     * regex as fallback.
     */
    private fun extractHosterUrlFromBody(
        webView: WebView,
        handler: Handler,
        latch: CountDownLatch,
        resultUrl: Array<String?>
    ) {
        val js = "(function(){try{return document.body?document.body.innerHTML:'';}catch(e){return '';}})();"
        try {
            webView.evaluateJavascript(js) { body ->
                val html = if (body != null && body != "null" && body.length > 2) {
                    if (body.startsWith("\"") && body.endsWith("\"")) {
                        // evaluateJavascript returns a JSON-quoted string; un-escape minimally.
                        body.substring(1, body.length - 1)
                            .replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n").replace("\\\\", "\\")
                    } else body
                } else ""
                val url = parseHosterUrl(html) ?: try { webView.url } catch (_: Throwable) { null }
                if (url != null && url.startsWith("http") && !url.contains("serienstream.to/r")) {
                    resultUrl[0] = url
                    latch.countDown()
                } else {
                    // No hoster URL in body -> err="..." (server rejected). Surface the err for logging.
                    val err = Regex("""var\s+err\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1) ?: ""
                    Log.w(TAG, "extractHosterUrl: no hoster URL in POST /r body${if (err.isNotEmpty()) " (err=$err)" else ""}")
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "extractHosterUrl threw ${t.javaClass.name}: ${t.message}")
            latch.countDown()
        }
    }

    /** Extract the hoster URL from the POST /r response body: `var t = "<url>"` or a direct hoster link. */
    private fun parseHosterUrl(html: String): String? {
        Regex("""var\s+t\s*=\s*"([^"]+)"""").find(html)?.let {
            if (it.groupValues[1].startsWith("http")) return it.groupValues[1]
        }
        Regex("""(https?://[^\s"'<>]*(?:voe|dood|ds2play|streamtape|filemoon|vidhide|vidhd|playmogo|vidply|vidoza)[^\s"'<>]*)""",
            RegexOption.IGNORE_CASE).find(html)?.let { return it.groupValues[1] }
        return null
    }

    // (Legacy pollForToken removed — replaced by pollForTurnstileThenSubmit in the Strategie D flow.)
}
