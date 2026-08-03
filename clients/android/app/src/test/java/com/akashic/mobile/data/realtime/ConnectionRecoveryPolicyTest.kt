package com.akashic.mobile.data.realtime

import com.akashic.mobile.data.local.HistoryProjectionProgress
import com.akashic.mobile.domain.model.ConnectionPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    private val unavailable = TransferNetworkState(TransferNetworkKind.UNAVAILABLE, false)
    private val unmetered = TransferNetworkState(TransferNetworkKind.UNMETERED, true)

    @Test
    fun `failure before recovery consumes one immediate reconnect`() {
        val latch = NetworkRecoveryLatch()
        latch.onGenerationStarted(7, unmetered)
        latch.onNetworkState(7, unmetered, unavailable, hasConnectionTarget = true)

        assertFalse(latch.consume(7))
        latch.onNetworkState(7, unavailable, unmetered, hasConnectionTarget = true)
        assertTrue(latch.consume(7))
        assertFalse(latch.consume(7))
    }

    @Test
    fun `recovery before failure survives duplicate callbacks and consumes once`() {
        val latch = NetworkRecoveryLatch()
        latch.onGenerationStarted(11, unmetered)
        latch.onNetworkState(11, unmetered, unavailable, hasConnectionTarget = true)
        latch.onNetworkState(11, unavailable, unmetered, hasConnectionTarget = true)
        latch.onNetworkState(11, unmetered, unmetered, hasConnectionTarget = true)

        assertTrue(latch.consume(11))
        latch.onNetworkState(11, unmetered, unmetered, hasConnectionTarget = true)
        assertFalse(latch.consume(11))
    }

    @Test
    fun `recovery owner does not cross generation or healthy progress`() {
        val latch = NetworkRecoveryLatch()
        latch.onGenerationStarted(17, unmetered)
        latch.onNetworkState(17, unmetered, unavailable, hasConnectionTarget = true)
        latch.onNetworkState(17, unavailable, unmetered, hasConnectionTarget = true)

        assertFalse(latch.consume(18))
        latch.onConnectionProgress(17)
        assertFalse(latch.consume(17))

        latch.onNetworkState(18, unmetered, unavailable, hasConnectionTarget = false)
        latch.onNetworkState(18, unavailable, unmetered, hasConnectionTarget = false)
        assertFalse(latch.consume(18))
    }

    @Test
    fun `phase deadlines distinguish handshake authentication and sync progress`() {
        assertEquals(10_000L, ConnectionDeadlinePhase.CHALLENGE.deadlineMillis())
        assertEquals(10_000L, ConnectionDeadlinePhase.AUTHENTICATION.deadlineMillis())
        assertEquals(20_000L, ConnectionDeadlinePhase.SYNC.deadlineMillis())
        assertTrue(ConnectionDeadlinePhase.CHALLENGE.timeoutMessage().contains("握手"))
        assertTrue(ConnectionDeadlinePhase.AUTHENTICATION.timeoutMessage().contains("认证"))
        assertTrue(ConnectionDeadlinePhase.SYNC.timeoutMessage().contains("同步"))
    }

    @Test
    fun `continuous sync replay keeps idle deadline beyond twenty seconds`() {
        var expiresAt = ConnectionDeadlinePhase.SYNC.deadlineMillis()
        for (progressAt in listOf(19_000L, 38_000L, 57_000L)) {
            assertTrue(progressAt < expiresAt)
            assertTrue(
                shouldRefreshSyncDeadline(
                    phaseBeforeFrame = ConnectionPhase.SYNCING,
                    phaseAfterFrame = ConnectionPhase.SYNCING,
                )
            )
            expiresAt = progressAt + ConnectionDeadlinePhase.SYNC.deadlineMillis()
        }
        assertTrue(expiresAt > 60_000L)
        assertFalse(
            shouldRefreshSyncDeadline(
                phaseBeforeFrame = ConnectionPhase.SYNCING,
                phaseAfterFrame = ConnectionPhase.DEGRADED,
            )
        )
    }

    @Test
    fun `history resumes from the first incomplete page`() {
        assertNull(
            historyStartPage(
                remoteMessageCount = 0,
                local = HistoryProjectionProgress(0, null),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertNull(
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(537, 536),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertEquals(
            54,
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(536, 535),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertEquals(
            46,
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(450, 449),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertEquals(
            1,
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(538, 537),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertEquals(
            1,
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(450, 451),
                forceReload = false,
                pageSize = 10,
            ),
        )
        assertEquals(
            1,
            historyStartPage(
                remoteMessageCount = 537,
                local = HistoryProjectionProgress(537, 536),
                forceReload = true,
                pageSize = 10,
            ),
        )
    }

    @Test
    fun `history batch stops after transport send failure`() = runBlocking {
        val requested = mutableListOf<Pair<String, Int>>()
        val sessions = listOf(
            RemoteSessionSummary("empty", "空会话", "2026-07-28T00:00:00Z", 0),
            RemoteSessionSummary("complete", "已完成", "2026-07-28T00:00:00Z", 10),
            RemoteSessionSummary("first", "第一段", "2026-07-28T00:00:00Z", 2),
            RemoteSessionSummary("failed", "发送失败", "2026-07-28T00:00:00Z", 3),
            RemoteSessionSummary("unreachable", "不应请求", "2026-07-28T00:00:00Z", 4),
        )

        requestHistoryBatch(
            sessions = sessions,
            startPage = {
                when (it.sessionId) {
                    "complete" -> null
                    "first" -> 2
                    else -> 1
                }
            },
            requestPage = { sessionId, page ->
                requested += sessionId to page
                sessionId != "failed"
            },
        )

        assertEquals(listOf("first" to 2, "failed" to 1), requested)
    }

    @Test
    fun `late candidates cannot downgrade connection progress`() {
        assertFalse(
            shouldApplyCandidateOpen(
                connectionPhase = ConnectionPhase.DEVICE_PROOF,
                hasActiveCandidate = false,
                candidateGeneration = 7,
                pairingConfirmationGeneration = null,
            )
        )
        assertFalse(
            shouldApplyCandidateOpen(
                connectionPhase = ConnectionPhase.SYNCING,
                hasActiveCandidate = true,
                candidateGeneration = 7,
                pairingConfirmationGeneration = null,
            )
        )
        assertFalse(
            shouldApplyCandidateOpen(
                connectionPhase = ConnectionPhase.SERVER_CHALLENGE,
                hasActiveCandidate = false,
                candidateGeneration = 7,
                pairingConfirmationGeneration = 7,
            )
        )
        assertTrue(
            shouldApplyCandidateOpen(
                connectionPhase = ConnectionPhase.DEGRADED,
                hasActiveCandidate = false,
                candidateGeneration = 8,
                pairingConfirmationGeneration = 7,
            )
        )

        val authentication = ConnectionPhaseDeadline(
            generation = 7,
            phase = ConnectionDeadlinePhase.AUTHENTICATION,
        )
        assertFalse(
            shouldReplacePhaseDeadline(
                current = authentication,
                generation = 7,
                next = ConnectionDeadlinePhase.CHALLENGE,
            )
        )
        assertTrue(
            shouldReplacePhaseDeadline(
                current = authentication,
                generation = 7,
                next = ConnectionDeadlinePhase.SYNC,
            )
        )
    }

    @Test
    fun `queued revoke accepts pre-return owner but rejects stale re-pair task`() {
        assertTrue(
            shouldApplyQueuedDeviceRevocation(
                currentEpoch = 8,
                queuedEpoch = 8,
                currentGeneration = 0,
                queuedGeneration = 21,
                ownerGenerationCurrent = true,
            )
        )
        assertFalse(
            shouldApplyQueuedDeviceRevocation(
                currentEpoch = 9,
                queuedEpoch = 8,
                currentGeneration = 22,
                queuedGeneration = 21,
                ownerGenerationCurrent = false,
            )
        )
        assertFalse(
            shouldApplyQueuedDeviceRevocation(
                currentEpoch = 8,
                queuedEpoch = 8,
                currentGeneration = 22,
                queuedGeneration = 21,
                ownerGenerationCurrent = false,
            )
        )
    }

    @Test
    fun `rejected loser open cannot consume recovered outage`() {
        val latch = NetworkRecoveryLatch()
        latch.onGenerationStarted(23, unmetered)
        latch.onNetworkState(23, unmetered, unavailable, hasConnectionTarget = true)
        latch.onNetworkState(23, unavailable, unmetered, hasConnectionTarget = true)

        val accepted = shouldApplyCandidateOpen(
            connectionPhase = ConnectionPhase.SYNCING,
            hasActiveCandidate = true,
            candidateGeneration = 23,
            pairingConfirmationGeneration = null,
        )
        if (accepted) latch.onConnectionProgress(23)

        assertFalse(accepted)
        assertTrue(latch.consume(23))
        assertFalse(latch.consume(23))
    }
}
