package com.akashic.mobile.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import com.akashic.mobile.data.realtime.AttachmentDownloadCoordinator
import com.akashic.mobile.data.realtime.MessageSendPayload
import com.akashic.mobile.data.realtime.ProtocolCodec
import com.akashic.mobile.data.realtime.historyStartPage
import java.time.Instant
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class LocalDeliveryStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var mediaCache: MediaCacheStore
    private lateinit var contentStore: MessageContentStore
    private lateinit var store: LocalDeliveryStore

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        mediaCache = MediaCacheStore(
            ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("media-${System.nanoTime()}"),
            database.mediaAttachments(),
        )
        contentStore = MessageContentStore(
            ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve(
                "message-content-${System.nanoTime()}",
            ),
            database.messageContentTransfers(),
        )
        store = LocalDeliveryStore(
            database,
            mediaCache,
            contentStore,
        )
        store.savePairedProfile(
            ServerProfileEntity("server", "server", "device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("device", "server", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("akashic:test", "server", "test", 1))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun turnStartedBindsClientMessageIdToTurnTriple() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }),
            2,
        )

        val message = database.messages().get("assistant:turn")
        assertNotNull(message)
        assertEquals("akashic:test", message!!.sessionId)
        assertEquals(null, message.clientMessageId)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", message.turnClientMessageId)
        assertEquals("turn", message.controlTurnId)

        // 同一三元组重放幂等
        store.applyEvent(
            "server",
            "device",
            event(2, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }),
            3,
        )
        assertEquals(
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            database.messages().get("assistant:turn")!!.turnClientMessageId,
        )
    }

    @Test
    fun turnStartedKeepsUserOutboxIdentityUniqueFromTurnCorrelation() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
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
            event(1, "turn.started", buildJsonObject { put("client_message_id", clientId) }),
            2,
        )

        assertEquals(clientId, database.messages().get("user:$clientId")!!.clientMessageId)
        val assistant = database.messages().get("assistant:turn")!!
        assertEquals(null, assistant.clientMessageId)
        assertEquals(clientId, assistant.turnClientMessageId)
        assertEquals("turn", assistant.controlTurnId)
    }

    @Test
    fun turnStartedRejectsInvalidClientMessageId() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyEvent(
                    "server",
                    "device",
                    event(1, "turn.started", buildJsonObject { put("client_message_id", "not-a-frame-id") }),
                    2,
                )
            }
        }
        Unit
    }

    @Test
    fun turnStartedRejectsClientMessageIdChangeOnReplay() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }),
            2,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyEvent(
                    "server",
                    "device",
                    event(2, "turn.started", buildJsonObject { put("client_message_id", "01BRZ3NDEKTSV4RRFFQ69G5FAV") }),
                    3,
                )
            }
        }
        Unit
    }

    /** 完整 turn 流水线：turn.started 绑定 cmid 后，后续 delta/final 不带 cmid 也必须保留绑定。 */
    @Test
    fun turnStartedThenThinkingAnswerFinalKeepsClientMessageId() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(
                1,
                "turn.started",
                buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") },
                turnId = "flow-turn",
            ),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "react.thinking.delta",
                buildJsonObject { put("block_id", "think-1"); put("ordinal", 0); put("delta", "分析") },
                turnId = "flow-turn",
            ),
            3,
        )
        store.applyEvent(
            "server",
            "device",
            event(3, "answer.delta", buildJsonObject { put("delta", "回答") }, turnId = "flow-turn"),
            4,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                4,
                "message.final",
                buildJsonObject {
                    put("message_id", "akashic:test:flow:final")
                    put("content", "最终回答")
                },
                turnId = "flow-turn",
            ),
            5,
        )

        val message = database.messages().get("akashic:test:flow:final")
        assertNotNull(message)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", message!!.turnClientMessageId)
        assertEquals("complete", message.deliveryState)
        assertEquals("最终回答", message.text)
        assertEquals("分析", database.messages().getBlock("think-1")!!.content)
    }

    @Test
    fun continuedAttemptFinalMergesIntoCanonicalLogicalTurn() = runBlocking {
        val logicalTurnId = "turn:logical"
        val attemptId = "turn:attempt-3"
        store.applyEvent(
            "server",
            "device",
            event(
                1,
                "turn.started",
                buildJsonObject {
                    put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                    put("control_turn_id", logicalTurnId)
                },
                turnId = attemptId,
            ),
            2,
        )
        // 已部署服务端留下的 durable delta 没有重复携带逻辑身份；不得把 attempt 当成新身份。
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "react.thinking.delta",
                buildJsonObject {
                    put("block_id", "thinking:$attemptId:0")
                    put("ordinal", 0)
                    put("delta", "分析")
                },
                turnId = attemptId,
            ),
            3,
        )
        store.applyEvent(
            "server",
            "device",
            event(3, "answer.delta", buildJsonObject { put("delta", "回答") }, turnId = attemptId),
            4,
        )
        assertEquals(logicalTurnId, database.messages().get("assistant:$attemptId")!!.controlTurnId)
        store.applyEvent(
            "server",
            "device",
            event(
                4,
                "message.final",
                buildJsonObject {
                    put("message_id", "akashic:test:logical:final")
                    put("content", "最终回答")
                    put("control_turn_id", logicalTurnId)
                },
                turnId = attemptId,
            ),
            5,
        )

        assertEquals(null, database.messages().get("assistant:$attemptId"))
        val canonical = database.messages().get("akashic:test:logical:final")!!
        assertEquals(logicalTurnId, canonical.controlTurnId)
        assertEquals("最终回答", canonical.text)
    }

    @Test
    fun explicitControlTurnIdChangeStillFailsLoudly() = runBlocking {
        val attemptId = "turn:attempt-4"
        store.applyEvent(
            "server",
            "device",
            event(
                1,
                "turn.started",
                buildJsonObject { put("control_turn_id", "turn:logical-a") },
                turnId = attemptId,
            ),
            2,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyEvent(
                    "server",
                    "device",
                    event(
                        2,
                        "answer.delta",
                        buildJsonObject {
                            put("delta", "错误身份")
                            put("control_turn_id", "turn:logical-b")
                        },
                        turnId = attemptId,
                    ),
                    3,
                )
            }
        }
        assertEquals("turn:logical-a", database.messages().get("assistant:$attemptId")!!.controlTurnId)
        assertEquals(1, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
        Unit
    }

    @Test
    fun historyRecoversContinuedAttemptWhenFinalWasMissed() = runBlocking {
        val logicalTurnId = "turn:logical-history"
        val attemptId = "turn:attempt-history-2"
        store.applyEvent(
            "server",
            "device",
            event(
                1,
                "turn.started",
                buildJsonObject {
                    put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                    put("control_turn_id", logicalTurnId)
                },
                turnId = attemptId,
            ),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "react.thinking.delta",
                buildJsonObject {
                    put("block_id", "thinking:$attemptId:0")
                    put("ordinal", 0)
                    put("delta", "分析")
                },
                turnId = attemptId,
            ),
            3,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                3,
                "history.page",
                buildJsonObject {
                    put("total", 1)
                    put("page", 1)
                    put("page_size", 10)
                    put("items", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "akashic:test:logical:history")
                            put("session_key", "akashic:test")
                            put("seq", 1)
                            put("role", "assistant")
                            put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                            put("content", "历史最终回答")
                            put("extra", buildJsonObject {
                                put("control_turn_id", logicalTurnId)
                            })
                            put("ts", "2026-08-13T01:00:00Z")
                        })
                    })
                },
            ),
            4,
        )

        assertEquals(null, database.messages().get("assistant:$attemptId"))
        val canonical = database.messages().get("akashic:test:logical:history")!!
        assertEquals(logicalTurnId, canonical.controlTurnId)
        assertEquals("历史最终回答", canonical.text)
        assertEquals(
            canonical.messageId,
            database.messages().getBlock("thinking:$attemptId:0")!!.messageId,
        )
        assertTrue(database.messages().activeAssistantTurns("server").isEmpty())
    }

    @Test
    fun terminalReplayRepairsLegacyStreamingTurnWithoutClientCorrelation() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity(
                messageId = "assistant:legacy-turn",
                clientMessageId = null,
                sessionId = "akashic:test",
                role = "assistant",
                text = "旧流式正文",
                deliveryState = "streaming",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        store.applyEvent(
            "server",
            "device",
            event(
                1,
                "message.final",
                buildJsonObject {
                    put("client_message_id", clientId)
                    put("user_message_id", "akashic:test:legacy:user")
                    put("message_id", "akashic:test:legacy:canonical")
                    put("content", "最终正文")
                },
                turnId = "legacy-turn",
            ),
            2,
        )

        assertEquals(null, database.messages().get("assistant:legacy-turn"))
        val canonical = database.messages().get("akashic:test:legacy:canonical")!!
        assertEquals(null, canonical.clientMessageId)
        assertEquals(clientId, canonical.turnClientMessageId)
        assertEquals("legacy-turn", canonical.controlTurnId)
        assertEquals("complete", canonical.deliveryState)
    }

    /** 后续事件意外携带非空 cmid 时校验与既有绑定一致，不一致 fail-loud。 */
    @Test
    fun turnDeltaWithDifferentClientMessageIdRejected() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }, turnId = "flow-turn"),
            2,
        )
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyEvent(
                    "server",
                    "device",
                    event(
                        2,
                        "answer.delta",
                        buildJsonObject { put("delta", "错误绑定") },
                        turnId = "flow-turn",
                    ).copy(payload = buildJsonObject {
                        put("delta", "错误绑定")
                        put("client_message_id", "01BRZ3NDEKTSV4RRFFQ69G5FAV")
                    }),
                    3,
                )
            }
        }
        Unit
    }

    /** 后续事件携带与既有绑定一致的 cmid 时正常通过。 */
    @Test
    fun turnDeltaWithMatchingClientMessageIdAccepted() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }, turnId = "flow-turn"),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "answer.delta",
                buildJsonObject { put("delta", "一致绑定") },
                turnId = "flow-turn",
            ).copy(payload = buildJsonObject {
                put("delta", "一致绑定")
                put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
            }),
            3,
        )
        assertEquals("一致绑定", database.messages().get("assistant:flow-turn")!!.text)
    }

    /** final 丢失时 seq 行计数与远端不相等，历史请求不会跳过，heal 路径可达。 */
    @Test
    fun finalLostHistoryNotSkippedBecauseSeqRowsCountOnly() = runBlocking {
        // 1. 先有 6 条 canonical 历史（seq 0..5）
        for (seq in 0..5) {
            database.messages().upsert(
                MessageEntity(
                    messageId = "canonical-$seq",
                    clientMessageId = null,
                    sessionId = "akashic:test",
                    role = "assistant",
                    text = "历史 $seq",
                    deliveryState = "complete",
                    createdAt = seq.toLong(),
                    updatedAt = seq.toLong(),
                    serverSeq = seq.toLong(),
                ),
            )
        }
        // 2. 新 turn 流式但 final 丢失：本地多了两条不带 serverSeq 的行
        store.applyEvent(
            "server",
            "device",
            event(1, "turn.started", buildJsonObject { put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV") }),
            7,
        )
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "react.thinking.delta",
                buildJsonObject {
                    put("block_id", "thinking:turn:0")
                    put("ordinal", 0)
                    put("delta", "正在分析")
                },
            ),
            8,
        )
        // 3. 投影进度只数带 seq 的行；远端已提交 user+assistant（8 条）时计数必然不等
        val progress = database.messages().historyProjectionProgress("akashic:test")
        assertEquals(6, progress.messageCount)
        assertEquals(5L, progress.maxServerSeq)
        assertNotNull(
            historyStartPage(
                remoteMessageCount = 8,
                local = progress,
                forceReload = false,
                pageSize = 10,
            ),
        )
    }

    /** 相等跳过只在服务端没有任何新提交时发生，此时不存在可丢失的 final。 */
    @Test
    fun equalCountHistorySkipRequiresNoServerSideCommit() = runBlocking {
        val progress = database.messages().historyProjectionProgress("akashic:test")
        assertEquals(0, progress.messageCount)
        assertEquals(
            null,
            historyStartPage(
                remoteMessageCount = 0,
                local = progress,
                forceReload = false,
                pageSize = 10,
            ),
        )
    }

    @Test
    fun reachingConversationTailClearsPersistedReadingAnchor() = runBlocking {
        database.messages().upsert(
            MessageEntity("message-in-history", null, "akashic:test", "assistant", "历史", "complete", 1, 1),
        )
        assertTrue(store.saveReadingPosition(
            sessionId = "akashic:test",
            messageId = "message-in-history",
            offsetPx = -24,
            expectedServerId = "server",
            updatedAt = 2,
        ))

        store.markSessionReadThrough(
            sessionId = "akashic:test",
            readAt = 3,
            expectedServerId = "server",
            updatedAt = 3,
        )

        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals(null, summary.anchorMessageId)
        assertEquals(0, summary.anchorOffsetPx)
    }

    @Test
    fun historyKeepsTextWhenAttachmentSourceIsUnavailable() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:degraded-attachment")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "附件缺失不影响正文")
                        put("extra", buildJsonObject {})
                        put("attachments", buildJsonArray {})
                        put("attachment_error", buildJsonObject {
                            put("code", "media_unavailable")
                            put("message", "附件源暂时不可用")
                        })
                        put("ts", "2026-08-12T05:00:00Z")
                    })
                })
            }),
            1,
        )

        val message = requireNotNull(database.messages().get("akashic:test:degraded-attachment"))
        assertEquals("附件缺失不影响正文", message.text)
        assertEquals(
            emptyList<MediaAttachmentEntity>(),
            database.mediaAttachments().forMessage(message.messageId),
        )
    }

    @Test
    fun historyRepairsConversationTitleFromCoreProjection() = runBlocking {
        database.conversations().upsert(
            ConversationEntity("akashic:test", "server", "新对话", 1, remoteKnown = true),
        )
        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("title", "迁移后的标题")
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:title")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "user")
                        put("content", "迁移后的标题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-08-27T00:00:00Z")
                    })
                })
            }),
            2,
        )

        assertEquals("迁移后的标题", database.conversations().get("akashic:test")!!.title)
    }

    @Test
    fun historyAcceptsTitleMeasuredAsUnicodeCodePoints() = runBlocking {
        val title = "🚀".repeat(17)
        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("title", title)
                put("total", 0)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {})
            }),
            2,
        )

        assertEquals(title, database.conversations().get("akashic:test")!!.title)
    }

    @Test
    fun tallMessageReadingAnchorPreservesLargeNegativeOffset() = runBlocking {
        database.messages().upsert(
            MessageEntity("tall-message", null, "akashic:test", "assistant", "长回复", "complete", 1, 1),
        )

        assertTrue(store.saveReadingPosition(
            sessionId = "akashic:test",
            messageId = "tall-message",
            offsetPx = -25_000,
            expectedServerId = "server",
            updatedAt = 2,
        ))

        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("tall-message", summary.anchorMessageId)
        assertEquals(-25_000, summary.anchorOffsetPx)
    }

    @Test
    fun deliberateConversationEntryClearsAnchorWithoutAdvancingReadWatermark() = runBlocking {
        database.messages().upsert(
            MessageEntity("already-read", null, "akashic:test", "assistant", "已读", "complete", 10, 10),
        )
        database.messages().upsert(
            MessageEntity("still-unread", null, "akashic:test", "assistant", "未读", "complete", 20, 20),
        )
        store.markSessionReadThrough("akashic:test", 10, "server", 21)
        assertTrue(store.saveReadingPosition("akashic:test", "already-read", -24, "server", 22))

        store.clearReadingPosition("akashic:test", "server", 23)

        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals(null, summary.anchorMessageId)
        assertEquals(0, summary.anchorOffsetPx)
        assertEquals(1, summary.unreadCount)
    }

    @Test
    fun removesUnavailableRemoteProjectionAndKeepsLocalWork() = runBlocking {
        assertEquals(1, database.conversations().markRemoteKnown("akashic:test"))
        database.messages().upsert(
            MessageEntity(
                "remote-history",
                null,
                "akashic:test",
                "assistant",
                "历史",
                "complete",
                1,
                1,
                serverSeq = 1,
            ),
        )

        assertEquals(
            RemoveUnavailableConversationResult.REMOVED,
            store.removeUnavailableConversation("server", "akashic:test"),
        )
        assertEquals(null, database.conversations().get("akashic:test"))
        assertEquals(0, database.messages().countForSession("akashic:test"))

        database.conversations().upsert(
            ConversationEntity("akashic:pending", "server", "待发送", 2, remoteKnown = true),
        )
        database.messages().upsert(
            MessageEntity(
                "remote-pending",
                null,
                "akashic:pending",
                "assistant",
                "历史",
                "complete",
                2,
                2,
                serverSeq = 1,
            ),
        )
        database.messages().upsert(
            MessageEntity(
                "user:pending",
                "pending",
                "akashic:pending",
                "user",
                "尚未发送",
                "pending",
                3,
                3,
            ),
        )

        assertEquals(
            RemoveUnavailableConversationResult.HAS_LOCAL_WORK,
            store.removeUnavailableConversation("server", "akashic:pending"),
        )
        assertNotNull(database.conversations().get("akashic:pending"))
    }

    @Test
    fun keepsUnavailableConversationWithAttachmentDraft() = runBlocking {
        assertEquals(1, database.conversations().markRemoteKnown("akashic:test"))
        database.messages().upsert(
            MessageEntity(
                "remote-history",
                null,
                "akashic:test",
                "assistant",
                "历史",
                "complete",
                1,
                1,
                serverSeq = 1,
            ),
        )
        database.attachmentTransfers().upsert(
            AttachmentTransferEntity(
                attachmentId = "draft",
                serverId = "server",
                sessionId = "akashic:test",
                filename = "draft.txt",
                contentType = "text/plain",
                sizeBytes = 4,
                sha256 = "a".repeat(64),
                transferredBytes = 0,
                state = "ready",
                updatedAt = 2,
            ),
        )

        assertEquals(
            RemoveUnavailableConversationResult.HAS_LOCAL_WORK,
            store.removeUnavailableConversation("server", "akashic:test"),
        )
        assertNotNull(database.conversations().get("akashic:test"))
    }

    @Test
    fun persistsComposerDraftAndClearsOnlyTheEmptyState() = runBlocking {
        database.messages().upsert(
            MessageEntity(
                "assistant:answer",
                null,
                "akashic:test",
                "assistant",
                "历史回答",
                "complete",
                1,
                1,
            ),
        )

        store.saveComposerDraft(
            sessionId = "akashic:test",
            text = "继续追问",
            replyToMessageId = "assistant:answer",
            expectedServerId = "server",
            updatedAt = 2,
        )

        val persisted = database.composerDrafts().get("server", "akashic:test")
        assertEquals("继续追问", persisted?.text)
        assertEquals("assistant:answer", persisted?.replyToMessageId)
        assertTrue(database.conversations().observeSummaries("server").first().single().hasLocalWork)

        store.saveComposerDraft("akashic:test", "", null, "server", 3)
        assertEquals(null, database.composerDrafts().get("server", "akashic:test"))
    }

    @Test
    fun dropsMissingReplyIdentityButPreservesDraftText() = runBlocking {
        store.saveComposerDraft(
            sessionId = "akashic:test",
            text = "目标消失后文字仍在",
            replyToMessageId = "assistant:missing",
            expectedServerId = "server",
            updatedAt = 2,
        )

        val persisted = database.composerDrafts().get("server", "akashic:test")
        assertEquals("目标消失后文字仍在", persisted?.text)
        assertEquals(null, persisted?.replyToMessageId)
    }

    @Test
    fun preparedShareCommitsOnlyAgainstItsExactDraftBase() = runBlocking {
        store.saveComposerDraft("akashic:test", "原草稿", null, "server", 2)

        assertEquals(
            PreparedComposerDraftResult.COMMITTED,
            store.commitPreparedComposerDraft(
                "akashic:test",
                "原草稿\n共享文字",
                "assistant:missing",
                "原草稿",
                null,
                2,
                "server",
                3,
            ),
        )
        assertEquals(
            PreparedComposerDraftResult.ALREADY_COMMITTED,
            store.commitPreparedComposerDraft(
                "akashic:test",
                "原草稿\n共享文字",
                "assistant:missing",
                "原草稿",
                null,
                2,
                "server",
                4,
            ),
        )

        store.saveComposerDraft("akashic:test", "用户继续编辑", null, "server", 5)
        assertEquals(
            PreparedComposerDraftResult.CONFLICT,
            store.commitPreparedComposerDraft(
                "akashic:test",
                "旧的 prepared",
                null,
                "原草稿\n共享文字",
                null,
                3,
                "server",
                6,
            ),
        )
        assertEquals(
            "用户继续编辑",
            database.composerDrafts().get("server", "akashic:test")?.text,
        )
    }

    @Test
    fun acknowledgedSentMessageSurvivesReloadableProjectionCleanup() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
                role = "user",
                text = "已 ACK 尚未 canonical 化",
                deliveryState = "sent",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.messages().upsert(
            MessageEntity(
                messageId = "akashic:test:complete",
                clientMessageId = null,
                sessionId = "akashic:test",
                role = "assistant",
                text = "可重建投影",
                deliveryState = "complete",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        store.clearReloadableCache("server", preservedSessionId = null)

        assertNotNull(database.messages().get("user:$clientId"))
        assertEquals(null, database.messages().get("akashic:test:complete"))
    }

    @Test
    fun restoresOnlyASelectionOwnedByTheCurrentServer() = runBlocking {
        store.savePairedProfile(
            ServerProfileEntity("other", "other", "other-device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("other-device", "other", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("akashic:other", "other", "other", 3))

        assertEquals(
            "akashic:test",
            store.restoreSelectedSession("server", "akashic:other"),
        )
        assertEquals(
            "akashic:other",
            store.restoreSelectedSession("other", "akashic:other"),
        )
    }

    @Test
    fun messageCommitAtomicallyConsumesOnlyItsCapturedDraftRevision() = runBlocking {
        store.saveComposerDraft("akashic:test", "准备发送", null, "server", 10)

        store.enqueueMessage(
            conversation = ConversationEntity("akashic:test", "server", "准备发送", 11),
            message = pendingUserMessage("user:atomic", "atomic", "准备发送", 11),
            command = pendingOutbox("atomic", 11),
            attachments = emptyList(),
            sentDraftRevision = 10,
        )

        assertEquals(null, database.composerDrafts().get("server", "akashic:test"))
        assertNotNull(database.messages().get("user:atomic"))
        assertNotNull(database.outbox().get("atomic"))
    }

    @Test
    fun newerComposerRevisionSurvivesAnOlderMessageCommit() = runBlocking {
        store.saveComposerDraft("akashic:test", "准备发送", null, "server", 10)
        store.saveComposerDraft("akashic:test", "发送后继续输入", null, "server", 11)

        store.enqueueMessage(
            conversation = ConversationEntity("akashic:test", "server", "准备发送", 12),
            message = pendingUserMessage("user:older", "older", "准备发送", 12),
            command = pendingOutbox("older", 12),
            attachments = emptyList(),
            sentDraftRevision = 10,
        )

        assertEquals(
            "发送后继续输入",
            database.composerDrafts().get("server", "akashic:test")?.text,
        )
    }

    @Test
    fun failedMessageTransactionKeepsDraftAndRollsBackMessage() = runBlocking {
        store.saveComposerDraft("akashic:test", "仍需保留", null, "server", 10)
        database.outbox().enqueue(pendingOutbox("duplicate", 10))

        val failure = runCatching {
            store.enqueueMessage(
                conversation = ConversationEntity("akashic:test", "server", "仍需保留", 11),
                message = pendingUserMessage("user:rollback", "duplicate", "仍需保留", 11),
                command = pendingOutbox("duplicate", 11),
                attachments = emptyList(),
                sentDraftRevision = 10,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(null, database.messages().get("user:rollback"))
        assertEquals("仍需保留", database.composerDrafts().get("server", "akashic:test")?.text)
    }

    @Test
    fun keepsUnavailableConversationWithComposerDraft() = runBlocking {
        assertEquals(1, database.conversations().markRemoteKnown("akashic:test"))
        store.saveComposerDraft("akashic:test", "稍后继续", null, "server", 2)

        assertEquals(
            RemoveUnavailableConversationResult.HAS_LOCAL_WORK,
            store.removeUnavailableConversation("server", "akashic:test"),
        )
        assertNotNull(database.conversations().get("akashic:test"))
    }

    @Test
    fun conversationSummarySeparatesRemoteHistoryFromLocalWork() = runBlocking {
        assertEquals(1, database.conversations().markRemoteKnown("akashic:test"))
        database.messages().upsert(
            MessageEntity(
                "remote-history",
                null,
                "akashic:test",
                "assistant",
                "历史",
                "complete",
                1,
                1,
                serverSeq = 1,
            ),
        )
        val remote = database.conversations().observeSummaries("server").first().single()
        assertTrue(remote.remoteKnown)
        assertEquals(false, remote.hasLocalWork)

        database.messages().upsert(
            MessageEntity(
                "user:failed",
                "failed",
                "akashic:test",
                "user",
                "失败草稿",
                "failed_retryable",
                2,
                2,
            ),
        )
        val pending = database.conversations().observeSummaries("server").first().single()
        assertTrue(pending.remoteKnown)
        assertTrue(pending.hasLocalWork)
    }

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
                put("status", "success"); put("result_preview", "完成"); put("duration_ms", 615)
                put("arguments", buildJsonObject { put("description", "读取实际运行日志") })
            }),
            event(5, "react.thinking.delta", buildJsonObject {
                put("block_id", "think-2"); put("ordinal", 2); put("delta", "再判断")
            }),
            event(6, "answer.delta", buildJsonObject { put("delta", "答案") }),
        )
        events.forEach { store.applyEvent("server", "device", it, it.eventSeq!!) }

        assertTrue(requireNotNull(database.conversations().get("akashic:test")).remoteKnown)
        val blocks = database.messages().getBlocks("assistant:turn")
        assertEquals(listOf("think-1", "tool-1", "think-2"), blocks.map { it.blockId })
        assertEquals(listOf("completed", "completed", "running"), blocks.map { it.status })
        assertEquals(
            StoredToolBlock(
                name = "shell",
                description = "读取实际运行日志",
                resultPreview = "完成",
                arguments = buildJsonObject { put("description", "读取实际运行日志") },
                durationMillis = 615,
            ),
            decodeStoredToolBlock(blocks[1].content),
        )
        assertEquals("答案", database.messages().get("assistant:turn")!!.text)
    }

    @Test
    fun firstRemoteTurnCreatesConversationBeforeAssistantProjection() = runBlocking {
        assertEquals(1, database.conversations().delete("server", "akashic:test"))

        store.applyEvent("server", "device", event(1, "turn.started", buildJsonObject {}), 2)

        val conversation = requireNotNull(database.conversations().get("akashic:test"))
        assertTrue(conversation.remoteKnown)
        assertEquals("streaming", database.messages().get("assistant:turn")!!.deliveryState)
        assertEquals(1, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
    }

    @Test
    fun overlappingTurnStartRollsBackProjectionAndCursor() = runBlocking {
        store.applyEvent("server", "device", event(1, "turn.started", buildJsonObject {}, "turn-old"), 2)

        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                store.applyEvent("server", "device", event(2, "turn.started", buildJsonObject {}, "turn-new"), 3)
            }
        }

        assertEquals("同一会话出现重叠 turn: turn-old -> turn-new", error.message)
        assertEquals(null, database.messages().get("assistant:turn-new"))
        assertEquals(
            listOf("assistant:turn-old"),
            database.messages().activeAssistantTurns("server").map(MessageEntity::messageId),
        )
        assertEquals(1, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
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
                put("status", "denied"); put("result_preview", "策略拒绝"); put("duration_ms", 20)
                put("arguments", buildJsonObject { put("description", "检查实际进程") })
            }),
            event(4, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:final")
                put("content", "答案"); put("thinking", "先确认运行状态，再给出结论。")
            }),
        )
        events.forEach { store.applyEvent("server", "device", it, it.eventSeq!!) }

        val blocks = database.messages().getBlocks("akashic:test:assistant:final")
        assertEquals(listOf("thinking", "tool"), blocks.map { it.kind })
        assertEquals(listOf(-1, 0), blocks.map { it.ordinal })
        assertEquals("failed", blocks.last().status)
        assertEquals("先确认运行状态，再给出结论。", blocks.first().content)
    }

    @Test
    fun historyContentRefRestoresExactBodyWithoutChangingThinkingOrTools() = runBlocking {
        val messageId = "akashic:test:history:large"
        val content = ("长正文🌙\n".repeat(40_000)).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("total", 1)
                put("page_size", 10)
                put("content_ref_version", 1)
                put("after_seq", -1)
                put("next_after_seq", 0)
                put("snapshot_max_seq", 0)
                put("has_more", false)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", messageId)
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "assistant")
                        put("content_ref", buildJsonObject {
                            put("version", 1)
                            put("encoding", "utf-8")
                            put("byte_length", content.size)
                            put("sha256", digest)
                            put("preview", "长正文🌙")
                        })
                        put("tool_chain", buildJsonArray {
                            add(buildJsonObject {
                                put("reasoning_content", "先核对远端权威正文")
                                put("calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("call_id", "call-1")
                                        put("name", "shell")
                                        put("status", "success")
                                        put("description", "检查一致性")
                                        put("result_preview", "一致")
                                    })
                                })
                            })
                        })
                        put("extra", buildJsonObject {})
                        put("ts", "2026-08-02T00:00:00Z")
                    })
                })
            }),
            1,
        )

        assertEquals("长正文🌙", database.messages().get(messageId)!!.text)
        assertEquals(
            listOf("thinking", "tool"),
            database.messages().getBlocks(messageId).map { it.kind },
        )
        val transfer = database.messageContentTransfers().get(messageId)!!
        contentStore.append(transfer, content)
        val completed = database.messageContentTransfers().get(messageId)!!
        val restored = contentStore.readVerified(completed)
        store.commitRestoredMessageContent(
            completed.copy(updatedAt = completed.updatedAt + 1),
            restored,
            2,
        )
        contentStore.delete(completed)

        assertEquals(content.toString(Charsets.UTF_8), database.messages().get(messageId)!!.text)
        assertEquals(null, database.messageContentTransfers().get(messageId))
        assertEquals(
            listOf("先核对远端权威正文", "shell"),
            database.messages().getBlocks(messageId).map {
                if (it.kind == "thinking") it.content else decodeStoredToolBlock(it.content).name
            },
        )
    }

    @Test
    fun repeatedHistoryManifestRequeuesFailedContentTransfer() = runBlocking {
        val messageId = "akashic:test:history:retry"
        val content = "完整正文".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        val payload = buildJsonObject {
            put("total", 1)
            put("page_size", 10)
            put("content_ref_version", 1)
            put("after_seq", -1)
            put("next_after_seq", 0)
            put("snapshot_max_seq", 0)
            put("has_more", false)
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("id", messageId)
                    put("session_key", "akashic:test")
                    put("seq", 0)
                    put("role", "assistant")
                    put("content_ref", buildJsonObject {
                        put("version", 1)
                        put("encoding", "utf-8")
                        put("byte_length", content.size)
                        put("sha256", digest)
                        put("preview", "完整")
                    })
                    put("tool_chain", buildJsonArray {})
                    put("extra", buildJsonObject {})
                    put("ts", "2026-08-02T00:00:00Z")
                })
            })
        }
        store.applyEvent("server", "device", event(1, "history.page", payload), 1)
        check(
            database.messageContentTransfers().updateProgress(
                messageId,
                0,
                "failed",
                2,
            ) == 1,
        )

        store.applyEvent("server", "device", event(2, "history.page", payload), 3)

        assertEquals("pending", database.messageContentTransfers().get(messageId)!!.state)
    }

    @Test
    fun historyReplacesLiveBlocksForTheSameCanonicalMessage() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(1, "react.thinking.delta", buildJsonObject {
                put("block_id", "live-thinking")
                put("ordinal", 0)
                put("delta", "流式思考")
            }),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(2, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:same")
                put("content", "实时回答")
            }),
            3,
        )
        store.applyEvent(
            "server",
            "device",
            event(3, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:assistant:same")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "历史回答")
                        put("extra", buildJsonObject { put("reasoning_content", "历史思考") })
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            }),
            4,
        )

        assertEquals(1, database.messages().countForSession("akashic:test"))
        assertEquals("历史回答", database.messages().get("akashic:test:assistant:same")!!.text)
        val blocks = database.messages().getBlocks("akashic:test:assistant:same")
        assertEquals(listOf("history:akashic:test:assistant:same:0"), blocks.map { it.blockId })
        assertEquals(listOf("历史思考"), blocks.map { it.content })
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
                        put("id", "akashic:test:0")
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "恢复问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                    add(buildJsonObject {
                        put("id", "akashic:test:1")
                        put("session_key", "akashic:test")
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
                                        put("status", "blocked")
                                        put("description", "读取状态")
                                        put("result_preview", "未执行")
                                    })
                                })
                            })
                        })
                        put("extra", buildJsonObject {
                            put("reasoning_content", "最后判断")
                            put("turn_duration_ms", 30_000)
                        })
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            },
        )

        store.applyEvent("server", "device", history, 1)

        assertEquals("恢复问题", database.messages().get("akashic:test:0")!!.text)
        assertEquals("恢复回答", database.messages().get("akashic:test:1")!!.text)
        assertEquals(
            listOf("恢复问题", "恢复回答"),
            database.messages().observeMessages("akashic:test").first().map { it.text },
        )
        val blocks = database.messages().getBlocks("akashic:test:1")
        assertEquals(listOf("thinking", "tool", "thinking"), blocks.map { it.kind })
        assertEquals("failed", blocks[1].status)
        assertEquals(StoredToolBlock("shell", "读取状态", "未执行"), decodeStoredToolBlock(blocks[1].content))
    }

    @Test
    fun historyCanonicalIdReplacesOptimisticUserMessage() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val attachmentId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
                role = "user",
                text = "本地问题",
                deliveryState = "sent",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.mediaAttachments().upsert(mediaAttachment(attachmentId))
        database.mediaAttachments().linkAll(
            listOf(MessageAttachmentEntity("user:$clientId", attachmentId, 0)),
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
                        put("id", "akashic:test:user:canonical")
                        put("client_message_id", clientId)
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "本地问题")
                        put("extra", buildJsonObject {})
                        put("attachments", buildJsonArray {
                            add(buildJsonObject {
                                put("attachment_id", attachmentId)
                                put("filename", "image.png")
                                put("content_type", "image/png")
                                put("size_bytes", 1_048_579)
                                put("sha256", "a".repeat(64))
                            })
                        })
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                })
            }),
            2,
        )

        assertEquals(null, database.messages().get("user:$clientId"))
        val canonical = database.messages().get("akashic:test:user:canonical")!!
        assertEquals(clientId, canonical.clientMessageId)
        assertEquals("complete", canonical.deliveryState)
        assertEquals(
            listOf(attachmentId),
            database.mediaAttachments().forMessage("akashic:test:user:canonical").map { it.attachmentId },
        )
    }

    @Test
    fun finalCompletesOptimisticUserWithoutMigratingIdentity() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
                role = "user",
                text = "继续",
                deliveryState = "sent",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        store.applyEvent(
            "server",
            "device",
            event(1, "message.final", buildJsonObject {
                put("user_message_id", "akashic:test:user:canonical")
                put("client_message_id", clientId)
                put("message_id", "akashic:test:assistant:canonical")
                put("content", "回答")
            }),
            2,
        )

        // final 只推进乐观用户消息状态，不迁移 canonical 身份（仍由 history 投影重建）
        assertEquals(null, database.messages().get("akashic:test:user:canonical"))
        val optimistic = database.messages().get("user:$clientId")!!
        assertEquals("complete", optimistic.deliveryState)
        assertEquals("回答", database.messages().get("akashic:test:assistant:canonical")!!.text)
    }

    @Test
    fun legacyHistoryWithoutClientIdRepairsUniqueOptimisticUserMessage() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val sentAt = Instant.parse("2026-07-14T16:00:00Z").toEpochMilli()
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
                role = "user",
                text = "旧版问题",
                deliveryState = "sent",
                createdAt = sentAt,
                updatedAt = sentAt,
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
                        put("id", "akashic:test:user:canonical")
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "旧版问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:09Z")
                    })
                })
            }),
            sentAt + 10_000,
        )

        assertEquals(1, database.messages().countForSession("akashic:test"))
        assertEquals(null, database.messages().get("user:$clientId"))
        assertEquals("旧版问题", database.messages().get("akashic:test:user:canonical")!!.text)
    }

    @Test
    fun legacyHistoryDoesNotGuessBetweenRepeatedIdenticalMessages() = runBlocking {
        val sentAt = Instant.parse("2026-07-14T16:00:00Z").toEpochMilli()
        listOf("01ARZ3NDEKTSV4RRFFQ69G5FAV", "01ARZ3NDEKTSV4RRFFQ69G5FAW").forEachIndexed { index, clientId ->
            database.messages().upsert(
                MessageEntity(
                    messageId = "user:$clientId",
                    clientMessageId = clientId,
                    sessionId = "akashic:test",
                    role = "user",
                    text = "重复问题",
                    deliveryState = "sent",
                    createdAt = sentAt + index,
                    updatedAt = sentAt + index,
                ),
            )
        }

        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:user:canonical")
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "重复问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:09Z")
                    })
                })
            }),
            sentAt + 10_000,
        )

        assertEquals(3, database.messages().countForSession("akashic:test"))
    }

    @Test
    fun legacyHistoryDoesNotConsumeANewerIdenticalUserMessage() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val historyAt = Instant.parse("2026-07-14T16:00:00Z").toEpochMilli()
        database.messages().upsert(
            MessageEntity(
                messageId = "user:$clientId",
                clientMessageId = clientId,
                sessionId = "akashic:test",
                role = "user",
                text = "稍后又问的相同问题",
                deliveryState = "sent",
                createdAt = historyAt + 30 * 60 * 1_000,
                updatedAt = historyAt + 30 * 60 * 1_000,
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
                        put("id", "akashic:test:user:old-canonical")
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "稍后又问的相同问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                })
            }),
            historyAt + 30 * 60 * 1_000,
        )

        assertEquals(2, database.messages().countForSession("akashic:test"))
        assertNotNull(database.messages().get("user:$clientId"))
        assertNotNull(database.messages().get("akashic:test:user:old-canonical"))
    }

    @Test
    fun gapResetClearsOnlyServerProjectionAndPreservesLocalWork() = runBlocking {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity("remote", null, "akashic:test", "assistant", "旧投影", "complete", 1, 1),
        )
        database.messages().upsert(
            MessageEntity("user:$clientId", clientId, "akashic:test", "user", "待发送", "pending", 2, 2),
        )
        database.outbox().enqueue(
            OutboxCommandEntity(clientId, "server", "{}", "pending", 0, 2, null),
        )
        database.attachmentTransfers().upsert(transfer("ready", 1_048_579, "draft"))
        store.savePairedProfile(
            ServerProfileEntity("other", "other", "other-device", "alias", "pin", "[]", "[]", "[]", 1),
            RealtimeCursorEntity("other-device", "other", 0, 0, 1),
        )
        database.conversations().upsert(ConversationEntity("akashic:other", "other", "other", 1))
        database.messages().upsert(
            MessageEntity("other-message", null, "akashic:other", "assistant", "其他电脑", "complete", 1, 1),
        )

        store.applyEvent(
            "server",
            "device",
            event(50, "sync.reset_required", buildJsonObject { put("reason", "inbox_retention_exceeded") }),
            3,
            preservedSessionId = "akashic:test",
        )

        assertEquals(50, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
        assertEquals(null, database.messages().get("remote"))
        assertNotNull(database.messages().get("user:$clientId"))
        assertNotNull(database.outbox().get(clientId))
        assertNotNull(database.attachmentTransfers().get("draft"))
        assertEquals("其他电脑", database.messages().get("other-message")!!.text)
    }

    @Test
    fun manualReloadClearsCommittedCacheButPreservesPairingAndUnsentWork() = runBlocking {
        val pendingId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        database.messages().upsert(
            MessageEntity("remote", null, "akashic:test", "assistant", "旧投影", "complete", 1, 1, 1),
        )
        database.messages().upsert(
            MessageEntity("sent", pendingId, "akashic:test", "user", "已提交", "sent", 2, 2),
        )
        database.messages().upsert(
            MessageEntity("pending", "pending-id", "akashic:test", "user", "待发送", "pending", 3, 3),
        )
        database.messages().upsert(
            MessageEntity("failed", "failed-id", "akashic:test", "user", "发送失败", "failed", 4, 4),
        )
        database.outbox().enqueue(
            OutboxCommandEntity("pending-id", "server", "{}", "pending", 0, 3, null),
        )
        database.attachmentTransfers().upsert(transfer("ready", 1_048_579, "draft"))

        store.clearReloadableCache("server", "akashic:test")

        assertEquals(null, database.messages().get("remote"))
        assertNotNull(database.messages().get("sent"))
        assertNotNull(database.messages().get("pending"))
        assertNotNull(database.messages().get("failed"))
        assertNotNull(database.outbox().get("pending-id"))
        assertNotNull(database.attachmentTransfers().get("draft"))
        assertNotNull(database.serverProfiles().get("server"))
        assertNotNull(database.realtimeCursors().get("device"))
        assertNotNull(database.conversations().get("akashic:test"))
    }

    @Test
    fun repeatedResetRebuildsHistoryAttachmentsAndThenAcceptsLiveEvents() = runBlocking {
        store.applyEvent(
            "server",
            "device",
            event(100, "sync.reset_required", buildJsonObject { put("reason", "inbox_retention_exceeded") }),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(200, "sync.reset_required", buildJsonObject { put("reason", "inbox_retention_exceeded") }),
            3,
        )
        val descriptor = buildJsonObject {
            put("attachment_id", "01ARZ3NDEKTSV4RRFFQ69G5FAW")
            put("filename", "history.png")
            put("content_type", "image/png")
            put("size_bytes", 3)
            put("sha256", "a".repeat(64))
        }
        store.applyEvent(
            "server",
            "device",
            event(201, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:history")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "历史恢复")
                        put("extra", buildJsonObject {})
                        put("attachments", buildJsonArray { add(descriptor) })
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            }),
            4,
        )
        store.applyEvent(
            "server",
            "device",
            event(202, "message.final", buildJsonObject {
                put("message_id", "akashic:test:live")
                put("content", "重建后的实时消息")
            }),
            5,
        )

        assertEquals(202, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)
        assertEquals("历史恢复", database.messages().get("akashic:test:history")!!.text)
        assertEquals("重建后的实时消息", database.messages().get("akashic:test:live")!!.text)
        assertEquals(
            listOf("01ARZ3NDEKTSV4RRFFQ69G5FAW"),
            database.mediaAttachments().forMessage("akashic:test:history").map { it.attachmentId },
        )
    }

    @Test
    fun attachmentProgressAndCursorCommitTogether() = runBlocking {
        database.attachmentTransfers().upsert(transfer(state = "uploading", offset = 0))
        store.applyEvent(
            "server",
            "device",
            event(1, "attachment.progress", buildJsonObject {
                put("attachment_id", "attachment")
                put("transferred_bytes", 1_048_576)
                put("size_bytes", 1_048_579)
            }),
            2,
        )

        assertEquals(1_048_576, database.attachmentTransfers().get("attachment")!!.transferredBytes)
        assertEquals(1, database.realtimeCursors().get("device")!!.lastAcknowledgedEventSeq)

        store.applyEvent(
            "server",
            "device",
            event(2, "attachment.ready", buildJsonObject {
                put("attachment_id", "attachment")
                put("filename", "image.png")
                put("content_type", "image/png")
                put("size_bytes", 1_048_579)
                put("sha256", "a".repeat(64))
            }),
            3,
        )
        assertEquals("ready", database.attachmentTransfers().get("attachment")!!.state)
    }

    @Test
    fun attachmentMessageMovesReadyToSendingThenSent() = runBlocking {
        database.attachmentTransfers().upsert(transfer(state = "ready", offset = 1_048_579))
        val payload = MessageSendPayload(
            clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            sessionId = "akashic:test",
            text = "",
            mediaRefs = listOf("attachment"),
            clientCreatedAt = Instant.EPOCH.toString(),
        )
        val command = WireEnvelope(
            v = 1,
            kind = WireKind.COMMAND,
            type = "message.send",
            id = payload.clientMessageId,
            connectionEpoch = 1,
            sessionId = "akashic:test",
            payload = ProtocolCodec.json().encodeToJsonElement(
                MessageSendPayload.serializer(),
                payload,
            ).jsonObject,
        )
        store.enqueueMessage(
            conversation = ConversationEntity("akashic:test", "server", "image.png", 2),
            message = MessageEntity(
                messageId = "user:${payload.clientMessageId}",
                clientMessageId = payload.clientMessageId,
                sessionId = "akashic:test",
                role = "user",
                text = "",
                deliveryState = "pending",
                createdAt = 2,
                updatedAt = 2,
            ),
            command = OutboxCommandEntity(
                commandId = command.id!!,
                serverId = "server",
                envelopeJson = ProtocolCodec.encode(command),
                state = "pending",
                attemptCount = 0,
                createdAt = 2,
                lastAttemptAt = null,
            ),
            attachments = listOf(mediaAttachment("attachment")),
        )

        assertEquals("sending", database.attachmentTransfers().get("attachment")!!.state)
        assertEquals(
            listOf("attachment"),
            database.mediaAttachments().forMessage("user:${payload.clientMessageId}").map { it.attachmentId },
        )
        assertEquals(
            listOf("attachment"),
            store.acknowledgeOutbox(command.id, 3),
        )
        assertEquals("sent", database.attachmentTransfers().get("attachment")!!.state)
        assertTrue(database.conversations().get("akashic:test")!!.remoteKnown)
        assertEquals(
            listOf("attachment"),
            database.mediaAttachments().forMessage("user:${payload.clientMessageId}").map { it.attachmentId },
        )
    }

    @Test
    fun retryDefiniteFailureKeepsVisualMessageAndCreatesNewIdempotencyKey() = runBlocking {
        val oldCommandId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val newCommandId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        enqueueRetryableMessage(oldCommandId, createdAt = 2_000)
        store.markOutboxAttempt(oldCommandId, attemptedAt = 2_100)
        store.retainFailedOutbox(oldCommandId, outcomeUnknown = false, updatedAt = 2_200)

        assertTrue(store.retryFailedMessage("visual-message", newCommandId, updatedAt = 2_300))
        assertTrue(!store.retryFailedMessage("visual-message", newCommandId, updatedAt = 2_301))

        val message = requireNotNull(database.messages().get("visual-message"))
        val command = requireNotNull(database.outbox().get(newCommandId))
        val payload = ProtocolCodec.decodePayload<MessageSendPayload>(ProtocolCodec.decode(command.envelopeJson).payload)
        assertEquals(newCommandId, message.clientMessageId)
        assertEquals("pending", message.deliveryState)
        assertEquals(newCommandId, payload.clientMessageId)
        assertEquals(2_000, command.createdAt)
        assertEquals(null, database.outbox().get(oldCommandId))
        assertEquals(1, database.messages().countForSession("akashic:test"))
    }

    @Test
    fun unavailableSessionRetainsPendingOutboxBeforeTransportAttempt() = runBlocking {
        val commandId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        enqueueRetryableMessage(commandId, createdAt = 2_000)

        store.retainUnsentOutbox(commandId, updatedAt = 2_100)

        assertEquals("failed_retryable", database.messages().get("visual-message")!!.deliveryState)
        assertEquals("failed_retryable", database.outbox().get(commandId)!!.state)
    }

    @Test
    fun verifyUnknownOutcomeReusesOriginalIdempotencyKey() = runBlocking {
        val oldCommandId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val unusedCommandId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        enqueueRetryableMessage(oldCommandId, createdAt = 2_000)
        store.markOutboxAttempt(oldCommandId, attemptedAt = 2_100)
        store.retainFailedOutbox(oldCommandId, outcomeUnknown = true, updatedAt = 2_200)

        assertTrue(store.retryFailedMessage("visual-message", unusedCommandId, updatedAt = 2_300))
        assertTrue(!store.retryFailedMessage("visual-message", unusedCommandId, updatedAt = 2_301))

        val message = requireNotNull(database.messages().get("visual-message"))
        val command = requireNotNull(database.outbox().get(oldCommandId))
        assertEquals(oldCommandId, message.clientMessageId)
        assertEquals("pending", message.deliveryState)
        assertEquals("retry", command.state)
        assertEquals(null, database.outbox().get(unusedCommandId))
    }

    @Test
    fun failedLocalMessageKeepsChronologicalPositionAmongServerMessages() = runBlocking {
        database.messages().upsert(retryMessage("server-before", null, 1_000, 1))
        database.messages().upsert(retryMessage("failed-local", "failed-command", 2_000, null, "failed_retryable"))
        database.messages().upsert(retryMessage("server-after", null, 3_000, 2))

        val ordered = database.messages().observeMessages("akashic:test").first()

        assertEquals(listOf("server-before", "failed-local", "server-after"), ordered.map { it.messageId })
    }

    @Test
    fun sentAttachmentLinkAndCachePathSurviveDatabaseRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sent-media-${System.nanoTime()}.db"
        val cachedFile = context.cacheDir.resolve("sent-media-${System.nanoTime()}.bin")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val messageId = "user:01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val attachment = mediaAttachment("01ARZ3NDEKTSV4RRFFQ69G5FAW").copy(
            sizeBytes = 3,
            transferredBytes = 3,
            cachePath = cachedFile.absolutePath,
        )
        var persisted = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            persisted.serverProfiles().upsert(
                ServerProfileEntity("restart", "restart", "restart-device", "alias", "pin", "[]", "[]", "[]", 1),
            )
            persisted.conversations().upsert(ConversationEntity("akashic:restart", "restart", "restart", 1))
            persisted.messages().upsert(
                MessageEntity(messageId, messageId.removePrefix("user:"), "akashic:restart", "user", "附件：image.png", "sent", 1, 1),
            )
            persisted.mediaAttachments().upsert(attachment.copy(serverId = "restart", sessionId = "akashic:restart"))
            persisted.mediaAttachments().linkAll(
                listOf(MessageAttachmentEntity(messageId, attachment.attachmentId, 0)),
            )
            persisted.close()

            persisted = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val restored = persisted.mediaAttachments().forMessage(messageId).single()
            assertEquals(cachedFile.absolutePath, restored.cachePath)
            assertEquals(byteArrayOf(1, 2, 3).toList(), cachedFile.readBytes().toList())
        } finally {
            persisted.close()
            context.deleteDatabase(name)
            cachedFile.delete()
        }
    }

    @Test
    fun liveAndHistoryMessagesIdempotentlyShareOutboundAttachment() = runBlocking {
        val descriptor = buildJsonObject {
            put("attachment_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
            put("filename", "answer.png")
            put("content_type", "image/png")
            put("size_bytes", 3)
            put("sha256", "a".repeat(64))
        }
        store.applyEvent(
            "server",
            "device",
            event(1, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:attachment")
                put("content", "完成")
                put("attachments", buildJsonArray { add(descriptor) })
            }),
            2,
        )
        store.applyEvent(
            "server",
            "device",
            event(2, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:1")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "完成")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:05Z")
                        put("attachments", buildJsonArray { add(descriptor) })
                    })
                })
            }),
            3,
        )

        val attachment = database.mediaAttachments().get("01ARZ3NDEKTSV4RRFFQ69G5FAV")
        assertNotNull(attachment)
        assertEquals("pending", attachment!!.state)
        assertEquals(
            listOf(attachment.attachmentId),
            database.mediaAttachments().forMessage("akashic:test:assistant:attachment").map { it.attachmentId },
        )
        assertEquals(
            listOf(attachment.attachmentId),
            database.mediaAttachments().forMessage("akashic:test:1").map { it.attachmentId },
        )
    }

    @Test
    fun largeMessageAttachmentWaitsForExplicitDownload() = runBlocking {
        val smallId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val largeId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        fun descriptor(id: String, sizeBytes: Long) = buildJsonObject {
            put("attachment_id", id)
            put("filename", "$id.pdf")
            put("content_type", "application/pdf")
            put("size_bytes", sizeBytes)
            put("sha256", "a".repeat(64))
        }
        store.applyEvent(
            "server",
            "device",
            event(1, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:large-attachment")
                put("content", "附件")
                put("attachments", buildJsonArray {
                    add(descriptor(smallId, 10L * 1024 * 1024 - 1))
                    add(descriptor(largeId, 10L * 1024 * 1024))
                })
            }),
            2,
        )

        assertEquals("pending", database.mediaAttachments().get(smallId)!!.state)
        assertEquals("remote", database.mediaAttachments().get(largeId)!!.state)
        assertEquals(listOf(smallId), database.mediaAttachments().pendingDownloads("server").map { it.attachmentId })

        mediaCache.reconcile()
        assertEquals("remote", database.mediaAttachments().get(largeId)!!.state)

        assertEquals(1, database.mediaAttachments().updateDownload(smallId, 0, "failed", 3))
        val sentTypes = mutableListOf<String>()
        val downloads = AttachmentDownloadCoordinator(
            database.mediaAttachments(),
            mediaCache,
            sendCommand = { type, _, _, _ -> sentTypes += type; true },
            onTransportUnavailable = {},
            onDownloadFailed = {},
        )
        downloads.retry(largeId)
        downloads.retry(largeId)
        downloads.onConnectionReady("server")

        assertEquals(listOf("attachment.download"), sentTypes)
        assertEquals(
            listOf(largeId),
            database.mediaAttachments().pendingDownloads("server").map { it.attachmentId },
        )
    }

    @Test
    fun unavailableSessionDownloadDoesNotBlockAnotherConversation() = runBlocking {
        val unavailableId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val availableId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        database.conversations().upsert(
            ConversationEntity("akashic:stale", "server", "stale", 2, remoteKnown = true),
        )
        database.mediaAttachments().upsertAll(
            listOf(
                mediaAttachment(unavailableId).copy(
                    sessionId = "akashic:stale",
                    sizeBytes = 1,
                    transferredBytes = 0,
                    state = "pending",
                    cachePath = mediaCache.cachePath(unavailableId),
                    updatedAt = 1,
                ),
                mediaAttachment(availableId).copy(
                    sizeBytes = 1,
                    transferredBytes = 0,
                    state = "pending",
                    cachePath = mediaCache.cachePath(availableId),
                    updatedAt = 2,
                ),
            ),
        )
        val sentSessions = mutableListOf<String>()
        val downloads = AttachmentDownloadCoordinator(
            database.mediaAttachments(),
            mediaCache,
            sendCommand = { _, _, sessionId, _ -> sentSessions += sessionId; true },
            onTransportUnavailable = {},
            onDownloadFailed = {},
            canTransfer = { it.sessionId != "akashic:stale" },
        )

        downloads.onConnectionReady("server")

        assertEquals(listOf("akashic:test"), sentSessions)
        assertEquals("pending", database.mediaAttachments().get(unavailableId)!!.state)
    }

    @Test
    fun attachmentReconcileDeletesSentAndOrphanFiles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = context.cacheDir.resolve("attachment-reconcile-${System.nanoTime()}")
        root.mkdirs()
        val dao = database.attachmentTransfers()
        dao.upsert(transfer(state = "sent", offset = 1_048_579))
        dao.upsert(transfer(id = "pending", state = "pending", offset = 0))
        root.resolve("attachment.upload").writeText("sent")
        root.resolve("pending.upload").writeText("pending")
        root.resolve("orphan.upload").writeText("orphan")
        val drafts = AttachmentDraftStore(context.contentResolver, root, dao)

        drafts.reconcile()

        assertEquals(null, dao.get("attachment"))
        assertEquals(false, root.resolve("attachment.upload").exists())
        assertEquals(false, root.resolve("orphan.upload").exists())
        assertEquals(true, root.resolve("pending.upload").exists())
        root.deleteRecursively()
        Unit
    }

    @Test
    fun reliabilityGateOrdersLocalFailureAndTracksDurableReadingState() = runBlocking {
        // 1. 模拟断网消息先于稍后到达的服务端消息创建
        database.messages().upsert(retryMessage("remote-before", null, 1_000, 10))
        database.messages().upsert(retryMessage("local-failed", "client-failed", 2_000, null, "failed_retryable"))
        database.messages().upsert(retryMessage("remote-after", null, 3_000, 11))
        val ordered = database.messages().observeMessages("akashic:test").first()
        assertEquals(listOf("remote-before", "local-failed", "remote-after"), ordered.map { it.messageId })

        // 2. 阅读水位只统计之后完成的助手消息，锚点独立保存
        database.messages().upsert(
            MessageEntity("assistant-old", null, "akashic:test", "assistant", "旧回答", "complete", 4_000, 4_000, 12),
        )
        database.conversationReadStates().markReadThrough("akashic:test", 4_000, 4_100)
        database.conversationReadStates().savePosition("akashic:test", "local-failed", -14, 4_200)
        database.messages().upsert(
            MessageEntity("assistant-new", null, "akashic:test", "assistant", "新回答", "complete", 5_000, 5_000, 13),
        )
        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals(1, summary.unreadCount)
        assertEquals("新回答", summary.lastMessagePreview)
        assertEquals("local-failed", summary.anchorMessageId)
        assertEquals(-14, summary.anchorOffsetPx)
    }

    @Test
    fun canonicalMergeMigratesOptimisticAndStreamingReadingAnchors() = runBlocking {
        // 1. 历史同步将 optimistic 用户消息与阅读锚点一起迁移
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val optimisticId = "user:$clientId"
        database.messages().upsert(
            MessageEntity(
                optimisticId,
                clientId,
                "akashic:test",
                "user",
                "本地问题",
                "sent",
                1,
                1,
            ),
        )
        assertTrue(store.saveReadingPosition("akashic:test", optimisticId, -12, "server", 2))
        store.applyEvent(
            "server",
            "device",
            event(1, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:user:canonical")
                        put("client_message_id", clientId)
                        put("session_key", "akashic:test")
                        put("seq", 0)
                        put("role", "user")
                        put("content", "本地问题")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:00Z")
                    })
                })
            }),
            3,
        )
        var summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:user:canonical", summary.anchorMessageId)
        assertEquals(-12, summary.anchorOffsetPx)
        assertTrue(store.saveReadingPosition("akashic:test", optimisticId, -30, "server", 4))
        summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:user:canonical", summary.anchorMessageId)
        assertEquals(-30, summary.anchorOffsetPx)

        // 2. 最终事件将 streaming 助手消息与阅读锚点一起迁移
        store.applyEvent("server", "device", event(2, "turn.started", buildJsonObject {}), 4)
        assertTrue(store.saveReadingPosition("akashic:test", "assistant:turn", -20, "server", 5))
        store.applyEvent(
            "server",
            "device",
            event(3, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:canonical")
                put("content", "最终回答")
            }),
            6,
        )
        summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:assistant:canonical", summary.anchorMessageId)
        assertEquals(-20, summary.anchorOffsetPx)
        assertTrue(store.saveReadingPosition("akashic:test", "assistant:turn", -40, "server", 7))
        summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:assistant:canonical", summary.anchorMessageId)
        assertEquals(-40, summary.anchorOffsetPx)
    }

    @Test
    fun lateFirstReadingSaveResolvesCanonicalAlias() = runBlocking {
        store.applyEvent("server", "device", event(1, "turn.started", buildJsonObject {}), 2)
        store.applyEvent(
            "server",
            "device",
            event(2, "message.final", buildJsonObject {
                put("message_id", "akashic:test:assistant:canonical")
                put("content", "最终回答")
            }),
            3,
        )

        assertTrue(store.saveReadingPosition("akashic:test", "assistant:turn", -18, "server", 4))
        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:assistant:canonical", summary.anchorMessageId)
        assertEquals(-18, summary.anchorOffsetPx)
    }

    @Test
    fun canonicalHistoryReplacesCompletedTurnWhoseFinalEventWasLost() = runBlocking {
        store.applyEvent("server", "device", event(1, "turn.started", buildJsonObject {}), 2)
        database.messages().upsert(
            MessageEntity(
                messageId = "user:history-owner",
                clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                sessionId = "akashic:test",
                role = "user",
                text = "问题",
                deliveryState = "sent",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        store.applyEvent(
            "server",
            "device",
            event(
                2,
                "react.thinking.delta",
                buildJsonObject {
                    put("block_id", "thinking:turn:0")
                    put("ordinal", 0)
                    put("delta", "正在分析")
                },
            ),
            3,
        )

        store.applyEvent(
            "server",
            "device",
            event(3, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:assistant:canonical")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("client_message_id", "01ARZ3NDEKTSV4RRFFQ69G5FAV")
                        put("content", "最终回答")
                        put("extra", buildJsonObject { put("control_turn_id", "turn") })
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            }),
            4,
        )

        assertEquals(null, database.messages().get("assistant:turn"))
        val canonical = database.messages().get("akashic:test:assistant:canonical")
        assertNotNull(canonical)
        assertEquals("complete", canonical!!.deliveryState)
        assertEquals("最终回答", canonical.text)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", canonical.turnClientMessageId)
        assertEquals("turn", canonical.controlTurnId)
        assertEquals(
            "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            database.messages().get("user:history-owner")?.clientMessageId,
        )
        assertTrue(database.messages().getBlocks(canonical.messageId).all { it.status == "completed" })
        assertTrue(database.messages().activeAssistantTurns("server").isEmpty())
    }

    @Test
    fun lateReadingSaveResolvesTwoStageCanonicalAlias() = runBlocking {
        val completedAt = Instant.parse("2026-07-14T16:00:05Z").toEpochMilli()
        store.applyEvent("server", "device", event(1, "turn.started", buildJsonObject {}), completedAt - 100)
        store.applyEvent(
            "server",
            "device",
            event(2, "message.final", buildJsonObject { put("content", "两段迁移回答") }),
            completedAt,
        )
        store.saveComposerDraft(
            "akashic:test",
            "沿着这条回答继续",
            "assistant:turn",
            "server",
            completedAt,
        )
        store.applyEvent(
            "server",
            "device",
            event(3, "history.page", buildJsonObject {
                put("total", 1)
                put("page", 1)
                put("page_size", 10)
                put("items", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "akashic:test:assistant:history-canonical")
                        put("session_key", "akashic:test")
                        put("seq", 1)
                        put("role", "assistant")
                        put("content", "两段迁移回答")
                        put("extra", buildJsonObject {})
                        put("ts", "2026-07-14T16:00:05Z")
                    })
                })
            }),
            completedAt + 1,
        )

        assertTrue(store.saveReadingPosition("akashic:test", "assistant:turn", -22, "server", completedAt + 2))
        val summary = database.conversations().observeSummaries("server").first().single()
        assertEquals("akashic:test:assistant:history-canonical", summary.anchorMessageId)
        assertEquals(-22, summary.anchorOffsetPx)
        assertEquals(
            "akashic:test:assistant:history-canonical",
            database.composerDrafts().get("server", "akashic:test")?.replyToMessageId,
        )
    }

    private fun transfer(state: String, offset: Long, id: String = "attachment") = AttachmentTransferEntity(
        attachmentId = id,
        serverId = "server",
        sessionId = "akashic:test",
        filename = "image.png",
        contentType = "image/png",
        sizeBytes = 1_048_579,
        sha256 = "a".repeat(64),
        transferredBytes = offset,
        state = state,
        updatedAt = 1,
    )

    private fun pendingUserMessage(
        messageId: String,
        clientMessageId: String,
        text: String,
        createdAt: Long,
    ) = MessageEntity(
        messageId = messageId,
        clientMessageId = clientMessageId,
        sessionId = "akashic:test",
        role = "user",
        text = text,
        deliveryState = "pending",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun pendingOutbox(commandId: String, createdAt: Long) = OutboxCommandEntity(
        commandId = commandId,
        serverId = "server",
        envelopeJson = "{}",
        state = "pending",
        attemptCount = 0,
        createdAt = createdAt,
        lastAttemptAt = null,
    )

    private fun mediaAttachment(id: String) = MediaAttachmentEntity(
        attachmentId = id,
        serverId = "server",
        sessionId = "akashic:test",
        filename = "image.png",
        contentType = "image/png",
        sizeBytes = 1_048_579,
        sha256 = "a".repeat(64),
        transferredBytes = 1_048_579,
        state = "cached",
        cachePath = "cache/$id.bin",
        lastAccessedAt = 1,
        updatedAt = 1,
    )

    private suspend fun enqueueRetryableMessage(commandId: String, createdAt: Long) {
        val payload = MessageSendPayload(
            clientMessageId = commandId,
            sessionId = "akashic:test",
            text = "需要恢复的消息",
            mediaRefs = emptyList(),
            clientCreatedAt = "2026-07-16T08:00:00Z",
        )
        val envelope = WireEnvelope(
            v = 1,
            kind = WireKind.COMMAND,
            type = "message.send",
            id = commandId,
            connectionEpoch = 1,
            sessionId = "akashic:test",
            payload = ProtocolCodec.json().encodeToJsonElement(MessageSendPayload.serializer(), payload).jsonObject,
        )
        store.enqueueMessage(
            ConversationEntity("akashic:test", "server", "test", 1),
            retryMessage("visual-message", commandId, createdAt, null, "pending"),
            OutboxCommandEntity(commandId, "server", ProtocolCodec.encode(envelope), "pending", 0, createdAt, null),
            emptyList(),
        )
    }

    private fun retryMessage(
        messageId: String,
        clientMessageId: String?,
        createdAt: Long,
        serverSeq: Long?,
        state: String = "complete",
    ) = MessageEntity(
        messageId = messageId,
        clientMessageId = clientMessageId,
        sessionId = "akashic:test",
        role = "user",
        text = messageId,
        deliveryState = state,
        createdAt = createdAt,
        updatedAt = createdAt,
        serverSeq = serverSeq,
    )

    private fun event(
        sequence: Long,
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
        turnId: String = "turn",
    ) = WireEnvelope(
        v = 1,
        kind = WireKind.EVENT,
        type = type,
        id = "01J00000000000000000000000",
        connectionEpoch = 1,
        eventSeq = sequence,
        sessionId = "akashic:test",
        turnId = turnId,
        payload = payload,
    )
}
