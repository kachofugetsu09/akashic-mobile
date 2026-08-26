package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogCoordinatorTest {
    @Test
    fun emptyCoreWaitsForFirstSessionBeforeLoadingCatalog() {
        val sent = mutableListOf<Pair<String, String>>()
        val coordinator = ModelCatalogCoordinator { type, sessionId ->
            sent += type to sessionId
            "request-${sent.size}"
        }

        coordinator.onConnectionReady("server-1", null)
        assertEquals("server-1", coordinator.state.value.serverId)
        assertTrue(sent.isEmpty())

        coordinator.onSessionSelected("akashic:" + "a".repeat(32))
        assertEquals(
            "model.catalog.get" to ("akashic:" + "a".repeat(32)),
            sent.single(),
        )
    }

    @Test
    fun projectsCatalogAndKeepsValidatedLocalSelection() {
        val sent = mutableListOf<Pair<String, String>>()
        val coordinator = ModelCatalogCoordinator { type, sessionId ->
            sent += type to sessionId
            "request-${sent.size}"
        }

        coordinator.onConnectionReady("server-1", "akashic:first")
        assertEquals("model.catalog.get" to "akashic:first", sent.single())
        assertTrue(coordinator.state.value.loading)

        coordinator.onReply(reply("request-1", "akashic:first"))
        coordinator.select("model-a", "high")

        assertEquals(ModelSelection("model-a", "high"), coordinator.selectionFor("akashic:first"))
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

        coordinator.onConnectionReady("server-1", "akashic:first")
        coordinator.onSessionSelected("akashic:second")
        coordinator.onReply(reply("request-1", "akashic:first"))

        assertEquals(
            listOf(
                "model.catalog.get" to "akashic:first",
                "model.catalog.get" to "akashic:second",
            ),
            sent,
        )
        assertEquals("akashic:second", coordinator.state.value.sessionId)
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
