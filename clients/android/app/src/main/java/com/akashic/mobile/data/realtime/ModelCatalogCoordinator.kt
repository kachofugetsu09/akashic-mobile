package com.akashic.mobile.data.realtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ModelSelection(
    val runtimeId: String,
    val reasoningEffort: String,
)

data class ModelCatalogState(
    val serverId: String? = null,
    val sessionId: String? = null,
    val generationId: Int? = null,
    val defaultRuntime: String = "",
    val selectedRuntimeId: String = "",
    val selectedReasoningEffort: String = "",
    val runtimes: List<ModelRuntimeSummary> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

class ModelCatalogCoordinator(
    private val send: (String, String) -> String?,
) {
    private val mutableState = MutableStateFlow(ModelCatalogState())
    val state: StateFlow<ModelCatalogState> = mutableState.asStateFlow()
    private var pendingRequestId: String? = null
    private var desiredSessionId: String? = null
    private var localSelectionSessionId: String? = null

    fun onConnectionReady(serverId: String, sessionId: String) {
        if (mutableState.value.serverId != serverId) {
            mutableState.value = ModelCatalogState(serverId = serverId)
            localSelectionSessionId = null
        }
        refresh(sessionId)
    }

    fun onSessionSelected(sessionId: String) {
        if (mutableState.value.serverId == null) return
        localSelectionSessionId = null
        refresh(sessionId)
    }

    fun onDisconnected() {
        pendingRequestId = null
        desiredSessionId = null
        localSelectionSessionId = null
        mutableState.value = ModelCatalogState()
    }

    fun select(runtimeId: String, reasoningEffort: String) {
        val state = mutableState.value
        val sessionId = requireNotNull(state.sessionId) { "模型选择缺少当前会话" }
        val normalizedRuntime = runtimeId.trim()
        val normalizedEffort = reasoningEffort.trim()
        if (normalizedRuntime.isEmpty()) {
            require(normalizedEffort.isEmpty()) { "默认模型不能单独设置思考强度" }
        } else {
            val runtime = requireNotNull(state.runtimes.find { it.id == normalizedRuntime }) {
                "模型已经不可用: $normalizedRuntime"
            }
            require(
                normalizedEffort.isEmpty() || normalizedEffort in runtime.supportedReasoningEfforts,
            ) { "模型 ${runtime.model} 不支持思考强度: $normalizedEffort" }
        }
        localSelectionSessionId = sessionId
        mutableState.value = state.copy(
            selectedRuntimeId = normalizedRuntime,
            selectedReasoningEffort = normalizedEffort,
            errorMessage = null,
        )
    }

    fun selectionFor(sessionId: String): ModelSelection? {
        val state = mutableState.value
        if (state.sessionId != sessionId || state.generationId == null) return null
        return ModelSelection(state.selectedRuntimeId, state.selectedReasoningEffort)
    }

    fun onReply(envelope: WireEnvelope): Boolean {
        if (envelope.id != pendingRequestId) return false
        require(envelope.type in setOf("model.catalog.get.ok", "model.catalog.get.error")) {
            "模型目录 reply 类型不匹配: ${envelope.type}"
        }
        pendingRequestId = null
        val desiredSession = requireNotNull(desiredSessionId) { "模型目录 reply 缺少目标会话" }
        if (envelope.sessionId != desiredSession) {
            refresh(desiredSession)
            return true
        }
        if (envelope.type.endsWith(".error")) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                errorMessage = envelope.payload["message"]?.toString()?.trim('"') ?: "模型目录读取失败",
            )
            return true
        }
        val payload = ProtocolCodec.decodePayload<ModelCatalogPayload>(envelope.payload)
        val remoteSelection = validatedSelection(
            payload.selectedRuntimeId,
            payload.selectedReasoningEffort,
            payload.runtimes,
        )
        val localSelection = validatedSelection(
            mutableState.value.selectedRuntimeId,
            mutableState.value.selectedReasoningEffort,
            payload.runtimes,
        )
        val selected = if (localSelectionSessionId == envelope.sessionId) {
            localSelection
        } else {
            remoteSelection
        }
        mutableState.value = mutableState.value.copy(
            sessionId = requireNotNull(envelope.sessionId),
            generationId = payload.generationId,
            defaultRuntime = payload.defaultRuntime,
            selectedRuntimeId = selected.runtimeId,
            selectedReasoningEffort = selected.reasoningEffort,
            runtimes = payload.runtimes,
            loading = false,
            errorMessage = null,
        )
        return true
    }

    private fun refresh(sessionId: String) {
        desiredSessionId = sessionId
        mutableState.value = mutableState.value.copy(
            sessionId = sessionId,
            loading = true,
            errorMessage = null,
        )
        if (pendingRequestId != null) return
        pendingRequestId = send("model.catalog.get", sessionId)
        if (pendingRequestId == null) {
            mutableState.value = mutableState.value.copy(
                loading = false,
                errorMessage = "模型目录命令未进入 WebSocket 队列",
            )
        }
    }

    private fun validatedSelection(
        runtimeId: String,
        reasoningEffort: String,
        runtimes: List<ModelRuntimeSummary>,
    ): ModelSelection {
        if (runtimeId.isEmpty()) return ModelSelection("", "")
        val runtime = runtimes.find { it.id == runtimeId } ?: return ModelSelection("", "")
        if (reasoningEffort.isNotEmpty() && reasoningEffort !in runtime.supportedReasoningEfforts) {
            return ModelSelection("", "")
        }
        return ModelSelection(runtimeId, reasoningEffort)
    }
}
