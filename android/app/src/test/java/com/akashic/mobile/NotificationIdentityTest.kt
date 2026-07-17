package com.akashic.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationIdentityTest {
    @Test
    fun `different ids with the same hash keep distinct pending intents`() {
        val first = "Aa"
        val second = "BB"

        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(notificationIntentData(first), notificationIntentData(second))
    }

    @Test
    fun `connection and message pending intents keep distinct identities`() {
        assertNotEquals(notificationIntentData(null), notificationIntentData("connection"))
    }
}
