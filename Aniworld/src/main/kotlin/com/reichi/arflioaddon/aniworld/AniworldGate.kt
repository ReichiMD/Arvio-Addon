package com.reichi.arflioaddon.aniworld

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AniWorld's browser check in a hidden WebView - the Serienstream approach (TurnstileSolver),
 * rebuilt for AniWorld's page form. This session cannot open aniworld.to (datacenter block,
 * Buero docs/themen/70 T1), so nothing here is tuned to a known page: it loads the page like a
 * browser, lets Cloudflare / DDoS-Guard run their own scripts, and logs what it saw.
 *
 * Two jobs:
 *  - [fetchPage]: an aniworld.to page that java.net could not get (browser check in front of the
 *    site). The WebView loads it, waits until it is the real page, returns its HTML and hands the
 *    check cookies to [AniworldSession] so the following java.net requests pass as well.
 *  - [resolveRedirect]: a hoster button's "/redirect/<id>" address. The WebView loads it (with the
 *    episode page as referer); the first navigation away from aniworld.to is the hoster embed page.
 *    If a Turnstile form shows up and gets its token, the form is submitted.
 *
 * Observation (docs/70, E3 "gut protokolliert"): page title, text, forms, iframes and scripts are
 * logged on every page. Pictures of the hidden WebView (Download/arvio-plugin/) are off since v2 - v1 played
 * on the device (25.09.2026); flip SAVE_PICTURES for gate debugging.
 */
internal object AniworldGate {

    private const val TAG = "ArvioAddon[AniworldGate]"
    private const val PAGE_TIMEOUT_MS = 30_000L
    private const val REDIRECT_TIMEOUT_MS = 45_000L
    private const val POLL_MS = 1000L
    private const val SAVE_PICTURES = false
    private const val SNAP_W = 1080
    private const val SNAP_H = 1920
    private val SNAP_AFTER_S = longArrayOf(5L, 15L, 30L)
    private const val MODE_PAGE = 0
    private const val MODE_REDIRECT = 1

    // Hosters AniWorld links to. A sub-frame document on one of these hosts also counts as result.
    private val HOSTER_HOST = Regex("""voe|vidoza|dood|ds2play|streamtape|filemoon|vidmoly|luluvdo|lulustream|vidhide|vidhd|mixdrop|supervideo|savefiles|vidsonic|streamwish|jamesbornmain|speedfiles|vidguard|loadx""")

    @Volatile private var context: Context? = null

    // One hidden WebView at a time: ARVIO may ask for details and links at the same time.
    private val oneAtATime = Any()

    fun init(ctx: Context) {
        context = ctx.applicationContext
        Log.d(TAG, "init: context saved (applicationContext)")
    }

    /** Application context for the plugin's own small storage (LinkBook, GateStats). */
    fun appContext(): Context? = context

    /** The real HTML of [url] after the browser check, or null. Blocking (network thread). */
    fun fetchPage(url: String): String? = synchronized(oneAtATime) {
        val r = openHidden(MODE_PAGE, url, "", PAGE_TIMEOUT_MS, "page")
        r.html
    }

    /** The hoster embed URL behind [redirectUrl], or null. Blocking (network thread). */
    fun resolveRedirect(redirectUrl: String, episodeUrl: String, label: String): String? = synchronized(oneAtATime) {
        val startedAt = System.currentTimeMillis()
        val r = openHidden(MODE_REDIRECT, redirectUrl, episodeUrl, REDIRECT_TIMEOUT_MS, label)
        val took = System.currentTimeMillis() - startedAt
        when {
            r.tapWanted -> GateStats.record("kaestchen", took, "$label " + if (r.url != null) "(trotzdem Adresse erhalten)" else "(keine Adresse)")
            r.url != null -> GateStats.record("durch", took, "$label (Browserfenster)")
            else -> GateStats.record("fehler", took, "$label letzte Seite='${r.lastTitle}'" + (r.cfError?.let { " cf-code=$it" } ?: ""))
        }
        r.url
    }

    private class Result {
        @Volatile var url: String? = null
        @Volatile var html: String? = null
        @Volatile var lastTitle: String = ""
        @Volatile var cfError: String? = null
        @Volatile var tapWanted = false
    }

    private fun hostOf(url: String): String = try { java.net.URI(url).host?.lowercase() ?: "" } catch (_: Throwable) { "" }

    private fun isHelperHost(host: String): Boolean =
        host.isEmpty() || host.endsWith("aniworld.to") || host.endsWith("cloudflare.com") ||
            host.endsWith("ddos-guard.net") || host.endsWith("google.com") || host.endsWith("gstatic.com") ||
            host.endsWith("recaptcha.net")

