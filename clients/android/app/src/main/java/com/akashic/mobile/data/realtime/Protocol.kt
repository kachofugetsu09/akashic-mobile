package com.akashic.mobile.data.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

const val WIRE_PROTOCOL_VERSION = 1
const val MAX_JSON_FRAME_BYTES = 256 * 1024

/** 声明后才能接收 turn.output.completed；旧客户端不声明即只收权威 terminal。 */
const val TURN_OUTPUT_COMPLETED_CAPABILITY = "turn-output-completed-v1"

/** 在 kotlinx.serialization 静默保留最后一个值前拒绝重复对象成员。 */
internal fun requireNoDuplicateJsonKeys(text: String) {
    JsonDuplicateKeyScanner(text).scan()
}

private class JsonDuplicateKeyScanner(private val text: String) {
    private var index = 0

    fun scan() {
        skipWhitespace()
        parseValue()
        skipWhitespace()
        require(index == text.length) { "JSON trailing data" }
    }

    private fun parseValue() {
        skipWhitespace()
        require(index < text.length) { "JSON value is missing" }
        when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            '-', in '0'..'9' -> parseNumber()
            else -> throw IllegalArgumentException("JSON value is invalid")
        }
    }

    private fun parseObject() {
        index += 1
        skipWhitespace()
        val keys = HashSet<String>()
        if (take('}')) return
        while (true) {
            skipWhitespace()
            val key = parseString()
            require(keys.add(key)) { "JSON duplicate object key: $key" }
            skipWhitespace()
            require(take(':')) { "JSON object colon is missing" }
            parseValue()
            skipWhitespace()
            if (take('}')) return
            require(take(',')) { "JSON object comma is missing" }
        }
    }

    private fun parseArray() {
        index += 1
        skipWhitespace()
        if (take(']')) return
        while (true) {
            parseValue()
            skipWhitespace()
            if (take(']')) return
            require(take(',')) { "JSON array comma is missing" }
        }
    }

    private fun parseString(): String {
        require(take('"')) { "JSON string is missing" }
        val start = index - 1
        while (index < text.length) {
            when (val char = text[index++]) {
                '"' -> {
                    val encoded = text.substring(start, index)
                    return ProtocolCodec.json().decodeFromString<String>(encoded)
                }
                '\\' -> {
                    require(index < text.length) { "JSON escape is truncated" }
                    if (text[index++] == 'u') {
                        require(index + 4 <= text.length) { "JSON unicode escape is truncated" }
                        repeat(4) {
                            require(text[index++].digitToIntOrNull(16) != null) {
                                "JSON unicode escape is invalid"
                            }
                        }
                    }
                }
                else -> require(char.code >= 0x20) { "JSON string control character" }
            }
        }
        throw IllegalArgumentException("JSON string is unterminated")
    }

    private fun parseLiteral(literal: String) {
        require(text.regionMatches(index, literal, 0, literal.length)) { "JSON literal is invalid" }
        index += literal.length
    }

    private fun parseNumber() {
        val start = index
        if (take('-')) Unit
        if (take('0')) Unit else {
            require(index < text.length && text[index] in '1'..'9') { "JSON number is invalid" }
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (take('.')) {
            require(index < text.length && text[index].isDigit()) { "JSON number fraction is invalid" }
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (index < text.length && text[index] in "eE") {
            index += 1
            if (index < text.length && text[index] in "+-") index += 1
            require(index < text.length && text[index].isDigit()) { "JSON number exponent is invalid" }
            while (index < text.length && text[index].isDigit()) index += 1
        }
        require(index > start) { "JSON number is missing" }
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    private fun take(expected: Char): Boolean =
        if (index < text.length && text[index] == expected) {
            index += 1
            true
        } else {
            false
        }
}

@Serializable
enum class WireKind {
    @SerialName("command")
    COMMAND,

    @SerialName("reply")
    REPLY,

    @SerialName("event")
    EVENT,

    @SerialName("ack")
    ACK,

    @SerialName("control")
    CONTROL,
}

@Serializable
data class WireEnvelope(
    val v: Int,
    val kind: WireKind,
    val type: String,
    val id: String? = null,
    @SerialName("connection_epoch")
    val connectionEpoch: Long? = null,
    @SerialName("event_seq")
    val eventSeq: Long? = null,
    @SerialName("session_id")
    val sessionId: String? = null,
    @SerialName("turn_id")
    val turnId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class MessageReplyReference(
    @SerialName("message_id")
    val messageId: String,
)

@Serializable
data class MessageSendPayload(
    @SerialName("message_log_version")
    val messageLogVersion: Int = 2,
    @SerialName("client_message_id")
    val clientMessageId: String,
    @SerialName("retry_of_client_message_id")
    val retryOfClientMessageId: String? = null,
    @SerialName("session_id")
    val sessionId: String,
    val text: String,
    @SerialName("media_refs")
    val mediaRefs: List<String>,
    @SerialName("client_created_at")
    val clientCreatedAt: String,
    @SerialName("reply_to")
    val replyTo: MessageReplyReference? = null,
    @SerialName("model_runtime_id")
    val modelRuntimeId: String? = null,
    @SerialName("model_reasoning_effort")
    val modelReasoningEffort: String? = null,
)

@Serializable
data class ModelCatalogPayload(
    @SerialName("generation_id") val generationId: Int,
    @SerialName("default_runtime") val defaultRuntime: String,
    @SerialName("selected_runtime_id") val selectedRuntimeId: String,
    @SerialName("selected_reasoning_effort") val selectedReasoningEffort: String,
    val runtimes: List<ModelRuntimeSummary>,
)

@Serializable
data class ModelRuntimeSummary(
    val id: String,
    val provider: String,
    val model: String,
    @SerialName("sourceId") val sourceId: String,
    @SerialName("sourceName") val sourceName: String,
    @SerialName("reasoningEffort") val reasoningEffort: String,
    @SerialName("supportedReasoningEfforts") val supportedReasoningEfforts: List<String>,
    val roles: List<String>,
    @SerialName("contextWindow") val contextWindow: Int,
    @SerialName("inputModalities") val inputModalities: List<String>,
)

@Serializable
data class AttachmentBeginPayload(
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class AttachmentBeginReplyPayload(
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("next_offset") val nextOffset: Long,
    @SerialName("chunk_size") val chunkSize: Int,
    val state: String,
)

@Serializable
data class AttachmentProgressPayload(
    @SerialName("attachment_id") val attachmentId: String,
    @SerialName("transferred_bytes") val transferredBytes: Long,
    @SerialName("size_bytes") val sizeBytes: Long,
)

@Serializable
data class AttachmentReadyPayload(
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class AttachmentFinishPayload(
    @SerialName("attachment_id") val attachmentId: String,
)

@Serializable
data class AttachmentDescriptor(
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class AttachmentDownloadPayload(
    @SerialName("message_id") val messageId: String,
    @SerialName("artifact_id") val artifactId: String,
    val offset: Long,
)

@Serializable
data class AttachmentDownloadReplyPayload(
    @SerialName("artifact_id") val artifactId: String,
    val kind: String,
    val filename: String?,
    @SerialName("media_type") val mediaType: String?,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    val offset: Long,
    @SerialName("next_offset") val nextOffset: Long,
    val complete: Boolean,
)

@Serializable
data class EventAckPayload(
    @SerialName("through_event_seq")
    val throughEventSeq: Long,
)

@Serializable
data class AuthAcceptedPayload(
    @SerialName("connection_epoch")
    val connectionEpoch: Long,
    @SerialName("device_id")
    val deviceId: String,
)

@Serializable
data class ResumePayload(
    @SerialName("last_ack")
    val lastAck: Long,
    @SerialName("active_turns")
    val activeTurns: List<String>,
)

@Serializable
data class PairPendingPayload(
    @SerialName("pairing_id") val pairingId: String,
    @SerialName("confirmation_code") val confirmationCode: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class PairAcceptedPayload(
    @SerialName("pairing_id") val pairingId: String,
    @SerialName("device_id") val deviceId: String,
)

@Serializable
data class SessionListPayload(
    val items: List<RemoteSessionSummary>,
    val version: Int,
    val total: Int,
    @SerialName("next_cursor") val nextCursor: SessionListCursor? = null,
)

@Serializable
data class SessionListCursor(
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class MessageSendOkPayload(
    val accepted: Boolean,
    @SerialName("client_message_id") val clientMessageId: String,
)

@Serializable
data class CommandListPayload(
    val items: List<RemoteCommandItem>,
)

@Serializable
data class RemoteCommandItem(
    val command: String,
    val description: String,
)

@Serializable
data class RuntimeDocumentListPayload(
    val items: List<RuntimeDocumentSummary>,
)

@Serializable
data class RuntimeDocumentSummary(
    val id: String,
    val title: String,
    @SerialName("relative_path") val relativePath: String,
    val group: String,
    val description: String,
    val available: Boolean,
)

@Serializable
data class RuntimeDocumentDetail(
    val id: String,
    val title: String,
    @SerialName("relative_path") val relativePath: String,
    val group: String,
    val description: String,
    val available: Boolean,
    val markdown: String,
)

@Serializable
data class SchedulerJobListPayload(
    val items: List<SchedulerJobSummary>,
)

@Serializable
data class SchedulerJobSummary(
    val id: String,
    val name: String? = null,
    val trigger: String,
    val tier: String,
    @SerialName("fire_at") val fireAt: String,
    val timezone: String,
    val enabled: Boolean,
    @SerialName("run_count") val runCount: Int,
)

@Serializable
data class SchedulerJobDetail(
    val id: String,
    val name: String? = null,
    val trigger: String,
    val tier: String,
    @SerialName("fire_at") val fireAt: String,
    val timezone: String,
    val enabled: Boolean,
    @SerialName("run_count") val runCount: Int,
    val markdown: String,
)

@Serializable
data class RuntimeCapabilityListPayload(
    @SerialName("snapshot_id") val snapshotId: String,
    val plugins: List<RuntimePluginSummary>,
    val skills: List<RuntimeSkillSummary>,
    @SerialName("mcp_servers") val mcpServers: List<RuntimeMcpSummary>,
)

@Serializable
data class RuntimePluginSummary(
    val id: String,
    val revision: String,
    @SerialName("generation_id") val generationId: String,
    @SerialName("api_version") val apiVersion: Int? = null,
    val composition: RuntimePluginComposition? = null,
)

@Serializable
data class RuntimePluginComposition(
    val ready: Boolean,
    @SerialName("topology_identity") val topologyIdentity: String,
    @SerialName("composition_revision") val compositionRevision: Int,
    val fibers: List<RuntimePluginFiber>,
    val health: List<RuntimePluginHealth>,
    @SerialName("incident_count") val incidentCount: Long,
    @SerialName("recent_incidents") val recentIncidents: List<RuntimePluginIncident>,
    @SerialName("incident_overflowed") val incidentOverflowed: Boolean,
)

@Serializable
data class RuntimePluginFiber(
    val name: String,
    val parent: String?,
    val state: String,
    val required: Boolean,
    @SerialName("static_active") val staticActive: Boolean,
    val dependencies: List<String>,
    @SerialName("missing_services") val missingServices: List<String>,
    val error: String?,
)

@Serializable
data class RuntimePluginHealth(
    val owner: String,
    val name: String,
    val required: Boolean,
    val healthy: Boolean,
    val reason: String?,
)

@Serializable
data class RuntimePluginIncident(
    val sequence: Long,
    val owner: String,
    val kind: String,
    val message: String,
    @SerialName("error_type") val errorType: String?,
)

@Serializable
data class RuntimeSkillSummary(
    val name: String,
    @SerialName("display_name") val displayName: String,
    val description: String,
    val source: String,
    @SerialName("source_id") val sourceId: String,
    val available: Boolean,
    val missing: String,
)

@Serializable
data class RuntimeMcpSummary(
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("tool_count") val toolCount: Int,
    val tools: List<RuntimeMcpToolSummary>,
)

@Serializable
data class RuntimeMcpToolSummary(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
data class RuntimeMcpDetail(
    @SerialName("owner_id") val ownerId: String,
    val name: String,
    @SerialName("tool_count") val toolCount: Int,
    val tools: List<RuntimeMcpToolDetail>,
    val markdown: String,
)

@Serializable
data class RuntimeMcpToolDetail(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
data class MobileUiCatalogPayload(
    @SerialName("catalog_revision") val catalogRevision: String,
    val items: List<MobileUiCatalogItem>,
)

@Serializable
data class MobileUiNavigationPayload(
    val label: String,
    val description: String,
)

@Serializable
data class MobileUiCatalogItem(
    val id: String,
    val revision: String,
    @SerialName("module_sha256") val moduleSha256: String,
    @SerialName("module_bytes") val moduleBytes: Int,
    @SerialName("stylesheet_sha256") val stylesheetSha256: String? = null,
    @SerialName("stylesheet_bytes") val stylesheetBytes: Int = 0,
    val navigation: MobileUiNavigationPayload? = null,
    val slots: List<String> = emptyList(),
)

@Serializable
data class MobileUiAssetPayload(
    @SerialName("plugin_id") val pluginId: String,
    @SerialName("plugin_revision") val pluginRevision: String,
    val kind: String,
    val sha256: String,
    val content: String,
)

@Serializable
data class RemoteSessionSummary(
    @SerialName("session_id") val sessionId: String,
    val title: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("head_seq") val headSeq: Long,
) {
    val snapshotMaxSeq: Long get() = headSeq
}

@Serializable
data class HistoryPagePayload(
    val items: List<RemoteHistoryMessage>,
    val version: Int,
    @SerialName("after_seq") val afterSeq: Long,
    @SerialName("next_after_seq") val nextAfterSeq: Long,
    @SerialName("through_seq") val throughSeq: Long,
    @SerialName("has_more") val hasMore: Boolean,
    val direction: String = "forward",
    @SerialName("before_seq") val beforeSeq: Long? = null,
    @SerialName("next_before_seq") val nextBeforeSeq: Long? = null,
    @SerialName("around_id") val aroundId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)

/** 旧缓存和新网络表示共享同一个展示合同，原 part 下标保持不变。 */
fun messageDisplayBody(body: JsonObject): JsonObject {
    if (body.getValue("kind").jsonPrimitive.content == "control") return body
    val parts = body.getValue("parts").jsonArray
    val displayParts = parts.map { value ->
        val part = value.jsonObject
        val kind = part["kind"]?.jsonPrimitive?.contentOrNull
        if (kind in setOf("history.provenance", "history.record", "history.turn_input")) {
            JsonObject(mapOf("kind" to requireNotNull(part["kind"]), "display" to JsonPrimitive("unavailable")))
        } else value
    }
    return JsonObject(body + ("parts" to JsonArray(displayParts)))
}

/** 分页边界校验在入库前完成，ACK 不会越过未持久接收的范围。 */
internal fun checkHistoryPage(page: HistoryPagePayload) {
    require(page.version == 2 && page.direction in setOf("forward", "backward")) { "History page version or direction is invalid" }
    require(page.afterSeq >= -1 && page.nextAfterSeq in page.afterSeq..page.throughSeq) { "History page range is invalid" }
    var previous = page.afterSeq
    page.items.forEach { row ->
        require(row.seq > previous && row.seq <= page.nextAfterSeq) { "History page items are not ordered within its range" }
        previous = row.seq
    }
    if (page.direction == "backward") {
        val before = requireNotNull(page.beforeSeq) { "Backward page has no before_seq" }
        val next = requireNotNull(page.nextBeforeSeq) { "Backward page has no next_before_seq" }
        require(before in 0..(page.throughSeq + 1) && page.nextAfterSeq == before - 1) { "Backward page upper boundary is invalid" }
        require(next == (page.items.firstOrNull()?.seq ?: before)) { "Backward page cursor is invalid" }
        require(page.afterSeq == if (page.hasMore) next - 1 else -1L) { "Backward page lower boundary is invalid" }
        require(!page.hasMore || next < before) { "Backward page did not advance" }
    } else {
        require(!page.hasMore || page.nextAfterSeq > page.afterSeq) { "Forward page did not advance" }
        require(page.hasMore || page.nextAfterSeq == page.throughSeq) { "Forward page did not reach its boundary" }
    }
}

@Serializable
data class MessagesAppendedPayload(
    val type: String,
    val version: Int,
    @SerialName("session_id") val sessionId: String,
    val items: List<RemoteHistoryMessage>,
    @SerialName("after_seq") val afterSeq: Long,
    @SerialName("next_after_seq") val nextAfterSeq: Long,
    @SerialName("through_seq") val throughSeq: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class MessageContentRef(
    val version: Int,
    val encoding: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("byte_length") val byteLength: Long,
    val sha256: String,
    @SerialName("display_only") val displayOnly: Boolean = false,
)

@Serializable
data class AttachmentAvailabilityError(
    val code: String,
    val message: String,
)

@Serializable
data class RemoteHistoryMessage(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Long,
    val timestamp: String? = null,
    val author: String? = null,
    val source: String? = null,
    val body: JsonObject? = null,
    val metadata: JsonObject? = null,
    @SerialName("message_ref") val messageRef: MessageContentRef? = null,
    val attachments: List<TimelineAttachmentDescriptor> = emptyList(),
)

@Serializable
data class TimelineAttachmentDescriptor(
    @SerialName("artifact_id") val artifactId: String,
    val kind: String,
    val filename: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
) {
    /** 只在远端附件进入本地缓存的边界校验 Core 合同。 */
    fun check() {
        require(ProtocolCodec.ARTIFACT_ID.matches(artifactId)) { "Artifact id is invalid" }
        require(kind == "file" || kind == "image") { "Artifact kind is invalid" }
        require(sizeBytes >= 0) { "Artifact size is invalid" }
        require(Regex("^[0-9a-f]{64}$").matches(sha256)) { "Artifact digest is invalid" }
        filename?.let { name ->
            require(name.isNotBlank() && name == name.trim() && name.codePointCount(0, name.length) <= 255 &&
                '/' !in name && '\\' !in name && name.none { it.code < 32 || it.code == 127 }) { "Artifact filename is invalid" }
        }
        mediaType?.let { require(it.length <= 255 && Regex("^[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+$").matches(it)) { "Artifact media type is invalid" } }
    }
}


@Serializable
data class SessionFollowPayload(
    @SerialName("display_only") val displayOnly: Boolean = true,
    @SerialName("message_log_version") val messageLogVersion: Int = 2,
    @SerialName("after_seq") val afterSeq: Long,
)

@Serializable
data class MessageContentPreparePayload(
    @SerialName("message_log_version") val messageLogVersion: Int = 2,
    @SerialName("message_id") val messageId: String,
    @SerialName("byte_length") val byteLength: Long,
    val sha256: String,
)

@Serializable
data class MessageContentGrantPayload(
    val version: Int,
    @SerialName("message_id") val messageId: String,
    @SerialName("byte_length") val byteLength: Long,
    val sha256: String,
    val encoding: String,
    @SerialName("media_type") val mediaType: String,
    val path: String,
    val ticket: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class ProtocolErrorPayload(
    val code: Int,
    val message: String,
)

object ProtocolCodec {
    val ARTIFACT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$")
    @PublishedApi
    internal val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    private val commandTypes = setOf(
        "session.list",
        "session.create",
        "session.open",
        "session.follow",
        "history.get",
        "message.content.prepare",
        "message.send",
        "attachment.begin",
        "attachment.finish",
        "attachment.download",
        "command.list",
        "model.catalog.get",
        "model.call.get",
        "runtime.document.list",
        "runtime.document.get",
        "runtime.capability.list",
        "runtime.mcp.get",
        "scheduler.job.list",
        "scheduler.job.get",
        "plugin.ui.catalog",
        "plugin.ui.asset.get",
        "plugin.ui.query",
        "plugin.ui.query.prepare",
        "plugin.ui.cancel",
        MOBILE_WEB_UI_RELEASE_GET,
        MOBILE_WEB_UI_CONTENT_PREPARE,
        "device.update",
        "ping",
    )

    private val specialReplyTypes = setOf(
        "session.created",
        "message.content.ready",
        "plugin.ui.catalog.not_modified",
        "plugin.ui.query.ready",
    )

    private val replyTypePattern = Regex(
        "^([a-z][a-z0-9]*(?:\\.[a-z][a-z0-9_]*)+)\\.(?:ok|error)$",
    )

    private val knownTypes = mapOf(
        WireKind.COMMAND to commandTypes,
        WireKind.EVENT to setOf(
            "session.list",
            "session.created",
            "session.updated",
            "history.page",
            "turn.started",
            "react.thinking.delta",
            "react.tool.started",
            "react.tool.completed",
            "answer.delta",
            "turn.output.completed",
            "message.final",
            "turn.interrupted",
            "attachment.progress",
            "attachment.ready",
            "connection.degraded",
            "sync.completed",
            "sync.reset_required",
        ),
        WireKind.ACK to setOf("event.ack"),
        WireKind.CONTROL to setOf(
            "server.challenge",
            "device.proof",
            "auth.accepted",
            "resume",
            "session.message",
            "plugin.ui.changed",
            MOBILE_WEB_UI_RELEASE_CHANGED,
            "device.revoked",
            "pair.claim",
            "pair.pending",
            "pair.accepted",
            "protocol.error",
        ),
    )

    /** 在 JSON 信任边界解析并校验 wire envelope。 */
    fun decode(text: String): WireEnvelope {
        // 1. 限制外部帧大小并完成结构解析
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_FRAME_BYTES) {
            "JSON frame exceeds $MAX_JSON_FRAME_BYTES bytes"
        }
        requireNoDuplicateJsonKeys(text)
        val envelope = json.decodeFromString<WireEnvelope>(text)

        // 2. 校验协议版本与 envelope 不变量
        require(envelope.v == WIRE_PROTOCOL_VERSION) { "Unsupported protocol version: ${envelope.v}" }
        validateEnvelope(envelope)
        return envelope
    }

    fun encode(envelope: WireEnvelope): String {
        require(envelope.v == WIRE_PROTOCOL_VERSION) { "Unsupported protocol version: ${envelope.v}" }
        validateEnvelope(envelope)
        val encoded = json.encodeToString(envelope)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_JSON_FRAME_BYTES) {
            "JSON frame exceeds $MAX_JSON_FRAME_BYTES bytes"
        }
        return encoded
    }

    fun json(): Json = json

    inline fun <reified T> decodePayload(payload: JsonObject): T = json.decodeFromJsonElement(payload)

    private fun validateEnvelope(envelope: WireEnvelope) {
        require(envelope.type.isNotBlank()) { "Envelope type is required" }
        knownTypes[envelope.kind]?.let { types ->
            require(envelope.type in types) { "Unsupported ${envelope.kind} type: ${envelope.type}" }
        }
        if (envelope.kind == WireKind.REPLY) {
            val command = replyTypePattern.matchEntire(envelope.type)?.groupValues?.get(1)
            require(envelope.type in specialReplyTypes || command in commandTypes) {
                "Unsupported REPLY type: ${envelope.type}"
            }
        }

        when (envelope.kind) {
            WireKind.COMMAND,
            WireKind.REPLY,
            WireKind.EVENT,
            -> require(envelope.id != null && FRAME_ID.matches(envelope.id)) {
                "${envelope.kind} id must be a UUIDv7 or ULID"
            }

            WireKind.ACK,
            WireKind.CONTROL,
            -> Unit
        }
        if (envelope.kind == WireKind.EVENT) {
            require(envelope.eventSeq != null && envelope.eventSeq > 0) { "Event sequence must be positive" }
        } else {
            require(envelope.eventSeq == null) { "event_seq is only valid on event frames" }
        }
        when (envelope.kind) {
            WireKind.COMMAND,
            WireKind.REPLY,
            WireKind.EVENT,
            WireKind.ACK,
            -> require(envelope.connectionEpoch != null && envelope.connectionEpoch > 0) {
                "Authenticated frames require a positive connection_epoch"
            }
            WireKind.CONTROL -> when (envelope.type) {
                "auth.accepted", "resume", "session.message", "plugin.ui.changed", MOBILE_WEB_UI_RELEASE_CHANGED, "device.revoked" -> require(
                    envelope.connectionEpoch != null && envelope.connectionEpoch > 0,
                ) { "Authenticated controls require a positive connection_epoch" }
                else -> require(envelope.connectionEpoch == null) {
                    "Pre-auth controls must not carry connection_epoch"
                }
            }
        }
        if (envelope.kind == WireKind.ACK || envelope.kind == WireKind.CONTROL) {
            require(envelope.id == null) { "${envelope.kind} id is not used by protocol v1" }
        }
        if (envelope.kind == WireKind.COMMAND && envelope.type == "message.send") {
            val payload = json.decodeFromJsonElement<MessageSendPayload>(envelope.payload)
            require(payload.clientMessageId == envelope.id) {
                "message.send id must equal client_message_id"
            }
        }
    }

    internal val FRAME_ID = Regex(
        "^(?:[0-9A-HJKMNP-TV-Z]{26}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-" +
            "7[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12})$",
    )
}
