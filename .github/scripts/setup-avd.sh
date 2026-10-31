#!/usr/bin/env bash
# Installs the emulator packages and creates the AVD. Fails loudly.
set -euo pipefail
set -x

API_LEVEL=${API_LEVEL:-34}
SYSTEM_IMAGE=${SYSTEM_IMAGE:-system-images;android-34;google_apis;x86_64}
AVD_NAME=${AVD_NAME:-ci34}

echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"
echo "ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<unset>}"

SDKMANAGER=$(command -v sdkmanager || true)
if [ -z "$SDKMANAGER" ]; then
  SDKMANAGER=$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}" -maxdepth 4 -name sdkmanager -type f 2>/dev/null | head -1)
fi
if [ -z "$SDKMANAGER" ]; then
  echo "sdkmanager not found" >&2
  exit 1
fi
AVDMANAGER="$(dirname "$SDKMANAGER")/avdmanager"
echo "SDKMANAGER=$SDKMANAGER"
echo "AVDMANAGER=$AVDMANAGER"

export PATH="$(dirname "$SDKMANAGER"):$PATH"

"$SDKMANAGER" --version
yes | "$SDKMANAGER" --licenses > /dev/null || true
"$SDKMANAGER" --install "platform-tools" "emulator" "$SYSTEM_IMAGE"

if [ ! -x "$AVDMANAGER" ]; then
  echo "avdmanager missing at $AVDMANAGER" >&2
  exit 1
fi
"$AVDMANAGER" list device | head -40
echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_6 --force

ls -la "$HOME/.android/avd"
cat "$HOME/.android/avd/$AVD_NAME.ini"
"${ANDROID_HOME}/emulator/emulator" -list-avds
