#!/bin/bash
#
# andClaw - 빌드 준비 스크립트 (proot + assets 번들)
#
# proot 바이너리를 jniLibs에 배치하고,
# Ubuntu arm64 rootfs, Node.js, 시스템 도구, OpenClaw, Playwright Chromium 을
# install_time_assets/src/main/assets/ 에 배치한다.
#
# 필요 조건:
#   - Docker Desktop (arm64 에뮬레이션 지원)
#   - curl, ar, tar
#
# 사용법:
#   chmod +x scripts/setup-assets.sh
#   ./scripts/setup-assets.sh
#
# 이 스크립트 실행 후 생성되는 파일:
#   jniLibs/arm64-v8a/
#     libproot.so, libtalloc.so, libproot-loader.so, libproot-loader32.so
#
#   install_time_assets/src/main/assets/
#     rootfs.tar.gz.bin                     (~30MB)   Ubuntu 24.04 arm64 base
#     node-arm64.tar.gz.bin                 (~25MB)   Node.js 22 arm64 linux
#     system-tools-arm64.tar.gz.bin         (~80-100MB) git, curl, python3, 시스템 libs
#     openclaw/                             OpenClaw 파일 트리 (증분 업데이트 최적화)
#     playwright-chromium-arm64.tar.gz.bin  (~150-180MB) Chromium headless_shell
#
# 업데이트 시 변경: openclaw/ 디렉토리만 갱신하면 됨
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"
ASSETS_DIR="$PROJECT_DIR/install_time_assets/src/main/assets"

# URLs & Versions
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107-70_aarch64.deb"
TALLOC_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb"
ROOTFS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
NODEJS_VERSION="v22.12.0"
NODEJS_URL="https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz"
PLAYWRIGHT_VERSION="1.49.1"

echo "============================================"
echo "  andClaw - 빌드 준비 (proot + assets)"
echo "============================================"
echo ""

mkdir -p "$JNILIBS_DIR"
mkdir -p "$ASSETS_DIR"

# ══════════════════════════════════════════════
#  Part 1: proot 바이너리 (jniLibs)
# ══════════════════════════════════════════════

if [ -f "$JNILIBS_DIR/libproot.so" ] && [ -f "$JNILIBS_DIR/libtalloc.so" ]; then
    echo "[1/7] proot 바이너리 이미 존재, 건너뜀"
    ls -lh "$JNILIBS_DIR/"*.so 2>/dev/null | while read line; do echo "   $line"; done
else
    echo "[1/7] proot 바이너리 설정 중..."

    TMP_DIR=$(mktemp -d)
    trap "rm -rf $TMP_DIR" EXIT

    # proot 다운로드 & 추출
    echo "   proot 패키지 다운로드 중..."
    curl -fSL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"

    cd "$TMP_DIR"
    mkdir -p proot_extract
    cd proot_extract
    ar x ../proot.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    PROOT_BIN=$(find . -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "   ERROR: proot 바이너리를 찾을 수 없습니다!"
        exit 1
    fi

    # libtalloc 다운로드 & 추출
    echo "   libtalloc 패키지 다운로드 중..."
    cd "$TMP_DIR"
    curl -fSL "$TALLOC_DEB_URL" -o "$TMP_DIR/talloc.deb"

    mkdir -p talloc_extract
    cd talloc_extract
    ar x ../talloc.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    TALLOC_LIB=$(find . -name "libtalloc.so*" -type f | head -1)
    if [ -z "$TALLOC_LIB" ]; then
        echo "   ERROR: libtalloc.so 를 찾을 수 없습니다!"
        exit 1
    fi

    # jniLibs 에 배치
    cp "$TMP_DIR/proot_extract/$PROOT_BIN" "$JNILIBS_DIR/libproot.so"
    cp "$TMP_DIR/talloc_extract/$TALLOC_LIB" "$JNILIBS_DIR/libtalloc.so"

    LOADER_BIN=$(find "$TMP_DIR/proot_extract" -name "loader" -path "*/proot/*" -type f 2>/dev/null | head -1)
    LOADER32_BIN=$(find "$TMP_DIR/proot_extract" -name "loader32" -path "*/proot/*" -type f 2>/dev/null | head -1)

    if [ -n "$LOADER_BIN" ]; then
        cp "$LOADER_BIN" "$JNILIBS_DIR/libproot-loader.so"
        chmod +x "$JNILIBS_DIR/libproot-loader.so"
        echo "   proot-loader: OK"
    else
        echo "   WARNING: proot-loader 없음"
    fi

    if [ -n "$LOADER32_BIN" ]; then
        cp "$LOADER32_BIN" "$JNILIBS_DIR/libproot-loader32.so"
        chmod +x "$JNILIBS_DIR/libproot-loader32.so"
        echo "   proot-loader32: OK"
    else
        echo "   WARNING: proot-loader32 없음"
    fi

    chmod +x "$JNILIBS_DIR/libproot.so"
    chmod +x "$JNILIBS_DIR/libtalloc.so"

    rm -rf "$TMP_DIR"
    trap - EXIT

    echo "   proot 바이너리 설정 완료"
    ls -lh "$JNILIBS_DIR/"
fi

# ══════════════════════════════════════════════
#  Part 2: assets 번들
# ══════════════════════════════════════════════

# ── 2. Ubuntu rootfs 다운로드 ──
ROOTFS_FILE="$ASSETS_DIR/rootfs.tar.gz.bin"
if [ -f "$ROOTFS_FILE" ]; then
    echo "[2/7] rootfs.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$ROOTFS_FILE" | cut -f1)"
