# WepWawet

> Steam Workshop browser & downloader for Android. Browse 3M+ Wallpaper Engine workshop items, log in with your own Steam account, and download wallpaper packs straight to your phone. **No PC required.**

**Status: v0.7.x-alpha — functional alpha.** Browse → login → download works end-to-end on-device. A pairing proxy is included for the official Wallpaper Engine Android app.

[![Build APK](https://github.com/687meowmeow/WE-bridge/actions/workflows/build.yml/badge.svg)](https://github.com/687meowmeow/WE-bridge/actions/workflows/build.yml)

---

## ⚠️ Vibe coded notice

This project is **vibe coded**. It was built largely with AI assistance, by prompting, testing on a phone, and prompting again.

## Why this exists

Because I'm too lazy to turn my PC on.

---

## What this is

WepWawet is an Android app (Kotlin + Jetpack Compose, min Android 8.0 / API 26, target SDK 35) that lets you browse the Steam Workshop for Wallpaper Engine (app 431960), inspect items, and download the actual wallpaper packs to your device.

Downloads run through **on-device JavaSteam**. Your Steam credentials stay on your phone and JavaSteam talks directly to Steam's auth and content servers. No PC, no server, no third-party service. Steam Guard 2FA is supported, and refresh tokens are saved so you can skip 2FA on later sessions.

The app also includes a foreground **pairing proxy** (`PairingService`) that lets the official Wallpaper Engine Android app pair with WepWawet acting as a "fake PC". This lets WE's own UI pull content served by WepWawet.

## Features

- **Browse & search**: scroll through Wallpaper Engine workshop items, sort them, search by text, and filter by tags.
- **Item details**: see previews, descriptions, and creator names for any wallpaper.
- **Steam login**: sign in with your own account, including Steam Guard / 2FA. Your login is remembered.
- **Download**: grab wallpaper packs straight to your phone, with a download manager to track progress.
- **Works with the official WE app**: converts wallpapers into a format the Android Wallpaper Engine app can use, and pairs with it as a "fake PC".
- **Logs**: built-in logging with crash capture and one-tap export for bug reports.

## Tech stack

| Area | Tech |
|------|------|
| Language / UI | Kotlin 2.2, Jetpack Compose, Navigation Compose |
| Networking | OkHttp, Retrofit, kotlinx.serialization |
| Steam access | [JavaSteam](https://github.com/Longi94/JavaSteam) via a patched fork (`com.github.687meowmeow:JavaSteam`, `1.8.0-wepwawet10`) |
| Images | Coil |
| Background work | WorkManager, foreground services |
| Native | C++ / CMake, etc2comp (ETC2 encoding) |
| Compression | lz4-java, zstd-jni, XZ |
| Build | Gradle (Kotlin DSL), AGP 8.7, GitHub Actions |

The JavaSteam fork exists because upstream skips some Wallpaper Engine workshop items (it doesn't recognize their file type), which made downloads hang forever.

## Repo layout

| Path | Purpose |
|------|---------|
| `app/` | Android app module |
| `app/src/main/java/com/wepwawet/ui/` | Compose UI: screens, components, theme, nav host |
| `app/src/main/java/com/wepwawet/data/` | Steam API service, repository, models, download manager |
| `app/src/main/java/com/wepwawet/pairing/` | JavaSteam downloader, pairing server, discovery, `.pkg`/`.mpkg` handling, ETC2 encoder |
| `app/src/main/java/com/wepwawet/auth/` | Steam login activity |
| `app/src/main/java/com/wepwawet/log/` | Logger, crash handler, log persistence |
| `app/src/main/cpp/` | JNI + bundled etc2comp |
| `app/proguard-rules.pro` | R8 rules |
| `app/debug.keystore` | Stable debug keystore so all builds share a signing key |
| `.github/workflows/build.yml` | CI: build the APK and attach it to a release |

## Device requirements

- **Android:** 8.0 (API 26) or higher
- **ABI:** arm64-v8a only
- **SoC:** Snapdragon 8 Gen 2 or newer is the tested target
- **RAM:** 6 GB+ recommended
- **Storage:** 1 GB+ free for the app and downloaded packs
- **Network:** internet access for Steam APIs

## Building

### Local

Requirements: JDK 17 and the Android SDK with NDK + CMake.

```bash
./gradlew :app:assembleRelease
```

## Disclaimer

Not affiliated with Valve, Steam, or Wallpaper Engine / Kristjan Skutta. You need your own Steam account.

## License

TBD. Likely Apache 2.0 or GPL 3, depending on what's simplest for third-party compliance. A `LICENSE` file will be added once decided.
