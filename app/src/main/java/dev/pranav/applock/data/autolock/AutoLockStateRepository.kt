package dev.pranav.applock.data.autolock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.pranav.applock.data.repository.PreferencesRepository

/**
 * SharedPreferences-backed state for "Automatically protect new apps".
 *
 * The enabled flag lives in the existing settings preferences file and is also exposed
 * through [PreferencesRepository], so the toggle uses the app's normal preference
 * structure. Baseline, processed installation instances and manual opt-outs are stored
 * keyed by [InstallIdentity] (package name plus firstInstallTime).
 */
class AutoLockStateRepository(context: Context) : AutoLockStateStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PreferencesRepository.PREFS_NAME_SETTINGS,
            Context.MODE_PRIVATE
        )

    override fun load(): AutoLockState = AutoLockState(
        enabled = preferences.getBoolean(
            PreferencesRepository.KEY_AUTO_LOCK_NEW_APPS,
            false
        ),
        baselineReady = preferences.getBoolean(KEY_BASELINE_READY, false),
        baseline = readIdentities(KEY_BASELINE),
        handled = readIdentities(KEY_HANDLED),
        manuallyUnprotected = readIdentities(KEY_MANUALLY_UNPROTECTED)
    )

    /**
     * Only baseline bookkeeping is written here. The enabled flag is owned by
     * [PreferencesRepository]/the settings toggle, so an in-flight disable operation can
     * never overwrite a newer enable choice made by the user.
     */
    override fun save(state: AutoLockState) {
        preferences.edit {
            putBoolean(KEY_BASELINE_READY, state.baselineReady)
            putStringSet(KEY_BASELINE, state.baseline.encode())
            putStringSet(KEY_HANDLED, state.handled.encode())
            putStringSet(KEY_MANUALLY_UNPROTECTED, state.manuallyUnprotected.encode())
        }
    }

    private fun readIdentities(key: String): Set<InstallIdentity> =
        preferences.getStringSet(key, emptySet())
            .orEmpty()
            .mapNotNull { InstallIdentity.decode(it) }
            .toSet()

    private fun Set<InstallIdentity>.encode(): Set<String> =
        mapTo(HashSet(size)) { it.encode() }

    companion object {
        private const val KEY_BASELINE_READY = "auto_lock_baseline_ready"
        private const val KEY_BASELINE = "auto_lock_baseline"
        private const val KEY_HANDLED = "auto_lock_handled_installs"
        private const val KEY_MANUALLY_UNPROTECTED = "auto_lock_manually_unprotected"
    }
}
