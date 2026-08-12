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
        ConversationReadStateEntity::class,
        ComposerDraftEntity::class,
        PendingMessageNotificationEntity::class,
        PendingTurnStopEntity::class,
        MessageContentTransferEntity::class,
        MobileWebUiStateEntity::class,
        MobileWebUiGenerationEntity::class,
        MobileWebUiBlobEntity::class,
        MobileWebUiRejectEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverProfiles(): ServerProfileDao

    abstract fun conversations(): ConversationDao

    abstract fun conversationReadStates(): ConversationReadStateDao

    abstract fun composerDrafts(): ComposerDraftDao

    abstract fun messages(): MessageDao

    abstract fun outbox(): OutboxDao

    abstract fun attachmentTransfers(): AttachmentTransferDao

    abstract fun realtimeCursors(): RealtimeCursorDao

    abstract fun mediaAttachments(): MediaAttachmentDao

    abstract fun pendingMessageNotifications(): PendingMessageNotificationDao

    abstract fun pendingTurnStops(): PendingTurnStopDao

    abstract fun messageContentTransfers(): MessageContentTransferDao

    abstract fun mobileWebUi(): MobileWebUiDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "akashic-mobile.db",
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        ).build()

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
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `serverSeq` INTEGER")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyToMessageId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyRole` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `replyPreview` TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversation_read_states` (
                        `sessionId` TEXT NOT NULL,
                        `lastReadAt` INTEGER NOT NULL,
                        `anchorMessageId` TEXT,
                        `anchorOffsetPx` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`sessionId`) REFERENCES `conversations`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `remoteKnown` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    UPDATE conversations
                    SET remoteKnown = 1
                    WHERE EXISTS (
                        SELECT 1 FROM messages
                        WHERE messages.sessionId = conversations.sessionId
                          AND (
                              messages.serverSeq IS NOT NULL
                              OR (
                                  messages.role = 'assistant'
                                  AND messages.deliveryState IN ('streaming', 'complete', 'interrupted')
                              )
                              OR (
                                  messages.role = 'user'
                                  AND messages.deliveryState = 'sent'
                              )
                          )
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `composer_drafts` (
                        `sessionId` TEXT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `replyToMessageId` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sessionId`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`sessionId`) REFERENCES `conversations`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_composer_drafts_serverId` ON `composer_drafts` (`serverId`)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_message_notifications` (
                        `messageId` TEXT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `hasAttachments` INTEGER NOT NULL,
                        `attention` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE
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
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 重建通知待办，使其不再依赖可清理的消息投影
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_message_notifications_v9` (
                        `messageId` TEXT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `hasAttachments` INTEGER NOT NULL,
                        `attention` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `pending_message_notifications_v9`(
                        messageId, serverId, sessionId, content, hasAttachments, attention, createdAt
                    )
                    SELECT messageId, serverId, sessionId, content, hasAttachments, attention, createdAt
                    FROM `pending_message_notifications`
                    """.trimIndent(),
                )

                // 2. 保留全部待办后替换旧表并恢复索引
                db.execSQL("DROP TABLE `pending_message_notifications`")
                db.execSQL(
                    "ALTER TABLE `pending_message_notifications_v9` RENAME TO `pending_message_notifications`",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_message_notifications_serverId` ON `pending_message_notifications` (`serverId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_message_notifications_createdAt` ON `pending_message_notifications` (`createdAt`)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. stop 意图只依赖服务器身份，避免投影重建时被级联删除
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `pending_turn_stops` (
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

                // 2. 每个服务器会话只允许一个待确认 stop 命令
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_turn_stops_serverId` ON `pending_turn_stops` (`serverId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_turn_stops_serverId_sessionId` " +
                        "ON `pending_turn_stops` (`serverId`, `sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_turn_stops_createdAt` ON `pending_turn_stops` (`createdAt`)",
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 保留每个会话最新的活动 turn，将旧版重叠投影显式收敛为中断态
                db.execSQL(
                    """
                    UPDATE `messages`
                    SET `deliveryState` = 'interrupted'
                    WHERE `role` = 'assistant'
                      AND `deliveryState` = 'streaming'
                      AND `messageId` LIKE 'assistant:%'
                      AND `messageId` != (
                          SELECT newer.`messageId`
                          FROM `messages` AS newer
                          WHERE newer.`sessionId` = `messages`.`sessionId`
                            AND newer.`role` = 'assistant'
                            AND newer.`deliveryState` = 'streaming'
                            AND newer.`messageId` LIKE 'assistant:%'
                          ORDER BY newer.`createdAt` DESC, newer.`rowid` DESC
                          LIMIT 1
                      )
                    """.trimIndent(),
                )

                // 2. 被收敛为中断态的旧 turn 不再保留运行中的 block 状态
                db.execSQL(
                    """
                    UPDATE `turn_blocks`
                    SET `status` = 'completed'
                    WHERE `status` = 'running'
                      AND `messageId` IN (
                          SELECT `messageId`
                          FROM `messages`
                          WHERE `role` = 'assistant'
                            AND `deliveryState` = 'interrupted'
                            AND `messageId` LIKE 'assistant:%'
                      )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `message_content_transfers` (
                        `messageId` TEXT NOT NULL,
                        `serverId` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `byteLength` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `transferredBytes` INTEGER NOT NULL,
                        `state` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`),
                        FOREIGN KEY(`messageId`) REFERENCES `messages`(`messageId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_content_transfers_serverId` ON `message_content_transfers` (`serverId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_content_transfers_sessionId` ON `message_content_transfers` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_content_transfers_state` ON `message_content_transfers` (`state`)",
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mobile_webui_state` (
                        `serverId` TEXT NOT NULL,
                        `desiredChannel` TEXT NOT NULL,
                        `desiredTargetKey` TEXT,
                        `desiredGenerationId` TEXT,
                        `desiredManifestDigest` TEXT,
                        `releaseEpoch` TEXT,
                        `releaseSequence` INTEGER,
                        `selectionDigest` TEXT,
                        `servingGenerationId` TEXT,
                        `fallbackGenerationId` TEXT,
                        `attemptingGenerationId` TEXT,
                        `attemptingNonce` TEXT,
                        `attemptingStartedAt` INTEGER,
                        `lastHealthyAt` INTEGER,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mobile_webui_generations` (
                        `serverId` TEXT NOT NULL,
                        `generationId` TEXT NOT NULL,
                        `targetKey` TEXT NOT NULL,
                        `manifestDigest` TEXT NOT NULL,
                        `manifestPath` TEXT NOT NULL,
                        `entrypoint` TEXT NOT NULL,
                        `bridgeProtocolMin` INTEGER NOT NULL,
                        `bridgeProtocolMax` INTEGER NOT NULL,
                        `snapshotProtocolMin` INTEGER NOT NULL,
                        `snapshotProtocolMax` INTEGER NOT NULL,
                        `minimumNativeBuild` INTEGER NOT NULL,
                        `platformsJson` TEXT NOT NULL,
                        `sourceRepository` TEXT NOT NULL,
                        `sourceCommit` TEXT NOT NULL,
                        `sourceTree` TEXT NOT NULL,
                        `inputDigest` TEXT NOT NULL,
                        `buildContextDigest` TEXT NOT NULL,
                        `dirtyProvenanceJson` TEXT,
                        `reproducible` INTEGER NOT NULL,
                        `builderIdentityJson` TEXT NOT NULL,
                        `unpackedSizeBytes` INTEGER NOT NULL,
                        `fileCount` INTEGER NOT NULL,
                        `verifiedAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`, `generationId`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_generations_serverId` " +
                        "ON `mobile_webui_generations` (`serverId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_generations_manifestDigest` " +
                        "ON `mobile_webui_generations` (`manifestDigest`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_generations_lastUsedAt` " +
                        "ON `mobile_webui_generations` (`lastUsedAt`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mobile_webui_blobs` (
                        `serverId` TEXT NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `bytes` INTEGER NOT NULL,
                        `relativePath` TEXT NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`serverId`, `sha256`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_blobs_serverId` " +
                        "ON `mobile_webui_blobs` (`serverId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_blobs_lastUsedAt` " +
                        "ON `mobile_webui_blobs` (`lastUsedAt`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mobile_webui_rejects` (
                        `serverId` TEXT NOT NULL,
                        `targetKey` TEXT NOT NULL,
                        `compatibilityFingerprint` TEXT NOT NULL,
                        `reason` TEXT NOT NULL,
                        `firstRejectedAt` INTEGER NOT NULL,
                        `retryAfter` INTEGER,
                        PRIMARY KEY(`serverId`, `targetKey`, `compatibilityFingerprint`),
                        FOREIGN KEY(`serverId`) REFERENCES `server_profiles`(`serverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_rejects_serverId` " +
                        "ON `mobile_webui_rejects` (`serverId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mobile_webui_rejects_retryAfter` " +
                        "ON `mobile_webui_rejects` (`retryAfter`)"
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `turnClientMessageId` TEXT")
                db.execSQL(
                    "UPDATE `messages` SET `turnClientMessageId` = `clientMessageId`, " +
                        "`clientMessageId` = NULL WHERE `role` = 'assistant' " +
                        "AND `clientMessageId` IS NOT NULL",
                )
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `controlTurnId` TEXT")
                db.execSQL(
                    "UPDATE `messages` SET `controlTurnId` = substr(`messageId`, 11) " +
                        "WHERE `role` = 'assistant' AND `deliveryState` = 'streaming' " +
                        "AND `messageId` LIKE 'assistant:%'",
                )
            }
        }
    }
}
