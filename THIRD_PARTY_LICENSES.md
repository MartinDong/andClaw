# Third-Party Licenses

This project includes third-party open-source components.
When distributing APK/AAB artifacts, you must comply with each component's license.

## Included Components (Key Runtime/Binary)

### 1) proot
- Upstream: https://github.com/proot-me/proot
- Package source used in build script:
  - https://packages.termux.dev/apt/termux-main/pool/main/p/proot/
- License: GPL-2.0-or-later
- Distribution note:
  - If `libproot.so` is shipped in APK/AAB, provide GPL notice and corresponding source access.

### 2) libtalloc
- Upstream: https://www.samba.org/
- Package source used in build script:
  - https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/
- License: LGPL-3.0-or-later
- Distribution note:
  - If `libtalloc.so` is shipped in APK/AAB, include LGPL notice and source/offer details as required.

## Other OSS Dependencies

The app also uses dependencies declared in Gradle files (for example AndroidX, Kotlin, OkHttp, Commons Compress, ZXing, etc.).
Their licenses remain applicable under their own terms.

## Recommended Compliance Checklist

1. Include an OSS notices screen or bundled notice file in app/release docs.
2. Keep links to upstream source repositories and license texts.
3. For GPL/LGPL components included as binaries, provide corresponding source access details in release notes or repository documentation.

## Disclaimer

This file is a practical compliance note, not legal advice.
For commercial/public distribution, run a formal legal/license review before release.
