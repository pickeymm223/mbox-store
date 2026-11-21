package com.mbox.store

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** App row used by the Home and Updates lists. */
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
        val size = Installer.formatSize(app.sizeBytes)
        holder.meta.text = listOf(app.developer, size)
            .filter { it.isNotEmpty() }.joinToString(" • ")
        IconLoader.load(app.iconUrl, holder.icon)
        holder.action.text = when (Installer.stateFor(holder.itemView.context, app)) {
            Installer.State.GET -> "GET"
            Installer.State.UPDATE -> "UPDATE"
            Installer.State.OPEN -> "OPEN"
        }
        holder.itemView.setOnClickListener { onOpen(app) }
        holder.action.setOnClickListener { onOpen(app) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val action: Button = v.findViewById(R.id.action)
    }
}
