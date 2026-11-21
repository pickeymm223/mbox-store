package com.mbox.store

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mbox.store.databinding.ActivityMainBinding
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = AppAdapter()
    private var allApps: List<StoreApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        binding.retry.setOnClickListener { load() }

        if (Catalog.apps.isNotEmpty()) {
            allApps = Catalog.apps
            applyFilter()
        } else {
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        // Install states may have changed while we were away.
        if (allApps.isNotEmpty()) adapter.notifyDataSetChanged()
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
                    applyFilter()
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

    private fun applyFilter() {
        val q = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = if (q.isEmpty()) allApps else allApps.filter {
            it.name.lowercase().contains(q) ||
                it.developer.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }
        adapter.submit(filtered)
        binding.emptyText.visibility =
            if (filtered.isEmpty() && allApps.isNotEmpty()) View.VISIBLE else View.GONE
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<StoreApp> = emptyList()

        fun submit(list: List<StoreApp>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.name.text = app.name
            val size = Installer.formatSize(app.sizeBytes)
            holder.meta.text = listOf(app.developer, size)
                .filter { it.isNotEmpty() }.joinToString(" • ")
            IconLoader.load(app.iconUrl, holder.icon)
            holder.action.text = when (Installer.stateFor(this@MainActivity, app)) {
                Installer.State.GET -> "GET"
                Installer.State.UPDATE -> "UPDATE"
                Installer.State.OPEN -> "OPEN"
            }
            val open = {
                startActivity(
                    Intent(this@MainActivity, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_PKG, app.pkg)
                )
            }
            holder.itemView.setOnClickListener { open() }
            holder.action.setOnClickListener { open() }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val action: Button = v.findViewById(R.id.action)
        }
    }
}
