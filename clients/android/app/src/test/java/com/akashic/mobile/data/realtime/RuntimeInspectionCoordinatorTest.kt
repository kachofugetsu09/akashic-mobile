package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInspectionCoordinatorTest {
    @Test
    fun refreshProjectsAllListsAndOpensMarkdownDetail() {
        val sent = mutableListOf<Pair<String, JsonObject>>()
        var nextId = 0
        val coordinator = RuntimeInspectionCoordinator { type, payload ->
            nextId += 1
            sent += type to payload
            "01ARZ3NDEKTSV4RRFFQ69G5FA${'U' + nextId}"
        }

        coordinator.onConnectionReady("server-1")

        assertEquals(
            setOf(
                "runtime.document.list",
                "runtime.capability.list",
                "scheduler.job.list",
            ),
            sent.mapTo(mutableSetOf()) { it.first },
        )
        assertTrue(coordinator.state.value.refreshing)

        coordinator.onReply(
            reply(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                type = "runtime.document.list.ok",
                payload = """
                    {"items":[{"id":"memory","title":"长期记忆",
                    "relative_path":"memory/MEMORY.md","group":"memory",
                    "description":"长期事实","available":true}]}
                """,
            ),
        )
        coordinator.onReply(
            reply(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAW",
                type = "runtime.capability.list.ok",
                payload = """
                    {"snapshot_id":"snap","plugins":[],"skills":[],
                    "mcp_servers":[{"owner_id":"workspace","name":"fitbit",
                    "tool_count":2,"tools":[{"name":"sleep","description":"睡眠"}]}]}
                """,
            ),
        )
        coordinator.onReply(
            reply(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAX",
                type = "scheduler.job.list.ok",
                payload = """
                    {"items":[{"id":"job-1","name":"早安","trigger":"every",
                    "tier":"instant","fire_at":"2026-07-29T00:00:00Z",
                    "timezone":"Asia/Shanghai","enabled":true,"run_count":3}]}
                """,
            ),
        )

        assertFalse(coordinator.state.value.refreshing)
        assertEquals("memory", coordinator.state.value.documents.single().id)
        assertEquals("fitbit", coordinator.state.value.mcpServers.single().name)
        assertEquals("job-1", coordinator.state.value.jobs.single().id)

        coordinator.openDocument("memory")
        assertEquals("runtime.document.get", sent.last().first)
        coordinator.onReply(
            reply(
                id = "01ARZ3NDEKTSV4RRFFQ69G5FAY",
                type = "runtime.document.get.ok",
                payload = """
                    {"id":"memory","title":"长期记忆",
                    "relative_path":"memory/MEMORY.md","group":"memory",
                    "description":"长期事实","available":true,
                    "markdown":"# Memory\n\n真实内容"}
                """,
            ),
        )

        val detail = requireNotNull(coordinator.state.value.detail)
        assertEquals(RuntimeInspectionDetailKind.DOCUMENT, detail.kind)
        assertEquals("# Memory\n\n真实内容", detail.markdown)

        coordinator.onDisconnected()
        assertEquals(RuntimeInspectionState(), coordinator.state.value)
    }

    private fun reply(id: String, type: String, payload: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = type,
        id = id,
        connectionEpoch = 1,
        payload = Json.parseToJsonElement(payload).jsonObject,
    )
}
