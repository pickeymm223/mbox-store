package com.mbox.store

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mbox.store.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: AppAdapter
    private lateinit var updatesAdapter: AppAdapter
    private lateinit var catAdapter: CategoryAdapter
    private var allApps: List<StoreApp> = emptyList()
    private var categoryFilter: String? = null

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
        homeAdapter = AppAdapter(openDetail)
        updatesAdapter = AppAdapter(openDetail)
        catAdapter = CategoryAdapter { category ->
            categoryFilter = category
            binding.bottomNav.selectedItemId = R.id.nav_home
            showSection(R.id.nav_home)
            applyFilter()
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = homeAdapter
        binding.updatesList.layoutManager = LinearLayoutManager(this)
        binding.updatesList.adapter = updatesAdapter
        binding.catList.layoutManager = LinearLayoutManager(this)
        binding.catList.adapter = catAdapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.retry.setOnClickListener { load() }
        binding.filterClear.setOnClickListener {
            categoryFilter = null
            applyFilter()
        }
        val openSelf = {
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PKG, packageName)
            )
        }
        binding.updateBanner.setOnClickListener { openSelf() }
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
            homeAdapter.refresh()
            refreshUpdates()
            refreshProfile()
            refreshSelfUpdate()
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
        applyFilter()
        val cats = allApps.map { it.category }.filter { it.isNotEmpty() }
            .groupingBy { it }.eachCount()
            .toList().sortedBy { it.first }
        catAdapter.submit(cats)
        refreshUpdates()
        refreshProfile()
    }

    /** The store's own catalog entry when a newer version exists. */
    private fun ownUpdate(): StoreApp? {
        val entry = Catalog.find(packageName) ?: return null
        val installed = Installer.installedVersion(this, packageName)
        return if (entry.versionCode > installed) entry else null
    }

    private fun refreshSelfUpdate() {
        val upd = ownUpdate()
        binding.updateBanner.visibility = if (upd != null) View.VISIBLE else View.GONE
        if (upd != null) {
            binding.selfUpdateText.text =
                "${getString(R.string.self_update_available)} (v${upd.versionName})"
            binding.selfUpdateBtn.visibility = View.VISIBLE
        } else {
            binding.selfUpdateText.text = getString(R.string.self_latest)
            binding.selfUpdateBtn.visibility = View.GONE
        }
    }

    private fun applyFilter() {
        val q = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val cat = categoryFilter
        binding.filterBar.visibility = if (cat != null) View.VISIBLE else View.GONE
        if (cat != null) binding.filterLabel.text = "Category: $cat"
        val filtered = allApps.filter { app ->
            (cat == null || app.category == cat) &&
                (q.isEmpty() ||
                    app.name.lowercase().contains(q) ||
                    app.developer.lowercase().contains(q) ||
                    app.category.lowercase().contains(q))
        }
        homeAdapter.submit(filtered)
        binding.emptyText.visibility =
            if (filtered.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
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
        private val onPick: (String) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private var items: List<Pair<String, Int>> = emptyList()

        fun submit(list: List<Pair<String, Int>>) {
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
            val (name, count) = items[position]
            holder.name.text = name
            holder.count.text = if (count == 1) "1 app" else "$count apps"
            holder.itemView.setOnClickListener { onPick(name) }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.catName)
            val count: TextView = v.findViewById(R.id.catCount)
        }
    }
}
