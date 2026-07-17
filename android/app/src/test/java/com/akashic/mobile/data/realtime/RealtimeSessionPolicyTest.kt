package com.akashic.mobile.data.realtime

import com.akashic.mobile.domain.model.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeSessionPolicyTest {
    @Test
    fun `only command rejection fails the active outbox item`() {
        assertEquals(
            TerminalProtocolAction.FAIL_ACTIVE_COMMAND,
            terminalProtocolAction(CLOSE_COMMAND_REJECTED, hasActiveCommand = true),
        )
        assertEquals(
            TerminalProtocolAction.PRESERVE_OUTBOX,
            terminalProtocolAction(4400, hasActiveCommand = true),
        )
        assertEquals(
            TerminalProtocolAction.PRESERVE_OUTBOX,
            terminalProtocolAction(CLOSE_COMMAND_REJECTED, hasActiveCommand = false),
        )
    }

    @Test
    fun `late loser frames cannot mutate an authenticated generation`() {
        val winner = SocketCandidateId(4, 1)
        val loser = SocketCandidateId(4, 2)

        assertTrue(shouldAcceptCandidateFrame(4, winner, winner))
        assertFalse(shouldAcceptCandidateFrame(4, winner, loser))
        assertFalse(shouldAcceptCandidateFrame(4, winner, SocketCandidateId(3, 1)))
    }

    @Test
    fun `pairing confirmation and active connection suppress late open callbacks`() {
        assertFalse(
            shouldApplyCandidateOpen(ConnectionPhase.CONNECTING, true, 4, null),
        )
        assertFalse(
            shouldApplyCandidateOpen(ConnectionPhase.SERVER_CHALLENGE, false, 4, 4),
        )
        assertTrue(
            shouldApplyCandidateOpen(ConnectionPhase.CONNECTING, false, 4, null),
        )
    }

    @Test
    fun `connection phases have finite deadlines`() {
        assertTrue(ConnectionDeadlinePhase.CHALLENGE.deadlineMillis() > 0)
        assertTrue(ConnectionDeadlinePhase.AUTHENTICATION.deadlineMillis() > 0)
        assertTrue(
            ConnectionDeadlinePhase.SYNC.deadlineMillis() >
                ConnectionDeadlinePhase.AUTHENTICATION.deadlineMillis(),
        )
    }

    @Test
    fun `outgoing text enforces the protocol maximum`() {
        assertEquals("hello", validateOutgoingMessageText(" hello "))
        assertEquals(1, outgoingMessageTextLength("🦋"))
        runCatching { validateOutgoingMessageText("x".repeat(MAX_MESSAGE_TEXT_LENGTH + 1)) }
            .onSuccess { error("oversized message was accepted") }
    }
}
