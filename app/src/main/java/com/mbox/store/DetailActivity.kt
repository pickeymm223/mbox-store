package com.mbox.store

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mbox.store.databinding.ActivityDetailBinding
import java.io.File
import java.security.MessageDigest
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
            pollDownload()
            handler.postDelayed(this, 1000)
        }
    }

    // MBOX Pay: watch a created order until Telegram / the chain settles it.
    private var balanceUsdt: Double = 0.0
    private var balanceStars: Int = 0
    private var orderPolling = false
    private var watchedOrderId: String? = null
    private val orderTick = object : Runnable {
        override fun run() {
            if (!orderPolling) return
            pollOrderOnce()
            handler.postDelayed(this, 4000)
        }
    }

    // Purchase verification: the device lock (PIN / pattern / password /
    // biometric) must be confirmed before any order is created, so a stray
    // tap or someone holding the phone cannot spend. A device with no lock
    // set skips the prompt (there is nothing to verify against).
    private var pendingVerifiedAction: (() -> Unit)? = null
    private val confirmPurchase = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pendingVerifiedAction
        pendingVerifiedAction = null
        if (result.resultCode == RESULT_OK) {
            action?.invoke()
        } else {
            setStatus("Purchase cancelled — verification was not completed.")
        }
    }

    private fun verifyPurchase(action: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            action()
            return
        }
        val intent = km.createConfirmDeviceCredentialIntent(
            "Confirm your purchase",
            "Verify it's you before paying"
        )
        if (intent == null) {
            action()
            return
        }
        pendingVerifiedAction = action
        confirmPurchase.launch(intent)
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
        binding.supportBtn.setOnClickListener { app?.let { onSupport(it) } }
        balanceUsdt = PayCache.balanceUsdt(this)
        balanceStars = PayCache.balanceStars(this)
        refreshState()
        renderPay()
    }

    override fun onResume() {
        super.onResume()
        if (!verifying) syncDownloadUi()
        // Coming back from Telegram / a wallet: resume watching any
        // order that is still settling so the page unlocks by itself.
        if (watchedOrderId != null && !orderPolling) watchOrder(watchedOrderId!!)
        val a = app
        if (a != null && (a.isPaid || a.support || a.iap.isNotEmpty()) &&
            Account.isLinked(this)
        ) {
            thread {
                PayCache.refresh(this)
                val bal = try { Pay.balance(this) } catch (e: Exception) { null }
                runOnUiThread {
                    if (bal != null) {
                        balanceUsdt = bal.usdt
                        balanceStars = bal.stars
                    }
                    refreshState()
                    renderPay()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        stopOrderPolling()
    }

    private fun refreshState() {
        val a = app ?: return
        binding.action.isEnabled = true
        binding.cancel.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.action.text = when (Installer.stateFor(this, a)) {
            Installer.State.OPEN -> "Open"
            Installer.State.UPDATE -> "Update"
            else ->
                if (a.isPaid && !PayCache.owns(this, a.pkg)) "Buy" else "Install"
        }
    }

    private fun onAction() {
        val a = app ?: return
        when (Installer.stateFor(this, a)) {
            Installer.State.OPEN -> Installer.openApp(this, a.pkg)
            else -> {
                if (a.isPaid && !PayCache.owns(this, a.pkg)) {
                    startBuy(a)
                    return
                }
                // A finished download waiting for its install tap?
                val p = Downloader.query(this, a.pkg)
                if (p != null && p.status == Downloader.STATUS_SUCCESSFUL) {
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
        // Download progress lives in the notification shade; on Android 13+
        // that needs the notification permission.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41
            )
        }
        Downloader.enqueue(this, a)
        showDownloading(0, a.sizeBytes)
        startPolling()
    }

    /** Re-attach the UI to a download started earlier (running or finished). */
    private fun syncDownloadUi() {
        val a = app ?: return
        val p = Downloader.query(this, a.pkg)
        when {
            p == null -> refreshState()
            p.status == Downloader.STATUS_SUCCESSFUL -> {
                if (Installer.installedVersion(this, a.pkg) >= a.versionCode) {
                    Downloader.forget(this, a.pkg)
                    refreshState()
                } else {
                    refreshState()
                    setStatus("Download complete — tap the button to install.")
                }
            }
            p.status == Downloader.STATUS_FAILED -> {
                Downloader.forget(this, a.pkg)
                Downloader.destFile(this, a).delete()
                refreshState()
                setStatus(
                    p.reason.ifEmpty {
                        "Download failed. Check your connection and try again."
                    }
                )
            }
            p.status == Downloader.STATUS_PAUSED -> {
                refreshState()
                binding.action.isEnabled = false
                binding.action.text = "Waiting…"
                binding.cancel.visibility = View.VISIBLE
                setStatus(p.reason)
            }
            else -> {
                showDownloading(p.done, if (p.total > 0) p.total else a.sizeBytes)
                startPolling()
            }
        }
    }

    private fun pollDownload() {
        val a = app ?: return
        val p = Downloader.query(this, a.pkg)
        if (p == null) {
            stopPolling()
            refreshState()
            return
        }
        when (p.status) {
            Downloader.STATUS_SUCCESSFUL -> {
                stopPolling()
                if (Prefs.askBeforeInstall(this)) {
                    refreshState()
                    setStatus("Download complete — tap the button to install.")
                } else {
                    verifyAndInstall(a, Downloader.destFile(this, a))
                }
            }
            Downloader.STATUS_FAILED -> {
                stopPolling()
                Downloader.forget(this, a.pkg)
                Downloader.destFile(this, a).delete()
                refreshState()
                setStatus(
                    p.reason.ifEmpty {
                        "Download failed. Check your connection and try again."
                    }
                )
            }
            Downloader.STATUS_PAUSED -> {
                stopPolling()
                refreshState()
                binding.action.isEnabled = false
                binding.action.text = "Waiting…"
                binding.cancel.visibility = View.VISIBLE
                setStatus(p.reason)
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

    // ---- MBOX Pay ----

    private fun renderPay() {
        val a = app ?: return
        if (a.isPaid) {
            binding.price.visibility = View.VISIBLE
            binding.price.text = priceText(a.priceStars, a.priceUsdt)
        } else {
            binding.price.visibility = View.GONE
        }
        binding.supportBtn.visibility = if (a.support) View.VISIBLE else View.GONE
        binding.iapRows.removeAllViews()
        binding.iapBox.visibility =
            if (a.iap.isNotEmpty()) View.VISIBLE else View.GONE
        for (p in a.iap) {
            binding.iapRows.addView(iapRow(a, p))
        }
    }

    private fun iapRow(a: StoreApp, p: IapProduct): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.weight = 1f
        texts.layoutParams = lp
        val t = TextView(this)
        t.text = p.label
        t.textSize = 15f
        t.setTextColor(getColor(R.color.text_primary))
        texts.addView(t)
        val s = TextView(this)
        s.text = priceText(p.priceStars, p.priceUsdt)
        s.textSize = 12.5f
        s.setTextColor(getColor(R.color.text_secondary))
        texts.addView(s)
        row.addView(texts)
        val owned = PayCache.owns(this, a.pkg + ":" + p.id)
        val b = Button(this)
        b.text = if (owned) "Owned" else "Buy"
        b.isEnabled = !owned
        b.setOnClickListener {
            if (!Account.isLinked(this)) {
                promptConnect()
                return@setOnClickListener
            }
            showRailSheet("iap", a.pkg, p.id, p.priceStars, p.priceUsdt, p.label)
        }
        row.addView(b)
        return row
    }

    private fun priceText(stars: Int, usdt: Double): CharSequence {
        val out = SpannableStringBuilder()
        if (stars > 0) out.append(Ui.starText(this, stars.toString(), 14))
        if (usdt > 0) {
            if (out.isNotEmpty()) out.append("  ·  ")
            out.append("₮ ${trimNum(usdt)}")
        }
        return out
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)

    private fun promptConnect() {
        setStatus(
            "Purchases are tied to your Telegram account. Connect Telegram " +
                "in Profile first — it is free and takes a few seconds."
        )
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, "profile")
        )
    }

    private fun startBuy(a: StoreApp) {
        if (!Account.isLinked(this)) {
            promptConnect()
            return
        }
        showRailSheet("app", a.pkg, null, a.priceStars, a.priceUsdt, a.name)
    }

    private fun onSupport(a: StoreApp) {
        if (!Account.isLinked(this)) {
            promptConnect()
            return
        }
        val dev = a.developer.ifEmpty { a.name }
        val railLabels = ArrayList<CharSequence>()
        val railKeys = ArrayList<String>()
        if (balanceUsdt > 0 || balanceStars > 0) {
            railLabels.add("MBOX Balance"); railKeys.add("balance")
        }
        railLabels.add("Telegram Stars"); railKeys.add("stars")
        railLabels.add("USDT (TON)"); railKeys.add("usdt")
        AlertDialog.Builder(this)
            .setTitle("Support $dev")
            .setItems(railLabels.toTypedArray()) { _, railIdx ->
                if (railKeys[railIdx] == "balance") {
                    showSupportBalance(a, dev)
                } else if (railKeys[railIdx] == "stars") {
                    val presets = intArrayOf(10, 25, 50, 100)
                    val labels = ArrayList<CharSequence>()
                    for (p in presets) labels.add(Ui.starText(this, "$p Stars", 18))
                    labels.add("Custom amount…")
                    AlertDialog.Builder(this)
                        .setTitle("Support $dev — Stars")
                        .setItems(labels.toTypedArray()) { _, which ->
                            if (which == presets.size) promptCustomSupport(a, "stars")
                            else createSupportOrder(a, "stars", presets[which], 0.0)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    val presets = doubleArrayOf(0.5, 1.0, 2.5, 5.0)
                    val labels = ArrayList<CharSequence>()
                    for (p in presets) labels.add("₮ ${trimNum(p)} USDT")
                    labels.add("Custom amount…")
                    AlertDialog.Builder(this)
                        .setTitle("Support $dev — USDT")
                        .setItems(labels.toTypedArray()) { _, which ->
                            if (which == presets.size) promptCustomSupport(a, "usdt")
                            else createSupportOrder(a, "usdt", 0, presets[which])
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Support from the pre-funded balance: pick the currency (when
     *  both are funded), then a preset or a custom amount. Spending
     *  the balance never leaves the app. */
    private fun showSupportBalance(a: StoreApp, dev: String) {
        if (balanceUsdt > 0 && balanceStars > 0) {
            val labels = arrayOf<CharSequence>(
                "USDT — ₮ ${trimNum(balanceUsdt)}",
                Ui.starText(this, "Stars — $balanceStars", 18)
            )
            AlertDialog.Builder(this)
                .setTitle("Support $dev — Balance")
                .setItems(labels) { _, which ->
                    if (which == 0) showSupportBalanceUsdt(a, dev)
                    else showSupportBalanceStars(a, dev)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else if (balanceUsdt > 0) {
            showSupportBalanceUsdt(a, dev)
        } else {
            showSupportBalanceStars(a, dev)
        }
    }

    private fun showSupportBalanceUsdt(a: StoreApp, dev: String) {
        val presets = doubleArrayOf(0.5, 1.0, 2.5, 5.0)
            .filter { it <= balanceUsdt }
        val labels = ArrayList<CharSequence>()
        for (p in presets) labels.add("₮ ${trimNum(p)} USDT")
        labels.add("Custom amount…")
        AlertDialog.Builder(this)
            .setTitle("Support $dev — Balance (USDT)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) promptCustomSupport(a, "balance")
                else createSupportOrder(a, "balance", 0, presets[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSupportBalanceStars(a: StoreApp, dev: String) {
        val presets = intArrayOf(10, 25, 50, 100)
            .filter { it <= balanceStars }
        val labels = ArrayList<CharSequence>()
        for (p in presets) labels.add(Ui.starText(this, "$p Stars", 18))
        labels.add("Custom amount…")
        AlertDialog.Builder(this)
            .setTitle("Support $dev — Balance (Stars)")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) promptCustomSupport(a, "balance-stars")
                else createSupportOrder(a, "balance-stars", presets[which], 0.0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createSupportOrder(a: StoreApp, rail: String, stars: Int, usdt: Double) {
        verifyPurchase {
            setStatus("Creating your support order…")
            val serverRail = if (rail == "balance-stars") "balance" else rail
            val balCurrency = when (rail) {
                "balance-stars" -> "stars"
                "balance" -> "usdt"
                else -> null
            }
            thread {
                val created = try {
                    Pay.createOrder(
                        this, "support", a.pkg, null, serverRail,
                        amountStars = stars, amountUsdt = usdt,
                        balanceCurrency = balCurrency
                    )
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (created == null) {
                        setStatus("Could not create the order. Try again.")
                    } else if (serverRail == "balance") {
                        if (rail == "balance-stars") {
                            balanceStars = (balanceStars - stars).coerceAtLeast(0)
                        } else {
                            balanceUsdt = (balanceUsdt - usdt).coerceAtLeast(0.0)
                        }
                        setStatus(
                            "Paid from your MBOX balance — thank you for your support!"
                        )
                        thread { PayCache.refresh(this) }
                    } else if (rail == "usdt") {
                        startActivity(
                            Intent(this, UsdtPayActivity::class.java)
                                .putExtra(UsdtPayActivity.EXTRA_ORDER, created.order.id)
                                .putExtra(UsdtPayActivity.EXTRA_ADDRESS, created.address)
                                .putExtra(UsdtPayActivity.EXTRA_MEMO, created.order.memo)
                                .putExtra(UsdtPayActivity.EXTRA_AMOUNT, created.order.amountUsdt)
                                .putExtra(
                                    UsdtPayActivity.EXTRA_LABEL,
                                    "Support ${a.developer.ifEmpty { a.name }}"
                                )
                        )
                        watchOrder(created.order.id)
                    } else {
                        if (created.invoiceLink.isNotEmpty()) {
                            setStatus(
                                "Opening Telegram to finish your Stars payment…"
                            )
                            openInvoiceLink(created.invoiceLink)
                        } else {
                            setStatus(
                                "Invoice sent to @mboxstoreappbot in Telegram " +
                                    "— pay there to complete your support."
                            )
                        }
                        watchOrder(created.order.id)
                    }
                }
            }
        }
    }

    private fun promptCustomSupport(a: StoreApp, rail: String) {
        val input = EditText(this)
        input.inputType = if (rail == "stars" || rail == "balance-stars")
            InputType.TYPE_CLASS_NUMBER
        else
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = if (rail == "stars" || rail == "balance-stars")
            "Amount in Stars (min 1)" else "Amount in USDT (min 0.1)"
        val wrap = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)
        input.layoutParams = lp
        wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Custom amount")
            .setView(wrap)
            .setPositiveButton("Continue") { _, _ ->
                val raw = input.text.toString().trim()
                if (rail == "stars" || rail == "balance-stars") {
                    val v = raw.toIntOrNull()
                    if (v == null || v < 1) {
                        setStatus("Enter a valid Stars amount (min 1).")
                    } else if (rail == "balance-stars" && v > balanceStars) {
                        setStatus(
                            "That is more than your Stars balance " +
                                "($balanceStars). Top up in Profile first."
                        )
                    } else {
                        createSupportOrder(a, rail, v, 0.0)
                    }
                } else {
                    val v = raw.toDoubleOrNull()
                    if (v == null || v < 0.1) {
                        setStatus("Enter a valid USDT amount (min 0.1).")
                    } else if (rail == "balance" && v > balanceUsdt) {
                        setStatus(
                            "That is more than your MBOX balance " +
                                "(₮ ${trimNum(balanceUsdt)}). Top up in Profile first."
                        )
                    } else {
                        createSupportOrder(a, rail, 0, v)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRailSheet(
        kind: String,
        item: String,
        productId: String?,
        stars: Int,
        usdt: Double,
        label: String
    ) {
        val options = ArrayList<CharSequence>()
        val rails = ArrayList<String>()
        if (stars > 0 && balanceStars >= stars) {
            options.add(Ui.starText(this, "MBOX Balance — $balanceStars Stars", 18))
            rails.add("balance-stars")
        }
        if (usdt > 0 && balanceUsdt >= usdt) {
            options.add("MBOX Balance — ₮ ${trimNum(balanceUsdt)}")
            rails.add("balance")
        }
        if (stars > 0) {
            options.add(Ui.starText(this, "Telegram Stars — $stars", 18))
            rails.add("stars")
        }
        if (usdt > 0) {
            options.add("USDT (TON) — ₮ ${trimNum(usdt)}")
            rails.add("usdt")
        }
        if (options.isEmpty()) return
        if (options.size == 1) {
            createOrder(kind, item, productId, rails[0], label)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Pay for $label")
            .setItems(options.toTypedArray()) { _, which ->
                createOrder(kind, item, productId, rails[which], label)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createOrder(
        kind: String,
        item: String,
        productId: String?,
        rail: String,
        label: String
    ) {
        verifyPurchase { createOrderVerified(kind, item, productId, rail, label) }
    }

    private fun createOrderVerified(
        kind: String,
        item: String,
        productId: String?,
        rail: String,
        label: String
    ) {
        setStatus("Creating your order…")
        val serverRail = if (rail == "balance-stars") "balance" else rail
        val balCurrency = when (rail) {
            "balance-stars" -> "stars"
            "balance" -> "usdt"
            else -> null
        }
        thread {
            val created = try {
                Pay.createOrder(
                    this, kind, item, productId, serverRail,
                    balanceCurrency = balCurrency
                )
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (created == null) {
                    setStatus(
                        "Could not create the order. Check your connection " +
                            "and try again."
                    )
                    return@runOnUiThread
                }
                if (serverRail == "balance") {
                    setStatus(
                        "Paid from your MBOX balance — thank you! " +
                            "Your purchase is unlocked."
                    )
                    thread {
                        PayCache.refresh(this)
                        val bal = try { Pay.balance(this) } catch (e: Exception) { null }
                        runOnUiThread {
                            if (bal != null) {
                                balanceUsdt = bal.usdt
                                balanceStars = bal.stars
                            }
                            refreshState()
                            renderPay()
                        }
                    }
                } else if (rail == "usdt") {
                    startActivity(
                        Intent(this, UsdtPayActivity::class.java)
                            .putExtra(UsdtPayActivity.EXTRA_ORDER, created.order.id)
                            .putExtra(UsdtPayActivity.EXTRA_ADDRESS, created.address)
                            .putExtra(UsdtPayActivity.EXTRA_MEMO, created.order.memo)
                            .putExtra(UsdtPayActivity.EXTRA_AMOUNT, created.order.amountUsdt)
                            .putExtra(UsdtPayActivity.EXTRA_LABEL, label)
                    )
                    watchOrder(created.order.id)
                } else {
                    // Stars direct: one tap on the invoice link lands on
                    // Telegram's payment sheet; coming back here, the
                    // order watch unlocks the page by itself.
                    if (created.invoiceLink.isNotEmpty()) {
                        setStatus(
                            "Opening Telegram to finish your Stars payment — " +
                                "come back here and this page unlocks by itself."
                        )
                        openInvoiceLink(created.invoiceLink)
                    } else if (created.invoiceSent) {
                        setStatus(
                            "Invoice sent to @mboxstoreappbot in Telegram — " +
                                "pay there and this page unlocks by itself."
                        )
                    } else {
                        setStatus(
                            "Order created — open @mboxstoreappbot in " +
                                "Telegram to pay."
                        )
                    }
                    watchOrder(created.order.id)
                }
            }
        }
    }

    /** Open a Telegram invoice link (t.me/$…) — Telegram shows its
     *  payment sheet directly, no bot-chat hunting. */
    private fun openInvoiceLink(link: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not open Telegram — open @mboxstoreappbot to pay.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun watchOrder(id: String) {
        watchedOrderId = id
        if (orderPolling) return
        orderPolling = true
        handler.postDelayed(orderTick, 3000)
    }

    private fun stopOrderPolling() {
        orderPolling = false
        handler.removeCallbacks(orderTick)
    }

    private fun pollOrderOnce() {
        val id = watchedOrderId
        if (id == null) {
            stopOrderPolling()
            return
        }
        thread {
            val o = try {
                Pay.status(this, id)
            } catch (e: Exception) {
                null
            }
            when {
                o != null && o.status == "paid" -> {
                    PayCache.refresh(this)
                    runOnUiThread {
                        stopOrderPolling()
                        setStatus("Payment received — thank you! You can install now.")
                        refreshState()
                        renderPay()
                    }
                }
                o != null && o.status == "expired" -> runOnUiThread {
                    stopOrderPolling()
                    setStatus("That order expired. Tap Buy to try again.")
                }
            }
        }
    }

    private fun verifyAndInstall(a: StoreApp, dest: File) {
        if (verifying) return
        verifying = true
        binding.action.isEnabled = false
        binding.cancel.visibility = View.GONE
        binding.progress.visibility = View.GONE
        setStatus("Checking the downloaded file…")
        thread {
            // Verify the file really is the app we promised: package name,
            // the catalog file hash (when recorded), and the developer's
            // signing certificate — before handing it to the installer.
            val archivePkg = Installer.apkPackageName(this, dest)
            val cert = Installer.apkCertSha256(this, dest)
            val certOk = a.certSha256.isEmpty() ||
                (cert != null && normalize(a.certSha256) == normalize(cert))
            val fileHash = if (a.sha256.isNotEmpty()) sha256Of(dest) else null
            val hashOk = a.sha256.isEmpty() ||
                (fileHash != null && normalize(fileHash) == normalize(a.sha256))
            runOnUiThread {
                verifying = false
                Downloader.forget(this, a.pkg)
                when {
                    archivePkg != a.pkg -> {
                        dest.delete()
                        refreshState()
                        setStatus("Downloaded file does not match this app. Cancelled.")
                    }
                    !hashOk -> {
                        dest.delete()
                        refreshState()
                        setStatus(
                            "File check failed — the download does not match " +
                                "the catalog hash. Cancelled."
                        )
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

    private fun sha256Of(f: File): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun setStatus(text: String) {
        binding.status.visibility = View.VISIBLE
        binding.status.text = text
    }

    private fun normalize(s: String): String =
        s.replace(":", "").replace(" ", "").uppercase()
}
