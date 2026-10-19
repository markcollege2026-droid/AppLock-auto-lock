package dev.pranav.applock.data.autolock

/**
 * Identifies one *installation instance* of an app.
 *
 * [firstInstallTime] is stable across ordinary updates, but changes when an app is
 * uninstalled and installed again. It is therefore used instead of the package name
 * alone to distinguish a genuine reinstall from an update.
 */
data class InstallIdentity(
    val packageName: String,
    val firstInstallTime: Long
) {
    fun encode(): String = "$packageName$SEPARATOR$firstInstallTime"

    companion object {
        private const val SEPARATOR = '|'

        /**
         * Decodes a value previously produced by [encode]. Malformed entries are
         * ignored rather than crashing, so corrupted preferences cannot break startup.
         */
        fun decode(encoded: String): InstallIdentity? {
            val separatorIndex = encoded.lastIndexOf(SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex == encoded.lastIndex) return null

            val packageName = encoded.substring(0, separatorIndex)
            if (packageName.isBlank()) return null

            val firstInstallTime = encoded.substring(separatorIndex + 1).toLongOrNull() ?: return null
            return InstallIdentity(packageName, firstInstallTime)
        }
    }
}

/**
 * The subset of PackageManager metadata needed by the automatic enrollment policy.
 * Keeping this as a plain data class makes the policy independent of Android and testable.
 */
data class InstalledApp(
    val identity: InstallIdentity,
    val lastUpdateTime: Long,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val hasLauncherActivity: Boolean
)

/**
 * An installed app together with the result of reading its self-declared
 * `dev.pranav.applock.AUTOLOCK_EXEMPT` metadata marker.
 */
data class AutoLockCandidate(
    val app: InstalledApp,
    val isExempt: Boolean
)

/**
 * Package sets that are relevant to automatic enrollment. These are supplied by the
 * Android layer so the core policy stays free of Context/PackageManager dependencies.
 */
data class AutoLockConfig(
    val selfPackageName: String,
    val excludedPackages: Set<String> = emptySet(),
    val keyboardPackages: Set<String> = emptySet(),
    val homePackages: Set<String> = emptySet()
)

/**
 * Persisted state of the automatic enrollment feature.
 *
 * [baseline] contains the installation instances that already existed when the feature
 * was enabled. Those apps are never enrolled automatically. Entries are keyed by
 * [InstallIdentity] so a reinstall is evaluated as a new installation.
 */
data class AutoLockState(
    val enabled: Boolean,
    val baselineReady: Boolean,
    val baseline: Set<InstallIdentity>,
    val handled: Set<InstallIdentity>,
    val manuallyUnprotected: Set<InstallIdentity>
) {
    companion object {
        val EMPTY = AutoLockState(
            enabled = false,
            baselineReady = false,
            baseline = emptySet(),
            handled = emptySet(),
            manuallyUnprotected = emptySet()
        )
    }
}

enum class AutoLockSkipReason {
    FEATURE_DISABLED,
    BASELINE_NOT_READY,
    INVALID_PACKAGE,
    SELF_PACKAGE,
    PRE_EXISTING_INSTALL,
    MANUALLY_UNPROTECTED,
    ALREADY_HANDLED,
    ALREADY_PROTECTED,
    SYSTEM_APP,
    DISABLED_APP,
    NO_LAUNCHER_ACTIVITY,
    EXCLUDED_PACKAGE,
    KEYBOARD_PACKAGE,
    HOME_PACKAGE,
    XML_EXEMPT
}

sealed interface AutoLockDecision {
    data class Enroll(val identity: InstallIdentity) : AutoLockDecision
    data class Skip(val reason: AutoLockSkipReason) : AutoLockDecision
}

data class AutoLockReconcileResult(
    val decisions: Map<String, AutoLockDecision>,
    val enrolledPackages: Set<String>
)
