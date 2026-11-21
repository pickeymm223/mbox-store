package com.mbox.store

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

object Installer {

    /** Installed versionCode of [pkg], or -1 when not installed. */
    fun installedVersion(context: Context, pkg: String): Long {
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }

    enum class State { GET, UPDATE, OPEN }

    fun stateFor(context: Context, app: StoreApp): State {
        val installed = installedVersion(context, app.pkg)
        return when {
            installed < 0L -> State.GET
            installed < app.versionCode -> State.UPDATE
            else -> State.OPEN
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun openApp(context: Context, pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** SHA-256 of the APK file's signing certificate, "AB:CD:…" form, or null. */
    fun apkCertSha256(context: Context, apk: File): String? {
        val pm = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            val info = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return null
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo ?: return null
                val signers = if (si.hasMultipleSigners()) si.apkContentsSigners
                else si.signingCertificateHistory
                signers?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            certBytes?.let { digest ->
                val md = MessageDigest.getInstance("SHA-256").digest(digest)
                md.joinToString(":") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Package name declared inside an APK file, or null when unreadable. */
    fun apkPackageName(context: Context, apk: File): String? {
        return try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
        } catch (e: Exception) {
            null
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context, "com.mbox.store.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB", mb)
        else String.format("%.0f KB", bytes / 1024.0)
    }
}
