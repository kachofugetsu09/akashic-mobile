package com.akashic.mobile.ui.web

import org.junit.Assert.assertEquals
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

    @Test
    fun staleActivationResultCannotCrossServerResetOrNonce() {
        val expected = MobileWebUiAttempt("server-a", "generation-a", "nonce-a")
        assertTrue(
            mobileWebUiActivationResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = expected,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedAttempt = expected,
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiActivationResultOwnerMatches(
                currentServerId = "server-b",
                currentAttempt = expected,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedAttempt = expected,
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiActivationResultOwnerMatches(
                currentServerId = null,
                currentAttempt = null,
                currentGeneration = null,
                currentGenerationRef = null,
                expectedAttempt = expected,
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiActivationResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = MobileWebUiAttempt("server-a", "generation-a", "nonce-b"),
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedAttempt = expected,
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
    }

    @Test
    fun staleCommittedResultCannotCrossServingOwnerOrPresentationEpoch() {
        assertTrue(
            mobileWebUiCommittedResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = null,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiCommittedResultOwnerMatches(
                currentServerId = "server-b",
                currentAttempt = null,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiCommittedResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = null,
                currentGeneration = "generation-b",
                currentGenerationRef = "generation-b",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiCommittedResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = MobileWebUiAttempt("server-a", "generation-b", "nonce-b"),
                currentGeneration = "generation-b",
                currentGenerationRef = "generation-b",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiCommittedResultOwnerMatches(
                currentServerId = "server-a",
                currentAttempt = null,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 5L,
                expectedPresentationEpoch = 4L,
            ),
        )
        assertFalse(
            mobileWebUiCommittedFailureOwnerMatches(
                currentServerId = "server-a",
                currentServingGeneration = "generation-b",
                currentAttempt = null,
                currentGeneration = "generation-a",
                currentGenerationRef = "generation-a",
                expectedServerId = "server-a",
                expectedGeneration = "generation-a",
                currentPresentationEpoch = 4L,
                expectedPresentationEpoch = 4L,
            ),
        )
    }

    @Test
    fun bridgeNumericBoundariesPreserveSignedOffsetAndRejectInvalidTimestamp() {
        assertEquals(Int.MIN_VALUE, mobileWebUiReadingPositionOffset(Int.MIN_VALUE.toLong()))
        assertEquals(Int.MAX_VALUE, mobileWebUiReadingPositionOffset(Int.MAX_VALUE.toLong()))
        assertEquals(null, mobileWebUiReadingPositionOffset(Int.MIN_VALUE.toLong() - 1L))
        assertEquals(null, mobileWebUiReadingPositionOffset(Int.MAX_VALUE.toLong() + 1L))
        assertEquals(0L, mobileWebUiReadThroughTimestamp(0L))
        assertEquals(9_007_199_254_740_991L, mobileWebUiReadThroughTimestamp(9_007_199_254_740_991L))
        assertEquals(null, mobileWebUiReadThroughTimestamp(-1L))
        assertEquals(null, mobileWebUiReadThroughTimestamp(9_007_199_254_740_992L))
        assertEquals(null, mobileWebUiReadThroughTimestamp(Long.MAX_VALUE))
    }

    @Test
    fun staleAsyncResultCannotCrossWebViewOwner() {
        val oldView = Any()
        val replacementView = Any()
        assertTrue(mobileWebUiOwnerViewCurrent(oldView, oldView))
        assertFalse(mobileWebUiOwnerViewCurrent(replacementView, oldView))
        assertFalse(mobileWebUiOwnerViewCurrent(null, oldView))
    }
}