else
    echo "[2/7] Ubuntu 24.04 arm64 rootfs 다운로드 중..."
    echo "   URL: $ROOTFS_URL"
    curl -fSL "$ROOTFS_URL" -o "$ROOTFS_FILE"
    echo "   완료: $(du -h "$ROOTFS_FILE" | cut -f1)"
fi

# ── 3. Node.js 다운로드 ──
NODEJS_FILE="$ASSETS_DIR/node-arm64.tar.gz.bin"
if [ -f "$NODEJS_FILE" ]; then
    echo "[3/7] node-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$NODEJS_FILE" | cut -f1)"
else
    echo "[3/7] Node.js $NODEJS_VERSION arm64 다운로드 중..."
    echo "   URL: $NODEJS_URL"
    curl -fSL "$NODEJS_URL" -o "$NODEJS_FILE"
    echo "   완료: $(du -h "$NODEJS_FILE" | cut -f1)"
fi

# ── Docker 필수 확인 ──
check_docker() {
    if ! command -v docker &>/dev/null; then
        echo "   ERROR: Docker가 필요합니다"
        exit 1
    fi
}

# ── 4. 시스템 도구 번들 (Docker Build 1) ──
TOOLS_FILE="$ASSETS_DIR/system-tools-arm64.tar.gz.bin"
if [ -f "$TOOLS_FILE" ]; then
    echo "[4/7] system-tools-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$TOOLS_FILE" | cut -f1)"
