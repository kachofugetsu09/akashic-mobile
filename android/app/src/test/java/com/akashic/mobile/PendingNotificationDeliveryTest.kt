package com.akashic.mobile

import com.akashic.mobile.data.realtime.FinalMessageEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingNotificationDeliveryTest {
    private val event = FinalMessageEvent(
        sessionId = "mobile:session",
        messageId = "message-1",
        content = "后台回复",
        hasAttachments = false,
    )

    @Test
    fun disabledPermissionRetainsRequiredNotification() = runBlocking {
        var posted = false
        var consumed = false

        deliverPendingNotification(
            shouldNotify = MessageNotificationPolicy.shouldNotify(false, event.sessionId, event),
            canPost = false,
            post = { posted = true },
            consume = { consumed = true },
        )

        assertFalse(posted)
        assertFalse(consumed)
    }

    @Test
    fun foregroundCurrentSessionSuppressionConsumesWithoutPosting() = runBlocking {
        var posted = false
        var consumed = false

        deliverPendingNotification(
            shouldNotify = MessageNotificationPolicy.shouldNotify(true, event.sessionId, event),
            canPost = false,
            post = { posted = true },
            consume = { consumed = true },
        )

        assertFalse(posted)
        assertTrue(consumed)
    }

    @Test
    fun successfulSystemPostConsumesNotification() = runBlocking {
        var posted = false
        var consumed = false

        deliverPendingNotification(
            shouldNotify = MessageNotificationPolicy.shouldNotify(false, event.sessionId, event),
            canPost = true,
            post = { posted = true },
            consume = { consumed = true },
        )

        assertTrue(posted)
        assertTrue(consumed)
    }

    @Test
    fun systemPostFailurePropagatesAndRetainsNotification() {
        var consumed = false

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                deliverPendingNotification(
                    shouldNotify = true,
                    canPost = true,
                    post = { error("notify failed") },
                    consume = { consumed = true },
                )
            }
        }
        assertFalse(consumed)
    }
}
