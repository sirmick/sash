#!/bin/bash -e

[ $# -ne 1 ] || [ ! -f $1 ] && echo "Usage: $0 {version.aab}" && exit 1

passage ls | grep hwfido2provider.key >/dev/null
if [ $? -ne 0 ]; then
  echo "Pass keystore/hwfido2provider.key not found. Aborting."
  exit 1
fi

export KS="$HOME/.passage/store/keystore/hwfido2provider.jks"
export KS_PASS=$(passage keystore/hwfido2provider.key)
export KEY_ALIAS="hwfido2provider"
export AAB="$1"

echo "[+] Pass copied"

./gradlew bundletoolBuildApks

unzip -o universal.apks
mv universal.apk hwfido2provider.apk
mv app.apks hwfido2provider.apks
echo "[+] Done"
