#!/usr/bin/env bash
# Run a single attack on the connected Android 17+ device under both the
# legacy (synchronized) MessageQueue and the new lock-free DeliQueue, and
# print the MQBully logcat lines from each pass.
#
# Usage:
#   ./scripts/run-attack.sh <attack-number> [wait-seconds]
#
# Examples:
#   ./scripts/run-attack.sh 14       # tombstone OOM
#   ./scripts/run-attack.sh 9 8      # multi-producer throughput, wait 8s

set -euo pipefail

PKG="${PKG:-com.chigichan24.messagequeuebully}"
ATTACK="${1:?usage: run-attack.sh <attack-number> [wait-seconds]}"
WAIT="${2:-6}"

require() { command -v "$1" >/dev/null || { echo "missing: $1"; exit 1; }; }
require adb

run_pass() {
  local label="$1"
  echo
  echo "================ $label (attack=$ATTACK) ================"
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" --es attack "$ATTACK" > /dev/null
  sleep "$WAIT"
  adb logcat -d -s MQBully:V
}

# Legacy: reset any override, so the targetSdk gate (37) leaves us on the old impl.
adb shell am compat reset USE_NEW_MESSAGEQUEUE "$PKG" > /dev/null 2>&1 || true
run_pass "LEGACY"

# Lock-free: force the new impl on, even though targetSdk is 36.
adb shell am compat enable USE_NEW_MESSAGEQUEUE "$PKG" > /dev/null
run_pass "LOCK-FREE"

# Restore default to avoid confusing the next run.
adb shell am compat reset USE_NEW_MESSAGEQUEUE "$PKG" > /dev/null 2>&1 || true
adb shell am force-stop "$PKG" > /dev/null
echo
echo "Done. Override reset to default."
