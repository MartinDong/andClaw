#!/bin/bash
#
# andClaw - 构建准备脚本 (proot + assets 包)
#
# 将proot二进制文件放置到jniLibs中,
# 将Ubuntu arm64 rootfs、Node.js、系统工具、OpenClaw、Playwright Chromium
# 放置到install_time_assets/src/main/assets/ 目录下。
#
# 必要条件:
#   - Docker Desktop（支持arm64仿真）
#   - curl、ar、tar
#
# 使用方法:
#   chmod +x scripts/setup-assets.sh
#   ./scripts/setup-assets.sh
#
# 执行此脚本后生成的文件:
#   jniLibs/arm64-v8a/
#     libproot.so、libtalloc.so、libproot-loader.so、libproot-loader32.so
#
#   install_time_assets/src/main/assets/
#     rootfs.tar.gz.bin                     (~30MB)   Ubuntu 24.04 arm64 基础镜像
#     node-arm64.tar.gz.bin                 (~25MB)   Node.js 22 arm64 linux版本
#     system-tools-arm64.tar.gz.bin         (~80-100MB) git、curl、python3、系统库
#     openclaw/                             OpenClaw 文件树（增量更新优化）
#     playwright-chromium-arm64.tar.gz.bin  (~150-180MB) Chromium headless_shell
#
# 更新时只需更改：仅需更新openclaw/目录
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
TERMUX_PROOT_COMMIT="${TERMUX_PROOT_COMMIT:-4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f}"
CUSTOM_LOADER32_SCRIPT="$SCRIPT_DIR/build-proot-loader32-16kb.sh"

require_readelf() {
    if command -v greadelf >/dev/null 2>&1; then
        READELF_CMD="greadelf"
    elif command -v readelf >/dev/null 2>&1; then
        READELF_CMD="readelf"
    else
        echo "   WARNING: 未找到readelf/greadelf。跳过16KB对齐验证。"
        echo "   (可通过brew install binutils安装)"
        READELF_CMD=""
    fi
}

is_elf_16kb_compatible() {
    local elf_path="$1"
    local aligns

    if [ -z "$READELF_CMD" ]; then
        return 0
    fi

    if [ ! -f "$elf_path" ]; then
        return 1
    fi

    aligns=$($READELF_CMD -W -l "$elf_path" | awk '/^[[:space:]]*LOAD[[:space:]]/ { print $NF }')
    if [ -z "$aligns" ]; then
        return 1
    fi

    while read -r align; do
        if [ "$align" != "0x4000" ]; then
            return 1
        fi
    done <<< "$aligns"

    return 0
}

verify_jnilib_16kb() {
    local libs=(
        "libproot.so"
        "libtalloc.so"
        "libproot-loader.so"
    )
    local loader32="$JNILIBS_DIR/libproot-loader32.so"
    local failed=0

    echo "   正在验证jniLibs 16KB对齐..."

    for lib in "${libs[@]}"; do
        local path="$JNILIBS_DIR/$lib"
        if [ ! -f "$path" ]; then
            echo "   ERROR: 未找到$lib文件"
            failed=1
            continue
        fi
        if is_elf_16kb_compatible "$path"; then
            echo "   OK: $lib (16KB)"
        else
            echo "   ERROR: $lib 未进行16KB对齐"
            failed=1
        fi
    done

    if [ -f "$loader32" ]; then
        if is_elf_16kb_compatible "$loader32"; then
            echo "   OK: libproot-loader32.so (16KB)"
        else
            echo "   ERROR: libproot-loader32.so 未进行16KB对齐"
            failed=1
        fi
    else
        echo "   WARNING: 未找到libproot-loader32.so"
    fi

    if [ "$failed" -ne 0 ]; then
        echo "ERROR: 16KB对齐验证失败"
        exit 1
    fi
}

ensure_loader32_16kb() {
    local loader32="$JNILIBS_DIR/libproot-loader32.so"

    if [ ! -f "$loader32" ]; then
        echo "   未找到libproot-loader32.so，尝试从源代码构建"
    elif is_elf_16kb_compatible "$loader32"; then
        echo "   libproot-loader32.so 已兼容16KB"
        return
    else
        echo "   libproot-loader32.so 为4KB对齐，将通过源代码构建替换"
    fi

    if [ ! -x "$CUSTOM_LOADER32_SCRIPT" ]; then
        echo "ERROR: $CUSTOM_LOADER32_SCRIPT 文件不存在或无执行权限"
        exit 1
    fi

    "$CUSTOM_LOADER32_SCRIPT" "$loader32" "$TERMUX_PROOT_COMMIT"

    if ! is_elf_16kb_compatible "$loader32"; then
        echo "ERROR: 源代码构建后libproot-loader32.so的16KB验证仍失败"
        exit 1
    fi
}

