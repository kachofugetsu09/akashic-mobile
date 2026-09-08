package com.akashic.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akashic.mobile.data.realtime.WIRE_PROTOCOL_VERSION
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDeliveryStoreCursorTest {
    private lateinit var database: AppDatabase
    private lateinit var store: LocalDeliveryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = LocalDeliveryStore(
            database,
            MediaCacheStore(context.cacheDir.resolve("cursor-test-${System.nanoTime()}"), database.mediaAttachments()),
            MessageContentStore(
                context.cacheDir.resolve("cursor-content-${System.nanoTime()}"),
                database.messageContentTransfers(),
            ),
        )
        runBlocking {
            store.savePairedProfile(
                ServerProfileEntity(
                    serverId = "server-1",
                    displayName = "Test",
                    deviceId = "device-1",
                    keyAlias = "key",
                    applicationKeyFingerprint = "fingerprint",
                    lanEndpointsJson = "[]",
                    tunnelEndpointsJson = "[]",
                    tlsSpkiPinsJson = "[]",
                    createdAt = 1,
                ),
                RealtimeCursorEntity("device-1", "server-1", 4, 1, 1),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun invalidFinalAttentionDoesNotAdvanceCursor() {
        val envelope = WireEnvelope(
            v = WIRE_PROTOCOL_VERSION,
            kind = WireKind.EVENT,
            type = "message.final",
            id = "final-frame",
            connectionEpoch = 1,
            eventSeq = 5,
            sessionId = "akashic:test",
            turnId = "turn-1",
            payload = buildJsonObject { put("metadata", JsonPrimitive("broken")) },
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.applyEvent("server-1", "device-1", envelope, updatedAt = 2) }
        }
        assertEquals(
            4L,
            runBlocking { database.realtimeCursors().get("device-1")?.lastAcknowledgedEventSeq },
        )
    }

    @Test
    fun repairingSameServerReplacesOnlyDerivedCursor() = runBlocking {
        database.conversations().upsert(
            ConversationEntity("akashic:kept", "server-1", "保留会话", 2),
        )
        database.messages().upsert(
            MessageEntity(
                "message-kept",
                null,
                "akashic:kept",
                "assistant",
                "保留历史",
                "complete",
                2,
                2,
            ),
        )

        store.savePairedProfile(
            ServerProfileEntity(
                "server-1",
                "Test",
                "device-2",
                "key",
                "fingerprint",
                "[]",
                "[]",
                "[]",
                3,
            ),
            RealtimeCursorEntity("device-2", "server-1", 0, 0, 3),
        )

        assertEquals(null, database.realtimeCursors().get("device-1"))
        assertEquals(0L, database.realtimeCursors().get("device-2")?.lastAcknowledgedEventSeq)
        assertEquals("保留历史", database.messages().get("message-kept")?.text)
    }
    @Test
    fun tailRangesCommitWithAckAndOldWindowExcludesLiveMessages() = runBlocking {
        val sessionId = "akashic:test"
        database.conversations().upsert(ConversationEntity(sessionId, "server-1", "测试", 1))
        database.messages().upsert(MessageEntity("pending", null, sessionId, "user", "待发正文", "pending", 1, 1))
        database.outbox().enqueue(OutboxCommandEntity("pending", "server-1", "{}", "pending", 0, 1, null))
        database.composerDrafts().upsert(ComposerDraftEntity(sessionId, "server-1", "未发草稿", null, 1))
        val row = buildJsonObject {
            put("id", "message-1001"); put("session_id", sessionId); put("seq", 1001)
            put("timestamp", "2026-09-09T00:00:00Z"); put("author", "assistant"); put("source", "conversation")
            put("body", buildJsonObject {
                put("kind", "output"); put("finish", "complete")
                put("parts", buildJsonArray { add(buildJsonObject { put("kind", "text"); put("value", "末页正文") }) })
            })
        }
        val reference = buildJsonObject {
            put("id", "message-1002"); put("session_id", sessionId); put("seq", 1002)
            put("message_ref", buildJsonObject {
                put("version", 2); put("encoding", "utf-8"); put("media_type", "application/json")
                put("byte_length", 120367); put("sha256", "a".repeat(64)); put("display_only", true)
            })
        }
        val page = buildJsonObject {
            put("version", 2); put("direction", "backward"); put("request_id", "tail")
            put("after_seq", 1000); put("next_after_seq", 1002); put("through_seq", 1002)
            put("before_seq", 1003); put("next_before_seq", 1001); put("has_more", true)
            put("items", buildJsonArray { add(row); add(reference) })
        }
        val frame = WireEnvelope(v = WIRE_PROTOCOL_VERSION, kind = WireKind.EVENT, type = "history.page",
            id = "tail", connectionEpoch = 1, eventSeq = 5, sessionId = sessionId, payload = page)
        store.applyEvent("server-1", "device-1", frame, 2)
        assertEquals(5L, database.realtimeCursors().get("device-1")?.lastAcknowledgedEventSeq)
        assertEquals(listOf(MessageRangeEntity(sessionId, 1000, 1002)), database.messages().receivedRanges(sessionId))
        assertEquals(1002L, database.messageContentTransfers().get("message-1002")?.messageSeq)
        assertEquals(null, database.messages().get("message-1002")?.serverSeq)
        assertEquals("末页正文", database.messages().get("message-1001")?.text)

        // 非法范围整笔回滚，不能 ACK 一个尚未接收的前缀。
        val invalid = frame.copy(eventSeq = 6, payload = kotlinx.serialization.json.JsonObject(page + ("after_seq" to JsonPrimitive(-1))))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.applyEvent("server-1", "device-1", invalid, 3) }
        }
        assertEquals(5L, database.realtimeCursors().get("device-1")?.lastAcknowledgedEventSeq)
        val live = com.akashic.mobile.data.realtime.RemoteHistoryMessage(
            id = "live", sessionId = sessionId, seq = 1003, timestamp = "2026-09-09T00:00:01Z",
            author = "assistant", source = "conversation", body = row["body"] as kotlinx.serialization.json.JsonObject)
        store.applyMessageRows("server-1", sessionId,
            com.akashic.mobile.data.realtime.MessagesAppendedPayload("messages.appended", 2, sessionId, listOf(live), 1002, 1003, 1003, false))
        val oldWindow = database.messages().observeMessageGraph(sessionId, 1000, 1001).first()
        assertEquals(setOf("pending", "message-1001"), oldWindow.map { it.message.messageId }.toSet())
        assertEquals(listOf(MessageRangeEntity(sessionId, 1000, 1003)), database.messages().receivedRanges(sessionId))

        store.applyEvent("server-1", "device-1", frame.copy(type = "sync.reset_required", eventSeq = 100,
            payload = buildJsonObject { put("reason", "inbox_retention_exceeded") }), 4)
        assertEquals(100L, database.realtimeCursors().get("device-1")?.lastAcknowledgedEventSeq)
        assertEquals("末页正文", database.messages().get("message-1001")?.text)
        assertEquals("pending", database.outbox().get("pending")?.state)
        assertEquals("未发草稿", database.composerDrafts().get("server-1", sessionId)?.text)
        assertEquals(listOf(MessageRangeEntity(sessionId, 1000, 1003)), database.messages().receivedRanges(sessionId))
    }

}
