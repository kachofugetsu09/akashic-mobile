package com.akashic.mobile.ui.conversation

private const val ASSISTANT_TURN_PREFIX = "assistant:"

/** 一次投影观测的结果；canStopFalse/composerReady 指向刚关闭的同一 turn。 */
internal data class TurnObservationResult(
    val targetTurnId: String?,
    val observed: MessageUi.AssistantTurn?,
    val hasThinking: Boolean,
    val hasAnswer: Boolean,
    val streaming: Boolean,
    val canStopFalse: Boolean,
    val composerReady: Boolean,
)

/**
 * 每会话维护的 turn 投影观测：只针对 coordinator 活动 turn 或刚关闭 turn，
 * 从消息列表尾部定位该 assistant，不做全历史扫描。
 */
internal class TurnProjectionObserver {
    private val pendingClosedTurnBySession = mutableMapOf<String, String>()
    // 每会话记录"见过 streaming"的 turn；active 换新 turn 后旧 turn 的边沿不污染闭包
    private val sawStreamingTurnBySession = mutableMapOf<String, String>()

    /** 计算本次投影观测；composer_ready 只在权威终态投影后且 streaming 边沿落回时成立一次。 */
    fun observe(
        messages: List<MessageUi>,
        sessionId: String?,
        activeTurnId: String?,
    ): TurnObservationResult {
        // 1. 目标 turn：coordinator 活动 turn，或刚关闭且尚未完成观测的 turn
        var closedTurnId: String? = null
        if (sessionId != null) {
            if (activeTurnId != null) {
                pendingClosedTurnBySession[sessionId] = activeTurnId
            } else {
                closedTurnId = pendingClosedTurnBySession[sessionId]
            }
        }
        val targetTurnId = activeTurnId ?: closedTurnId
        val observed = targetTurnId?.let { assistantTurnFromTail(messages, it) }
        val streaming = observed?.isStreaming ?: false
        // 2. streaming 边沿按 turn 记录：active 仍非空时投影终态也不删除 sawStreaming，
        //    换新 turn 后旧 turn 的 streaming 不再对闭包生效
        val previousStreaming = sessionId != null && targetTurnId != null &&
            sawStreamingTurnBySession[sessionId] == targetTurnId

        // 3. 仅 closedTurn 的权威终态投影同次产出 canStopFalse/composerReady 并清理
        val terminalProjected = closedTurnId != null && observed != null && !streaming
        val composerReady = terminalProjected && previousStreaming
        if (terminalProjected) {
            sessionId?.let(pendingClosedTurnBySession::remove)
            if (sessionId != null) sawStreamingTurnBySession.remove(sessionId)
        } else if (streaming && sessionId != null) {
            targetTurnId?.let { sawStreamingTurnBySession[sessionId] = it }
        }
        return TurnObservationResult(
            targetTurnId = targetTurnId,
            observed = observed,
            hasThinking = hasVisibleThinking(observed?.blocks ?: emptyList()),
            hasAnswer = observed?.answer?.isNotEmpty() ?: false,
            streaming = streaming,
            canStopFalse = terminalProjected,
            composerReady = composerReady,
        )
    }
}

/** 从列表尾部定位指定 turn 的 assistant 投影，避免全历史扫描。 */
internal fun assistantTurnFromTail(messages: List<MessageUi>, turnId: String): MessageUi.AssistantTurn? =
    messages.lastOrNull { it is MessageUi.AssistantTurn && it.id == "$ASSISTANT_TURN_PREFIX$turnId" }
        as? MessageUi.AssistantTurn

/** 空 thinking 块没有可见首字。 */
internal fun hasVisibleThinking(blocks: List<ProcessBlockUi>): Boolean =
    blocks.any { it.kind == ProcessBlockKind.THINKING && it.detail.isNotBlank() }
