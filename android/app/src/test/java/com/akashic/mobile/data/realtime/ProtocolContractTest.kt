package com.akashic.mobile.data.realtime

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolContractTest {
    @Test
    fun `snapshot hash matches pinned source metadata`() {
        val source = readJson(repositoryRoot.resolve("protocol/source.json"))
        val snapshot = repositoryRoot.resolve(source.requiredText("snapshot_path"))
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(snapshot.toFile().readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(source.requiredText("sha256"), actual)
    }

    @Test
    fun `Kotlin wire types match the pinned schema`() {
        val schema = readJson(repositoryRoot.resolve("protocol/mobile-realtime-v1.json"))

        assertEquals(schema.stringSet("commandTypes"), ProtocolCodec.supportedTypes(WireKind.COMMAND))
        assertEquals(schema.stringSet("eventTypes"), ProtocolCodec.supportedTypes(WireKind.EVENT))
        assertEquals(schema.stringSet("controlTypes"), ProtocolCodec.supportedTypes(WireKind.CONTROL))
    }

    @Test
    fun `shared protocol fixtures decode through the production codec`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/frames-v1.json")) {
            "frames-v1.json is missing from test resources"
        }
        val fixtures = stream.bufferedReader().use { ProtocolCodec.json().parseToJsonElement(it.readText()).jsonArray }

        assertEquals(7, fixtures.size)
        fixtures.forEach { ProtocolCodec.decode(it.toString()) }
    }

    @Test
    fun `runtime compatibility is locked separately from the schema snapshot`() {
        val compatibility = readJson(repositoryRoot.resolve("protocol/runtime-compatibility.json"))

        assertEquals("runtime_semantics_not_schema", compatibility.requiredText("scope"))
        assertEquals(
            setOf(
                "message_send_command_id_equals_client_message_id",
                "command_outcome_unknown_preserves_original_command_id",
                "message_send_protocol_rejection_uses_close_code_4410",
                "claimed_deleted_session_is_not_recreated",
            ),
            compatibility.stringSet("required_semantics"),
        )
        require(compatibility.requiredText("verified_source_commit").matches(Regex("^[0-9a-f]{40}$"))) {
            "Runtime compatibility commit must be a full Git SHA"
        }
    }

    private fun readJson(path: Path): JsonObject =
        ProtocolCodec.json().parseToJsonElement(path.toFile().readText()).jsonObject

    private fun JsonObject.requiredText(key: String): String =
        requireNotNull(this[key]) { "Missing protocol metadata field: $key" }.jsonPrimitive.content

    private fun JsonObject.stringSet(key: String): Set<String> =
        (requireNotNull(this[key]) { "Missing protocol schema field: $key" } as JsonArray)
            .map { it.jsonPrimitive.content }
            .toSet()

    private companion object {
        val repositoryRoot: Path = Path.of(
            requireNotNull(System.getProperty("akashic.repositoryRoot")) {
                "Gradle must provide akashic.repositoryRoot"
            },
        )
    }
}
