package com.akashic.mobile.data.realtime.pluginui

import com.akashic.mobile.data.realtime.MobileUiCatalogItem
import com.akashic.mobile.data.realtime.MobileUiCatalogPayload
import com.akashic.mobile.data.realtime.WIRE_PROTOCOL_VERSION
import com.akashic.mobile.data.realtime.WireEnvelope
import com.akashic.mobile.data.realtime.WireKind
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginUiCoordinatorTest {
    @Test
    fun catalogChangeWhileCatalogPendingDiscardsCachedOldCatalog() {
        val root = Files.createTempDirectory("plugin-ui-catalog-change").toFile()
        try {
            val assets = PluginUiAssetStore(root.resolve("assets"))
            val catalogs = PluginUiCatalogStore(root.resolve("catalogs"))
            val module = "export default { slots: {} };"
            val moduleBytes = module.toByteArray(Charsets.UTF_8)
            val moduleSha = moduleBytes.sha256()
            assets.store(moduleSha, "module", module, moduleBytes.size)
            val oldCatalog = catalog(
                revision = "a".repeat(64),
                items = listOf(catalogItem("observe", moduleSha, moduleBytes.size)),
            )
            val sent = mutableListOf<Pair<String, String>>()
            val coordinator = coordinator(root, assets, catalogs, sent)

            coordinator.onConnectionReady("mobile-lab")
            coordinator.onCatalogChanged()
            coordinator.onReply(catalogReply(sent.single().second, oldCatalog))

            assertEquals(listOf("plugin.ui.catalog", "plugin.ui.catalog"), sent.map { it.first })
            assertEquals("", coordinator.catalog.value.catalogRevision)
            assertEquals(true, coordinator.catalog.value.updating)
            assertEquals(null, catalogs.read("mobile-lab"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun assetFailureConsumesOtherLateAssetReplies() {
        val root = Files.createTempDirectory("plugin-ui-asset-failure").toFile()
        try {
            val assets = PluginUiAssetStore(root.resolve("assets"))
            val catalogs = PluginUiCatalogStore(root.resolve("catalogs"))
            val firstModule = "export default { slots: { first: true } };".toByteArray()
            val secondModule = "export default { slots: { second: true } };".toByteArray()
            val catalog = catalog(
                revision = "b".repeat(64),
                items = listOf(
                    catalogItem("first", firstModule.sha256(), firstModule.size),
                    catalogItem("second", secondModule.sha256(), secondModule.size),
                ),
            )
            val sent = mutableListOf<Pair<String, String>>()
            val coordinator = coordinator(root, assets, catalogs, sent)

            coordinator.onConnectionReady("mobile-lab")
            coordinator.onReply(catalogReply(sent.single().second, catalog))
            val assetCommands = sent.filter { it.first == "plugin.ui.asset.get" }.map { it.second }
            assertEquals(2, assetCommands.size)

            assertEquals(true, coordinator.onReply(assetErrorReply(assetCommands.first())))
            assertEquals(true, coordinator.onReply(lateAssetReply(assetCommands.last())))
            assertEquals("", coordinator.catalog.value.catalogRevision)
            assertEquals(false, coordinator.catalog.value.updating)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoredAssetReplyOwnershipIsBounded() {
        val root = Files.createTempDirectory("plugin-ui-bounded-ignored-assets").toFile()
        try {
            val assets = PluginUiAssetStore(root.resolve("assets"))
            val catalogs = PluginUiCatalogStore(root.resolve("catalogs"))
            val module = "export default { slots: {} };".toByteArray()
            val catalog = catalog(
                revision = "c".repeat(64),
                items = listOf(
                    catalogItem("first", module.sha256(), module.size),
                    catalogItem("second", module.sha256(), module.size),
                ),
            )
            val sent = mutableListOf<Pair<String, String>>()
            val coordinator = coordinator(root, assets, catalogs, sent)
            val ignoredIds = mutableListOf<String>()

            repeat(129) {
                coordinator.onConnectionReady("mobile-lab")
                val catalogId = sent.last { it.first == "plugin.ui.catalog" }.second
                coordinator.onReply(catalogReply(catalogId, catalog))
                val assetIds = sent.takeLast(2).map { it.second }
                coordinator.onReply(assetErrorReply(assetIds.first()))
                ignoredIds += assetIds.last()
            }

            assertEquals(false, coordinator.onReply(lateAssetReply(ignoredIds.first())))
            assertEquals(true, coordinator.onReply(lateAssetReply(ignoredIds.last())))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun immutableQueriesUseSingleflightAndPersistAcrossCoordinatorRecreation() = runBlocking {
        val root = Files.createTempDirectory("plugin-ui-coordinator").toFile()
        try {
            val assets = PluginUiAssetStore(root.resolve("assets"))
            val catalogs = PluginUiCatalogStore(root.resolve("catalogs"))
            val results = PluginUiResultStore(root.resolve("results"))
            val module = "export default { slots: {} };"
            val moduleBytes = module.toByteArray(Charsets.UTF_8)
            val moduleSha = moduleBytes.sha256()
            assets.store(moduleSha, "module", module, moduleBytes.size)
            val catalog = MobileUiCatalogPayload(
                catalogRevision = "a".repeat(64),
                items = listOf(
                    MobileUiCatalogItem(
                        id = "observe",
                        revision = "1.2.0",
                        moduleSha256 = moduleSha,
                        moduleBytes = moduleBytes.size,
                        slots = listOf("turn.after_answer"),
                    ),
                ),
            )
            val sent = mutableListOf<Pair<String, String>>()
            val coordinator = PluginUiCoordinator(assets, catalogs, results) { type, _, _, _ ->
                "command-${sent.size + 1}".also { sent += type to it }
            }
            coordinator.onConnectionReady("mobile-lab")
            coordinator.onReply(catalogReply(sent.single().second, catalog))

            coordinator.query(
                "request-1", "owner-1", "turn.after_answer", "mobile:one", null,
                "observe", "kvcache.message_usage", "{\"message_id\":\"message-1\"}",
                "immutable", "mobile-lab",
            )
            coordinator.query(
                "request-2", "owner-2", "turn.after_answer", "mobile:one", null,
                "observe", "kvcache.message_usage", "{\"message_id\":\"message-1\"}",
                "immutable", "mobile-lab",
            )

            val queryCommands = sent.filter { it.first == "plugin.ui.query" }
            assertEquals(1, queryCommands.size)
            coordinator.onReply(queryReply(queryCommands.single().second))
            assertEquals(
                setOf("request-1", "request-2"),
                coordinator.results.take(2).toList().map { it.requestId }.toSet(),
            )

            val restoredSent = mutableListOf<String>()
            val restored = PluginUiCoordinator(assets, catalogs, results) { type, _, _, _ ->
                "restored-${restoredSent.size + 1}".also { restoredSent += type }
            }
            restored.onConnectionReady("mobile-lab")
            restored.query(
                "request-3", "owner-3", "turn.after_answer", "mobile:one", null,
                "observe", "kvcache.message_usage", "{\"message_id\":\"message-1\"}",
                "immutable", "mobile-lab",
            )
            assertEquals(listOf("plugin.ui.catalog"), restoredSent)
            assertEquals("request-3", restored.results.take(1).toList().single().requestId)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun catalogReply(id: String, catalog: MobileUiCatalogPayload) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "plugin.ui.catalog.ok",
        id = id,
        payload = Json.encodeToJsonElement(catalog).jsonObject,
    )

    private fun assetErrorReply(id: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "plugin.ui.asset.get.error",
        id = id,
        payload = buildJsonObject { put("message", "asset unavailable") },
    )

    private fun lateAssetReply(id: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "plugin.ui.asset.get.ok",
        id = id,
        payload = JsonObject(emptyMap()),
    )

    private fun queryReply(id: String) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "plugin.ui.query.ok",
        id = id,
        payload = buildJsonObject {
            put("result", buildJsonObject {
                put("usage", buildJsonObject { put("output_tokens", 321) })
            })
        },
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun catalog(
        revision: String,
        items: List<MobileUiCatalogItem>,
    ) = MobileUiCatalogPayload(catalogRevision = revision, items = items)

    private fun catalogItem(id: String, moduleSha: String, moduleBytes: Int) = MobileUiCatalogItem(
        id = id,
        revision = "1.0.0",
        moduleSha256 = moduleSha,
        moduleBytes = moduleBytes,
        slots = listOf("turn.after_answer"),
    )

    private fun coordinator(
        root: java.io.File,
        assets: PluginUiAssetStore,
        catalogs: PluginUiCatalogStore,
        sent: MutableList<Pair<String, String>>,
    ) = PluginUiCoordinator(assets, catalogs, PluginUiResultStore(root.resolve("results"))) {
            type, _, _, _ ->
        "command-${sent.size + 1}".also { sent += type to it }
    }
}
