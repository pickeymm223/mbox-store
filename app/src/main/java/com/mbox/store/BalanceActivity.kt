package com.mbox.store

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivityBalanceBinding
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Pre-funded USDT balance: shows the balance, the deposit name
 * (mboxstoreapp.ton — the raw address is never displayed, only copied)
 * and the account's own deposit memo. While this screen is open it polls
 * the balance endpoint, which is also what makes the server credit fresh
 * deposits — so a deposit appears here on its own.
 */
class BalanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBalanceBinding
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var address = ""
    private var memo = ""
    private var shownUsdt = -1.0
    private var shownStars = -1

    private val tick = object : Runnable {
        override fun run() {
            if (!polling) return
            load()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.balBack.setOnClickListener { finish() }
        binding.balCopyAddress.setOnClickListener { copy("Address", address) }
        binding.balCopyMemo.setOnClickListener { copy("Memo", memo) }
        binding.balTopupWallet.setOnClickListener { openWallet() }
        binding.balTopupStars.setOnClickListener { promptTopUpStars() }
        shownUsdt = PayCache.balanceUsdt(this)
        binding.balAmount.text = "₮ " + trimNum(shownUsdt) + " USDT"
        shownStars = PayCache.balanceStars(this)
        binding.balStarsAmount.text = Ui.starText(this, "$shownStars Stars", 24)
    }

    override fun onResume() {
        super.onResume()
        polling = true
        handler.postDelayed(tick, 300)
    }

    override fun onPause() {
        super.onPause()
        polling = false
        handler.removeCallbacks(tick)
    }

    private fun load() {
        thread {
            val bal = try { Pay.balance(this) } catch (e: Exception) { null }
                ?: return@thread
            runOnUiThread {
                address = bal.address
                memo = bal.depositMemo
                binding.balMemo.text = bal.depositMemo
                if (shownUsdt >= 0 && bal.usdt > shownUsdt) {
                    binding.balStatus.text =
                        "Deposit received — your balance is ready to spend."
                    Toast.makeText(
                        this, "Deposit received — thank you!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                shownUsdt = bal.usdt
                binding.balAmount.text = "₮ " + trimNum(bal.usdt) + " USDT"
                if (shownStars >= 0 && bal.stars > shownStars) {
                    binding.balStatus.text =
                        "Stars top-up received — your balance is ready to spend."
                    Toast.makeText(
                        this, "Stars top-up received — thank you!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                shownStars = bal.stars
                binding.balStarsAmount.text =
                    Ui.starText(this, "${bal.stars} Stars", 24)
            }
        }
    }

    /** Stars top-up: pick an amount, then Telegram's payment sheet
     *  opens via the invoice link; this screen's polling shows the
     *  credit as soon as the payment lands. */
    private fun promptTopUpStars() {
        val presets = intArrayOf(50, 100, 250, 500)
        val labels = ArrayList<CharSequence>()
        for (p in presets) labels.add(Ui.starText(this, "$p Stars", 18))
        labels.add("Custom amount…")
        AlertDialog.Builder(this)
            .setTitle("Top up Stars balance")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) promptCustomTopUp()
                else topUpStars(presets[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomTopUp() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Amount in Stars (min 1)"
        val wrap = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)
        input.layoutParams = lp
        wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Custom amount")
            .setView(wrap)
            .setPositiveButton("Continue") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v < 1) {
                    Toast.makeText(
                        this, "Enter a valid Stars amount (min 1).",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    topUpStars(v)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun topUpStars(amount: Int) {
        Toast.makeText(this, "Preparing your top-up…", Toast.LENGTH_SHORT).show()
        thread {
            val link = try { Pay.topUpStars(this, amount) } catch (e: Exception) { null }
            runOnUiThread {
                if (link == null) {
                    Toast.makeText(
                        this, "Could not prepare the top-up. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Could not open Telegram — open @mboxstoreappbot to top up.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Open the wallet's USDT send screen with the deposit memo filled
     *  in; the user picks the amount there. */
    private fun openWallet() {
        if (address.isEmpty() || memo.isEmpty()) {
            Toast.makeText(
                this, "Still loading your deposit details — try again in a moment.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val params = "jetton=${UsdtPayActivity.USDT_MASTER}&text=${Uri.encode(memo)}"
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("ton://transfer/$address?$params"))
            )
            return
        } catch (e: ActivityNotFoundException) {
            // No wallet registered for the ton:// scheme — fall through.
        }
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://app.tonkeeper.com/transfer/$address?$params")
                )
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No wallet app found — copy the address and memo below instead.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun copy(label: String, value: String) {
        if (value.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v)
}
