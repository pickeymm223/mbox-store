package com.mbox.store

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A purchasable product inside a listed app (MBOX Pay, Level 2). */
data class IapProduct(
    val id: String,
    val label: String,
    val priceStars: Int,
    val priceUsdt: Double
)

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
    val screenshots: List<String> = emptyList(),
    /** MBOX Pay: price of the app itself (0 = free / not sold on that rail). */
    val priceStars: Int = 0,
    val priceUsdt: Double = 0.0,
    /** SHA-256 of the APK file itself; verified after download when set. */
    val sha256: String = "",
    /** True when the developer accepts support payments through the store. */
    val support: Boolean = false,
    val iap: List<IapProduct> = emptyList()
) {
    val isPaid: Boolean get() = priceStars > 0 || priceUsdt > 0
}

object Catalog {
    const val CATALOG_URL = "https://mbox-store-api.zawminhtet723.workers.dev/catalog.json"

    @Volatile
    var apps: List<StoreApp> = emptyList()
        private set

    fun find(pkg: String): StoreApp? = apps.firstOrNull { it.pkg == pkg }

    private const val CACHE_FILE = "catalog_cache.json"

    /** Blocking fetch — call from a background thread. */
    fun fetch(context: Context? = null): List<StoreApp> {
        val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "MBOXStore-Android")
        conn.setRequestProperty("Accept", "application/json")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val parsed = parse(text)
        apps = parsed
        if (context != null && parsed.isNotEmpty()) saveCache(context, text)
        return parsed
    }

    /** Keep the raw catalog on the phone so Home works offline too. */
    private fun saveCache(context: Context, text: String) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(text)
        } catch (e: Exception) {
            // Cache is best-effort; a failed write changes nothing.
        }
    }

    /** The on-device copy from the last successful fetch, if any. */
    fun loadCache(context: Context): List<StoreApp>? {
        return try {
            val f = File(context.filesDir, CACHE_FILE)
            if (!f.exists()) return null
            val parsed = parse(f.readText())
            if (parsed.isEmpty()) null else {
                apps = parsed
                parsed
            }
        } catch (e: Exception) {
            null
        }
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
                    screenshots = parseShots(o.optJSONArray("screenshots")),
                    priceStars = o.optInt("priceStars", 0),
                    priceUsdt = o.optDouble("priceUsdt", 0.0),
                    sha256 = o.optString("sha256"),
                    support = o.optBoolean("support", false),
                    iap = parseIap(o.optJSONArray("iap"))
                )
            )
        }
        return out
    }

    private fun parseIap(arr: org.json.JSONArray?): List<IapProduct> {
        if (arr == null) return emptyList()
        val out = ArrayList<IapProduct>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                IapProduct(
                    id = id,
                    label = o.optString("label", id),
                    priceStars = o.optInt("priceStars", 0),
                    priceUsdt = o.optDouble("priceUsdt", 0.0)
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
