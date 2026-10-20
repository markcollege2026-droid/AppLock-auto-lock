package dev.pranav.applock.data.autolock

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import android.view.inputmethod.InputMethodManager
import dev.pranav.applock.services.AppLockConstants

/**
 * Reads install metadata and the self-declared exemption marker from PackageManager.
 *
 * Only targeted lookups are made for foreground first-launch checks. The full installed
 * package query is reserved for startup reconciliation and runs on an IO dispatcher.
 */
class AndroidPackageMetadataReader(
    context: Context,
    private val exemptionCache: AutoLockExemptionCache = AutoLockExemptionCache()
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun get(packageName: String): AutoLockCandidate? {
        if (packageName.isBlank()) return null

        val info = try {
            // Install timestamps live on PackageInfo, not ApplicationInfo.
            packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (_: PackageManager.NameNotFoundException) {
            // The package disappeared between the event and this lookup.
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read package metadata for $packageName", e)
            return null
        }

        return try {
            toCandidate(
                info = info,
                hasLauncherActivity =
                packageManager.getLaunchIntentForPackage(packageName) != null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read package metadata for $packageName", e)
            null
        }
    }

    fun getAll(): List<AutoLockCandidate> {
        val packages = try {
            packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to list installed packages", e)
            return emptyList()
        }

        if (packages.isEmpty()) return emptyList()

        val launcherPackages = getLauncherPackages()
        return packages.mapNotNull { info ->
            try {
                toCandidate(
                    info = info,
                    hasLauncherActivity = when {
                        launcherPackages != null -> info.packageName in launcherPackages
                        else -> packageManager.getLaunchIntentForPackage(info.packageName) != null
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping metadata for ${info.packageName}", e)
                null
            }
        }
    }

    private fun toCandidate(info: PackageInfo, hasLauncherActivity: Boolean): AutoLockCandidate? {
        val appInfo = info.applicationInfo ?: return null
        val app = InstalledApp(
            identity = InstallIdentity(
                packageName = info.packageName,
                firstInstallTime = info.firstInstallTime
            ),
            lastUpdateTime = info.lastUpdateTime,
            // Updated pre-installed apps still carry FLAG_SYSTEM. Auto-enrollment is
            // intentionally limited to user-installed apps; manual protection remains
            // unchanged for system apps.
            isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isEnabled = appInfo.enabled,
            hasLauncherActivity = hasLauncherActivity
        )
        val isExempt = exemptionCache.resolve(app) {
            readExemptionMarker(appInfo)
        }
        return AutoLockCandidate(app, isExempt)
    }

    fun invalidate(packageName: String) {
        exemptionCache.invalidate(packageName)
    }

    fun clearCache() {
        exemptionCache.clear()
    }

    private fun readExemptionMarker(info: ApplicationInfo): Boolean =
        AutoLockExemptionMarker.isExempt(
            info.metaData?.get(AutoLockExemptionMarker.META_DATA_NAME)
        )

    /**
     * Returns null when LauncherApps is unavailable/fails so callers can fall back to
     * per-package launch-intent lookups instead of silently classifying every app as
     * having no launcher.
     */
    private fun getLauncherPackages(): Set<String>? {
        return try {
            val launcherApps =
                appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    ?: return null
            launcherApps.getActivityList(null, Process.myUserHandle())
                .mapNotNull { it.applicationInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query launcher activities", e)
            null
        }
    }

    companion object {
        private const val TAG = "AutoLockMetadata"
    }
}

/**
 * Supplies package sets that must never be auto-enrolled. Manual protection is not
 * affected by these exclusions.
 */
class AutoLockConfigProvider(private val context: Context) {

    fun current(): AutoLockConfig {
        val appContext = context.applicationContext
        return AutoLockConfig(
            selfPackageName = appContext.packageName,
            excludedPackages = AppLockConstants.EXCLUDED_APPS,
            keyboardPackages = getKeyboardPackages(appContext),
            homePackages = getHomePackages(appContext)
        )
    }

    private fun getKeyboardPackages(context: Context): Set<String> {
        return try {
            val inputMethodManager =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            inputMethodManager?.enabledInputMethodList
                ?.map { it.packageName }
                ?.toSet()
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query enabled keyboards", e)
            emptySet()
        }
    }

    private fun getHomePackages(context: Context): Set<String> {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query launchers", e)
            emptySet()
        }
    }

    companion object {
        private const val TAG = "AutoLockConfig"
    }
}
