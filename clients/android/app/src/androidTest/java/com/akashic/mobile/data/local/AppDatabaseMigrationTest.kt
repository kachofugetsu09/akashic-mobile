package com.akashic.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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
    }
}