    private fun openHidden(mode: Int, startUrl: String, referer: String, timeoutMs: Long, label: String): Result {
        val result = Result()
        val ctx = context ?: run {
            Log.w(TAG, "$label: no context (init not called?)")
            return result
        }
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val webViewRef = arrayOfNulls<WebView>(1)
        val startedAt = System.currentTimeMillis()
        val pollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        Log.d(TAG, "$label: start mode=${if (mode == MODE_PAGE) "page" else "redirect"} url=$startUrl cookies=${AniworldSession.cookieNames()}")

        fun finishWith(url: String?, why: String) {
            if (latch.count == 0L) return
            if (url != null) result.url = url
            Log.d(TAG, "$label: done after ${System.currentTimeMillis() - startedAt}ms ($why) url=$url")
            latch.countDown()
        }

        val bridge = object {
            @android.webkit.JavascriptInterface
            fun onLog(msg: String) {
                Log.d(TAG, "$label js: $msg")
                if (msg.contains("before-interactive") || msg.contains("interactiveBegin")) result.tapWanted = true
                if (msg.contains("error-callback")) result.cfError = msg.substringAfter("code=", "?")
            }
        }

        handler.post {
            try {
                try { WebView.enableSlowWholeDocumentDraw() } catch (_: Throwable) {}
                val webView = WebView(ctx)
                webViewRef[0] = webView
                // A hidden WebView is never laid out (0x0) - give it a phone-sized box (pictures, Turnstile).
                webView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(SNAP_W, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(SNAP_H, android.view.View.MeasureSpec.EXACTLY))
                webView.layout(0, 0, SNAP_W, SNAP_H)
                val s = webView.settings
                s.javaScriptEnabled = true
                s.domStorageEnabled = true
                // No pop-ups: ad scripts would otherwise take over the (single) window.
                s.javaScriptCanOpenWindowsAutomatically = false
                s.setSupportMultipleWindows(false)
                s.userAgentString = AniworldSession.USER_AGENT
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                AniworldSession.copyToWebView()
                webView.addJavascriptInterface(bridge, "AwBridge")

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                        Log.d(TAG, "$label console:[${m.lineNumber()}] ${m.message().take(300)}")
                        return true
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val req = request ?: return false
                        val url = req.url?.toString() ?: return false
                        val host = hostOf(url)
                        Log.d(TAG, "$label navigate: $url mainFrame=${req.isForMainFrame} redirect=${req.isRedirect}")
                        if (mode == MODE_REDIRECT && req.isForMainFrame && url.startsWith("http") && !isHelperHost(host)) {
                            finishWith(url, "left aniworld.to")
                            return true
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val req = request ?: return null
                        val url = req.url?.toString() ?: return null
                        if (mode == MODE_REDIRECT && latch.count > 0 && !req.isForMainFrame) {
                            val host = hostOf(url)
                            val accept = req.requestHeaders?.get("Accept") ?: ""
                            // Like Serienstream v2: the hoster may open inside a player iframe.
                            if ((accept.isEmpty() || accept.contains("text/html")) && !isHelperHost(host) && HOSTER_HOST.containsMatchIn(host)) {
                                Log.d(TAG, "$label hoster frame caught: $url")
                                finishWith(url, "hoster iframe")
                            }
                        }
                        return null
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        Log.d(TAG, "$label pageStarted: $url")
                        val u = url ?: return
                        if (mode == MODE_REDIRECT && u.startsWith("http") && !isHelperHost(hostOf(u))) finishWith(u, "page started off aniworld.to")
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        Log.d(TAG, "$label pageFinished: $url (${System.currentTimeMillis() - startedAt}ms)")
                        if (latch.count == 0L) return
                        try { view.evaluateJavascript(HOOK_JS, null) } catch (_: Throwable) {}
                        describe(view, label, "loaded")
                        // One poller per WebView, even when a check page reloads itself several times.
                        if (!pollerStarted.getAndSet(true)) {
                            if (mode == MODE_PAGE) pollForRealPage(view, handler, latch, result, label, startedAt + timeoutMs)
                            else pollRedirectPage(view, handler, latch, label, startedAt + timeoutMs, 0)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        if (request != null && request.isForMainFrame) Log.w(TAG, "$label error: ${error?.description} (${request.url})")
                    }

                    override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                        if (request != null && request.isForMainFrame) Log.w(TAG, "$label http ${response?.statusCode} ${response?.reasonPhrase} (${request.url})")
                    }
                }
                for (sec in SNAP_AFTER_S) {
                    handler.postDelayed({ if (latch.count > 0) { describe(webView, label, "${sec}s"); picture(webView, label, "${sec}s") } }, sec * 1000L)
                }
                val headers = HashMap<String, String>()
                if (referer.isNotEmpty()) headers["Referer"] = referer
                webView.loadUrl(startUrl, headers)
            } catch (t: Throwable) {
                Log.w(TAG, "$label: webView create threw ${t.javaClass.name}: ${t.message}")
                latch.countDown()
            }
        }

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "$label: TIMEOUT after ${timeoutMs}ms")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // Also after a timeout: stops the pollers and the delayed pictures before the WebView goes.
        latch.countDown()

        // Take the cookies over and destroy the WebView on the main thread.
        val done = CountDownLatch(1)
        handler.post {
            try {
                AniworldSession.takeFromWebView()
                webViewRef[0]?.let { w ->
                    result.lastTitle = w.title ?: ""
                    w.stopLoading()
                    w.loadUrl("about:blank")
                    w.setWebViewClient(WebViewClient())
                    w.setWebChromeClient(null)
                    w.removeAllViews()
                    w.destroy()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$label: cleanup threw ${t.javaClass.name}: ${t.message}")
            }
            done.countDown()
        }
        try { done.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return result
    }

    /** MODE_PAGE: wait until the browser check is gone, then take the HTML. */
    private fun pollForRealPage(view: WebView, handler: Handler, latch: CountDownLatch, result: Result, label: String, deadline: Long) {
        if (latch.count == 0L) return
        if (System.currentTimeMillis() > deadline) return
        try {
            view.evaluateJavascript(STATE_JS) { raw ->
                val state = unquote(raw)
                if (state.startsWith("page|")) {
                    view.evaluateJavascript("document.documentElement.outerHTML") { html ->
                        val h = unquote(html)
                        if (h.length > 500 && latch.count > 0) {
                            result.html = h
                            Log.d(TAG, "$label: real page ($state), ${h.length} chars")
                            latch.countDown()
                        }
                    }
                } else {
                    handler.postDelayed({ pollForRealPage(view, handler, latch, result, label, deadline) }, POLL_MS)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label: poll threw ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * MODE_REDIRECT: nothing to do while Cloudflare/DDoS-Guard work. If the page holds a form with a
     * filled Turnstile token and nothing happens for two seconds, submit it (once). Everything seen
     * is logged every five seconds.
     */
    private fun pollRedirectPage(view: WebView, handler: Handler, latch: CountDownLatch, label: String, deadline: Long, round: Int) {
        if (latch.count == 0L || System.currentTimeMillis() > deadline) return
        try {
            view.evaluateJavascript(SUBMIT_JS) { raw ->
                val v = unquote(raw)
                if (v.isNotEmpty() && v != "wait") Log.d(TAG, "$label submit-check: $v")
                if (round > 0 && round % 5 == 0) describe(view, label, "poll$round")
                handler.postDelayed({ pollRedirectPage(view, handler, latch, label, deadline, round + 1) }, POLL_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label: redirect poll threw ${t.javaClass.name}: ${t.message}")
        }
    }

    /** Logs what the page shows: title, state, text, forms, iframes, check scripts. */
    private fun describe(view: WebView, label: String, moment: String) {
        try {
            view.evaluateJavascript(DESCRIBE_JS) { raw -> Log.d(TAG, "$label observe $moment: ${unquote(raw)}") }
        } catch (t: Throwable) {
            Log.w(TAG, "$label observe $moment threw ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null") return ""
        if (raw.length < 2 || !raw.startsWith("\"")) return raw
        return try { org.json.JSONArray("[$raw]").getString(0) } catch (_: Throwable) { raw.substring(1, raw.length - 1) }
    }

    // "challenge|<len>|<title>" while a browser check is showing, "page|<len>|<title>" afterwards.
    private val STATE_JS = """
(function(){try{
var t=document.title||'';var n=document.documentElement?document.documentElement.outerHTML.length:0;
var ch=/just a moment|einen moment|checking your browser|ddos-guard|attention required|access denied/i.test(t)
 ||!!document.querySelector('#challenge-form,#challenge-stage,#cf-wrapper,#ddg-captcha,script[src*="challenge-platform"],script[src*="ddos-guard"]');
if(!document.body||document.body.innerText.length<50)ch=true;
return (ch?'challenge':'page')+'|'+n+'|'+t.slice(0,80);
}catch(e){return 'err|0|'+e.message;}})()
""".trimIndent()

    private val DESCRIBE_JS = """
(function(){try{
var o=[];o.push('title='+(document.title||'').slice(0,80));o.push('url='+location.href.slice(0,120));
o.push('text='+(document.body?document.body.innerText.replace(/\s+/g,' ').slice(0,250):'-'));
var f=document.querySelectorAll('form');for(var i=0;i<f.length&&i<4;i++){var names=[];var ins=f[i].querySelectorAll('input,button,textarea,select');
for(var j=0;j<ins.length&&j<12;j++){var x=ins[j];names.push((x.name||x.id||x.tagName)+(x.value?':'+String(x.value).length:''));}
o.push('form'+i+'['+(f[i].getAttribute('method')||'get')+' '+(f[i].getAttribute('action')||'')+']='+names.join(','));}
var fr=document.querySelectorAll('iframe');for(var k=0;k<fr.length&&k<5;k++)o.push('iframe'+k+'='+(fr[k].src||'').slice(0,100));
var sc=document.querySelectorAll('script[src]');var cs=[];for(var m=0;m<sc.length;m++){var s=sc[m].src;if(/cloudflare|turnstile|ddos|recaptcha|altcha|hcaptcha/i.test(s))cs.push(s.slice(0,90));}
if(cs.length)o.push('check-scripts='+cs.join(' '));
var ts=document.querySelector('[name=cf-turnstile-response]');if(ts)o.push('turnstile-token='+(ts.value||'').length);
if(document.querySelector('.g-recaptcha,[data-sitekey]'))o.push('captcha-widget=yes');
return o.join(' | ');
}catch(e){return 'err '+e.message;}})()
""".trimIndent()

    // Submits a form whose Turnstile token is filled - once, two polls after the token appeared.
    private val SUBMIT_JS = """
(function(){try{
var ts=document.querySelector('[name=cf-turnstile-response]');
if(!ts||!ts.value||ts.value.length<10)return 'wait';
window.__awSeen=(window.__awSeen||0)+1;
if(window.__awSeen<3||window.__awSubmitted)return 'wait';
var f=ts.form||document.querySelector('form');if(!f)return 'token but no form';
window.__awSubmitted=1;var b=f.querySelector('[type=submit]');
if(b&&b.click){b.click();return 'clicked submit ('+(f.getAttribute('action')||'')+')';}
f.submit();return 'form.submit ('+(f.getAttribute('action')||'')+')';
}catch(e){return 'err '+e.message;}})()
""".trimIndent()

    // Cloudflare's verdict into the log: turnstile.render callbacks + the widget's postMessages.
    private val HOOK_JS = """
(function(){try{
if(window.__awHook)return;window.__awHook=1;
function log(m){try{AwBridge.onLog(m);}catch(e){}}
function hookTs(t){if(!t||t.__h||!t.render)return;var orig=t.render;t.render=function(el,o){o=o||{};var cb=o.callback,ecb=o['error-callback'];
o.callback=function(tok){log('ts callback: token len='+((tok||'').length));if(cb)cb(tok);};
o['error-callback']=function(c){log('ts error-callback code='+c);if(ecb)return ecb(c);};
o['before-interactive-callback']=function(){log('ts before-interactive: Cloudflare wants a tap');};
log('ts render hooked');return orig.call(this,el,o);};t.__h=1;}
if(window.turnstile)hookTs(window.turnstile);else{var n=0,iv=setInterval(function(){n++;if(window.turnstile){clearInterval(iv);hookTs(window.turnstile);}else if(n>1500)clearInterval(iv);},20);}
window.addEventListener('message',function(e){var d=e.data;if(!d||typeof d!=='object'||d.source!=='cloudflare-challenge')return;
var x='';try{x=JSON.stringify(d);}catch(err){}log('cf msg '+d.event+': '+x.slice(0,160));},false);
}catch(e){}})()
""".trimIndent()

    /** Picture of the hidden WebView into Download/arvio-plugin/ (docs/70: pictures on while exploring). */
    private fun picture(webView: WebView, label: String, moment: String) {
        if (!SAVE_PICTURES) return
        try {
            val w = if (webView.width > 0) webView.width else SNAP_W
            val h = if (webView.height > 0) webView.height else SNAP_H
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            webView.draw(canvas)
            val ctx = context ?: return
            val name = "aniworld-$label-$moment-${System.currentTimeMillis()}.png"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues()
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/arvio-plugin")
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) { Log.w(TAG, "picture $moment: MediaStore insert returned null"); return }
                val out = ctx.contentResolver.openOutputStream(uri)
                if (out == null) { Log.w(TAG, "picture $moment: no output stream"); return }
                try { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) } finally { out.close() }
                Log.d(TAG, "picture saved Download/arvio-plugin/$name")
            } else {
                Log.w(TAG, "picture $moment: Android < 10, not saved")
            }
            bmp.recycle()
        } catch (t: Throwable) {
            Log.w(TAG, "picture $moment failed ${t.javaClass.name}: ${t.message}")
        }
    }
}
