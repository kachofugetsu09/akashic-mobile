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
    fun migrateCanonical1To3AddsOwnershipAndMediaWithoutLosingMessages() {
        helper.createDatabase(CANONICAL_DATABASE_NAME, 1).apply {
            insertBaseRows()
            close()
        }

        helper.runMigrationsAndValidate(
            CANONICAL_DATABASE_NAME,
            3,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
        ).use { database ->
            assertSingleString(database, "SELECT text FROM messages", "旧消息")
            assertSingleString(
                database,
                "SELECT remoteState FROM conversations",
                ConversationRemoteState.UNKNOWN,
            )
            assertSingleInt(database, "SELECT COUNT(*) FROM media_attachments", 0)
        }
    }

    @Test
    fun migratePublishedPr4Version2AddsOwnershipWithoutLosingMedia() {
        helper.createDatabase(LEGACY_DATABASE_NAME, 1).apply {
            insertBaseRows()
            createPublishedPr4MediaTables()
            execSQL(
                "INSERT INTO media_attachments VALUES(" +
                    "'attachment', 'server', 'mobile:test', 'old.txt', 'text/plain', " +
                    "3, 'sha', 3, 'available', '/cache/old.txt', 4, 4)",
            )
            execSQL("INSERT INTO message_attachments VALUES('old-message', 'attachment', 0)")
            execSQL("PRAGMA user_version = 2")
            close()
        }

        helper.runMigrationsAndValidate(
            LEGACY_DATABASE_NAME,
            3,
            true,
            AppDatabase.MIGRATION_2_3,
        ).use { database ->
            assertSingleString(
                database,
                "SELECT remoteState FROM conversations",
                ConversationRemoteState.UNKNOWN,
            )
            assertSingleString(database, "SELECT filename FROM media_attachments", "old.txt")
            assertSingleString(database, "SELECT attachmentId FROM message_attachments", "attachment")
        }
    }

    @Test
    fun migratePartialVersion2FailsLoudly() {
        helper.createDatabase(PARTIAL_DATABASE_NAME, 1).apply {
            createPublishedPr4MediaAttachmentTable()
            execSQL("PRAGMA user_version = 2")
            close()
        }

        assertThrows(IllegalStateException::class.java) {
            helper.runMigrationsAndValidate(
                PARTIAL_DATABASE_NAME,
                3,
                true,
                AppDatabase.MIGRATION_2_3,
            )
        }
    }

    private fun SupportSQLiteDatabase.insertBaseRows() {
        execSQL(
            "INSERT INTO server_profiles VALUES(" +
                "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
        )
        execSQL("INSERT INTO conversations VALUES('mobile:test', 'server', '旧会话', 2)")
        execSQL(
            "INSERT INTO messages VALUES(" +
                "'old-message', NULL, 'mobile:test', 'assistant', '旧消息', 'complete', 3, 3)",
        )
    }

    private fun SupportSQLiteDatabase.createPublishedPr4MediaTables() {
        createPublishedPr4MediaAttachmentTable()
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

    private fun SupportSQLiteDatabase.createPublishedPr4MediaAttachmentTable() {
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
        const val CANONICAL_DATABASE_NAME = "migration-canonical-1-3"
        const val LEGACY_DATABASE_NAME = "migration-published-pr4-2-3"
        const val PARTIAL_DATABASE_NAME = "migration-partial-2-3"
    }
}
