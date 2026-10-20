package dev.pranav.applock.data.autolock

internal fun testApp(
    packageName: String = "com.example.newapp",
    firstInstallTime: Long = 1_000L,
    lastUpdateTime: Long = 1_000L,
    isSystemApp: Boolean = false,
    isEnabled: Boolean = true,
    hasLauncherActivity: Boolean = true
): InstalledApp = InstalledApp(
    identity = InstallIdentity(packageName, firstInstallTime),
    lastUpdateTime = lastUpdateTime,
    isSystemApp = isSystemApp,
    isEnabled = isEnabled,
    hasLauncherActivity = hasLauncherActivity
)

internal fun testCandidate(
    app: InstalledApp,
    isExempt: Boolean = false
): AutoLockCandidate = AutoLockCandidate(app, isExempt)

internal val testConfig = AutoLockConfig(
    selfPackageName = "dev.pranav.applock",
    excludedPackages = setOf("com.android.systemui"),
    keyboardPackages = setOf("com.example.keyboard"),
    homePackages = setOf("com.example.launcher")
)

internal class InMemoryAutoLockStateStore(
    initial: AutoLockState = AutoLockState.EMPTY
) : AutoLockStateStore {
    private var current = initial

    override fun load(): AutoLockState = current

    override fun save(state: AutoLockState) {
        current = state
    }
}
