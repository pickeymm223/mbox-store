package com.mbox.store

import android.content.Context

/** Simple on-device settings for the store. */
object Prefs {
    private const val FILE = "settings"

    fun wifiOnly(context: Context): Boolean =
        prefs(context).getBoolean("wifi_only", false)

    fun setWifiOnly(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("wifi_only", on).apply()

    fun askBeforeInstall(context: Context): Boolean =
        prefs(context).getBoolean("ask_before_install", false)

    fun setAskBeforeInstall(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("ask_before_install", on).apply()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
