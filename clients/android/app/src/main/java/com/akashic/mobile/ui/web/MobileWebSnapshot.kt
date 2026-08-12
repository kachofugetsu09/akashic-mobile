package com.akashic.mobile.ui.web

import com.akashic.mobile.ui.conversation.AssistantTurnStatus
import com.akashic.mobile.ui.conversation.CommandUi
import com.akashic.mobile.ui.conversation.ComposerAttachmentState
import com.akashic.mobile.ui.conversation.ComposerAttachmentUi
import com.akashic.mobile.ui.conversation.ConnectionStatusUi
import com.akashic.mobile.ui.conversation.ConversationUiState
import com.akashic.mobile.ui.conversation.MessageAttachmentState
import com.akashic.mobile.ui.conversation.MessageAttachmentUi
import com.akashic.mobile.ui.conversation.MessageUi
import com.akashic.mobile.ui.conversation.ModelCatalogUi
import com.akashic.mobile.ui.conversation.ModelRuntimeUi
import com.akashic.mobile.ui.conversation.MessageDeliveryActionUi
import com.akashic.mobile.ui.conversation.MessageReplyUi
import com.akashic.mobile.ui.conversation.ProcessBlockKind
import com.akashic.mobile.ui.conversation.ProcessBlockState
import com.akashic.mobile.ui.conversation.ProcessBlockUi
import com.akashic.mobile.ui.conversation.PendingMessageUi
import com.akashic.mobile.ui.conversation.ReadingPositionUi
import com.akashic.mobile.ui.conversation.NavigationTargetUi
import com.akashic.mobile.ui.conversation.SessionUi
import com.akashic.mobile.ui.conversation.TransferStatusUi
import com.akashic.mobile.ui.conversation.RuntimeInspectionUi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MobileWebSnapshot(
    val protocolVersion: Int,
    val connection: MobileWebConnection,
    val sessions: List<MobileWebSession>,
    val selectedSessionId: String?,
    val readingPosition: MobileWebReadingPosition?,
    val navigationTarget: MobileWebNavigationTarget?,
    val projectionGeneration: Long,
    val messages: List<MobileWebMessage>,
    val composer: MobileWebComposer,
    val modelCatalog: MobileWebModelCatalog,
    val runtimeInspection: MobileWebRuntimeInspection,
)

@Serializable
data class MobileWebStreamPatch(
    val protocolVersion: Int,
    val projectionGeneration: Long,
    val selectedSessionId: String,
    val messageIndex: Int,
    val messageId: String,
    val searchRevision: Long,
    val durationSeconds: Int? = null,
    val contentAppend: String? = null,
    val thinkingAppend: MobileWebThinkingAppend? = null,
    val message: MobileWebMessage? = null,
    val state: MobileWebStatePatch? = null,
    val clientMessageId: String? = null,
)

@Serializable
data class MobileWebThinkingAppend(
    val blockIndex: Int,
    val blockId: String,
    val delta: String,
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
)

@Serializable
data class MobileWebMessage(
    val id: String,
    val sessionId: String,
    val role: MobileWebRole,
    val content: String,
    val createdAt: Long,
    val searchRevision: Long,
    val replyable: Boolean,
    val reply: MobileWebReply? = null,
    val deliveryLabel: String? = null,
    val deliveryAction: MobileWebDeliveryAction? = null,
    val blocks: List<MobileWebProcessBlock> = emptyList(),
    val streaming: Boolean = false,
    val interrupted: Boolean = false,
    val durationSeconds: Int? = null,
    val attachments: List<MobileWebAttachment> = emptyList(),
)

@Serializable
enum class MobileWebDeliveryAction {
    @SerialName("retry") RETRY,
    @SerialName("verify") VERIFY,
}

@Serializable
data class MobileWebReply(
    val messageId: String,
    val role: String,
    val preview: String,
)

@Serializable
enum class MobileWebRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
}

@Serializable
data class MobileWebProcessBlock(
    val id: String,
    val kind: MobileWebProcessKind,
    val title: String,
    val detail: String,
    val state: MobileWebProcessState,
    val arguments: JsonObject? = null,
    val resultPreview: String? = null,
    val durationMillis: Long? = null,
)

@Serializable
enum class MobileWebProcessKind {
    @SerialName("thinking") THINKING,
    @SerialName("tool") TOOL,
}

