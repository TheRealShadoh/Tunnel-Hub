# Shadoh Hub

Android wrapper app with three tabs — **Chat**, **Hermes**, **Spark** — each loading its
`*.shadoh.net` site through your Cloudflare tunnel.

## What it does

- Bottom tabs for `chat.shadoh.net`, `hermes.shadoh.net`, `spark.shadoh.net`. Each tab keeps
  its own WebView alive, so switching tabs preserves scroll position, drafts and state.
- Cloudflare Access login works in-app: the redirect through `shadoh.cloudflareaccess.com`
  stays inside the WebView and the resulting `CF_Authorization` cookie is persisted, so you
  only re-authenticate when Cloudflare's session expires.
- The `; wv` marker is stripped from the User-Agent so Google sign-in (if that's your Access
  identity provider) doesn't reject the embedded browser. If it still does, use the One-time
  PIN login method in Access instead.
- Per-site **Desktop site** toggle (overflow menu) for any page that isn't mobile-friendly.
- File uploads, mic permission (for voice input in Open WebUI), dark-mode follow, deep links
  (`https://hermes.shadoh.net/...` tapped elsewhere opens in the app).
- Back button walks WebView history, then returns to the first tab, then exits.
- **Clear login cookies** in the overflow menu if you need to force a fresh Access login.

## Build options

### A. GitHub Actions (no Android Studio needed)
1. Push this folder to a GitHub repo.
2. The **Build APK** workflow runs on push (or trigger it from the Actions tab).
3. Download the `ShadohHub-debug` artifact, unzip, copy `app-debug.apk` to your phone,
   and install it (allow "install unknown apps" for your file manager/browser).

### B. Android Studio
Open the folder, let Gradle sync, **Run** on your connected phone (or Build → Build APK).

### C. Command line
```
gradle :app:assembleDebug      # needs Android SDK, JDK 17 and Gradle 8.9+ (run `gradle wrapper` once to get ./gradlew)
```
APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Customising
- Sites/tabs: edit `app/src/main/java/net/shadoh/hub/Site.kt` and `res/menu/bottom_nav.xml`.
- Hosts allowed to stay in-app (vs. opening the external browser): `Sites.isInternalHost`.
- Name/icon: `res/values/strings.xml`, `res/drawable/ic_launcher_foreground.xml`.

## Notes
- The debug APK is unsigned-for-store but fine to sideload. For a release build, add a
  signing config in `app/build.gradle.kts`.
- If a site shows a blank page after login, check that it doesn't send
  `X-Frame-Options`/CSP headers that block non-browser UAs — the WebView UA mirrors Chrome.
