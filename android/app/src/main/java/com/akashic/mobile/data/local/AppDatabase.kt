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
        PendingTurnStopEntity::class,
    ],
    version = 5,
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

    abstract fun pendingTurnStops(): PendingTurnStopDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "akashic-mobile.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addConversationRemoteState(db)
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
                    addConversationRemoteState(db)
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. v3 必须完整拥有 PR4 的媒体投影
                check(
                    db.hasTable("media_attachments") && db.hasTable("message_attachments"),
                ) { "Version 3 media schema is incomplete" }

                // 2. 规范 PR4 与公开 PR6 v3 在这一层获得 PR5 持久状态
                if (db.hasTable("pending_message_notifications")) {
                    db.requireNotificationIndexes(3)
                } else {
                    createPendingNotificationTable(db)
                }
                if (db.hasTable("pending_turn_stops")) {
                    db.requireTurnStopIndexes(3)
                } else {
                    createPendingTurnStopTable(db)
                }
                if (!db.hasColumn("conversations", "remoteState")) {
                    addConversationRemoteState(db)
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 两种已发布 v4 都必须完整保有 media 与通知队列
                check(
                    db.hasTable("media_attachments") && db.hasTable("message_attachments"),
                ) { "Version 4 media schema is incomplete" }
                check(db.hasTable("pending_message_notifications")) {
                    "Version 4 notification schema is missing"
                }
                db.requireNotificationIndexes(4)

                // 2. 旧 PR6 v4 补 PR5 状态，最终 PR5 v4 保留既有数据
                if (db.hasTable("pending_turn_stops")) {
                    db.requireTurnStopIndexes(4)
                } else {
                    createPendingTurnStopTable(db)
                }
                if (!db.hasColumn("conversations", "remoteState")) {
                    addConversationRemoteState(db)
                }

                // 3. 最终 PR5 v4 补稳定服务端顺序，旧 PR6 原值保持不动
                if (!db.hasColumn("messages", "serverSeq")) {
                    db.execSQL("ALTER TABLE `messages` ADD COLUMN `serverSeq` INTEGER")
                }
            }
        }

        private fun addConversationRemoteState(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `conversations` ADD COLUMN `remoteState` " +
                    "TEXT NOT NULL DEFAULT 'unknown'",
            )
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

        private fun createPendingNotificationTable(db: SupportSQLiteDatabase) {
            db.execSQL(
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
            db.execSQL(
                "CREATE INDEX `index_pending_message_notifications_serverId` " +
                    "ON `pending_message_notifications` (`serverId`)",
            )
            db.execSQL(
                "CREATE INDEX `index_pending_message_notifications_createdAt` " +
                    "ON `pending_message_notifications` (`createdAt`)",
            )
        }

        private fun createPendingTurnStopTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE `pending_turn_stops` (
                    `commandId` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `turnId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`commandId`),
                    FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_pending_turn_stops_serverId` ON `pending_turn_stops` (`serverId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX `index_pending_turn_stops_serverId_sessionId` " +
                    "ON `pending_turn_stops` (`serverId`, `sessionId`)",
            )
            db.execSQL("CREATE INDEX `index_pending_turn_stops_createdAt` ON `pending_turn_stops` (`createdAt`)")
        }

        private fun SupportSQLiteDatabase.requireNotificationIndexes(version: Int) {
            check(
                hasIndex("index_pending_message_notifications_serverId") &&
                    hasIndex("index_pending_message_notifications_createdAt"),
            ) { "Version $version notification schema is incomplete" }
        }

        private fun SupportSQLiteDatabase.requireTurnStopIndexes(version: Int) {
            check(
                hasIndex("index_pending_turn_stops_serverId") &&
                    hasIndex("index_pending_turn_stops_serverId_sessionId") &&
                    hasIndex("index_pending_turn_stops_createdAt"),
            ) { "Version $version turn stop schema is incomplete" }
        }

        private fun SupportSQLiteDatabase.hasTable(tableName: String): Boolean = query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

        private fun SupportSQLiteDatabase.hasIndex(indexName: String): Boolean = query(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(indexName),
        ).use { cursor -> cursor.moveToFirst() }

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean =
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
                    .any { it == columnName }
            }
    }
}
