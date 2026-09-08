package com.akashic.mobile.data.realtime

import com.akashic.mobile.domain.model.ConnectionPhase
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    private val unavailable = TransferNetworkState(TransferNetworkKind.UNAVAILABLE, false)
    private val unmetered = TransferNetworkState(TransferNetworkKind.UNMETERED, true)

    @Test
    fun `session message keeps empty append nonempty append and reply status separate`() {
        val empty = ProtocolCodec.json().parseToJsonElement(
            """
            {
              "type": "messages.appended",
              "version": 2,
              "session_id": "akashic:test",
              "items": [],
              "after_seq": 4,
              "next_after_seq": 4,
              "through_seq": 4,
              "has_more": false
            }
            """.trimIndent(),
        ).jsonObject
        val nonempty = ProtocolCodec.json().parseToJsonElement(
            """
            {
              "type": "messages.appended",
              "version": 2,
              "session_id": "akashic:test",
              "items": [{
                "id": "message-0",
                "session_id": "akashic:test",
                "seq": 0,
                "timestamp": "2026-09-08T05:33:47Z",
                "author": "user",
                "source": "conversation",
                "body": {"kind": "input", "parts": []},
                "metadata": {},
                "attachments": []
              }],
              "after_seq": -1,
              "next_after_seq": 0,
              "through_seq": 0,
              "has_more": false
            }
            """.trimIndent(),
        ).jsonObject
        val status = ProtocolCodec.json().parseToJsonElement(
            """
            {
              "type": "reply.status",
              "version": 2,
              "session_id": "akashic:test",
              "snapshot_id": null,
              "available": false,
              "items": []
            }
            """.trimIndent(),
        ).jsonObject

        val emptyMessage = decodeSessionMessage(empty) as SessionMessageContent.Messages
        val appendedMessage = decodeSessionMessage(nonempty) as SessionMessageContent.Messages
        val replyStatus = decodeSessionMessage(status) as SessionMessageContent.ReplyStatus

        assertTrue(emptyMessage.payload.items.isEmpty())
        assertEquals(4L, emptyMessage.payload.nextAfterSeq)
        assertEquals("message-0", appendedMessage.payload.items.single().id)
        assertEquals(status, replyStatus.payload)
    }

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
    fun `tail page validates received manifests without requiring old history or body download`() {
        val row = RemoteHistoryMessage(id = "last", sessionId = "akashic:test", seq = 4205,
            messageRef = MessageContentRef(2, "utf-8", "application/json", 120367, "a".repeat(64)))
        val page = HistoryPagePayload(items = listOf(row), version = 2, afterSeq = 4204,
            nextAfterSeq = 4205, throughSeq = 4205, hasMore = true,
            direction = "backward", beforeSeq = 4206, nextBeforeSeq = 4205)
        checkHistoryPage(page)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            checkHistoryPage(page.copy(afterSeq = -1))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            checkHistoryPage(page.copy(nextAfterSeq = 4204))
        }
    }

    @Test
    fun `late follow reply after session switch cannot replace current subscription`() {
        val oldOwner = PendingFollowOwner(
            id = "follow-a",
            sessionId = "akashic:a",
            epoch = 7,
        )

        assertEquals(
            FollowReplyAction.IGNORE,
            followReplyAction(
                owner = oldOwner,
                replyId = "follow-a",
                replySessionId = "akashic:a",
                currentSessionId = "akashic:b",
                currentEpoch = 7,
            ),
        )
        assertEquals(
            FollowReplyAction.INVALID,
            followReplyAction(
                owner = oldOwner,
                replyId = "follow-a",
                replySessionId = "akashic:b",
                currentSessionId = "akashic:a",
                currentEpoch = 7,
            ),
        )
        assertEquals(
            FollowReplyAction.ACCEPT,
            followReplyAction(
                owner = oldOwner,
                replyId = "follow-a",
                replySessionId = "akashic:a",
                currentSessionId = "akashic:a",
                currentEpoch = 7,
            ),
        )
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
