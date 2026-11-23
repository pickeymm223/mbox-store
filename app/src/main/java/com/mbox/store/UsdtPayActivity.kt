package com.mbox.store

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

        // Official USDT jetton master on TON.
        const val USDT_MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs"
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
        binding.payInWallet.setOnClickListener { openWallet(address, memo, amount) }
    }

    /**
     * Hand the payment to the user's TON wallet with everything filled
     * in — recipient, USDT amount and the order memo — via the standard
     * transfer link every TON wallet understands. The wallet still asks
     * the user to confirm there (wallets never sign on their own); when
     * they come back, this screen's polling sees the transfer arrive.
     */
    private fun openWallet(address: String, memo: String, amount: Double) {
        if (address.isEmpty() || amount <= 0) return
        val units = java.math.BigDecimal.valueOf(amount)
            .movePointRight(6).toBigInteger().toString()
        val params = "jetton=$USDT_MASTER&amount=$units&text=${Uri.encode(memo)}"
        val tonLink = Intent(Intent.ACTION_VIEW, Uri.parse("ton://transfer/$address?$params"))
        try {
            startActivity(tonLink)
            return
        } catch (e: ActivityNotFoundException) {
            // No wallet registered for the ton:// scheme — fall through.
        }
        val webLink = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://app.tonkeeper.com/transfer/$address?$params")
        )
        try {
            startActivity(webLink)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No wallet app found — copy the address and memo below instead.",
                Toast.LENGTH_LONG
            ).show()
        }
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
        binding.payInWallet.visibility = View.GONE
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
