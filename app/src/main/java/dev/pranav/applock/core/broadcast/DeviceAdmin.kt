package dev.pranav.applock.core.broadcast

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dev.pranav.applock.R

class DeviceAdmin : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "DeviceAdmin"
        private const val PREFS_NAME = "dev.pranav.applock.admin_prefs"
        private const val KEY_PASSWORD_VERIFIED = "password_verified"

        private const val SETTINGS_PREFS_NAME = "app_lock_settings"
        private const val KEY_ANTI_UNINSTALL = "anti_uninstall"
    }

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        super.onEnabled(context, intent)
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ANTI_UNINSTALL, true)
        }

        val component = ComponentName(context, DeviceAdmin::class.java)

        // The uninstall protection itself comes from being an *active* device administrator:
        // Android refuses to uninstall such an app. setUninstallBlocked() is an additional,
        // device-owner/profile-owner-only API on recent releases and throws SecurityException for
        // a regular admin, so a denial must not escape from here.
        runCatching {
            getManager(context).setUninstallBlocked(component, context.packageName, true)
        }.onFailure { Log.d(TAG, "setUninstallBlocked not permitted for a regular admin", it) }
    }

    /**
     * Shown by the system (Settings > Device admin apps > Deactivate) as an extra confirmation
     * before the administrator is removed. Returning null keeps the flow to a single step once
     * anti-uninstall has been switched off through App Lock's own password-protected screen.
     *
     * Note: this is not consulted by the uninstall flow, which goes straight to
     * `uninstallPackageWithActiveAdmins()`; that path is handled by the accessibility service.
     */
    override fun onDisableRequested(
        context: Context,
        intent: android.content.Intent
    ): CharSequence? {
        if (!isAntiUninstallEnabled(context)) return null
        return context.getString(R.string.anti_uninstall_deactivate_warning)
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        super.onDisabled(context, intent)
        context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ANTI_UNINSTALL, false)
        }
    }

    fun setPasswordVerified(context: Context, verified: Boolean) {
        getSharedPreferences(context).edit { putBoolean(KEY_PASSWORD_VERIFIED, verified) }
    }

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun isAntiUninstallEnabled(context: Context): Boolean {
        return context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ANTI_UNINSTALL, false)
    }
}