@Serializable
enum class MobileWebProcessState {
    @SerialName("completed") COMPLETED,
    @SerialName("running") RUNNING,
    @SerialName("failed") FAILED,
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
    protocolVersion = 8,
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
    messages = messages.map(MessageUi::toMobileWebMessage),
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

/** Build a control-state patch without serializing the unchanged message graph. */
fun ConversationUiState.toMobileWebStatePatch(previous: ConversationUiState): MobileWebStatePatch? {
    if (
        selectedSessionId != previous.selectedSessionId ||
        projectionGeneration != previous.projectionGeneration ||
        messages != previous.messages
    ) {
        return null
    }
    return MobileWebStatePatch(
        protocolVersion = 1,
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

/** 为同一助手 turn 的流式或终态变化生成轻量 WebView patch。 */
fun ConversationUiState.toMobileWebStreamPatch(
    previous: ConversationUiState,
): MobileWebStreamPatch? {
    // 1. 消息投影必须仍属于同一会话 generation
    if (
        selectedSessionId == null ||
        selectedSessionId != previous.selectedSessionId ||
        projectionGeneration != previous.projectionGeneration ||
        messages.size != previous.messages.size
    ) {
        return null
    }

    // 2. 只允许一个现有 streaming assistant message 发生局部变化
    var changedIndex = -1
    for (index in messages.indices) {
        val before = previous.messages[index]
        val after = messages[index]
        if (before == after) continue
        if (changedIndex >= 0 || !after.isSameStreamingTurnUpdate(before)) return null
        changedIndex = index
    }
    if (changedIndex < 0) return null

    // 3. 执行中只允许会话摘要变化；终态随消息一起提交完整控制状态
    val before = previous.messages[changedIndex] as MessageUi.AssistantTurn
    val after = messages[changedIndex] as MessageUi.AssistantTurn

    // 4. 诊断身份字段必须通过协议校验，否则 fail-loud
    after.clientMessageId?.let { clientMessageId ->
        require(FRAME_ID_PATTERN.matches(clientMessageId)) { "Stream patch client message id 无效" }
    }

    val terminalState = if (after.isStreaming) {
        if (previous.copy(sessions = sessions, messages = messages) != this) return null
        null
    } else {
        copy(messages = previous.messages).toMobileWebStatePatch(previous) ?: return null
    }

    // 5. 追加型更新只跨桥发送新增文字；结构或终态变化携带一条完整消息
    val append = after.streamAppendFrom(before)
    return if (after.isStreaming && append != null) {
        MobileWebStreamPatch(
            protocolVersion = 3,
            projectionGeneration = projectionGeneration,
            selectedSessionId = selectedSessionId,
            messageIndex = changedIndex,
            messageId = before.id,
            searchRevision = after.updatedAtMillis,
            durationSeconds = after.durationSeconds,
            contentAppend = append.content,
            thinkingAppend = append.thinking,
            clientMessageId = after.clientMessageId,
        )
    } else {
        MobileWebStreamPatch(
            protocolVersion = 3,
            projectionGeneration = projectionGeneration,
            selectedSessionId = selectedSessionId,
            messageIndex = changedIndex,
            messageId = before.id,
            searchRevision = after.updatedAtMillis,
            durationSeconds = after.durationSeconds,
            message = after.toMobileWebMessage(),
            state = terminalState,
            clientMessageId = after.clientMessageId,
        )
    }
}

internal data class MobileWebTerminalTransition(
    val sessionId: String,
    val turnId: String,
    val clientMessageId: String?,
)

/** 完整 snapshot 同时迁移 user/assistant identity 时，保留终态跨桥观测。 */
internal fun ConversationUiState.terminalTransitionFrom(
    previous: ConversationUiState,
): MobileWebTerminalTransition? {
    // 1. 从同一会话的旧投影定位仍在流式的权威 turn
    if (selectedSessionId == null || selectedSessionId != previous.selectedSessionId) return null
    val before = previous.messages.asReversed().filterIsInstance<MessageUi.AssistantTurn>()
        .firstOrNull {
            it.isStreaming &&
                (it.controlTurnId != null || it.id.startsWith(ASSISTANT_TURN_PREFIX))
        }
        ?: return null
    val beforeTurnId = before.controlTurnId ?: before.id.removePrefix(ASSISTANT_TURN_PREFIX)

    // 2. canonical ID 可变；权威 turn id 优先，客户端发件身份兼容旧投影
    val after = messages.asReversed().filterIsInstance<MessageUi.AssistantTurn>()
        .firstOrNull {
            !it.isStreaming && it.sessionId == before.sessionId && (
                if (it.controlTurnId != null) {
                    it.controlTurnId == beforeTurnId
                } else if (before.clientMessageId != null) {
                    it.clientMessageId == before.clientMessageId
                } else {
                    it.id == before.id
                }
                )
        }
        ?: return null
    return MobileWebTerminalTransition(
        sessionId = before.sessionId,
        turnId = beforeTurnId,
        clientMessageId = after.clientMessageId ?: before.clientMessageId,
    )
}

private fun MessageUi.isSameStreamingTurnUpdate(previous: MessageUi): Boolean {
    if (this !is MessageUi.AssistantTurn || previous !is MessageUi.AssistantTurn) return false
    if (
        !previous.isStreaming ||
        sessionId != previous.sessionId ||
        createdAtMillis != previous.createdAtMillis ||
        (isStreaming && id != previous.id)
    ) {
        return false
    }
    return previous.copy(
        id = id,
        intro = intro,
        answer = answer,
        blocks = blocks,
        status = status,
        durationSeconds = durationSeconds,
        attachments = attachments,
        updatedAtMillis = updatedAtMillis,
    ) == this
}

private data class MobileWebStreamAppend(
    val content: String?,
    val thinking: MobileWebThinkingAppend?,
)

/** 提取同一 streaming turn 相对已投递状态的无损追加量。 */
private fun MessageUi.AssistantTurn.streamAppendFrom(
    previous: MessageUi.AssistantTurn,
): MobileWebStreamAppend? {
    // 1. 回答与 block 结构必须保持追加语义
    if (!answer.startsWith(previous.answer) || blocks.size != previous.blocks.size) return null
    var thinkingAppend: MobileWebThinkingAppend? = null
    for (index in blocks.indices) {
        val before = previous.blocks[index]
        val after = blocks[index]
        if (before == after) continue
        if (
            thinkingAppend != null ||
            before.kind != ProcessBlockKind.THINKING ||
            before.copy(detail = after.detail) != after ||
            !after.detail.startsWith(before.detail)
        ) {
            return null
        }
        thinkingAppend = MobileWebThinkingAppend(
            blockIndex = index,
            blockId = after.id,
            delta = after.detail.removePrefix(before.detail),
        )
    }

    // 2. 纯计时变化没有内容收益，交给完整消息回退
    val contentAppend = answer.removePrefix(previous.answer).ifEmpty { null }
    return if (contentAppend != null || thinkingAppend != null) {
        MobileWebStreamAppend(contentAppend, thinkingAppend)
    } else {
        null
    }
}

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
    MobileWebPendingMessage(messageId, preview, createdAtMillis)

internal fun MessageUi.toMobileWebMessage(): MobileWebMessage = when (this) {
    is MessageUi.User -> MobileWebMessage(
        id = id,
        sessionId = sessionId,
        role = MobileWebRole.USER,
        content = text,
        createdAt = createdAtMillis,
        searchRevision = updatedAtMillis,
        replyable = replyable,
        reply = reply?.toMobileWebReply(),
        deliveryLabel = deliveryLabel,
        deliveryAction = deliveryAction?.toMobileWebDeliveryAction(),
        attachments = attachments.map(MessageAttachmentUi::toMobileWebAttachment),
    )
    is MessageUi.AssistantTurn -> MobileWebMessage(
        id = id,
        sessionId = sessionId,
        role = MobileWebRole.ASSISTANT,
        content = answer,
        createdAt = createdAtMillis,
        searchRevision = updatedAtMillis,
        replyable = status == AssistantTurnStatus.COMPLETE,
        reply = reply?.toMobileWebReply(),
        blocks = blocks.map(ProcessBlockUi::toMobileWebProcessBlock),
        streaming = status == AssistantTurnStatus.STREAMING,
        interrupted = status == AssistantTurnStatus.INTERRUPTED,
        durationSeconds = durationSeconds,
        attachments = attachments.map(MessageAttachmentUi::toMobileWebAttachment),
    )
}

private fun MessageDeliveryActionUi.toMobileWebDeliveryAction() = when (this) {
    MessageDeliveryActionUi.RETRY -> MobileWebDeliveryAction.RETRY
    MessageDeliveryActionUi.VERIFY -> MobileWebDeliveryAction.VERIFY
}

private fun ProcessBlockUi.toMobileWebProcessBlock() = MobileWebProcessBlock(
    id = id,
    kind = when (kind) {
        ProcessBlockKind.THINKING -> MobileWebProcessKind.THINKING
        ProcessBlockKind.TOOL -> MobileWebProcessKind.TOOL
    },
    title = title,
    detail = detail,
    state = when (state) {
        ProcessBlockState.COMPLETED -> MobileWebProcessState.COMPLETED
        ProcessBlockState.RUNNING -> MobileWebProcessState.RUNNING
        ProcessBlockState.FAILED -> MobileWebProcessState.FAILED
    },
    arguments = arguments,
    resultPreview = resultPreview,
    durationMillis = durationMillis,
)

private fun MessageReplyUi.toMobileWebReply() = MobileWebReply(messageId, role, preview)

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

private fun CommandUi.toMobileWebCommand() = MobileWebCommand(command, description)

private fun ConnectionStatusUi.toMobileWebStatus(): MobileWebConnectionStatus = when (this) {
    ConnectionStatusUi.CONNECTING -> MobileWebConnectionStatus.CONNECTING
    ConnectionStatusUi.READY -> MobileWebConnectionStatus.READY
    ConnectionStatusUi.DEGRADED -> MobileWebConnectionStatus.DEGRADED
    ConnectionStatusUi.RECONNECTING -> MobileWebConnectionStatus.RECONNECTING
    ConnectionStatusUi.DISCONNECTED -> MobileWebConnectionStatus.DISCONNECTED
}

private val FRAME_ID_PATTERN = Regex(
    "^(?:[0-9A-HJKMNP-TV-Z]{26}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-" +
        "7[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12})$",
)
