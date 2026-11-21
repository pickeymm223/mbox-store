package com.mbox.store

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityDetailBinding
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var binding: ActivityDetailBinding
    private var app: StoreApp? = null
    private var busy = false

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
            a.category
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
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refreshState()
    }

    private fun refreshState() {
        val a = app ?: return
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
            else -> downloadAndInstall(a)
        }
    }

    private fun setStatus(text: String) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = text
    }

    private fun downloadAndInstall(a: StoreApp) {
        if (busy) return
        if (!Installer.canInstallPackages(this)) {
            setStatus(
                "One-time setup: allow MBOX Store to install apps, " +
                    "then come back and tap again."
            )
            Installer.openUnknownSourcesSettings(this)
            return
        }
        busy = true
        binding.action.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        binding.progress.progress = 0
        setStatus("Downloading ${a.name}…")

        val dir = File(getExternalFilesDir(null), "apks").apply { mkdirs() }
        val dest = File(dir, "${a.pkg}-${a.versionCode}.apk")
        thread {
            val ok = try {
                download(a.apkUrl, dest) { pct ->
                    runOnUiThread { binding.progress.progress = pct }
                }
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                runOnUiThread {
                    busy = false
                    binding.action.isEnabled = true
                    binding.progress.visibility = View.GONE
                    setStatus("Download failed. Check your connection and try again.")
                }
                return@thread
            }
            // Verify the file really is the app we promised, signed with the
            // catalog certificate, before handing it to the installer.
            val archivePkg = Installer.apkPackageName(this, dest)
            val cert = Installer.apkCertSha256(this, dest)
            val certOk = a.certSha256.isEmpty() ||
                (cert != null && normalize(a.certSha256) == normalize(cert))
            runOnUiThread {
                busy = false
                binding.action.isEnabled = true
                binding.progress.visibility = View.GONE
                when {
                    archivePkg != a.pkg -> {
                        dest.delete()
                        setStatus("Downloaded file does not match this app. Cancelled.")
                    }
                    !certOk -> {
                        dest.delete()
                        setStatus("Signature check failed. For your safety, the install was cancelled.")
                    }
                    else -> {
                        History.add(this, a)
                        setStatus("Opening installer…")
                        Installer.install(this, dest)
                    }
                }
            }
        }
    }

    private fun normalize(s: String): String =
        s.replace(":", "").replace(" ", "").uppercase()

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "MBOXStore-Android")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(16 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        return dest.exists() && dest.length() > 0
    }
}
