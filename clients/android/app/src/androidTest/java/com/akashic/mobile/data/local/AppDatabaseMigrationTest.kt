package com.akashic.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @After
    fun removeTestDatabases() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(
            DATABASE_1_2,
            DATABASE_2_3,
            DATABASE_3_4,
            DATABASE_4_5,
            DATABASE_5_6,
            DATABASE_6_7,
            DATABASE_7_8,
            DATABASE_8_9,
            DATABASE_9_10,
            DATABASE_10_11,
            DATABASE_11_12,
            DATABASE_12_13,
            DATABASE_13_14,
            DATABASE_14_15,
            DATABASE_15_16,
            DATABASE_16_17,
            DATABASE_17_18,
            DATABASE_17_18_RETRIED,
            DATABASE_17_18_CANONICAL,
            DATABASE_17_18_INVALID,
            DATABASE_18_19,
            "migration-19-20",
        )
            .forEach(context::deleteDatabase)
    }

    @Test
    fun migrate1To2CreatesMediaCacheTables() {
        helper.createDatabase(DATABASE_1_2, 1).apply {
            execSQL(
                """
                INSERT INTO server_profiles VALUES(
                    'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2)")
            execSQL(
                """
                INSERT INTO messages VALUES(
                    'old-message', NULL, 'akashic:test', 'assistant', '旧消息', 'complete', 3, 3
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_1_2,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query("SELECT text FROM messages WHERE messageId = 'old-message'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("旧消息", cursor.getString(0))
            }
            database.query("SELECT COUNT(*) FROM media_attachments").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate2To3AddsServerSequenceWithoutLosingMessages() {
        helper.createDatabase(DATABASE_2_3, 2).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2)")
            execSQL(
                "INSERT INTO messages VALUES('old-message', NULL, 'akashic:test', 'assistant', '旧消息', 'complete', 3, 4)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_2_3,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT text, serverSeq FROM messages WHERE messageId = 'old-message'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("旧消息", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate3To4AddsReplyProjectionWithoutDroppingMessages() {
        helper.createDatabase(DATABASE_3_4, 3).apply {
            execSQL(
                """
                INSERT INTO messages(
                    messageId, clientMessageId, sessionId, role, text, deliveryState,
                    createdAt, updatedAt, serverSeq
                ) VALUES ('message-1', NULL, 'akashic:test', 'assistant', '保留我', 'complete', 1, 1, 1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_3_4,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        ).use { database ->
            database.query(
                "SELECT text, replyToMessageId, replyRole, replyPreview FROM messages WHERE messageId = 'message-1'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移后消息丢失" }
                assertEquals("保留我", cursor.getString(0))
                assertEquals(null, cursor.getString(1))
                assertEquals(null, cursor.getString(2))
                assertEquals(null, cursor.getString(3))
            }
        }
    }

    @Test
    fun migrate4To5AddsConversationReadingState() {
        helper.createDatabase(DATABASE_4_5, 4).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_4_5,
            5,
            true,
            AppDatabase.MIGRATION_4_5,
        ).use { database ->
            database.execSQL(
                "INSERT INTO conversation_read_states VALUES('akashic:test', 3, 'message-1', -12, 4)",
            )
            database.query(
                "SELECT lastReadAt, anchorMessageId, anchorOffsetPx FROM conversation_read_states",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals("message-1", cursor.getString(1))
                assertEquals(-12, cursor.getInt(2))
            }
        }
    }

    @Test
    fun migrate5To6PersistsKnownRemoteConversationIdentity() {
        helper.createDatabase(DATABASE_5_6, 5).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:remote', 'server', '远端会话', 2)")
            execSQL("INSERT INTO conversations VALUES('akashic:live', 'server', '实时会话', 3)")
            execSQL("INSERT INTO conversations VALUES('akashic:local', 'server', '本机会话', 3)")
            execSQL("INSERT INTO conversations VALUES('akashic:failed', 'server', '失败草稿', 3)")
            execSQL(
                """
                INSERT INTO messages(
                    messageId, clientMessageId, sessionId, role, text, deliveryState,
                    createdAt, updatedAt, serverSeq, replyToMessageId, replyRole, replyPreview
                ) VALUES(
                    'remote-message', NULL, 'akashic:remote', 'assistant', '旧消息', 'complete',
                    4, 4, 1, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages(
                    messageId, clientMessageId, sessionId, role, text, deliveryState,
                    createdAt, updatedAt, serverSeq, replyToMessageId, replyRole, replyPreview
                ) VALUES(
                    'live-final', NULL, 'akashic:live', 'assistant', '刚完成的回答', 'complete',
                    5, 5, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages(
                    messageId, clientMessageId, sessionId, role, text, deliveryState,
                    createdAt, updatedAt, serverSeq, replyToMessageId, replyRole, replyPreview
                ) VALUES(
                    'local-pending', 'local-client', 'akashic:local', 'user', '还没发送', 'pending',
                    6, 6, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO messages(
                    messageId, clientMessageId, sessionId, role, text, deliveryState,
                    createdAt, updatedAt, serverSeq, replyToMessageId, replyRole, replyPreview
                ) VALUES(
                    'failed-assistant', NULL, 'akashic:failed', 'assistant', '', 'failed',
                    7, 7, NULL, NULL, NULL, NULL
                )
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_5_6,
            6,
            true,
            AppDatabase.MIGRATION_5_6,
        ).use { database ->
            database.query("SELECT sessionId, remoteKnown FROM conversations ORDER BY sessionId").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("akashic:failed", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                check(cursor.moveToNext())
                assertEquals("akashic:live", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                check(cursor.moveToNext())
                assertEquals("akashic:local", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                check(cursor.moveToNext())
                assertEquals("akashic:remote", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    @Test
    fun migrate6To7CreatesConversationOwnedComposerDrafts() {
        helper.createDatabase(DATABASE_6_7, 6).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_6_7,
            7,
            true,
            AppDatabase.MIGRATION_6_7,
        ).use { database ->
            database.execSQL(
                """
                INSERT INTO composer_drafts(
                    sessionId, serverId, text, replyToMessageId, updatedAt
                ) VALUES('akashic:test', 'server', '保留草稿', 'missing-message', 3)
                """.trimIndent(),
            )
            database.query(
                "SELECT text, replyToMessageId, updatedAt FROM composer_drafts WHERE sessionId = 'akashic:test'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("保留草稿", cursor.getString(0))
                assertEquals("missing-message", cursor.getString(1))
                assertEquals(3L, cursor.getLong(2))
            }

        }
    }

    @Test
    fun migrate7To8CreatesDurableMessageNotificationQueue() {
        helper.createDatabase(DATABASE_7_8, 7).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('message-1', NULL, 'akashic:test', 'assistant', '完成', 'complete', 3, 3, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_7_8,
            8,
            true,
            AppDatabase.MIGRATION_7_8,
        ).use { database ->
            database.execSQL(
                "INSERT INTO pending_message_notifications VALUES('message-1', 'server', 'akashic:test', '完成', 0, 'COMPLETE', 4)",
            )
            database.query(
                "SELECT content, attention FROM pending_message_notifications WHERE messageId = 'message-1'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("完成", cursor.getString(0))
                assertEquals("COMPLETE", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate8To9KeepsNotificationsAfterProjectionCleanup() {
        helper.createDatabase(DATABASE_8_9, 8).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('message-1', NULL, 'akashic:test', 'assistant', '完成', 'complete', 3, 3, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO pending_message_notifications VALUES('message-1', 'server', 'akashic:test', '完成', 0, 'COMPLETE', 4)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_8_9,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        ).use { database ->
            database.execSQL("DELETE FROM messages WHERE messageId = 'message-1'")
            database.query(
                "SELECT content FROM pending_message_notifications WHERE messageId = 'message-1'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("完成", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate9To10CreatesDurableTurnStopQueueWithoutLosingHistory() {
        helper.createDatabase(DATABASE_9_10, 9).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('assistant:turn-1', NULL, 'akashic:test', 'assistant', '生成中', 'streaming', 3, 3, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_9_10,
            10,
            true,
            AppDatabase.MIGRATION_9_10,
        ).use { database ->
            database.execSQL(
                "INSERT INTO pending_turn_stops VALUES('stop-1', 'server', 'akashic:test', 'turn-1', 4)",
            )
            database.query("SELECT text FROM messages WHERE messageId = 'assistant:turn-1'").use { cursor ->
                check(cursor.moveToFirst()) { "迁移后 streaming 消息丢失" }
                assertEquals("生成中", cursor.getString(0))
            }
            database.query("SELECT turnId FROM pending_turn_stops WHERE commandId = 'stop-1'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("turn-1", cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate10To11RepairsOverlappingStreamingTurnsWithoutDeletingContent() {
        helper.createDatabase(DATABASE_10_11, 10).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('assistant:turn-old', NULL, 'akashic:test', 'assistant', '旧回答', 'streaming', 3, 8, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO messages VALUES('assistant:turn-new', NULL, 'akashic:test', 'assistant', '新回答', 'streaming', 4, 4, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO turn_blocks VALUES('block-old', 'assistant:turn-old', 'turn-old', 0, 'thinking', 'running', '保留思考', 8)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_10_11,
            11,
            true,
            AppDatabase.MIGRATION_10_11,
        ).use { database ->
            database.query(
                "SELECT messageId, text, deliveryState FROM messages ORDER BY createdAt",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("assistant:turn-old", cursor.getString(0))
                assertEquals("旧回答", cursor.getString(1))
                assertEquals("interrupted", cursor.getString(2))
                check(cursor.moveToNext())
                assertEquals("assistant:turn-new", cursor.getString(0))
                assertEquals("新回答", cursor.getString(1))
                assertEquals("streaming", cursor.getString(2))
            }
            database.query("SELECT content, status FROM turn_blocks WHERE blockId = 'block-old'").use { cursor ->
                check(cursor.moveToFirst()) { "迁移删除了旧 turn block" }
                assertEquals("保留思考", cursor.getString(0))
                assertEquals("completed", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate11To12CreatesResumableMessageContentStateWithoutChangingMessages() {
        helper.createDatabase(DATABASE_11_12, 11).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('assistant:long', NULL, 'akashic:test', 'assistant', '正文预览', 'complete', 3, 8, 7, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_11_12,
            12,
            true,
            AppDatabase.MIGRATION_11_12,
        ).use { database ->
            database.execSQL(
                "INSERT INTO message_content_transfers VALUES('assistant:long', 'server', 'akashic:test', 400000, '${"a".repeat(64)}', 262144, 'downloading', 9)",
            )
            database.query("SELECT text FROM messages WHERE messageId = 'assistant:long'").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("正文预览", cursor.getString(0))
            }
            database.query(
                "SELECT transferredBytes, state FROM message_content_transfers WHERE messageId = 'assistant:long'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(262144L, cursor.getLong(0))
                assertEquals("downloading", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate12To13CreatesWebUiTablesIndexesAndForeignKeys() {
        helper.createDatabase(DATABASE_12_13, 12).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:legacy', 'server', '迁移会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('legacy-message', NULL, 'akashic:legacy', 'assistant', '迁移正文', 'complete', 3, 4, 7, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO message_content_transfers VALUES('legacy-message', 'server', 'akashic:legacy', 400000, '${"a".repeat(64)}', 262144, 'downloading', 5)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_12_13,
            13,
            true,
            AppDatabase.MIGRATION_12_13,
        ).use { database ->
            database.query(
                "SELECT serverId, displayName, deviceId, keyAlias, applicationKeyFingerprint, " +
                    "lanEndpointsJson, tunnelEndpointsJson, tlsSpkiPinsJson, createdAt " +
                    "FROM server_profiles WHERE serverId = 'server'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 server profile" }
                assertEquals("server", cursor.getString(0))
                assertEquals("电脑", cursor.getString(1))
                assertEquals("device", cursor.getString(2))
                assertEquals("alias", cursor.getString(3))
                assertEquals("pin", cursor.getString(4))
                assertEquals("[]", cursor.getString(5))
                assertEquals("[]", cursor.getString(6))
                assertEquals("[]", cursor.getString(7))
                assertEquals(1L, cursor.getLong(8))
            }
            database.query(
                "SELECT title, remoteKnown FROM conversations WHERE sessionId = 'akashic:legacy'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 conversation" }
                assertEquals("迁移会话", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query(
                "SELECT text, deliveryState, serverSeq FROM messages WHERE messageId = 'legacy-message'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 message" }
                assertEquals("迁移正文", cursor.getString(0))
                assertEquals("complete", cursor.getString(1))
                assertEquals(7L, cursor.getLong(2))
            }
            database.query(
                "SELECT transferredBytes, state FROM message_content_transfers WHERE messageId = 'legacy-message'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 message content transfer" }
                assertEquals(262144L, cursor.getLong(0))
                assertEquals("downloading", cursor.getString(1))
            }
            listOf("mobile_webui_state", "mobile_webui_generations", "mobile_webui_blobs", "mobile_webui_rejects")
                .forEach { table ->
                    database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                    database.query(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'",
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                    database.query("PRAGMA foreign_key_list('$table')").use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals("server_profiles", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                    }
                }
            database.query("PRAGMA index_list('mobile_webui_generations')").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count += 1
                assertEquals(4, count)
            }
            database.query("PRAGMA index_list('mobile_webui_blobs')").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count += 1
                assertEquals(3, count)
            }
        }
    }

    @Test
    fun migrate13To14AddsSeparateTurnClientMessageIdentity() {
        helper.createDatabase(DATABASE_13_14, 13).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:legacy', 'server', '迁移会话', 2, 1)")
            execSQL(
                "INSERT INTO messages VALUES('legacy-message', '01ARZ3NDEKTSV4RRFFQ69G5FAV', " +
                    "'akashic:legacy', 'assistant', " +
                    "'流式正文', 'streaming', 3, 4, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO messages VALUES('legacy-user', '01ARZ3NDEKTSV4RRFFQ69G5FAW', " +
                    "'akashic:legacy', 'user', '问题', 'sent', 2, 2, NULL, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO messages VALUES('legacy-complete', '01ARZ3NDEKTSV4RRFFQ69G5FAX', " +
                    "'akashic:legacy', 'assistant', '旧回答', 'complete', 1, 1, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_13_14,
            14,
            true,
            AppDatabase.MIGRATION_13_14,
        ).use { database ->
            database.query(
                "SELECT text, deliveryState, clientMessageId, turnClientMessageId " +
                    "FROM messages WHERE messageId = 'legacy-message'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 legacy streaming message" }
                assertEquals("流式正文", cursor.getString(0))
                assertEquals("streaming", cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
                assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", cursor.getString(3))
            }
            database.query(
                "SELECT clientMessageId, turnClientMessageId FROM messages " +
                    "WHERE messageId = 'legacy-user'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 legacy user message" }
                assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAW", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
            database.query(
                "SELECT clientMessageId, turnClientMessageId FROM messages " +
                    "WHERE messageId = 'legacy-complete'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 legacy complete assistant" }
                assertEquals(true, cursor.isNull(0))
                assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAX", cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate14To15AddsAuthoritativeControlTurnIdentity() {
        helper.createDatabase(DATABASE_14_15, 14).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:legacy', 'server', '迁移会话', 2, 1)")
            execSQL(
                "INSERT INTO messages(" +
                    "messageId, clientMessageId, sessionId, role, text, deliveryState, createdAt, updatedAt, " +
                    "replyToMessageId, replyRole, replyPreview, turnClientMessageId" +
                    ") VALUES('assistant:legacy-turn', NULL, 'akashic:legacy', 'assistant', '流式正文', " +
                    "'streaming', 3, 4, NULL, NULL, NULL, '01ARZ3NDEKTSV4RRFFQ69G5FAV')",
            )
            execSQL(
                "INSERT INTO messages(" +
                    "messageId, clientMessageId, sessionId, role, text, deliveryState, createdAt, updatedAt, " +
                    "replyToMessageId, replyRole, replyPreview, turnClientMessageId" +
                    ") VALUES('canonical-complete', NULL, 'akashic:legacy', 'assistant', '旧回答', " +
                    "'complete', 2, 2, NULL, NULL, NULL, '01ARZ3NDEKTSV4RRFFQ69G5FAX')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_14_15,
            15,
            true,
            AppDatabase.MIGRATION_14_15,
        ).use { database ->
            database.query(
                "SELECT turnClientMessageId, controlTurnId FROM messages " +
                    "WHERE messageId = 'assistant:legacy-turn'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 v14 streaming message" }
                assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", cursor.getString(0))
                assertEquals("legacy-turn", cursor.getString(1))
            }
            database.query(
                "SELECT controlTurnId FROM messages WHERE messageId = 'canonical-complete'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失 v14 complete assistant" }
                assertEquals(true, cursor.isNull(0))
            }
        }
    }

    @Test
    fun migrate15To16DropsOldSessionProjectionAndKeepsPairingCursor() {
        helper.createDatabase(DATABASE_15_16, 15).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('mobile:legacy-device', 'server', '旧会话', 2, 1)")
            execSQL(
                "INSERT INTO messages(" +
                    "messageId, clientMessageId, sessionId, role, text, deliveryState, " +
                    "createdAt, updatedAt) VALUES(" +
                    "'mobile:legacy-device:0', NULL, 'mobile:legacy-device', 'assistant', '旧消息', " +
                    "'complete', 3, 3)",
            )
            execSQL(
                "INSERT INTO outbox_commands VALUES(" +
                    "'command', 'server', '{}', 'pending', 0, 4, NULL)",
            )
            execSQL(
                "INSERT INTO attachment_transfers VALUES(" +
                    "'attachment', 'server', 'mobile:legacy-device', 'a.txt', 'text/plain', " +
                    "1, 'a', 0, 'pending', 5)",
            )
            execSQL(
                "INSERT INTO turn_blocks VALUES(" +
                    "'block', 'mobile:legacy-device:0', 'turn', 0, 'thinking', " +
                    "'running', '旧思考', 6)",
            )
            execSQL(
                "INSERT INTO media_attachments VALUES(" +
                    "'media', 'server', 'mobile:legacy-device', 'a.txt', 'text/plain', " +
                    "1, 'a', 1, 'cached', '/tmp/a.txt', 7, 7)",
            )
            execSQL(
                "INSERT INTO message_attachments VALUES(" +
                    "'mobile:legacy-device:0', 'media', 0)",
            )
            execSQL(
                "INSERT INTO conversation_read_states VALUES(" +
                    "'mobile:legacy-device', 8, 'mobile:legacy-device:0', 4, 8)",
            )
            execSQL(
                "INSERT INTO composer_drafts VALUES(" +
                    "'mobile:legacy-device', 'server', '旧草稿', NULL, 9)",
            )
            execSQL(
                "INSERT INTO pending_message_notifications VALUES(" +
                    "'notification', 'server', 'mobile:legacy-device', '旧通知', 0, 'normal', 10)",
            )
            execSQL(
                "INSERT INTO pending_turn_stops VALUES(" +
                    "'stop', 'server', 'mobile:legacy-device', 'turn', 11)",
            )
            execSQL(
                "INSERT INTO message_content_transfers VALUES(" +
                    "'mobile:legacy-device:0', 'server', 'mobile:legacy-device', " +
                    "10, 'b', 2, 'pending', 12)",
            )
            execSQL("INSERT INTO realtime_cursors VALUES('device', 'server', 42, 7, 6)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_15_16,
            16,
            true,
            AppDatabase.MIGRATION_15_16,
        ).use { database ->
            listOf(
                "conversations",
                "messages",
                "outbox_commands",
                "attachment_transfers",
                "turn_blocks",
                "media_attachments",
                "message_attachments",
                "conversation_read_states",
                "composer_drafts",
                "pending_message_notifications",
                "pending_turn_stops",
                "message_content_transfers",
            ).forEach { table ->
                database.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("$table must be rebuilt", 0, cursor.getInt(0))
                }
            }
            database.query("SELECT keyAlias FROM server_profiles").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("alias", cursor.getString(0))
            }
            database.query(
                "SELECT lastAcknowledgedEventSeq, connectionEpoch FROM realtime_cursors",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(42, cursor.getLong(0))
                assertEquals(7, cursor.getLong(1))
            }
        }
    }

    @Test
    fun migrate16To17KeepsNotificationsReadyAndAddsNullableHeadSequence() {
        helper.createDatabase(DATABASE_16_17, 16).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL(
                "INSERT INTO pending_message_notifications VALUES(" +
                    "'message-1', 'server', 'akashic:test', '旧通知', 1, 'normal', 10)",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_16_17,
            17,
            true,
            AppDatabase.MIGRATION_16_17,
        ).use { database ->
            database.query(
                "SELECT content, ready, headSeq FROM pending_message_notifications " +
                    "WHERE messageId = 'message-1'",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "迁移丢失待通知消息" }
                assertEquals("旧通知", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals(true, cursor.isNull(2))
            }
        }
    }

    @Test
    fun migrate17To18UnifiesPendingInputIdentityAndSettlesCompletedInput() {
        val firstId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val completeId = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        val restoringId = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        helper.createDatabase(DATABASE_17_18, 17).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '迁移会话', 2, 1)")
            insertV17Message("user:$firstId", firstId, "pending", "本地问题", null, "{}", 10)
            insertV17Message("reply", null, "complete", "引用", 3, "{}", 11, replyTo = "user:$firstId")
            execSQL("INSERT INTO conversation_read_states VALUES('akashic:test', 0, 'user:$firstId', 24, 12)")
            execSQL("INSERT INTO composer_drafts VALUES('akashic:test', 'server', '继续', 'user:$firstId', 13)")
            insertV17Outbox(firstId, "pending")

            insertV17Message("user:$completeId", completeId, "outcome_unknown", "本地旧正文", null, "{}", 20)
            insertV17Message(
                completeId,
                null,
                "complete",
                "远端正文",
                7,
                "{\"kind\":\"input\",\"parts\":[{\"kind\":\"text\",\"value\":\"远端正文\"}]}",
                21,
                recordedAt = "2026-09-08T08:00:00Z",
            )
            insertV17Outbox(completeId, "outcome_unknown")

            insertV17Message("user:$restoringId", restoringId, "outcome_unknown", "长问题", null, "{}", 30)
            insertV17Message(restoringId, null, "restoring", "", 8, "{}", 31, role = "restoring")
            insertV17Outbox(restoringId, "outcome_unknown")
            execSQL(
                "INSERT INTO message_content_transfers VALUES(" +
                    "'$restoringId', 'server', 'akashic:test', 8, 4096, '${"c".repeat(64)}', " +
                    "1024, 'downloading', 0, 32)",
            )

            listOf("local-a", "local-b", "remote-b", "local-c").forEachIndexed { index, attachmentId ->
                execSQL(
                    "INSERT INTO media_attachments VALUES(" +
                        "'$attachmentId', 'server', 'akashic:test', '$attachmentId.png', 'image/png', " +
                        "3, '${"a".repeat(64)}', 3, 'cached', 'cache/$attachmentId', 1, 1)",
                )
                execSQL(
                    "INSERT INTO attachment_transfers VALUES(" +
                        "'$attachmentId', 'server', 'akashic:test', '$attachmentId.png', 'image/png', " +
                        "3, '${"a".repeat(64)}', 3, '${if (attachmentId in setOf("local-b", "local-c")) "ready" else "sent"}', 1)",
                )
                val owner = when (index) {
                    0 -> "user:$firstId"
                    1 -> "user:$completeId"
                    2 -> completeId
                    else -> "user:$restoringId"
                }
                execSQL("INSERT INTO message_attachments VALUES('$owner', '$attachmentId', 0)")
            }
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_17_18,
            18,
            true,
            AppDatabase.MIGRATION_17_18,
        ).use { database ->
            database.query("SELECT text, deliveryState, clientMessageId FROM messages WHERE messageId = '$firstId'").use {
                check(it.moveToFirst())
                assertEquals("本地问题", it.getString(0))
                assertEquals("pending", it.getString(1))
                assertEquals(true, it.isNull(2))
            }
            database.query("SELECT anchorMessageId FROM conversation_read_states").use {
                check(it.moveToFirst()); assertEquals(firstId, it.getString(0))
            }
            database.query("SELECT replyToMessageId FROM composer_drafts").use {
                check(it.moveToFirst()); assertEquals(firstId, it.getString(0))
            }
            database.query("SELECT replyToMessageId FROM messages WHERE messageId = 'reply'").use {
                check(it.moveToFirst()); assertEquals(firstId, it.getString(0))
            }
            database.query("SELECT messageId FROM message_attachments WHERE attachmentId = 'local-a'").use {
                check(it.moveToFirst()); assertEquals(firstId, it.getString(0))
            }
            database.query("SELECT state FROM outbox_commands WHERE commandId = '$firstId'").use {
                check(it.moveToFirst()); assertEquals("pending", it.getString(0))
            }

            database.query("SELECT text, bodyJson FROM messages WHERE messageId = '$completeId'").use {
                check(it.moveToFirst())
                assertEquals("远端正文", it.getString(0))
                assertEquals(true, it.getString(1).contains("远端正文"))
            }
            database.query("SELECT COUNT(*) FROM outbox_commands WHERE commandId = '$completeId'").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            database.query("SELECT state FROM attachment_transfers WHERE attachmentId = 'local-b'").use {
                check(it.moveToFirst()); assertEquals("sent", it.getString(0))
            }
            database.query("SELECT attachmentId FROM message_attachments WHERE messageId = '$completeId'").use {
                check(it.moveToFirst()); assertEquals("remote-b", it.getString(0))
            }

            database.query(
                "SELECT text, deliveryState, serverSeq FROM messages WHERE messageId = '$restoringId'",
            ).use {
                check(it.moveToFirst())
                assertEquals("长问题", it.getString(0))
                assertEquals("restoring", it.getString(1))
                assertEquals(8, it.getLong(2))
            }
            database.query(
                "SELECT transferredBytes, state FROM message_content_transfers WHERE messageId = '$restoringId'",
            ).use {
                check(it.moveToFirst())
                assertEquals(1024, it.getLong(0))
                assertEquals("downloading", it.getString(1))
            }
            database.query("SELECT state, lastAttemptAt FROM outbox_commands WHERE commandId = '$restoringId'").use {
                check(it.moveToFirst())
                assertEquals("retry", it.getString(0))
                assertEquals(true, it.isNull(1))
            }
            database.query("SELECT state FROM attachment_transfers WHERE attachmentId = 'local-c'").use {
                check(it.moveToFirst()); assertEquals("sending", it.getString(0))
            }
            database.query("SELECT COUNT(*) FROM messages WHERE messageId LIKE 'user:%'").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM messages WHERE clientMessageId IS NOT NULL").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun migrate17To18MovesRetriedInputToLatestClientMessageId() {
        val firstId = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
        val retryId = "01ARZ3NDEKTSV4RRFFQ69G5FB1"
        val sourceId = "user:$firstId"
        helper.createDatabase(DATABASE_17_18_RETRIED, 17).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '迁移会话', 2, 1)")
            insertV17Message(sourceId, retryId, "failed_retryable", "重试问题", null, "{}", 10)
            insertV17Message("reply", null, "complete", "引用", 3, "{}", 11, replyTo = sourceId)
            execSQL("INSERT INTO conversation_read_states VALUES('akashic:test', 0, '$sourceId', 24, 12)")
            execSQL("INSERT INTO composer_drafts VALUES('akashic:test', 'server', '继续', '$sourceId', 13)")
            insertV17Outbox(retryId, "failed_retryable")
            execSQL(
                "INSERT INTO media_attachments VALUES(" +
                    "'retry-media', 'server', 'akashic:test', 'retry.png', 'image/png', " +
                    "3, '${"a".repeat(64)}', 3, 'cached', 'cache/retry-media', 1, 1)",
            )
            execSQL("INSERT INTO message_attachments VALUES('$sourceId', 'retry-media', 0)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_17_18_RETRIED,
            18,
            true,
            AppDatabase.MIGRATION_17_18,
        ).use { database ->
            database.query(
                "SELECT messageId, clientMessageId, text, deliveryState FROM messages " +
                    "WHERE text = '重试问题'",
            ).use {
                check(it.moveToFirst())
                assertEquals(retryId, it.getString(0))
                assertEquals(true, it.isNull(1))
                assertEquals("重试问题", it.getString(2))
                assertEquals("failed_retryable", it.getString(3))
            }
            database.query("SELECT anchorMessageId FROM conversation_read_states").use {
                check(it.moveToFirst()); assertEquals(retryId, it.getString(0))
            }
            database.query("SELECT replyToMessageId FROM composer_drafts").use {
                check(it.moveToFirst()); assertEquals(retryId, it.getString(0))
            }
            database.query("SELECT replyToMessageId FROM messages WHERE messageId = 'reply'").use {
                check(it.moveToFirst()); assertEquals(retryId, it.getString(0))
            }
            database.query("SELECT messageId FROM message_attachments WHERE attachmentId = 'retry-media'").use {
                check(it.moveToFirst()); assertEquals(retryId, it.getString(0))
            }
            database.query("SELECT commandId, state FROM outbox_commands").use {
                check(it.moveToFirst())
                assertEquals(retryId, it.getString(0))
                assertEquals("failed_retryable", it.getString(1))
                assertEquals(false, it.moveToNext())
            }
            database.query("SELECT COUNT(*) FROM messages WHERE messageId = '$sourceId'").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun migrate17To18KeepsLegacyCanonicalInputIdentityAndSettlesOutbox() {
        val clientId = "01ARZ3NDEKTSV4RRFFQ69G5FB2"
        val unacknowledgedClientId = "01ARZ3NDEKTSV4RRFFQ69G5FB3"
        val sessionId = "akashic:00000000000070008000000000000001"
        val canonicalId = "$sessionId:2"
        val unacknowledgedCanonicalId = "$sessionId:3"
        helper.createDatabase(DATABASE_17_18_CANONICAL, 17).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('$sessionId', 'server', '迁移会话', 2, 1)")
            insertV17Message(
                canonicalId,
                clientId,
                "sent",
                "已接纳问题",
                null,
                "{}",
                10,
                sessionId = sessionId,
            )
            insertV17Message(
                "reply",
                null,
                "complete",
                "引用",
                3,
                "{}",
                11,
                replyTo = canonicalId,
                sessionId = sessionId,
            )
            insertV17Message(
                unacknowledgedCanonicalId,
                unacknowledgedClientId,
                "outcome_unknown",
                "ACK 前已持久化",
                null,
                "{}",
                12,
                sessionId = sessionId,
            )
            insertV17Outbox(unacknowledgedClientId, "outcome_unknown")
            execSQL(
                "INSERT INTO media_attachments VALUES(" +
                    "'canonical-media', 'server', '$sessionId', 'canonical.png', 'image/png', " +
                    "3, '${"a".repeat(64)}', 3, 'cached', 'cache/canonical-media', 1, 1)",
            )
            execSQL(
                "INSERT INTO attachment_transfers VALUES(" +
                    "'canonical-media', 'server', '$sessionId', 'canonical.png', 'image/png', " +
                    "3, '${"a".repeat(64)}', 3, 'ready', 1)",
            )
            execSQL("INSERT INTO message_attachments VALUES('$unacknowledgedCanonicalId', 'canonical-media', 0)")
            execSQL("INSERT INTO conversation_read_states VALUES('$sessionId', 0, '$canonicalId', 24, 12)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_17_18_CANONICAL,
            18,
            true,
            AppDatabase.MIGRATION_17_18,
        ).use { database ->
            database.query(
                "SELECT messageId, clientMessageId, text, deliveryState, serverSeq, bodyJson " +
                    "FROM messages WHERE messageId = '$canonicalId'",
            ).use {
                check(it.moveToFirst())
                assertEquals(canonicalId, it.getString(0))
                assertEquals(true, it.isNull(1))
                assertEquals("已接纳问题", it.getString(2))
                assertEquals("sent", it.getString(3))
                assertEquals(true, it.isNull(4))
                assertEquals("{}", it.getString(5))
            }
            database.query("SELECT anchorMessageId FROM conversation_read_states").use {
                check(it.moveToFirst()); assertEquals(canonicalId, it.getString(0))
            }
            database.query("SELECT replyToMessageId FROM messages WHERE messageId = 'reply'").use {
                check(it.moveToFirst()); assertEquals(canonicalId, it.getString(0))
            }
            database.query("SELECT COUNT(*) FROM outbox_commands").use {
                check(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            database.query(
                "SELECT messageId, clientMessageId, deliveryState FROM messages " +
                    "WHERE messageId = '$unacknowledgedCanonicalId'",
            ).use {
                check(it.moveToFirst())
                assertEquals(unacknowledgedCanonicalId, it.getString(0))
                assertEquals(true, it.isNull(1))
                assertEquals("sent", it.getString(2))
            }
            database.query("SELECT state FROM attachment_transfers WHERE attachmentId = 'canonical-media'").use {
                check(it.moveToFirst()); assertEquals("sent", it.getString(0))
            }
        }
    }

    @Test
    fun migrate17To18RejectsUnknownPendingIdentityShape() {
        helper.createDatabase(DATABASE_17_18_INVALID, 17).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '迁移会话', 2, 1)")
            insertV17Message(
                "unknown-local-id",
                "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "failed_retryable",
                "不能猜测身份",
                null,
                "{}",
                3,
            )
            close()
        }

        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(
                DATABASE_17_18_INVALID,
                18,
                true,
                AppDatabase.MIGRATION_17_18,
            ).close()
        }
    }

    @Test
    fun migrate19To20KeepsPartialDownloadsAndDoesNotInventCoverage() = kotlinx.coroutines.runBlocking<Unit> {
        val name = "migration-19-20"
        helper.createDatabase(name, 19).apply {
            execSQL("INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)")
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '保留', 2, 1)")
            insertV17Message("old", null, "complete", "旧正文", 4205,
                """{"kind":"input","parts":[{"kind":"text","value":"旧正文"}]}""", 3,
                recordedAt = "2026-09-09T00:00:00Z")
            insertV17Message("download", null, "restoring", "", null, "{}", 4)
            execSQL("INSERT INTO message_content_transfers VALUES('download', 'server', 'akashic:test', 4204, 100000, ?, 4096, 'downloading', 1, 4)", arrayOf("a".repeat(64)))
            execSQL("INSERT INTO outbox_commands VALUES('pending', 'server', '{}', 'pending', 0, 1, NULL)")
            execSQL("INSERT INTO composer_drafts VALUES('akashic:test', 'server', '保留草稿', NULL, 4)")
            close()
        }
        helper.runMigrationsAndValidate(name, 20, true, AppDatabase.MIGRATION_19_20).use { db ->
            db.query("SELECT transferredBytes, state, displayOnly FROM message_content_transfers").use {
                check(it.moveToFirst()); assertEquals(4096L, it.getLong(0)); assertEquals("downloading", it.getString(1)); assertEquals(0, it.getInt(2))
            }
            db.query("SELECT COUNT(*) FROM message_ranges").use { check(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM messages").use { check(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            db.query("SELECT text FROM composer_drafts").use { check(it.moveToFirst()); assertEquals("保留草稿", it.getString(0)) }
            db.query("SELECT state FROM outbox_commands").use { check(it.moveToFirst()); assertEquals("pending", it.getString(0)) }
            db.execSQL("INSERT INTO message_ranges VALUES('akashic:test', 4200, 4205)")
            db.query("PRAGMA foreign_key_check").use { assertEquals(false, it.moveToFirst()) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reopened = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            assertEquals(listOf(MessageRangeEntity("akashic:test", 4200, 4205)), reopened.messages().receivedRanges("akashic:test"))
            assertEquals(4096L, reopened.messageContentTransfers().get("download")?.transferredBytes)
            assertEquals("旧正文", reopened.messages().get("old")?.text)
        } finally { reopened.close() }
    }

    @Test
    fun migrate18To19KeepsArtifactBytesAndEndsOnlyDefiniteFailures() = kotlinx.coroutines.runBlocking<Unit> {
        val artifact = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val failed = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        val unknown = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).joinToString("") { "%02x".format(it) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheRoot = context.cacheDir.resolve("room19-cache").apply { mkdirs() }
        val oldFile = cacheRoot.resolve("b".repeat(64) + ".bin").apply { writeBytes(byteArrayOf()) }
        val oldPath = oldFile.absolutePath
        val refs = """[{"artifact_id":"$artifact","kind":"file","filename":null,"media_type":null,"size_bytes":0,"sha256":"$digest"}]"""
        helper.createDatabase(DATABASE_18_19, 18).apply {
            execSQL("INSERT INTO server_profiles VALUES('server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)")
            execSQL("INSERT INTO conversations VALUES('akashic:test', 'server', '旧会话', 2, 1)")
            execSQL("INSERT INTO conversations VALUES('akashic:other', 'server', '另一会话', 2, 1)")
            for ((id, sid) in listOf("old-message" to "akashic:test", "other-message" to "akashic:other")) {
                insertV17Message(id, null, "complete", "旧正文", 0,
                    """{"kind":"input","parts":[{"kind":"artifact_ref","value":"$artifact"}]}""", 3,
                    recordedAt = "2026-09-08T00:00:00Z", sessionId = sid)
                execSQL("UPDATE messages SET attachmentsJson = ? WHERE messageId = ?", arrayOf(refs, id))
            }
            execSQL("INSERT INTO media_attachments VALUES(?, 'server', 'akashic:test', ?, 'application/octet-stream', 0, ?, 0, 'cached', ?, 3, 3)",
                arrayOf(artifact, artifact, digest, oldPath))
            execSQL("INSERT INTO message_attachments VALUES('old-message', ?, 0)", arrayOf(artifact))
            execSQL("INSERT INTO message_attachments VALUES('other-message', ?, 0)", arrayOf(artifact))
            for ((id, state) in listOf(failed to "failed_retryable", unknown to "outcome_unknown")) {
                insertV17Message(id, null, state, "保留失败正文", null, "{}", 4)
                val envelope = """{"v":1,"kind":"command","type":"message.send","id":"$id","connection_epoch":1,"session_id":"akashic:test","payload":{"message_log_version":2,"client_message_id":"$id","session_id":"akashic:test","text":"保留失败正文","media_refs":[],"client_created_at":"2026-09-08T00:00:00Z"}}"""
                execSQL("INSERT INTO outbox_commands VALUES(?, 'server', ?, ?, 1, 1, 1)", arrayOf(id, envelope, state))
            }
            close()
        }
        helper.runMigrationsAndValidate(DATABASE_18_19, 19, true, AppDatabase.MIGRATION_18_19).use { db ->
            db.query("SELECT attachmentId, artifactId, filename, contentType, cachePath FROM media_attachments").use {
                check(it.moveToFirst())
                assertEquals(artifactCacheId("server", artifact), it.getString(0))
                assertEquals(artifact, it.getString(1))
                assertEquals(true, it.isNull(2))
                assertEquals(true, it.isNull(3))
                assertEquals(oldPath, it.getString(4))
            }
            db.query("SELECT COUNT(*) FROM messages").use { check(it.moveToFirst()); assertEquals(4, it.getInt(0)) }
            db.query("SELECT commandId, state FROM outbox_commands").use {
                check(it.moveToFirst()); assertEquals(unknown, it.getString(0)); assertEquals("outcome_unknown", it.getString(1)); assertEquals(false, it.moveToNext())
            }
            db.query("SELECT deliveryState, text FROM messages WHERE messageId = ?", arrayOf(failed)).use {
                check(it.moveToFirst()); assertEquals("failed", it.getString(0)); assertEquals("保留失败正文", it.getString(1))
            }
            db.query("PRAGMA foreign_key_check").use { assertEquals(false, it.moveToFirst()) }
        }
        val reopened = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_18_19).addMigrations(AppDatabase.MIGRATION_19_20).build()
        try {
            assertEquals(1, reopened.conversations().delete("server", "akashic:test"))
            assertEquals(1, reopened.mediaAttachments().all().size)
            reopened.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(false, it.moveToFirst()) }
            MediaCacheStore(cacheRoot, reopened.mediaAttachments()).reconcile()
            assertEquals("cached", reopened.mediaAttachments().get(artifactCacheId("server", artifact))?.state)
            assertEquals(true, oldFile.isFile)
            assertEquals(0L, oldFile.length())
        } finally {
            reopened.close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV17Message(
        messageId: String,
        clientMessageId: String?,
        deliveryState: String,
        text: String,
        serverSeq: Long?,
        bodyJson: String,
        updatedAt: Long,
        role: String = "user",
        recordedAt: String = "",
        replyTo: String? = null,
        sessionId: String = "akashic:test",
    ) {
        execSQL(
            """
            INSERT INTO messages(
              messageId, clientMessageId, sessionId, role, text, deliveryState, createdAt, updatedAt,
              serverSeq, replyToMessageId, replyRole, replyPreview, turnClientMessageId, controlTurnId,
              recordedAt, author, source, bodyJson, metadataJson, attachmentsJson
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?, '{}', '[]')
            """.trimIndent(),
            arrayOf<Any?>(
                messageId,
                clientMessageId,
                sessionId,
                role,
                text,
                deliveryState,
                updatedAt,
                updatedAt,
                serverSeq,
                replyTo,
                recordedAt,
                if (recordedAt.isEmpty()) "" else "user",
                if (recordedAt.isEmpty()) "" else "mobile",
                bodyJson,
            ),
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV17Outbox(commandId: String, state: String) {
        execSQL(
            "INSERT INTO outbox_commands VALUES(?, 'server', '{}', ?, 1, 1, 1)",
            arrayOf(commandId, state),
        )
    }

    private companion object {
        const val DATABASE_1_2 = "migration-1-2"
        const val DATABASE_2_3 = "migration-2-3"
        const val DATABASE_3_4 = "migration-3-4"
        const val DATABASE_4_5 = "migration-4-5"
        const val DATABASE_5_6 = "migration-5-6"
        const val DATABASE_6_7 = "migration-6-7"
        const val DATABASE_7_8 = "migration-7-8"
        const val DATABASE_8_9 = "migration-8-9"
        const val DATABASE_9_10 = "migration-9-10"
        const val DATABASE_10_11 = "migration-10-11"
        const val DATABASE_11_12 = "migration-11-12"
        const val DATABASE_12_13 = "migration-12-13"
        const val DATABASE_13_14 = "migration-13-14"
        const val DATABASE_14_15 = "migration-14-15"
        const val DATABASE_15_16 = "migration-15-16"
        const val DATABASE_16_17 = "migration-16-17"
        const val DATABASE_17_18 = "migration-17-18"
        const val DATABASE_17_18_RETRIED = "migration-17-18-retried"
        const val DATABASE_17_18_CANONICAL = "migration-17-18-canonical"
        const val DATABASE_18_19 = "migration-18-19"
        const val DATABASE_17_18_INVALID = "migration-17-18-invalid"
    }
}
