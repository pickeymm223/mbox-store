package com.mbox.store

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * The store's own download engine. Runs downloads one at a time in a
 * foreground service so the notification shade shows the MBOX Store logo,
 * the app being downloaded (icon + name), live progress, and a Cancel
 * action — the same shape as Play Store's downloads. Progress is mirrored
 * into Downloader's SharedPreferences state for the detail page.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL = "downloads"
        private const val ACTION_START = "com.mbox.store.dl.START"
        private const val ACTION_CANCEL = "com.mbox.store.dl.CANCEL"

        fun start(context: Context, app: StoreApp) {
            val i = Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra("pkg", app.pkg)
                .putExtra("name", app.name)
                .putExtra("url", app.apkUrl)
                .putExtra("icon", app.iconUrl)
                .putExtra("versionCode", app.versionCode)
                .putExtra("size", app.sizeBytes)
            ContextCompat.startForegroundService(context, i)
        }

        fun cancel(context: Context, pkg: String) {
            val i = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra("pkg", pkg)
            context.startService(i)
        }
    }

    private data class Req(
        val pkg: String,
        val name: String,
        val url: String,
        val iconUrl: String,
        val versionCode: Long,
        val size: Long
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Req>()
    private val cancelled = HashSet<String>()
    private val iconCache = HashMap<String, Bitmap?>()
    @Volatile private var active: Req? = null
    @Volatile private var foregroundUp = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val req = Req(
                    pkg = intent.getStringExtra("pkg").orEmpty(),
                    name = intent.getStringExtra("name").orEmpty(),
                    url = intent.getStringExtra("url").orEmpty(),
                    iconUrl = intent.getStringExtra("icon").orEmpty(),
                    versionCode = intent.getLongExtra("versionCode", 0L),
                    size = intent.getLongExtra("size", 0L)
                )
                if (req.pkg.isNotEmpty() &&
                    active?.pkg != req.pkg &&
                    queue.none { it.pkg == req.pkg }
                ) {
                    queue.add(req)
                    cancelled.remove(req.pkg)
                }
                ensureForeground(req)
                pump()
            }
            ACTION_CANCEL -> {
                val pkg = intent.getStringExtra("pkg").orEmpty()
                cancelled.add(pkg)
                queue.removeAll { it.pkg == pkg }
                if (active?.pkg != pkg) {
                    // Queued or already finished — nothing else to do.
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.cancel(notifId(pkg))
                    maybeStop()
                }
                // If it is the active download, the worker loop sees the
                // flag, cleans up, and stops the service if idle.
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureForeground(req: Req?) {
        if (foregroundUp || req == null) return
        val n = buildProgressNotification(
            req.pkg, req.name, null, 0, req.size, ongoing = true
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                notifId(req.pkg), n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(notifId(req.pkg), n)
        }
        foregroundUp = true
    }

    private fun pump() {
        if (active != null) return
        val next = queue.poll() ?: run {
            maybeStop()
            return
        }
        active = next
        // Rebind the foreground notification to the download now starting
        // (the previous one stays behind as its completion notice).
        if (foregroundUp) {
            val n = buildProgressNotification(
                next.pkg, next.name, null, 0, next.size, ongoing = true
            )
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    notifId(next.pkg), n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notifId(next.pkg), n)
            }
        }
        executor.execute { runDownload(next) }
    }

    private fun maybeStop() {
        if (active == null && queue.isEmpty()) {
            if (foregroundUp) {
                stopForeground(STOP_FOREGROUND_DETACH)
                foregroundUp = false
            }
            stopSelf()
        }
    }

    private fun runDownload(req: Req) {
        val app = StoreApp(
            pkg = req.pkg, name = req.name, developer = "", category = "",
            genre = "", versionName = "", versionCode = req.versionCode,
            sizeBytes = req.size, iconUrl = req.iconUrl, apkUrl = req.url,
            certSha256 = "", description = "", changelog = ""
        )
        val nm = getSystemService(NotificationManager::class.java)
        val id = notifId(req.pkg)
        try {
            if (Prefs.wifiOnly(this) && !onWifi()) {
                val reason = "Waiting for Wi-Fi — or turn off " +
                    "“Download on Wi-Fi only” in Settings."
                Downloader.writeState(
                    this, req.pkg,
                    Downloader.Progress(Downloader.STATUS_PAUSED, 0, req.size, reason)
                )
                nm.notify(
                    id, buildProgressNotification(
                        req.pkg, req.name, iconFor(req), 0, req.size,
                        ongoing = false, text = reason
                    )
                )
                return
            }

            Downloader.writeState(
                this, req.pkg,
                Downloader.Progress(Downloader.STATUS_RUNNING, 0, req.size)
            )
            val part = Downloader.partFile(this, app)
            val dest = Downloader.destFile(this, app)
            part.delete()

            val conn = URL(req.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "MBOXStore-Android")
            val total = if (conn.contentLengthLong > 0) {
                conn.contentLengthLong
            } else {
                req.size
            }
            var done = 0L
            var lastUi = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.contains(req.pkg)) {
                            throw InterruptedException("cancelled")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUi > 500) {
                            lastUi = now
                            Downloader.writeState(
                                this, req.pkg,
                                Downloader.Progress(
                                    Downloader.STATUS_RUNNING, done, total
                                )
                            )
                            nm.notify(
                                id, buildProgressNotification(
                                    req.pkg, req.name, iconFor(req), done, total,
                                    ongoing = true
                                )
                            )
                        }
                    }
                }
            }
            if (cancelled.contains(req.pkg)) throw InterruptedException("cancelled")
            dest.delete()
            if (!part.renameTo(dest)) throw IllegalStateException("rename failed")
            Downloader.writeState(
                this, req.pkg,
                Downloader.Progress(Downloader.STATUS_SUCCESSFUL, done, total)
            )
            nm.notify(
                id, buildDoneNotification(
                    req, "Download complete — tap to install"
                )
            )
        } catch (e: InterruptedException) {
            Downloader.partFile(this, app).delete()
            Downloader.forget(this, req.pkg)
            nm.cancel(id)
        } catch (e: Exception) {
            Downloader.partFile(this, app).delete()
            if (cancelled.contains(req.pkg)) {
                Downloader.forget(this, req.pkg)
                nm.cancel(id)
            } else {
                Downloader.writeState(
                    this, req.pkg,
                    Downloader.Progress(
                        Downloader.STATUS_FAILED, 0, req.size,
                        "Download failed. Check your connection and try again."
                    )
                )
                nm.notify(
                    id, buildDoneNotification(
                        req, "Download failed — tap to try again"
                    )
                )
            }
        } finally {
            active = null
            cancelled.remove(req.pkg)
            pump()
        }
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun iconFor(req: Req): Bitmap? {
        if (iconCache.containsKey(req.pkg)) return iconCache[req.pkg]
        val bmp = try {
            if (req.iconUrl.isEmpty()) {
                null
            } else {
                val conn = URL(req.iconUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", "MBOXStore-Android")
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }
        iconCache[req.pkg] = bmp
        return bmp
    }

    private fun detailIntent(pkg: String): PendingIntent {
        val i = Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_PKG, pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, notifId(pkg), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildProgressNotification(
        pkg: String,
        name: String,
        icon: Bitmap?,
        done: Long,
        total: Long,
        ongoing: Boolean,
        text: String? = null
    ): android.app.Notification {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dl)
            .setContentTitle(name)
            .setContentText(text ?: "Downloading… $pct%")
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(detailIntent(pkg))
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (icon != null) b.setLargeIcon(icon)
        if (total > 0) {
            b.setProgress(100, pct, false)
        } else {
            b.setProgress(0, 0, true)
        }
        if (ongoing) {
            val cancelIntent = Intent(this, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra("pkg", pkg)
            val cancelPi = PendingIntent.getService(
                this, notifId(pkg), cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel", cancelPi
            )
        }
        return b.build()
    }

    private fun buildDoneNotification(req: Req, text: String): android.app.Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_dl)
            .setContentTitle(req.name)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(detailIntent(req.pkg))
            .setPriority(NotificationCompat.PRIORITY_LOW)
        iconFor(req)?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun notifId(pkg: String): Int = 2000 + (pkg.hashCode() and 0x3FFF)
}