else
    echo "[4/7] 시스템 도구 번들 빌드 중 (Docker)..."
    echo "   git, curl, python3, 시스템 libs, Chromium deps 포함"
    check_docker

    docker rm -f andclaw-tools-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-tools-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        echo '--- apt-get update ---'
        apt-get update -qq

        # libs 스냅샷 (설치 전)
        echo '--- Snapshot libs before installs ---'
        find /usr/lib -type f -o -type l 2>/dev/null | sort > /tmp/libs-before.txt

        echo '--- Installing core tools ---'
        apt-get install -y -qq --no-install-recommends \\
            curl wget git ca-certificates \\
            python3 python3-pip python3-venv \\
            openssh-client rsync \\
            jq zip unzip \\
            less vim-tiny \\
            coreutils findutils procps \\
            iproute2 iputils-ping dnsutils net-tools \\
            file diffutils patch

        git --version
        python3 --version
        curl --version | head -1

        # Chromium 시스템 의존성
        echo '--- Installing Chromium system dependencies ---'
        apt-get install -y -qq --no-install-recommends \\
            libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \\
            libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \\
            libxrandr2 libgbm1 libpango-1.0-0 libcairo2 libasound2t64 \\
            libx11-6 libxcb1 libxext6 libdbus-1-3 fonts-liberation \\
            libatspi2.0-0 libxcursor1 libxi6 libxtst6

        echo '--- Snapshot libs after installs ---'
        find /usr/lib -type f -o -type l 2>/dev/null | sort > /tmp/libs-after.txt
        comm -13 /tmp/libs-before.txt /tmp/libs-after.txt > /tmp/new-libs.txt
        echo \"New libs count: \$(wc -l < /tmp/new-libs.txt)\"

        # 바이너리 목록 생성
        echo '--- Collecting binaries ---'
        > /tmp/bin-list.txt
        for bin in \\
            curl wget \\
            git git-receive-pack git-upload-archive git-upload-pack \\
            python3 pip3 python3-config \\
            ssh scp sftp ssh-keygen ssh-keyscan \\
            rsync \\
            jq zip unzip \\
            less vim.tiny \\
            ls dir vdir cp mv rm mkdir rmdir chmod chown chgrp ln cat \\
            head tail sort uniq wc cut tr tee paste \\
            grep egrep fgrep sed awk \\
            find xargs locate \\
            tar gzip gunzip bzip2 \\
            basename dirname readlink realpath \\
            date touch stat file md5sum sha256sum \\
            diff patch \\
            ps kill top free \\
            ip ss ping nslookup dig host netstat \\
            id whoami hostname uname env printenv \\
            expr test true false sleep seq dd \\
        ; do
            for dir in /usr/bin /usr/local/bin /bin /usr/sbin /sbin; do
                if [ -e \"\$dir/\$bin\" ]; then
                    echo \"\${dir#/}/\$bin\" >> /tmp/bin-list.txt
                    break
                fi
            done
        done
        echo \"Binaries to bundle: \$(wc -l < /tmp/bin-list.txt)\"

        echo '--- Creating system-tools bundle ---'
        cd /
        tar czf /tmp/system-tools.tar.gz \\
            usr/lib/git-core \\
            usr/share/git-core \\
            usr/share/perl \\
            usr/lib/python3* \\
            usr/lib/python3 \\
            usr/share/python3 \\
            \$(cat /tmp/bin-list.txt) \\
            \$(cat /tmp/new-libs.txt | sed 's|^/||') \\
            usr/share/fonts/truetype/liberation \\
            etc/ssl/certs \\
            usr/share/ca-certificates \\
            2>/dev/null || true
        ls -lh /tmp/system-tools.tar.gz
        echo '--- DONE ---'
    "

    docker cp andclaw-tools-builder:/tmp/system-tools.tar.gz "$TOOLS_FILE"
    docker rm andclaw-tools-builder

    echo "   완료: $(du -h "$TOOLS_FILE" | cut -f1)"
fi

# ── 5. OpenClaw 파일 자산 (Docker Build 2) ──
OPENCLAW_ASSET_DIR="$ASSETS_DIR/openclaw"
OPENCLAW_MAIN="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/openclaw.mjs"
OPENCLAW_BIN="$OPENCLAW_ASSET_DIR/usr/local/bin/openclaw"
OPENCLAW_ANTHROPIC_PARSER="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk/andclaw_us__vendor/partial-json-parser/parser.js"

ensure_openclaw_json_parser_shim() {
    for sdk_root in \
        "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk" \
        "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw/extensions/memory-lancedb/node_modules/@anthropic-ai/sdk" \
    ; do
        partial_js="$sdk_root/_vendor/partial-json-parser/parser.js"
        partial_mjs="$sdk_root/_vendor/partial-json-parser/parser.mjs"
        shim_dir="$sdk_root/_vendor/json-parser"
        if [ -f "$partial_js" ]; then
            mkdir -p "$shim_dir"
            printf '%s\n' 'module.exports = require("../partial-json-parser/parser.js");' > "$shim_dir/json-parser.js"
        fi
        if [ -f "$partial_mjs" ]; then
            mkdir -p "$shim_dir"
            printf '%s\n' 'export * from "../partial-json-parser/parser.mjs";' > "$shim_dir/json-parser.mjs"
        fi
    done
}

encode_openclaw_underscore_paths() {
    local openclaw_root="$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw"
    if [ ! -d "$openclaw_root" ]; then
        return
    fi
    # Android assets 패키징에서 '_*' 파일/디렉토리가 누락될 수 있어 안전한 이름으로 인코딩
    find "$openclaw_root" -depth \( -type d -o -type f \) | while read -r path; do
        name="$(basename "$path")"
        parent="$(dirname "$path")"
        case "$name" in
            _*)
                encoded="$parent/andclaw_us__${name#_}"
                if [ -e "$encoded" ]; then
                    rm -rf "$encoded"
                fi
                mv "$path" "$encoded"
                ;;
        esac
    done
}


