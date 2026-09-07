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
    version = 18,
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
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
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

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 不依赖 migration 连接的 FK 状态，按依赖顺序显式清掉 Session 图。
                db.execSQL("DELETE FROM `message_attachments`")
                db.execSQL("DELETE FROM `turn_blocks`")
                db.execSQL("DELETE FROM `message_content_transfers`")
                db.execSQL("DELETE FROM `media_attachments`")
                db.execSQL("DELETE FROM `conversation_read_states`")
                db.execSQL("DELETE FROM `composer_drafts`")
                db.execSQL("DELETE FROM `messages`")
                db.execSQL("DELETE FROM `outbox_commands`")
                db.execSQL("DELETE FROM `attachment_transfers`")
                db.execSQL("DELETE FROM `pending_message_notifications`")
                db.execSQL("DELETE FROM `pending_turn_stops`")
                db.execSQL("DELETE FROM `conversations`")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 旧服务端投影是 Turn 视图，不能冒充 Message v2；本地待发工作继续由 outbox 拥有。
                val oldProjection = "`serverSeq` IS NOT NULL AND NOT (`clientMessageId` IS NOT NULL AND `deliveryState` IN ('pending','sent','failed','failed_retryable','outcome_unknown'))"
                db.execSQL("DELETE FROM `message_attachments` WHERE `messageId` IN (SELECT `messageId` FROM `messages` WHERE $oldProjection)")
                db.execSQL("DELETE FROM `turn_blocks` WHERE `messageId` IN (SELECT `messageId` FROM `messages` WHERE $oldProjection)")
                db.execSQL("DELETE FROM `message_content_transfers` WHERE `messageId` IN (SELECT `messageId` FROM `messages` WHERE $oldProjection)")
                db.execSQL("DELETE FROM `messages` WHERE $oldProjection")
                db.execSQL("DELETE FROM `turn_blocks`")
                // 旧 stop 意图保留为迁移证据，但新运行时不再重放 turn.stop。
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `recordedAt` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `author` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `source` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `bodyJson` TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `metadataJson` TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentsJson` TEXT NOT NULL DEFAULT '[]'")
                db.execSQL(
                    "UPDATE `messages` SET `serverSeq` = NULL " +
                        "WHERE `clientMessageId` IS NOT NULL AND `deliveryState` IN " +
                        "('pending','sent','failed','failed_retryable','outcome_unknown')",
                )
                // v11 的传输任务只恢复旧投影正文，不能解释 Message v2 整条 JSON。
                db.execSQL("DELETE FROM `message_content_transfers`")
                db.execSQL("ALTER TABLE `message_content_transfers` ADD COLUMN `messageSeq` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `message_content_transfers` ADD COLUMN `notifyWhenReady` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `pending_message_notifications` ADD COLUMN `ready` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `pending_message_notifications` ADD COLUMN `headSeq` INTEGER")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 找出仍使用临时本地 ID 的待发 Input；outbox commandId 已经是目标 Message ID。
                db.execSQL(
                    """
                    CREATE TEMP TABLE `message_identity_moves` AS
                    SELECT
                      local.`messageId` AS `sourceId`,
                      local.`clientMessageId` AS `targetId`,
                      EXISTS(
                        SELECT 1 FROM `messages` AS target
                        WHERE target.`messageId` = local.`clientMessageId`
                      ) AS `targetExists`,
                      EXISTS(
                        SELECT 1 FROM `messages` AS remote
                        WHERE remote.`messageId` = local.`clientMessageId`
                          AND remote.`sessionId` = local.`sessionId`
                          AND remote.`serverSeq` IS NOT NULL
                          AND remote.`recordedAt` != ''
                          AND remote.`bodyJson` != '{}'
                          AND remote.`role` = 'user'
                          AND remote.`bodyJson` LIKE '%"kind":"input"%'
                      ) AS `keepTarget`
                      , EXISTS(
                        SELECT 1 FROM `messages` AS target
                        WHERE target.`messageId` = local.`clientMessageId`
                          AND target.`sessionId` = local.`sessionId`
                          AND target.`role` = 'restoring'
                          AND target.`deliveryState` = 'restoring'
                          AND EXISTS(
                            SELECT 1 FROM `message_content_transfers` AS transfer
                            WHERE transfer.`messageId` = target.`messageId`
                              AND transfer.`sessionId` = target.`sessionId`
                          )
                      ) AS `targetRestoring`
                    FROM `messages` AS local
                    WHERE local.`role` = 'user'
                      AND local.`clientMessageId` IS NOT NULL
                      AND local.`messageId` = 'user:' || local.`clientMessageId`
                      AND local.`deliveryState` IN (
                        'pending', 'sent', 'failed', 'failed_retryable', 'outcome_unknown'
                      )
                    """.trimIndent(),
                )
                db.query(
                    "SELECT COUNT(*) FROM `message_identity_moves` " +
                        "WHERE `targetExists` = 1 AND `keepTarget` = 0 AND `targetRestoring` = 0",
                ).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "Room 18 Message identity target is neither a complete Input nor a restoring Input"
                    }
                }
                db.query(
                    """
                    SELECT COUNT(*) FROM `messages`
                    WHERE `role` = 'user'
                      AND `clientMessageId` IS NOT NULL
                      AND `messageId` != `clientMessageId`
                      AND `messageId` != 'user:' || `clientMessageId`
                      AND `deliveryState` IN (
                        'pending', 'sent', 'failed', 'failed_retryable', 'outcome_unknown'
                      )
                    """.trimIndent(),
                ).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        "Room 18 found unsupported local Message identity"
                    }
                }

                // 2. 所有稳定引用先改指统一 ID。
                db.execSQL(
                    """
                    UPDATE `conversation_read_states`
                    SET `anchorMessageId` = (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `sourceId` = `conversation_read_states`.`anchorMessageId`
                    )
                    WHERE `anchorMessageId` IN (SELECT `sourceId` FROM `message_identity_moves`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `composer_drafts`
                    SET `replyToMessageId` = (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `sourceId` = `composer_drafts`.`replyToMessageId`
                    )
                    WHERE `replyToMessageId` IN (SELECT `sourceId` FROM `message_identity_moves`)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `messages`
                    SET `replyToMessageId` = (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `sourceId` = `messages`.`replyToMessageId`
                    )
                    WHERE `replyToMessageId` IN (SELECT `sourceId` FROM `message_identity_moves`)
                    """.trimIndent(),
                )

                // 3. 没有目标行时复制本地事实；restoring 目标保留 manifest 和下载进度。
                db.execSQL(
                    """
                    INSERT INTO `messages` (
                      `messageId`, `clientMessageId`, `sessionId`, `role`, `text`, `deliveryState`,
                      `createdAt`, `updatedAt`, `serverSeq`, `replyToMessageId`, `replyRole`,
                      `replyPreview`, `turnClientMessageId`, `controlTurnId`, `recordedAt`, `author`,
                      `source`, `bodyJson`, `metadataJson`, `attachmentsJson`
                    )
                    SELECT
                      move.`targetId`, NULL, local.`sessionId`, local.`role`, local.`text`,
                      local.`deliveryState`, local.`createdAt`, local.`updatedAt`, local.`serverSeq`,
                      local.`replyToMessageId`, local.`replyRole`, local.`replyPreview`,
                      local.`turnClientMessageId`, local.`controlTurnId`, local.`recordedAt`,
                      local.`author`, local.`source`, local.`bodyJson`, local.`metadataJson`,
                      local.`attachmentsJson`
                    FROM `message_identity_moves` AS move
                    JOIN `messages` AS local ON local.`messageId` = move.`sourceId`
                    WHERE move.`targetExists` = 0
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `messages`
                    SET
                      `clientMessageId` = NULL,
                      `role` = (SELECT local.`role` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `text` = (SELECT local.`text` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `deliveryState` = 'restoring',
                      `createdAt` = (SELECT local.`createdAt` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `updatedAt` = (SELECT local.`updatedAt` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `replyToMessageId` = (SELECT local.`replyToMessageId` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `replyRole` = (SELECT local.`replyRole` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`),
                      `replyPreview` = (SELECT local.`replyPreview` FROM `message_identity_moves` AS move JOIN `messages` AS local ON local.`messageId` = move.`sourceId` WHERE move.`targetId` = `messages`.`messageId`)
                    WHERE `messages`.`messageId` IN (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `targetRestoring` = 1
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `outbox_commands`
                    SET `state` = 'retry', `lastAttemptAt` = NULL
                    WHERE `commandId` IN (
                      SELECT move.`targetId`
                      FROM `message_identity_moves` AS move
                      JOIN `messages` AS local ON local.`messageId` = move.`sourceId`
                      WHERE move.`targetRestoring` = 1
                        AND local.`deliveryState` IN ('failed_retryable', 'outcome_unknown')
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    UPDATE `attachment_transfers`
                    SET `state` = 'sending'
                    WHERE `state` = 'ready'
                      AND `attachmentId` IN (
                        SELECT link.`attachmentId`
                        FROM `message_attachments` AS link
                        JOIN `message_identity_moves` AS move ON move.`sourceId` = link.`messageId`
                        JOIN `messages` AS local ON local.`messageId` = move.`sourceId`
                        WHERE move.`targetRestoring` = 1
                          AND local.`deliveryState` IN ('failed_retryable', 'outcome_unknown')
                      )
                    """.trimIndent(),
                )
                listOf("message_attachments", "turn_blocks").forEach { table ->
                    db.execSQL(
                        """
                        UPDATE `$table`
                        SET `messageId` = (
                          SELECT `targetId` FROM `message_identity_moves`
                          WHERE `sourceId` = `$table`.`messageId` AND `keepTarget` = 0
                        )
                        WHERE `messageId` IN (
                          SELECT `sourceId` FROM `message_identity_moves` WHERE `keepTarget` = 0
                        )
                        """.trimIndent(),
                    )
                }
                db.execSQL(
                    """
                    UPDATE `message_content_transfers`
                    SET `messageId` = (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `sourceId` = `message_content_transfers`.`messageId`
                        AND `targetExists` = 0
                    )
                    WHERE `messageId` IN (
                      SELECT `sourceId` FROM `message_identity_moves` WHERE `targetExists` = 0
                    )
                    """.trimIndent(),
                )

                // 4. 完整远端行证明发送已接受，补做漏掉的 ACK 后移除旧投影关系。
                db.execSQL(
                    """
                    UPDATE `attachment_transfers`
                    SET `state` = 'sent'
                    WHERE `state` IN ('ready', 'sending')
                      AND `attachmentId` IN (
                        SELECT link.`attachmentId`
                        FROM `message_attachments` AS link
                        JOIN `message_identity_moves` AS move ON move.`sourceId` = link.`messageId`
                        WHERE move.`keepTarget` = 1
                      )
                    """.trimIndent(),
                )
                db.execSQL(
                    "DELETE FROM `outbox_commands` WHERE `commandId` IN (" +
                        "SELECT `targetId` FROM `message_identity_moves` WHERE `keepTarget` = 1)",
                )
                listOf("message_attachments", "turn_blocks").forEach { table ->
                    db.execSQL(
                        "DELETE FROM `$table` WHERE `messageId` IN (" +
                            "SELECT `sourceId` FROM `message_identity_moves` WHERE `keepTarget` = 1)",
                    )
                }
                db.execSQL(
                    "DELETE FROM `message_content_transfers` WHERE `messageId` IN (" +
                        "SELECT `sourceId` FROM `message_identity_moves` WHERE `targetExists` = 1)",
                )
                db.execSQL(
                    "DELETE FROM `pending_message_notifications` WHERE `messageId` IN (" +
                        "SELECT `sourceId` FROM `message_identity_moves` WHERE `keepTarget` = 1)",
                )
                db.execSQL(
                    """
                    UPDATE `pending_message_notifications`
                    SET `messageId` = (
                      SELECT `targetId` FROM `message_identity_moves`
                      WHERE `sourceId` = `pending_message_notifications`.`messageId`
                    )
                    WHERE `messageId` IN (
                      SELECT `sourceId` FROM `message_identity_moves` WHERE `keepTarget` = 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "DELETE FROM `messages` WHERE `messageId` IN (SELECT `sourceId` FROM `message_identity_moves`)",
                )
                db.execSQL("UPDATE `messages` SET `clientMessageId` = NULL WHERE `clientMessageId` IS NOT NULL")
                db.execSQL("DROP TABLE `message_identity_moves`")
            }
        }
    }
}
