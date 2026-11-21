package com.mbox.store

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One app listed in the MBOX Store catalog. */
data class StoreApp(
    val pkg: String,
    val name: String,
    val developer: String,
    val category: String,
    val genre: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val iconUrl: String,
    val apkUrl: String,
    val certSha256: String,
    val description: String,
    val changelog: String,
    val screenshots: List<String> = emptyList()
)

object Catalog {
    const val CATALOG_URL = "https://mbox-store-api.zawminhtet723.workers.dev/catalog.json"

    @Volatile
    var apps: List<StoreApp> = emptyList()
        private set

    fun find(pkg: String): StoreApp? = apps.firstOrNull { it.pkg == pkg }

    /** Blocking fetch — call from a background thread. */
    fun fetch(): List<StoreApp> {
        val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "MBOXStore-Android")
        conn.setRequestProperty("Accept", "application/json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val parsed = parse(text)
        apps = parsed
        return parsed
    }

    fun parse(text: String): List<StoreApp> {
        val root = JSONObject(text)
        val arr = root.optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<StoreApp>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package").trim()
            if (pkg.isEmpty()) continue
            out.add(
                StoreApp(
                    pkg = pkg,
                    name = o.optString("name", pkg),
                    developer = o.optString("developer"),
                    category = o.optString("category"),
                    genre = o.optString("genre")
                        .ifEmpty { o.optString("category") },
                    versionName = o.optString("versionName"),
                    versionCode = o.optLong("versionCode", 0L),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    iconUrl = o.optString("icon"),
                    apkUrl = o.optString("apk"),
                    certSha256 = o.optString("certSha256"),
                    description = o.optString("description"),
                    changelog = o.optString("changelog"),
                    screenshots = parseShots(o.optJSONArray("screenshots"))
                )
            )
        }
        return out
    }

    private fun parseShots(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out.add(s)
        }
        return out
    }
}
