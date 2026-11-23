package com.mbox.store

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityPayHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Payment history: every MBOX Pay order on this account, newest first. */
class PayHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPayHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPayHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.histBack.setOnClickListener { finish() }
        if (!Account.isLinked(this)) {
            binding.historyEmpty.visibility = View.VISIBLE
            binding.historyEmpty.text =
                "Connect Telegram in Profile to see your payments."
            return
        }
        thread {
            val orders = try { Pay.orders(this) } catch (e: Exception) { emptyList() }
            runOnUiThread { render(orders) }
        }
    }

    private fun render(orders: List<Pay.Order>) {
        binding.historyEmpty.visibility =
            if (orders.isEmpty()) View.VISIBLE else View.GONE
        binding.historyBox.removeAllViews()
        val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
        for (o in orders) {
            binding.historyBox.addView(row(o, fmt))
        }
    }

    private fun row(o: Pay.Order, fmt: SimpleDateFormat): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val tLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        tLp.weight = 1f
        texts.layoutParams = tLp
        val t = TextView(this)
        t.text = o.label
        t.textSize = 15f
        t.setTextColor(getColor(R.color.text_primary))
        texts.addView(t)
        val s = TextView(this)
        s.text = SpannableStringBuilder(fmt.format(Date(o.created)))
            .append(" · ").append(amountText(o))
        s.textSize = 12.5f
        s.setTextColor(getColor(R.color.text_secondary))
        texts.addView(s)
        row.addView(texts)
        val pill = TextView(this)
        pill.text = when (o.status) {
            "paid" -> "Paid"
            "expired" -> "Expired"
            else -> "Pending"
        }
        pill.textSize = 12f
        pill.setTextColor(
            getColor(if (o.status == "paid") R.color.accent else R.color.pill_text)
        )
        pill.setTypeface(pill.typeface, android.graphics.Typeface.BOLD)
        pill.setBackgroundResource(R.drawable.bg_pill)
        pill.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5))
        row.addView(pill)
        return row
    }

    private fun amountText(o: Pay.Order): CharSequence = when (o.rail) {
        "usdt" -> "₮ " + trimNum(o.amountUsdt) + " USDT"
        else -> Ui.starText(this, o.amountStars.toString(), 13)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)
}
