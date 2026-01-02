#!/bin/bash
set -e

# =============================================================================
# Shadow App Release Build Script
# =============================================================================
#
# This script builds signed release APKs for both phone and watch apps.
# The watch APK is automatically embedded in the phone app's assets.
#
# Required environment variables:
#   SHADOW_KEYSTORE_PATH     - Path to the .keystore or .jks file
#   SHADOW_KEYSTORE_PASSWORD - Password for the keystore
#   SHADOW_KEY_ALIAS         - Key alias name
#   SHADOW_KEY_PASSWORD      - Password for the key
#
# Usage:
#   export SHADOW_KEYSTORE_PATH="/path/to/your.keystore"
#   export SHADOW_KEYSTORE_PASSWORD="your_keystore_password"
#   export SHADOW_KEY_ALIAS="your_key_alias"
#   export SHADOW_KEY_PASSWORD="your_key_password"
#   ./build.sh
#
# Or create a .env file (git-ignored) and source it:
#   source .env && ./build.sh
#
# =============================================================================

echo "=============================================="
echo "  Shadow App Release Build"
echo "=============================================="
echo ""

# Check required environment variables
check_env() {
    if [ -z "${!1}" ]; then
        echo "ERROR: $1 environment variable is not set"
        echo ""
        echo "Required environment variables:"
        echo "  SHADOW_KEYSTORE_PATH     - Path to keystore file"
        echo "  SHADOW_KEYSTORE_PASSWORD - Keystore password"
        echo "  SHADOW_KEY_ALIAS         - Key alias"
        echo "  SHADOW_KEY_PASSWORD      - Key password"
        exit 1
    fi
}

check_env SHADOW_KEYSTORE_PATH
check_env SHADOW_KEYSTORE_PASSWORD
check_env SHADOW_KEY_ALIAS
check_env SHADOW_KEY_PASSWORD

# Verify keystore exists
if [ ! -f "$SHADOW_KEYSTORE_PATH" ]; then
    echo "ERROR: Keystore file not found: $SHADOW_KEYSTORE_PATH"
    exit 1
fi

echo "Keystore: $SHADOW_KEYSTORE_PATH"
echo "Key Alias: $SHADOW_KEY_ALIAS"
echo ""

# Clean previous builds
echo "[1/5] Cleaning previous builds..."
./gradlew clean -q

# Build wear release APK
echo "[2/5] Building Wear OS release APK..."
./gradlew :wear:assembleRelease -q

# Check wear APK was built
WEAR_APK="wear/build/outputs/apk/release/wear-release.apk"
if [ ! -f "$WEAR_APK" ]; then
    echo "ERROR: Wear APK not found at $WEAR_APK"
    exit 1
fi

WEAR_SIZE=$(du -h "$WEAR_APK" | cut -f1)
echo "    Wear APK built: $WEAR_SIZE"

# Copy wear APK to assets
echo "[3/5] Embedding Wear APK in phone app assets..."
mkdir -p src/main/assets
cp "$WEAR_APK" src/main/assets/wear-release.apk
echo "    Copied to: src/main/assets/wear-release.apk"

# Build phone release APK
echo "[4/5] Building Phone release APK..."
./gradlew assembleRelease -q

# Check phone APK was built
PHONE_APK="build2/outputs/apk/release/Test DPC-release.apk"
if [ ! -f "$PHONE_APK" ]; then
    # Try alternative name
    PHONE_APK=$(find build2/outputs/apk/release -name "*.apk" -type f 2>/dev/null | head -1)
fi

if [ -z "$PHONE_APK" ] || [ ! -f "$PHONE_APK" ]; then
    echo "ERROR: Phone APK not found"
    exit 1
fi

PHONE_SIZE=$(du -h "$PHONE_APK" | cut -f1)
echo "    Phone APK built: $PHONE_SIZE"

# Create output directory
echo "[5/5] Copying APKs to output directory..."
mkdir -p release-output
cp "$WEAR_APK" release-output/shadow-watch.apk
cp "$PHONE_APK" release-output/shadow-phone.apk

echo ""
echo "=============================================="
echo "  Build Complete!"
echo "=============================================="
echo ""
echo "Output files:"
echo "  release-output/shadow-phone.apk  ($PHONE_SIZE)"
echo "  release-output/shadow-watch.apk  ($WEAR_SIZE)"
echo ""
echo "Install commands:"
echo "  adb install -r release-output/shadow-phone.apk"
echo "  adb -s <watch-ip>:5555 install -r release-output/shadow-watch.apk"
echo ""
