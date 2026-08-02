package com.akashic.mobile.data.realtime

import com.akashic.mobile.data.local.MessageContentStore
import com.akashic.mobile.data.local.MessageContentTransferDao
import com.akashic.mobile.data.local.MessageContentTransferEntity
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class MessageContentHttpRequest(
    val commandId: String,
    val path: String,
    val ticket: String,
    val offset: Long,
    val byteLength: Long,
    val sha256: String,
)

data class MessageContentHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val contentRange: String?,
    val etag: String?,
    val contentDigest: String?,
    val representationDigest: String?,
)

class MessageContentDownloadCoordinator(
    private val dao: MessageContentTransferDao,
    private val store: MessageContentStore,
    private val sendCommand: (String, String, String, JsonObject) -> Boolean,
    private val startHttpDownload: (MessageContentHttpRequest) -> Unit,
    private val commit: suspend (MessageContentTransferEntity, String, Long) -> Unit,
    private val onTransportUnavailable: (String) -> Unit,
    private val onDownloadFailed: (String) -> Unit,
) {
    private data class ActiveDownload(
        val commandId: String,
        val transfer: MessageContentTransferEntity,
        val grant: MessageContentGrantPayload? = null,
    )

    private var serverId: String? = null
    private var active: ActiveDownload? = null

    suspend fun onConnectionReady(currentServerId: String) {
        if (serverId != currentServerId) {
            serverId = currentServerId
            active = null
        }
        startNext()
    }

    fun onDisconnected() {
        serverId = null
        active = null
    }

    fun hasActiveDownload(): Boolean = active != null

    suspend fun resumeIfIdle(currentServerId: String) {
        if (serverId == currentServerId && active == null) startNext()
    }

    /** 消费 prepare reply 并启动当前票据的首个 Range 请求。 */
    suspend fun onReply(envelope: WireEnvelope): Boolean {
        val current = active ?: return false
        if (envelope.id != current.commandId) return false
        if (envelope.type == "message.content.prepare.error") {
            fail(current.transfer, replyMessage(envelope), discard = false)
            return true
        }
        require(envelope.type == "message.content.ready") {
            "Unexpected message content reply: ${envelope.type}"
        }
        val grant = ProtocolCodec.decodePayload<MessageContentGrantPayload>(envelope.payload)
        require(
            grant.messageId == current.transfer.messageId &&
                grant.byteLength == current.transfer.byteLength &&
                grant.sha256.equals(current.transfer.sha256, ignoreCase = true) &&
                grant.path == MESSAGE_CONTENT_HTTP_PATH &&
                grant.ticket.isNotBlank()
        ) { "消息正文下载票据与 manifest 不匹配" }
        val granted = current.copy(grant = grant)
        active = granted
        requestRange(granted)
        return true
    }

    /** 校验一个 HTTP 206 分片，持久化后继续当前票据或完成正文提交。 */
    suspend fun onHttpResponse(commandId: String, response: MessageContentHttpResponse) {
        val current = requireNotNull(active) { "收到未请求的消息正文 HTTP 响应" }
        require(current.commandId == commandId) { "消息正文 HTTP 响应 command id 不匹配" }
        if (response.statusCode == 401) {
            active = null
            startNext()
            return
        }
        if (response.statusCode != 206) {
            fail(
                current.transfer,
                "消息正文 HTTP 下载失败：${response.statusCode}",
                discard = false,
            )
            return
        }
        validateResponse(current.transfer, response)
        val nextOffset = store.append(current.transfer, response.body)
        val updated = current.transfer.copy(
            transferredBytes = nextOffset,
            state = "downloading",
            updatedAt = System.currentTimeMillis(),
        )
        if (nextOffset < updated.byteLength) {
            val continued = current.copy(transfer = updated)
            active = continued
            requestRange(continued)
            return
        }

        // 完整摘要和 UTF-8 都验证通过后，Room 消息与任务在同一事务提交
        val content = store.readVerified(updated)
        commit(updated, content, System.currentTimeMillis())
        store.delete(updated)
        active = null
        startNext()
    }

    fun onHttpFailure(commandId: String, message: String) {
        val current = active ?: return
        if (current.commandId != commandId) return
        active = null
        onTransportUnavailable(message)
    }

    suspend fun failActive(message: String, discard: Boolean) {
        val current = active ?: return
        fail(current.transfer, message, discard)
    }

    private suspend fun startNext() {
        val currentServerId = serverId ?: return
        if (active != null) return
        val transfer = dao.pending(currentServerId).firstOrNull() ?: return
        val partial = store.partialFile(transfer)
        val actual = if (partial.exists()) partial.length() else 0L
        require(actual == transfer.transferredBytes) { "消息正文恢复 offset 未完成启动校准" }
        val commandId = Ulid.next()
        active = ActiveDownload(commandId, transfer)
        val sent = sendCommand(
            "message.content.prepare",
            commandId,
            transfer.sessionId,
            ProtocolCodec.json().encodeToJsonElement(
                MessageContentPreparePayload.serializer(),
                MessageContentPreparePayload(
                    transfer.messageId,
                    transfer.byteLength,
                    transfer.sha256,
                ),
            ).jsonObject,
        )
        if (!sent) {
            active = null
            onTransportUnavailable("消息正文恢复命令未进入 WebSocket 队列")
        }
    }

    private fun requestRange(current: ActiveDownload) {
        val grant = requireNotNull(current.grant) { "消息正文 Range 缺少下载票据" }
        startHttpDownload(
            MessageContentHttpRequest(
                commandId = current.commandId,
                path = grant.path,
                ticket = grant.ticket,
                offset = current.transfer.transferredBytes,
                byteLength = current.transfer.byteLength,
                sha256 = current.transfer.sha256,
            ),
        )
    }

    private fun validateResponse(
        transfer: MessageContentTransferEntity,
        response: MessageContentHttpResponse,
    ) {
        require(response.body.isNotEmpty() && response.body.size <= MAX_RANGE_BYTES) {
            "消息正文 HTTP 分片大小无效"
        }
        val nextOffset = Math.addExact(transfer.transferredBytes, response.body.size.toLong())
        require(nextOffset <= transfer.byteLength) { "消息正文 HTTP 分片超过声明长度" }
        require(
            response.contentRange ==
                "bytes ${transfer.transferredBytes}-${nextOffset - 1}/${transfer.byteLength}"
        ) { "消息正文 Content-Range 不连续" }
        require(response.etag == "\"${transfer.sha256}\"") { "消息正文 ETag 不匹配" }
        require(
            response.contentDigest == digestField(MessageDigest.getInstance("SHA-256").digest(response.body))
        ) { "消息正文 Content-Digest 不匹配" }
        require(
            response.representationDigest == digestField(hexToBytes(transfer.sha256))
        ) { "消息正文 Repr-Digest 不匹配" }
    }

    private suspend fun fail(
        transfer: MessageContentTransferEntity,
        message: String,
        discard: Boolean,
    ) {
        val offset = if (discard) 0L else transfer.transferredBytes
        if (discard) store.delete(transfer)
        check(
            dao.updateProgress(
                transfer.messageId,
                offset,
                "failed",
                System.currentTimeMillis(),
            ) == 1,
        ) { "消息正文恢复记录已消失: ${transfer.messageId}" }
        active = null
        onDownloadFailed(message)
        startNext()
    }

    private fun digestField(bytes: ByteArray): String =
        "sha-256=:${Base64.getEncoder().encodeToString(bytes)}:"

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun replyMessage(envelope: WireEnvelope): String =
        envelope.payload["message"]?.toString()?.trim('"') ?: "消息正文恢复失败"

    private companion object {
        const val MESSAGE_CONTENT_HTTP_PATH = "/mobile/message-content/v1"
        const val MAX_RANGE_BYTES = 256 * 1024
    }
}
