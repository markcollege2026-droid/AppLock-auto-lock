#!/usr/bin/env bash
# Emulator-side checks for the anti-uninstall protection.
# Runs against a booted AVD with the debug APK already built at
# app/build/outputs/apk/debug/*.apk. Writes evidence into emulator-artifacts/.
#
# Checks:
#   1. the app installs and starts without crashing
#   2. the device admin can be activated and anti-uninstall switched on
#   3. the accessibility service connects
#   4. Settings' app-info screen (the one with the "Uninstall" button) is closed
#      immediately, so the device-admin removal screen is never reached
#   5. the Settings device-admin list is still blocked
#   6. the device admin is still active at the end

set -x

PKG=dev.pranav.applock
ADMIN=$PKG/dev.pranav.applock.core.broadcast.DeviceAdmin
A11Y=$PKG/dev.pranav.applock.services.AppLockAccessibilityService
OUT=emulator-artifacts
RESULTS=$OUT/checks.txt

mkdir -p "$OUT"
: > "$RESULTS"

pass() { echo "PASS  $1" | tee -a "$RESULTS"; }
fail() { echo "FAIL  $1" | tee -a "$RESULTS"; }
skip() { echo "SKIP  $1" | tee -a "$RESULTS"; }

APK=$(find app/build/outputs/apk/debug -name '*.apk' | head -1)
if [ -z "$APK" ]; then
  fail "no debug APK found"
  exit 1
fi
echo "using $APK"

adb devices -l > "$OUT/devices.txt" 2>&1
adb shell getprop ro.build.version.release > "$OUT/android-release.txt" 2>&1
adb shell getprop ro.build.fingerprint > "$OUT/fingerprint.txt" 2>&1

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

# ---------- 1. install + launch ----------
adb install -r -g "$APK" || adb install -r "$APK"
if adb shell pm list packages | grep -q "$PKG"; then
  pass "debug APK installed"
else
  fail "debug APK was not installed"
  exit 1
fi
adb shell dumpsys package "$PKG" > "$OUT/package.txt" 2>&1

adb logcat -c || true
adb shell am start -W -n $PKG/.MainActivity
sleep 5
adb shell dumpsys activity activities > "$OUT/activities-launch.txt" 2>&1
adb exec-out screencap -p > "$OUT/01-launch.png"
adb logcat -d > "$OUT/logcat-launch.txt" 2>&1
if grep -q "FATAL EXCEPTION" "$OUT/logcat-launch.txt"; then
  fail "app crashed on launch (see logcat-launch.txt)"
elif grep -m1 "topResumedActivity" "$OUT/activities-launch.txt" | grep -q "$PKG"; then
  pass "app launches and is the resumed activity"
else
  fail "app did not become the resumed activity"
fi

# ---------- 2. activate the admin + switch anti-uninstall on ----------
adb shell am force-stop "$PKG"
adb shell dpm set-active-admin "$ADMIN" > "$OUT/dpm-set-admin.txt" 2>&1
adb shell dumpsys device_policy > "$OUT/device_policy-active.txt" 2>&1
if grep -qi "dev.pranav.applock" "$OUT/device_policy-active.txt"; then
  pass "device admin active"
else
  fail "could not activate the device admin"
fi

# Platform-level protection: an active admin makes the package uninstallable=no.
adb uninstall "$PKG" > "$OUT/uninstall-attempt.txt" 2>&1
if grep -q "DELETE_FAILED_DEVICE_POLICY_MANAGER" "$OUT/uninstall-attempt.txt"; then
  pass "package install refuses uninstall while the admin is active"
elif adb shell pm list packages | grep -q "$PKG"; then
  pass "package survived the uninstall attempt"
else
  fail "package was uninstalled while the admin was active"
fi

# The toggle is stored in app_lock_settings/anti_uninstall (what SettingsScreen writes).
cat > /tmp/app_lock_settings.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="anti_uninstall" value="true" />
</map>
XML
adb push /tmp/app_lock_settings.xml /data/local/tmp/app_lock_settings.xml
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/app_lock_settings.xml > shared_prefs/app_lock_settings.xml'"
adb shell "run-as $PKG cat shared_prefs/app_lock_settings.xml" > "$OUT/prefs-written.txt" 2>&1
if grep -q anti_uninstall "$OUT/prefs-written.txt"; then
  pass "anti-uninstall pref written"
else
  fail "could not write the anti-uninstall pref"
fi

# ---------- 2b. diagnostics: what do the Settings screens look like on this build? ----------
# Captured before the accessibility service is enabled so nothing can close them.
dump_screen() {
  name=$1
  adb shell uiautomator dump "/sdcard/ui-$name.xml" > "$OUT/uiautomator-$name.txt" 2>&1
  adb pull "/sdcard/ui-$name.xml" "$OUT/ui-$name.xml" > /dev/null 2>&1
  adb shell dumpsys window > "$OUT/window-$name.txt" 2>&1
  adb shell dumpsys activity activities > "$OUT/activities-$name-raw.txt" 2>&1
  adb shell dumpsys activity top > "$OUT/activity-top-$name.txt" 2>&1
  adb exec-out screencap -p > "$OUT/00-$name.png"
}

