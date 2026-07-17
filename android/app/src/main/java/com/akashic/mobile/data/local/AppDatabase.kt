package com.akashic.mobile.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ServerProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        TurnBlockEntity::class,
        OutboxCommandEntity::class,
        AttachmentTransferEntity::class,
        RealtimeCursorEntity::class,
        MediaAttachmentEntity::class,
        MessageAttachmentEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfiles(): ServerProfileDao

    abstract fun conversations(): ConversationDao

    abstract fun messages(): MessageDao

    abstract fun outbox(): OutboxDao

    abstract fun attachmentTransfers(): AttachmentTransferDao

    abstract fun realtimeCursors(): RealtimeCursorDao

    abstract fun mediaAttachments(): MediaAttachmentDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "akashic-mobile.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `remoteState` " +
                        "TEXT NOT NULL DEFAULT 'unknown'",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 补齐规范 v2 缺少的媒体缓存表
                val hasMediaAttachments = db.hasTable("media_attachments")
                val hasMessageAttachments = db.hasTable("message_attachments")
                check(hasMediaAttachments == hasMessageAttachments) {
                    "Version 2 media schema is incomplete"
                }
                if (!hasMediaAttachments) {
                    createMediaTables(db)
                }

                // 2. 补齐旧 PR4 v2 缺少的会话归属列
                if (!db.hasColumn("conversations", "remoteState")) {
                    db.execSQL(
                        "ALTER TABLE `conversations` ADD COLUMN `remoteState` " +
                            "TEXT NOT NULL DEFAULT 'unknown'",
                    )
                }
            }
        }

        private fun createMediaTables(db: SupportSQLiteDatabase) {
            db.execSQL(
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
            db.execSQL("CREATE INDEX `index_media_attachments_serverId` ON `media_attachments` (`serverId`)")
            db.execSQL("CREATE INDEX `index_media_attachments_sessionId` ON `media_attachments` (`sessionId`)")
            db.execSQL("CREATE INDEX `index_media_attachments_state` ON `media_attachments` (`state`)")
            db.execSQL(
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
            db.execSQL(
                "CREATE INDEX `index_message_attachments_attachmentId` " +
                    "ON `message_attachments` (`attachmentId`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX `index_message_attachments_messageId_ordinal` " +
                    "ON `message_attachments` (`messageId`, `ordinal`)",
            )
        }

        private fun SupportSQLiteDatabase.hasTable(tableName: String): Boolean = query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean =
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                    .any { it == columnName }
            }
    }
}
