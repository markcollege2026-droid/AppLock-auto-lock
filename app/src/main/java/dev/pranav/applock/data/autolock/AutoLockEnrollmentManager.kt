package dev.pranav.applock.data.autolock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.pranav.applock.data.repository.AppLockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AutoLockEvent {
    data class PackageChanged(val packageName: String) : AutoLockEvent
    data class ProtectionChanged(val packageNames: Set<String>) : AutoLockEvent
    data object FeatureStateChanged : AutoLockEvent
}

/**
 * Process-wide coordinator for automatic enrollment.
 *
 * There is exactly one runtime package receiver for the whole application process. It is
 * registered when the Application starts and is never registered from an Activity, so
 * there are no duplicate listeners. Android does not deliver runtime broadcasts while the
 * process is dead; [scheduleReconcile] recovers those missed installations on the next
 * process/backend start.
 */
object AutoLockEnrollmentManager {
    private const val TAG = "AutoLockEnrollment"

    private val _events = MutableSharedFlow<AutoLockEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AutoLockEvent> = _events

    @Volatile
    private var coordinator: AutoLockCoordinator? = null

    fun initialize(context: Context, repository: AppLockRepository) {
        if (coordinator != null) return
        synchronized(this) {
            if (coordinator != null) return
            coordinator = try {
                AutoLockCoordinator(context.applicationContext, repository).also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to initialize automatic enrollment", e)
                null
            }
        }
    }

    /**
     * Called after the setting has been persisted. Uses the current preference value, so
     * rapid enable/disable toggles converge on the latest user choice.
     */
    fun onSettingChanged() {
        coordinator?.onSettingChanged()
    }

    /**
     * Used by all three locking backends before they decide that a foreground package is
     * unprotected. Returns true if the app is (or just became) protected.
     */
    fun isProtectedOrEnrollOnLaunch(
        repository: AppLockRepository,
        packageName: String
    ): Boolean {
        if (repository.isAppLocked(packageName)) return true

        val activeCoordinator = coordinator ?: return false
        return activeCoordinator.enrollOnLaunchIfEligible(packageName)
    }

    fun recordManualProtection(context: Context, packageName: String) {
        val activeCoordinator = coordinator
        if (activeCoordinator != null) {
            activeCoordinator.recordManualProtection(packageName)
            return
        }

        // Defensive fallback for unusual initialization order.
        val engine = AutoLockEnrollmentEngine(AutoLockStateRepository(context))
        engine.markManuallyProtected(packageName)
    }

    fun recordManualUnprotection(context: Context, packageName: String) {
        val activeCoordinator = coordinator
        if (activeCoordinator != null) {
            activeCoordinator.recordManualUnprotection(packageName)
            return
        }

        val reader = AndroidPackageMetadataReader(context)
        val identity = reader.get(packageName)?.app?.identity ?: return
        AutoLockEnrollmentEngine(AutoLockStateRepository(context))
            .markManuallyUnprotected(identity)
    }

    fun scheduleReconcile() {
        coordinator?.scheduleReconcile()
    }

