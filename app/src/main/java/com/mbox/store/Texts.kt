package com.mbox.store

/** Long-form texts shown by InfoActivity (About / legal / explainer pages). */
object Texts {

    const val ABOUT = """MBOX Store

An app store for Myanmar: essential apps from their developers' official sources, every one signature-verified before install.

Free apps download with no account. Connect Telegram (optional) to sync purchases, receive update alerts, and pay with Telegram Stars.

Channel: @mboxstoreapp
Bot: @mboxstoreappbot"""

    const val VERIFICATION = """How verification works

Every app in MBOX Store comes from its developer's official source — the developer's own website, official GitHub release, or official download page.

Before an app is listed, we record the developer's signing certificate fingerprint (SHA-256). Android apps can only be updated by the same key that signed them, so this fingerprint identifies the real developer.

When you install or update an app from MBOX Store, the app checks the downloaded file's certificate against the fingerprint on record before opening the installer. If it doesn't match, the install is cancelled and the file is deleted.

You can see each app's certificate fingerprint on its store page."""

    const val PRIVACY = """MBOX Store — Privacy Policy
Last updated: 22 November 2026

MBOX Store is an Android app store that distributes signature-verified apps from official sources. This policy explains what information the Store handles and why.

1. No account needed to browse or download
You can browse the catalog and download free apps without creating an account and without giving us your name, phone number, or email address.

2. Information processed on your device
Installed apps check: to show you which of your apps were installed from MBOX Store and whether updates are available, the app reads the list of installed packages on your device and compares versions on the device. Your installed-app list is not uploaded to our servers.

Download history is stored only on your device. Deleting the app's data removes it.

3. Server logs
The catalog and app files are served through Cloudflare. As with any website, standard technical logs (such as IP address and request time) may be processed by Cloudflare to deliver files and prevent abuse. We do not use these logs to identify or profile individual users, and the app contains no advertising or analytics trackers.

4. Telegram connection (optional)
If you choose "Connect Telegram", we receive your Telegram user ID, display name, username, and profile photo from Telegram. We use this only to link your purchases and supported-developer records to you, let you re-download paid apps you own, and send update alerts through our Telegram bot if you turn them on.

You can disconnect at any time in Profile → Settings → Telegram connection. Disconnecting removes the link between your Telegram account and your Store records.

5. Payments with Telegram Stars
Paid apps and developer support are paid with Telegram Stars inside Telegram. We never receive or store your bank card or wallet details. We keep a purchase record — Telegram user ID, the app, the amount in Stars, and the date — so we can prove ownership, restore purchases, and account to developers for support payments.

6. Sharing
We do not sell personal information. Information is shared only with the service providers needed to operate the Store (currently Cloudflare, for hosting and file delivery), or when required by law.

7. Your choices and deletion
Browse and download free apps anonymously — no account is required. Disconnect Telegram at any time. To delete purchase records linked to your Telegram account, contact us (Section 9). Some records may need to be kept for accounting where the law requires it.

8. Children
The Store is a general-audience service. We do not knowingly collect personal information from children beyond what is described above.

9. Contact
Questions about this policy: Telegram @mboxstoreappbot · Channel @mboxstoreapp

10. Changes
If this policy changes, the new version will be published in the app with a new "last updated" date before it takes effect."""

    const val TERMS = """MBOX Store — Terms of Service (summary)
Last updated: 22 November 2026

• MBOX Store distributes apps obtained from developers' official sources. Each app remains the property of its developer and is subject to that developer's own terms.

• Every listed app is signature-verified before install. Verification confirms the file came from the recorded developer; it is not a guarantee that an app is free of defects.

• Free apps can be downloaded without an account. Paid apps and developer support are paid with Telegram Stars through Telegram; a completed Stars payment is recorded against your Telegram account so your purchases can be restored.

• Support payments are voluntary contributions to a developer, minus the costs of operating the Store, and are accounted to the developer.

• We may remove an app from the catalog at any time, for example if its official source disappears or its signature changes.

• Full terms will be published before paid apps launch."""

    const val LICENSES = """Open source licenses

MBOX Store is built with open source software, including:

• Kotlin standard library — Apache License 2.0
• AndroidX (Core, AppCompat, RecyclerView) — Apache License 2.0
• Material Components for Android — Apache License 2.0

The full text of the Apache License 2.0 is available at apache.org/licenses/LICENSE-2.0"""

    const val PURCHASES = """Payments & purchases

Paid apps and developer support are paid with Telegram Stars — inside Telegram, so no card details ever reach MBOX Store.

Nothing here yet. When paid apps launch, everything you buy will be listed on this page, tied to your Telegram account, and re-downloadable at any time."""

    const val SUPPORT = """Support sent

When you support a developer with Telegram Stars, it is recorded here and accounted to that developer.

Nothing here yet — you haven't sent any support payments."""
}
