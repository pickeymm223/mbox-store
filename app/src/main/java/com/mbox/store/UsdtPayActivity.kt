package com.mbox.store

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityUsdtPayBinding
import java.util.Locale
import kotlin.concurrent.thread

/**
 * USDT payment screen for one MBOX Pay order: shows the store wallet
 * address and the order's memo, then polls the order status until the
 * on-chain transfer is matched (the server verifies via tonapi).
 */
class UsdtPayActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ORDER = "order_id"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MEMO = "memo"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_LABEL = "label"
    }

    private lateinit var binding: ActivityUsdtPayBinding
    private val handler = Handler(Looper.getMainLooper())
    private var orderId = ""
    private var polling = false
    private var paid = false

    private val tick = object : Runnable {
        override fun run() {
            if (!polling || paid) return
            pollOnce()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsdtPayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        orderId = intent.getStringExtra(EXTRA_ORDER).orEmpty()
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val memo = intent.getStringExtra(EXTRA_MEMO).orEmpty()
        val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
        binding.usdtApp.text = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        binding.usdtAmount.text = "₮ " + trimNum(amount) + " USDT"
        binding.usdtAddress.text = address
        binding.usdtMemo.text = memo
        binding.usdtBack.setOnClickListener { finish() }
        binding.copyAddress.setOnClickListener { copy("Address", address) }
        binding.copyMemo.setOnClickListener { copy("Memo", memo) }
    }

    override fun onResume() {
        super.onResume()
        if (!paid && orderId.isNotEmpty()) {
            polling = true
            handler.postDelayed(tick, 1200)
        }
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(tick)
    }

    private fun pollOnce() {
        val id = orderId
        thread {
            val o = try { Pay.status(this, id) } catch (e: Exception) { null }
            if (o != null && o.status == "paid") {
                runOnUiThread { onPaid() }
            }
        }
    }

    private fun onPaid() {
        if (paid) return
        paid = true
        polling = false
        handler.removeCallbacks(tick)
        binding.usdtSpinner.visibility = View.GONE
        binding.usdtStatusTitle.text = "Payment received"
        binding.usdtStatusSub.text =
            "Your purchase is unlocked — go back to the app page."
        thread { PayCache.refresh(this) }
        Toast.makeText(this, "Payment received — thank you!", Toast.LENGTH_LONG).show()
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)
}
