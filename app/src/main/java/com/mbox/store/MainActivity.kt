package com.mbox.store

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mbox.store.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        val openSelf = {
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PKG, packageName)
            )
        }
        binding.selfUpdateBtn.setOnClickListener { openSelf() }
        binding.bottomNav.setOnItemSelectedListener { item ->
            showSection(item.itemId)
            true
        }

        if (Catalog.apps.isNotEmpty()) {
            allApps = Catalog.apps
            onCatalogReady()
        } else {
            load()
        }
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

    // ---- Updates / Profile / self-update (unchanged behaviour) ----

    /** The store's own catalog entry when a newer version exists. */
    private fun ownUpdate(): StoreApp? {
        val entry = Catalog.find(packageName) ?: return null
        val installed = Installer.installedVersion(this, packageName)
        return if (entry.versionCode > installed) entry else null
    }

    private fun refreshSelfUpdate() {
        val upd = ownUpdate()
        if (upd != null) {
            binding.selfUpdateText.text =
                "${getString(R.string.self_update_available)} (v${upd.versionName})"
            binding.selfUpdateBtn.visibility = View.VISIBLE
        } else {
            binding.selfUpdateText.text = getString(R.string.self_latest)
            binding.selfUpdateBtn.visibility = View.GONE
        }
    }

    private fun refreshUpdates() {
        if (allApps.isEmpty()) return
        val updates = allApps.filter {
            Installer.stateFor(this, it) == Installer.State.UPDATE
        }
        updatesAdapter.submit(updates)
        binding.updatesSummary.text = when {
            updates.isEmpty() -> ""
            updates.size == 1 -> "1 update available"
            else -> "${updates.size} updates available"
        }
        binding.updatesEmpty.visibility = if (updates.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshProfile() {
        binding.profileVersion.text = "Version ${BuildConfig.VERSION_NAME}"
        refreshSelfUpdate()

        val mine = allApps.filter { Installer.installedVersion(this, it.pkg) >= 0L }
        binding.myAppsBox.removeAllViews()
        if (mine.isEmpty()) {
            binding.myAppsBox.addView(dimText(getString(R.string.no_my_apps)))
        } else {
            for (app in mine) {
                val state = when (Installer.stateFor(this, app)) {
                    Installer.State.UPDATE -> "Update available"
                    else -> "Installed"
                }
                binding.myAppsBox.addView(rowText("${app.name} — $state"))
            }
        }

        val history = History.all(this)
        binding.historyBox.removeAllViews()
        if (history.isEmpty()) {
            binding.historyBox.addView(dimText(getString(R.string.no_history)))
        } else {
            val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
            for (e in history) {
                binding.historyBox.addView(
                    rowText("${e.name} • v${e.versionName} • ${fmt.format(Date(e.atMillis))}")
                )
            }
        }
    }

    private fun rowText(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(getColor(R.color.text_primary))
        tv.setPadding(0, 6, 0, 6)
        return tv
    }

    private fun dimText(text: String): TextView {
        val tv = rowText(text)
        tv.setTextColor(getColor(R.color.text_secondary))
        return tv
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
