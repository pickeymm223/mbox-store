package com.mbox.store

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MBOX Pay client — orders, entitlements and payment history against the
 * store API. Every call needs the Telegram link token (purchases live on
 * the Telegram account). All calls are blocking — call off the UI thread.
 */
object Pay {

    data class Order(
        val id: String,
        val kind: String,
        val item: String,
        val productId: String,
        val label: String,
        val rail: String,
        val amountStars: Int,
        val amountUsdt: Double,
        val memo: String,
        val status: String,
        val created: Long,
        val paidAt: Long
    )

    data class Created(
        val order: Order,
        val address: String,
        val invoiceSent: Boolean,
        val invoiceLink: String
    )

    fun parseOrder(o: JSONObject): Order = Order(
        id = o.optString("id"),
        kind = o.optString("kind"),
        item = o.optString("item"),
        productId = o.optString("productId"),
        label = o.optString("label"),
        rail = o.optString("rail"),
        amountStars = o.optInt("amountStars", 0),
        amountUsdt = o.optDouble("amountUsdt", 0.0),
        memo = o.optString("memo"),
        status = o.optString("status"),
        created = o.optLong("created", 0L),
        paidAt = o.optLong("paidAt", 0L)
    )

    /** POST /pay/order — prices for goods come from the catalog server-side. */
    fun createOrder(
        context: Context,
        kind: String,
        item: String,
        productId: String?,
        rail: String,
        amountStars: Int = 0,
        amountUsdt: Double = 0.0,
        balanceCurrency: String? = null
    ): Created {
        val body = JSONObject()
            .put("linkToken", Account.token(context))
            .put("kind", kind)
            .put("item", item)
            .put("rail", rail)
        if (productId != null) body.put("productId", productId)
        if (balanceCurrency != null) body.put("balanceCurrency", balanceCurrency)
        if (kind == "support") {
            body.put("amountStars", amountStars)
            body.put("amountUsdt", amountUsdt)
        }
        val j = request("POST", "/pay/order", body)
        return Created(
            order = parseOrder(j.getJSONObject("order")),
            address = j.optString("address"),
            invoiceSent = j.optBoolean("invoiceSent", false),
            invoiceLink = j.optString("invoiceLink")
        )
    }

    /** GET /pay/status — pending USDT orders verify against the chain. */
    fun status(context: Context, orderId: String): Order? {
        val t = Account.token(context)
        if (t.isEmpty()) return null
        val j = request(
            "GET",
            "/pay/status?token=" + URLEncoder.encode(t, "UTF-8") +
                "&id=" + URLEncoder.encode(orderId, "UTF-8"),
            null
        )
        return parseOrder(j.getJSONObject("order"))
    }

    data class Balance(
        val usdt: Double,
        val stars: Int,
        val depositMemo: String,
        val address: String
    )

    /** GET /pay/balance — the read also makes the server credit any
     *  fresh deposits carrying this account's deposit memo. */
    fun balance(context: Context): Balance {
        val t = Account.token(context)
        val j = request(
            "GET",
            "/pay/balance?token=" + URLEncoder.encode(t, "UTF-8"),
            null
        )
        return Balance(
            usdt = j.optDouble("balanceUsdt", 0.0),
            stars = j.optInt("balanceStars", 0),
            depositMemo = j.optString("depositMemo"),
            address = j.optString("address")
        )
    }

    /** POST /pay/topup/stars — returns a Telegram invoice link; paying
     *  it credits the in-app Stars balance (settled by the bot webhook). */
    fun topUpStars(context: Context, amountStars: Int): String {
        val j = request(
            "POST", "/pay/topup/stars",
            JSONObject()
                .put("linkToken", Account.token(context))
                .put("amountStars", amountStars)
        )
        val link = j.optString("invoiceLink")
        if (!j.optBoolean("ok", false) || link.isEmpty()) {
            throw IllegalStateException("top-up link failed")
        }
        return link
    }

    /** GET /pay/entitlements — item keys this account owns. */
    fun entitlements(context: Context): Set<String> {
        val t = Account.token(context)
        if (t.isEmpty()) return emptySet()
        val j = request(
            "GET",
            "/pay/entitlements?token=" + URLEncoder.encode(t, "UTF-8"),
            null
        )
        val arr = j.optJSONArray("items") ?: return emptySet()
        val out = HashSet<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    /** GET /pay/orders — newest first. */
    fun orders(context: Context): List<Order> {
        val t = Account.token(context)
        if (t.isEmpty()) return emptyList()
        val j = request(
            "GET",
            "/pay/orders?token=" + URLEncoder.encode(t, "UTF-8"),
            null
        )
        val arr = j.optJSONArray("orders") ?: return emptyList()
        val out = ArrayList<Order>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(parseOrder(it)) }
        }
        return out
    }

    /** POST /pay/payout-address — a developer's TON wallet for payouts. */
    fun setPayoutAddress(context: Context, address: String): Boolean {
        val j = request(
            "POST", "/pay/payout-address",
            JSONObject()
                .put("linkToken", Account.token(context))
                .put("address", address)
        )
        return j.optBoolean("ok", false)
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(Account.API_BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        conn.setRequestProperty("User-Agent", "MBOXStore-Android")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return JSONObject(text)
    }
}

/**
 * On-device cache of what the signed-in account owns, so Detail and
 * Profile can render instantly and re-check in the background.
 */
object PayCache {

    private const val PREFS = "paycache"

    fun owned(context: Context): Set<String> =
        prefs(context).getStringSet("owned", emptySet()) ?: emptySet()

    fun owns(context: Context, key: String): Boolean = owned(context).contains(key)

    fun ownedApps(context: Context): List<String> =
        owned(context).filter { !it.contains(":") }

    fun supportStars(context: Context): Int =
        prefs(context).getInt("support_stars", 0)

    fun supportUsdt(context: Context): Double =
        prefs(context).getString("support_usdt", "0")?.toDoubleOrNull() ?: 0.0

    fun balanceUsdt(context: Context): Double =
        prefs(context).getString("balance_usdt", "0")?.toDoubleOrNull() ?: 0.0

    fun balanceStars(context: Context): Int =
        prefs(context).getInt("balance_stars", 0)

    fun payoutAddress(context: Context): String =
        prefs(context).getString("payout_address", "").orEmpty()

    fun savePayoutAddress(context: Context, address: String) {
        prefs(context).edit().putString("payout_address", address).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Blocking refresh from the server — call from a background thread. */
    fun refresh(context: Context) {
        if (!Account.isLinked(context)) return
        try {
            val owned = Pay.entitlements(context)
            val orders = Pay.orders(context)
            var sStars = 0
            var sUsdt = 0.0
            for (o in orders) {
                if (o.kind == "support" && o.status == "paid") {
                    sStars += o.amountStars
                    sUsdt += o.amountUsdt
                }
            }
            prefs(context).edit()
                .putStringSet("owned", owned)
                .putInt("support_stars", sStars)
                .putString("support_usdt", sUsdt.toString())
                .apply()
            try {
                val bal = Pay.balance(context)
                prefs(context).edit()
                    .putString("balance_usdt", bal.usdt.toString())
                    .putInt("balance_stars", bal.stars)
                    .apply()
            } catch (e: Exception) {
                // Balance cache stays stale; the next refresh retries.
            }
        } catch (e: Exception) {
            // Keep the last known cache; the next refresh retries.
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
