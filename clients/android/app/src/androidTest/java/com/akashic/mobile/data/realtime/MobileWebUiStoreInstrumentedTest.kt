package com.akashic.mobile.data.realtime

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.ServerProfileEntity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileWebUiStoreInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var root: File
    private lateinit var store: MobileWebUiStore

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        root = context.cacheDir.resolve("mobile-webui-store-test-${System.nanoTime()}")
        store = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        database.serverProfiles().upsert(
            ServerProfileEntity(
                serverId = SERVER_ID,
                displayName = "测试电脑",
                deviceId = "device",
                keyAlias = "alias",
                applicationKeyFingerprint = "fingerprint",
                lanEndpointsJson = "[]",
                tunnelEndpointsJson = "[]",
                tlsSpkiPinsJson = "[]",
                createdAt = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun baselineDesiredDoesNotDiscardPreviouslyHealthyServingOnColdRestore() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val baseline = releaseView(null)
        store.setDesired(SERVER_ID, baseline, null)

        val restored = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        assertTrue(restored.restoreCommittedServing(SERVER_ID))
        assertEquals(
            MobileWebUiServing(SERVER_ID, target.generationId),
            restored.serving.value,
        )
        assertEquals(target.generationId, database.mobileWebUi().getState(SERVER_ID)?.servingGenerationId)
    }

    @Test
    fun pinnedCorruptManifestPreventsLocalAndGlobalBlobDeletion() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val generation = requireNotNull(database.mobileWebUi().getGeneration(SERVER_ID, target.generationId))
        val manifestFile = root.resolve(serverHash()).resolve(generation.manifestPath)
        manifestFile.writeText("{corrupt")
        val blobDigest = emptyDigest()
        val blobFile = root.resolve(serverHash()).resolve("blobs").resolve(blobDigest)
        assertTrue(blobFile.isFile)

        val localReport = store.collectGarbage(SERVER_ID, maxGenerations = 1, maxBytes = 1)
        assertEquals(0, localReport.removedBlobs)
        assertTrue("local GC must retain blobs owned by pinned corrupt generation", blobFile.isFile)
        assertNotNull(database.mobileWebUi().getBlob(SERVER_ID, blobDigest))

        val globalReport = store.collectGlobalGarbage(maxBytes = 1)
        assertEquals(0, globalReport.removedBlobs)
        assertTrue("global GC must retain blobs owned by pinned corrupt generation", blobFile.isFile)
        assertNotNull(database.mobileWebUi().getBlob(SERVER_ID, blobDigest))
    }

    @Test
    fun resetDurablyPairsBaselineStateWithExactManualReject() = runBlocking {
        val target = prepareHealthyGeneration(store)
        store.resetServer(
            SERVER_ID,
            manualRejectTarget = target,
            fingerprint = FINGERPRINT,
        )

        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertEquals("baseline", state.desiredChannel)
        assertEquals(null, state.servingGenerationId)
        val reject = database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT)
        assertEquals("manual_reset", reject?.reason)
    }

    @Test
    fun baselineSelectionClearsRemoteServingOnlyAtNextSessionOwnerAction() = runBlocking {
        val target = prepareHealthyGeneration(store)
        store.setDesired(SERVER_ID, releaseView(null), null)
        assertEquals(target.generationId, store.serving.value?.generationId)

        assertTrue(store.applyDesiredBaselineForSession(SERVER_ID))
        assertNull(database.mobileWebUi().getState(SERVER_ID)?.servingGenerationId)
        assertNull(store.serving.value)

        val restored = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        assertTrue(restored.restoreCommittedServing(SERVER_ID))
        assertNull(restored.serving.value)
    }

    @Test
    fun blockedSpaceAdmissionDoesNotWriteBlobAndRecoversAfterBudgetIsAvailable() = runBlocking {
        val content = "space-test".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)

        val blocked = store.prepareDownloadSpace(SERVER_ID, manifest, serverBudgetBytes = 1)
        assertFalse(blocked.allowed)
        assertEquals(content.size.toLong(), blocked.requiredBytes)
        assertFalse(root.resolve(serverHash()).resolve("blobs").resolve(contentDigest(content)).exists())

        val allowed = store.prepareDownloadSpace(SERVER_ID, manifest)
        assertTrue(allowed.allowed)
        store.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        assertTrue(store.hasBlob(SERVER_ID, contentDigest(content), content.size.toLong()))
    }

    @Test
    fun rejectedOpenDoesNotLeaveAttemptMarker() = runBlocking {
        val target = prepareHealthyGeneration(store)
        store.markRejected(SERVER_ID, target, FINGERPRINT, "manual_reset")

        assertFalse(store.openAttempt(SERVER_ID, target.generationId, "rejected", FINGERPRINT))
        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertNull(state.attemptingGenerationId)
        assertNull(state.attemptingNonce)
        assertEquals(target.generationId, state.servingGenerationId)
    }

    @Test
    fun rollbackAndRejectAtomicallyClearsAttemptAndRejectsCandidate() = runBlocking {
        val target = prepareHealthyGeneration(store)
        store.clearServingToBaseline(SERVER_ID)
        assertTrue(store.openAttempt(SERVER_ID, target.generationId, "candidate", FINGERPRINT))

        assertNull(
            store.rollbackAttemptAndReject(
                SERVER_ID,
                target.generationId,
                "candidate",
                FINGERPRINT,
                "health_timeout",
            ),
        )
        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertNull(state.attemptingGenerationId)
        assertNull(state.attemptingNonce)
        assertNull(state.servingGenerationId)
        assertEquals("health_timeout", database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT)?.reason)
    }

    private suspend fun prepareHealthyGeneration(store: MobileWebUiStore): MobileWebUiTarget {
        val manifest = validManifest()
        val bytes = mobileWebUiManifestBytes(manifest)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, bytes)
        store.ensureEmptyBlob(SERVER_ID, emptyDigest())
        store.setDesired(SERVER_ID, releaseView(target), target)
        assertTrue(store.openAttempt(SERVER_ID, target.generationId, NONCE, FINGERPRINT))
        store.commitHealthy(SERVER_ID, target.generationId, NONCE)
        return target
    }

    private fun targetFor(manifest: MobileWebUiManifest): MobileWebUiTarget {
        val bytes = mobileWebUiManifestBytes(manifest)
        val digest = mobileWebUiManifestDigest(manifest)
        return MobileWebUiTarget(
            targetKey = deriveMobileWebUiTargetKey(SERVER_ID, manifest.generationId, digest),
            generationId = manifest.generationId,
            manifestDigest = digest,
            manifestSizeBytes = bytes.size.toLong(),
            bridgeProtocolMin = manifest.bridgeProtocolMin,
            bridgeProtocolMax = manifest.bridgeProtocolMax,
            snapshotProtocolMin = manifest.snapshotProtocolMin,
            snapshotProtocolMax = manifest.snapshotProtocolMax,
            minimumNativeBuild = manifest.minimumNativeBuild,
            platforms = manifest.platforms,
        )
    }

    private fun manifestWithFile(content: ByteArray): MobileWebUiManifest {
        val provisional = validManifest().copy(
            files = listOf(
                MobileWebUiManifestFile(
                    path = "mobile.html",
                    sha256 = contentDigest(content),
                    sizeBytes = content.size.toLong(),
                    mime = "text/html",
                ),
            ),
            unpackedSizeBytes = content.size.toLong(),
            fileCount = 1,
            generationId = "0".repeat(64),
        )
        return provisional.copy(generationId = mobileWebUiGenerationIdentityDigest(provisional))
    }

    private fun contentDigest(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }

    private fun releaseView(target: MobileWebUiTarget?): MobileWebUiReleaseView =
        MobileWebUiReleaseView(
            serverId = SERVER_ID,
            releaseEpoch = "00000000-0000-4000-8000-000000000001",
            sequence = 1,
            selectionDigest = mobileWebUiSelectionDigest(SERVER_ID, target?.targetKey, null),
            stable = target,
            preview = null,
        )

    private fun validManifest(): MobileWebUiManifest {
        val provisional = MobileWebUiManifest(
            schemaVersion = MOBILE_WEB_UI_MANIFEST_SCHEMA,
            generationId = "0".repeat(64),
            entrypoint = "mobile.html",
            files = listOf(
                MobileWebUiManifestFile("mobile.html", emptyDigest(), 0, "text/html"),
            ),
            bridgeProtocolMin = MOBILE_WEB_UI_BRIDGE_PROTOCOL,
            bridgeProtocolMax = MOBILE_WEB_UI_BRIDGE_PROTOCOL,
            snapshotProtocolMin = MOBILE_WEB_UI_SNAPSHOT_PROTOCOL,
            snapshotProtocolMax = MOBILE_WEB_UI_SNAPSHOT_PROTOCOL,
            minimumNativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
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

    private fun emptyDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(0)).joinToString("") { "%02x".format(it) }

    private fun serverHash(): String = MessageDigest.getInstance("SHA-256")
        .digest(SERVER_ID.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SERVER_ID = "server-1"
        const val NONCE = "test-nonce"
        const val FINGERPRINT = "android-45-1-7"
    }
}
