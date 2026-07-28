package com.akashic.mobile.data.realtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RuntimeInspectionState(
    val serverId: String? = null,
    val refreshing: Boolean = false,
    val detailLoading: Boolean = false,
    val snapshotId: String? = null,
    val documents: List<RuntimeDocumentSummary> = emptyList(),
    val jobs: List<SchedulerJobSummary> = emptyList(),
    val plugins: List<RuntimePluginSummary> = emptyList(),
    val skills: List<RuntimeSkillSummary> = emptyList(),
    val mcpServers: List<RuntimeMcpSummary> = emptyList(),
    val detail: RuntimeInspectionDetail? = null,
    val errorMessage: String? = null,
)

data class RuntimeInspectionDetail(
    val kind: RuntimeInspectionDetailKind,
    val key: String,
    val title: String,
    val subtitle: String,
    val markdown: String,
)

enum class RuntimeInspectionDetailKind {
    DOCUMENT,
    MCP,
    SCHEDULE,
}

class RuntimeInspectionCoordinator(
    private val send: (String, JsonObject) -> String?,
) {
    private enum class Request(
        val command: String,
    ) {
        DOCUMENT_LIST("runtime.document.list"),
        DOCUMENT_GET("runtime.document.get"),
        CAPABILITY_LIST("runtime.capability.list"),
        MCP_GET("runtime.mcp.get"),
        JOB_LIST("scheduler.job.list"),
        JOB_GET("scheduler.job.get"),
    }

    private val mutableState = MutableStateFlow(RuntimeInspectionState())
    val state: StateFlow<RuntimeInspectionState> = mutableState.asStateFlow()
    private val pending = mutableMapOf<String, Request>()

    fun onConnectionReady(serverId: String) {
        if (mutableState.value.serverId != serverId) {
            mutableState.value = RuntimeInspectionState(serverId = serverId)
        }
        refresh()
    }

    fun onDisconnected() {
        pending.clear()
        mutableState.value = RuntimeInspectionState()
    }

    fun refresh() {
        if (mutableState.value.serverId == null) return
        if (pending.values.any { it.isList() }) return
        mutableState.value = mutableState.value.copy(
            refreshing = true,
            errorMessage = null,
        )
        send(Request.DOCUMENT_LIST, buildJsonObject {})
        send(Request.CAPABILITY_LIST, buildJsonObject {})
        send(Request.JOB_LIST, buildJsonObject {})
        updateLoading()
    }

    fun openDocument(documentId: String) {
        require(documentId.isNotBlank()) { "运行时文档 ID 不能为空" }
        requestDetail(
            Request.DOCUMENT_GET,
            buildJsonObject { put("document_id", documentId) },
        )
    }

    fun openMcp(ownerId: String, serverName: String) {
        require(ownerId.isNotBlank() && serverName.isNotBlank()) {
            "MCP 身份不能为空"
        }
        requestDetail(
            Request.MCP_GET,
            buildJsonObject {
                put("owner_id", ownerId)
                put("server_name", serverName)
            },
        )
    }

    fun openJob(jobId: String) {
        require(jobId.isNotBlank()) { "定时任务 ID 不能为空" }
        requestDetail(
            Request.JOB_GET,
            buildJsonObject { put("job_id", jobId) },
        )
    }

    fun clearDetail() {
        mutableState.value = mutableState.value.copy(detail = null)
    }

    fun onReply(envelope: WireEnvelope): Boolean {
        val id = envelope.id ?: return false
        val request = pending.remove(id) ?: return false
        require(envelope.type == "${request.command}.ok" || envelope.type == "${request.command}.error") {
            "运行时检查 reply 类型不匹配: ${envelope.type}"
        }
        if (envelope.type.endsWith(".error")) {
            mutableState.value = mutableState.value.copy(
                errorMessage = envelope.payload["message"]?.jsonPrimitive?.content
                    ?: "运行时检查读取失败",
            )
            updateLoading()
            return true
        }

        mutableState.value = when (request) {
            Request.DOCUMENT_LIST -> {
                val payload = ProtocolCodec.decodePayload<RuntimeDocumentListPayload>(
                    envelope.payload,
                )
                mutableState.value.copy(documents = payload.items)
            }
            Request.CAPABILITY_LIST -> {
                val payload = ProtocolCodec.decodePayload<RuntimeCapabilityListPayload>(
                    envelope.payload,
                )
                mutableState.value.copy(
                    snapshotId = payload.snapshotId,
                    plugins = payload.plugins,
                    skills = payload.skills,
                    mcpServers = payload.mcpServers,
                )
            }
            Request.JOB_LIST -> {
                val payload = ProtocolCodec.decodePayload<SchedulerJobListPayload>(
                    envelope.payload,
                )
                mutableState.value.copy(jobs = payload.items)
            }
            Request.DOCUMENT_GET -> {
                val payload = ProtocolCodec.decodePayload<RuntimeDocumentDetail>(
                    envelope.payload,
                )
                mutableState.value.copy(
                    detail = RuntimeInspectionDetail(
                        RuntimeInspectionDetailKind.DOCUMENT,
                        payload.id,
                        payload.title,
                        payload.relativePath,
                        payload.markdown,
                    ),
                )
            }
            Request.MCP_GET -> {
                val payload = ProtocolCodec.decodePayload<RuntimeMcpDetail>(
                    envelope.payload,
                )
                mutableState.value.copy(
                    detail = RuntimeInspectionDetail(
                        RuntimeInspectionDetailKind.MCP,
                        "${payload.ownerId}/${payload.name}",
                        payload.name,
                        "${payload.toolCount} 个工具 · ${payload.ownerId}",
                        payload.markdown,
                    ),
                )
            }
            Request.JOB_GET -> {
                val payload = ProtocolCodec.decodePayload<SchedulerJobDetail>(
                    envelope.payload,
                )
                mutableState.value.copy(
                    detail = RuntimeInspectionDetail(
                        RuntimeInspectionDetailKind.SCHEDULE,
                        payload.id,
                        payload.name ?: "未命名定时任务",
                        payload.fireAt,
                        payload.markdown,
                    ),
                )
            }
        }
        updateLoading()
        return true
    }

    private fun requestDetail(request: Request, payload: JsonObject) {
        if (pending.values.any { !it.isList() }) return
        mutableState.value = mutableState.value.copy(
            detailLoading = true,
            errorMessage = null,
        )
        send(request, payload)
        updateLoading()
    }

    private fun send(request: Request, payload: JsonObject) {
        val id = send(request.command, payload)
        if (id == null) {
            mutableState.value = mutableState.value.copy(
                errorMessage = "运行时检查命令未进入 WebSocket 队列",
            )
            return
        }
        pending[id] = request
    }

    private fun updateLoading() {
        mutableState.value = mutableState.value.copy(
            refreshing = pending.values.any { it.isList() },
            detailLoading = pending.values.any { !it.isList() },
        )
    }

    private fun Request.isList(): Boolean = this in setOf(
        Request.DOCUMENT_LIST,
        Request.CAPABILITY_LIST,
        Request.JOB_LIST,
    )
}