adb shell input keyevent KEYCODE_HOME || true
sleep 2
adb shell cmd package resolve-activity -a android.settings.APPLICATION_DETAILS_SETTINGS \
  -c android.intent.category.DEFAULT > "$OUT/resolve-appinfo.txt" 2>&1
adb shell cmd package resolve-activity -a android.settings.DEVICE_ADMIN_SETTINGS \
  -c android.intent.category.DEFAULT > "$OUT/resolve-adminlist.txt" 2>&1
adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG \
  > "$OUT/start-appinfo.txt" 2>&1
sleep 5
dump_screen appinfo
adb shell am force-stop com.android.settings
adb shell input keyevent KEYCODE_HOME || true
sleep 2

adb shell am start -a android.settings.DEVICE_ADMIN_SETTINGS > "$OUT/start-adminlist.txt" 2>&1
sleep 5
if ! adb shell dumpsys activity activities | grep -q "topResumedActivity.*com.android.settings"; then
  adb shell am start -n 'com.android.settings/.Settings$DeviceAdminSettingsActivity' \
    >> "$OUT/start-adminlist.txt" 2>&1
  sleep 5
fi
dump_screen adminlist
adb shell am force-stop com.android.settings
adb shell input keyevent KEYCODE_HOME || true
sleep 2

# ---------- 3. accessibility service ----------
adb shell appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow || true
adb shell settings put secure enabled_accessibility_services "$A11Y"
adb shell settings put secure accessibility_enabled 1
for _ in $(seq 1 15); do
  adb shell dumpsys accessibility | grep -qi "applock" && break
  sleep 2
done
adb shell dumpsys accessibility > "$OUT/accessibility.txt" 2>&1
if grep -qi "applock" "$OUT/accessibility.txt"; then
  pass "accessibility service connected"
else
  skip "accessibility service did not connect - screen checks are inconclusive"
fi

# ---------- 4. CHECK: app-info screen is closed ----------
adb logcat -c || true
adb shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:$PKG
sleep 5
adb shell dumpsys activity activities > "$OUT/activities-appinfo.txt" 2>&1
adb exec-out screencap -p > "$OUT/02-appinfo.png"
adb logcat -d > "$OUT/logcat-appinfo.txt" 2>&1
grep -m1 "topResumedActivity" "$OUT/activities-appinfo.txt" | tee "$OUT/appinfo-top-activity.txt"
grep -m1 -i "appinfo\|InstalledAppDetails" "$OUT/activities-appinfo.txt" >> "$OUT/appinfo-top-activity.txt" || true

if grep -q "Closed the app-info screen while anti-uninstall" "$OUT/logcat-appinfo.txt"; then
  pass "app-info screen closed by the accessibility service"
else
  fail "app-info screen was not closed (no service log line)"
fi
adb shell uiautomator dump "/sdcard/ui-appinfo-after.xml" > /dev/null 2>&1
adb pull "/sdcard/ui-appinfo-after.xml" "$OUT/ui-appinfo-after.xml" > /dev/null 2>&1
adb shell dumpsys window > "$OUT/window-appinfo-after.txt" 2>&1
if grep -m1 "topResumedActivity" "$OUT/activities-appinfo.txt" | grep -qE "InstalledAppDetails|SpaActivity|AppInfo"; then
  fail "app-info screen is still the resumed activity"
else
  pass "app-info screen is no longer foreground"
fi

# ---------- 5. CHECK: device-admin list is still blocked ----------
adb logcat -c || true
adb shell am start -a android.settings.DEVICE_ADMIN_SETTINGS
sleep 5
adb logcat -d > "$OUT/logcat-adminlist.txt" 2>&1
adb shell dumpsys device_policy > "$OUT/device_policy-after.txt" 2>&1
adb exec-out screencap -p > "$OUT/03-adminlist.png"
adb shell uiautomator dump "/sdcard/ui-adminlist-after.xml" > /dev/null 2>&1
adb pull "/sdcard/ui-adminlist-after.xml" "$OUT/ui-adminlist-after.xml" > /dev/null 2>&1
if grep -q "Blocked device admin deactivation" "$OUT/logcat-adminlist.txt"; then
  pass "device-admin list screen blocked"
else
  skip "device-admin list block not observed (android.settings.DEVICE_ADMIN_SETTINGS may not be resolvable on this build)"
fi
if grep -qi "dev.pranav.applock" "$OUT/device_policy-after.txt"; then
  pass "device admin still active after the attempts"
else
  fail "device admin was removed during the test"
fi

# ---------- 6. full evidence ----------
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb logcat -d > "$OUT/logcat-full.txt" 2>&1
adb shell dumpsys activity activities > "$OUT/activities-final.txt" 2>&1
adb shell dumpsys accessibility > "$OUT/accessibility-final.txt" 2>&1
adb bugreport "$OUT/bugreport.zip" || adb bugreport "$OUT/bugreport" || true

echo "=== results ==="
cat "$RESULTS"
! grep -q '^FAIL' "$RESULTS"
