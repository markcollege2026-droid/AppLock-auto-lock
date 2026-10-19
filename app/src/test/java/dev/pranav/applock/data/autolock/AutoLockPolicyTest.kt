package dev.pranav.applock.data.autolock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPolicyTest {

    private val readyState = AutoLockState(
        enabled = true,
        baselineReady = true,
        baseline = emptySet(),
        handled = emptySet(),
        manuallyUnprotected = emptySet()
    )

    private fun decision(
        app: InstalledApp,
        isExempt: Boolean = false,
        isProtected: Boolean = false,
        state: AutoLockState = readyState,
        config: AutoLockConfig = testConfig
    ): AutoLockDecision = AutoLockPolicy.evaluate(app, isExempt, isProtected, state, config)

    private fun assertSkip(
        expected: AutoLockSkipReason,
        decision: AutoLockDecision
    ) {
        assertTrue("Expected Skip($expected) but was $decision", decision is AutoLockDecision.Skip)
        assertEquals(expected, (decision as AutoLockDecision.Skip).reason)
    }

    @Test
    fun `feature disabled skips enrollment`() {
        assertSkip(
            AutoLockSkipReason.FEATURE_DISABLED,
            decision(testApp(), state = readyState.copy(enabled = false))
        )
    }

    @Test
    fun `baseline not ready skips enrollment`() {
        assertSkip(
            AutoLockSkipReason.BASELINE_NOT_READY,
            decision(testApp(), state = readyState.copy(baselineReady = false))
        )
    }

    @Test
    fun `pre-existing installation is never enrolled`() {
        val app = testApp()
        assertSkip(
            AutoLockSkipReason.PRE_EXISTING_INSTALL,
            decision(app, state = readyState.copy(baseline = setOf(app.identity)))
        )
    }

    @Test
    fun `normal user app is enrolled`() {
        val app = testApp()
        assertEquals(AutoLockDecision.Enroll(app.identity), decision(app))
    }

    @Test
    fun `applock itself is excluded`() {
        assertSkip(
            AutoLockSkipReason.SELF_PACKAGE,
            decision(testApp(packageName = "dev.pranav.applock"))
        )
    }

    @Test
    fun `system apps are excluded from automatic enrollment`() {
        assertSkip(
            AutoLockSkipReason.SYSTEM_APP,
            decision(testApp(isSystemApp = true))
        )
    }

    @Test
    fun `disabled apps are excluded`() {
        assertSkip(
            AutoLockSkipReason.DISABLED_APP,
            decision(testApp(isEnabled = false))
        )
    }

    @Test
    fun `apps without a launcher activity are excluded`() {
        assertSkip(
            AutoLockSkipReason.NO_LAUNCHER_ACTIVITY,
            decision(testApp(hasLauncherActivity = false))
        )
    }

    @Test
    fun `system exclusion list is respected`() {
        assertSkip(
            AutoLockSkipReason.EXCLUDED_PACKAGE,
            decision(testApp(packageName = "com.android.systemui"))
        )
    }

    @Test
    fun `keyboards and launchers are excluded`() {
        assertSkip(
            AutoLockSkipReason.KEYBOARD_PACKAGE,
            decision(testApp(packageName = "com.example.keyboard"))
        )
        assertSkip(
            AutoLockSkipReason.HOME_PACKAGE,
            decision(testApp(packageName = "com.example.launcher"))
        )
    }

    @Test
    fun `exempt marker skips enrollment`() {
        assertSkip(
            AutoLockSkipReason.XML_EXEMPT,
            decision(testApp(), isExempt = true)
        )
    }

    @Test
    fun `manually unprotected installation is skipped before handled bookkeeping`() {
        val app = testApp()
        assertSkip(
            AutoLockSkipReason.MANUALLY_UNPROTECTED,
            decision(
                app,
                state = readyState.copy(
                    handled = setOf(app.identity),
                    manuallyUnprotected = setOf(app.identity)
                )
            )
        )
    }

    @Test
    fun `already protected apps are not enrolled again`() {
        assertSkip(
            AutoLockSkipReason.ALREADY_PROTECTED,
            decision(testApp(), isProtected = true)
        )
    }

    @Test
    fun `manually protected and marked app is still considered protected`() {
        // The protected-list check happens before the XML exemption, so the marker can
        // never suppress a lock for an app the user protected manually.
        assertSkip(
            AutoLockSkipReason.ALREADY_PROTECTED,
            decision(testApp(), isExempt = true, isProtected = true)
        )
    }

    @Test
    fun `only a real boolean true grants the manifest exemption`() {
        assertTrue(AutoLockExemptionMarker.isExempt(true))
        assertFalse(AutoLockExemptionMarker.isExempt(false))
        assertFalse(AutoLockExemptionMarker.isExempt(null))
        assertFalse(AutoLockExemptionMarker.isExempt("true"))
        assertFalse(AutoLockExemptionMarker.isExempt("false"))
        assertFalse(AutoLockExemptionMarker.isExempt(1))
        assertFalse(AutoLockExemptionMarker.isExempt(0))
        assertFalse(AutoLockExemptionMarker.isExempt(emptyList<String>()))
    }

    @Test
    fun `marker meta data name is stable`() {
        assertEquals("dev.pranav.applock.AUTOLOCK_EXEMPT", AutoLockExemptionMarker.META_DATA_NAME)
    }

    @Test
    fun `skip bookkeeping only remembers permanent decisions`() {
        assertTrue(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.XML_EXEMPT))
        assertTrue(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.SYSTEM_APP))
        assertTrue(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.ALREADY_PROTECTED))
        assertFalse(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.FEATURE_DISABLED))
        assertFalse(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.BASELINE_NOT_READY))
        assertFalse(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.PRE_EXISTING_INSTALL))
        assertFalse(AutoLockPolicy.shouldRememberSkip(AutoLockSkipReason.MANUALLY_UNPROTECTED))
    }

    @Test
    fun `exemption cache invalidates on update and reinstall`() {
        val cache = AutoLockExemptionCache()
        var lookups = 0
        var markerValue = true

        val firstInstall = testApp(firstInstallTime = 100, lastUpdateTime = 100)
        assertTrue(cache.resolve(firstInstall) { lookups++; markerValue })
        assertEquals(1, lookups)

        // Same installation instance: cached value is reused.
        markerValue = false
        assertTrue(cache.resolve(firstInstall) { lookups++; markerValue })
        assertEquals(1, lookups)

        // Ordinary update: new lastUpdateTime forces a fresh read.
        val updated = firstInstall.copy(lastUpdateTime = 200)
        assertFalse(cache.resolve(updated) { lookups++; markerValue })
        assertEquals(2, lookups)

        // Reinstall: new firstInstallTime forces a fresh read even with same update time.
        val reinstalled = firstInstall.copy(
            identity = InstallIdentity(firstInstall.identity.packageName, 999),
            lastUpdateTime = 200
        )
        markerValue = true
        assertTrue(cache.resolve(reinstalled) { lookups++; markerValue })
        assertEquals(3, lookups)

        // Explicit invalidation also forces a fresh read.
        cache.invalidate(firstInstall.identity.packageName)
        markerValue = false
        assertFalse(cache.resolve(reinstalled) { lookups++; markerValue })
        assertEquals(4, lookups)
    }

    @Test
    fun `install identity round trips and rejects malformed values`() {
        val identity = InstallIdentity("com.example.app", 123456789L)
        assertEquals(identity, InstallIdentity.decode(identity.encode()))

        assertEquals(null, InstallIdentity.decode("com.example.app"))
        assertEquals(null, InstallIdentity.decode("|123"))
        assertEquals(null, InstallIdentity.decode("com.example.app|"))
        assertEquals(null, InstallIdentity.decode("com.example.app|not-a-number"))
        assertEquals(null, InstallIdentity.decode(""))
    }
}
