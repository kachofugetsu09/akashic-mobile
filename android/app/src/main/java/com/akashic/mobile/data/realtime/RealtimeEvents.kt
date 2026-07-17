package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class FinalMessageEvent(
    val sessionId: String,
    val messageId: String,
    val content: String,
    val hasAttachments: Boolean,
)

internal fun deliveredAssistantMessageId(envelope: WireEnvelope): String = when (envelope.type) {
    "message.final" -> requireNotNull(envelope.payload["message_id"]?.jsonPrimitive?.content) {
        "Final message has no canonical message_id"
    }
    "message.proactive" -> "proactive:${requireNotNull(envelope.id) { "Proactive event has no frame id" }}"
    else -> error("Unsupported delivered message type: ${envelope.type}")
}

internal fun deliveredFinalMessageEvent(envelope: WireEnvelope): FinalMessageEvent {
    require(envelope.type in DELIVERED_MESSAGE_TYPES) {
        "Unsupported final message event: ${envelope.type}"
    }
    return FinalMessageEvent(
        sessionId = requireNotNull(envelope.sessionId),
        messageId = deliveredAssistantMessageId(envelope),
        content = envelope.payload["content"]?.jsonPrimitive?.content
            ?: envelope.payload["text"]?.jsonPrimitive?.content
            ?: "",
        hasAttachments = envelope.payload["attachments"]?.jsonArray?.isNotEmpty() == true,
    )
}

private val DELIVERED_MESSAGE_TYPES = setOf("message.final", "message.proactive")
