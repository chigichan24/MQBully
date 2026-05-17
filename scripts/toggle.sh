#!/usr/bin/env bash
set -euo pipefail

PKG="${PKG:-com.chigichan24.messagequeuebully}"
ACTION="${1:-status}"

case "$ACTION" in
  new)
    adb shell am compat enable USE_NEW_MESSAGEQUEUE "$PKG"
    adb shell am force-stop "$PKG"
    echo "→ USE_NEW_MESSAGEQUEUE ENABLED. App stopped; relaunch from launcher."
    ;;
  old)
    adb shell am compat disable USE_NEW_MESSAGEQUEUE "$PKG"
    adb shell am force-stop "$PKG"
    echo "→ USE_NEW_MESSAGEQUEUE DISABLED. App stopped; relaunch from launcher."
    ;;
  reset)
    adb shell am compat reset USE_NEW_MESSAGEQUEUE "$PKG" || \
      adb shell am compat reset-all "$PKG"
    adb shell am force-stop "$PKG"
    echo "→ Compat reset. App stopped; relaunch from launcher."
    ;;
  status)
    echo "=== am compat enabled changes for $PKG ==="
    adb shell dumpsys platform_compat | awk -v pkg="$PKG" '
      /ChangeId\(421623328/ { print }
      $0 ~ pkg { print }
    '
    ;;
  *)
    echo "Usage: $0 {new|old|reset|status}"
    exit 2
    ;;
esac
