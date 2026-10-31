#!/usr/bin/env bash
# Boots the AVD headlessly and waits for sys.boot_completed.
set -euo pipefail
set -x

AVD_NAME=${AVD_NAME:-ci34}
EMULATOR="${ANDROID_HOME}/emulator/emulator"
LOG=${EMULATOR_LOG:-/tmp/emulator.log}

echo "HOME=$HOME"
env | grep -i '^ANDROID' || true
ls -la "$HOME/.android/avd" || true
find / -maxdepth 6 -name "${AVD_NAME}.ini" 2>/dev/null | head -5 || true

"$EMULATOR" -avd "$AVD_NAME" \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swiftshader_indirect -camera-back none -camera-front none \
  -accel on > "$LOG" 2>&1 &
EMULATOR_PID=$!

adb start-server

deadline=$((SECONDS + 600))
booted=0
while [ $SECONDS -lt $deadline ]; do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "emulator process exited early" >&2
    tail -100 "$LOG" >&2 || true
    exit 1
  fi
  if adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q 1; then
    booted=1
    break
  fi
  sleep 5
done

if [ "$booted" -ne 1 ]; then
  echo "emulator did not boot within 600s" >&2
  tail -100 "$LOG" >&2 || true
  exit 1
fi

adb wait-for-device
for _ in $(seq 1 20); do
  [ "$(adb shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 3
done
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true
adb devices -l
tail -30 "$LOG" || true
