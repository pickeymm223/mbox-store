package com.mbox.store

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.mbox.store.databinding.ActivityLibraryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Library: apps installed from the store + this device's download history. */
class LibraryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.libBack.setOnClickListener { finish() }

        val mine = Catalog.apps.filter {
            Installer.installedVersion(this, it.pkg) >= 0L
        }
        val adapter = AppAdapter { app ->
            startActivity(
                Intent(this, DetailActivity::class.java)
                    .putExtra(DetailActivity.EXTRA_PKG, app.pkg)
            )
        }
        binding.libList.layoutManager = LinearLayoutManager(this)
        binding.libList.adapter = adapter
        adapter.submit(mine)
        binding.libEmpty.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE

        val history = History.all(this)
        if (history.isEmpty()) {
            binding.libHistory.addView(dimText("No downloads yet."))
        } else {
            val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
            for (e in history) {
                binding.libHistory.addView(
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
}
