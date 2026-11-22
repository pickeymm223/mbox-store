package com.mbox.store

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityPurchasesBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Purchases: everything the signed-in Telegram account owns — paid apps,
 * in-app purchases and support sent — restored from the server.
 */
class PurchasesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPurchasesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPurchasesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.purBack.setOnClickListener { finish() }
        render()
        if (Account.isLinked(this)) {
            thread {
                PayCache.refresh(this)
                val orders = try { Pay.orders(this) } catch (e: Exception) { emptyList() }
                runOnUiThread { render(orders) }
            }
        }
    }

    private fun render(orders: List<Pay.Order> = emptyList()) {
        val owned = PayCache.owned(this)
        val ownedApps = owned.filter { !it.contains(":") }
        val iaps = owned.filter { it.contains(":") }
        val support = orders.filter { it.kind == "support" && it.status == "paid" }

        binding.payEmpty.visibility =
            if (owned.isEmpty() && support.isEmpty()) View.VISIBLE else View.GONE

        binding.ownedTitle.visibility = if (ownedApps.isNotEmpty()) View.VISIBLE else View.GONE
        binding.ownedBox.removeAllViews()
        for (key in ownedApps) {
            val app = Catalog.find(key)
            binding.ownedBox.addView(appRow(
                iconUrl = app?.iconUrl ?: "",
                title = app?.name ?: key,
                sub = "Owned",
                chip = "GET"
            ) {
                startActivity(
                    Intent(this, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_PKG, key)
                )
            })
        }

        binding.iapTitle.visibility = if (iaps.isNotEmpty()) View.VISIBLE else View.GONE
        binding.iapBox.removeAllViews()
        for (key in iaps) {
            val pkg = key.substringBefore(":")
            val pid = key.substringAfter(":")
            val app = Catalog.find(pkg)
            val prod = app?.iap?.firstOrNull { it.id == pid }
            binding.iapBox.addView(appRow(
                iconUrl = app?.iconUrl ?: "",
                title = prod?.label ?: pid,
                sub = (app?.name ?: pkg) + " · In-app purchase",
                chip = null
            ) {
                startActivity(
                    Intent(this, DetailActivity::class.java)
                        .putExtra(DetailActivity.EXTRA_PKG, pkg)
                )
            })
        }

        binding.supportTitle.visibility = if (support.isNotEmpty()) View.VISIBLE else View.GONE
        binding.supportBox.removeAllViews()
        val fmt = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
        for (o in support) {
            val app = Catalog.find(o.item)
            binding.supportBox.addView(appRow(
                iconUrl = app?.iconUrl ?: "",
                title = o.label,
                sub = fmt.format(Date(if (o.paidAt > 0) o.paidAt else o.created)) +
                    " · " + amountText(o),
                chip = null
            ) { })
        }
    }

    private fun amountText(o: Pay.Order): String = when {
        o.amountStars > 0 -> "⭐ ${o.amountStars}"
        o.amountUsdt > 0 -> "₮ " + trimNum(o.amountUsdt)
        else -> ""
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)

    private fun appRow(
        iconUrl: String,
        title: String,
        sub: String,
        chip: String?,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        val iv = ImageView(this)
        val ivLp = LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46))
        iv.layoutParams = ivLp
        if (iconUrl.isNotEmpty()) IconLoader.load(iconUrl, iv)
        else iv.setImageResource(R.drawable.ic_app_placeholder)
        row.addView(iv)
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val tLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.weight = 1f
        tLp.marginStart = Ui.dp(this, 12)
        texts.layoutParams = tLp
        val t = TextView(this)
        t.text = title
        t.textSize = 15f
        t.setTextColor(getColor(R.color.text_primary))
        texts.addView(t)
        val s = TextView(this)
        s.text = sub
        s.textSize = 12.5f
        s.setTextColor(getColor(R.color.text_secondary))
        texts.addView(s)
        row.addView(texts)
        if (chip != null) {
            val c = TextView(this)
            c.text = chip
            c.textSize = 13f
            c.setTextColor(getColor(R.color.accent))
            c.setTypeface(c.typeface, android.graphics.Typeface.BOLD)
            c.setBackgroundResource(R.drawable.bg_pill)
            c.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7))
            row.addView(c)
        }
        row.setOnClickListener { onClick() }
        return row
    }
}
