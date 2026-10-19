package dev.pranav.applock.data.autolock

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android backends cannot be instantiated in a local JVM test (they depend on the
 * AccessibilityService / Service runtime). These checks guard the wiring that would
 * otherwise regress silently: every backend must consult the shared enrollment gate, and
 * the existing global-protection gates must remain in place.
 */
class BackendWiringTest {

    private fun source(relativePath: String): String {
        val fromModuleDir = File("src/main/java/dev/pranav/applock/$relativePath")
        val fromRepoRoot = File("app/src/main/java/dev/pranav/applock/$relativePath")
        val file = if (fromModuleDir.exists()) fromModuleDir else fromRepoRoot
        assertTrue("Source file not found: $relativePath", file.exists())
        return file.readText()
    }

    @Test
    fun `all three backends use the shared automatic enrollment gate`() {
        listOf(
            "services/AppLockAccessibilityService.kt",
            "services/UsageLockService.kt",
            "services/ShizukuAppLockService.kt"
        ).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must call isProtectedOrEnrollAutomatically",
                text.contains("isProtectedOrEnrollAutomatically")
            )
            assertTrue(
                "$path must trigger reconciliation when the backend starts",
                text.contains("scheduleReconcile")
            )
        }
    }

    @Test
    fun `global protection gates remain in every backend`() {
        assertTrue(
            source("services/AppLockAccessibilityService.kt")
                .contains("if (!appLockRepository.isProtectEnabled())")
        )
        assertTrue(
            source("services/UsageLockService.kt")
                .contains("if (!appLockRepository.isProtectEnabled()")
        )
        assertTrue(
            source("shizuku/ShizukuActivityManager.kt")
                .contains("if (!appLockRepository.isProtectEnabled()) return")
        )
    }

    @Test
    fun `no backend still decides protection with a raw getLockedApps membership check`() {
        listOf(
            "services/AppLockAccessibilityService.kt",
            "services/UsageLockService.kt",
            "services/ShizukuAppLockService.kt"
        ).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must not use packageName !in getLockedApps() as a lock decision",
                !text.contains("packageName !in appLockRepository.getLockedApps()")
            )
        }
    }
}
