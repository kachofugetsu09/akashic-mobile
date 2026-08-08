package com.akashic.mobile.data.realtime

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class MobileWebUiProtocolTest {
    @Test
    fun `HTTP error parser accepts only string codes at the trust boundary`() {
        assertEquals("message", jsonStringValue(JsonPrimitive("message")))
        assertNull(jsonStringValue(JsonObject(emptyMap())))
        assertNull(jsonStringValue(JsonPrimitive(1)))
        assertEquals(
            "operator revoked",
            decodeDeviceRevocationReason(JsonObject(mapOf("reason" to JsonPrimitive("operator revoked")))),
        )
        assertEquals(
            "",
            decodeDeviceRevocationReason(JsonObject(mapOf("reason" to JsonObject(emptyMap())))),
        )
        assertEquals(
            "resource_not_found",
            decodeMobileWebUiHttpErrorCode("""{"code":"resource_not_found"}""".toByteArray()),
        )
        assertEquals(
            "invalid_ticket",
            decodeMobileWebUiHttpErrorCode("""{"error":{"code":"invalid_ticket"}}""".toByteArray()),
        )

        listOf(
            """{"code":{}}""",
            """{"code":[]}""",
            """{"code":1}""",
            """{"code":true}""",
            """{"code":null}""",
            """{"error":{"code":{}}}""",
            """{"error":{"code":[]}}""",
            "[]",
            "null",
            "42",
            "{\"code\":\"unterminated}",
        ).forEach { body ->
            assertNull(body, decodeMobileWebUiHttpErrorCode(body.toByteArray()))
        }
    }

    @Test
    fun `duplicate key scanner reports malformed values as argument errors`() {
        listOf("garbage", "\"unterminated").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                requireNoDuplicateJsonKeys(raw)
            }
        }
    }

    @Test
    fun `canonical manifest derives generation and target identity`() {
        val manifest = validManifest()
        validateMobileWebUiManifest(manifest)
        val digest = mobileWebUiManifestDigest(manifest)
        val target = validTarget(manifest, digest)

        validateMobileWebUiTarget(target, SERVER_ID)
        validateMobileWebUiManifest(manifest, target)
        assertEquals(
            deriveMobileWebUiTargetKey(SERVER_ID, manifest.generationId, digest),
            target.targetKey,
        )
        assertEquals(
            mobileWebUiSelectionDigest(SERVER_ID, target.targetKey, null),
            mobileWebUiSelectionDigest(SERVER_ID, target.targetKey, null),
        )
    }

    @Test
    fun `NFC path keeps case while casefold key detects collision`() {
        val path = "assets/HashABC.WOFF2"
        assertEquals(path, normalizeMobileWebUiPath(path))
        assertEquals("assets/hashabc.woff2", mobileWebUiPathKey(path))
    }

    @Test
    fun `zero byte file is valid`() {
        val manifest = validManifest()
        validateMobileWebUiManifest(manifest)
        assertEquals(0, manifest.files.single().sizeBytes)
        assertEquals(0, manifest.unpackedSizeBytes)
    }

    @Test
    fun `stable and preview may reference same target`() {
        val manifest = validManifest()
        val digest = mobileWebUiManifestDigest(manifest)
        val target = validTarget(manifest, digest)
        val view = MobileWebUiReleaseView(
            serverId = SERVER_ID,
            releaseEpoch = "00000000-0000-4000-8000-000000000001",
            sequence = 1,
            selectionDigest = mobileWebUiSelectionDigest(SERVER_ID, target.targetKey, target.targetKey),
            stable = target,
            preview = target,
        )
        validateMobileWebUiReleaseView(view, SERVER_ID)
    }

    @Test
    fun `unknown manifest fields are rejected`() {
        val manifest = validManifest()
        val raw = MOBILE_WEB_UI_JSON.encodeToString(manifest)
            .removeSuffix("}") + ",\"archive_size_bytes\":1}"
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(raw)
        }
    }

    @Test
    fun `release view requires explicit stable and preview fields`() {
        val digest = mobileWebUiSelectionDigest(SERVER_ID, null, null)
        val withoutStable = """
            {"server_id":"$SERVER_ID","release_epoch":"00000000-0000-4000-8000-000000000001","sequence":1,"selection_digest":"$digest","preview":null}
        """.trimIndent()
        val withoutPreview = """
            {"server_id":"$SERVER_ID","release_epoch":"00000000-0000-4000-8000-000000000001","sequence":1,"selection_digest":"$digest","stable":null}
        """.trimIndent()
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiReleaseView>(withoutStable)
        }
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiReleaseView>(withoutPreview)
        }
    }

    @Test
    fun `release decoder rejects missing or unknown fields`() {
        val digest = mobileWebUiSelectionDigest(SERVER_ID, null, null)
        val missing = JsonObject(
            mapOf(
                "server_id" to JsonPrimitive(SERVER_ID),
                "release_epoch" to JsonPrimitive("00000000-0000-4000-8000-000000000001"),
                "sequence" to JsonPrimitive(1),
                "selection_digest" to JsonPrimitive(digest),
                "preview" to JsonNull,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            decodeMobileWebUiReleaseView(missing)
        }
        val unknown = JsonObject(
            mapOf(
                "server_id" to JsonPrimitive(SERVER_ID),
                "release_epoch" to JsonPrimitive("00000000-0000-4000-8000-000000000001"),
                "sequence" to JsonPrimitive(1),
                "selection_digest" to JsonPrimitive(digest),
                "stable" to JsonNull,
                "preview" to JsonNull,
                "unexpected" to JsonPrimitive(true),
            ),
        )
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            decodeMobileWebUiReleaseView(unknown)
        }
    }

    @Test
    fun `release hint decoder requires a valid selection digest`() {
        val payload = buildJsonObject {
            put("server_id", SERVER_ID)
            put("selection_digest", "not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeMobileWebUiReleaseChangedPayload(payload)
        }
    }

    @Test
    fun `same digest cannot be advertised with two MIME types in one generation`() {
        val base = validManifest()
        val duplicateMime = base.copy(
            generationId = "0".repeat(64),
            files = listOf(
                base.files.single().copy(path = "empty.txt", mime = "text/plain"),
                base.files.single(),
            ),
            fileCount = 2,
        ).let { it.copy(generationId = mobileWebUiGenerationIdentityDigest(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiManifest(duplicateMime)
        }
    }

    @Test
    fun `same digest cannot be advertised with two sizes in one generation`() {
        val base = validManifest()
        val duplicateSize = base.copy(
            generationId = "0".repeat(64),
            files = listOf(
                base.files.single().copy(path = "empty.txt", sizeBytes = 0),
                base.files.single().copy(path = "other.txt", sizeBytes = 1),
            ),
            fileCount = 2,
        ).let { it.copy(generationId = mobileWebUiGenerationIdentityDigest(it)) }
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiManifest(duplicateSize)
        }
    }

    @Test
    fun `golden suffix MIME mapping matches core`() {
        val html = "<html/>".toByteArray()
        val js = "console.log(1)".toByteArray()
        val css = "body{}".toByteArray()
        val files = listOf(
            MobileWebUiManifestFile("app.js", js.sha256(), js.size.toLong(), "text/javascript"),
            MobileWebUiManifestFile("mobile.html", html.sha256(), html.size.toLong(), "text/html"),
            MobileWebUiManifestFile("style.css", css.sha256(), css.size.toLong(), "text/css"),
        )
        val base = validManifest().copy(
            files = files,
            unpackedSizeBytes = files.sumOf { it.sizeBytes },
            fileCount = files.size,
            generationId = "0".repeat(64),
        )
        validateMobileWebUiManifest(base.copy(generationId = mobileWebUiGenerationIdentityDigest(base)))
    }

    @Test
    fun `release epoch and platform tokens are strict ASCII UUID4`() {
        val manifest = validManifest()
        val target = validTarget(manifest, mobileWebUiManifestDigest(manifest))
        val digest = mobileWebUiSelectionDigest(SERVER_ID, target.targetKey, null)
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiReleaseView(
                MobileWebUiReleaseView(SERVER_ID, "epoch-1", 1, digest, target, null),
                SERVER_ID,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiTarget(target.copy(platforms = listOf("安卓")), SERVER_ID)
        }
    }

    @Test
    fun `content grant rejects malformed or oversized bearer tokens`() {
        val manifest = validManifest()
        val target = validTarget(manifest, mobileWebUiManifestDigest(manifest))
        val valid = MobileWebUiContentGrant(
            targetKey = target.targetKey,
            manifestDigest = target.manifestDigest,
            ticket = "ticket",
            expiresAt = "2026-08-03T12:00:00Z",
        )
        validateMobileWebUiContentGrant(valid, target)
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiContentGrant(valid.copy(ticket = "bad ticket"), target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiContentGrant(valid.copy(ticket = "x".repeat(4097)), target)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateMobileWebUiContentGrant(valid.copy(expiresAt = "not-a-date"), target)
        }
    }

    @Test
    fun `unknown client errors retry while exact missing target rejects`() {
        assertEquals(
            MobileWebUiHttpAction.RETRY_AFTER,
            classifyMobileWebUiHttp("manifest", 404),
        )
        assertEquals(
            MobileWebUiHttpAction.RETRY_AFTER,
            classifyMobileWebUiHttp("manifest", 403),
        )
        assertEquals(
            MobileWebUiHttpAction.RETRY_AFTER,
            classifyMobileWebUiHttp("manifest", 400),
        )
        assertEquals(
            MobileWebUiHttpAction.REJECT_TARGET,
            classifyMobileWebUiHttp("manifest", 404, "resource_not_found"),
        )
    }

    @Test
    fun `expired grant timestamp remains syntax-valid and server controls expiry`() {
        val manifest = validManifest()
        val target = validTarget(manifest, mobileWebUiManifestDigest(manifest))
        validateMobileWebUiContentGrant(
            MobileWebUiContentGrant(
                targetKey = target.targetKey,
                manifestDigest = target.manifestDigest,
                ticket = "ticket",
                expiresAt = "2020-01-01T00:00:00Z",
            ),
            target,
        )
    }

    @Test
    fun `native compatibility rejects a verified manifest from the previous protocol generation`() {
        val current = validManifest()
        val previous = current.copy(
            bridgeProtocolMin = 1,
            bridgeProtocolMax = 1,
            snapshotProtocolMin = 7,
            snapshotProtocolMax = 7,
            minimumNativeBuild = MOBILE_WEB_UI_NATIVE_BUILD - 1,
        )

        assertTrue(mobileWebUiManifestSupportsNative(current, MOBILE_WEB_UI_NATIVE_BUILD))
        assertFalse(mobileWebUiManifestSupportsNative(previous, MOBILE_WEB_UI_NATIVE_BUILD))
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this).joinToString("") { "%02x".format(it) }

    private fun validManifest(): MobileWebUiManifest {
        val provisional = MobileWebUiManifest(
            schemaVersion = MOBILE_WEB_UI_MANIFEST_SCHEMA,
            generationId = "0".repeat(64),
            entrypoint = "mobile.html",
            files = listOf(
                MobileWebUiManifestFile(
                    path = "mobile.html",
                    sha256 = emptyDigest(),
                    sizeBytes = 0,
                    mime = "text/html",
                ),
            ),
            bridgeProtocolMin = MOBILE_WEB_UI_BRIDGE_PROTOCOL,
            bridgeProtocolMax = MOBILE_WEB_UI_BRIDGE_PROTOCOL,
            snapshotProtocolMin = MOBILE_WEB_UI_SNAPSHOT_PROTOCOL,
            snapshotProtocolMax = MOBILE_WEB_UI_SNAPSHOT_PROTOCOL,
            minimumNativeBuild = 45,
            platforms = listOf("android"),
            sourceRepository = "https://github.com/kachofugetsu09/akashic-agent",
            sourceCommit = "a".repeat(40),
            sourceTree = "b".repeat(40),
            inputDigest = "c".repeat(64),
            buildContextDigest = "d".repeat(64),
            dirtyProvenance = null,
            reproducible = true,
            builderIdentity = MobileWebUiBuilderIdentity(
                nodeVersion = "v22.23.1",
                npmVersion = "10.9.2",
                packageLockDigest = "e".repeat(64),
                buildScriptDigest = "f".repeat(64),
            ),
            unpackedSizeBytes = 0,
            fileCount = 1,
        )
        return provisional.copy(generationId = mobileWebUiGenerationIdentityDigest(provisional))
    }

    private fun validTarget(manifest: MobileWebUiManifest, digest: String) = MobileWebUiTarget(
        targetKey = deriveMobileWebUiTargetKey(SERVER_ID, manifest.generationId, digest),
        generationId = manifest.generationId,
        manifestDigest = digest,
        manifestSizeBytes = mobileWebUiManifestBytes(manifest).size.toLong(),
        bridgeProtocolMin = manifest.bridgeProtocolMin,
        bridgeProtocolMax = manifest.bridgeProtocolMax,
        snapshotProtocolMin = manifest.snapshotProtocolMin,
        snapshotProtocolMax = manifest.snapshotProtocolMax,
        minimumNativeBuild = manifest.minimumNativeBuild,
        platforms = manifest.platforms,
    )

    private fun emptyDigest() = MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(0)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SERVER_ID = "server-1"
    }
}
