package com.akashic.mobile.ui.web

import com.akashic.mobile.ui.conversation.CommandUi
import com.akashic.mobile.ui.conversation.ComposerAttachmentState
import com.akashic.mobile.ui.conversation.ComposerAttachmentUi
import com.akashic.mobile.ui.conversation.ConnectionStatusUi
import com.akashic.mobile.ui.conversation.ConversationUiState
import com.akashic.mobile.ui.conversation.MessageAttachmentState
import com.akashic.mobile.ui.conversation.MessageAttachmentUi
import com.akashic.mobile.ui.conversation.ModelCatalogUi
import com.akashic.mobile.ui.conversation.ModelRuntimeUi
import com.akashic.mobile.ui.conversation.MessageDeliveryActionUi
import com.akashic.mobile.ui.conversation.PendingMessageUi
import com.akashic.mobile.ui.conversation.ReadingPositionUi
import com.akashic.mobile.ui.conversation.NavigationTargetUi
import com.akashic.mobile.ui.conversation.SessionUi
import com.akashic.mobile.ui.conversation.TransferStatusUi
import com.akashic.mobile.ui.conversation.TimelineMessageUi
import com.akashic.mobile.ui.conversation.TimelineAttachmentUi
import com.akashic.mobile.ui.conversation.RuntimeInspectionUi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 完整附件 metadata 的 null 是协议事实，快照与增量不能省略它。
internal val mobileWebJson = Json

@Serializable
data class MobileWebSnapshot(
    val protocolVersion: Int,
    val connection: MobileWebConnection,
    val sessions: List<MobileWebSession>,
    val selectedSessionId: String?,
    val readingPosition: MobileWebReadingPosition?,
    val navigationTarget: MobileWebNavigationTarget?,
    val projectionGeneration: Long,
    val messages: List<MobileWebTimelineMessage>,
    val throughSeq: Long,
    val replyStatus: JsonElement,
    val downloads: List<MobileWebDownload>,
    val composer: MobileWebComposer,
    val modelCatalog: MobileWebModelCatalog,
    val runtimeInspection: MobileWebRuntimeInspection,
)

@Serializable
data class MobileWebTimelineMessage(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val timestamp: String,
    val author: String,
    val source: String,
    val body: JsonObject,
    val metadata: JsonObject,
    val attachments: List<MobileWebTimelineAttachment>,
)

