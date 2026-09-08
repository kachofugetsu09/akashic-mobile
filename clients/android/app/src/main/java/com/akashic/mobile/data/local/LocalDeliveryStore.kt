package com.akashic.mobile.data.local

import androidx.room.withTransaction
import com.akashic.mobile.data.realtime.ProtocolCodec
import com.akashic.mobile.data.realtime.AttachmentProgressPayload
import com.akashic.mobile.data.realtime.AttachmentReadyPayload
import com.akashic.mobile.data.realtime.HistoryPagePayload
import com.akashic.mobile.data.realtime.RemoteHistoryMessage
import com.akashic.mobile.data.realtime.SessionListPayload
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import com.akashic.mobile.data.realtime.finalMessageAttention
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RemoveUnavailableConversationResult {
    REMOVED,
    HAS_LOCAL_WORK,
    NOT_REMOTE,
}

enum class PreparedComposerDraftResult {
    COMMITTED,
    ALREADY_COMMITTED,
    CONFLICT,
}

internal enum class NotificationTargetProjection {
    AVAILABLE,
    MISSING,
    WRONG_SERVER,
    WRONG_SESSION,
}

private const val TOOL_BLOCK_V1_PREFIX = "tool.v1:"

@Serializable
internal data class StoredToolBlock(
    val name: String,
    val description: String? = null,
    val resultPreview: String? = null,
    val arguments: JsonObject? = null,
    val durationMillis: Long? = null,
)

internal fun decodeStoredToolBlock(content: String): StoredToolBlock {
    if (!content.startsWith(TOOL_BLOCK_V1_PREFIX)) {
        return StoredToolBlock(name = "工具调用", description = content)
    }
    return ProtocolCodec.json().decodeFromString(content.removePrefix(TOOL_BLOCK_V1_PREFIX))
}

private fun encodeStoredToolBlock(content: StoredToolBlock): String =
    TOOL_BLOCK_V1_PREFIX + ProtocolCodec.json().encodeToString(content)

internal fun parseServerInstant(value: String, field: String): Long =
    try {
        Instant.parse(value).toEpochMilli()
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("$field must be an RFC 3339 UTC instant", error)
    }

