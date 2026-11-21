package com.mbox.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One recorded download from the store. */
data class HistoryEntry(
    val pkg: String,
    val name: String,
    val versionName: String,
    val atMillis: Long
)

/** Local download history, stored on-device in SharedPreferences. */
object History {
    private const val PREFS = "mbox_store"
    private const val KEY = "download_history"
    private const val MAX = 50

    fun add(context: Context, app: StoreApp) {
        val list = all(context).toMutableList()
        list.removeAll { it.pkg == app.pkg }
        list.add(
            0,
            HistoryEntry(app.pkg, app.name, app.versionName, System.currentTimeMillis())
        )
        val arr = JSONArray()
        for (e in list.take(MAX)) {
            arr.put(
                JSONObject()
                    .put("pkg", e.pkg)
                    .put("name", e.name)
                    .put("versionName", e.versionName)
                    .put("at", e.atMillis)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun all(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    HistoryEntry(
                        o.optString("pkg"),
                        o.optString("name"),
                        o.optString("versionName"),
                        o.optLong("at")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
}
