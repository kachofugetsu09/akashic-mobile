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
}
