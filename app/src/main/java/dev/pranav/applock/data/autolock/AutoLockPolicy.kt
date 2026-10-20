package dev.pranav.applock.data.autolock

import java.util.concurrent.ConcurrentHashMap

/**
 * The self-declared manifest marker that lets an app opt out of automatic enrollment.
 *
 * This is intentionally not a security boundary. Any app can declare the marker and
 * receive the exemption; no signing certificate, token or permission is checked.
 */
object AutoLockExemptionMarker {
    const val META_DATA_NAME = "dev.pranav.applock.AUTOLOCK_EXEMPT"

    /**
     * Only a real Boolean `true` value grants the exemption. A missing value, `false`,
     * a string, a number or any other malformed value is treated as "not exempt".
     */
    fun isExempt(metaDataValue: Any?): Boolean = metaDataValue is Boolean && metaDataValue
}

/**
 * Pure automatic-enrollment policy shared by the installation receiver, startup
 * reconciliation and the first-launch fallback used by all locking backends.
 */
object AutoLockPolicy {

    /**
     * Decides whether [app] should be protected automatically.
     *
     * [isProtected] is a snapshot of the manual protected list and [isExempt] is the
     * already-resolved value of the manifest marker. This function never mutates state
     * and never removes anything from the protected list.
     */
    fun evaluate(
        app: InstalledApp,
        isExempt: Boolean,
        isProtected: Boolean,
        state: AutoLockState,
        config: AutoLockConfig
    ): AutoLockDecision {
        val packageName = app.identity.packageName

        return when {
            !state.enabled -> AutoLockDecision.Skip(AutoLockSkipReason.FEATURE_DISABLED)
            !state.baselineReady -> AutoLockDecision.Skip(AutoLockSkipReason.BASELINE_NOT_READY)
            packageName.isBlank() -> AutoLockDecision.Skip(AutoLockSkipReason.INVALID_PACKAGE)
            packageName == config.selfPackageName -> AutoLockDecision.Skip(AutoLockSkipReason.SELF_PACKAGE)
            app.identity in state.baseline -> AutoLockDecision.Skip(AutoLockSkipReason.PRE_EXISTING_INSTALL)
            app.identity in state.manuallyUnprotected -> AutoLockDecision.Skip(AutoLockSkipReason.MANUALLY_UNPROTECTED)
            app.identity in state.handled -> AutoLockDecision.Skip(AutoLockSkipReason.ALREADY_HANDLED)
            isProtected -> AutoLockDecision.Skip(AutoLockSkipReason.ALREADY_PROTECTED)
            app.isSystemApp -> AutoLockDecision.Skip(AutoLockSkipReason.SYSTEM_APP)
            !app.isEnabled -> AutoLockDecision.Skip(AutoLockSkipReason.DISABLED_APP)
            !app.hasLauncherActivity -> AutoLockDecision.Skip(AutoLockSkipReason.NO_LAUNCHER_ACTIVITY)
            packageName in config.excludedPackages -> AutoLockDecision.Skip(AutoLockSkipReason.EXCLUDED_PACKAGE)
            packageName in config.keyboardPackages -> AutoLockDecision.Skip(AutoLockSkipReason.KEYBOARD_PACKAGE)
            packageName in config.homePackages -> AutoLockDecision.Skip(AutoLockSkipReason.HOME_PACKAGE)
            isExempt -> AutoLockDecision.Skip(AutoLockSkipReason.XML_EXEMPT)
            else -> AutoLockDecision.Enroll(app.identity)
        }
    }

    /**
     * Some skips are worth remembering for the lifetime of this installation instance so
     * that later scans/foreground events do not repeatedly look the package up. Skips that
     * merely mean "feature is off" or "state is not ready yet" must never be remembered.
     */
    fun shouldRememberSkip(reason: AutoLockSkipReason): Boolean = when (reason) {
        AutoLockSkipReason.SYSTEM_APP,
        AutoLockSkipReason.DISABLED_APP,
        AutoLockSkipReason.NO_LAUNCHER_ACTIVITY,
        AutoLockSkipReason.EXCLUDED_PACKAGE,
        AutoLockSkipReason.KEYBOARD_PACKAGE,
        AutoLockSkipReason.HOME_PACKAGE,
        AutoLockSkipReason.XML_EXEMPT,
        AutoLockSkipReason.ALREADY_PROTECTED -> true

        AutoLockSkipReason.FEATURE_DISABLED,
        AutoLockSkipReason.BASELINE_NOT_READY,
        AutoLockSkipReason.INVALID_PACKAGE,
        AutoLockSkipReason.SELF_PACKAGE,
        AutoLockSkipReason.PRE_EXISTING_INSTALL,
        AutoLockSkipReason.MANUALLY_UNPROTECTED,
        AutoLockSkipReason.ALREADY_HANDLED -> false
    }
}

/**
 * In-memory cache for the self-declared exemption marker.
 *
 * The key includes both install times. An ordinary update changes `lastUpdateTime`, and
 * a reinstall changes `firstInstallTime`, so stale values can never be reused for a new
 * installation instance even if a broadcast is missed.
 */
class AutoLockExemptionCache {
    private val cachedValues = ConcurrentHashMap<String, Boolean>()

    fun resolve(app: InstalledApp, lookup: () -> Boolean): Boolean {
        val key = cacheKey(app)
        cachedValues[key]?.let { return it }

        val result = lookup()
        cachedValues[key] = result
        return result
    }

    fun invalidate(packageName: String) {
        val prefix = "$packageName$KEY_SEPARATOR"
        cachedValues.keys
            .toList()
            .filter { it.startsWith(prefix) }
            .forEach(cachedValues::remove)
    }

    fun clear() {
        cachedValues.clear()
    }

    private fun cacheKey(app: InstalledApp): String =
        "${app.identity.packageName}$KEY_SEPARATOR${app.identity.firstInstallTime}" +
                "$KEY_SEPARATOR${app.lastUpdateTime}"

    companion object {
        private const val KEY_SEPARATOR = '|'
    }
}
