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
        PendingMessageNotificationEntity::class,
    ],
    version = 4,
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

    abstract fun pendingMessageNotifications(): PendingMessageNotificationDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "akashic-mobile.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_attachments` (
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_attachments_serverId` ON `media_attachments` (`serverId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_attachments_sessionId` ON `media_attachments` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_attachments_state` ON `media_attachments` (`state`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_attachments` (
                        `messageId` TEXT NOT NULL,
                        `attachmentId` TEXT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`, `attachmentId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`attachmentId`) REFERENCES `media_attachments`(`attachmentId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_attachments_attachmentId` ON `message_attachments` (`attachmentId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_messageId_ordinal` ON `message_attachments` (`messageId`, `ordinal`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createPendingMessageNotifications(db)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 兼容已安装 Draft PR6：旧 v3 已有 serverSeq，但还没有通知待办
                if (!hasColumn(db, "messages", "serverSeq")) {
                    db.execSQL("ALTER TABLE `messages` ADD COLUMN `serverSeq` INTEGER")
                }

                // 2. 新 stacked 路径的 v3 已有通知待办；旧 v3 在这里补齐
                if (!hasTable(db, "pending_message_notifications")) {
                    createPendingMessageNotifications(db)
                }
            }
        }

        private fun createPendingMessageNotifications(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pending_message_notifications` (
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
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_message_notifications_serverId` ON `pending_message_notifications` (`serverId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pending_message_notifications_createdAt` ON `pending_message_notifications` (`createdAt`)",
            )
        }

        private fun hasTable(db: SupportSQLiteDatabase, table: String): Boolean =
            db.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { it.moveToFirst() }

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                    .any { it == column }
            }
    }
}
