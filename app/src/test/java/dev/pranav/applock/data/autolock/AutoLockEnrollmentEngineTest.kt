package dev.pranav.applock.data.autolock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoLockEnrollmentEngineTest {

    private fun engine(store: InMemoryAutoLockStateStore = InMemoryAutoLockStateStore()) =
        AutoLockEnrollmentEngine(store)

    private fun skipReason(decision: AutoLockDecision): AutoLockSkipReason? =
        (decision as? AutoLockDecision.Skip)?.reason

    @Test
    fun `feature off does not protect installations`() {
        val engine = engine()
        var enrolled = false

        val decision = engine.processInstallation(
            candidate = testCandidate(testApp()),
            config = testConfig,
            protectedPackages = emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.FEATURE_DISABLED, skipReason(decision))
        assertFalse(enrolled)
    }

    @Test
    fun `enabling does not protect apps that are already installed`() {
        val store = InMemoryAutoLockStateStore()
        val engine = engine(store)
        val preExisting = testApp("com.example.preexisting")
        var enrolled = false

        engine.enable(listOf(preExisting))
        val decision = engine.processInstallation(
            testCandidate(preExisting),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.PRE_EXISTING_INSTALL, skipReason(decision))
        assertFalse(enrolled)
        assertEquals(setOf(preExisting.identity), engine.state().baseline)
        assertTrue(engine.state().baselineReady)
    }

    @Test
    fun `fresh eligible installation is enrolled once`() {
        val store = InMemoryAutoLockStateStore()
        val engine = engine(store)
        engine.enable(emptyList())
        val newApp = testApp("com.example.fresh")
        val enrolled = mutableListOf<InstallIdentity>()

        val decision = engine.processInstallation(
            testCandidate(newApp),
            testConfig,
            emptySet()
        ) { enrolled += it }

        assertEquals(AutoLockDecision.Enroll(newApp.identity), decision)
        assertEquals(listOf(newApp.identity), enrolled)
        assertTrue(engine.state().handled.contains(newApp.identity))
    }

    @Test
    fun `ordinary update does not enroll a pre-existing app`() {
        val engine = engine()
        val preExisting = testApp("com.example.preexisting", firstInstallTime = 100)
        engine.enable(listOf(preExisting))
        var enrolled = false

        val updated = preExisting.copy(lastUpdateTime = 9_999)
        val decision = engine.processInstallation(
            testCandidate(updated),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.PRE_EXISTING_INSTALL, skipReason(decision))
        assertFalse(enrolled)
    }

    @Test
    fun `ordinary update does not enroll a previously skipped new app`() {
        val engine = engine()
        engine.enable(emptyList())
        val app = testApp("com.example.nolauncher", hasLauncherActivity = false)

        val first = engine.processInstallation(testCandidate(app), testConfig, emptySet()) {}
        assertEquals(AutoLockSkipReason.NO_LAUNCHER_ACTIVITY, skipReason(first))

        var enrolled = false
        val updated = app.copy(hasLauncherActivity = true, lastUpdateTime = 2_000)
        val second = engine.processInstallation(
            testCandidate(updated),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.ALREADY_HANDLED, skipReason(second))
        assertFalse(enrolled)
    }

    @Test
    fun `manual unprotection survives scans updates and restart`() {
        val store = InMemoryAutoLockStateStore()
        val engine = engine(store)
        engine.enable(emptyList())
        val app = testApp("com.example.auto", firstInstallTime = 100)

        engine.processInstallation(testCandidate(app), testConfig, emptySet()) {}
        engine.markManuallyUnprotected(app.identity)

        // Simulate a process restart with the same persisted store.
        val restarted = AutoLockEnrollmentEngine(store)
        var enrolled = false
        val updated = app.copy(lastUpdateTime = 5_000)

        val decision = restarted.processInstallation(
            testCandidate(updated),
            testConfig,
            emptySet()
        ) { enrolled = true }
        assertEquals(AutoLockSkipReason.MANUALLY_UNPROTECTED, skipReason(decision))
        assertFalse(enrolled)

        val reconcile = restarted.reconcile(
            listOf(testCandidate(updated)),
            testConfig,
            emptySet()
        ) { enrolled = true }
        assertTrue(reconcile.enrolledPackages.isEmpty())
        assertFalse(enrolled)
    }

    @Test
    fun `disabling stops enrollment but preserves existing protection`() {
        val engine = engine()
        val protected = mutableSetOf<String>()
        engine.enable(emptyList())
        val current = testApp("com.example.current")
        engine.processInstallation(testCandidate(current), testConfig, emptySet()) {
            protected += it.packageName
        }
        assertTrue(current.identity.packageName in protected)

        engine.disable()

        var enrolled = false
        val afterDisable = testApp("com.example.afterdisable")
        val decision = engine.processInstallation(
            testCandidate(afterDisable),
            testConfig,
            protected
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.FEATURE_DISABLED, skipReason(decision))
        assertFalse(enrolled)
        assertTrue(current.identity.packageName in protected)
        assertTrue(engine.state().baseline.isEmpty())
        assertFalse(engine.state().baselineReady)
    }

    @Test
    fun `re-enabling does not retroactively enroll apps installed while disabled`() {
        val engine = engine()
        engine.enable(emptyList())
        engine.disable()

        val installedWhileDisabled = testApp("com.example.while.disabled")
        engine.enable(listOf(installedWhileDisabled))
        var enrolled = false

        val decision = engine.processInstallation(
            testCandidate(installedWhileDisabled),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.PRE_EXISTING_INSTALL, skipReason(decision))
        assertFalse(enrolled)
    }

    @Test
    fun `missed installations are recovered without resetting the baseline`() {
        val engine = engine()
        val preExisting = testApp("com.example.preexisting", firstInstallTime = 100)
        engine.enable(listOf(preExisting))
        val missed = testApp("com.example.missed", firstInstallTime = 200)

        val result = engine.reconcile(
            listOf(testCandidate(preExisting), testCandidate(missed)),
            testConfig,
            emptySet()
        ) { }

        assertEquals(setOf(missed.identity.packageName), result.enrolledPackages)
        assertEquals(setOf(preExisting.identity), engine.state().baseline)
        assertTrue(engine.state().baselineReady)
    }

    @Test
    fun `first launch fallback enrolls before reconciliation runs`() {
        val engine = engine()
        engine.enable(emptyList())
        val launched = testApp("com.example.firstlaunch")
        var enrolled = false

        val decision = engine.processInstallation(
            testCandidate(launched),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockDecision.Enroll(launched.identity), decision)
        assertTrue(enrolled)
    }

    @Test
    fun `reconciliation is a no-op until the baseline is ready`() {
        val store = InMemoryAutoLockStateStore(
            AutoLockState.EMPTY.copy(enabled = true, baselineReady = false)
        )
        val engine = AutoLockEnrollmentEngine(store)
        var enrolled = false

        val result = engine.reconcile(
            listOf(testCandidate(testApp())),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertTrue(result.decisions.isEmpty())
        assertTrue(result.enrolledPackages.isEmpty())
        assertFalse(enrolled)
    }

    @Test
    fun `duplicate installation events enroll exactly once`() {
        val engine = engine()
        engine.enable(emptyList())
        val app = testApp("com.example.duplicate")
        val enrollmentCount = AtomicInteger(0)

        repeat(10) {
            engine.processInstallation(testCandidate(app), testConfig, emptySet()) {
                enrollmentCount.incrementAndGet()
            }
        }

        assertEquals(1, enrollmentCount.get())
        assertEquals(1, engine.state().handled.count { it == app.identity })
    }

    @Test
    fun `concurrent installation events do not lose state or double enroll`() {
        val engine = engine()
        engine.enable(emptyList())
        val app = testApp("com.example.concurrent")
        val enrollmentCount = AtomicInteger(0)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        val futures = (1..8).map {
            executor.submit {
                start.await()
                repeat(25) {
                    engine.processInstallation(
                        testCandidate(app),
                        testConfig,
                        emptySet()
                    ) { enrollmentCount.incrementAndGet() }
                }
            }
        }

        start.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(1, enrollmentCount.get())
        assertTrue(engine.state().handled.contains(app.identity))
    }

    @Test
    fun `genuine reinstall is evaluated as a fresh installation`() {
        val engine = engine()
        engine.enable(emptyList())
        val firstInstall = testApp("com.example.reinstall", firstInstallTime = 100)

        engine.processInstallation(testCandidate(firstInstall), testConfig, emptySet()) {}
        engine.markManuallyUnprotected(firstInstall.identity)

        val reinstalled = testApp(
            "com.example.reinstall",
            firstInstallTime = 200,
            lastUpdateTime = 200
        )
        var enrolled = false
        val decision = engine.processInstallation(
            testCandidate(reinstalled),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockDecision.Enroll(reinstalled.identity), decision)
        assertTrue(enrolled)
        assertTrue(engine.state().manuallyUnprotected.none {
            it.packageName == reinstalled.identity.packageName
        })
    }

    @Test
    fun `reinstall with marker is skipped even after previous manual unprotection`() {
        val engine = engine()
        engine.enable(emptyList())
        val firstInstall = testApp("com.example.reinstall.marked", firstInstallTime = 100)
        engine.processInstallation(testCandidate(firstInstall), testConfig, emptySet()) {}
        engine.markManuallyUnprotected(firstInstall.identity)

        val reinstalled = testApp(
            "com.example.reinstall.marked",
            firstInstallTime = 300,
            lastUpdateTime = 300
        )
        var enrolled = false
        val decision = engine.processInstallation(
            testCandidate(reinstalled, isExempt = true),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.XML_EXEMPT, skipReason(decision))
        assertFalse(enrolled)
    }

    @Test
    fun `marker true skips installation events`() {
        val engine = engine()
        engine.enable(emptyList())
        var enrolled = false
        val decision = engine.processInstallation(
            testCandidate(testApp("com.example.marked"), isExempt = true),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.XML_EXEMPT, skipReason(decision))
        assertFalse(enrolled)
    }

    @Test
    fun `marker true skips reconciliation`() {
        val engine = engine()
        engine.enable(emptyList())
        var enrolled = false
        val marked = testApp("com.example.marked.reconcile")

        val result = engine.reconcile(
            listOf(testCandidate(marked, isExempt = true)),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.XML_EXEMPT, skipReason(result.decisions[marked.identity.packageName]!!))
        assertTrue(result.enrolledPackages.isEmpty())
        assertFalse(enrolled)
    }

    @Test
    fun `marker absent still enrolls`() {
        val engine = engine()
        engine.enable(emptyList())
        val app = testApp("com.example.notmarked")
        var enrolled = false

        engine.processInstallation(testCandidate(app, isExempt = false), testConfig, emptySet()) {
            enrolled = true
        }

        assertTrue(enrolled)
    }

    @Test
    fun `marked app manually protected is never removed or unmarked`() {
        val engine = engine()
        engine.enable(emptyList())
        val marked = testApp("com.example.marked.manual")
        val protected = mutableSetOf(marked.identity.packageName)
        var enrolled = false

        val decision = engine.processInstallation(
            testCandidate(marked, isExempt = true),
            testConfig,
            protected
        ) { enrolled = true }

        assertEquals(AutoLockSkipReason.ALREADY_PROTECTED, skipReason(decision))
        assertFalse(enrolled)
        assertTrue(marked.identity.packageName in protected)
    }

    @Test
    fun `marked app does not change protection of other apps`() {
        val engine = engine()
        engine.enable(emptyList())
        val marked = testApp("com.example.marked.trigger")
        val otherProtected = testApp("com.example.other.protected")
        val protected = setOf(otherProtected.identity.packageName)

        val result = engine.reconcile(
            listOf(
                testCandidate(marked, isExempt = true),
                testCandidate(otherProtected)
            ),
            testConfig,
            protected
        ) { }

        assertEquals(
            AutoLockSkipReason.XML_EXEMPT,
            skipReason(result.decisions[marked.identity.packageName]!!)
        )
        assertEquals(
            AutoLockSkipReason.ALREADY_PROTECTED,
            skipReason(result.decisions[otherProtected.identity.packageName]!!)
        )
        assertTrue(result.enrolledPackages.isEmpty())
    }

    @Test
    fun `manual protection clears automatic enrollment opt-out`() {
        val engine = engine()
        engine.enable(emptyList())
        val app = testApp("com.example.manual")
        engine.markManuallyUnprotected(app.identity)
        assertTrue(engine.state().manuallyUnprotected.contains(app.identity))

        engine.markManuallyProtected(app.identity.packageName)

        assertFalse(engine.state().manuallyUnprotected.any {
            it.packageName == app.identity.packageName
        })
    }

    @Test
    fun `baseline survives engine restart`() {
        val store = InMemoryAutoLockStateStore()
        val firstEngine = engine(store)
        val preExisting = testApp("com.example.persisted", firstInstallTime = 100)
        firstEngine.enable(listOf(preExisting))

        val restarted = AutoLockEnrollmentEngine(store)
        var enrolled = false
        val result = restarted.reconcile(
            listOf(testCandidate(preExisting)),
            testConfig,
            emptySet()
        ) { enrolled = true }

        assertTrue(result.enrolledPackages.isEmpty())
        assertFalse(enrolled)
        assertEquals(setOf(preExisting.identity), restarted.state().baseline)
    }
}