/** 从 Message body 提取原生列表摘要，保留非文本 part 给 WebUI 使用。 */
internal fun timelineText(body: JsonObject): String {
    if (body["kind"]?.jsonPrimitive?.contentOrNull == "control") {
        return body["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
    return (body["parts"] as? JsonArray).orEmpty().mapNotNull { raw ->
        val part = raw as? JsonObject ?: return@mapNotNull null
        if (part["kind"]?.jsonPrimitive?.contentOrNull != "text") return@mapNotNull null
        part["value"]?.jsonPrimitive?.contentOrNull
    }.joinToString("\n")
}

class LocalDeliveryStore(
    private val database: AppDatabase,
    private val mediaCache: MediaCacheStore,
    private val messageContentStore: MessageContentStore,
) {
    private val projectionStateMutex = Mutex()
    /** 明确失败重试换 ID 后，短暂接住 WebUI 已发出的旧 ID 操作。 */

    /** 恢复当前电脑拥有的会话选择，拒绝把另一台电脑的会话带入当前投影。 */
    suspend fun restoreSelectedSession(serverId: String, selectedSessionId: String?): String? {
        // 1. 优先保留仍属于当前电脑的显式选择
        selectedSessionId?.let { sessionId ->
            val selected = database.conversations().get(sessionId)
            if (selected?.serverId == serverId) return sessionId
        }

        // 2. 否则只从当前电脑的持久会话中选择最近一项
        return database.conversations().latestForServer(serverId)?.sessionId
    }

    /** 核对系统通知指向当前电脑拥有的本地会话与消息投影。 */
    internal suspend fun notificationTargetProjection(
        serverId: String,
        sessionId: String,
        messageId: String?,
    ): NotificationTargetProjection = projectionStateMutex.withLock {
        // 1. 会话必须存在且仍由当前电脑拥有
        val conversation = database.conversations().get(sessionId)
            ?: return@withLock NotificationTargetProjection.MISSING
        if (conversation.serverId != serverId) {
            return@withLock NotificationTargetProjection.WRONG_SERVER
        }

        // 2. 连接通知只选择会话；消息通知还必须命中同一会话内的消息
        if (messageId == null) return@withLock NotificationTargetProjection.AVAILABLE
        val message = database.messages().get(messageId)
            ?: return@withLock NotificationTargetProjection.MISSING
        if (message.sessionId != sessionId) {
            return@withLock NotificationTargetProjection.WRONG_SESSION
        }
        NotificationTargetProjection.AVAILABLE
    }

    /** 串行校验并保存当前电脑的一份会话草稿。 */
    suspend fun saveComposerDraft(
        sessionId: String,
        text: String,
        replyToMessageId: String?,
        expectedServerId: String?,
        updatedAt: Long,
    ) = projectionStateMutex.withLock {
        // 1. 在 WebView 边界限制消息大小并确认会话归属
        require(text.length <= 65_536) { "会话草稿超过消息长度上限" }
        require(updatedAt in 1..9_007_199_254_740_991) { "会话草稿 revision 无效" }
        val conversation = requireNotNull(database.conversations().get(sessionId)) {
            "会话草稿对应的会话不存在: $sessionId"
        }
        require(conversation.serverId == expectedServerId) { "会话草稿不属于当前电脑" }

        // 2. 引用目标消失属于可恢复状态；跨会话目标仍然是协议错误
        val resolvedReplyId = resolveComposerReply(sessionId, replyToMessageId)

        // 3. 空草稿删除实体，其余状态原样保存供进程重启恢复
        if (text.isEmpty() && resolvedReplyId == null) {
            database.composerDrafts().delete(conversation.serverId, sessionId)
        } else {
            database.composerDrafts().upsert(
                ComposerDraftEntity(
                    sessionId = sessionId,
                    serverId = conversation.serverId,
                    text = text,
                    replyToMessageId = resolvedReplyId,
                    updatedAt = updatedAt,
                ),
            )
        }
    }

    /** 在草稿唯一写序内比较基线并提交分享结果，拒绝覆盖较新的用户输入。 */
    suspend fun commitPreparedComposerDraft(
        sessionId: String,
        text: String,
        replyToMessageId: String?,
        baseText: String?,
        baseReplyToMessageId: String?,
        baseUpdatedAt: Long?,
        expectedServerId: String?,
        updatedAt: Long,
    ): PreparedComposerDraftResult = projectionStateMutex.withLock {
        database.withTransaction {
            // 1. 在同一锁内解析当前引用身份与会话 owner
            require(text.length <= 65_536) { "会话草稿超过消息长度上限" }
            val conversation = requireNotNull(database.conversations().get(sessionId)) {
                "会话草稿对应的会话不存在: $sessionId"
            }
            require(conversation.serverId == expectedServerId) { "会话草稿不属于当前电脑" }
            val resolvedReplyId = resolveComposerReply(sessionId, replyToMessageId)
            val current = database.composerDrafts().get(conversation.serverId, sessionId)

            // 2. 已是目标值只确认消费；只有基线未变化时才允许写入
            if (current != null && current.text == text && current.replyToMessageId == resolvedReplyId) {
                return@withTransaction PreparedComposerDraftResult.ALREADY_COMMITTED
            }
            val baseUnchanged = if (baseUpdatedAt == null) {
                current == null
            } else {
                current?.run {
                    this.text == baseText &&
                        this.replyToMessageId == baseReplyToMessageId &&
                        this.updatedAt == baseUpdatedAt
                } == true
            }
            if (!baseUnchanged) return@withTransaction PreparedComposerDraftResult.CONFLICT

            // 3. prepared 分享不会为空，原样保存解析后的 canonical reply
            database.composerDrafts().upsert(
                ComposerDraftEntity(
                    sessionId = sessionId,
                    serverId = conversation.serverId,
                    text = text,
                    replyToMessageId = resolvedReplyId,
                    updatedAt = updatedAt,
                ),
            )
            PreparedComposerDraftResult.COMMITTED
        }
    }

    private suspend fun resolveComposerReply(sessionId: String, replyToMessageId: String?): String? =
        replyToMessageId?.let { messageId ->
            require(messageId.length in 1..512) { "会话草稿引用 ID 无效" }
            val target = database.messages().get(messageId) ?: return@let null
            require(target.sessionId == sessionId) { "会话草稿引用不属于当前会话" }
            messageId
        }

    /** 串行校验并保存 WebView 当前可见消息锚点。 */
    suspend fun saveReadingPosition(
        sessionId: String,
        messageId: String,
        offsetPx: Int,
        expectedServerId: String?,
        updatedAt: Long,
    ): Boolean = projectionStateMutex.withLock {
        // 1. 校验当前投影身份；长消息顶部可远离视口，不裁切其相对偏移
        val conversation = requireNotNull(database.conversations().get(sessionId)) {
            "阅读位置会话不存在: $sessionId"
        }
        require(conversation.serverId == expectedServerId) { "阅读位置会话不属于当前电脑" }
        val message = database.messages().get(messageId) ?: return@withLock false
        require(message.sessionId == sessionId) { "阅读锚点不属于当前会话" }

        // 2. 与历史落地共用写锁，锚点始终引用同一个 Message
        database.conversationReadStates().savePosition(sessionId, messageId, offsetPx, updatedAt)
        true
    }

    /** 用户主动进入会话时清除旧锚点，但不提前推进已读水位。 */
    suspend fun clearReadingPosition(
        sessionId: String,
        expectedServerId: String?,
        updatedAt: Long,
    ) = projectionStateMutex.withLock {
        val conversation = requireNotNull(database.conversations().get(sessionId)) {
            "阅读位置会话不存在: $sessionId"
        }
        require(conversation.serverId == expectedServerId) { "阅读位置会话不属于当前电脑" }
        database.conversationReadStates().clearPosition(sessionId, updatedAt)
    }

    /** 串行推进已读水位并清除旧阅读锚点。 */
    suspend fun markSessionReadThrough(
        sessionId: String,
        readAt: Long,
        expectedServerId: String?,
        updatedAt: Long,
    ) = projectionStateMutex.withLock {
        // 1. 校验 WebView 边界输入与当前电脑身份
        require(readAt >= 0) { "已读水位不能为负数" }
        val conversation = requireNotNull(database.conversations().get(sessionId)) {
            "已读水位会话不存在: $sessionId"
        }
        require(conversation.serverId == expectedServerId) { "已读水位会话不属于当前电脑" }

        // 2. 与保存及重试换 ID 共用同一写序
        database.conversationReadStates().markReadThrough(sessionId, readAt, updatedAt)
    }

    suspend fun savePairedProfile(profile: ServerProfileEntity, cursor: RealtimeCursorEntity) {
        require(profile.serverId == cursor.serverId) { "Realtime cursor belongs to another server" }
        database.withTransaction {
            // 1. server profile 是长期状态；重复配对只更新当前凭据
            database.serverProfiles().upsert(profile)

            // 2. cursor 是 device 派生状态；新 device 必须原子替换旧 checkpoint
            val existing = database.realtimeCursors().getForServer(profile.serverId)
            if (existing == null) {
                database.realtimeCursors().insert(cursor)
            } else if (existing.deviceId != cursor.deviceId) {
                check(database.realtimeCursors().deleteForServer(profile.serverId) == 1)
                database.realtimeCursors().insert(cursor)
            }
        }
    }

    suspend fun enqueueMessage(
        conversation: ConversationEntity,
        message: MessageEntity,
        command: OutboxCommandEntity,
        attachments: List<MediaAttachmentEntity>,
        sentDraftRevision: Long? = null,
    ) = projectionStateMutex.withLock {
        database.withTransaction {
            // 1. 持久化用户消息与可重放 outbox
            database.conversations().upsert(conversation)
            database.messages().upsert(message)
            database.outbox().enqueue(command)

            // 2. 只消费发送时捕获的草稿 revision，保留随后产生的新输入
            if (sentDraftRevision != null) {
                require(sentDraftRevision in 1..9_007_199_254_740_991) {
                    "会话草稿 revision 无效"
                }
                database.composerDrafts().deleteRevision(
                    conversation.serverId,
                    conversation.sessionId,
                    sentDraftRevision,
                )
            }

            // 3. 附件状态与消息提交保持原子
            if (attachments.isNotEmpty()) {
                val attachmentIds = attachments.map { it.cacheId }
                database.mediaAttachments().upsertAll(attachments)
                database.mediaAttachments().linkAll(
                    attachmentIds.mapIndexed { ordinal, id ->
                        MessageAttachmentEntity(message.messageId, id, ordinal)
                    },
                )
                check(database.attachmentTransfers().markSending(attachmentIds, message.updatedAt) == attachmentIds.size)
            }
        }
    }

    /** 清除可从服务端恢复的投影与附件缓存，同时保留配对和未发送工作。 */
    suspend fun clearReloadableCache(serverId: String, preservedSessionId: String?) {
        // 1. 原子移除已提交消息，保留待发送消息、草稿和连接身份；
        //    与 sync.reset_required 共用同一白名单：sent（已 ACK、正文尚未投影）也保留
        projectionStateMutex.withLock {
            database.withTransaction {
                database.messages().deleteServerProjection(serverId)
                database.conversations().deleteEmptyProjection(serverId, preservedSessionId)
            }
        }

        // 2. 删除失去消息引用的附件文件和描述符
        mediaCache.reconcile()
    }

    /** 删除服务端已不存在、且没有未发送工作的本机会话副本。 */
    suspend fun removeUnavailableConversation(
        serverId: String,
        sessionId: String,
    ): RemoveUnavailableConversationResult = projectionStateMutex.withLock {
        // 1. 在持久化边界重新确认会话归属和可删除状态
        val result = database.withTransaction {
            val conversation = requireNotNull(database.conversations().get(sessionId)) {
                "Unknown conversation: $sessionId"
            }
            require(conversation.serverId == serverId) { "Conversation belongs to another server" }
            if (!conversation.remoteKnown) {
                return@withTransaction RemoveUnavailableConversationResult.NOT_REMOTE
            }
            if (
                database.messages().countLocalWorkForSession(sessionId) > 0 ||
                database.attachmentTransfers().drafts(serverId, sessionId).isNotEmpty() ||
                database.composerDrafts().get(serverId, sessionId) != null
            ) {
                return@withTransaction RemoveUnavailableConversationResult.HAS_LOCAL_WORK
            }

            // 2. 删除本地投影，依靠外键清理消息、已读锚点和媒体描述符
            database.attachmentTransfers().deleteSentForSession(serverId, sessionId)
            check(database.conversations().delete(serverId, sessionId) == 1)
            RemoveUnavailableConversationResult.REMOVED
        }

        // 3. 删除失去数据库引用的缓存文件
        if (result == RemoveUnavailableConversationResult.REMOVED) mediaCache.reconcile()
        result
    }

    /** 在同一事务应用有序事件并推进持久化 cursor。 */
    suspend fun applyEvent(
        serverId: String,
        deviceId: String,
        envelope: WireEnvelope,
        updatedAt: Long,
        preservedSessionId: String? = null,
    ): Long = projectionStateMutex.withLock {
        require(envelope.kind == WireKind.EVENT) { "Only event envelopes can advance the cursor" }
        val eventSeq = requireNotNull(envelope.eventSeq)
        val connectionEpoch = requireNotNull(envelope.connectionEpoch)
        database.withTransaction {
            val cursor = requireNotNull(database.realtimeCursors().get(deviceId)) {
                "Realtime cursor is missing for device $deviceId"
            }
            require(cursor.serverId == serverId) { "Realtime cursor belongs to another server" }
            require(connectionEpoch >= cursor.connectionEpoch) { "Stale event connection epoch" }
            if (envelope.type == "sync.reset_required") {
                require(eventSeq >= cursor.lastAcknowledgedEventSeq) { "Stale reset event sequence" }
                database.messages().deleteServerProjection(serverId)
                database.conversations().deleteEmptyProjection(serverId, preservedSessionId)
                check(
                    database.realtimeCursors().reset(deviceId, eventSeq, connectionEpoch, updatedAt) == 1,
                ) { "Reset cursor rollback or stale connection epoch for device $deviceId" }
                return@withTransaction eventSeq
            }
            require(eventSeq == cursor.lastAcknowledgedEventSeq + 1) {
                "Event sequence gap: expected ${cursor.lastAcknowledgedEventSeq + 1}, got $eventSeq"
            }

            // Message v2 正文来自 history/session.follow；旧 durable Turn 事件只推进 ACK。
            envelope.sessionId?.let { sessionId ->
                if (envelope.type in REMOTE_SESSION_EVENTS) {
                    ensureRemoteConversation(serverId, sessionId, updatedAt)
                }
            }
            applyEventContent(serverId, envelope, updatedAt)
            val changed = database.realtimeCursors().advance(
                deviceId = deviceId,
                throughEventSeq = eventSeq,
                connectionEpoch = connectionEpoch,
                updatedAt = updatedAt,
            )
            check(changed == 1) { "Cursor rollback or stale connection epoch for device $deviceId" }
            eventSeq
        }
    }

    /** 应用当前连接订阅到的非 durable Message 行，不推进 durable event cursor。 */
    suspend fun applyMessageRows(
        serverId: String,
        sessionId: String,
        items: List<RemoteHistoryMessage>,
    ) =
        projectionStateMutex.withLock {
            database.withTransaction {
                applyMessageRowsInTransaction(
                    serverId,
                    sessionId,
                    items,
                    notificationMessageIds = items.mapTo(linkedSetOf()) { it.id },
                )
            }
        }

    suspend fun markOutboxAttempt(commandId: String, attemptedAt: Long) {
        val changed = database.outbox().markInFlight(commandId, attemptedAt)
        check(changed == 1) { "Outbox command is not pending: $commandId" }
    }

    suspend fun retryOutbox(commandId: String) {
        val changed = database.outbox().markForRetry(commandId)
        check(changed == 1) { "Outbox command is not in flight: $commandId" }
    }

    suspend fun acknowledgeOutbox(
        commandId: String,
        acknowledgedClientMessageId: String,
        updatedAt: Long,
    ): List<String> =
        database.withTransaction {
            val command = requireNotNull(database.outbox().get(commandId)) { "Unknown outbox command: $commandId" }
            val envelope = ProtocolCodec.decode(command.envelopeJson)
            val payload = ProtocolCodec.decodePayload<com.akashic.mobile.data.realtime.MessageSendPayload>(envelope.payload)
            require(acknowledgedClientMessageId == payload.clientMessageId) {
                "Outbox ACK client_message_id mismatch"
            }
            check(database.conversations().markRemoteKnown(requireNotNull(envelope.sessionId)) == 1) {
                "Outbox ACK 对应的会话投影不存在: ${envelope.sessionId}"
            }
            val message = requireNotNull(database.messages().get(payload.clientMessageId)) {
                "Outbox message is missing: ${payload.clientMessageId}"
            }
            if (message.serverSeq == null) {
                check(database.messages().markInputAccepted(payload.clientMessageId, updatedAt) == 1) {
                    "Outbox message is not pending: ${payload.clientMessageId}"
                }
            } else if (message.deliveryState != "complete") {
                require(
                    message.role == "user" && message.deliveryState == "restoring" &&
                        database.messageContentTransfers().get(message.messageId) != null
                ) { "Saved Input has invalid restoring state" }
            } else {
                require(message.role == "user") {
                    "Canonical Input has invalid delivery state"
                }
            }
            if (payload.mediaRefs.isNotEmpty()) {
                payload.mediaRefs.forEach { attachmentId ->
                    val transfer = requireNotNull(database.attachmentTransfers().get(attachmentId)) {
                        "Outbox attachment is missing: $attachmentId"
                    }
                    check(transfer.state == "sending") { "Outbox attachment is not sending: $attachmentId" }
                }
                check(database.attachmentTransfers().markSent(payload.mediaRefs, updatedAt) == payload.mediaRefs.size)
            }
            check(database.outbox().deleteAcknowledged(commandId) == 1) {
                "Outbox command disappeared: $commandId"
            }
            payload.mediaRefs
        }

    /** 未知结果保留原命令，等待用户以同一 ID 核对。 */
    suspend fun retainUnknownOutbox(commandId: String, updatedAt: Long) {
        database.withTransaction {
            val command = requireNotNull(database.outbox().get(commandId)) { "Unknown outbox command: $commandId" }
            val envelope = ProtocolCodec.decode(command.envelopeJson)
            val payload = ProtocolCodec.decodePayload<com.akashic.mobile.data.realtime.MessageSendPayload>(envelope.payload)
            val message = requireNotNull(database.messages().get(payload.clientMessageId)) { "Outbox message is missing" }
            if (message.serverSeq != null) {
                acknowledgeOutbox(commandId, payload.clientMessageId, updatedAt)
                return@withTransaction
            }
            val state = "outcome_unknown"
            check(database.messages().updateDelivery(payload.clientMessageId, state, updatedAt) == 1)
            check(database.outbox().markFailed(commandId, state) == 1)
        }
    }

    /** 明确拒绝结束发送待办，原正文和附件保留为失败记录。 */
    suspend fun discardFailedOutbox(commandId: String, updatedAt: Long) {
        database.withTransaction {
            val command = requireNotNull(database.outbox().get(commandId)) { "Unknown outbox command: $commandId" }
            val envelope = ProtocolCodec.decode(command.envelopeJson)
            val payload = ProtocolCodec.decodePayload<com.akashic.mobile.data.realtime.MessageSendPayload>(envelope.payload)
            val message = requireNotNull(database.messages().get(payload.clientMessageId)) { "Outbox message is missing" }
            if (message.serverSeq != null) {
                acknowledgeOutbox(commandId, payload.clientMessageId, updatedAt)
                return@withTransaction
            }
            check(database.messages().updateDelivery(payload.clientMessageId, "failed", updatedAt) == 1)
            if (payload.mediaRefs.isNotEmpty()) {
                check(database.attachmentTransfers().restoreReady(payload.mediaRefs, updatedAt) == payload.mediaRefs.size)
            }
            check(database.outbox().deleteAcknowledged(commandId) == 1)
        }
    }

    /** 只核对结果未知的发送，不移动 Message 身份或制造新命令。 */
    suspend fun verifyMessageOutcome(messageId: String, updatedAt: Long): Boolean =
        projectionStateMutex.withLock {
            database.withTransaction {
                val message = requireNotNull(database.messages().get(messageId)) { "Unknown message: $messageId" }
                require(message.role == "user") { "Only Input delivery can be checked" }
                if (message.deliveryState != "outcome_unknown") return@withTransaction false
                val command = requireNotNull(database.outbox().get(messageId)) { "Unknown outcome has no command" }
                require(command.state == "outcome_unknown") { "Message and outbox outcome states diverged" }
                val envelope = ProtocolCodec.decode(command.envelopeJson)
                val payload = ProtocolCodec.decodePayload<com.akashic.mobile.data.realtime.MessageSendPayload>(envelope.payload)
                require(payload.clientMessageId == messageId) { "Outbox message id mismatch" }
                check(database.outbox().recheckUnknown(messageId) == 1)
                check(database.messages().updateDelivery(messageId, "pending", updatedAt) == 1)
                payload.mediaRefs.forEach { id ->
                    check(database.attachmentTransfers().get(id)?.state == "sending") { "Unknown command lost its attachment ownership" }
                }
                true
            }
        }

    private suspend fun applyEventContent(
        serverId: String,
        envelope: WireEnvelope,
        updatedAt: Long,
    ) {
        when (envelope.type) {
            "session.list" -> applySessionList(serverId, envelope)
            "session.created" -> upsertConversation(serverId, envelope, updatedAt)
            "session.updated" -> applySessionUpdated(serverId, envelope, updatedAt)
            "history.page" -> {
                val sessionId = requireNotNull(envelope.sessionId)
                val notificationIds = database.pendingMessageNotifications()
                    .pendingHintsForSession(sessionId).mapTo(linkedSetOf()) { it.messageId }
                val page = ProtocolCodec.decodePayload<HistoryPagePayload>(envelope.payload)
                require(page.version == 2) { "History page message version mismatch" }
                applyMessageRowsInTransaction(
                    serverId,
                    sessionId,
                    page.items,
                    notificationIds,
                )
            }
            "turn.started", "react.thinking.delta", "react.tool.started",
            "react.tool.completed", "answer.delta", "message.final",
            "turn.interrupted", "turn.output.completed" -> Unit
            "attachment.progress" -> applyAttachmentProgress(envelope, updatedAt)
            "attachment.ready" -> applyAttachmentReady(envelope, updatedAt)
            else -> Unit
        }
    }

    /** 同一事务保存会话更新与主动通知 hint，随后才推进 durable cursor。 */
    private suspend fun applySessionUpdated(
        serverId: String,
        envelope: WireEnvelope,
        updatedAt: Long,
    ) {
        upsertConversation(serverId, envelope, updatedAt)
        val rawMessageId = envelope.payload["message_id"]
        val rawHeadSeq = envelope.payload["head_seq"]
        if (rawMessageId == null && rawHeadSeq == null) return
        val sessionId = requireNotNull(envelope.sessionId)
        require(envelope.payload["session_id"]?.jsonPrimitive?.content == sessionId) {
            "session.updated hint session_id mismatch"
        }
        val messageId = rawMessageId?.jsonPrimitive?.content
            ?: error("session.updated hint 缺少 message_id")
        require(messageId.isNotBlank() && messageId.length <= 512) {
            "session.updated hint message_id 无效"
        }
        val headSeq = rawHeadSeq?.jsonPrimitive?.longOrNull
            ?: error("session.updated hint 缺少 head_seq")
        require(headSeq >= 0) { "session.updated hint head_seq 无效" }
        database.pendingMessageNotifications().insertHint(
            PendingMessageNotificationEntity(
                messageId = messageId,
                serverId = serverId,
                sessionId = sessionId,
                content = "",
                hasAttachments = false,
                attention = "COMPLETE",
                ready = false,
                headSeq = headSeq,
                createdAt = updatedAt,
            ),
        )
        val existing = database.messages().get(messageId)
        if (existing?.serverSeq != null && existing.sessionId == sessionId) {
            val body = ProtocolCodec.json().parseToJsonElement(existing.bodyJson).jsonObject
            if (
                body["kind"]?.jsonPrimitive?.contentOrNull == "output" &&
                body["finish"]?.jsonPrimitive?.contentOrNull == "complete"
            ) {
                val metadata = ProtocolCodec.json().parseToJsonElement(existing.metadataJson).jsonObject
                val attachments = ProtocolCodec.json().parseToJsonElement(existing.attachmentsJson) as JsonArray
                database.pendingMessageNotifications().markReady(
                    messageId = messageId,
                    content = timelineText(body),
                    hasAttachments = attachments.isNotEmpty(),
                    attention = finalMessageAttention(metadata).name,
                    createdAt = existing.createdAt,
                )
            }
        }
    }

    private suspend fun applyAttachmentProgress(envelope: WireEnvelope, updatedAt: Long) {
        val payload = ProtocolCodec.decodePayload<AttachmentProgressPayload>(envelope.payload)
        val transfer = requireNotNull(database.attachmentTransfers().get(payload.attachmentId)) {
            "Attachment progress references an unknown transfer"
        }
        require(payload.sizeBytes == transfer.sizeBytes) { "Attachment progress size mismatch" }
        require(payload.transferredBytes in transfer.transferredBytes..transfer.sizeBytes) {
            "Attachment progress moved backwards or exceeded size"
        }
        val state = if (payload.transferredBytes == transfer.sizeBytes) "finishing" else "uploading"
        check(
            database.attachmentTransfers().updateState(
                attachmentId = transfer.attachmentId,
                transferredBytes = payload.transferredBytes,
                state = state,
                updatedAt = updatedAt,
            ) == 1,
        )
    }

    private suspend fun applyAttachmentReady(envelope: WireEnvelope, updatedAt: Long) {
        val payload = ProtocolCodec.decodePayload<AttachmentReadyPayload>(envelope.payload)
        val transfer = requireNotNull(database.attachmentTransfers().get(payload.attachmentId)) {
            "Attachment ready references an unknown transfer"
        }
        require(
            payload.filename == transfer.filename &&
                payload.contentType == transfer.contentType &&
                payload.sizeBytes == transfer.sizeBytes &&
                payload.sha256 == transfer.sha256
        ) { "Attachment ready metadata mismatch" }
        check(
            database.attachmentTransfers().updateState(
                attachmentId = transfer.attachmentId,
                transferredBytes = transfer.sizeBytes,
                state = "ready",
                updatedAt = updatedAt,
            ) == 1,
        )
    }

    private suspend fun applySessionList(serverId: String, envelope: WireEnvelope) {
        val payload = ProtocolCodec.decodePayload<SessionListPayload>(envelope.payload)
        payload.items.forEach { item ->
            val title = requireSessionTitle(item.title, "Session list")
            database.conversations().upsert(
                ConversationEntity(
                    sessionId = item.sessionId,
                    serverId = serverId,
                    title = title,
                    updatedAt = parseServerInstant(item.updatedAt, "session.list.updated_at"),
                    remoteKnown = true,
                ),
            )
        }
    }

    private suspend fun applyMessageRowsInTransaction(
        serverId: String,
        sessionId: String,
        items: List<RemoteHistoryMessage>,
        notificationMessageIds: Set<String>,
    ) {
        val current = database.conversations().get(sessionId)
        if (current == null) {
            database.conversations().upsert(
                ConversationEntity(
                    sessionId,
                    serverId,
                    "新对话",
                    System.currentTimeMillis(),
                    remoteKnown = true,
                ),
            )
        } else {
            require(current.serverId == serverId) { "History session belongs to another server" }
            check(database.conversations().markRemoteKnown(sessionId) == 1)
        }
        items.forEach { remote ->
            require(remote.sessionId == sessionId) { "History item session mismatch" }
            require((remote.body == null) != (remote.messageRef == null)) {
                "History item must carry exactly one of body or message_ref"
            }
            require(remote.id.isNotBlank() && remote.id.length <= 512) { "History message id is invalid" }
            require(remote.seq >= 0) { "History message seq is invalid" }
            if (remote.messageRef != null) {
                stageMessageReference(serverId, sessionId, remote, remote.id in notificationMessageIds)
            } else {
                commitMessageRow(serverId, sessionId, remote, remote.id in notificationMessageIds)
            }
        }
    }

    /** 保存整条 Message 下载任务；不完整行不进入任何 UI 投影。 */
    private suspend fun stageMessageReference(
        serverId: String,
        sessionId: String,
        remote: RemoteHistoryMessage,
        notifyWhenReady: Boolean,
    ) {
        val reference = requireNotNull(remote.messageRef)
        require(
            reference.version == 2 &&
                reference.encoding == "utf-8" &&
                reference.mediaType == "application/json" &&
                reference.byteLength > 0 &&
                reference.sha256.matches(Regex("[0-9a-fA-F]{64}"))
        ) { "History message_ref is invalid" }
        val existing = database.messages().get(remote.id)
        val alreadyRestored = existing?.serverSeq == remote.seq &&
            existing.recordedAt.isNotEmpty() && existing.bodyJson != "{}"
        if (existing != null) {
            require(existing.sessionId == sessionId) { "History message identity belongs to another session" }
            require(existing.serverSeq == null || existing.serverSeq == remote.seq) {
                "History message identity changed seq"
            }
        } else {
            database.messages().upsert(
                MessageEntity(
                    messageId = remote.id,
                    clientMessageId = null,
                    sessionId = sessionId,
                    role = "restoring",
                    text = "",
                    deliveryState = "restoring",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        reconcileMessageContentTransfer(
            serverId = serverId,
            sessionId = sessionId,
            messageId = remote.id,
            messageSeq = remote.seq,
            reference = reference,
            alreadyRestored = alreadyRestored,
            notifyWhenReady = notifyWhenReady,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 校验并提交一条完整 Message v2 记录。 */
    private suspend fun commitMessageRow(
        serverId: String,
        sessionId: String,
        remote: RemoteHistoryMessage,
        notifyNewOutput: Boolean,
    ) {
        val body = requireNotNull(remote.body) { "History Message has no body" }
        val timestamp = requireNotNull(remote.timestamp) { "History Message has no timestamp" }
        val author = requireNotNull(remote.author) { "History Message has no author" }
        val source = requireNotNull(remote.source) { "History Message has no source" }
        require(author.isNotBlank() && source.isNotBlank()) { "History Message attribution is invalid" }
        val metadata = remote.metadata ?: JsonObject(emptyMap())
        val completedAt = parseServerInstant(timestamp, "history.page.timestamp")
        val kind = body["kind"]?.jsonPrimitive?.contentOrNull ?: error("History body has no kind")
        val canonical = MessageEntity(
            messageId = remote.id,
            clientMessageId = null,
            sessionId = sessionId,
            role = if (kind == "input") "user" else "assistant",
            text = timelineText(body),
            deliveryState = "complete",
            createdAt = completedAt,
            updatedAt = completedAt,
            serverSeq = remote.seq,
            recordedAt = timestamp,
            author = author,
            source = source,
            bodyJson = body.toString(),
            metadataJson = metadata.toString(),
            attachmentsJson = JsonArray(
                remote.attachments.map {
                    ProtocolCodec.json().encodeToJsonElement(
                        com.akashic.mobile.data.realtime.TimelineAttachmentDescriptor.serializer(),
                        it,
                    )
                },
            ).toString(),
        )
        val prior = database.messages().get(remote.id)
        val isNewServerMessage = prior?.serverSeq != remote.seq
        saveMessage(canonical)
        upsertMessageAttachments(
            serverId = serverId,
            messageId = remote.id,
            descriptors = remote.attachments,
            updatedAt = completedAt,
        )
        database.messages().deleteBlocks(remote.id)
        if (
            notifyNewOutput && kind == "output" &&
            body["finish"]?.jsonPrimitive?.contentOrNull == "complete"
        ) {
            val notifications = database.pendingMessageNotifications()
            val changed = notifications.markReady(
                messageId = remote.id,
                content = timelineText(body),
                hasAttachments = remote.attachments.isNotEmpty(),
                attention = finalMessageAttention(metadata).name,
                createdAt = completedAt,
            )
            if (changed == 0 && isNewServerMessage) {
                notifications.upsert(
                    PendingMessageNotificationEntity(
                        messageId = remote.id,
                        serverId = serverId,
                        sessionId = sessionId,
                        content = timelineText(body),
                        hasAttachments = remote.attachments.isNotEmpty(),
                        attention = finalMessageAttention(metadata).name,
                        ready = true,
                        headSeq = remote.seq,
                        createdAt = completedAt,
                    ),
                )
            }
        }
    }

    private fun requireSessionTitle(title: String, source: String): String {
        val codePoints = title.codePointCount(0, title.length)
        require(title.isNotBlank() && codePoints <= 32) { "$source title is invalid" }
        return title
    }

    /** 把摘要一致的完整 Message JSON 与恢复任务在一个 Room 事务中提交。 */
    suspend fun commitRestoredMessageContent(
        transfer: MessageContentTransferEntity,
        content: String,
        updatedAt: Long,
    ) = database.withTransaction {
        val current = requireNotNull(database.messageContentTransfers().get(transfer.messageId)) {
            "消息正文恢复记录已消失: ${transfer.messageId}"
        }
        require(
            current.messageId == transfer.messageId &&
                current.serverId == transfer.serverId &&
                current.sessionId == transfer.sessionId &&
                current.byteLength == transfer.byteLength &&
                current.sha256 == transfer.sha256 &&
                current.transferredBytes == current.byteLength
        ) {
            "消息正文恢复记录尚未完整"
        }
        val encoded = content.toByteArray(Charsets.UTF_8)
        require(
            encoded.size.toLong() == current.byteLength && sha256(encoded) == current.sha256
        ) { "消息正文恢复内容与 manifest 不一致" }
        val remote = ProtocolCodec.json().decodeFromString<RemoteHistoryMessage>(content)
        require(
            remote.id == current.messageId && remote.sessionId == current.sessionId &&
                remote.seq == current.messageSeq && remote.messageRef == null && remote.body != null
        ) { "下载的 Message 与 manifest 身份不一致" }
        commitMessageRow(current.serverId, current.sessionId, remote, current.notifyWhenReady)
        check(database.messageContentTransfers().delete(current.messageId) == 1) {
            "消息正文恢复记录提交时已消失: ${current.messageId}"
        }
    }

    private suspend fun reconcileMessageContentTransfer(
        serverId: String,
        sessionId: String,
        messageId: String,
        messageSeq: Long,
        reference: com.akashic.mobile.data.realtime.MessageContentRef?,
        alreadyRestored: Boolean,
        notifyWhenReady: Boolean,
        updatedAt: Long,
    ) {
        val dao = database.messageContentTransfers()
        val existing = dao.get(messageId)
        if (reference == null || alreadyRestored) {
            if (existing != null) {
                messageContentStore.delete(existing)
                check(dao.delete(messageId) == 1) { "消息正文恢复记录已消失: $messageId" }
            }
            return
        }
        val sha256 = reference.sha256.lowercase()
        if (existing != null) {
            require(
                existing.serverId == serverId &&
                    existing.sessionId == sessionId &&
                    existing.messageSeq == messageSeq &&
                    existing.byteLength == reference.byteLength &&
                    existing.sha256 == sha256
            ) { "History content reference changed for an existing message" }
            if (existing.state == "failed") {
                check(
                    dao.updateProgress(
                        messageId,
                        existing.transferredBytes,
                        if (existing.transferredBytes == 0L) "pending" else "downloading",
                        updatedAt,
                    ) == 1,
                ) { "消息正文恢复记录已消失: $messageId" }
            }
            if (notifyWhenReady && !existing.notifyWhenReady) {
                check(dao.markNotifyWhenReady(messageId) == 1) {
                    "消息正文恢复通知标记已消失: $messageId"
                }
            }
            return
        }
        dao.upsert(
            MessageContentTransferEntity(
                messageId = messageId,
                serverId = serverId,
                sessionId = sessionId,
                messageSeq = messageSeq,
                byteLength = reference.byteLength,
                sha256 = sha256,
                transferredBytes = 0,
                state = "pending",
                notifyWhenReady = notifyWhenReady,
                updatedAt = updatedAt,
            ),
        )
    }

    private suspend fun upsertConversation(serverId: String, envelope: WireEnvelope, updatedAt: Long) {
        val sessionId = envelope.sessionId ?: payloadText(envelope, "session_id")
        require(!sessionId.isNullOrBlank()) { "Session event has no session_id" }
        val current = database.conversations().get(sessionId)
        database.conversations().upsert(
            ConversationEntity(
                sessionId = sessionId,
                serverId = current?.serverId ?: serverId,
                title = payloadText(envelope, "title") ?: current?.title ?: "新对话",
                updatedAt = updatedAt,
                remoteKnown = true,
            ),
        )
    }

    private suspend fun ensureRemoteConversation(serverId: String, sessionId: String, updatedAt: Long) {
        val current = database.conversations().get(sessionId)
        if (current == null) {
            database.conversations().upsert(
                ConversationEntity(sessionId, serverId, "新对话", updatedAt, remoteKnown = true),
            )
            return
        }
        require(current.serverId == serverId) { "远端事件会话不属于当前电脑" }
        if (!current.remoteKnown) {
            check(database.conversations().markRemoteKnown(sessionId) == 1)
        }
    }

    /** 保存服务端 Message；同一 ID 的本地发送行会被完整记录原位替换。 */
    private suspend fun saveMessage(canonical: MessageEntity) {
        val messages = database.messages()
        val existing = messages.get(canonical.messageId)
        val contentTransfer = database.messageContentTransfers().get(canonical.messageId)
        if (existing != null && existing.role != "restoring" && contentTransfer == null) {
            require(existing.sessionId == canonical.sessionId) {
                "Message identity belongs to another session"
            }
            if (existing.serverSeq != null) {
                require(existing == canonical) { "Saved Message facts changed during replay" }
                return
            }
        }
        if (existing != null && contentTransfer != null && existing.role != "restoring") {
            require(existing.role == canonical.role) { "Restoring Message kind changed" }
        }
        messages.upsert(canonical)
    }

    /** 同一服务端的 artifact 只保存一份缓存，消息链接提供会话授权。 */
    private suspend fun upsertMessageAttachments(
        serverId: String,
        messageId: String,
        descriptors: List<com.akashic.mobile.data.realtime.TimelineAttachmentDescriptor>,
        updatedAt: Long,
    ) {
        val dao = database.mediaAttachments()
        dao.deleteLinks(messageId)
        require(descriptors.groupBy { it.artifactId }.values.all { it.distinct().size == 1 }) { "同一附件描述不一致" }
        val entities = descriptors.distinctBy { it.artifactId }.map { descriptor ->
            descriptor.check()
            val key = artifactCacheId(serverId, descriptor.artifactId)
            val existing = dao.get(key)
            if (existing != null) {
                require(existing.artifactId == descriptor.artifactId && existing.serverId == serverId &&
                    existing.filename == descriptor.filename && existing.contentType == descriptor.mediaType &&
                    existing.kind == descriptor.kind && existing.sizeBytes == descriptor.sizeBytes &&
                    existing.sha256 == descriptor.sha256) { "附件描述与已缓存元数据不一致" }
                existing
            } else {
                MediaAttachmentEntity(
                    cacheId = key, serverId = serverId, artifactId = descriptor.artifactId,
                    kind = descriptor.kind, filename = descriptor.filename, contentType = descriptor.mediaType,
                    sizeBytes = descriptor.sizeBytes, sha256 = descriptor.sha256, transferredBytes = 0,
                    state = if (descriptor.sizeBytes >= AUTO_DOWNLOAD_LIMIT_BYTES) "remote" else "pending",
                    cachePath = mediaCache.cachePath(key), lastAccessedAt = updatedAt, updatedAt = updatedAt,
                )
            }
        }
        dao.upsertAll(entities)
        dao.linkAll(entities.mapIndexed { ordinal, entity -> MessageAttachmentEntity(messageId, entity.cacheId, ordinal) })
    }

    private fun requireFrameId(value: String) {
        require(ProtocolCodec.FRAME_ID.matches(value)) { "Frame id must be a UUIDv7 or ULID" }
    }

    private fun requireControlTurnId(value: String) {
        require(value.isNotBlank() && value.length <= 512) { "Control turn id is invalid" }
    }

    /** 服务端只发布 call_id；envelope.id 兜底保留到旧协议数据滚动出窗口。 */
    private fun toolCallId(envelope: WireEnvelope): String =
        payloadText(envelope, "call_id") ?: requireNotNull(envelope.id)

    private fun payloadText(envelope: WireEnvelope, key: String): String? =
        (envelope.payload[key] as? JsonPrimitive)?.contentOrNull

    private fun payloadText(payload: JsonObject, key: String): String? =
        (payload[key] as? JsonPrimitive)?.contentOrNull

    private fun payloadLong(envelope: WireEnvelope, key: String): Long? =
        envelope.payload[key]?.jsonPrimitive?.longOrNull

    private fun sha256(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val AUTO_DOWNLOAD_LIMIT_BYTES = 10L * 1024 * 1024
        val REMOTE_SESSION_EVENTS = setOf(
            "session.created",
            "session.updated",
            "history.page",
            "turn.started",
        )
        val DELIVERED_MESSAGE_EVENTS = setOf("message.final")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
