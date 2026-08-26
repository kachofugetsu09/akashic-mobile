package com.akashic.mobile.data.local

import java.time.Instant
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerInstantTest {
    @Test
    fun `accepts an RFC 3339 UTC instant`() {
        assertEquals(
            Instant.parse("2026-07-13T17:37:51.488915Z").toEpochMilli(),
            parseServerInstant("2026-07-13T17:37:51.488915Z", "session.list.updated_at"),
        )
    }

    @Test
    fun `rejects a timestamp without a timezone as a protocol error`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parseServerInstant("2026-07-14T01:37:51.488915", "session.list.updated_at")
        }

        assertTrue(error.message!!.contains("session.list.updated_at"))
        assertTrue(error.cause is DateTimeParseException)
    }
}
