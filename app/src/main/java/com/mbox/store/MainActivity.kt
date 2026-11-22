package com.mbox.store

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mbox.store.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var flatAdapter: AppAdapter
    private lateinit var updatesAdapter: AppAdapter
    private lateinit var searchAdapter: AppAdapter
    private lateinit var essentialAdapter: AppAdapter
    private lateinit var popularAdapter: AppAdapter
    private lateinit var allAdapter: AppAdapter
    private lateinit var catAdapter: CategoryAdapter
    private var allApps: List<StoreApp> = emptyList()

    private var flatTitle: String? = null
    private var flatApps: List<StoreApp> = emptyList()
    private var essentialApps: List<StoreApp> = emptyList()
    private var popularApps: List<StoreApp> = emptyList()
    private var restApps: List<StoreApp> = emptyList()
    private var bannerTarget: StoreApp? = null

    // Telegram connect flow state.
    private var connectCode: String? = null
    private var connectAttempts = 0
    private val connectHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val openDetail = { app: StoreApp ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PKG, app.pkg)
            )
        }
        flatAdapter = AppAdapter(openDetail)
        updatesAdapter = AppAdapter(openDetail)
        searchAdapter = AppAdapter(openDetail)
        essentialAdapter = AppAdapter(openDetail)
        popularAdapter = AppAdapter(openDetail)
        allAdapter = AppAdapter(openDetail)
        catAdapter = CategoryAdapter { group ->
            enterFlat(group.title, group.apps)
            binding.bottomNav.selectedItemId = R.id.nav_home
            showSection(R.id.nav_home)
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = flatAdapter
        binding.updatesList.layoutManager = LinearLayoutManager(this)
        binding.updatesList.adapter = updatesAdapter
        binding.catResults.layoutManager = LinearLayoutManager(this)
        binding.catResults.adapter = searchAdapter
        binding.essentialList.layoutManager = LinearLayoutManager(this)
        binding.essentialList.adapter = essentialAdapter
        binding.popularList.layoutManager = LinearLayoutManager(this)
        binding.popularList.adapter = popularAdapter
        binding.allList.layoutManager = LinearLayoutManager(this)
        binding.allList.adapter = allAdapter
        binding.catList.layoutManager = GridLayoutManager(this, 2)
        binding.catList.adapter = catAdapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applySearch()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.retry.setOnClickListener { load() }
        binding.filterClear.setOnClickListener { exitFlat() }
        binding.searchPill.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_categories
            showSection(R.id.nav_categories)
            binding.search.requestFocus()
        }
        binding.featureBanner.setOnClickListener {
            bannerTarget?.let { openDetail(it) }
        }
        binding.essentialHead.setOnClickListener {
            enterFlat(getString(R.string.sec_essential), essentialApps)
        }
        binding.popularHead.setOnClickListener {
            enterFlat(getString(R.string.sec_popular), popularApps)
        }
        binding.allHead.setOnClickListener {
            enterFlat(getString(R.string.sec_all), restApps)
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            showSection(item.itemId)
            true
        }

        if (intent.getStringExtra(EXTRA_TAB) == "profile") {
            binding.bottomNav.selectedItemId = R.id.nav_profile
            showSection(R.id.nav_profile)
        }

        if (Catalog.apps.isNotEmpty()) {
            allApps = Catalog.apps
            onCatalogReady()
        } else {
            load()
        }

        // If we hold a link token, confirm it is still valid server-side;
        // a link revoked elsewhere should not linger on this device.
        if (Account.isLinked(this)) {
            thread {
                val me = Account.fetchMe(this)
                if (me == null) {
                    Account.clear(this)
                    runOnUiThread { refreshProfile() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_TAB) == "profile") {
            binding.bottomNav.selectedItemId = R.id.nav_profile
            showSection(R.id.nav_profile)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        // Install states may have changed while we were away.
        if (allApps.isNotEmpty()) {
            essentialAdapter.refresh()
            popularAdapter.refresh()
            allAdapter.refresh()
            searchAdapter.refresh()
            flatAdapter.refresh()
            refreshUpdates()
            refreshProfile()
            refreshBanner()
        }
    }

    private fun showSection(id: Int) {
        binding.sectionHome.visibility = if (id == R.id.nav_home) View.VISIBLE else View.GONE
        binding.sectionCategories.visibility =
            if (id == R.id.nav_categories) View.VISIBLE else View.GONE
        binding.sectionUpdates.visibility =
            if (id == R.id.nav_updates) View.VISIBLE else View.GONE
        binding.sectionProfile.visibility =
            if (id == R.id.nav_profile) View.VISIBLE else View.GONE
        if (id == R.id.nav_updates) refreshUpdates()
        if (id == R.id.nav_profile) refreshProfile()
    }

    private fun load() {
        binding.progress.visibility = View.VISIBLE
        binding.errorBox.visibility = View.GONE
        thread {
            try {
                val apps = Catalog.fetch()
                runOnUiThread {
                    binding.progress.visibility = View.GONE
                    allApps = apps
                    onCatalogReady()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progress.visibility = View.GONE
                    binding.errorBox.visibility = View.VISIBLE
                    binding.errorText.text =
                        "Could not load the catalog.\nCheck your connection and try again."
                }
            }
        }
    }

    private fun onCatalogReady() {
        refreshSections()
        refreshBanner()
        catAdapter.submit(categoryGroups())
        refreshUpdates()
        refreshProfile()
        applySearch()
    }

    // ---- Home sections ----

    private fun refreshSections() {
        essentialApps = ESSENTIAL_PKGS.mapNotNull { Catalog.find(it) }
        popularApps = POPULAR_PKGS.mapNotNull { Catalog.find(it) }
        val inSections = (essentialApps + popularApps).map { it.pkg }.toSet()
        restApps = allApps.filter { it.pkg !in inSections }
        essentialAdapter.submit(essentialApps)
        popularAdapter.submit(popularApps)
        allAdapter.submit(restApps)
        binding.essentialHead.visibility =
            if (essentialApps.isEmpty()) View.GONE else View.VISIBLE
        binding.essentialList.visibility =
            if (essentialApps.isEmpty()) View.GONE else View.VISIBLE
        binding.popularHead.visibility =
            if (popularApps.isEmpty()) View.GONE else View.VISIBLE
        binding.popularList.visibility =
            if (popularApps.isEmpty()) View.GONE else View.VISIBLE
        binding.allHead.visibility =
            if (restApps.isEmpty()) View.GONE else View.VISIBLE
        binding.allList.visibility =
            if (restApps.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refreshBanner() {
        val upd = ownUpdate()
        val featured = Catalog.find(FEATURED_PKG) ?: allApps.firstOrNull()
        val target = upd ?: featured
        if (target == null) {
            binding.featureBanner.visibility = View.GONE
            bannerTarget = null
            return
        }
        binding.featureBanner.visibility = View.VISIBLE
        bannerTarget = target
        if (upd != null) {
            binding.bannerKicker.text = getString(R.string.store_update_kicker)
            binding.bannerTitle.text = "MBOX Store v${upd.versionName} is here"
            binding.bannerText.text = upd.changelog
                .ifEmpty { "A new version of MBOX Store is ready to install." }
        } else {
            binding.bannerKicker.text = getString(R.string.featured_app)
            binding.bannerTitle.text = target.name
            binding.bannerText.text = target.description.trim().take(120)
        }
        IconLoader.load(target.iconUrl, binding.bannerIcon)
    }

    // ---- Flat (single-section) mode on Home ----

    private fun enterFlat(title: String, apps: List<StoreApp>) {
        flatTitle = title
        flatApps = apps
        binding.filterLabel.text = title
        binding.homeSections.visibility = View.GONE
        binding.homeFlat.visibility = View.VISIBLE
        flatAdapter.submit(apps)
        binding.emptyText.visibility = View.GONE
    }

    private fun exitFlat() {
        flatTitle = null
        flatApps = emptyList()
        binding.homeFlat.visibility = View.GONE
        binding.homeSections.visibility = View.VISIBLE
    }

    // ---- Search (inside the Categories tab) ----

    private fun applySearch() {
        val q = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) {
            binding.catResults.visibility = View.GONE
            binding.catList.visibility = View.VISIBLE
            binding.browseLabel.visibility = View.VISIBLE
            return
        }
        val filtered = allApps.filter { app ->
            app.name.lowercase().contains(q) ||
                app.developer.lowercase().contains(q) ||
                app.genre.lowercase().contains(q) ||
                app.category.lowercase().contains(q)
        }
        searchAdapter.submit(filtered)
        binding.catList.visibility = View.GONE
        binding.browseLabel.visibility = View.GONE
        binding.catResults.visibility = View.VISIBLE
    }

    // ---- Categories ----

    data class CatGroup(val title: String, val apps: List<StoreApp>)

    private fun categoryGroups(): List<CatGroup> {
        val comm = allApps.filter { it.category == "Communication" }
        val browsers = allApps.filter { it.genre == "Browser" || it.genre == "Privacy Browser" }
        val vpn = allApps.filter { it.genre == "VPN" || it.genre == "VPN & Proxy" }
        val video = allApps.filter { it.genre == "Video Player" || it.genre == "Music" }
        val grouped = (comm + browsers + vpn + video).map { it.pkg }.toSet()
        val tools = allApps.filter { it.pkg !in grouped }
        return listOf(
            CatGroup("Communication", comm),
            CatGroup("Browsers", browsers),
            CatGroup("VPN & Privacy", vpn),
            CatGroup("Video & Music", video),
            CatGroup("Tools", tools)
        ).filter { it.apps.isNotEmpty() }
    }

    // ---- Updates ----

    /** The store's own catalog entry when a newer version exists. */
    private fun ownUpdate(): StoreApp? {
        val entry = Catalog.find(packageName) ?: return null
        val installed = Installer.installedVersion(this, packageName)
        return if (entry.versionCode > installed) entry else null
    }

    private fun updatesAvailable(): List<StoreApp> =
        allApps.filter { Installer.stateFor(this, it) == Installer.State.UPDATE }

    private fun refreshUpdates() {
        if (allApps.isEmpty()) return
        val updates = updatesAvailable()
        updatesAdapter.submit(updates)
        binding.updatesSummary.text = when {
            updates.isEmpty() -> ""
            updates.size == 1 -> "1 update available"
            else -> "${updates.size} updates available"
        }
        binding.updatesEmpty.visibility = if (updates.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- Profile (Play-style grouped menu, own wording) ----

    private fun refreshProfile() {
        val box = binding.profileBox
        box.removeAllViews()
        val linked = Account.isLinked(this)

        if (linked) {
            box.addView(accountCard())
        } else if (connectCode != null) {
            box.addView(connectWaitingCard())
        } else {
            box.addView(connectCard())
        }

        val manage = Ui.row(
            this, R.drawable.ic_row_plane, "#2AABEE",
            "Manage your Telegram connection",
            sub = if (linked) "Connected" else "Not connected"
        ) { onManageConnection() }
        val manageGroup = LinearLayout(this)
        manageGroup.orientation = LinearLayout.VERTICAL
        manageGroup.background = Ui.cardBg(this)
        val mgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mgLp.topMargin = Ui.dp(this, 12)
        manageGroup.layoutParams = mgLp
        manageGroup.addView(manage)
        box.addView(manageGroup)

        val updates = if (allApps.isEmpty()) emptyList() else updatesAvailable()
        val group1 = Ui.group(
            this,
            Ui.row(
                this, R.drawable.ic_row_grid, "#2EC4B6",
                "Manage apps & device"
            ) {
                binding.bottomNav.selectedItemId = R.id.nav_updates
                showSection(R.id.nav_updates)
            },
            Ui.row(
                this, R.drawable.ic_row_bell, "#FFC94D",
                "Notifications & offers",
                badge = if (updates.isNotEmpty()) updates.size.toString() else null
            ) { openNotifications(updates) },
            Ui.row(
                this, R.drawable.ic_row_card, "#6EC1E4",
                "Payments & purchases",
                sub = "Telegram Stars · purchase history"
            ) { InfoActivity.open(this, "Payments & purchases", Texts.PURCHASES) },
            Ui.row(
                this, R.drawable.ic_row_shield, "#7FD1AE",
                "App verification",
                sub = "Every APK signature-checked before install"
            ) { InfoActivity.open(this, "App verification", Texts.VERIFICATION) },
            Ui.row(
                this, R.drawable.ic_row_folder, "#B39DDB",
                "Library",
                sub = "Purchased & downloaded apps"
            ) { startActivity(Intent(this, LibraryActivity::class.java)) },
            Ui.row(
                this, R.drawable.ic_row_star, "#FFC94D",
                "Support sent",
                sub = "Stars you sent to developers"
            ) { InfoActivity.open(this, "Support sent", Texts.SUPPORT) }
        )
        val g1Lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        g1Lp.topMargin = Ui.dp(this, 12)
        group1.layoutParams = g1Lp
        box.addView(group1)

        val group2 = Ui.group(
            this,
            Ui.row(
                this, R.drawable.ic_row_person, "#F48FB1",
                "Personalization in MBOX Store"
            ) { startActivity(Intent(this, SettingsActivity::class.java)) },
            Ui.row(
                this, R.drawable.ic_row_sliders, "#9AA7B1",
                "Settings"
            ) { startActivity(Intent(this, SettingsActivity::class.java)) },
            Ui.row(
                this, R.drawable.ic_row_info, "#6EC1E4",
                "Help & feedback"
            ) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Account.BOT_LINK)))
            }
        )
        val g2Lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        g2Lp.topMargin = Ui.dp(this, 12)
        group2.layoutParams = g2Lp
        box.addView(group2)

        box.addView(legalFooter())
    }

    private fun accountCard(): View {
        val user = Account.user(this)
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.background = Ui.cardBg(this, 26)
        card.setPadding(
            Ui.dp(this, 18), Ui.dp(this, 18),
            Ui.dp(this, 18), Ui.dp(this, 18)
        )
        val avatar = ImageView(this)
        val aLp = LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58))
        avatar.layoutParams = aLp
        avatar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        avatar.clipToOutline = true
        if (user != null && user.avatarUrl.isNotEmpty()) {
            IconLoader.load(user.avatarUrl, avatar)
        } else {
            avatar.setImageResource(R.drawable.ic_launcher)
        }
        card.addView(avatar)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val tLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.weight = 1f
        tLp.marginStart = Ui.dp(this, 14)
        texts.layoutParams = tLp
        val name = TextView(this)
        name.text = user?.name?.ifEmpty { "Telegram user" } ?: "Telegram user"
        name.textSize = 18f
        name.setTextColor(getColor(R.color.text_primary))
        name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
        texts.addView(name)
        val sub = TextView(this)
        sub.text = "Connected with Telegram"
        sub.textSize = 13.5f
        sub.setTextColor(Color.parseColor("#2AABEE"))
        val sLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        sLp.topMargin = Ui.dp(this, 3)
        sub.layoutParams = sLp
        texts.addView(sub)
        card.addView(texts)
        card.setOnClickListener { onManageConnection() }
        return card
    }

    private fun connectCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.cardBg(this, 26)
        card.setPadding(
            Ui.dp(this, 18), Ui.dp(this, 18),
            Ui.dp(this, 18), Ui.dp(this, 18)
        )
        val title = TextView(this)
        title.text = "Connect Telegram"
        title.textSize = 18f
        title.setTextColor(getColor(R.color.text_primary))
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        card.addView(title)
        val body = TextView(this)
        body.text = "Sync your purchases, get update alerts in Telegram, " +
            "and pay with Telegram Stars. Free downloads never need an account."
        body.textSize = 14f
        body.setTextColor(getColor(R.color.text_secondary))
        val bLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bLp.topMargin = Ui.dp(this, 6)
        body.layoutParams = bLp
        card.addView(body)
        val btn = Button(this)
        btn.text = "Connect Telegram"
        btn.setTextColor(Color.parseColor("#06231F"))
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = Ui.dp(this, 24).toFloat()
        bg.setColor(getColor(R.color.accent))
        btn.background = bg
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        btnLp.topMargin = Ui.dp(this, 14)
        btn.layoutParams = btnLp
        btn.setOnClickListener { startConnect() }
        card.addView(btn)
        return card
    }

    private fun connectWaitingCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Ui.cardBg(this, 26)
        card.setPadding(
            Ui.dp(this, 18), Ui.dp(this, 18),
            Ui.dp(this, 18), Ui.dp(this, 18)
        )
        val rowTop = LinearLayout(this)
        rowTop.orientation = LinearLayout.HORIZONTAL
        rowTop.gravity = Gravity.CENTER_VERTICAL
        val spin = ProgressBar(this)
        val spLp = LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22))
        spin.layoutParams = spLp
        rowTop.addView(spin)
        val title = TextView(this)
        title.text = "Waiting for Telegram…"
        title.textSize = 16.5f
        title.setTextColor(getColor(R.color.text_primary))
        val tLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tLp.marginStart = Ui.dp(this, 12)
        title.layoutParams = tLp
        rowTop.addView(title)
        card.addView(rowTop)
        val body = TextView(this)
        body.text = "In the @mboxstoreappbot chat, tap Start. " +
            "This page updates by itself when you're connected."
        body.textSize = 13.5f
        body.setTextColor(getColor(R.color.text_secondary))
        val bLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bLp.topMargin = Ui.dp(this, 8)
        body.layoutParams = bLp
        card.addView(body)
        val cancel = TextView(this)
        cancel.text = "Cancel"
        cancel.textSize = 14.5f
        cancel.setTextColor(Color.parseColor("#E8736A"))
        val cLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cLp.topMargin = Ui.dp(this, 12)
        cancel.layoutParams = cLp
        cancel.setOnClickListener { cancelConnect() }
        card.addView(cancel)
        return card
    }

    private fun legalFooter(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Ui.dp(this, 22)
        row.layoutParams = lp
        val privacy = TextView(this)
        privacy.text = "Privacy Policy"
        privacy.textSize = 13f
        privacy.setTextColor(getColor(R.color.text_secondary))
        privacy.setOnClickListener {
            InfoActivity.open(this, "Privacy Policy", Texts.PRIVACY)
        }
        row.addView(privacy)
        val dot = TextView(this)
        dot.text = "  ·  "
        dot.textSize = 13f
        dot.setTextColor(getColor(R.color.text_secondary))
        row.addView(dot)
        val terms = TextView(this)
        terms.text = "Terms of Service"
        terms.textSize = 13f
        terms.setTextColor(getColor(R.color.text_secondary))
        terms.setOnClickListener {
            InfoActivity.open(this, "Terms of Service", Texts.TERMS)
        }
        row.addView(terms)
        return row
    }

    private fun openNotifications(updates: List<StoreApp>) {
        val body = if (updates.isEmpty()) {
            "You're all caught up.\n\nWhen an app you installed from " +
                "MBOX Store has an update, it will be listed here — and, " +
                "if you're connected, sent to you in Telegram."
        } else {
            buildString {
                append("Updates available:\n")
                for (app in updates) {
                    append("\n• ${app.name} — version ${app.versionName}")
                }
                append(
                    "\n\nOpen the Updates tab to install them. " +
                        "Connected accounts also get these alerts in Telegram."
                )
            }
        }
        InfoActivity.open(this, "Notifications & offers", body)
    }

    private fun onManageConnection() {
        if (!Account.isLinked(this)) {
            if (connectCode == null) startConnect()
            return
        }
        val user = Account.user(this)
        val who = when {
            !user?.username.isNullOrEmpty() -> "@${user?.username}"
            else -> user?.name ?: "your Telegram account"
        }
        AlertDialog.Builder(this)
            .setTitle(user?.name ?: "Telegram")
            .setMessage(
                "Connected as $who.\n\n" +
                    "Disconnecting removes the link on this device and on " +
                    "the server. Purchases stay recorded to your Telegram " +
                    "account."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                thread {
                    Account.unlink(this)
                    runOnUiThread {
                        Toast.makeText(
                            this, "Disconnected from Telegram",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshProfile()
                    }
                }
            }
            .show()
    }

    private fun startConnect() {
        Toast.makeText(this, "Opening Telegram…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val (code, link) = Account.createSession()
                runOnUiThread {
                    connectCode = code
                    connectAttempts = 0
                    refreshProfile()
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    scheduleConnectPoll()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Could not start the connection. Check your internet and try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun scheduleConnectPoll() {
        val code = connectCode ?: return
        connectHandler.postDelayed({
            if (connectCode != code) return@postDelayed
            thread {
                val result = try {
                    Account.pollStatus(code)
                } catch (e: Exception) {
                    Account.Poll.Pending
                }
                runOnUiThread {
                    if (connectCode != code) return@runOnUiThread
                    when (result) {
                        is Account.Poll.Linked -> {
                            Account.save(this, result.linkToken, result.user)
                            connectCode = null
                            Toast.makeText(
                                this, "Connected with Telegram",
                                Toast.LENGTH_SHORT
                            ).show()
                            refreshProfile()
                        }
                        Account.Poll.Expired -> {
                            connectCode = null
                            Toast.makeText(
                                this,
                                "That connect link expired — tap Connect to try again.",
                                Toast.LENGTH_LONG
                            ).show()
                            refreshProfile()
                        }
                        Account.Poll.Pending -> {
                            connectAttempts++
                            if (connectAttempts > 150) {
                                connectCode = null
                                refreshProfile()
                            } else {
                                scheduleConnectPoll()
                            }
                        }
                    }
                }
            }
        }, 2000)
    }

    private fun cancelConnect() {
        connectCode = null
        connectHandler.removeCallbacksAndMessages(null)
        refreshProfile()
    }

    inner class CategoryAdapter(
        private val onPick: (CatGroup) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private var items: List<CatGroup> = emptyList()

        fun submit(list: List<CatGroup>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val group = items[position]
            holder.name.text = group.title
            holder.count.text =
                if (group.apps.size == 1) "1 app" else "${group.apps.size} apps"
            holder.glyph.text = group.title.firstOrNull()?.uppercaseChar()?.toString() ?: ""
            val color = TILE_COLORS[position % TILE_COLORS.size]
            holder.glyph.background.mutate().setTint(Color.parseColor(color))
            holder.itemView.setOnClickListener { onPick(group) }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.catName)
            val count: TextView = v.findViewById(R.id.catCount)
            val glyph: TextView = v.findViewById(R.id.catGlyph)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"

        private val ESSENTIAL_PKGS = listOf(
            "com.opera.mini.native",
            "com.psiphon3",
            "ch.protonvpn.android",
            "org.torproject.torbrowser"
        )
        private val POPULAR_PKGS = listOf(
            "org.telegram.messenger.web",
            "com.whatsapp",
            "org.thoughtcrime.securesms",
            "org.mozilla.firefox"
        )
        private const val FEATURED_PKG = "org.telegram.messenger.web"
        private val TILE_COLORS = listOf(
            "#2EC4B6", "#6EC1E4", "#B39DDB", "#F48FB1", "#FFD54F", "#A5D6A7"
        )
    }
}
