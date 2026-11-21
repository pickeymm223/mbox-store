package com.mbox.store

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.File

/**
 * Downloads run through the system DownloadManager, so every download gets
 * a notification-shade entry with live progress and a working Cancel, and
 * keeps going when the user leaves the detail page. The tracked download id
 * per package is kept in SharedPreferences so the page can re-attach to a
 * running (or finished) download when the user comes back.
 */
object Downloader {

    private const val PREFS = "downloads"

    data class Progress(val status: Int, val done: Long, val total: Long)

    fun destFile(context: Context, app: StoreApp): File {
        val dir = File(context.getExternalFilesDir(null), "apks").apply { mkdirs() }
        return File(dir, "${app.pkg}-${app.versionCode}.apk")
    }

    fun enqueue(context: Context, app: StoreApp): Long {
        val dm = manager(context)
        // A stale partial file at the destination makes DownloadManager fail.
        destFile(context, app).delete()
        val req = DownloadManager.Request(Uri.parse(app.apkUrl))
            .setTitle(app.name)
            .setDescription("Downloading ${app.name}…")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalFilesDir(
                context, null, "apks/${app.pkg}-${app.versionCode}.apk"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "MBOXStore-Android")
        val id = dm.enqueue(req)
        prefs(context).edit().putLong(app.pkg, id).apply()
        return id
    }

    fun trackedId(context: Context, pkg: String): Long =
        prefs(context).getLong(pkg, -1L)

    fun query(context: Context, id: Long): Progress? {
        val dm = manager(context)
        var cursor: Cursor? = null
        return try {
            cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
                val done = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    )
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                Progress(status, done, total)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    fun cancel(context: Context, app: StoreApp) {
        val id = trackedId(context, app.pkg)
        if (id != -1L) manager(context).remove(id)
        forget(context, app.pkg)
        destFile(context, app).delete()
    }

    fun forget(context: Context, pkg: String) {
        prefs(context).edit().remove(pkg).apply()
    }

    private fun manager(context: Context): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
