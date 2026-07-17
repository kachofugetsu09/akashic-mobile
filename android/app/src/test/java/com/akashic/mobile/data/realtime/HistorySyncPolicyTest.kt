package com.akashic.mobile.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HistorySyncPolicyTest {
    @Test
    fun `every nonempty remote session starts from page one`() {
        val catalog = SessionListPayload(
            listOf(
                RemoteSessionSummary("mobile:a", "A", "2026-07-18T00:00:00Z", 21),
                RemoteSessionSummary("mobile:b", "B", "2026-07-18T00:00:00Z", 0),
                RemoteSessionSummary("mobile:c", "C", "2026-07-18T00:00:00Z", 1),
            ),
        )

        assertEquals(listOf("mobile:a", "mobile:c"), historySessionsToFetch(catalog))
    }

    @Test
    fun `history pagination continues until the final page`() {
        val first = HistoryPagePayload(emptyList(), total = 21, page = 1, pageSize = 10)
        val second = HistoryPagePayload(emptyList(), total = 21, page = 2, pageSize = 10)
        val third = HistoryPagePayload(emptyList(), total = 21, page = 3, pageSize = 10)

        assertEquals(2, nextHistoryPage(first))
        assertEquals(3, nextHistoryPage(second))
        assertNull(nextHistoryPage(third))
    }

    @Test
    fun `duplicate catalog ids fail loudly`() {
        val duplicate = RemoteSessionSummary("mobile:a", "A", "2026-07-18T00:00:00Z", 1)
        assertThrows(IllegalArgumentException::class.java) {
            historySessionsToFetch(SessionListPayload(listOf(duplicate, duplicate)))
        }
    }

    @Test
    fun `only rejection of selected deleted session clears selection`() {
        assertEquals(true, shouldClearRejectedSession("session_not_found", "mobile:a", "mobile:a"))
        assertEquals(false, shouldClearRejectedSession("session_not_found", "mobile:b", "mobile:a"))
        assertEquals(false, shouldClearRejectedSession("invalid_request", "mobile:a", "mobile:a"))
    }
}
