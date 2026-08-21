package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeInspectionCoordinatorTest {
    @Test
    fun `decodes plugin v3 composition with strict schema`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "snapshot_id":"snap-v3",
              "plugins":[{
                "id":"inspected_v3",
                "revision":"1.0.0",
                "generation_id":"generation-1",
                "api_version":3,
                "composition":{
                  "ready":true,
                  "topology_identity":"topology-1",
                  "composition_revision":4,
                  "fibers":[{
                    "name":"worker",
                    "parent":"inspected_v3",
                    "state":"active",
                    "required":false,
                    "static_active":true,
                    "dependencies":[],
                    "missing_services":[],
                    "error":null
                  }],
                  "health":[{
                    "owner":"worker",
                    "name":"poller",
                    "required":false,
                    "healthy":false,
                    "reason":"paused"
                  }],
                  "incident_count":140,
                  "recent_incidents":[{
                    "sequence":140,
                    "owner":"worker",
                    "kind":"poll",
                    "message":"failure 139",
                    "error_type":null
                  }],
                  "incident_overflowed":false
                }
              }],
              "skills":[],
              "mcp_servers":[]
            }
            """.trimIndent(),
        ).jsonObject

        val decoded = ProtocolCodec.decodePayload<RuntimeCapabilityListPayload>(payload)
        val plugin = decoded.plugins.single()
        val composition = requireNotNull(plugin.composition)

        assertEquals(3, plugin.apiVersion)
        assertEquals("topology-1", composition.topologyIdentity)
        assertEquals("active", composition.fibers.single().state)
        assertEquals("paused", composition.health.single().reason)
        assertEquals(140L, composition.incidentCount)
        assertEquals("failure 139", composition.recentIncidents.single().message)
    }

    @Test
    fun `decodes plugin summary from pre v3 inspection payload`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "snapshot_id":"snap-v2",
              "plugins":[{
                "id":"legacy-plugin",
                "revision":"1.0.0",
                "generation_id":"generation-legacy"
              }],
              "skills":[],
              "mcp_servers":[]
            }
            """.trimIndent(),
        ).jsonObject

        val plugin = ProtocolCodec
            .decodePayload<RuntimeCapabilityListPayload>(payload)
            .plugins
            .single()

        assertNull(plugin.apiVersion)
        assertNull(plugin.composition)
    }

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
                    "tool_count":2,"tools":[{"name":"sleep","description":"睡眠",
                    "input_schema":{"type":"object","properties":{"date":{"type":"string"}}}}]}]}
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
        assertEquals(
            "object",
            coordinator.state.value.mcpServers.single().tools.single().inputSchema["type"]
                ?.jsonPrimitive
                ?.content,
        )
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
