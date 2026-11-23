package com.mbox.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Async icon loader with two cache layers: an in-memory LruCache and an
 * on-device disk cache, so icons (app icons, avatars, banner art) still
 * show when the phone is offline or the network is slow.
 */
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
            val bmp = fromDisk(into, url) ?: download(into, url)
            if (bmp != null) {
                cache.put(url, bmp)
                into.post {
                    if (into.tag == url) into.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun cacheFile(into: ImageView, url: String): File {
        val dir = File(into.context.cacheDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.img")
    }

    private fun fromDisk(into: ImageView, url: String): Bitmap? {
        return try {
            val f = cacheFile(into, url)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun download(into: ImageView, url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "MBOXStore-Android")
            val bytes = conn.inputStream.use { it.readBytes() }
            try {
                cacheFile(into, url).writeBytes(bytes)
            } catch (e: Exception) {
                // Disk cache is best-effort; a failed write changes nothing.
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}
