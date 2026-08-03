package com.akashic.mobile.ui.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWebUiPresentationPolicyTest {
    @Test
    fun readyWhileTypingDoesNotReplaceCurrentLease() {
        assertFalse(
            shouldStartMobileWebUiCandidate(
                sessionStarted = true,
                readyGeneration = "candidate",
                servingGeneration = "serving",
            ),
        )
    }

    @Test
    fun nextUiSessionAppliesPreparedGeneration() {
        assertTrue(
            shouldStartMobileWebUiCandidate(
                sessionStarted = false,
                readyGeneration = "candidate",
                servingGeneration = "serving",
            ),
        )
    }

    @Test
    fun explicitApplyRequiresNativeReplacementPermission() {
        assertTrue(
            shouldStartMobileWebUiCandidate(
                sessionStarted = true,
                readyGeneration = "candidate",
                servingGeneration = "serving",
                explicitApply = true,
                canReplaceUi = true,
            ),
        )
        assertFalse(
            shouldStartMobileWebUiCandidate(
                sessionStarted = true,
                readyGeneration = "candidate",
                servingGeneration = "serving",
                explicitApply = true,
                canReplaceUi = false,
            ),
        )
    }

    @Test
    fun missingReadyGenerationKeepsCurrentOrBaselineLease() {
        assertFalse(
            shouldStartMobileWebUiCandidate(
                sessionStarted = true,
                readyGeneration = null,
                servingGeneration = "serving",
            ),
        )
        assertFalse(
            shouldStartMobileWebUiCandidate(
                sessionStarted = false,
                readyGeneration = null,
                servingGeneration = null,
            ),
        )
    }

    @Test
    fun newPresentationEpochDoesNotConstructCandidateLease() {
        assertFalse(mobileWebUiCandidateLeaseAllowed(true, sessionEpoch = 2, presentationEpoch = 3))
        assertFalse(mobileWebUiCandidateLeaseAllowed(false, sessionEpoch = 2, presentationEpoch = 2))
        assertTrue(mobileWebUiCandidateLeaseAllowed(true, sessionEpoch = 2, presentationEpoch = 2))
    }

    @Test
    fun explicitApplyIsConsumedOnlyAfterDurableAttemptExists() {
        assertFalse(mobileWebUiApplyCanBeConsumed(explicitApply = false, durableAttemptCreated = true))
        assertFalse(mobileWebUiApplyCanBeConsumed(explicitApply = true, durableAttemptCreated = false))
        assertTrue(mobileWebUiApplyCanBeConsumed(explicitApply = true, durableAttemptCreated = true))
    }
}
