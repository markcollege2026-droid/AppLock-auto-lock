package dev.pranav.applock.data.autolock

/**
 * Persistence boundary for automatic-enrollment state. Android uses SharedPreferences;
 * unit tests use an in-memory implementation.
 */
interface AutoLockStateStore {
    fun load(): AutoLockState
    fun save(state: AutoLockState)
}

/**
 * Serializes all automatic-enrollment state changes and applies the shared policy.
 *
 * Installation broadcasts, startup reconciliation, first-launch fallback and manual
 * settings changes can all arrive concurrently. Every state read-modify-write happens
 * under [lock], so one source of change cannot overwrite another.
 */
class AutoLockEnrollmentEngine(private val store: AutoLockStateStore) {

    private val lock = Any()

    fun state(): AutoLockState = synchronized(lock) { store.load() }

    /**
     * Establishes the persistent baseline when the feature is enabled.
     *
     * Calling this again while a baseline is already active is intentionally a no-op:
     * process restarts must reconcile against the existing baseline instead of
     * reclassifying missed installations as pre-existing apps.
     */
    fun enable(installed: Collection<InstalledApp>): AutoLockState = synchronized(lock) {
        val current = store.load()
        if (current.enabled && current.baselineReady) {
            current
        } else {
            val next = current.copy(
                enabled = true,
                baselineReady = true,
                baseline = installed.map { it.identity }.toSet(),
                handled = emptySet()
            )
            store.save(next)
            next
        }
    }

    /**
     * Stops automatic enrollment. Protected apps and manual choices are deliberately
     * left untouched; only the auto-enrollment baseline/processed bookkeeping is reset.
     */
    fun disable(): AutoLockState = synchronized(lock) {
        val current = store.load()
        val next = current.copy(
            enabled = false,
            baselineReady = false,
            baseline = emptySet(),
            handled = emptySet()
        )
        store.save(next)
        next
    }

    /**
     * Evaluates a single installation instance (installation event or first-launch
     * fallback). [onEnroll] is invoked at most once for an installation instance.
     */
    fun processInstallation(
        candidate: AutoLockCandidate,
        config: AutoLockConfig,
        protectedPackages: Set<String>,
        onEnroll: (InstallIdentity) -> Unit
    ): AutoLockDecision = synchronized(lock) {
        val current = pruneSupersededInstances(store.load(), candidate.app.identity)
        val decision = AutoLockPolicy.evaluate(
            app = candidate.app,
            isExempt = candidate.isExempt,
            isProtected = candidate.app.identity.packageName in protectedPackages,
            state = current,
            config = config
        )

        val next = when {
            decision is AutoLockDecision.Enroll -> {
                // Mark handled before returning so duplicate/concurrent events do not
                // call addLockedApp() again and wipe legitimate temporary-unlock state.
                onEnroll(decision.identity)
                current.copy(handled = current.handled + decision.identity)
            }

            decision is AutoLockDecision.Skip &&
                    AutoLockPolicy.shouldRememberSkip(decision.reason) ->
                current.copy(handled = current.handled + candidate.app.identity)

            else -> current
        }

        if (next != current) store.save(next)
        decision
    }

