package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ModelCallStatsReaderTest {
    @Test fun repliesKeepTheirRequestAndLateResultsAreIgnored() {
        val payloads = mutableListOf<JsonObject>()
        var sent = 0
        val reader = ModelCallStatsReader { type, payload ->
            assertEquals("model.call.get", type)
            payloads += payload
            "request-${++sent}"
        }
        val results = mutableListOf<JsonObject>()
        val old = reader.read("old") { error("cancelled request received a reply") }
        reader.forget(old)
        reader.read("new", results::add)
        assertEquals(buildJsonObject { put("call_record_id", "new") }, payloads.last())
        assertTrue(reader.onReply(reply("request-1", "old")))
        assertTrue(results.isEmpty())
        val response = reply("request-2", "new")
        assertTrue(reader.onReply(response))
        assertEquals(listOf(response.payload), results)
        reader.onReply(response)
        assertEquals(1, results.size)
    }

    @Test fun failedSendAndDisconnectCompleteWithoutInventingStats() {
        val results = mutableListOf<JsonObject>()
        ModelCallStatsReader { _, _ -> null }.read("call", results::add)
        assertTrue(results.single().containsKey("error"))
        results.clear()
        val reader = ModelCallStatsReader { _, _ -> "request" }
        reader.read("call", results::add)
        reader.onDisconnected()
        reader.onDisconnected()
        reader.onReply(reply("request", "call"))
        assertEquals(1, results.size)
        assertTrue(results.single().containsKey("error"))
    }

    private fun reply(id: String, callId: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION, kind = WireKind.REPLY, type = "model.call.get.ok",
        id = id, connectionEpoch = 1, payload = buildJsonObject { put("call_record_id", callId) },
    )
}
