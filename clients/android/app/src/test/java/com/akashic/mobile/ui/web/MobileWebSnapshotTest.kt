package com.akashic.mobile.ui.web

import com.akashic.mobile.ui.conversation.ConnectionStatusUi
import com.akashic.mobile.ui.conversation.EmptyConversationState
import com.akashic.mobile.ui.conversation.MessageAttachmentState
import com.akashic.mobile.ui.conversation.MessageAttachmentUi
import com.akashic.mobile.ui.conversation.TimelineAttachmentUi
import com.akashic.mobile.ui.conversation.TimelineMessageUi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWebSnapshotTest {
    @Test
    fun `serializes complete message log v2 row in snapshot v10`() {
        val body = buildJsonObject {
            put("kind", "output")
            put("finish", "complete")
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("kind", "text")
                    put("value", "完整正文")
                })
            })
        }
        val metadata = buildJsonObject {
            put("reply_to", "message-0")
        }
        val replyStatus = buildJsonObject {
            put("type", "reply.status")
            put("version", 2)
            put("session_id", "akashic:test")
            put("snapshot_id", "reply-snapshot-1")
            put("available", true)
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("session_id", "akashic:test")
                    put("source", "conversation")
                    put("handle", "reply-1")
                    put("active", true)
                    put("preview", buildJsonObject {
                        put("message_id", "reply-1")
                        put("text", "")
                        put("thinking", "")
                    })
                })
            })
        }
        val snapshot = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 7,
            downloads = listOf(
                MessageAttachmentUi(
                    id = "artifact-1",
                    artifactId = "artifact-1",
                    filename = "result.png",
                    contentType = "image/png",
                    sizeBytes = 42,
                    transferredBytes = 42,
                    state = MessageAttachmentState.CACHED,
                    cachePath = "/private/result.png",
                ),
            ),
            timelineMessages = listOf(
                TimelineMessageUi(
                    id = "message-1",
                    sessionId = "akashic:test",
                    seq = 9_007_199_254_740_991L,
                    timestamp = "2026-09-08T08:00:00Z",
                    author = "assistant",
                    source = "akashic",
                    body = body,
                    metadata = metadata,
                    attachments = listOf(
                        TimelineAttachmentUi(
                            artifactId = "artifact-1",
                            kind = "image",
                            filename = null,
                            mediaType = null,
                            sizeBytes = 42,
                            sha256 = "a".repeat(64),
                        ),
                    ),
                ),
            ),
            replyStatus = replyStatus,
        ).toMobileWebSnapshot()

        val encoded = mobileWebJson.encodeToString(snapshot)
        val json = Json.parseToJsonElement(encoded).jsonObject
        val message = json.getValue("messages").jsonArray.single().jsonObject

        assertEquals(10, snapshot.protocolVersion)
        assertEquals(9_007_199_254_740_991L, snapshot.throughSeq)
        assertEquals(replyStatus, snapshot.replyStatus)
        assertEquals("artifact-1", snapshot.downloads.single().artifactId)
        assertEquals("cached", snapshot.downloads.single().state)
        assertEquals(42L, snapshot.downloads.single().transferredBytes)
        assertTrue(snapshot.downloads.single().contentUrl?.contains("artifact-1") == true)
        assertEquals("message-1", message.getValue("id").jsonPrimitive.content)
        assertEquals("akashic:test", message.getValue("session_id").jsonPrimitive.content)
        assertEquals(body, message.getValue("body"))
        assertEquals(metadata, message.getValue("metadata"))
        assertEquals("artifact-1", message.getValue("attachments").jsonArray.single().jsonObject
            .getValue("artifact_id").jsonPrimitive.content)
        assertEquals(kotlinx.serialization.json.JsonNull, message.getValue("attachments").jsonArray.single().jsonObject.getValue("filename"))
        assertEquals(kotlinx.serialization.json.JsonNull, message.getValue("attachments").jsonArray.single().jsonObject.getValue("media_type"))
    }

    @Test
    fun `state patch v2 excludes message fields and only follows control changes`() {
        val timeline = listOf(
            TimelineMessageUi(
                id = "message-1",
                sessionId = "akashic:test",
                seq = 1,
                timestamp = "2026-09-08T08:00:00Z",
                author = "user",
                source = "mobile",
                body = buildJsonObject {
                    put("kind", "input")
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", "text")
                            put("value", "你好")
                        })
                    })
                },
                metadata = buildJsonObject {},
                attachments = emptyList(),
            ),
        )
        val before = EmptyConversationState.copy(
            connectionStatus = ConnectionStatusUi.READY,
            selectedSessionId = "akashic:test",
            timelineMessages = timeline,
        )
        val changedTimeline = before.copy(
            timelineMessages = timeline + timeline.single().copy(id = "message-2", seq = 2),
        )
        val changedReply = before.copy(
            replyStatus = buildJsonObject { put("type", "reply.status") },
        )
        val patch = before.copy(connectionNotice = "已连接").toMobileWebStatePatch(before)

        requireNotNull(patch)
        assertEquals(2, patch.protocolVersion)
        assertTrue(!Json.encodeToString(patch).contains("message-1"))
        assertNull(changedTimeline.toMobileWebStatePatch(before))
        assertNull(changedReply.toMobileWebStatePatch(before))
    }

    @Test
    fun `append event contains only the new tail and exact cursors`() {
        val first = timelineMessage("message-1", 1, "old")
        val second = timelineMessage("message-2", 4, "new")
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 11,
            timelineMessages = listOf(first),
        )

        val events = requireNotNull(
            before.copy(timelineMessages = listOf(first, second)).toMobileWebMessageEvents(before),
        )
        val wrapper = events.single()

        assertEquals(1, wrapper.protocolVersion)
        assertEquals(11L, wrapper.projectionGeneration)
        assertEquals("messages.appended", wrapper.event.getValue("type").jsonPrimitive.content)
        assertEquals(1L, wrapper.event.getValue("after_seq").jsonPrimitive.content.toLong())
        assertEquals(4L, wrapper.event.getValue("through_seq").jsonPrimitive.content.toLong())
        assertEquals(4L, wrapper.event.getValue("next_after_seq").jsonPrimitive.content.toLong())
        assertEquals(
            listOf("message-2"),
            wrapper.event.getValue("items").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            },
        )
    }

    @Test
    fun `reply status event does not serialize long history`() {
        val longMessage = timelineMessage("message-long", 1, "x".repeat(120_367))
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 5,
            timelineMessages = listOf(longMessage),
        )
        val status = buildJsonObject {
            put("type", "reply.status")
            put("version", 2)
            put("session_id", "akashic:test")
            put("snapshot_id", "reply-1")
            put("available", true)
            put("items", buildJsonArray {})
        }

        val event = requireNotNull(
            before.copy(replyStatus = status).toMobileWebMessageEvents(before),
        ).single()
        val encoded = Json.encodeToString(event)

        assertEquals(status, event.event)
        assertTrue(encoded.length < 1_000)
        assertTrue(!encoded.contains("message-long"))
    }

    @Test
    fun `restoring a long message into history requires a full snapshot`() {
        val preview = timelineMessage("message-2", 2, "preview")
        val tail = timelineMessage("message-3", 3, "tail")
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 8,
            timelineMessages = listOf(preview, tail),
        )
        val restored = preview.copy(body = timelineBody("x".repeat(120_367)))

        assertNull(before.copy(timelineMessages = listOf(restored, tail)).toMobileWebMessageEvents(before))
    }

    @Test
    fun `empty reply status remains an explicit protocol value`() {
        val state = EmptyConversationState.copy(selectedSessionId = "akashic:test")
        val event = requireNotNull(state.toMobileWebReplyEvent())
        val snapshotJson = Json { explicitNulls = false }.encodeToJsonElement(
            MobileWebSnapshot.serializer(),
            state.toMobileWebSnapshot(),
        ).jsonObject

        assertEquals("false", event.event.getValue("available").jsonPrimitive.content)
        assertTrue(event.event.getValue("snapshot_id").toString() == "null")
        assertTrue(snapshotJson.containsKey("replyStatus"))
        assertTrue(snapshotJson.getValue("replyStatus").toString() == "null")
    }
}

private fun timelineMessage(id: String, seq: Long, text: String) = TimelineMessageUi(
    id = id,
    sessionId = "akashic:test",
    seq = seq,
    timestamp = "2026-09-08T08:00:00Z",
    author = "assistant",
    source = "akashic",
    body = timelineBody(text),
    metadata = buildJsonObject {},
    attachments = emptyList(),
)

private fun timelineBody(text: String) = buildJsonObject {
    put("kind", "output")
    put("finish", "complete")
    put("parts", buildJsonArray {
        add(buildJsonObject {
            put("kind", "text")
            put("value", text)
        })
    })
}
