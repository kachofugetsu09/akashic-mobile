package com.akashic.mobile.data.realtime

import com.akashic.mobile.data.local.MessageEntity
import com.akashic.mobile.data.local.OutboxCommandEntity
import java.time.Instant
import kotlinx.serialization.json.jsonObject

internal const val CLOSE_COMMAND_REJECTED = 4410

internal data class PendingMessageSend(
    val message: MessageEntity,
    val command: OutboxCommandEntity,
)

/** 用同一个幂等身份构造本地消息和可重放命令。 */
internal fun preparePendingMessageSend(
    serverId: String,
    sessionId: String,
    body: String,
    now: Long,
    mediaRefs: List<String> = emptyList(),
    displayText: String = body,
    clientMessageId: String = Ulid.next(now),
): PendingMessageSend {
    val payload = MessageSendPayload(
        clientMessageId = clientMessageId,
        sessionId = sessionId,
        text = body,
        mediaRefs = mediaRefs,
        clientCreatedAt = Instant.ofEpochMilli(now).toString(),
    )
    val envelope = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.COMMAND,
        type = "message.send",
        id = clientMessageId,
        connectionEpoch = 1,
        sessionId = sessionId,
        payload = ProtocolCodec.json().encodeToJsonElement(MessageSendPayload.serializer(), payload).jsonObject,
    )
    return PendingMessageSend(
        message = MessageEntity(
            messageId = "user:$clientMessageId",
            clientMessageId = clientMessageId,
            sessionId = sessionId,
            role = "user",
            text = displayText,
            deliveryState = "pending",
            createdAt = now,
            updatedAt = now,
        ),
        command = OutboxCommandEntity(
            commandId = clientMessageId,
            serverId = serverId,
            envelopeJson = ProtocolCodec.encode(envelope),
            state = "pending",
            attemptCount = 0,
            createdAt = now,
            lastAttemptAt = null,
        ),
    )
}

internal enum class OutboxFailureDisposition {
    RETRY_ORIGINAL,
    FAIL,
}

internal fun messageSendFailureDisposition(errorCode: String?): OutboxFailureDisposition =
    if (errorCode == "command_outcome_unknown") {
        OutboxFailureDisposition.RETRY_ORIGINAL
    } else {
        OutboxFailureDisposition.FAIL
    }

internal class SingleFlightOutbox {
    var commandId: String? = null
        private set

    fun claim(nextCommandId: String): Boolean {
        if (commandId != null) return false
        commandId = nextCommandId
        return true
    }

    fun complete(completedCommandId: String) {
        check(commandId == completedCommandId) {
            "Outbox reply does not match the active command: expected=$commandId actual=$completedCommandId"
        }
        commandId = null
    }

    fun reset() {
        commandId = null
    }
}
