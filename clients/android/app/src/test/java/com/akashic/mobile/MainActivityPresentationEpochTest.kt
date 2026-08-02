package com.akashic.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPresentationEpochTest {
    @Test
    fun configurationRecreationDoesNotStartAnotherPresentationSession() {
        assertFalse(shouldAdvanceMobileWebUiPresentationEpoch(leftForeground = true, changingConfigurations = true))
        assertFalse(shouldAdvanceMobileWebUiPresentationEpoch(leftForeground = false, changingConfigurations = false))
    }

    @Test
    fun realBackgroundReturnStartsAnotherPresentationSession() {
        assertTrue(shouldAdvanceMobileWebUiPresentationEpoch(leftForeground = true, changingConfigurations = false))
    }

    @Test
    fun nativeActivityContinuationDoesNotStartAnotherPresentationSession() {
        assertFalse(
            shouldAdvanceMobileWebUiPresentationEpoch(
                leftForeground = true,
                changingConfigurations = false,
                nativeActivityContinuation = true,
            ),
        )
    }

    @Test
    fun processRecreationStartsANewUiSessionButRotationKeepsTheSession() {
        assertFalse(mobileWebUiSessionStartedForProcess("old-process", "new-process", true))
        assertTrue(mobileWebUiSessionStartedForProcess("same-process", "same-process", true))
        assertFalse(mobileWebUiSessionStartedForProcess(null, "same-process", true))
    }
}
