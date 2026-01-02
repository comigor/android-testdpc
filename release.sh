#!/bin/bash
set -e

# =============================================================================
# Shadow App Release Upload Script
# =============================================================================
# Run ./build.sh first to generate signed APKs, then run this to upload.
# =============================================================================

# variables
versionMajor=$(grep "versionMajor =" build.gradle | sed -E "s/^.*versionMajor = ([0-9]+).*$/\1/g")
versionMinor=$(grep "versionMinor =" build.gradle | sed -E "s/^.*versionMinor = ([0-9]+).*$/\1/g")
versionBuild=$(grep "versionBuild =" build.gradle | sed -E "s/^.*versionBuild = ([0-9]+).*$/\1/g")
versionFull="$versionMajor.$versionMinor.$versionBuild"

echo "Version: $versionFull"

# Check if release APKs exist
PHONE_APK="release-output/shadow-phone.apk"
WEAR_APK="release-output/shadow-watch.apk"

if [ ! -f "$PHONE_APK" ]; then
    echo "ERROR: Phone APK not found at $PHONE_APK"
    echo "Run ./build.sh first to generate signed APKs"
    exit 1
fi

if [ ! -f "$WEAR_APK" ]; then
    echo "ERROR: Wear APK not found at $WEAR_APK"
    echo "Run ./build.sh first to generate signed APKs"
    exit 1
fi

echo "Phone APK: $(du -h "$PHONE_APK" | cut -f1)"
echo "Wear APK:  $(du -h "$WEAR_APK" | cut -f1)"

# create temp folders
mkdir -p /tmp/shadowfiles
rm -rf /tmp/shadowfiles/*

# copy phone APK (latest + versioned)
cp "$PHONE_APK" "/tmp/shadowfiles/latest.apk"
cp "$PHONE_APK" "/tmp/shadowfiles/$versionFull.apk"

# copy wear APK (latest + versioned)
cp "$WEAR_APK" "/tmp/shadowfiles/latest-watch.apk"
cp "$WEAR_APK" "/tmp/shadowfiles/$versionFull-watch.apk"

# generate checksum for phone APK (for QR provisioning)
checksum=$(openssl dgst -binary -sha256 "/tmp/shadowfiles/$versionFull.apk" | openssl base64 | tr "+/" "-_" | tr -d "=")

qrcodejson=$(
cat <<END_HEREDOC
  {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "dev.borges.shadow/com.afwsamples.testdpc.DeviceAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "$checksum",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://public.borges.dev/shadow/$versionFull.apk"
  }
END_HEREDOC
)

echo ""
echo "QR Code JSON:"
echo "$qrcodejson" | jq

# generate qrcode
qrencode -o "/tmp/shadowfiles/latest.png" "$qrcodejson"
cp "/tmp/shadowfiles/latest.png" "/tmp/shadowfiles/$versionFull.png"

echo ""
echo "Files to upload:"
ls -lh /tmp/shadowfiles/

echo ""
echo "Uploading to server..."
scp /tmp/shadowfiles/* brick:/DATA/AppData/static-file-server/shadow/

echo ""
echo "Done! Files available at:"
echo "  Phone: https://public.borges.dev/shadow/latest.apk"
echo "  Phone: https://public.borges.dev/shadow/$versionFull.apk"
echo "  Watch: https://public.borges.dev/shadow/latest-watch.apk"
echo "  Watch: https://public.borges.dev/shadow/$versionFull-watch.apk"
echo "  QR:    https://public.borges.dev/shadow/latest.png"
