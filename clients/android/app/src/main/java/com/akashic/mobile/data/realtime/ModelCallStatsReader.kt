package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 只拥有调用统计查询的关联与结束，不缓存模型事实。调用者串行访问。 */
class ModelCallStatsReader(private val send: (String, JsonObject) -> String?) {
    private val pending = mutableMapOf<String, (JsonObject) -> Unit>()

    fun read(callId: String, receive: (JsonObject) -> Unit): String? {
        val id = send("model.call.get", buildJsonObject { put("call_record_id", callId) })
        if (id == null) receive(error("统计连接不可用"))
        else pending[id] = receive
        return id
    }

    fun forget(id: String?) { pending.remove(id) }

    fun onReply(envelope: WireEnvelope): Boolean {
        if (envelope.type !in setOf("model.call.get.ok", "model.call.get.error")) return false
        pending.remove(envelope.id)?.invoke(envelope.payload)
        return true
    }

    fun onDisconnected() {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it(error("统计连接已中断")) }
    }

    companion object {
        fun error(message: String): JsonObject = buildJsonObject { put("error", message) }
    }
}
