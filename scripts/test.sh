#!/usr/bin/env bash
#
# Everything that can be checked without a device.
#
# Which is less than it sounds and more than it was. The claims this project
# makes about isolation are made by the kernel and can only be measured on a
# phone — but the rules feeding it are ordinary code, and those are here: the
# catalogue and its minting, the fence, and the vault.
#
#   ./test.sh            run everything
#   ./test.sh catalogue  just the fast one
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLCHAIN="${TOOLCHAIN:-$HOME/.local/wash-toolchain/android}"
GRADLE="$TOOLCHAIN/gradle/bin/gradle"
export JAVA_HOME="$TOOLCHAIN/jdk"
export ANDROID_HOME="$TOOLCHAIN/sdk"

# --offline by default: these tests exist to be run constantly, and a
# dependency resolution over the network is most of the wall clock.
GRADLE_ARGS="${GRADLE_ARGS:---offline --console=plain}"

ONLY="${1:-all}"
FAILED=()

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAILED+=("$1"); }

want() { [ "$ONLY" = all ] || [ "$ONLY" = "$1" ]; }

if want catalogue; then
  say "Catalogue — entries, minting, and the generated output"
  if python3 "$ROOT/scripts/test_mint.py" 2>&1 | tail -3; then
    ok "catalogue"
  else
    bad "catalogue"
  fi
fi

if want fence; then
  say "Fence — the origin rule, off-device"
  # One flavour is enough: the test source set is shared, and the rule under
  # test has no per-site anything in it.
  if (cd "$ROOT/probe/loader" && "$GRADLE" $GRADLE_ARGS -q :app:testChaseDebugUnitTest); then
    ok "fence"
  else
    bad "fence"
  fi
fi

if want vault; then
  say "Vault — storage, crypto, conflict resolution"
  if (cd "$ROOT/latch" && "$GRADLE" $GRADLE_ARGS -q :app:testDebugUnitTest); then
    ok "vault"
  else
    bad "vault"
  fi
fi

echo
if [ ${#FAILED[@]} -eq 0 ]; then
  printf '\033[32mall green\033[0m\n'
else
  printf '\033[31mfailed: %s\033[0m\n' "${FAILED[*]}"
  exit 1
fi
