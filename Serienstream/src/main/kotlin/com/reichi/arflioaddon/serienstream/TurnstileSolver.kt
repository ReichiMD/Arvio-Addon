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
    private const val DEFAULT_TIMEOUT_MS = 30_000L
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
     * Load [turnstileUrl] (the /r?t=<token> redirect page that renders the Turnstile widget) in a
     * WebView, wait for the cf-turnstile-response token, and return it together with the WebView
     * cookies.
     *
     * [episodePageUrl] is loaded FIRST so the WebView collects the Serienstream session cookies
     * (DDoS-Guard __ddg*, Laravel session, XSRF-TOKEN). Without this warm-up the /r?t= token is
     * bound to a different session and Serienstream redirects to the homepage (no Turnstile widget
     * renders -> no token). This mirrors what a real browser does: visit the episode page, then
     * click the hoster button which opens /r?t=.
     *
     * Blocks the calling thread up to [timeoutMs]. Returns null if no token was produced in time
     * (timeout, page failed to load, no Turnstile widget present, etc.).
     */
    fun solveTurnstileToken(
        turnstileUrl: String,
        episodePageUrl: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SolveResult? {
        val ctx = context ?: run {
            Log.w(TAG, "solveTurnstileToken: no context (init not called?)")
            return null
        }
        val latch = CountDownLatch(1)
        // token[0] holds the result; nullable to allow in-place mutation from the main thread.
        val token = arrayOfNulls<String>(1)
        val mainHandler = Handler(Looper.getMainLooper())

        // Capture the webView reference on the main thread so we can destroy it on the main thread.
        val webViewRef = arrayOfNulls<WebView>(1)
        // Phases: 0 = warm-up (episode page), 1 = gate (/r?t=), 2 = done.
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
                // A real desktop Chrome UA keeps Turnstile in "high trust" / non-interactive mode.
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; TCL C7K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                // Accept third-party cookies so the Cloudflare challenge iframe can set its cookies.
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                webView.webChromeClient = WebChromeClient()
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String?) {
                        Log.d(TAG, "onPageFinished: phase=${phase.get()} url=$loadedUrl")
                        when (phase.get()) {
                            0 -> {
                                // Warm-up done (episode page loaded, cookies collected). Persist the
                                // cookies NOW so the subsequent gate load sends them. CookieManager
                                // keeps cookies in memory but the WebView only sends them on the next
                                // navigation once they are committed; flush() forces that.
                                try {
                                    CookieManager.getInstance().flush()
                                } catch (_: Throwable) {}
                                // Small delay so the Set-Cookie headers from the episode page are
                                // captured before we navigate to the gate.
                                phase.set(1)
                                mainHandler.postDelayed({
                                    if (latch.count > 0) {
                                        Log.d(TAG, "warm-up done, loading gate: $turnstileUrl")
                                        try { view.loadUrl(turnstileUrl) } catch (_: Throwable) {}
                                    }
                                }, 300L)
                            }
                            1 -> {
                                // Gate page loaded. Start polling for the Turnstile token.
                                pollForToken(view, mainHandler, token, latch, timeoutMs)
                            }
                            else -> {
                                // Already polling or done.
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
                Log.d(TAG, "loadUrl (warm-up): $episodePageUrl")
                webView.loadUrl(episodePageUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "webView create threw ${t.javaClass.name}: ${t.message}")
                latch.countDown()
            }
        }

        try {
            // Block the calling (network) thread until token arrives or timeout.
            val got = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!got) {
                Log.w(TAG, "solveTurnstileToken: TIMEOUT after ${timeoutMs}ms")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Destroy the WebView on the main thread to release resources.
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

        val resolved = token[0]
        if (resolved == null || resolved.length < 10) {
            Log.w(TAG, "solveTurnstileToken: no token produced")
            return null
        }
        // Export cookies for the gate URL (the domain the POST /r will target).
        val cookies = try {
            CookieManager.getInstance().getCookie(turnstileUrl) ?: ""
        } catch (_: Throwable) { "" }
        Log.d(TAG, "solveTurnstileToken: token=${resolved.take(24)}… cookies=${cookies.length} chars")
        return SolveResult(resolved, cookies)
    }

    /**
     * Poll the loaded page for the cf-turnstile-response token. The widget writes the token into
     * a hidden form input named `cf-turnstile-response`; the JS API `turnstile.getResponse()`
     * returns the same value once solved. We check both, every 500ms, until a token appears or
     * the deadline expires.
     */
    private fun pollForToken(
        webView: WebView,
        handler: Handler,
        token: Array<String?>,
        latch: CountDownLatch,
        deadlineMs: Long
    ) {
        if (System.currentTimeMillis() >= deadlineMs) {
            latch.countDown()
            return
        }
        // JS that returns the token if present, else null. evaluateJavascript wraps the result in
        // double quotes (a JSON string) or the literal "null".
        val js = "(function(){try{var el=document.querySelector('[name=cf-turnstile-response]');if(el&&el.value)return el.value;}catch(e){}try{return turnstile.getResponse();}catch(e){}return null;})();"
        try {
            webView.evaluateJavascript(js) { value ->
                if (value != null && value != "null" && value.length > 10) {
                    // Strip the surrounding JSON quotes that evaluateJavascript adds.
                    val cleaned = if (value.startsWith("\"") && value.endsWith("\"")) {
                        value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\/", "/")
                    } else value
                    token[0] = cleaned
                    latch.countDown()
                } else {
                    handler.postDelayed({
                        if (!latch.await(0, TimeUnit.MILLISECONDS)) {
                            pollForToken(webView, handler, token, latch, deadlineMs)
                        }
                    }, POLL_INTERVAL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pollForToken: evaluateJavascript threw ${t.javaClass.name}: ${t.message}")
            handler.postDelayed({
                if (!latch.await(0, TimeUnit.MILLISECONDS)) {
                    pollForToken(webView, handler, token, latch, deadlineMs)
                }
            }, POLL_INTERVAL_MS)
        }
    }
}
