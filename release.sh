#!/bin/bash
# manually generate signed apk from android studio

# variables
versionMajor=$(cat build.gradle | grep "versionMajor =" | sed -E "s/^.*versionMajor = ([0-9]+).*$/\1/g")
versionMinor=$(cat build.gradle | grep "versionMinor =" | sed -E "s/^.*versionMinor = ([0-9]+).*$/\1/g")
versionBuild=$(cat build.gradle | grep "versionBuild =" | sed -E "s/^.*versionBuild = ([0-9]+).*$/\1/g")
versionFull=$(echo "$versionMajor.$versionMinor.$versionBuild")

# create temp folders
mkdir -p /tmp/shadowfiles
rm -rf /tmp/shadowfiles/*

# move apk
cp "release/Test DPC-release.apk" "/tmp/shadowfiles/latest.apk"
cp "/tmp/shadowfiles/latest.apk" "/tmp/shadowfiles/$versionFull.apk"

# generate checksum
checksum=$(cat "/tmp/shadowfiles/$versionFull.apk" | openssl dgst -binary -sha256 | openssl base64 | tr "+/" "-_" | tr -d "=")

qrcodejson=$(
cat <<END_HEREDOC
  {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "dev.borges.shadow/com.afwsamples.testdpc.DeviceAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM": "$checksum",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://public.borges.dev/shadow/$versionFull.apk"
  }
END_HEREDOC
)

echo "$qrcodejson" | jq

# generate qrcode
qrencode -o "/tmp/shadowfiles/latest.png" "$qrcodejson"
cp "/tmp/shadowfiles/latest.png" "/tmp/shadowfiles/$versionFull.png"

# copy to server
scp /tmp/shadowfiles/* brick:/DATA/AppData/static-file-server/shadow/
