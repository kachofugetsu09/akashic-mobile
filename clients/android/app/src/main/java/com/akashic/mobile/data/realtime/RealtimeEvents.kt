package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

data class FinalMessageEvent(
    val sessionId: String,
    val messageId: String,
    val content: String,
    val hasAttachments: Boolean,
    val attention: FinalMessageAttention = FinalMessageAttention.COMPLETE,
)

internal fun deliveredAssistantMessageId(envelope: WireEnvelope): String {
    require(envelope.type == "message.final") { "Unsupported delivered message type: ${envelope.type}" }
    return requireNotNull(envelope.payload["message_id"]?.jsonPrimitive?.content) {
        "Final event has no canonical message_id"
    }
}

internal fun messageReplyReference(
    messageId: String,
    clientMessageId: String?,
): MessageReplyReference = when {
    clientMessageId != null -> MessageReplyReference(clientMessageId = clientMessageId)
    else -> MessageReplyReference(messageId = messageId)
}

internal fun deliveredFinalMessageEvent(envelope: WireEnvelope): FinalMessageEvent {
    require(envelope.type == "message.final") {
        "Unsupported final message event: ${envelope.type}"
    }
    return FinalMessageEvent(
        sessionId = requireNotNull(envelope.sessionId),
        messageId = deliveredAssistantMessageId(envelope),
        content = envelope.payload["content"]?.jsonPrimitive?.content
            ?: envelope.payload["text"]?.jsonPrimitive?.content
            ?: "",
        hasAttachments = envelope.payload["attachments"]?.jsonArray?.isNotEmpty() == true,
        attention = finalMessageAttention(envelope.payload),
    )
}

enum class FinalMessageAttention {
    COMPLETE,
    CONFIRMATION,
}

internal fun finalMessageAttention(payload: JsonObject): FinalMessageAttention {
    """读取服务端显式声明的移动端通知语义。"""

    // 1. 缺省终态就是普通完成
    val rawMetadata = payload["metadata"] ?: return FinalMessageAttention.COMPLETE

    // 2. 在协议边界校验结构与枚举值
    val metadata = rawMetadata as? JsonObject ?: error("最终消息 metadata 必须是对象")
    return when (metadata["mobile_attention"]?.jsonPrimitive?.content) {
        null -> FinalMessageAttention.COMPLETE
        "confirmation" -> FinalMessageAttention.CONFIRMATION
        else -> error("最终消息 mobile_attention 无效")
    }
}