if [ -f "$OPENCLAW_MAIN" ] && [ -f "$OPENCLAW_BIN" ] && [ -f "$OPENCLAW_ANTHROPIC_PARSER" ]; then
    echo "[5/7] openclaw 디렉토리 자산 이미 존재, 건너뜀"
    echo "   크기: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
else
    echo "[5/7] OpenClaw 디렉토리 자산 빌드 중 (Docker)..."
    echo "   npm install -g openclaw (latest)"
    check_docker

    docker rm -f andclaw-openclaw-builder 2>/dev/null || true
    rm -rf "$OPENCLAW_ASSET_DIR"
    mkdir -p "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules" "$OPENCLAW_ASSET_DIR/usr/local/bin"

    docker run --platform linux/arm64 --name andclaw-openclaw-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends curl ca-certificates git

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version

        echo '--- Installing OpenClaw ---'
        npm install -g openclaw 2>&1

        # 호환성: 일부 번들/의존성은 구 경로(_vendor/json-parser/json-parser.js)를 참조할 수 있다.
        # 최신 SDK는 _vendor/partial-json-parser/parser.js를 사용하므로 shim을 생성해 둘 다 동작하게 한다.
        for sdk_root in \
            /usr/local/lib/node_modules/openclaw/node_modules/@anthropic-ai/sdk \
            /usr/local/lib/node_modules/openclaw/extensions/memory-lancedb/node_modules/@anthropic-ai/sdk \
        ; do
            if [ -d \$sdk_root/_vendor/partial-json-parser ]; then
                mkdir -p \$sdk_root/_vendor/json-parser
                printf '%s\n' 'module.exports = require("../partial-json-parser/parser.js");' > \$sdk_root/_vendor/json-parser/json-parser.js
                printf '%s\n' 'export * from "../partial-json-parser/parser.mjs";' > \$sdk_root/_vendor/json-parser/json-parser.mjs
            fi
        done

        # Windows docker cp에서 symlink 생성 권한 오류를 피하기 위해 .bin 심링크 제거
        find /usr/local/lib/node_modules/openclaw/node_modules -path '*/.bin/*' -type l -delete || true

        # openclaw bin symlink -> 셸 래퍼로 교체 (ESM 상대 경로 호환성)
        rm -f /usr/local/bin/openclaw
        printf '#!/bin/sh\nexec node /usr/local/lib/node_modules/openclaw/openclaw.mjs \"\$@\"\n' > /usr/local/bin/openclaw
        chmod +x /usr/local/bin/openclaw

        ls -lh /usr/local/bin/openclaw
        echo '--- DONE ---'
    "

    docker cp andclaw-openclaw-builder:/usr/local/lib/node_modules/openclaw "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw"
    docker cp andclaw-openclaw-builder:/usr/local/bin/openclaw "$OPENCLAW_ASSET_DIR/usr/local/bin/openclaw"
    docker rm andclaw-openclaw-builder

    echo "   완료: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
fi

# 일부 npm 조합에서 _vendor/json-parser 경로를 요구하므로, 에셋 복사 후 호스트에서 shim을 다시 보장한다.
ensure_openclaw_json_parser_shim
# assets 패키징 전 _* 경로 인코딩
encode_openclaw_underscore_paths

# ── 6. Playwright Chromium 번들 (Docker Build 3) ──
PLAYWRIGHT_FILE="$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin"
if [ -f "$PLAYWRIGHT_FILE" ]; then
    echo "[6/7] playwright-chromium-arm64.tar.gz.bin 이미 존재, 건너뜀"
    echo "   크기: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
