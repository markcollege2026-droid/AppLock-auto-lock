### What changed

Stops a fast tap from deactivating the device administrator during the moment Android shows the
device-admin removal screen.

- The accessibility service closes Settings' **App Lock app-info screen** (the one with the
  Uninstall button) as soon as it appears, so the removal screen is never reached on that route.
  Detection is content-based — it matches Settings' own "App info" / "Force stop" strings in the
  device language — so it works on Android 12–14, including the Compose app-info screen.
- `onDisableRequested()` adds a confirmation on the Settings → Device admin apps route.
- `setUninstallBlocked()` failures no longer crash a regular (non-owner) admin.

### Verified

- GitHub Actions: build green, 44 unit tests, 0 failures.
- Android 14 emulator (KVM): the app-info screen is closed on repeated attempts, `pm uninstall` is
  refused with `DELETE_FAILED_DEVICE_POLICY_MANAGER`, `dpm remove-active-admin` is refused
  ("Attempt to remove non-test admin"), and the admin stays active afterwards.

### Install

`app-debug.apk` is the build used by the emulator test — recommended for testing this fix.

`app-release.apk` is the R8-minified build. The project currently ships an empty
`proguard-rules.pro`, so reflection-based paths are unverified in the release variant.

Both are signed with the project's debug key, as configured in `app/build.gradle.kts`.

### Known limits

This is a UI-level defence. With Developer options → Running services → **Force stop**, or
`adb shell settings put secure enabled_accessibility_services ""`, the reactive layer is disabled
until App Lock is opened again; `pm uninstall` and `dpm remove-active-admin` stay refused by the
platform.