echo "============================================"
echo "  andClaw - 构建准备 (proot + assets)"
echo "============================================"
echo ""

mkdir -p "$JNILIBS_DIR"
mkdir -p "$ASSETS_DIR"

# ══════════════════════════════════════════════
#  Part 1: proot二进制文件 (jniLibs)
# ══════════════════════════════════════════════

if [ -f "$JNILIBS_DIR/libproot.so" ] && [ -f "$JNILIBS_DIR/libtalloc.so" ]; then
    echo "[1/7] proot二进制文件已存在，跳过"
    ls -lh "$JNILIBS_DIR/"*.so 2>/dev/null | while read line; do echo "   $line"; done
else
    echo "[1/7] 正在配置proot二进制文件..."

    TMP_DIR=$(mktemp -d)
    trap "rm -rf $TMP_DIR" EXIT

    # 下载并提取proot
    echo "   正在下载proot包..."
    curl -fSL "$PROOT_DEB_URL" -o "$TMP_DIR/proot.deb"

    cd "$TMP_DIR"
    mkdir -p proot_extract
    cd proot_extract
    tar -xf ../proot.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    PROOT_BIN=$(find . -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "   ERROR: 未找到proot二进制文件!"
        exit 1
    fi

    # 下载并提取libtalloc
    echo "   正在下载libtalloc包..."
    cd "$TMP_DIR"
    curl -fSL "$TALLOC_DEB_URL" -o "$TMP_DIR/talloc.deb"

    mkdir -p talloc_extract
    cd talloc_extract
    tar -xf ../talloc.deb
    if [ -f data.tar.xz ]; then
        tar xf data.tar.xz 2>/dev/null || (xz -d data.tar.xz && tar xf data.tar 2>/dev/null) || true
    elif [ -f data.tar.gz ]; then
        tar xzf data.tar.gz 2>/dev/null || true
    elif [ -f data.tar.zst ]; then
        zstd -d data.tar.zst && tar xf data.tar 2>/dev/null || true
    fi

    TALLOC_LIB=$(find . -name "libtalloc.so*" -type f | head -1)
    if [ -z "$TALLOC_LIB" ]; then
        echo "   ERROR: 未找到libtalloc.so!"
        exit 1
    fi

    # 放置到jniLibs
    cp "$TMP_DIR/proot_extract/$PROOT_BIN" "$JNILIBS_DIR/libproot.so"
    cp "$TMP_DIR/talloc_extract/$TALLOC_LIB" "$JNILIBS_DIR/libtalloc.so"

    LOADER_BIN=$(find "$TMP_DIR/proot_extract" -name "loader" -path "*/proot/*" -type f 2>/dev/null | head -1)
    LOADER32_BIN=$(find "$TMP_DIR/proot_extract" -name "loader32" -path "*/proot/*" -type f 2>/dev/null | head -1)

    if [ -n "$LOADER_BIN" ]; then
        cp "$LOADER_BIN" "$JNILIBS_DIR/libproot-loader.so"
        chmod +x "$JNILIBS_DIR/libproot-loader.so"
        echo "   proot-loader: OK"
    else
        echo "   WARNING: 未找到proot-loader"
    fi

    if [ -n "$LOADER32_BIN" ]; then
        cp "$LOADER32_BIN" "$JNILIBS_DIR/libproot-loader32.so"
        chmod +x "$JNILIBS_DIR/libproot-loader32.so"
        echo "   proot-loader32: OK"
    else
        echo "   WARNING: 未找到proot-loader32"
    fi

    chmod +x "$JNILIBS_DIR/libproot.so"
    chmod +x "$JNILIBS_DIR/libtalloc.so"

    rm -rf "$TMP_DIR"
    trap - EXIT

    echo "   proot二进制文件配置完成"
    ls -lh "$JNILIBS_DIR/"
fi

require_readelf
ensure_loader32_16kb
verify_jnilib_16kb

# ══════════════════════════════════════════════
#  Part 2: assets 번들
# ══════════════════════════════════════════════

# ── 2. 下载Ubuntu rootfs ──
ROOTFS_FILE="$ASSETS_DIR/rootfs.tar.gz.bin"
if [ -f "$ROOTFS_FILE" ]; then
    echo "[2/7] rootfs.tar.gz.bin 已存在，跳过"
    echo "   大小: $(du -h "$ROOTFS_FILE" | cut -f1)"
else
    echo "[2/7] 正在下载Ubuntu 24.04 arm64 rootfs..."
    echo "   URL: $ROOTFS_URL"
    curl -fSL "$ROOTFS_URL" -o "$ROOTFS_FILE"
    echo "   完成: $(du -h "$ROOTFS_FILE" | cut -f1)"
fi

# ── 3. 下载Node.js ──
NODEJS_FILE="$ASSETS_DIR/node-arm64.tar.gz.bin"
if [ -f "$NODEJS_FILE" ]; then
    echo "[3/7] node-arm64.tar.gz.bin 已存在，跳过"
    echo "   大小: $(du -h "$NODEJS_FILE" | cut -f1)"
else
    echo "[3/7] 正在下载Node.js $NODEJS_VERSION arm64..."
    echo "   URL: $NODEJS_URL"
    curl -fSL "$NODEJS_URL" -o "$NODEJS_FILE"
    echo "   完成: $(du -h "$NODEJS_FILE" | cut -f1)"
fi

# ── 检查Docker是否安装 ──
check_docker() {
    if ! command -v docker &>/dev/null; then
        echo "   ERROR: 需要安装Docker"
        exit 1
    fi
}

# ── 4. 构建系统工具包 (Docker Build 1) ──
TOOLS_FILE="$ASSETS_DIR/system-tools-arm64.tar.gz.bin"
if [ -f "$TOOLS_FILE" ]; then
    echo "[4/7] system-tools-arm64.tar.gz.bin 已存在，跳过"
    echo "   大小: $(du -h "$TOOLS_FILE" | cut -f1)"
else
    echo "[4/7] 正在构建系统工具包 (Docker)..."
    echo "   包含git、curl、python3、系统库、Chromium依赖"
    check_docker

    docker rm -f andclaw-tools-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-tools-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        
        # 先安装 ca-certificates (使用HTTP源)
        sed -i 's|https://mirrors.aliyun.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|https://archive.ubuntu.com|http://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends ca-certificates
        
        # 切换到HTTPS源
        sed -i 's|http://mirrors.aliyun.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        echo '--- apt-get update ---'
        for i in 1 2 3; do
            apt-get update -qq && break || { echo 'apt-get update failed, retrying...'; sleep 5; }
        done
        
        # 安装前的库快照
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

        # Chromium系统依赖
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

        # 生成二进制文件列表
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

    echo "   完成: $(du -h "$TOOLS_FILE" | cut -f1)"
fi

# ── 5. 构建OpenClaw文件资产 (Docker Build 2) ──
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
    # Android assets打包时可能会遗漏'_*'文件/目录，因此使用安全名称进行编码
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
    echo "[5/7] openclaw目录资产已存在，跳过"
    echo "   大小: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
else
    echo "[5/7] 正在构建OpenClaw目录资产 (Docker)..."
    echo "   npm install -g openclaw (latest)"
    check_docker

    docker rm -f andclaw-openclaw-builder 2>/dev/null || true
    rm -rf "$OPENCLAW_ASSET_DIR"
    mkdir -p "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules" "$OPENCLAW_ASSET_DIR/usr/local/bin"

    docker run --platform linux/arm64 --name andclaw-openclaw-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        
        # 先安装 ca-certificates (使用HTTP源)
        sed -i 's|https://mirrors.aliyun.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|https://archive.ubuntu.com|http://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends ca-certificates
        
        # 切换到HTTPS源
        sed -i 's|http://mirrors.aliyun.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        for i in 1 2 3; do
            apt-get update -qq && break || {
                echo 'apt-get update failed, retrying...'
                sleep 5
            }
        done
        apt-get install -y -qq --no-install-recommends curl ca-certificates git

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version

        echo '--- Installing OpenClaw ---'
        npm config set registry https://registry.npmjs.org/
        npm config set fetch-retries 5
        npm config set fetch-retry-mintimeout 20000
        npm config set fetch-retry-maxtimeout 120000
        npm install -g openclaw 2>&1

        # 兼容性：某些包/依赖可能引用旧路径(_vendor/json-parser/json-parser.js)。
        # 最新SDK使用_vendor/partial-json-parser/parser.js，因此生成shim以兼容两种路径。
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

        # 为避免Windows docker cp中的符号链接创建权限错误，删除.bin符号链接
        find /usr/local/lib/node_modules/openclaw/node_modules -path '*/.bin/*' -type l -delete || true

        # 将openclaw bin符号链接替换为shell包装器（ESM相对路径兼容性）
        rm -f /usr/local/bin/openclaw
        printf '#!/bin/sh\nexec node /usr/local/lib/node_modules/openclaw/openclaw.mjs \"\$@\"\n' > /usr/local/bin/openclaw
        chmod +x /usr/local/bin/openclaw

        ls -lh /usr/local/bin/openclaw
        echo '--- DONE ---'
    "

    docker cp andclaw-openclaw-builder:/usr/local/lib/node_modules/openclaw "$OPENCLAW_ASSET_DIR/usr/local/lib/node_modules/openclaw"
    docker cp andclaw-openclaw-builder:/usr/local/bin/openclaw "$OPENCLAW_ASSET_DIR/usr/local/bin/openclaw"
    docker rm andclaw-openclaw-builder

    echo "   完成: $(du -sh "$OPENCLAW_ASSET_DIR" | cut -f1)"
fi

# 由于某些npm组合需要_vendor/json-parser路径，因此在资产复制后再次在主机上确保shim存在。
ensure_openclaw_json_parser_shim
# assets打包前对_*路径进行编码
encode_openclaw_underscore_paths

# ── 6. 构建Playwright Chromium包 (Docker Build 3) ──
PLAYWRIGHT_FILE="$ASSETS_DIR/playwright-chromium-arm64.tar.gz.bin"
if [ -f "$PLAYWRIGHT_FILE" ]; then
    echo "[6/7] playwright-chromium-arm64.tar.gz.bin 已存在，跳过"
    echo "   大小: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
else
    echo "[6/7] 正在构建Playwright Chromium包 (Docker)..."
    echo "   Playwright $PLAYWRIGHT_VERSION, Chromium headless_shell only"
    check_docker

    docker rm -f andclaw-playwright-builder 2>/dev/null || true

    docker run --platform linux/arm64 --name andclaw-playwright-builder ubuntu:24.04 bash -c "
        set -e
        export DEBIAN_FRONTEND=noninteractive
        
        # 先安装 ca-certificates (使用HTTP源)
        sed -i 's|https://mirrors.aliyun.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|https://archive.ubuntu.com|http://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        apt-get update -qq
        apt-get install -y -qq --no-install-recommends ca-certificates
        
        # 切换到HTTPS源
        sed -i 's|http://mirrors.aliyun.com|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/ubuntu.sources 2>/dev/null || \
        sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g' /etc/apt/sources.list 2>/dev/null || true
        
        for i in 1 2 3; do
            apt-get update -qq && break || { echo 'apt-get update failed, retrying...'; sleep 5; }
        done
        apt-get install -y -qq --no-install-recommends curl ca-certificates

        echo '--- Installing Node.js ---'
        curl -fsSL https://nodejs.org/dist/$NODEJS_VERSION/node-$NODEJS_VERSION-linux-arm64.tar.gz | tar xz -C /usr/local --strip-components=1
        node --version

        echo '--- Installing Playwright Chromium ---'
        npx playwright@$PLAYWRIGHT_VERSION install chromium 2>&1

        PW_DIR=/root/.cache/ms-playwright

        # 删除完整的chromium目录 (chromium-XXXX)
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

    echo "   完成: $(du -h "$PLAYWRIGHT_FILE" | cut -f1)"
fi

# ── 7. 清理 ──
echo "[7/7] 正在清理..."

# 删除旧的集成包
OLD_BUNDLE="$ASSETS_DIR/openclaw-bundle-arm64.tar.gz.bin"
if [ -f "$OLD_BUNDLE" ]; then
    echo "   删除旧集成包: openclaw-bundle-arm64.tar.gz.bin"
    rm -f "$OLD_BUNDLE"
fi

# 删除旧的OpenClaw tar资产（已迁移到目录方式）
OLD_OPENCLAW_TAR="$ASSETS_DIR/openclaw-arm64.tar.gz.bin"
if [ -f "$OLD_OPENCLAW_TAR" ]; then
    echo "   删除旧OpenClaw tar: openclaw-arm64.tar.gz.bin"
    rm -f "$OLD_OPENCLAW_TAR"
fi

# 生成用于恢复执行权限的清单
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
echo "   executable-manifest.json 生成完成"

echo ""
echo "============================================"
echo "  完成!"
echo "============================================"
echo ""
echo "jniLibs:"
ls -lh "$JNILIBS_DIR/"
echo ""
echo "assets:"
ls -lh "$ASSETS_DIR/"
echo ""
echo "总assets大小: $(du -sh "$ASSETS_DIR/" | cut -f1)"
echo ""
echo "下一步: 在Android Studio中构建"