else
    echo "[6/7] Playwright Chromium 번들 빌드 중 (Docker)..."
    echo "   Playwright $PLAYWRIGHT_VERSION, Chromium headless_shell only"
    check_docker

    docker rm -f andclaw-playwright-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-playwright-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends curl ca-certificates

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version

        echo '--- Installing Playwright Chromium ---'
        npx playwright@$PLAYWRIGHT_VERSION install chromium 2>&1

        PW_DIR=/root/.cache/ms-playwright

        # full chromium 디렉토리 삭제 (chromium-XXXX)
        FULL_CHROME_DIR=\$(find \$PW_DIR -maxdepth 1 -type d -name 'chromium-*' 2>/dev/null | head -1)
        if [ -n \"\$FULL_CHROME_DIR\" ]; then
            echo \"Removing full chrome: \$FULL_CHROME_DIR (\$(du -sh \$FULL_CHROME_DIR | cut -f1))\"
            rm -rf \"\$FULL_CHROME_DIR\"
        fi

        # headless_shell 경량화
        HS_DIR=\$(find \$PW_DIR -maxdepth 1 -type d -name 'chromium_headless_shell-*' 2>/dev/null | head -1)
        if [ -n \"\$HS_DIR\" ]; then
            HS_CHROME_DIR=\"\$HS_DIR/chrome-linux\"
            if [ -d \"\$HS_CHROME_DIR/locales\" ]; then
                find \"\$HS_CHROME_DIR/locales\" -name '*.pak' ! -name 'en-US.pak' -delete
                echo \"Locales trimmed\"
            fi
            rm -f \"\$HS_CHROME_DIR/chrome_crashpad_handler\" 2>/dev/null
            rm -rf \"\$HS_CHROME_DIR/MEIPreload\" 2>/dev/null
            echo \"headless_shell size: \$(du -sh \$HS_DIR | cut -f1)\"
        else
            echo \"WARNING: headless_shell not found!\"
        fi
        echo \"Total playwright size: \$(du -sh \$PW_DIR | cut -f1)\"

        echo '--- Creating playwright bundle ---'
        cd /
        tar czf /tmp/playwright.tar.gz \\
            root/.cache/ms-playwright
        ls -lh /tmp/playwright.tar.gz
        echo '--- DONE ---'
    "

    docker cp andclaw-playwright-builder:/tmp/playwright.tar.gz "$PLAYWRIGHT_FILE"
    docker rm andclaw-playwright-builder

    echo "   완료: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
fi

# ── 7. 정리 ──
echo "[7/7] 정리 중..."

# 기존 통합 번들 삭제
OLD_BUNDLE="$ASSETS_DIR/openclaw-bundle-arm64.tar.gz.bin"
if [ -f "$OLD_BUNDLE" ]; then
    echo "   기존 통합 번들 삭제: openclaw-bundle-arm64.tar.gz.bin"
    rm -f "$OLD_BUNDLE"
fi

# 기존 OpenClaw tar 자산 삭제 (디렉토리 방식으로 이관)
OLD_OPENCLAW_TAR="$ASSETS_DIR/openclaw-arm64.tar.gz.bin"
if [ -f "$OLD_OPENCLAW_TAR" ]; then
    echo "   구형 OpenClaw tar 삭제: openclaw-arm64.tar.gz.bin"
    rm -f "$OLD_OPENCLAW_TAR"
fi

# 실행 권한 복원을 위한 매니페스트 생성
EXEC_MANIFEST="$ASSETS_DIR/executable-manifest.json"
cat > "$EXEC_MANIFEST" <<'JSON'
{
  "assets": {
    "openclaw": [
      "usr/local/bin/openclaw"
    ],
    "rootfs.tar.gz.bin": [],
    "node-arm64.tar.gz.bin": [],
    "system-tools-arm64.tar.gz.bin": [],
    "playwright-chromium-arm64.tar.gz.bin": []
  }
}
JSON
echo "   executable-manifest.json 생성 완료"

echo ""
echo "============================================"
echo "  완료!"
echo "============================================"
echo ""
echo "jniLibs:"
ls -lh "$JNILIBS_DIR/"
echo ""
echo "assets:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "총 assets 크기: $(du -sh "$ASSETS_DIR/" | cut -f1)"
echo ""
echo "다음 단계: Android Studio 에서 빌드"
