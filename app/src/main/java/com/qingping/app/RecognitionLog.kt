package com.qingping.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object RecognitionLog {
    data class Entry(val time: Long, val pkg: String, val kind: String, val reason: String,
                     val text: String, val id: String, val bounds: String, val signals: String)
    private const val LIMIT = 250
    private val rows = ArrayList<Entry>()
    private val dedup = LinkedHashMap<String, Long>()
    private var loaded = false
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "qingping-log-writer").apply { isDaemon = true }
    }
    private var writeScheduled = false
    var revision: Long = 0
        private set
    private fun prefs(c: Context) = c.getSharedPreferences("recognition_log", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("enabled", true)
    @Synchronized fun setEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("enabled", value).apply()
        revision++
    }
    fun startupSkipEnabled(c: Context) = prefs(c).getBoolean("startup_skip", false)
    fun setStartupSkipEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("startup_skip", value).apply()
    }
    fun onlyRelated(c: Context) = prefs(c).getBoolean("only_related", true)
    fun setOnlyRelated(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("only_related", value).apply()
    }
    fun gestureEnabled(c: Context) = prefs(c).getBoolean("gesture", false)
    fun setGestureEnabled(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("gesture", value).apply()
    }
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(prefs(c).getString("rows", "[]"))
            for (i in 0 until minOf(a.length(), LIMIT)) {
                val j = a.getJSONObject(i)
                rows.add(Entry(j.getLong("time"), j.optString("pkg"), j.optString("kind"),
                    j.optString("reason"), j.optString("text"), j.optString("id"),
                    j.optString("bounds"), j.optString("signals")))
            }
        } catch (_: Exception) { rows.clear() }
    }
    @Synchronized fun add(c: Context, pkg: String, kind: String, reason: String,
                          text: String = "", id: String = "", bounds: String = "", signals: String = "") {
        if (!enabled(c)) return
        load(c)
        val key = "$pkg|$kind|$reason|$text|$id"
        val now = SystemClock.elapsedRealtime()
        val previous = dedup[key]
        if (previous != null && now - previous < 3000) return
        dedup[key] = now
        while (dedup.size > 128) dedup.remove(dedup.keys.first())
        rows.add(Entry(System.currentTimeMillis(), pkg, kind, reason.take(200), text.take(60),
            id.take(150), bounds.take(80), signals.take(250)))
        while (rows.size > LIMIT) rows.removeAt(0)
        persist(c)
    }
    // Call under this object's monitor. Snapshot/serialization runs off the service main thread.
    private fun persist(c: Context) {
        revision++
        if (writeScheduled) return
        writeScheduled = true
        val context = c.applicationContext
        writer.schedule({
            val snapshot = synchronized(this) {
                writeScheduled = false
                rows.toList()
            }
            try { writeRows(context, snapshot) }
            catch (e: RuntimeException) { Log.w("QingPingLog", "Failed to persist diagnostics", e) }
        }, 500, TimeUnit.MILLISECONDS)
    }
    private fun writeRows(c: Context, snapshot: List<Entry>) {
        val a = JSONArray()
        snapshot.forEach { e -> a.put(JSONObject().put("time", e.time).put("pkg", e.pkg)
            .put("kind", e.kind).put("reason", e.reason).put("text", e.text)
            .put("id", e.id).put("bounds", e.bounds).put("signals", e.signals)) }
        prefs(c).edit().putString("rows", a.toString()).apply()
    }
    @Synchronized fun entries(c: Context): List<Entry> { load(c); return rows.asReversed().toList() }
    @Synchronized fun clear(c: Context) { load(c); rows.clear(); dedup.clear(); persist(c) }
}
