#!/usr/bin/env bash
#
# Provision a device with the whole system.
#
# Everything after the first boot is scriptable, and this is that script. What
# it cannot do is the five minutes before: flashing, the bootloader unlock and
# re-lock (two physical button presses, deliberately), and the setup wizard.
# See pane/docs/PACK.md.
#
#   ./provision.sh              build what is missing, then install
#   ./provision.sh --no-build   install what is already built
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-$HOME/.local/wash-toolchain/android}"
ADB="$TOOLCHAIN/sdk/platform-tools/adb"
GRADLE="JAVA_HOME=$TOOLCHAIN/jdk ANDROID_HOME=$TOOLCHAIN/sdk $TOOLCHAIN/gradle/bin/gradle --console=plain -q"

LATCH_PKG="s1m.hwfido2provider.debug"

# The site list is the catalogue, never a copy of it. It was a copy once, and
# it went stale the day the catalogue changed: this script then built three
# flavours that no longer existed and failed on the first cp.
SITES="$(cd "$ROOT/catalogue" && ls *.json | sed 's/\.json$//')"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
run() { eval "$@"; }

[ "${1:-}" = "--no-build" ] || {
  say "Building"
  # The catalogue first: it generates the loader's flavours and the manager's
  # list, so everything after this is downstream of it.
  run "python3 '$ROOT/scripts/mint.py'"
  # The engine. Large and built once; every site app borrows it at runtime.
  run "$GRADLE -p '$ROOT/pane/android' assembleDebug"
  # The vault. Needs the Syncthing binary, which is fetched not committed.
  run "make -C '$ROOT/latch' sync-binary >/dev/null"
  run "$GRADLE -p '$ROOT/latch' assembleDebug"
  # The site apps, one build variant each.
  run "$GRADLE -p '$ROOT/probe/loader' assembleDebug"
  # The manager carries the site apps as assets, so they must exist first.
  # Clearing first matters: a site removed from the catalogue would otherwise
  # stay in the assets and ship inside the manager forever, unlisted and
  # uninstallable, at full size.
  ASSETS="$ROOT/probe/manager/app/src/main/assets"
  rm -f "$ASSETS"/*.apk
  for s in $SITES; do
    cp "$ROOT/probe/loader/app/build/outputs/apk/$s/debug/app-$s-debug.apk" \
       "$ASSETS/$s.apk"
  done
  run "$GRADLE -p '$ROOT/probe/manager' assembleDebug"
}

say "Waiting for a device"
# A killed Cuttlefish leaves its adb entry behind as "offline", and every adb
# command then fails with "more than one device" -- including wait-for-device,
# which is the one you would reach for to diagnose it.
"$ADB" devices | awk '$2=="offline"{print $1}' | while read -r stale; do
  echo "  dropping stale device $stale"
  "$ADB" disconnect "$stale" >/dev/null 2>&1 || true
done
# An explicit ANDROID_SERIAL wins. Two devices attached is the normal case when
# a fresh one is booted to test against beside a long-lived one, and picking the
# first silently provisions whichever adb happens to list first -- which is the
# old one about half the time, and looks like the script having done nothing.
if [ -n "${ANDROID_SERIAL:-}" ]; then
  SERIAL="$ANDROID_SERIAL"
  "$ADB" devices | awk -v s="$SERIAL" '$1==s && $2=="device"{found=1} END{exit !found}' || {
    echo "ANDROID_SERIAL=$SERIAL is not an attached device"; exit 1; }
else
  SERIAL="$("$ADB" devices | awk '$2=="device"{print $1; exit}')"
  COUNT="$("$ADB" devices | awk '$2=="device"' | wc -l)"
  [ "$COUNT" -le 1 ] || echo "  $COUNT devices attached; using $SERIAL. Set ANDROID_SERIAL to choose."
fi
[ -n "$SERIAL" ] || { echo "no device attached"; exit 1; }
export ANDROID_SERIAL="$SERIAL"
echo "  using $SERIAL"
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell input swipe 360 1400 360 400 200 >/dev/null 2>&1 || true

say "Installing"
"$ADB" install -r "$ROOT/pane/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1
"$ADB" install -r "$ROOT/latch/app/build/outputs/apk/debug/app-debug.apk" | tail -1
"$ADB" install -r "$ROOT/probe/manager/app/build/outputs/apk/debug/app-debug.apk" | tail -1

say "Wiring latch in as the system's password and credential provider"
# Android routes both through Settings screens that need a human. Setting them
# directly is what makes provisioning a script rather than a checklist.
"$ADB" shell settings put secure autofill_service \
  "$LATCH_PKG/s1m.hwfido2provider.LatchAutofillService"
"$ADB" shell settings put secure credential_service \
  "$LATCH_PKG/s1m.hwfido2provider.ProviderService"
"$ADB" shell settings put secure credential_service_primary \
  "$LATCH_PKG/s1m.hwfido2provider.ProviderService"
# One-time "allow this app to install apps" consent, pre-granted.
"$ADB" shell appops set com.manager REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
"$ADB" shell setprop log.tag.latch VERBOSE  >/dev/null 2>&1 || true
"$ADB" shell setprop log.tag.loader VERBOSE >/dev/null 2>&1 || true

say "Installed"
printf '  engine    %s\n' "$("$ADB" shell pm path com.pane >/dev/null 2>&1 && echo present || echo MISSING)"
printf '  vault     %s\n' "$("$ADB" shell pm path $LATCH_PKG >/dev/null 2>&1 && echo present || echo MISSING)"
printf '  manager   %s\n' "$("$ADB" shell pm path com.manager >/dev/null 2>&1 && echo present || echo MISSING)"
printf '  autofill  %s\n' "$("$ADB" shell settings get secure autofill_service | tr -d '\r')"

cat <<'EOF'

Next, on the device:
  1. Sites      — install a site from the catalogue
  2. Passchain  — Passwords, create a vault, add a credential
  3. Open the site app; its login form fills from the vault

Nothing else is installed. No Play Services, no accounts, no engine per app.
EOF
