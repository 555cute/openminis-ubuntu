#!/usr/bin/env bash
#
# Prepare Android sandbox assets for OpenMinis Ubuntu Agent:
#   1. Download Ubuntu base arm64 rootfs and slim it
#   2. Download PRoot aarch64 static binary from Termux packages
#   3. Place both into src/android/app/src/main/assets/
#
# Usage: ./scripts/prepare_android_sandbox.sh
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$PROJECT_ROOT/src/android/app/src/main/assets"

# Ubuntu base arm64 — official cdimage base.
# 24.04 LTS (noble). ~29 MB compressed raw; after slim ~25 MB; extract ~80–100 MB.
UBUNTU_VERSION="24.04.3"
UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_VERSION}-base-arm64.tar.gz"

# Termux proot package — aarch64 static binary.
PROOT_VERSION="5.1.107.87"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"

mkdir -p "$ASSETS_DIR"

ROOTFS_FILE="$ASSETS_DIR/ubuntu-base-rootfs.tar.gz"
PROOT_FILE="$ASSETS_DIR/proot-aarch64"

# --- Ubuntu rootfs ---
if [ -f "$ROOTFS_FILE" ]; then
    echo "✓ Ubuntu rootfs already exists: $ROOTFS_FILE"
else
    echo "Downloading Ubuntu ${UBUNTU_VERSION} arm64 base rootfs..."
    TMP_DL="$(mktemp)"
    curl -fSL --retry 3 --retry-delay 2 -o "$TMP_DL" "$UBUNTU_URL"

    WORK="$(mktemp -d)"
    echo "Slimming rootfs (drop man/doc/locale noise)..."
    tar -xzf "$TMP_DL" -C "$WORK"
    rm -rf \
      "$WORK"/usr/share/man \
      "$WORK"/usr/share/doc \
      "$WORK"/usr/share/info \
      "$WORK"/usr/share/locale \
      "$WORK"/usr/share/lintian \
      "$WORK"/var/cache/apt 2>/dev/null || true
    rm -rf "$WORK"/var/lib/apt/lists/* 2>/dev/null || true

    # Seed apt sources so first `apt-get update` works on arm64 ports.
    mkdir -p "$WORK"/etc/apt
    cat >"$WORK"/etc/apt/sources.list <<'EOF'
deb http://ports.ubuntu.com/ubuntu-ports noble main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-updates main restricted universe multiverse
deb http://ports.ubuntu.com/ubuntu-ports noble-security main restricted universe multiverse
EOF

    # Ensure /var/minis skeleton exists (also recreated by RootfsManager).
    mkdir -p "$WORK"/var/minis/attachments \
             "$WORK"/var/minis/offloads \
             "$WORK"/var/minis/workspace \
             "$WORK"/var/minis/skills \
             "$WORK"/var/minis/memory \
             "$WORK"/var/minis/shared \
             "$WORK"/var/minis/mounts \
             "$WORK"/var/minis/pi
    mkdir -p "$WORK"/opt/bin
    touch "$WORK"/root/.bashrc

    # Disable services that cannot run under PRoot (no real init/systemd).
    mkdir -p "$WORK"/etc/profile.d
    cat >"$WORK"/etc/profile.d/00-ubuntu-proot.sh <<'EOF'
# OpenMinis Ubuntu Agent — PRoot guest profile
export DEBIAN_FRONTEND=noninteractive
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin"
EOF

    tar -czf "$ROOTFS_FILE" -C "$WORK" .
    rm -rf "$WORK" "$TMP_DL"
    echo "✓ Prepared: $ROOTFS_FILE ($(du -h "$ROOTFS_FILE" | cut -f1))"
fi

# --- PRoot binary ---
if [ -f "$PROOT_FILE" ]; then
    echo "✓ PRoot binary already exists: $PROOT_FILE"
else
    echo "Downloading PRoot ${PROOT_VERSION} aarch64 from Termux..."

    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    DEB_FILE="$TMPDIR/proot.deb"
    curl -fSL -o "$DEB_FILE" "$PROOT_DEB_URL"

    cd "$TMPDIR"
    ar x "$DEB_FILE"

    if [ -f "data.tar.xz" ]; then
        tar xf data.tar.xz
    elif [ -f "data.tar.gz" ]; then
        tar xzf data.tar.gz
    elif [ -f "data.tar.zst" ]; then
        zstd -d data.tar.zst -o data.tar
        tar xf data.tar
    else
        echo "Error: Could not find data archive in .deb"
        ls -la "$TMPDIR"
        exit 1
    fi

    PROOT_BIN=$(find "$TMPDIR" -name "proot" -type f | head -1)
    if [ -z "$PROOT_BIN" ]; then
        echo "Error: Could not find proot binary in extracted .deb"
        find "$TMPDIR" -type f
        exit 1
    fi

    cp "$PROOT_BIN" "$PROOT_FILE"
    chmod +x "$PROOT_FILE"
    cd "$PROJECT_ROOT"

    echo "✓ Extracted PRoot binary: $PROOT_FILE ($(du -h "$PROOT_FILE" | cut -f1))"
fi

# Also install as a real JNI lib so PackageManager extracts an executable
# into nativeLibraryDir on install. Writing there at runtime fails with
# EACCES on modern Android (read-only APK lib mount).
JNI_DIR="$PROJECT_ROOT/src/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"
if [ -f "$PROOT_FILE" ]; then
    cp -f "$PROOT_FILE" "$JNI_DIR/libproot.so"
    chmod 755 "$JNI_DIR/libproot.so"
    echo "✓ Installed JNI lib: $JNI_DIR/libproot.so"
fi


echo ""
echo "Assets ready in: $ASSETS_DIR"
ls -lh "$ROOTFS_FILE" "$PROOT_FILE"
