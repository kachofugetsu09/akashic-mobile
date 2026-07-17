package com.akashic.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test
    fun migrateCanonical1To4BuildsTheLayeredSchemaWithoutLosingMessages() {
        helper.createDatabase(CANONICAL_DATABASE_NAME, 1).apply {
            insertVersion1BaseRows()
            close()
        }

        helper.runMigrationsAndValidate(
            CANONICAL_DATABASE_NAME,
            4,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        ).use { database ->
            assertSingleString(database, "SELECT text FROM messages", "旧消息")
            assertSingleString(
                database,
                "SELECT remoteState FROM conversations",
                ConversationRemoteState.UNKNOWN,
            )
            assertSingleInt(database, "SELECT COUNT(*) FROM media_attachments", 0)
            assertSingleInt(database, "SELECT COUNT(*) FROM pending_message_notifications", 0)
            assertSingleInt(database, "SELECT COUNT(*) FROM pending_turn_stops", 0)
        }
    }

    @Test
    fun migratePublishedPr4Version2To4PreservesMediaAndAddsOwnershipAndNotifications() {
        helper.createDatabase(PUBLISHED_PR4_DATABASE_NAME, 1).apply {
            insertVersion1BaseRows()
            createMediaTables()
            insertMediaRows()
            execSQL("PRAGMA user_version = 2")
            close()
        }

        helper.runMigrationsAndValidate(
            PUBLISHED_PR4_DATABASE_NAME,
            4,
            true,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
        ).use { database ->
            assertSingleString(database, "SELECT filename FROM media_attachments", "old.txt")
            assertSingleString(
                database,
                "SELECT remoteState FROM conversations",
                ConversationRemoteState.UNKNOWN,
            )
            assertSingleInt(database, "SELECT COUNT(*) FROM pending_message_notifications", 0)
            assertSingleInt(database, "SELECT COUNT(*) FROM pending_turn_stops", 0)
        }
    }

    @Test
    fun migrateFinalPr4Version3PreservesOwnershipAndMediaAndAddsNotifications() {
        helper.createDatabase(FINAL_PR4_DATABASE_NAME, 3).apply {
            insertVersion3BaseRows()
            insertMediaRows()
            close()
        }

        helper.runMigrationsAndValidate(
            FINAL_PR4_DATABASE_NAME,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        ).use { database ->
            assertSingleString(database, "SELECT remoteState FROM conversations", ConversationRemoteState.REMOTE)
            assertSingleString(database, "SELECT filename FROM media_attachments", "old.txt")
            database.execSQL(
                "INSERT INTO pending_message_notifications VALUES(" +
                    "'notice', 'server', 'mobile:test', '完成', 1, 5)",
            )
            database.execSQL(
                "INSERT INTO pending_turn_stops VALUES(" +
                    "'stop', 'server', 'mobile:test', 'turn-1', 6)",
            )
            assertSingleString(database, "SELECT content FROM pending_message_notifications", "完成")
            assertSingleString(database, "SELECT turnId FROM pending_turn_stops", "turn-1")
        }
    }

    @Test
    fun migratePublishedPr5Version3PreservesNotificationAndMediaAndAddsOwnership() {
        helper.createDatabase(PUBLISHED_PR5_DATABASE_NAME, 1).apply {
            insertVersion1BaseRows()
            createMediaTables()
            createPendingNotificationTable(includeIndexes = true)
            insertMediaRows()
            execSQL(
                "INSERT INTO pending_message_notifications VALUES(" +
                    "'notice', 'server', 'mobile:test', '待展示', 1, 5)",
            )
            execSQL("PRAGMA user_version = 3")
            close()
        }

        helper.runMigrationsAndValidate(
            PUBLISHED_PR5_DATABASE_NAME,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        ).use { database ->
            assertSingleString(database, "SELECT content FROM pending_message_notifications", "待展示")
            assertSingleString(database, "SELECT filename FROM media_attachments", "old.txt")
            assertSingleString(
                database,
                "SELECT remoteState FROM conversations",
                ConversationRemoteState.UNKNOWN,
            )
            assertSingleInt(database, "SELECT COUNT(*) FROM pending_turn_stops", 0)
        }
    }

    @Test
    fun migratePartialVersion3FailsLoudly() {
        helper.createDatabase(PARTIAL_DATABASE_NAME, 1).apply {
            createMediaTables()
            createPendingNotificationTable(includeIndexes = false)
            execSQL("PRAGMA user_version = 3")
            close()
        }

        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(
                PARTIAL_DATABASE_NAME,
                4,
                true,
                AppDatabase.MIGRATION_3_4,
            )
        }
    }

    private fun SupportSQLiteDatabase.insertVersion1BaseRows() {
        execSQL(
            "INSERT INTO server_profiles VALUES(" +
                "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
        )
        execSQL("INSERT INTO conversations VALUES('mobile:test', 'server', '旧会话', 2)")
        insertMessage()
    }

    private fun SupportSQLiteDatabase.insertVersion3BaseRows() {
        execSQL(
            "INSERT INTO server_profiles VALUES(" +
                "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
        )
        execSQL("INSERT INTO conversations VALUES('mobile:test', 'server', '旧会话', 2, 'remote')")
        insertMessage()
    }

    private fun SupportSQLiteDatabase.insertMessage() {
        execSQL(
            "INSERT INTO messages VALUES(" +
                "'old-message', NULL, 'mobile:test', 'assistant', '旧消息', 'complete', 3, 3)",
        )
    }

    private fun SupportSQLiteDatabase.insertMediaRows() {
        execSQL(
            "INSERT INTO media_attachments VALUES(" +
                "'attachment', 'server', 'mobile:test', 'old.txt', 'text/plain', " +
                "3, 'sha', 3, 'available', '/cache/old.txt', 4, 4)",
        )
        execSQL("INSERT INTO message_attachments VALUES('old-message', 'attachment', 0)")
    }

    private fun SupportSQLiteDatabase.createMediaTables() {
        execSQL(
            """
            CREATE TABLE `media_attachments` (
                `attachmentId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `filename` TEXT NOT NULL,
                `contentType` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `sha256` TEXT NOT NULL,
                `transferredBytes` INTEGER NOT NULL,
                `state` TEXT NOT NULL,
                `cachePath` TEXT NOT NULL,
                `lastAccessedAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`attachmentId`),
                FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`sessionId`) REFERENCES `conversations`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execSQL("CREATE INDEX `index_media_attachments_serverId` ON `media_attachments` (`serverId`)")
        execSQL("CREATE INDEX `index_media_attachments_sessionId` ON `media_attachments` (`sessionId`)")
        execSQL("CREATE INDEX `index_media_attachments_state` ON `media_attachments` (`state`)")
        execSQL(
            """
            CREATE TABLE `message_attachments` (
                `messageId` TEXT NOT NULL,
                `attachmentId` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`, `attachmentId`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`attachmentId`) REFERENCES `media_attachments`(`attachmentId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        execSQL(
            "CREATE INDEX `index_message_attachments_attachmentId` " +
                "ON `message_attachments` (`attachmentId`)",
        )
        execSQL(
            "CREATE UNIQUE INDEX `index_message_attachments_messageId_ordinal` " +
                "ON `message_attachments` (`messageId`, `ordinal`)",
        )
    }

    private fun SupportSQLiteDatabase.createPendingNotificationTable(includeIndexes: Boolean) {
        execSQL(
            """
            CREATE TABLE `pending_message_notifications` (
                `messageId` TEXT NOT NULL,
                `serverId` TEXT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `hasAttachments` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`),
                FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        if (includeIndexes) {
            execSQL(
                "CREATE INDEX `index_pending_message_notifications_serverId` " +
                    "ON `pending_message_notifications` (`serverId`)",
            )
            execSQL(
                "CREATE INDEX `index_pending_message_notifications_createdAt` " +
                    "ON `pending_message_notifications` (`createdAt`)",
            )
        }
    }

    private fun assertSingleString(
        database: SupportSQLiteDatabase,
        query: String,
        expected: String,
    ) {
        database.query(query).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
        }
    }

    private fun assertSingleInt(
        database: SupportSQLiteDatabase,
        query: String,
        expected: Int,
    ) {
        database.query(query).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }

    private companion object {
        const val CANONICAL_DATABASE_NAME = "migration-canonical-1-4"
        const val PUBLISHED_PR4_DATABASE_NAME = "migration-published-pr4-2-4"
        const val FINAL_PR4_DATABASE_NAME = "migration-final-pr4-3-4"
        const val PUBLISHED_PR5_DATABASE_NAME = "migration-published-pr5-3-4"
        const val PARTIAL_DATABASE_NAME = "migration-partial-3-4"
    }
}