    /**
     * Scans the installed-app snapshot and enrolls eligible missed installations.
     *
     * This is only called on process/backend startup, never on every foreground event.
     * The baseline is retained as-is: apps installed while AppLock was not running are
     * found because they are missing from it, not by rebuilding it.
     */
    fun reconcile(
        installed: Collection<AutoLockCandidate>,
        config: AutoLockConfig,
        protectedPackages: Set<String>,
        onEnroll: (InstallIdentity) -> Unit
    ): AutoLockReconcileResult = synchronized(lock) {
        val initial = store.load()
        if (!initial.enabled || !initial.baselineReady) {
            return@synchronized AutoLockReconcileResult(emptyMap(), emptySet())
        }

        val uniqueCandidates = installed.distinctBy { it.app.identity.packageName }
        val installedByPackage = uniqueCandidates.associateBy { it.app.identity.packageName }
        val superseded = (initial.baseline + initial.handled + initial.manuallyUnprotected)
            .filter { identity ->
                val currentIdentity = installedByPackage[identity.packageName]?.app?.identity
                currentIdentity != null && currentIdentity != identity
            }
            .toSet()

        val state = if (superseded.isEmpty()) {
            initial
        } else {
            initial.copy(
                baseline = initial.baseline - superseded,
                handled = initial.handled - superseded,
                manuallyUnprotected = initial.manuallyUnprotected - superseded
            )
        }

        val decisions = LinkedHashMap<String, AutoLockDecision>()
        val enrolledPackages = LinkedHashSet<String>()
        // Accumulate handled identities in one set and persist once. This keeps the
        // reconciliation lock short even when hundreds of packages are installed.
        val handled = state.handled.toMutableSet()

        for (candidate in uniqueCandidates) {
            val packageName = candidate.app.identity.packageName
            val decision = AutoLockPolicy.evaluate(
                app = candidate.app,
                isExempt = candidate.isExempt,
                isProtected = packageName in protectedPackages,
                state = state,
                config = config
            )
            decisions[packageName] = decision

            when {
                decision is AutoLockDecision.Enroll -> {
                    onEnroll(decision.identity)
                    enrolledPackages += packageName
                    handled += decision.identity
                }

                decision is AutoLockDecision.Skip &&
                        AutoLockPolicy.shouldRememberSkip(decision.reason) ->
                    handled += candidate.app.identity

                else -> Unit
            }
        }

        val next = state.copy(handled = handled)
        if (next != initial) store.save(next)
        AutoLockReconcileResult(decisions, enrolledPackages)
    }

    /**
     * Remembers that the user deliberately removed protection from this installation
     * instance. The choice survives scans, restarts and ordinary updates, but not a
     * genuine uninstall/reinstall (which gets a new [InstallIdentity]).
     */
    fun markManuallyUnprotected(identity: InstallIdentity): AutoLockState = synchronized(lock) {
        val current = store.load()
        val next = current.copy(
            handled = current.handled + identity,
            manuallyUnprotected = current.manuallyUnprotected + identity
        )
        store.save(next)
        next
    }

    /**
     * Clears an earlier automatic-enrollment opt-out when the user manually protects the
     * app again. The protected list itself is owned by LockedAppsRepository and is never
     * modified here.
     */
    fun markManuallyProtected(packageName: String): AutoLockState = synchronized(lock) {
        val current = store.load()
        if (current.manuallyUnprotected.none { it.packageName == packageName }) {
            current
        } else {
            val next = current.copy(
                manuallyUnprotected = current.manuallyUnprotected
                    .filterNot { it.packageName == packageName }
                    .toSet()
            )
            store.save(next)
            next
        }
    }

    /**
     * Drops bookkeeping for older installation instances of the same package. This is
     * what makes a genuine uninstall/reinstall a fresh installation while keeping
     * ordinary updates (same firstInstallTime) on their previous decision.
     */
    private fun pruneSupersededInstances(
        state: AutoLockState,
        currentIdentity: InstallIdentity
    ): AutoLockState {
        fun Set<InstallIdentity>.withoutOldInstances(): Set<InstallIdentity> =
            filterNot {
                it.packageName == currentIdentity.packageName &&
                        it.firstInstallTime != currentIdentity.firstInstallTime
            }.toSet()

        val baseline = state.baseline.withoutOldInstances()
        val handled = state.handled.withoutOldInstances()
        val manuallyUnprotected = state.manuallyUnprotected.withoutOldInstances()

        if (baseline == state.baseline &&
            handled == state.handled &&
            manuallyUnprotected == state.manuallyUnprotected
        ) {
            return state
        }

        return state.copy(
            baseline = baseline,
            handled = handled,
            manuallyUnprotected = manuallyUnprotected
        )
    }
}
