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
        // resultUrl[0] holds the final hoster URL.
        val resultUrl = arrayOfNulls<String>(1)
        // extracted[0] = "/r?t=<token>" URL (from the episode page, bound to the WebView session),
        // extracted[1] = CSRF _token (from the episode page).
        val extracted = arrayOfNulls<String>(2)
        val mainHandler = Handler(Looper.getMainLooper())
        val webViewRef = arrayOfNulls<WebView>(1)
        // Phases: 0 = warm-up (episode), 1 = extract+gate, 2 = wait for Turnstile, 3 = submit, 4 = read answer.
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
                                // Episode-Seite geladen -> echte Seite abwarten (Form + __ddg),
                                // dann /r?t= Token + CSRF aus dem WebView extrahieren und Gate laden.
                                val warmupDeadline = System.currentTimeMillis() + 15_000L
                                checkEpisodePageReadyAndExtract(view, mainHandler, phase, latch,
                                    extracted, hosterIndex, episodePageUrl, warmupDeadline)
                            }
                            1 -> {
                                // Gate-Seite geladen. Auf Turnstile-Token warten, dann verify-init
                                // fetchen + ALTCHA + Form submitten.
                                phase.set(2)
                                val turnstileDeadline = System.currentTimeMillis() + 20_000L
                                pollForTurnstileThenSubmit(view, mainHandler, phase, latch, resultUrl,
                                    extracted, turnstileDeadline)
                            }
                            3 -> {
                                // Form submitted -> POST /r Antwort geladen. Body nach Hoster-URL durchsuchen.
                                phase.set(4)
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
     * DDoS-Guard challenge page. Once the real page is present, extract the /r?t= token
     * (data-play-url of the [hosterIndex]-th hoster button) AND the CSRF _token from the
     * WebView DOM. Both are stored in [extracted] (extracted[0] = gateUrl, extracted[1] = csrf).
     * Then load the gate URL in the SAME WebView so the token matches the WebView session.
     */
    private fun checkEpisodePageReadyAndExtract(
        webView: WebView,
        handler: Handler,
        phase: java.util.concurrent.atomic.AtomicInteger,
        latch: CountDownLatch,
        extracted: Array<String?>,
        hosterIndex: Int,
        episodePageUrl: String,
        deadlineMs: Long
    ) {
        if (System.currentTimeMillis() >= deadlineMs) {
            Log.w(TAG, "checkEpisodePageReadyAndExtract: TIMEOUT (no player-prepare-form)")
            latch.countDown()
            return
        }
        // JS: check form present, and if so, return JSON {gateUrl, csrf} for the hosterIndex-th button.
        val js = "(function(){try{var f=document.getElementById('player-prepare-form')||document.querySelector('form[action=\"/r\"]');if(!f)return 'noform';var btns=document.querySelectorAll('[data-play-url]');if(!btns||btns.length<=" + hosterIndex + ")return 'nobtn';var pu=btns[" + hosterIndex + "].getAttribute('data-play-url');var ti=f.querySelector('[name=\"_token\"]');var csrf=ti?ti.value:'';return JSON.stringify({gateUrl:pu,csrf:csrf});}catch(e){return 'err:'+e.message;}})();"
        try {
            webView.evaluateJavascript(js) { value ->
                if (value != null && value != "null" && value.length > 10 && value.startsWith("\"")) {
                    // Strip JSON quotes.
                    val raw = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
                    if (raw.startsWith("{") && raw.contains("gateUrl")) {
                        try {
                            val json = org.json.JSONObject(raw)
                            val gateUrl = json.optString("gateUrl")
                            val csrf = json.optString("csrf")
                            if (gateUrl.isNotEmpty()) {
                                // data-play-url is a RELATIVE path (/r?t=...). WebView.loadUrl does NOT
                                // resolve relative URLs against the current page (it treats "/r?t=..."
                                // as file:///r?t=... -> ERR_ACCESS_DENIED). Resolve it against the
                                // episode page URL to build an absolute https://serienstream.to/r?t=... URL.
                                val absoluteGateUrl = resolveAgainstEpisode(gateUrl, episodePageUrl)
                                extracted[0] = absoluteGateUrl
                                extracted[1] = csrf
                                // Echte Seite + Token extrahiert -> Cookies flushen, Gate laden.
                                try { CookieManager.getInstance().flush() } catch (_: Throwable) {}
                                val hasDdg = try {
                                    (CookieManager.getInstance().getCookie("https://serienstream.to") ?: "").contains("__ddg")
                                } catch (_: Throwable) { false }
                                Log.d(TAG, "episode page ready (form found, __ddg=$hasDdg), extracted gate token, loading gate: $absoluteGateUrl")
                                phase.set(1)
                                handler.postDelayed({
                                    if (latch.count > 0) {
                                        try { webView.loadUrl(absoluteGateUrl) } catch (_: Throwable) {}
                                    }
                                }, 300L)
                                return@evaluateJavascript
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "checkEpisodePageReadyAndExtract: JSON parse threw ${t.javaClass.name}: ${t.message}")
                        }
                    }
                }
                // Not ready yet -> poll again.
                handler.postDelayed({
                    if (phase.get() < 1 && latch.count > 0) {
                        checkEpisodePageReadyAndExtract(webView, handler, phase, latch, extracted, hosterIndex, episodePageUrl, deadlineMs)
                    }
                }, POLL_INTERVAL_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "checkEpisodePageReadyAndExtract: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (phase.get() < 1 && latch.count > 0) {
                    checkEpisodePageReadyAndExtract(webView, handler, phase, latch, extracted, hosterIndex, episodePageUrl, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }

    /**
     * Resolve a relative URL (e.g. "/r?t=...") against the episode page URL to build an absolute
     * https://serienstream.to/r?t=... URL. Uses java.net.URI (JDK, never R8-obfuscated).
     */
    private fun resolveAgainstEpisode(relative: String, episodePageUrl: String): String {
        return try {
            java.net.URI(episodePageUrl).resolve(relative).toString()
        } catch (_: Throwable) {
            // Fallback: manual prefix if it starts with "/".
            val base = try {
                val u = java.net.URI(episodePageUrl)
                "${u.scheme}://${u.host}"
            } catch (_: Throwable) { "https://serienstream.to" }
            if (relative.startsWith("/")) base + relative else relative
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
        extracted: Array<String?>,
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
                    Log.d(TAG, "Turnstile token obtained (${cleaned.take(20)}...), fetch verify-init + solve ALTCHA")
                    phase.set(3)
                    fetchVerifyInitAndSubmit(webView, handler, phase, latch, resultUrl, extracted)
                } else {
                    handler.postDelayed({
                        if (phase.get() < 3 && latch.count > 0) {
                            pollForTurnstileThenSubmit(webView, handler, phase, latch, resultUrl, extracted, deadlineMs)
                        }
                    }, POLL_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pollForTurnstile: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (phase.get() < 3 && latch.count > 0) {
                    pollForTurnstileThenSubmit(webView, handler, phase, latch, resultUrl, extracted, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }

    /**
     * Fetch /api/inline/verify-init from the WebView context (so it uses the WebView session cookies),
     * solve the ALTCHA PoW in Kotlin (java.security.MessageDigest), then inject JS that fills the
     * player-prepare-form with _token + t + altcha and submits it.
     */
    private fun fetchVerifyInitAndSubmit(
        webView: WebView,
        handler: Handler,
        phase: java.util.concurrent.atomic.AtomicInteger,
        latch: CountDownLatch,
        resultUrl: Array<String?>,
        extracted: Array<String?>
    ) {
        val csrf = extracted[1] ?: ""
        val gateUrl = extracted[0] ?: ""
        val tToken = try {
            val raw = gateUrl.substringAfter("t=").substringBefore("&")
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Throwable) {
            gateUrl.substringAfter("t=").substringBefore("&")
        }
        val cs = escapeJsString(csrf)
        val ts = escapeJsString(tToken)
        // JS: fetch verify-init (runs in the WebView so it sends the WebView session cookies), return JSON text.
        val fetchJs = "(function(){return fetch('/api/inline/verify-init',{credentials:'include'}).then(function(r){return r.text();}).then(function(t){return t;}).catch(function(e){return 'err:'+e.message;});})();"
        try {
            webView.evaluateJavascript(fetchJs) { value ->
                val raw = if (value != null && value != "null" && value.startsWith("\"") && value.endsWith("\"")) {
                    value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n").replace("\\\\", "\\")
                } else value ?: ""
                if (raw.startsWith("{") && raw.contains("challenge")) {
                    val payload = try {
                        solveAltcha(raw)
                    } catch (t: Throwable) {
                        Log.w(TAG, "fetchVerifyInitAndSubmit: solveAltcha threw ${t.javaClass.name}: ${t.message}")
                        ""
                    }
                    if (payload.isEmpty()) {
                        Log.w(TAG, "fetchVerifyInitAndSubmit: ALTCHA PoW not solvable")
                        latch.countDown()
                        return@evaluateJavascript
                    }
                    Log.d(TAG, "ALTCHA PoW solved (payload ${payload.length} chars), submitting form")
                    val ap = escapeJsString(payload)
                    val submitJs = buildSubmitJs(cs, ts, ap)
                    try {
                        webView.evaluateJavascript(submitJs) { _ ->
                            Log.d(TAG, "form submit injected")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "submit inject threw ${t.javaClass.name}: ${t.message}")
                        latch.countDown()
                    }
                } else {
                    Log.w(TAG, "fetchVerifyInitAndSubmit: verify-init fetch failed: ${raw.take(80)}")
                    latch.countDown()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchVerifyInitAndSubmit: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            latch.countDown()
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
