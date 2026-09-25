package com.reichi.arflioaddon.serienstream

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Linkbuch" (Buero docs/themen/62): remembers, per episode, the hoster embed URL that
 * Serienstream's Cloudflare gate handed out (e.g. https://jamesbornmain.com/e/abc123).
 * That URL hardly ever changes, so replaying an episode, the player's second call or a later
 * visit go straight to the hoster without passing the gate again. Kept on the device only
 * (SharedPreferences) - never shared.
 */
internal object LinkBook {

    private const val TAG = "ArvioAddon[Linkbuch]"
    private const val PREFS = "arvio_addon_serienstream"
    private const val KEY_BOOK = "linkbook"
    private const val MAX_EPISODES = 500

    class Entry(val hosterUrl: String, val provider: String, val language: String, val savedAt: Long)

    private val lock = Any()

    private fun prefs(): android.content.SharedPreferences? =
        TurnstileSolver.appContext()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readBook(): JSONObject = try {
        JSONObject(prefs()?.getString(KEY_BOOK, null) ?: "{}")
    } catch (_: Throwable) { JSONObject() }

    fun get(episodeUrl: String): List<Entry> = synchronized(lock) {
        val arr = readBook().optJSONArray(episodeUrl) ?: return emptyList()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("u", "")
            if (u.startsWith("http")) out.add(Entry(u, o.optString("p", ""), o.optString("l", ""), o.optLong("t", 0L)))
        }
        out
    }

    fun put(episodeUrl: String, entry: Entry): Unit = synchronized(lock) {
        val p = prefs() ?: return
        val book = readBook()
        val arr = JSONArray()
        arr.put(JSONObject().put("u", entry.hosterUrl).put("p", entry.provider).put("l", entry.language).put("t", entry.savedAt))
        book.put(episodeUrl, arr)
        // Cap the size: drop the oldest episodes.
        if (book.length() > MAX_EPISODES) {
            val keys = ArrayList<String>()
            val it = book.keys()
            while (it.hasNext()) keys.add(it.next())
            keys.sortBy { k -> book.optJSONArray(k)?.optJSONObject(0)?.optLong("t", 0L) ?: 0L }
            for (i in 0 until book.length() - MAX_EPISODES) book.remove(keys[i])
        }
        p.edit().putString(KEY_BOOK, book.toString()).apply()
        Log.d(TAG, "saved ${entry.provider} ${entry.hosterUrl} (${book.length()} episodes in the book)")
        Unit
    }

    fun remove(episodeUrl: String): Unit = synchronized(lock) {
        val p = prefs() ?: return
        val book = readBook()
        book.remove(episodeUrl)
        p.edit().putString(KEY_BOOK, book.toString()).apply()
    }
}

/**
 * Measuring (Buero docs/themen/62): one log line per pass through Serienstream's gate - did
 * Cloudflare let us through unseen, or did it want the checkbox? - plus running counts for
 * the last hour and the last 24 hours, and how often the Linkbuch saved a pass.
 * Search the log for "GATE-STAT".
 */
internal object GateStats {

    private const val TAG = "ArvioAddon[GATE-STAT]"
    private const val PREFS = "arvio_addon_serienstream"
    private const val KEY_EVENTS = "gate_events"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HOUR_MS = 60 * 60 * 1000L

    private val lock = Any()

    /** kind: "durch" (gate passed unseen), "kaestchen" (Cloudflare wanted a tap), "fehler", "linkbuch". */
    fun record(kind: String, durationMs: Long, detail: String): Unit = synchronized(lock) {
        val now = System.currentTimeMillis()
        val p = TurnstileSolver.appContext()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = try { JSONArray(p?.getString(KEY_EVENTS, null) ?: "[]") } catch (_: Throwable) { JSONArray() }
        val kept = JSONArray()
        for (i in 0 until events.length()) {
            val o = events.optJSONObject(i) ?: continue
            if (now - o.optLong("t", 0L) < DAY_MS) kept.put(o)
        }
        kept.put(JSONObject().put("t", now).put("k", kind))
        p?.edit()?.putString(KEY_EVENTS, kept.toString())?.apply()

        val counts = IntArray(8) // hour: durch, kaestchen, fehler, linkbuch | day: same
        for (i in 0 until kept.length()) {
            val o = kept.optJSONObject(i) ?: continue
            val slot = when (o.optString("k", "")) { "durch" -> 0; "kaestchen" -> 1; "fehler" -> 2; "linkbuch" -> 3; else -> -1 }
            if (slot < 0) continue
            counts[4 + slot]++
            if (now - o.optLong("t", 0L) < HOUR_MS) counts[slot]++
        }
        Log.i(TAG, "GATE-STAT $kind (${durationMs / 1000}.${(durationMs % 1000) / 100}s) $detail | " +
            "letzte Stunde: ${counts[0]} durch, ${counts[1]} Kaestchen, ${counts[2]} Fehler, ${counts[3]} aus Linkbuch | " +
            "24 h: ${counts[4]} durch, ${counts[5]} Kaestchen, ${counts[6]} Fehler, ${counts[7]} aus Linkbuch")
        Unit
    }
}
