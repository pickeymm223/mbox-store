package com.mbox.store

import android.app.DownloadManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityDetailBinding
import java.io.File
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var binding: ActivityDetailBinding
    private var app: StoreApp? = null

    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var verifying = false
    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling) return
            pollDownload(autoInstall = true)
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        app = Catalog.find(pkg)
        val a = app
        if (a == null) {
            finish()
            return
        }
        binding.name.text = a.name
        binding.developer.text = a.developer
        IconLoader.load(a.iconUrl, binding.icon)
        val size = Installer.formatSize(a.sizeBytes)
        binding.meta.text = listOf(
            "Version ${a.versionName}",
            size,
            a.genre
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        binding.description.text = a.description
        if (a.screenshots.isNotEmpty()) {
            binding.shotsBox.visibility = View.VISIBLE
            for (url in a.screenshots) {
                val iv = android.widget.ImageView(this)
                val w = (150 * resources.displayMetrics.density).toInt()
                val h = (300 * resources.displayMetrics.density).toInt()
                val lp = android.widget.LinearLayout.LayoutParams(w, h)
                lp.marginEnd = (10 * resources.displayMetrics.density).toInt()
                iv.layoutParams = lp
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.shotsRow.addView(iv)
                IconLoader.load(url, iv)
            }
        }
        binding.changelogBox.visibility =
            if (a.changelog.isNotEmpty()) View.VISIBLE else View.GONE
        binding.changelog.text = a.changelog
        binding.certBox.visibility =
            if (a.certSha256.isNotEmpty()) View.VISIBLE else View.GONE
        binding.cert.text = a.certSha256
        binding.action.setOnClickListener { onAction() }
        binding.cancel.setOnClickListener { onCancel() }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (!verifying) syncDownloadUi()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun refreshState() {
        val a = app ?: return
        binding.action.isEnabled = true
        binding.cancel.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.action.text = when (Installer.stateFor(this, a)) {
            Installer.State.GET -> "Install"
            Installer.State.UPDATE -> "Update"
            Installer.State.OPEN -> "Open"
        }
    }

    private fun onAction() {
        val a = app ?: return
        when (Installer.stateFor(this, a)) {
            Installer.State.OPEN -> Installer.openApp(this, a.pkg)
            else -> {
                // A finished download waiting for its install tap?
                val id = Downloader.trackedId(this, a.pkg)
                val p = if (id != -1L) Downloader.query(this, id) else null
                if (p != null && p.status == DownloadManager.STATUS_SUCCESSFUL) {
                    verifyAndInstall(a, Downloader.destFile(this, a))
                } else {
                    startDownload(a)
                }
            }
        }
    }

    private fun onCancel() {
        val a = app ?: return
        Downloader.cancel(this, a)
        stopPolling()
        setStatus("Download cancelled.")
        refreshState()
    }

    private fun startDownload(a: StoreApp) {
        if (!Installer.canInstallPackages(this)) {
            setStatus(
                "One-time setup: allow MBOX Store to install apps, " +
                    "then come back and tap again."
            )
            Installer.openUnknownSourcesSettings(this)
            return
        }
        Downloader.enqueue(this, a)
        showDownloading(0, a.sizeBytes)
        startPolling()
    }

    /** Re-attach the UI to a download started earlier (running or finished). */
    private fun syncDownloadUi() {
        val a = app ?: return
        val id = Downloader.trackedId(this, a.pkg)
        if (id == -1L) {
            refreshState()
            return
        }
        val p = Downloader.query(this, id)
        when {
            p == null -> {
                Downloader.forget(this, a.pkg)
                refreshState()
            }
            p.status == DownloadManager.STATUS_SUCCESSFUL -> {
                if (Installer.installedVersion(this, a.pkg) >= a.versionCode) {
                    Downloader.forget(this, a.pkg)
                    refreshState()
                } else {
                    refreshState()
                    setStatus("Download complete — tap the button to install.")
                }
            }
            p.status == DownloadManager.STATUS_FAILED -> {
                Downloader.forget(this, a.pkg)
                Downloader.destFile(this, a).delete()
                refreshState()
                setStatus("Download failed. Check your connection and try again.")
            }
            else -> {
                showDownloading(p.done, if (p.total > 0) p.total else a.sizeBytes)
                startPolling()
            }
        }
    }

    private fun pollDownload(autoInstall: Boolean) {
        val a = app ?: return
        val id = Downloader.trackedId(this, a.pkg)
        if (id == -1L) {
            stopPolling()
            return
        }
        val p = Downloader.query(this, id) ?: return
        when (p.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                stopPolling()
                if (autoInstall) {
                    verifyAndInstall(a, Downloader.destFile(this, a))
                }
            }
            DownloadManager.STATUS_FAILED -> {
                stopPolling()
                Downloader.forget(this, a.pkg)
                Downloader.destFile(this, a).delete()
                refreshState()
                setStatus("Download failed. Check your connection and try again.")
            }
            else -> showDownloading(p.done, if (p.total > 0) p.total else a.sizeBytes)
        }
    }

    private fun showDownloading(done: Long, total: Long) {
        val a = app ?: return
        binding.action.isEnabled = false
        binding.action.text = "Downloading…"
        binding.cancel.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress =
            if (total > 0) (done * 100 / total).toInt() else 0
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        setStatus(
            "Downloading ${a.name}… $pct% • " +
                "${Installer.formatSize(done)} of ${Installer.formatSize(total)}"
        )
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.postDelayed(pollTick, 500)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(pollTick)
    }

    private fun verifyAndInstall(a: StoreApp, dest: File) {
        if (verifying) return
        verifying = true
        binding.action.isEnabled = false
        binding.cancel.visibility = View.GONE
        binding.progress.visibility = View.GONE
        setStatus("Checking the downloaded file…")
        thread {
            // Verify the file really is the app we promised, signed with the
            // catalog certificate, before handing it to the installer.
            val archivePkg = Installer.apkPackageName(this, dest)
            val cert = Installer.apkCertSha256(this, dest)
            val certOk = a.certSha256.isEmpty() ||
                (cert != null && normalize(a.certSha256) == normalize(cert))
            runOnUiThread {
                verifying = false
                Downloader.forget(this, a.pkg)
                when {
                    archivePkg != a.pkg -> {
                        dest.delete()
                        refreshState()
                        setStatus("Downloaded file does not match this app. Cancelled.")
                    }
                    !certOk -> {
                        dest.delete()
                        refreshState()
                        setStatus("Signature check failed. For your safety, the install was cancelled.")
                    }
                    else -> {
                        History.add(this, a)
                        refreshState()
                        setStatus("Opening installer…")
                        Installer.install(this, dest)
                    }
                }
            }
        }
    }

    private fun setStatus(text: String) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = text
    }

    private fun normalize(s: String): String =
        s.replace(":", "").replace(" ", "").uppercase()
}
