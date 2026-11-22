package com.mbox.store

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Download state, owned by DownloadService (the store's own download
 * engine — the system DownloadManager could not show the store's logo in
 * its notifications). The service records each download's progress in
 * SharedPreferences so any screen can attach to it, and posts a
 * logo-carrying notification with live progress and a Cancel action.
 *
 * Status values mirror the old DownloadManager constants.
 */
object Downloader {

    const val STATUS_PENDING = 1
    const val STATUS_RUNNING = 2
    const val STATUS_PAUSED = 4
    const val STATUS_SUCCESSFUL = 8
    const val STATUS_FAILED = 16

    private const val PREFS = "downloads"
    private const val KEY_PREFIX = "state:"

    data class Progress(
        val status: Int,
        val done: Long,
        val total: Long,
        val reason: String = ""
    )

    fun destFile(context: Context, app: StoreApp): File {
        val dir = File(context.getExternalFilesDir(null), "apks").apply { mkdirs() }
        return File(dir, "${app.pkg}-${app.versionCode}.apk")
    }

    fun partFile(context: Context, app: StoreApp): File =
        File(destFile(context, app).absolutePath + ".part")

    fun enqueue(context: Context, app: StoreApp) {
        writeState(
            context, app.pkg,
            Progress(STATUS_PENDING, 0, app.sizeBytes)
        )
        DownloadService.start(context, app)
    }

    fun hasState(context: Context, pkg: String): Boolean =
        prefs(context).contains(KEY_PREFIX + pkg)

    fun query(context: Context, pkg: String): Progress? {
        val raw = prefs(context).getString(KEY_PREFIX + pkg, null) ?: return null
        return try {
            val j = JSONObject(raw)
            Progress(
                status = j.getInt("status"),
                done = j.optLong("done"),
                total = j.optLong("total"),
                reason = j.optString("reason")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun writeState(context: Context, pkg: String, progress: Progress) {
        val j = JSONObject()
            .put("status", progress.status)
            .put("done", progress.done)
            .put("total", progress.total)
            .put("reason", progress.reason)
        prefs(context).edit().putString(KEY_PREFIX + pkg, j.toString()).apply()
    }

    fun cancel(context: Context, app: StoreApp) {
        DownloadService.cancel(context, app.pkg)
        forget(context, app.pkg)
        destFile(context, app).delete()
        partFile(context, app).delete()
    }

    fun forget(context: Context, pkg: String) {
        prefs(context).edit().remove(KEY_PREFIX + pkg).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
