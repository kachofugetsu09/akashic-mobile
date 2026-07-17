package com.akashic.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerProfileDao {
    @Upsert
    suspend fun upsert(profile: ServerProfileEntity)

    @Query("SELECT * FROM server_profiles WHERE serverId = :serverId")
    suspend fun get(serverId: String): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles ORDER BY createdAt LIMIT 1")
    suspend fun first(): ServerProfileEntity?

    @Query("SELECT * FROM server_profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("DELETE FROM server_profiles WHERE serverId = :serverId")
    suspend fun delete(serverId: String): Int
}

@Dao
interface ConversationDao {
    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE serverId = :serverId ORDER BY updatedAt DESC")
    fun observeForServer(serverId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE serverId = :serverId AND sessionId = :sessionId")
    suspend fun getForServer(serverId: String, sessionId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE serverId = :serverId")
    suspend fun listForServer(serverId: String): List<ConversationEntity>

    @Query("UPDATE conversations SET remoteState = :remoteState WHERE sessionId = :sessionId")
    suspend fun updateRemoteState(sessionId: String, remoteState: String): Int

    @Query(
        """
        DELETE FROM conversations
        WHERE serverId = :serverId
          AND (:preservedSessionId IS NULL OR sessionId != :preservedSessionId)
          AND NOT EXISTS (SELECT 1 FROM messages WHERE messages.sessionId = conversations.sessionId)
          AND NOT EXISTS (
            SELECT 1 FROM attachment_transfers
            WHERE attachment_transfers.sessionId = conversations.sessionId
              AND attachment_transfers.serverId = :serverId
          )
        """,
    )
    suspend fun deleteEmptyProjection(serverId: String, preservedSessionId: String?): Int
}

@Dao
interface MessageDao {
    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Upsert
    suspend fun upsertBlocks(blocks: List<TurnBlockEntity>)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, messageId")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM turn_blocks WHERE messageId = :messageId ORDER BY ordinal")
    fun observeBlocks(messageId: String): Flow<List<TurnBlockEntity>>

    @Query("SELECT * FROM turn_blocks WHERE messageId = :messageId ORDER BY ordinal")
    suspend fun getBlocks(messageId: String): List<TurnBlockEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun get(messageId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    @Query("SELECT * FROM turn_blocks WHERE blockId = :blockId")
    suspend fun getBlock(blockId: String): TurnBlockEntity?

    @Query("DELETE FROM turn_blocks WHERE messageId = :messageId")
    suspend fun deleteBlocks(messageId: String): Int

    @Query("UPDATE turn_blocks SET messageId = :targetId WHERE messageId = :sourceId")
    suspend fun moveBlocks(sourceId: String, targetId: String): Int

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun delete(messageId: String): Int

    @Query("UPDATE messages SET clientMessageId = NULL WHERE messageId = :messageId")
    suspend fun clearClientMessageId(messageId: String): Int

    @Query(
        """
        DELETE FROM messages
        WHERE sessionId IN (SELECT sessionId FROM conversations WHERE serverId = :serverId)
          AND NOT (
            clientMessageId IS NOT NULL
            AND (
              deliveryState IN ('pending', 'failed')
              OR EXISTS (
                SELECT 1 FROM outbox_commands
                WHERE outbox_commands.commandId = messages.clientMessageId
              )
            )
          )
        """,
    )
    suspend fun deleteServerProjection(serverId: String): Int

    @Query(
        """
        DELETE FROM messages
        WHERE sessionId = :sessionId
          AND NOT (
            clientMessageId IS NOT NULL
            AND (
              deliveryState IN ('pending', 'failed')
              OR EXISTS (
                SELECT 1 FROM outbox_commands
                WHERE outbox_commands.commandId = messages.clientMessageId
              )
            )
          )
        """,
    )
    suspend fun deleteSessionProjection(sessionId: String): Int

    @Query("UPDATE messages SET deliveryState = :state, updatedAt = :updatedAt WHERE clientMessageId = :clientMessageId")
    suspend fun updateDelivery(clientMessageId: String, state: String, updatedAt: Long): Int

    @Query("UPDATE turn_blocks SET status = 'completed', updatedAt = :updatedAt WHERE messageId = :messageId AND status = 'running'")
    suspend fun completeRunningBlocks(messageId: String, updatedAt: Long): Int

    @Query(
        "UPDATE turn_blocks SET status = 'completed', updatedAt = :updatedAt " +
            "WHERE messageId = :messageId AND kind = 'thinking' AND status = 'running'",
    )
    suspend fun completeRunningThinking(messageId: String, updatedAt: Long): Int

    @Transaction
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt, messageId")
    fun observeMessageGraph(sessionId: String): Flow<List<MessageWithBlocks>>

    @Transaction
    @Query(
        """
        SELECT messages.* FROM messages
        INNER JOIN conversations ON conversations.sessionId = messages.sessionId
        WHERE conversations.serverId = :serverId AND messages.sessionId = :sessionId
        ORDER BY messages.createdAt, messages.messageId
        """,
    )
    fun observeMessageGraphForServer(serverId: String, sessionId: String): Flow<List<MessageWithBlocks>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(command: OutboxCommandEntity)

    @Query(
        """
        SELECT * FROM outbox_commands
        WHERE serverId = :serverId AND state IN ('pending', 'retry')
        ORDER BY createdAt, commandId
        """,
    )
    suspend fun pending(serverId: String): List<OutboxCommandEntity>

    @Query(
        """
        SELECT outbox_commands.* FROM outbox_commands
        INNER JOIN messages ON messages.clientMessageId = outbox_commands.commandId
        INNER JOIN conversations ON conversations.sessionId = messages.sessionId
        WHERE outbox_commands.serverId = :serverId
          AND outbox_commands.state IN ('pending', 'retry')
          AND conversations.remoteState IN ('local', 'remote')
        ORDER BY outbox_commands.createdAt, outbox_commands.commandId
        """,
    )
    suspend fun dispatchable(serverId: String): List<OutboxCommandEntity>

    @Query(
        """
        UPDATE outbox_commands
        SET state = 'in_flight', attemptCount = attemptCount + 1, lastAttemptAt = :attemptedAt
        WHERE commandId = :commandId AND state IN ('pending', 'retry')
        """,
    )
    suspend fun markInFlight(commandId: String, attemptedAt: Long): Int

    @Query("UPDATE outbox_commands SET state = 'retry' WHERE commandId = :commandId AND state = 'in_flight'")
    suspend fun markForRetry(commandId: String): Int

    @Query("DELETE FROM outbox_commands WHERE commandId = :commandId")
    suspend fun deleteAcknowledged(commandId: String): Int

    @Query("SELECT * FROM outbox_commands WHERE commandId = :commandId")
    suspend fun get(commandId: String): OutboxCommandEntity?

    @Query("UPDATE outbox_commands SET state = 'retry' WHERE serverId = :serverId AND state = 'in_flight'")
    suspend fun resetInFlight(serverId: String): Int

    @Query("SELECT COUNT(*) FROM outbox_commands WHERE serverId = :serverId")
    fun observeCount(serverId: String): Flow<Int>
}

@Dao
interface AttachmentTransferDao {
    @Upsert
    suspend fun upsert(transfer: AttachmentTransferEntity)

    @Query("SELECT * FROM attachment_transfers WHERE attachmentId = :attachmentId")
    suspend fun get(attachmentId: String): AttachmentTransferEntity?
}

@Dao
interface RealtimeCursorDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(cursor: RealtimeCursorEntity)

    @Query("SELECT * FROM realtime_cursors WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): RealtimeCursorEntity?

    @Upsert
    suspend fun upsert(cursor: RealtimeCursorEntity)

    @Query(
        """
        UPDATE realtime_cursors
        SET lastAcknowledgedEventSeq = :throughEventSeq,
            connectionEpoch = :connectionEpoch,
            updatedAt = :updatedAt
        WHERE deviceId = :deviceId
          AND lastAcknowledgedEventSeq <= :throughEventSeq
          AND connectionEpoch <= :connectionEpoch
        """,
    )
    suspend fun advance(
        deviceId: String,
        throughEventSeq: Long,
        connectionEpoch: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE realtime_cursors
        SET lastAcknowledgedEventSeq = :eventSeq,
            connectionEpoch = :connectionEpoch,
            updatedAt = :updatedAt
        WHERE deviceId = :deviceId
          AND lastAcknowledgedEventSeq <= :eventSeq
          AND connectionEpoch <= :connectionEpoch
        """,
    )
    suspend fun reset(
        deviceId: String,
        eventSeq: Long,
        connectionEpoch: Long,
        updatedAt: Long,
    ): Int
}
