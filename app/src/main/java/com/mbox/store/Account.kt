package com.mbox.store

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The user's Telegram link for MBOX Store, created through @mboxstoreappbot.
 * The link token issued by the store API is the only credential; it lives
 * in SharedPreferences on this device and can be revoked from the app
 * (disconnect) at any time. All API calls are blocking — call off the UI
 * thread.
 */
object Account {

    private const val PREFS = "account"
    const val API_BASE = "https://mbox-store-api.zawminhtet723.workers.dev"
    const val BOT_LINK = "https://t.me/mboxstoreappbot"

    data class User(
        val id: Long,
        val name: String,
        val username: String,
        val avatarUrl: String
    )

    sealed class Poll {
        object Pending : Poll()
        object Expired : Poll()
        data class Linked(val linkToken: String, val user: User) : Poll()
    }

    fun isLinked(context: Context): Boolean = token(context).isNotEmpty()

    fun token(context: Context): String =
        prefs(context).getString("link_token", "").orEmpty()

    fun user(context: Context): User? {
        val p = prefs(context)
        val id = p.getLong("tg_id", 0L)
        if (id == 0L) return null
        return User(
            id = id,
            name = p.getString("name", "").orEmpty(),
            username = p.getString("username", "").orEmpty(),
            avatarUrl = p.getString("avatar", "").orEmpty()
        )
    }

    fun alerts(context: Context): Boolean =
        prefs(context).getBoolean("alerts", true)

    fun save(context: Context, linkToken: String, user: User) {
        prefs(context).edit()
            .putString("link_token", linkToken)
            .putLong("tg_id", user.id)
            .putString("name", user.name)
            .putString("username", user.username)
            .putString("avatar", user.avatarUrl)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        // Purchases belong to the link; drop the on-device cache too.
        PayCache.clear(context)
    }

    /** POST /connect/create -> (code, telegram deep link). */
    fun createSession(): Pair<String, String> {
        val j = request("POST", "/connect/create", JSONObject())
        return j.getString("code") to j.getString("link")
    }

    /** GET /connect/status?code= */
    fun pollStatus(code: String): Poll {
        val j = request("GET", "/connect/status?code=" +
            URLEncoder.encode(code, "UTF-8"), null)
        return when (j.optString("status")) {
            "linked" -> Poll.Linked(
                j.getString("linkToken"),
                parseUser(j.getJSONObject("user"))
            )
            "pending" -> Poll.Pending
            else -> Poll.Expired
        }
    }

    /** Outcome of asking the server who a stored link token belongs to. */
    sealed class Me {
        data class Linked(val user: User) : Me()
        object Gone : Me()     // server answered 404: the link is really over
        object Unknown : Me()  // network/server error: no verdict either way
    }

    /**
     * GET /connect/me?token= — only an explicit 404 means the link no
     * longer exists. A failed request (offline, timeout, 5xx) must never
     * be read as a revoked link.
     */
    fun fetchMe(context: Context): Me {
        val t = token(context)
        if (t.isEmpty()) return Me.Unknown
        return try {
            val j = request("GET", "/connect/me?token=" +
                URLEncoder.encode(t, "UTF-8"), null)
            val u = parseUser(j.getJSONObject("user"))
            prefs(context).edit()
                .putBoolean("alerts", j.optBoolean("alerts", true))
                .apply()
            Me.Linked(u)
        } catch (e: HttpStatusException) {
            if (e.code == 404) Me.Gone else Me.Unknown
        } catch (e: Exception) {
            Me.Unknown
        }
    }

    /** Refresh the cached profile of the existing link (token untouched). */
    fun saveUser(context: Context, user: User) {
        prefs(context).edit()
            .putLong("tg_id", user.id)
            .putString("name", user.name)
            .putString("username", user.username)
            .putString("avatar", user.avatarUrl)
            .apply()
    }

    fun unlink(context: Context) {
        val t = token(context)
        if (t.isNotEmpty()) {
            try {
                request("POST", "/connect/unlink",
                    JSONObject().put("linkToken", t))
            } catch (e: Exception) {
                // Local clear below still ends the link on this device.
            }
        }
        clear(context)
    }

    fun setAlerts(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("alerts", on).apply()
        val t = token(context)
        if (t.isNotEmpty()) {
            try {
                request("POST", "/connect/prefs", JSONObject()
                    .put("linkToken", t)
                    .put("alerts", on))
            } catch (e: Exception) {
                // Preference is saved locally; the server copy syncs on
                // the next successful call.
            }
        }
    }

    private fun parseUser(j: JSONObject): User = User(
        id = j.optLong("id"),
        name = j.optString("name"),
        username = j.optString("username"),
        avatarUrl = j.optString("avatarUrl")
    )

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val conn = URL(API_BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "MBOXStore-Android")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpStatusException(code)
        return JSONObject(text)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    class HttpStatusException(val code: Int) : Exception("HTTP $code")
}
