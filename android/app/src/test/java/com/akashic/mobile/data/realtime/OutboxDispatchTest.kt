package com.akashic.mobile.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxDispatchTest {
    @Test
    fun `only one durable command can be in flight`() {
        val state = SingleFlightOutbox()

        assertTrue(state.claim("first"))
        assertFalse(state.claim("second"))
        state.complete("first")
        assertTrue(state.claim("second"))
    }

    @Test(expected = IllegalStateException::class)
    fun `reply for another command fails loudly`() {
        val state = SingleFlightOutbox()
        state.claim("first")

        state.complete("second")
    }

    @Test
    fun `unknown outcome retains original id while ordinary rejection fails`() {
        assertEquals(
            OutboxFailureDisposition.RETRY_ORIGINAL,
            messageSendFailureDisposition("command_outcome_unknown"),
        )
        assertEquals(OutboxFailureDisposition.FAIL, messageSendFailureDisposition("invalid_session"))
    }

    @Test
    fun `production enqueue payload uses one idempotency identity`() {
        val clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val pending = preparePendingMessageSend(
            serverId = "server",
            sessionId = "mobile:00000000-0000-0000-0000-000000000001",
            body = "hello",
            now = 1_700_000_000_000,
            clientMessageId = clientMessageId,
        )
        val envelope = ProtocolCodec.decode(pending.command.envelopeJson)
        val payload = ProtocolCodec.decodePayload<MessageSendPayload>(envelope.payload)

        assertEquals(clientMessageId, pending.command.commandId)
        assertEquals(pending.command.commandId, envelope.id)
        assertEquals(envelope.id, payload.clientMessageId)
        assertEquals("user:$clientMessageId", pending.message.messageId)
        assertEquals(clientMessageId, pending.message.clientMessageId)
    }
}