    internal fun publish(event: AutoLockEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Android implementation of the coordinator. State transitions are serialized by
 * [stateMutex]; package lookups happen off the main thread except for a single targeted
 * lookup used by the first-launch fallback.
 */
private class AutoLockCoordinator(
    private val context: Context,
    private val repository: AppLockRepository
) {
    private val stateStore = AutoLockStateRepository(context)
    private val engine = AutoLockEnrollmentEngine(stateStore)
    private val metadataReader = AndroidPackageMetadataReader(context)
    private val configProvider = AutoLockConfigProvider(context)

    /**
     * A dedicated single thread keeps setting changes, package events and reconciliation
     * in submission order. All work below is off the main thread, and the serialized
     * dispatcher prevents rapid on/off toggles from racing each other.
     */
    private val coordinatorDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "AutoLockEnrollment").apply { isDaemon = true }
        }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + coordinatorDispatcher)
    private val stateMutex = Mutex()
    private val reconcileRequested = AtomicBoolean(false)
    // Cached enrollment outcome per package. Populated by reconciliation and by the
    // first-launch fallback; invalidated by package broadcasts and manual changes.
    private val decisions = ConcurrentHashMap<String, Boolean>()
    private val pendingManualUnprotection = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cachedConfig: AutoLockConfig? = null

    @Volatile
    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handlePackageBroadcast(intent ?: return)
        }
    }

    fun start() {
        // Read preferences before registering the receiver. If credential-encrypted
        // storage is not available yet (direct boot), initialization is retried later
        // without leaving an orphaned receiver behind.
        val enabled = repository.isAutoLockNewAppsEnabled()
        registerPackageReceiver()

        if (!enabled) return

        scope.launch {
            stateMutex.withLock {
                if (!engine.state().baselineReady) {
                    establishBaselineLocked()
                }
            }
            scheduleReconcile()
        }
    }

    fun onSettingChanged() {
        registerPackageReceiver()
        scope.launch {
            stateMutex.withLock {
                if (repository.isAutoLockNewAppsEnabled()) {
                    establishBaselineLocked()
                } else {
                    engine.disable()
                    decisions.clear()
                    metadataReader.clearCache()
                    cachedConfig = null
                }
            }

            AutoLockEnrollmentManager.publish(AutoLockEvent.FeatureStateChanged)

            if (repository.isAutoLockNewAppsEnabled()) {
                scheduleReconcile()
            }
        }
    }

    /**
     * First-launch fallback. Performs at most one targeted PackageManager lookup (results
     * are cached until a package broadcast invalidates them), so no full scan happens on
     * the accessibility/polling thread.
     */
    fun enrollOnLaunchIfEligible(packageName: String): Boolean {
        if (!repository.isAutoLockNewAppsEnabled()) return false

        decisions[packageName]?.let { return it }

        val state = engine.state()
        if (!state.enabled || !state.baselineReady) return false

        val candidate = metadataReader.get(packageName) ?: return false

        // Re-check the protected list after the lookup; a manual change may have landed
        // while the metadata was being read.
        val protectedPackages = repository.getLockedApps()
        if (packageName in protectedPackages) {
            decisions[packageName] = true
            return true
        }

        val config = cachedConfig ?: configProvider.current().also { cachedConfig = it }
        var enrolled = false

        val decision = engine.processInstallation(
            candidate = candidate,
            config = config,
            protectedPackages = protectedPackages
        ) { identity ->
            // The setting may have been turned off while the lookup was running.
            if (repository.isAutoLockNewAppsEnabled()) {
                repository.addLockedApp(identity.packageName, automatic = true)
                enrolled = true
            }
        }

        val isNowProtected = repository.isAppLocked(packageName)
        decisions[packageName] = isNowProtected

        if (enrolled) {
            AutoLockEnrollmentManager.publish(
                AutoLockEvent.ProtectionChanged(setOf(packageName))
            )
        }

        return isNowProtected
    }

    fun recordManualProtection(packageName: String) {
        engine.markManuallyProtected(packageName)
        decisions.remove(packageName)
    }

    /**
     * Records a persistent opt-out for the current installation instance. The protected
     * list itself is removed by AppLockRepository immediately after this call.
     */
    fun recordManualUnprotection(packageName: String) {
        decisions.remove(packageName)

        val state = engine.state()
        val identity = metadataReader.get(packageName)?.app?.identity
            ?: (state.handled + state.baseline)
                .firstOrNull { it.packageName == packageName }

        if (identity == null) {
            // A transient PackageManager failure. Retry during the next reconciliation;
            // this also covers packages disappearing mid-lookup.
            pendingManualUnprotection.add(packageName)
            return
        }

        engine.markManuallyUnprotected(identity)
    }

    fun scheduleReconcile() {
        if (!reconcileRequested.compareAndSet(false, true)) return

        scope.launch {
            try {
                stateMutex.withLock {
                    // Loop so package events that arrive while a scan is running are not
                    // dropped between snapshots.
                    do {
                        reconcileRequested.set(false)
                        reconcileLocked()
                    } while (reconcileRequested.get())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Automatic enrollment reconciliation failed", e)
            } finally {
                reconcileRequested.set(false)
            }
        }
    }

    private fun establishBaselineLocked() {
        if (!repository.isAutoLockNewAppsEnabled()) {
            engine.disable()
            decisions.clear()
            metadataReader.clearCache()
            return
        }

        val current = engine.state()
        if (current.enabled && current.baselineReady) return

        val installed = metadataReader.getAll()
        if (installed.isEmpty()) {
            // Do not persist an empty baseline: a PackageManager failure would otherwise
            // make every existing app look like a new installation.
            Log.w(TAG, "Skipping baseline setup because installed apps could not be read")
            return
        }

        engine.enable(installed.map { it.app })
        decisions.clear()
        metadataReader.clearCache()
        cachedConfig = null
        Log.d(TAG, "Automatic enrollment baseline established for ${installed.size} apps")
    }

    private fun reconcileLocked() {
        if (!repository.isAutoLockNewAppsEnabled()) return

        if (!engine.state().baselineReady) {
            // Only reachable after a crash during first-time setup; normal process
            // restarts keep the persisted baseline.
            establishBaselineLocked()
        }
        if (!engine.state().enabled || !engine.state().baselineReady) return

        resolvePendingManualUnprotection()

        val installed = metadataReader.getAll()
        if (installed.isEmpty()) return

        val config = configProvider.current().also { cachedConfig = it }
        val protectedPackages = repository.getLockedApps()
        val newlyEnrolled = LinkedHashSet<String>()

        val result = engine.reconcile(
            installed = installed,
            config = config,
            protectedPackages = protectedPackages
        ) { identity ->
            // Do not finish an in-flight scan after the user disabled the feature.
            if (repository.isAutoLockNewAppsEnabled()) {
                repository.addLockedApp(identity.packageName, automatic = true)
                newlyEnrolled += identity.packageName
            }
        }

        installed.forEach { candidate ->
            val packageName = candidate.app.identity.packageName
            val decision = result.decisions[packageName] ?: return@forEach
            decisions[packageName] = decision is AutoLockDecision.Enroll
        }

        if (newlyEnrolled.isNotEmpty()) {
            Log.d(TAG, "Automatically protected missed installations: $newlyEnrolled")
            AutoLockEnrollmentManager.publish(
                AutoLockEvent.ProtectionChanged(newlyEnrolled)
            )
        }
    }

    private fun resolvePendingManualUnprotection() {
        if (pendingManualUnprotection.isEmpty()) return

        pendingManualUnprotection.toList().forEach { packageName ->
            val candidate = metadataReader.get(packageName)
            if (candidate != null) {
                engine.markManuallyUnprotected(candidate.app.identity)
                pendingManualUnprotection.remove(packageName)
            } else {
                // Package is genuinely gone; there is nothing left to suppress.
                pendingManualUnprotection.remove(packageName)
            }
        }
    }

    private fun handlePackageBroadcast(intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_PACKAGE_ACTIONS) return

        val data = intent.data ?: return
        if (data.scheme != PACKAGE_SCHEME) return

        val packageName = data.schemeSpecificPart
        if (packageName.isNullOrBlank()) return

        val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        invalidatePackageCaches(packageName)

        if (action == Intent.ACTION_PACKAGE_ADDED && !replacing &&
            repository.isAutoLockNewAppsEnabled()
        ) {
            // Enrollment itself is done from the IO reconciliation, including the marker
            // and eligibility checks. This avoids doing PackageManager work on the
            // broadcast main thread.
            scheduleReconcile()
        }

        AutoLockEnrollmentManager.publish(AutoLockEvent.PackageChanged(packageName))
    }

    private fun invalidatePackageCaches(packageName: String) {
        decisions.remove(packageName)
        metadataReader.invalidate(packageName)
        cachedConfig = null
    }

    private fun registerPackageReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            SUPPORTED_PACKAGE_ACTIONS.forEach { addAction(it) }
            addDataScheme(PACKAGE_SCHEME)
        }

        try {
            // Package broadcasts are system broadcasts. NOT_EXPORTED keeps other apps
            // from sending spoofed package events to this receiver.
            ContextCompat.registerReceiver(
                context,
                packageReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to register package installation receiver", e)
        }
    }

    companion object {
        private const val TAG = "AutoLockCoordinator"
        private const val PACKAGE_SCHEME = "package"

        private val SUPPORTED_PACKAGE_ACTIONS = arrayOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_CHANGED
        )
    }
}
