package com.mbox.store

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mbox.store.databinding.ActivitySettingsBinding
import kotlin.concurrent.thread

/** Settings: Telegram connection, alerts, download preferences, About. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setBack.setOnClickListener { finish() }
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val box = binding.setBox
        box.removeAllViews()
        val linked = Account.isLinked(this)
        val user = Account.user(this)

        box.addView(Ui.sectionLabel(this, "GENERAL"))
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(
                    this, R.drawable.ic_row_bell, "#FFC94D",
                    "Update alerts in Telegram",
                    sub = if (linked) {
                        "Get a bot message when your apps update"
                    } else {
                        "Connect Telegram in Profile first"
                    },
                    checked = Account.alerts(this),
                    enabled = linked
                ) { on -> thread { Account.setAlerts(this, on) } },
                Ui.row(
                    this, R.drawable.ic_row_plane, "#2AABEE",
                    "Telegram connection",
                    sub = when {
                        !linked -> "Not connected"
                        !user?.username.isNullOrEmpty() -> "@${user?.username}"
                        else -> user?.name
                    }
                ) { onConnectionRow() }
            )
        )

        box.addView(Ui.sectionLabel(this, "NETWORK PREFERENCES"))
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(
                    this, R.drawable.ic_row_download, "#A5D6A7",
                    "Download on Wi-Fi only",
                    sub = "Downloads wait while you're on mobile data",
                    checked = Prefs.wifiOnly(this)
                ) { on -> Prefs.setWifiOnly(this, on) }
            )
        )

        box.addView(Ui.sectionLabel(this, "DOWNLOADS"))
        box.addView(
            Ui.group(
                this,
                Ui.switchRow(
                    this, R.drawable.ic_row_card, "#6EC1E4",
                    "Ask before installing",
                    sub = "After a download, install only when you tap",
                    checked = Prefs.askBeforeInstall(this)
                ) { on -> Prefs.setAskBeforeInstall(this, on) }
            )
        )

        box.addView(Ui.sectionLabel(this, "ABOUT"))
        val self = Catalog.find(packageName)
        val installed = Installer.installedVersion(this, packageName)
        val update = self != null && self.versionCode > installed
        box.addView(
            Ui.group(
                this,
                Ui.row(
                    this, R.drawable.ic_row_info, "#6EC1E4",
                    "About MBOX Store", chevron = true
                ) { InfoActivity.open(this, "About MBOX Store", Texts.ABOUT) },
                Ui.row(
                    this, R.drawable.ic_row_grid, "#2EC4B6",
                    "MBOX Store version",
                    sub = "Version ${BuildConfig.VERSION_NAME}",
                    chevron = false
                ),
                Ui.row(
                    this, R.drawable.ic_row_download, "#A5D6A7",
                    "Update MBOX Store",
                    sub = if (update) {
                        "Version ${self?.versionName} is available"
                    } else {
                        "You're up to date"
                    }
                ) {
                    if (update && self != null) {
                        startActivity(
                            Intent(this, DetailActivity::class.java)
                                .putExtra(DetailActivity.EXTRA_PKG, self.pkg)
                        )
                    }
                },
                Ui.row(
                    this, R.drawable.ic_row_shield, "#7FD1AE",
                    "Signature verification", chevron = true
                ) { InfoActivity.open(this, "App verification", Texts.VERIFICATION) },
                Ui.row(
                    this, R.drawable.ic_row_folder, "#B39DDB",
                    "Open source licenses", chevron = true
                ) { InfoActivity.open(this, "Open source licenses", Texts.LICENSES) },
                Ui.row(
                    this, R.drawable.ic_row_info, "#6EC1E4",
                    "Privacy Policy", chevron = true
                ) { InfoActivity.open(this, "Privacy Policy", Texts.PRIVACY) },
                Ui.row(
                    this, R.drawable.ic_row_info, "#6EC1E4",
                    "Terms of Service", chevron = true
                ) { InfoActivity.open(this, "Terms of Service", Texts.TERMS) }
            )
        )
    }

    private fun onConnectionRow() {
        if (!Account.isLinked(this)) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, "profile")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            return
        }
        val user = Account.user(this)
        val who = when {
            !user?.username.isNullOrEmpty() -> "@${user?.username}"
            else -> user?.name ?: "your Telegram account"
        }
        AlertDialog.Builder(this)
            .setTitle(user?.name ?: "Telegram")
            .setMessage(
                "Connected as $who.\n\n" +
                    "Disconnecting removes the link on this device and on " +
                    "the server. Purchases stay recorded to your Telegram " +
                    "account."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                thread {
                    Account.unlink(this)
                    runOnUiThread {
                        Toast.makeText(
                            this, "Disconnected from Telegram",
                            Toast.LENGTH_SHORT
                        ).show()
                        build()
                    }
                }
            }
            .show()
    }
}
