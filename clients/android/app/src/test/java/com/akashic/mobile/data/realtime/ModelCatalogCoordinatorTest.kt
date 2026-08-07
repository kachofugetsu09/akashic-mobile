package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCoordinatorTest {
    @Test
    fun projectsCatalogAndKeepsValidatedLocalSelection() {
        val sent = mutableListOf<Pair<String, String>>()
        val coordinator = ModelCatalogCoordinator { type, sessionId ->
            sent += type to sessionId
            "request-${sent.size}"
        }

        coordinator.onConnectionReady("server-1", "mobile:first")
        assertEquals("model.catalog.get" to "mobile:first", sent.single())
        assertTrue(coordinator.state.value.loading)

        coordinator.onReply(reply("request-1", "mobile:first"))
        coordinator.select("model-a", "high")

        assertEquals(ModelSelection("model-a", "high"), coordinator.selectionFor("mobile:first"))
        assertEquals("model-a", coordinator.state.value.selectedRuntimeId)
        assertEquals("high", coordinator.state.value.selectedReasoningEffort)
    }

    @Test
    fun sessionChangeDuringRequestDiscardsStaleReplyAndRefreshesLatestSession() {
        val sent = mutableListOf<Pair<String, String>>()
        val coordinator = ModelCatalogCoordinator { type, sessionId ->
            sent += type to sessionId
            "request-${sent.size}"
        }

        coordinator.onConnectionReady("server-1", "mobile:first")
        coordinator.onSessionSelected("mobile:second")
        coordinator.onReply(reply("request-1", "mobile:first"))

        assertEquals(
            listOf(
                "model.catalog.get" to "mobile:first",
                "model.catalog.get" to "mobile:second",
            ),
            sent,
        )
        assertEquals("mobile:second", coordinator.state.value.sessionId)
        assertTrue(coordinator.state.value.loading)
    }

    private fun reply(id: String, sessionId: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "model.catalog.get.ok",
        id = id,
        connectionEpoch = 1,
        sessionId = sessionId,
        payload = Json.parseToJsonElement(
            """
                {"generation_id":3,"default_runtime":"model-a",
                "selected_runtime_id":"","selected_reasoning_effort":"",
                "runtimes":[{"id":"model-a","provider":"openai","model":"gpt-test",
                "sourceId":"source-a","sourceName":"OpenAI","reasoningEffort":"medium",
                "supportedReasoningEfforts":["low","medium","high"],
                "roles":["default","agent"],"contextWindow":128000,
                "inputModalities":["text","image"]}]}
            """.trimIndent(),
        ).jsonObject,
    )
}
