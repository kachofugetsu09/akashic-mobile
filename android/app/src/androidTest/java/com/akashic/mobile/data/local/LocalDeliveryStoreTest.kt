package com.akashic.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDeliveryStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var store: LocalDeliveryStore

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        store = LocalDeliveryStore(database)
        store.savePairedProfile(
            ServerProfileEntity("server", "server", "device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("device", "server", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("mobile:test", "server", "test", 1))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun reducerPreservesThinkingToolThinkingOrder() = runBlocking {
        val events = listOf(
            event(1, "turn.started", buildJsonObject {}),
            event(2, "react.thinking.delta", buildJsonObject {
                put("block_id", "think-1"); put("ordinal", 0); put("delta", "先分析")
            }),
            event(3, "react.tool.started", buildJsonObject {
                put("block_id", "tool-1"); put("call_id", "call-1"); put("ordinal", 1); put("tool_name", "shell")
                put("arguments", buildJsonObject { put("description", "读取运行日志") })
            }),
            event(4, "react.tool.completed", buildJsonObject {
                put("block_id", "tool-1"); put("call_id", "call-1"); put("ordinal", 1); put("tool_name", "shell")
                put("status", "success"); put("result_preview", "完成")
            }),
            event(5, "react.thinking.delta", buildJsonObject {
                put("block_id", "think-2"); put("ordinal", 2); put("delta", "再判断")
            }),
            event(6, "answer.delta", buildJsonObject { put("delta", "答案") }),
        )
        events.forEach { store.applyEvent("server", "device", it, it.eventSeq!!) }

        val blocks = database.messages().getBlocks("assistant:turn")
        assertEquals(listOf("think-1", "tool-1", "think-2"), blocks.map { it.blockId })
        assertEquals(listOf("completed", "completed", "running"), blocks.map { it.status })
        assertEquals(
            StoredToolBlock("shell", "读取运行日志", "完成"),
            decodeStoredToolBlock(blocks[1].content),
        )
        assertEquals("答案", database.messages().get("assistant:turn")!!.text)
    }

    @Test
    fun finalMessageAddsReasoningWhenProviderDidNotStreamThinking() = runBlocking {
        val events = listOf(
            event(1, "turn.started", buildJsonObject {}),
            event(2, "react.tool.started", buildJsonObject {
                put("block_id", "tool-1"); put("call_id", "call-1"); put("ordinal", 0); put("tool_name", "shell")
                put("arguments", buildJsonObject { put("description", "检查进程") })
            }),
            event(3, "react.tool.completed", buildJsonObject {
                put("block_id", "tool-1"); put("call_id", "call-1"); put("ordinal", 0); put("tool_name", "shell")
                put("status", "success"); put("result_preview", "完成")
            }),
            event(4, "message.final", buildJsonObject {
                put("message_id", "mobile:test:1")
                put("content", "答案"); put("thinking", "先确认运行状态，再给出结论。")
            }),
        )
        events.forEach { store.applyEvent("server", "device", it, it.eventSeq!!) }

        assertEquals(null, database.messages().get("assistant:turn"))
        val blocks = database.messages().getBlocks("mobile:test:1")
        assertEquals(listOf("thinking", "tool"), blocks.map { it.kind })
        assertEquals(listOf(-1, 0), blocks.map { it.ordinal })
        assertEquals("先确认运行状态，再给出结论。", blocks.first().content)
    }

    @Test
    fun historyPageRestoresMessagesThinkingAndTools() = runBlocking {
        val history = event(
            1,
            "history.page",
            buildJsonObject {
                put("total", 2)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "mobile:test:0")
                        put("session_key", "mobile:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "恢复问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                    add(buildJsonObject {
                        put("id", "mobile:test:1")
                        put("session_key", "mobile:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "恢复回答")
                        put("tool_chain", buildJsonArray {
                            add(buildJsonObject {
                                put("text", "先检查")
                                put("calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("call_id", "call-1")
                                        put("name", "shell")
                                        put("status", "success")
                                        put("description", "读取状态")
                                        put("result_preview", "完成")
                                    })
                                })
                            })
                        })
                        put("extra", buildJsonObject { put("reasoning_content", "最后判断") })
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            },
        )

        store.applyEvent("server", "device", history, 1)

        assertEquals("恢复问题", database.messages().get("mobile:test:0")!!.text)
        assertEquals("恢复回答", database.messages().get("mobile:test:1")!!.text)
        val blocks = database.messages().getBlocks("mobile:test:1")
        assertEquals(listOf("thinking", "tool", "thinking"), blocks.map { it.kind })
        assertEquals(StoredToolBlock("shell", "读取状态", "完成"), decodeStoredToolBlock(blocks[1].content))
    }

    @Test
    fun historyCanonicalIdReplacesOptimisticUserMessage() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "mobile:test",
                role = "user",
                text = "本地问题",
                deliveryState = "sent",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "mobile:test:canonical")
                        put("session_key", "mobile:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "本地问题")
                        put("client_message_id", clientId)
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                })
            }),
            2,
        )

        assertEquals(null, database.messages().get("user:$clientId"))
        val canonical = database.messages().get("mobile:test:canonical")!!
        assertEquals(clientId, canonical.clientMessageId)
        assertEquals("complete", canonical.deliveryState)
    }

    @Test
    fun resetClearsOnlyServerProjectionAndPreservesLocalWork() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity("remote", null, "mobile:test", "assistant", "旧投影", "complete", 1, 1),
        )
        database.messages().upsert(
            MessageEntity("user:$clientId", clientId, "mobile:test", "user", "待发送", "pending", 2, 2),
        )
        val failedClientId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        database.messages().upsert(
            MessageEntity("user:$failedClientId", failedClientId, "mobile:test", "user", "发送失败", "failed", 3, 3),
        )
        database.outbox().enqueue(
            OutboxCommandEntity(clientId, "server", "{}", "pending", 0, 2, null),
        )
        store.savePairedProfile(
            ServerProfileEntity("other", "other", "other-device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("other-device", "other", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("mobile:other", "other", "other", 1))
        database.messages().upsert(
            MessageEntity("other-message", null, "mobile:other", "assistant", "其他电脑", "complete", 1, 1),
        )

        store.applyEvent(
            "server",
            "device",
            event(50, "sync.reset_required", buildJsonObject { put("reason", "inbox_retention_exceeded") }),
            3,
            preservedSessionId = "mobile:test",
        )

        assertEquals(50, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
        assertEquals(null, database.messages().get("remote"))
        assertNotNull(database.messages().get("user:$clientId"))
        assertNotNull(database.messages().get("user:$failedClientId"))
        assertNotNull(database.outbox().get(clientId))
        assertEquals("其他电脑", database.messages().get("other-message")!!.text)
    }

    @Test
    fun catalogReconciliationRemovesOnlyAbsentRebuildableProjection() = runBlocking {
        database.conversations().upsert(
            ConversationEntity("mobile:kept", "server", "保留", 1, ConversationRemoteState.REMOTE),
        )
        database.conversations().upsert(
            ConversationEntity("mobile:gone", "server", "已删除", 1, ConversationRemoteState.REMOTE),
        )
        database.conversations().upsert(ConversationEntity("mobile:local", "server", "本地未决", 1))
        database.messages().upsert(
            MessageEntity("kept-message", null, "mobile:kept", "assistant", "在目录中", "complete", 1, 1),
        )
        database.messages().upsert(
            MessageEntity("gone-message", null, "mobile:gone", "assistant", "可重建", "complete", 1, 1),
        )
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity("user:$clientId", clientId, "mobile:local", "user", "待发送", "pending", 1, 1),
        )
        database.outbox().enqueue(
            OutboxCommandEntity(clientId, "server", "{}", "pending", 0, 1, null),
        )

        store.reconcileSessionCatalog(
            serverId = "server",
            remoteSessionIds = setOf("mobile:kept"),
            preservedSessionId = "mobile:test",
        )

        assertNotNull(database.messages().get("kept-message"))
        assertEquals(null, database.messages().get("gone-message"))
        assertEquals(null, database.conversations().get("mobile:gone"))
        assertNotNull(database.messages().get("user:$clientId"))
        assertEquals(
            ConversationRemoteState.LOCAL,
            database.conversations().get("mobile:local")!!.remoteState,
        )
        assertEquals(clientId, database.outbox().dispatchable("server").single().commandId)
        assertNotNull(database.conversations().get("mobile:test"))
    }

    @Test
    fun catalogDeletionPreservesButBlocksUnknownPendingWork() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.conversations().upsert(
            ConversationEntity(
                "mobile:blocked",
                "server",
                "迁移会话",
                1,
                ConversationRemoteState.UNKNOWN,
            ),
        )
        database.messages().upsert(
            MessageEntity("user:$clientId", clientId, "mobile:blocked", "user", "待发送", "pending", 1, 1),
        )
        database.outbox().enqueue(
            OutboxCommandEntity(clientId, "server", "{}", "pending", 0, 1, null),
        )

        store.reconcileSessionCatalog(
            serverId = "server",
            remoteSessionIds = emptySet(),
            preservedSessionId = "mobile:blocked",
        )

        assertEquals(
            ConversationRemoteState.DELETED,
            database.conversations().get("mobile:blocked")!!.remoteState,
        )
        assertNotNull(database.messages().get("user:$clientId"))
        assertNotNull(database.outbox().get(clientId))
        assertEquals(emptyList<OutboxCommandEntity>(), database.outbox().dispatchable("server"))

        assertEquals(
            1,
            database.conversations().updateRemoteState("mobile:blocked", ConversationRemoteState.REMOTE),
        )
        assertEquals(clientId, database.outbox().dispatchable("server").single().commandId)
    }

    @Test
    fun eventCannotMutateAnotherServersSession() = runBlocking {
        store.savePairedProfile(
            ServerProfileEntity("other", "other", "other-device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("other-device", "other", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("mobile:foreign", "other", "other", 1))
        val foreign = event(1, "turn.started", buildJsonObject {}).copy(sessionId = "mobile:foreign")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.applyEvent("server", "device", foreign, 2) }
        }
        assertEquals(null, database.messages().get("assistant:turn"))
    }

    private fun event(sequence: Long, type: String, payload: kotlinx.serialization.json.JsonObject) = WireEnvelope(
        v = 1,
        kind = WireKind.EVENT,
        type = type,
        id = "01J00000000000000000000000",
        connectionEpoch = 1,
        eventSeq = sequence,
        sessionId = "mobile:test",
        turnId = "turn",
        payload = payload,
    )
}
