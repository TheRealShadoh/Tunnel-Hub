# Tunnel Hub

A small Android app that turns your self-hosted web UIs — the ones you publish through a
Cloudflare Tunnel behind Cloudflare Access — into tabs in one native-feeling app, with the
Access login handled inside the app and in-app updates from GitHub Releases.

Typical use: an Open WebUI instance, a dashboard and an agent UI, each on its own subdomain,
all one tap away on your phone without a browser bar in the way.

## Install

**[⬇ Download the latest APK](https://github.com/TheRealShadoh/tunnel-hub/releases/latest/download/TunnelHub.apk)**

Open the file on your phone and allow installs from your browser if prompted.
All builds: [Releases](https://github.com/TheRealShadoh/tunnel-hub/releases).

On first launch you'll get a setup screen: add a name and URL for each tab, save. You can change
them later from the overflow menu (**Edit tabs…**).

## What it does

- **One tab per site**, each keeping its own WebView alive so switching tabs preserves scroll
  position, drafts and state. Tapping the active tab scrolls to top / goes home.
- **Cloudflare Access login inside the app.** The bounce through `*.cloudflareaccess.com`
  (and common identity providers: Google, GitHub, Microsoft, Okta, Auth0) is shown as a
  full-screen **Sign in** view with the tabs hidden; once Access redirects back to your site the
  tabs appear. Same thing kicks in automatically when a session expires. The auth cookie is
  persisted, so you only re-authenticate when Cloudflare says so.
- The `; wv` marker is stripped from the User-Agent so Google sign-in doesn't refuse the
  embedded browser. If it still does, use Access's One-time PIN method.
- **Mobile fit**: pages with no viewport meta get one injected, zoom-to-fit and pinch-zoom are
  on. A per-site **Desktop site** toggle is in the overflow menu for pages that need it, and each
  tab has an optional **Custom CSS** box (setup screen → *Custom CSS*) for patching layouts that
  were never built for phones — e.g. `.sidebar{display:none}`.
- **In-app updates**: overflow → **Check for updates** (also checked quietly on launch, at most
  every 6 h). Downloads the APK from the latest GitHub Release and hands it to Android's
  installer. The release repo must be public (no token is stored in the app).
- File uploads, microphone permission (voice input), dark-mode follow, external links open in
  your browser, back button walks history then returns to the first tab.
- **Clear login cookies** in the overflow menu forces a fresh Access login.

## Build it yourself

### GitHub Actions (no Android Studio needed)
Fork, then push to `main`. The **Build APK** workflow builds a debug APK, uploads it as a
workflow artifact and publishes a GitHub Release tagged `build-<run number>` with
`TunnelHub.apk` attached. The app's `versionCode` is that run number, which is how the updater
knows what's newer.

**Signing (so updates install over the previous build).** Without a stable key each CI run
signs with a throwaway debug key and Android refuses the upgrade. Generate a keystore once:

```
keytool -genkeypair -keystore tunnel-hub.jks -alias tunnelhub -keyalg RSA -keysize 2048 -validity 10000
```

and add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | `base64 -w0 tunnel-hub.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_PASSWORD` | key password |
| `SIGNING_KEY_ALIAS` | `tunnelhub` (or whatever you used) |

### Android Studio / command line
Open the folder and run on a device, or `gradle :app:assembleDebug` (JDK 17, Android SDK,
Gradle 8.9+). The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Pointing a fork's updater at itself
`Updater.REPO` in `app/src/main/java/dev/tunnelhub/app/Updater.kt` names the repo whose Releases
the app checks. Change it to `you/your-fork`.

## Privacy / security notes
- Nothing leaves the phone except requests to the sites you configure and to `api.github.com`
  for update checks. There is no analytics or telemetry.
- Only `https://` is used; cleartext traffic is disabled. Mixed content is blocked.
- Downloaded update APKs are served to the installer through a `FileProvider` over the app's
  private cache and deleted on the next update.

## License
MIT
