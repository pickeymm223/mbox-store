# MBOX Store

An independent Android app store — browse, download and install apps directly
from developers, with signature verification before every install.

- App name: **MBOX Store**
- Package: `com.mbox.store`
- Language: Kotlin, minSdk 26 (Android 8.0), targetSdk 34, compileSdk 34
- Catalog: served by the `mbox-store-api` Cloudflare Worker (APK files in R2)
- Distribution: direct APK (sideload), NOT Google Play

## How it works

1. The app fetches `catalog.json` from the store API.
2. Each catalog entry carries the APK URL and the SHA-256 of the developer's
   signing certificate.
3. On Install, the APK is downloaded with progress, its package name and
   signing certificate are verified against the catalog, and only then is the
   system installer opened.
4. Installed apps show OPEN; older installs show UPDATE.

## Build

CI (GitHub Actions) builds a debug APK on every push to `main` and publishes
it to a GitHub Release. For local builds: JDK 17 + Android SDK 34 +
Gradle 8.7, `gradle assembleDebug`.
