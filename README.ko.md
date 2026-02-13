# andClaw

andClaw는 안드로이드 폰을 온디바이스 AI 게이트웨이 호스트로 바꿔주는 앱이다.
`proot` 기반 Ubuntu arm64 환경에서 OpenClaw를 실행하고, Compose UI로 설치/온보딩/실행 제어를 제공한다.

## 주요 기능

- rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 원클릭 설치
- 앱 내 게이트웨이 시작/중지/재시작 제어
- Provider/모델 설정 (OpenRouter, OpenAI, Anthropic, Google, Codex 모드)
- 채널 지원 (WhatsApp, Telegram, Discord)
- 대용량 에셋을 위한 Play Asset Delivery 지원

## 요구사항

- Android Studio / Gradle 환경
- Java 11
- Docker (에셋 준비 스크립트 실행 시 필요)
- arm64 안드로이드 기기 (최소 SDK 26)

## 프로젝트 구조

- `app/` - Android 앱 모듈 (Kotlin + Jetpack Compose)
- `install_time_assets/` - install-time asset pack
- `scripts/setup-assets.sh` - `jniLibs`와 대용량 에셋 생성 스크립트

## 빌드

```bash
# 1) 에셋 준비 (최초 1회 또는 번들 갱신 시)
./scripts/setup-assets.sh

# 2) 디버그 APK
./gradlew assembleDebug

# 3) 릴리스 AAB
./gradlew bundleRelease
```

산출물:

- 디버그 APK: `app/build/outputs/apk/debug/app-debug.apk`
- 릴리스 AAB: `app/build/outputs/bundle/release/app-release.aab`

## 디버그 설치

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 테스트

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

## 오픈소스 고지

핵심 서드파티 런타임 컴포넌트와 배포 시 유의사항은 `THIRD_PARTY_LICENSES.md`를 참고.