@Serializable
data class MobileWebTimelineAttachment(
    @SerialName("artifact_id") val artifactId: String,
    val kind: String,
    val filename: String?,
    @SerialName("media_type") val mediaType: String?,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class MobileWebMessageEvent(
    val protocolVersion: Int,
    val projectionGeneration: Long,
    val event: JsonObject,
)

@Serializable
data class MobileWebStatePatch(
    val protocolVersion: Int,
    val connection: MobileWebConnection,
    val sessions: List<MobileWebSession>,
    val selectedSessionId: String?,
    val readingPosition: MobileWebReadingPosition?,
    val navigationTarget: MobileWebNavigationTarget?,
    val projectionGeneration: Long,
    val downloads: List<MobileWebDownload>,
    val composer: MobileWebComposer,
    val modelCatalog: MobileWebModelCatalog,
    val runtimeInspection: MobileWebRuntimeInspection,
)

@Serializable
data class MobileWebModelCatalog(
    val generationId: Int?,
    val defaultRuntime: String,
    val selectedRuntimeId: String,
    val selectedReasoningEffort: String,
    val runtimes: List<MobileWebModelRuntime>,
    val loading: Boolean,
    val errorMessage: String?,
)

@Serializable
data class MobileWebModelRuntime(
    val id: String,
    val provider: String,
    val model: String,
    val sourceId: String,
    val sourceName: String,
    val reasoningEffort: String,
    val supportedReasoningEfforts: List<String>,
    val roles: List<String>,
    val contextWindow: Int,
    val inputModalities: List<String>,
)

@Serializable
data class MobileWebConnection(
    val label: String,
    val status: MobileWebConnectionStatus,
    val notice: String?,
    val error: String?,
)

@Serializable
enum class MobileWebConnectionStatus {
    @SerialName("connecting") CONNECTING,
    @SerialName("ready") READY,
    @SerialName("degraded") DEGRADED,
    @SerialName("reconnecting") RECONNECTING,
    @SerialName("disconnected") DISCONNECTED,
}

@Serializable
data class MobileWebSession(
    val id: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageAt: Long?,
    val unreadCount: Int,
    val isRunning: Boolean,
    val isAvailable: Boolean,
    val canRemove: Boolean,
)

@Serializable
data class MobileWebReadingPosition(
    val messageId: String,
    val offsetPx: Int,
)

@Serializable
data class MobileWebNavigationTarget(
    val sessionId: String,
    val messageId: String,
)

@Serializable
data class MobileWebPendingMessage(
    val messageId: String,
    val preview: String,
    val createdAt: Long,
    val deliveryLabel: String,
    val deliveryAction: MobileWebDeliveryAction? = null,
)

@Serializable
enum class MobileWebDeliveryAction {
    @SerialName("retry") RETRY,
    @SerialName("verify") VERIFY,
}

@Serializable
data class MobileWebAttachment(
    val id: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val transferredBytes: Long,
    val state: String,
    val canRemove: Boolean = false,
    val contentUrl: String? = null,
)

@Serializable
data class MobileWebDownload(
    val artifactId: String,
    val cacheId: String,
    val state: String,
    val transferredBytes: Long,
    val contentUrl: String? = null,
)

@Serializable
data class MobileWebCommand(
    val command: String,
    val description: String,
)

@Serializable
data class MobileWebRuntimeInspection(
    val refreshing: Boolean,
    val detailLoading: Boolean,
    val snapshotId: String?,
    val documents: List<MobileWebRuntimeDocument>,
    val jobs: List<MobileWebRuntimeJob>,
    val mcpServers: List<MobileWebRuntimeMcp>,
    val pluginCount: Int,
    val skillCount: Int,
    val detail: MobileWebRuntimeDetail?,
    val errorMessage: String?,
)

@Serializable
data class MobileWebRuntimeDocument(
    val id: String,
    val title: String,
    val relativePath: String,
    val description: String,
    val available: Boolean,
)

@Serializable
data class MobileWebRuntimeJob(
    val id: String,
    val name: String?,
    val trigger: String,
    val tier: String,
    val fireAt: String,
    val enabled: Boolean,
)

@Serializable
data class MobileWebRuntimeMcp(
    val ownerId: String,
    val name: String,
    val toolCount: Int,
)

@Serializable
data class MobileWebRuntimeDetail(
    val kind: String,
    val key: String,
    val title: String,
    val subtitle: String,
    val markdown: String,
)

@Serializable
data class MobileWebComposer(
    val draft: MobileWebComposerDraft,
    val attachments: List<MobileWebAttachment>,
    val pendingMessages: List<MobileWebPendingMessage>,
    val transferStatus: MobileWebTransferStatus?,
    val commands: List<MobileWebCommand>,
    val isStreaming: Boolean,
    val isResyncing: Boolean,
    val canResync: Boolean,
    val isStopping: Boolean,
    val canStop: Boolean,
    val canSend: Boolean,
)

@Serializable
data class MobileWebComposerDraft(
    val text: String,
    val replyToMessageId: String?,
    val updatedAt: Long?,
)

@Serializable
data class MobileWebTransferStatus(
    val title: String,
    val detail: String,
    val progressPercent: Int,
    val requiresMeteredApproval: Boolean,
)

/** 把原生持久化投影转换为版本化 WebView 快照。 */
fun ConversationUiState.toMobileWebSnapshot(): MobileWebSnapshot = MobileWebSnapshot(
    protocolVersion = 10,
    connection = MobileWebConnection(
        label = connectionLabel,
        status = connectionStatus.toMobileWebStatus(),
        notice = connectionNotice,
        error = errorNotice,
    ),
    sessions = sessions.map(SessionUi::toMobileWebSession),
    selectedSessionId = selectedSessionId,
    readingPosition = readingPosition?.toMobileWebReadingPosition(),
    navigationTarget = navigationTarget?.toMobileWebNavigationTarget(),
    projectionGeneration = projectionGeneration,
    messages = timelineMessages.map(TimelineMessageUi::toMobileWebTimelineMessage),
    throughSeq = timelineMessages.lastOrNull()?.seq ?: -1,
    replyStatus = replyStatus ?: JsonNull,
    downloads = downloads.mapNotNull(MessageAttachmentUi::toMobileWebDownload),
    composer = MobileWebComposer(
        draft = MobileWebComposerDraft(
            text = composerDraft.text,
            replyToMessageId = composerDraft.replyToMessageId,
            updatedAt = composerDraft.updatedAt,
        ),
        attachments = attachments.map(ComposerAttachmentUi::toMobileWebAttachment),
        pendingMessages = pendingMessages.map(PendingMessageUi::toMobileWebPendingMessage),
        transferStatus = transferStatus?.toMobileWebTransferStatus(),
        commands = commands.map(CommandUi::toMobileWebCommand),
        isStreaming = isStreaming,
        isResyncing = isResyncing,
        canResync = canResync,
        isStopping = isStopping,
        canStop = canStop,
        canSend = canSend,
    ),
    modelCatalog = modelCatalog.toMobileWebModelCatalog(),
    runtimeInspection = runtimeInspection.toMobileWebRuntimeInspection(),
)

/** 已提交前缀复用原对象；只把新尾部和变化的回复状态送入 WebUI。 */
fun ConversationUiState.toMobileWebMessageEvents(previous: ConversationUiState): List<MobileWebMessageEvent>? {
    // 1. 切会话、重建和长消息补回历史中部需要完整快照。
    if (selectedSessionId == null || selectedSessionId != previous.selectedSessionId ||
        projectionGeneration != previous.projectionGeneration
    ) return null
    val current = timelineMessages
    val before = previous.timelineMessages
    if (current !== before && (current.size < before.size ||
            before.indices.any { current[it] != before[it] })
    ) return null

    // 2. 状态变化不遍历或序列化历史；append 的游标来自已交付前缀。
    return buildList {
        if (current.size > before.size) {
            add(MobileWebMessageEvent(1, projectionGeneration, buildJsonObject {
                put("type", "messages.appended")
                put("version", 2)
                put("session_id", selectedSessionId)
                put("after_seq", before.lastOrNull()?.seq ?: -1)
                put("through_seq", current.last().seq)
                put("next_after_seq", current.last().seq)
                put("has_more", false)
                put("items", JsonArray(current.subList(before.size, current.size).map {
                    Json.encodeToJsonElement(MobileWebTimelineMessage.serializer(), it.toMobileWebTimelineMessage())
                }))
            }))
        }
        if (replyStatus != previous.replyStatus) add(requireNotNull(toMobileWebReplyEvent()))
    }
}

/** 断线或等待订阅时显式清除临时草稿，不伪造已提交 Message。 */
fun ConversationUiState.toMobileWebReplyEvent(): MobileWebMessageEvent? {
    val sessionId = selectedSessionId ?: return null
    return MobileWebMessageEvent(1, projectionGeneration, replyStatus ?: buildJsonObject {
        put("type", "reply.status")
        put("version", 2)
        put("session_id", sessionId)
        put("snapshot_id", JsonNull)
        put("available", false)
        put("items", JsonArray(emptyList()))
    })
}

/** 只发布变化的控制字段；消息与回复状态沿独立消息事件发送。 */
fun ConversationUiState.toMobileWebStatePatch(previous: ConversationUiState): MobileWebStatePatch? {
    if (copy(timelineMessages = emptyList(), replyStatus = null) ==
        previous.copy(timelineMessages = emptyList(), replyStatus = null)
    ) return null
    return MobileWebStatePatch(
        protocolVersion = 2,
        connection = MobileWebConnection(
            label = connectionLabel,
            status = connectionStatus.toMobileWebStatus(),
            notice = connectionNotice,
            error = errorNotice,
        ),
        sessions = sessions.map(SessionUi::toMobileWebSession),
        selectedSessionId = selectedSessionId,
        readingPosition = readingPosition?.toMobileWebReadingPosition(),
        navigationTarget = navigationTarget?.toMobileWebNavigationTarget(),
        projectionGeneration = projectionGeneration,
        downloads = downloads.mapNotNull(MessageAttachmentUi::toMobileWebDownload),
        composer = MobileWebComposer(
            draft = MobileWebComposerDraft(
                text = composerDraft.text,
                replyToMessageId = composerDraft.replyToMessageId,
                updatedAt = composerDraft.updatedAt,
            ),
            attachments = attachments.map(ComposerAttachmentUi::toMobileWebAttachment),
            pendingMessages = pendingMessages.map(PendingMessageUi::toMobileWebPendingMessage),
            transferStatus = transferStatus?.toMobileWebTransferStatus(),
            commands = commands.map(CommandUi::toMobileWebCommand),
            isStreaming = isStreaming,
            isResyncing = isResyncing,
            canResync = canResync,
            isStopping = isStopping,
            canStop = canStop,
            canSend = canSend,
        ),
        modelCatalog = modelCatalog.toMobileWebModelCatalog(),
        runtimeInspection = runtimeInspection.toMobileWebRuntimeInspection(),
    )
}

private fun ModelCatalogUi.toMobileWebModelCatalog() = MobileWebModelCatalog(
    generationId = generationId,
    defaultRuntime = defaultRuntime,
    selectedRuntimeId = selectedRuntimeId,
    selectedReasoningEffort = selectedReasoningEffort,
    runtimes = runtimes.map(ModelRuntimeUi::toMobileWebModelRuntime),
    loading = loading,
    errorMessage = errorMessage,
)

private fun ModelRuntimeUi.toMobileWebModelRuntime() = MobileWebModelRuntime(
    id = id,
    provider = provider,
    model = model,
    sourceId = sourceId,
    sourceName = sourceName,
    reasoningEffort = reasoningEffort,
    supportedReasoningEfforts = supportedReasoningEfforts,
    roles = roles,
    contextWindow = contextWindow,
    inputModalities = inputModalities,
)

private fun RuntimeInspectionUi.toMobileWebRuntimeInspection() =
    MobileWebRuntimeInspection(
        refreshing = refreshing,
        detailLoading = detailLoading,
        snapshotId = snapshotId,
        documents = documents.map {
            MobileWebRuntimeDocument(
                it.id,
                it.title,
                it.relativePath,
                it.description,
                it.available,
            )
        },
        jobs = jobs.map {
            MobileWebRuntimeJob(
                it.id,
                it.name,
                it.trigger,
                it.tier,
                it.fireAt,
                it.enabled,
            )
        },
        mcpServers = mcpServers.map {
            MobileWebRuntimeMcp(it.ownerId, it.name, it.toolCount)
        },
        pluginCount = pluginCount,
        skillCount = skillCount,
        detail = detail?.let {
            MobileWebRuntimeDetail(
                it.kind,
                it.key,
                it.title,
                it.subtitle,
                it.markdown,
            )
        },
        errorMessage = errorMessage,
    )

private fun TransferStatusUi.toMobileWebTransferStatus() = MobileWebTransferStatus(
    title = title,
    detail = detail,
    progressPercent = progressPercent,
    requiresMeteredApproval = requiresMeteredApproval,
)

private fun SessionUi.toMobileWebSession() = MobileWebSession(
    id = sessionId,
    title = title,
    lastMessagePreview = lastMessagePreview,
    lastMessageAt = lastMessageAtMillis,
    unreadCount = unreadCount,
    isRunning = isRunning,
    isAvailable = isAvailable,
    canRemove = canRemove,
)

private fun ReadingPositionUi.toMobileWebReadingPosition() =
    MobileWebReadingPosition(messageId, offsetPx)

private fun NavigationTargetUi.toMobileWebNavigationTarget() =
    MobileWebNavigationTarget(sessionId, messageId)

private fun PendingMessageUi.toMobileWebPendingMessage() =
    MobileWebPendingMessage(
        messageId,
        preview,
        createdAtMillis,
        deliveryLabel,
        deliveryAction?.toMobileWebDeliveryAction(),
    )

private fun TimelineMessageUi.toMobileWebTimelineMessage() = MobileWebTimelineMessage(
    id = id,
    sessionId = sessionId,
    seq = seq,
    timestamp = timestamp,
    author = author,
    source = source,
    body = body,
    metadata = metadata,
    attachments = attachments.map(TimelineAttachmentUi::toMobileWebTimelineAttachment),
)

private fun TimelineAttachmentUi.toMobileWebTimelineAttachment() = MobileWebTimelineAttachment(
    artifactId = artifactId,
    kind = kind,
    filename = filename,
    mediaType = mediaType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
)

private fun MessageDeliveryActionUi.toMobileWebDeliveryAction() = when (this) {
    MessageDeliveryActionUi.RETRY -> MobileWebDeliveryAction.RETRY
    MessageDeliveryActionUi.VERIFY -> MobileWebDeliveryAction.VERIFY
}

private fun ComposerAttachmentUi.toMobileWebAttachment() = MobileWebAttachment(
    id = id,
    filename = filename,
    contentType = contentType,
    sizeBytes = sizeBytes,
    transferredBytes = transferredBytes,
    state = when (state) {
        ComposerAttachmentState.WAITING_FOR_CONNECTION -> "waiting"
        ComposerAttachmentState.WAITING_FOR_METERED_APPROVAL -> "metered_paused"
        ComposerAttachmentState.UPLOADING -> "uploading"
        ComposerAttachmentState.READY -> "ready"
        ComposerAttachmentState.FAILED -> "failed"
    },
    canRemove = canRemove,
)

private fun MessageAttachmentUi.toMobileWebAttachment() = MobileWebAttachment(
    id = id,
    filename = filename,
    contentType = contentType,
    sizeBytes = sizeBytes,
    transferredBytes = transferredBytes,
    state = when (state) {
        MessageAttachmentState.REMOTE -> "remote"
        MessageAttachmentState.PENDING -> "pending"
        MessageAttachmentState.DOWNLOADING -> "downloading"
        MessageAttachmentState.CACHED -> "cached"
        MessageAttachmentState.FAILED -> "failed"
        MessageAttachmentState.EVICTED -> "evicted"
    },
    contentUrl = if (state == MessageAttachmentState.CACHED) {
        mobileMediaResourceUrl(id, filename)
    } else {
        null
    },
)

private fun MessageAttachmentUi.toMobileWebDownload(): MobileWebDownload? {
    val artifact = artifactId ?: return null
    return MobileWebDownload(
    artifactId = artifact,
    cacheId = id,
    state = when (state) {
        MessageAttachmentState.REMOTE -> "remote"
        MessageAttachmentState.PENDING -> "pending"
        MessageAttachmentState.DOWNLOADING -> "downloading"
        MessageAttachmentState.CACHED -> "cached"
        MessageAttachmentState.FAILED -> "failed"
        MessageAttachmentState.EVICTED -> "evicted"
    },
    transferredBytes = transferredBytes,
    contentUrl = if (state == MessageAttachmentState.CACHED) {
        mobileMediaResourceUrl(id, filename)
    } else {
        null
    },
)

}

private fun CommandUi.toMobileWebCommand() = MobileWebCommand(command, description)

private fun ConnectionStatusUi.toMobileWebStatus(): MobileWebConnectionStatus = when (this) {
    ConnectionStatusUi.CONNECTING -> MobileWebConnectionStatus.CONNECTING
    ConnectionStatusUi.READY -> MobileWebConnectionStatus.READY
    ConnectionStatusUi.DEGRADED -> MobileWebConnectionStatus.DEGRADED
    ConnectionStatusUi.RECONNECTING -> MobileWebConnectionStatus.RECONNECTING
    ConnectionStatusUi.DISCONNECTED -> MobileWebConnectionStatus.DISCONNECTED
}
