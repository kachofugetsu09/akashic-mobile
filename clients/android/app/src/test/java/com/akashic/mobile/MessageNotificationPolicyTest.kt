package com.akashic.mobile

import com.akashic.mobile.data.realtime.FinalMessageEvent
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import com.akashic.mobile.data.realtime.deliveredFinalMessageEvent
import com.akashic.mobile.data.realtime.messageReplyReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationPolicyTest {
    private val event = FinalMessageEvent(
        sessionId = "akashic:session-a",
        messageId = "message-1",
        content = "最终回答第一行\n不会进入通知预览",
        hasAttachments = false,
    )

    @Test
    fun foregroundCurrentSessionDoesNotDuplicateFinalMessage() {
        assertFalse(
            MessageNotificationPolicy.shouldNotify(
                appVisible = true,
                currentSessionId = event.sessionId,
                event = event,
            ),
        )
    }

    @Test
    fun backgroundOrDifferentSessionReceivesFinalMessage() {
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                appVisible = false,
                currentSessionId = event.sessionId,
                event = event,
            ),
        )
        assertTrue(
            MessageNotificationPolicy.shouldNotify(
                appVisible = true,
                currentSessionId = "akashic:session-b",
                event = event,
            ),
        )
    }

    @Test
    fun previewUsesOnlyFinalAnswerAndHasPrivateFallback() {
        assertEquals("最终回答第一行", MessageNotificationPolicy.preview(event))
        assertEquals(
            "收到一个附件",
            MessageNotificationPolicy.preview(event.copy(content = "", hasAttachments = true)),
        )
        assertEquals(
            "收到一条新回复",
            MessageNotificationPolicy.preview(event.copy(content = "", hasAttachments = false)),
        )
    }

    @Test
    fun notificationTapIdentitySeparatesMessagesInTheSameSession() {
        val first = notificationIntentData("akashic:session-a", "message-1")
        val second = notificationIntentData("akashic:session-a", "message-2")

        assertNotEquals(first, second)
        assertEquals(first, notificationIntentData("akashic:session-a", "message-1"))
    }

    @Test
    fun finalNotificationRequiresTheCanonicalMessageIdentity() {
        val event = deliveredFinalMessageEvent(
            WireEnvelope(
                v = 1,
                kind = WireKind.EVENT,
                type = "message.final",
                id = "event-42",
                sessionId = "akashic:session-a",
                payload = JsonObject(
                    mapOf(
                        "content" to JsonPrimitive("后台任务完成"),
                        "attachments" to JsonArray(emptyList()),
                        "message_id" to JsonPrimitive("mobile:session-a:42"),
                    ),
                ),
            ),
        )

        assertEquals("mobile:session-a:42", event.messageId)
        assertEquals("后台任务完成", event.content)
        assertFalse(event.hasAttachments)
    }

    @Test
    fun finalNotificationRejectsMissingCanonicalIdentity() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            deliveredFinalMessageEvent(
            WireEnvelope(
                v = 1,
                kind = WireKind.EVENT,
                type = "message.final",
                id = "event-42",
                sessionId = "akashic:session-a",
                payload = JsonObject(
                    mapOf(
                        "content" to JsonPrimitive("后台任务完成"),
                        "attachments" to JsonArray(emptyList()),
                    ),
                ),
            ),
            )
        }
    }

    @Test
    fun repliesUseCanonicalOrOptimisticMessageIdentity() {
        val canonical = messageReplyReference("mobile:session-a:42", null)
        val optimistic = messageReplyReference(
            "user:01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        )

        assertEquals("mobile:session-a:42", canonical.messageId)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", optimistic.clientMessageId)
    }
}
