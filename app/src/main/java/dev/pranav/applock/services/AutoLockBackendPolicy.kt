package dev.pranav.applock.services

import dev.pranav.applock.data.autolock.AutoLockEnrollmentManager
import dev.pranav.applock.data.repository.AppLockRepository

/**
 * Shared lock decision used by every backend (Accessibility, Usage Stats and Shizuku).
 *
 * It checks the real protected list first, then gives automatic enrollment one chance to
 * protect an eligible new installation. Enrollment never shows a lock overlay by itself;
 * the caller continues its normal lock decision and lock-screen flow.
 */
fun AppLockRepository.isProtectedOrEnrollAutomatically(packageName: String): Boolean =
    AutoLockEnrollmentManager.isProtectedOrEnrollOnLaunch(this, packageName)
