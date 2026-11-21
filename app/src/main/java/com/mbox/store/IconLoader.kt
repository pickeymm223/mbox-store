package com.mbox.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Minimal async icon loader with an in-memory cache (no external deps). */
object IconLoader {
    private val cache = LruCache<String, Bitmap>(64)
    private val pool = Executors.newFixedThreadPool(4)

    fun load(url: String, into: ImageView) {
        if (url.isEmpty()) {
            into.setImageResource(R.drawable.ic_app_placeholder)
            return
        }
        cache.get(url)?.let {
            into.setImageBitmap(it)
            return
        }
        into.tag = url
        into.setImageResource(R.drawable.ic_app_placeholder)
        pool.execute {
            val bmp = try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "MBOXStore-Android")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            if (bmp != null) {
                cache.put(url, bmp)
                into.post {
                    if (into.tag == url) into.setImageBitmap(bmp)
                }
            }
        }
    }
}
