#!/usr/bin/env bash
#
# What is actually on this device.
#
# Run it on a fresh image to establish the baseline, and again after
# provisioning to see exactly what was added. The claim the whole project makes
# is about what is *absent*, so it is worth being able to check.
#
set -uo pipefail

TOOLCHAIN="${TOOLCHAIN:-$HOME/.local/wash-toolchain/android}"
ADB="$TOOLCHAIN/sdk/platform-tools/adb"

# A killed emulator leaves an "offline" entry behind and then every adb command
# fails with "more than one device".
"$ADB" devices | awk '$2=="offline"{print $1}' | while read -r s; do
  "$ADB" disconnect "$s" >/dev/null 2>&1
done
SERIAL="$("$ADB" devices | awk '$2=="device"{print $1; exit}')"
[ -n "$SERIAL" ] || { echo "no device"; exit 1; }
export ANDROID_SERIAL="$SERIAL"

get()  { "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'; }
head2() { printf '\n\033[1m%s\033[0m\n' "$1"; }

head2 "Device"
printf '  %-22s %s\n' \
  "serial"      "$SERIAL" \
  "model"       "$(get ro.product.model)" \
  "android"     "$(get ro.build.version.release) (sdk $(get ro.build.version.sdk))" \
  "build"       "$(get ro.build.fingerprint)" \
  "abi"         "$(get ro.product.cpu.abi)"

head2 "Security posture"
printf '  %-22s %s\n' \
  "verified boot"  "$(get ro.boot.verifiedbootstate)" \
  "keystore"       "$(get ro.boot.vendor.apex.com.android.hardware.keymint)" \
  "screen lock"    "$("$ADB" shell locksettings get-disabled 2>/dev/null | tr -d '\r')"
for f in android.hardware.strongbox_keystore android.hardware.nfc \
         android.hardware.usb.host android.hardware.fingerprint; do
  present="$("$ADB" shell pm list features 2>/dev/null | grep -c "feature:$f\$" | tr -d '\r')"
  printf '  %-22s %s\n' "${f##*.}" "$([ "$present" = 1 ] && echo declared || echo absent)"
done

head2 "Web engine"
printf '  %-22s %s\n' \
  "webview provider" "$("$ADB" shell dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package' | sed 's/.*: //' | tr -d '\r')"

head2 "Providers we care about"
printf '  %-22s %s\n' \
  "autofill"   "$("$ADB" shell settings get secure autofill_service | tr -d '\r')" \
  "credential" "$("$ADB" shell settings get secure credential_service | tr -d '\r')" \
  "home"       "$("$ADB" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1 | tr -d '\r')" \
  "browser"    "$("$ADB" shell cmd package resolve-activity --brief -a android.intent.action.VIEW -d 'https://example.com' 2>/dev/null | tail -1 | tr -d '\r')"

head2 "Installed, not part of the system image"
COUNT=0
while read -r p; do
  p="${p#package:}"; p="$(echo "$p" | tr -d '\r')"
  [ -n "$p" ] || continue
  size="$("$ADB" shell pm path "$p" 2>/dev/null | head -1 | sed 's/package://' | tr -d '\r')"
  bytes="$("$ADB" shell stat -c%s "$size" 2>/dev/null | tr -d '\r')"
  printf '  %-42s %s\n' "$p" "$([ -n "${bytes:-}" ] && echo "$((bytes/1024)) KB" || echo '')"
  COUNT=$((COUNT+1))
done < <("$ADB" shell pm list packages -3 2>/dev/null)
[ "$COUNT" = 0 ] && echo "  (none — this is a bare image)"

head2 "Google"
GMS=$("$ADB" shell pm list packages 2>/dev/null | grep -cE "com\.google\.android\.gms|com\.android\.vending" | tr -d '\r')
printf '  %-22s %s\n' "Play Services / Store" "$([ "$GMS" = 0 ] && echo absent || echo "$GMS package(s)")"
echo
