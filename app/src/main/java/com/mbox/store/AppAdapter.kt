package com.mbox.store

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Play Store-style app row: icon, name, genre, size pill + version.
 * The whole row opens the app page — no buttons on the row itself.
 */
class AppAdapter(
    private val onOpen: (StoreApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    private var items: List<StoreApp> = emptyList()

    fun submit(list: List<StoreApp>) {
        items = list
        notifyDataSetChanged()
    }

    fun refresh() = notifyDataSetChanged()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return Holder(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        holder.name.text = app.name
        holder.genre.text = app.genre
        holder.sizePill.text = Installer.formatSize(app.sizeBytes)
        holder.version.text = "v${app.versionName}"
        IconLoader.load(app.iconUrl, holder.icon)
        holder.itemView.setOnClickListener { onOpen(app) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val genre: TextView = v.findViewById(R.id.genre)
        val sizePill: TextView = v.findViewById(R.id.sizePill)
        val version: TextView = v.findViewById(R.id.version)
    }
}
