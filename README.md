# andClaw

andClaw turns an Android phone into an on-device AI gateway host.
It runs OpenClaw inside a `proot` Ubuntu arm64 environment and provides a Compose-based mobile UI for setup, onboarding, and runtime control.

## Features

- One-tap setup flow for rootfs, Node.js, tools, OpenClaw, and Playwright Chromium
- Gateway lifecycle management (start/stop/restart) from Android UI
- Provider/model configuration (OpenRouter, OpenAI, Anthropic, Google, Codex mode)
- Channel support (WhatsApp, Telegram, Discord)
- Play Asset Delivery support for large install-time assets

## Requirements

- Android Studio / Gradle environment
- Java 11
- Docker (for asset preparation script)
- arm64 Android device (minimum SDK 26)

## Project Layout

- `app/` - Android app module (Kotlin + Jetpack Compose)
- `install_time_assets/` - install-time asset pack
- `scripts/setup-assets.sh` - prepares `jniLibs` and large bundled assets

## Build

```bash
# 1) Prepare assets (required on first build or when refreshing bundles)
./scripts/setup-assets.sh

# 2) Debug APK
./gradlew assembleDebug

# 3) Release AAB
./gradlew bundleRelease
```

Artifacts:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release AAB: `app/build/outputs/bundle/release/app-release.aab`

## Install (Debug)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

## Open-Source Notices

See `THIRD_PARTY_LICENSES.md` for key third-party runtime components and distribution notes.

