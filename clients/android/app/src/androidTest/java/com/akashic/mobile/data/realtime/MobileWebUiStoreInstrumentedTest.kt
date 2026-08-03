package com.akashic.mobile.data.realtime

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.database.sqlite.SQLiteException
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.ServerProfileEntity
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun resetFsyncsRootAfterEachJournalMutation() = runBlocking {
        val target = prepareHealthyGeneration(store)
        var syncCalls = 0
        val durableStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            syncRootDirectory = { syncCalls += 1 },
        )

        durableStore.resetServer(SERVER_ID, manualRejectTarget = target, fingerprint = FINGERPRINT)

        assertEquals(5, syncCalls)
        assertEquals("baseline", database.mobileWebUi().getState(SERVER_ID)?.desiredChannel)
    }

    @Test
    fun resetWithDefaultDirectoryFsyncCompletesOnPrivateRoot() = runBlocking {
        val target = prepareHealthyGeneration(store)

        store.resetServer(SERVER_ID, manualRejectTarget = target, fingerprint = FINGERPRINT)

        assertEquals("baseline", database.mobileWebUi().getState(SERVER_ID)?.desiredChannel)
        assertFalse(root.resolve("${serverHash()}.reset.json").exists())
    }

    @Test
    fun resetRootFsyncFailureLeavesJournalAndRoomOwners() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            syncRootDirectory = { throw IOException("injected root fsync failure") },
        )

        assertThrows(IOException::class.java) {
            runBlocking {
                failingStore.resetServer(SERVER_ID, manualRejectTarget = target, fingerprint = FINGERPRINT)
            }
        }

        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertEquals(target.generationId, state.servingGenerationId)
        assertEquals(target.generationId, state.desiredGenerationId)
        assertNull(database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT))
        assertTrue(root.resolve("${serverHash()}.reset.json").isFile)
        assertTrue(root.resolve(serverHash()).isDirectory)
    }

    @Test
    fun resetDeleteFailurePreservesRoomOwnersAndDoesNotWriteManualReject() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val generation = requireNotNull(database.mobileWebUi().getGeneration(SERVER_ID, target.generationId))
        val generationDirectory = requireNotNull(root.resolve(serverHash()).resolve(generation.manifestPath).parentFile)
        val blob = root.resolve(serverHash()).resolve("blobs").resolve(emptyDigest())
        val trashBlob = root.resolve("${serverHash()}.reset-trash").resolve("blobs").resolve(emptyDigest())
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deleteDirectory = { false },
        )

        assertThrows(IOException::class.java) {
            runBlocking {
                failingStore.resetServer(
                    SERVER_ID,
                    manualRejectTarget = target,
                    fingerprint = FINGERPRINT,
                )
            }
        }
        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertEquals(target.generationId, state.servingGenerationId)
        assertEquals(target.generationId, state.desiredGenerationId)
        assertNull(database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT))
        assertFalse(generationDirectory.exists())
        assertTrue(root.resolve("${serverHash()}.reset-trash").isDirectory)
        assertTrue(root.resolve("${serverHash()}.reset.json").isFile)
        assertFalse(blob.exists())
        assertTrue(trashBlob.isFile)
        assertNull(failingStore.serving.value)
    }

    @Test
    fun resetDatabaseFailureLeavesMarkerAndReopenReconcilesWithoutServingDeletedFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database.close()
        val databaseFile = context.cacheDir.resolve("mobile-webui-reset-db-${System.nanoTime()}.db")
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries()
            .build()
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
        val fileStore = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        val target = prepareHealthyGeneration(fileStore)
        database.close()

        val failure = assertThrows(Exception::class.java) {
            runBlocking {
                fileStore.resetServer(SERVER_ID, manualRejectTarget = target, fingerprint = FINGERPRINT)
            }
        }
        assertTrue(
            "closed Room database must fail the reset transaction",
            failure is IllegalStateException || failure is SQLiteException,
        )
        assertNull(fileStore.serving.value)
        assertTrue(root.resolve("${serverHash()}.reset.json").isFile)

        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val recoveryStore = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        recoveryStore.reconcile(SERVER_ID)
        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertEquals("baseline", state.desiredChannel)
        assertNull(state.servingGenerationId)
        assertNull(database.mobileWebUi().getGeneration(SERVER_ID, target.generationId))
        assertNull(database.mobileWebUi().getBlob(SERVER_ID, emptyDigest()))
        assertEquals("manual_reset", database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT)?.reason)
        assertFalse(root.resolve("${serverHash()}.reset.json").exists())
        database.close()
        databaseFile.delete()
        databaseFile.resolveSibling("${databaseFile.name}-shm").delete()
        databaseFile.resolveSibling("${databaseFile.name}-wal").delete()
        Unit
    }

    @Test
    fun resetMarkerDeleteFailureReportsCommittedResetAndReplaysCleanup() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deleteResetMarker = { false },
        )

        assertThrows(MobileWebUiResetCleanupPendingException::class.java) {
            runBlocking {
                failingStore.resetServer(SERVER_ID, manualRejectTarget = target, fingerprint = FINGERPRINT)
            }
        }
        assertEquals("baseline", database.mobileWebUi().getState(SERVER_ID)?.desiredChannel)
        assertEquals("manual_reset", database.mobileWebUi().getReject(SERVER_ID, target.targetKey, FINGERPRINT)?.reason)
        assertTrue(root.resolve("${serverHash()}.reset.json").isFile)

        val recoveryStore = MobileWebUiStore(root, database.mobileWebUi(), nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)
        recoveryStore.reconcile(SERVER_ID)
        assertFalse(root.resolve("${serverHash()}.reset.json").exists())
    }

    @Test
    fun manifestPublishRenameFailureIsObservableWithoutRoomOwner() = runBlocking {
        val manifest = validManifest()
        val target = targetFor(manifest)
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            renameFile = { _, _ -> false },
        )

        assertThrows(IOException::class.java) {
            runBlocking { failingStore.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest)) }
        }
        assertNull(database.mobileWebUi().getGeneration(SERVER_ID, target.generationId))
    }

    @Test
    fun blobPublishRenameAndDestinationDeleteFailuresAreObservable() = runBlocking {
        val content = "rename-failure".toByteArray()
        val digest = contentDigest(content)
        val renameFailStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            renameFile = { _, _ -> false },
        )
        assertThrows(IOException::class.java) {
            runBlocking { renameFailStore.appendBlob(SERVER_ID, digest, content.size.toLong(), 0, content) }
        }
        assertFalse(root.resolve(serverHash()).resolve("blobs").resolve(digest).exists())

        val deleteDigest = contentDigest("delete-failure".toByteArray())
        val deleteContent = "delete-failure".toByteArray()
        val corruptDestination = root.resolve(serverHash()).resolve("blobs").resolve(deleteDigest)
        val corruptParent = requireNotNull(corruptDestination.parentFile)
        assertTrue(corruptParent.isDirectory || corruptParent.mkdirs())
        corruptDestination.writeText("corrupt")
        val deleteFailStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deletePartialFile = { false },
        )
        assertThrows(IOException::class.java) {
            runBlocking { deleteFailStore.appendBlob(SERVER_ID, deleteDigest, deleteContent.size.toLong(), 0, deleteContent) }
        }
        assertTrue(corruptDestination.isFile)
    }

    @Test
    fun partialCleanupFailureIsObservable() = runBlocking {
        val content = "partial".toByteArray()
        val digest = contentDigest(content)
        store.blobOffset(SERVER_ID, digest, content.size.toLong())
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deletePartialFile = { false },
        )

        assertThrows(IOException::class.java) {
            runBlocking { failingStore.resetBlobPartial(SERVER_ID, digest, content.size.toLong()) }
        }
        Unit
    }

    @Test
    fun completePartialIsVerifiedAndPublishedAfterRestart() = runBlocking {
        val content = "crash-safe-part".toByteArray()
        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )

        assertEquals(0L, store.blobOffset(SERVER_ID, digest, content.size.toLong()))
        assertTrue(store.hasBlob(SERVER_ID, digest, content.size.toLong()))
        assertNotNull(database.mobileWebUi().getBlob(SERVER_ID, digest))
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertFalse(partialDirectory.resolve("$digest.meta").exists())
    }

    @Test
    fun corruptCompletePartialIsDiscardedAndRecreatedAtZero() = runBlocking {
        val expected = "crash-safe-part".toByteArray()
        val corrupt = "crash-safe-data".toByteArray()
        assertEquals(expected.size, corrupt.size)
        val digest = contentDigest(expected)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(corrupt)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${expected.size}}",
        )

        assertEquals(0L, store.blobOffset(SERVER_ID, digest, expected.size.toLong()))
        assertFalse(store.hasBlob(SERVER_ID, digest, expected.size.toLong()))
        assertTrue(partialDirectory.resolve("$digest.meta").isFile)
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertNull(database.mobileWebUi().getBlob(SERVER_ID, digest))
    }

    @Test
    fun concurrentPresentationReadsOnlyServeMatchingGenerationManifest() = runBlocking {
        val firstContent = "presentation-a".toByteArray()
        val secondContent = "presentation-b".toByteArray()
        val first = prepareHealthyFileGeneration(firstContent)
        val second = prepareHealthyFileGeneration(secondContent)
        val firstPath = "${serverHash()}/${first.generationId}/mobile.html"
        val secondPath = "${serverHash()}/${second.generationId}/mobile.html"
        val stop = AtomicBoolean(false)
        val mismatches = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        val writer = thread(start = true, name = "webui-presentation-writer") {
            try {
                runBlocking {
                    repeat(200) { index ->
                        val target = if (index % 2 == 0) first else second
                        store.setDesired(SERVER_ID, releaseView(target), target)
                        val nonce = "presentation-$index"
                        assertTrue(store.openAttempt(SERVER_ID, target.generationId, nonce, FINGERPRINT))
                        store.commitHealthy(SERVER_ID, target.generationId, nonce)
                    }
                }
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                stop.set(true)
            }
        }
        while (!stop.get()) {
            assertPresentationBody(firstPath, firstContent, mismatches)
            assertPresentationBody(secondPath, secondContent, mismatches)
        }
        writer.join()
        failure.get()?.let { throw AssertionError("presentation writer failed", it) }
        assertEquals(0, mismatches.get())
    }

    @Test
    fun garbageCollectionRemovesOrphanGenerationWithoutRoomOwner() = runBlocking {
        prepareHealthyGeneration(store)
        val orphan = root.resolve(serverHash()).resolve("generations").resolve("orphan-generation")
        assertTrue(orphan.mkdirs())
        orphan.resolve("manifest.json").writeText("orphan")

        val report = store.collectGarbage(SERVER_ID, maxGenerations = 4, maxBytes = 256 * 1024 * 1024)

        assertTrue(report.removedGenerations >= 1)
        assertFalse(orphan.exists())
    }

    @Test
    fun garbageCollectionReportsOrphanGenerationDeleteFailureWithoutFalseRemoval() = runBlocking {
        prepareHealthyGeneration(store)
        val orphan = root.resolve(serverHash()).resolve("generations").resolve("orphan-generation")
        assertTrue(orphan.mkdirs())
        orphan.resolve("manifest.json").writeText("orphan")
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deleteDirectory = { false },
        )

        val report = failingStore.collectGarbage(SERVER_ID, maxGenerations = 4, maxBytes = 256 * 1024 * 1024)

        assertEquals(0, report.removedGenerations)
        assertTrue(report.blockedGenerations > 0)
        assertTrue(report.unownedFiles > 0)
        assertTrue(orphan.exists())
    }

    @Test
    fun publishBlobRoomFailureLeavesOrphanThatRecoveryGcRemoves() = runBlocking {
        val content = "blob".toByteArray()
        val digest = contentDigest(content)
        val failingDao = object : com.akashic.mobile.data.local.MobileWebUiDao by database.mobileWebUi() {
            override suspend fun upsertBlob(blob: com.akashic.mobile.data.local.MobileWebUiBlobEntity) {
                throw SQLiteException("injected blob owner failure")
            }
        }
        val failingStore = MobileWebUiStore(root, failingDao, nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD)

        assertThrows(SQLiteException::class.java) {
            runBlocking { failingStore.appendBlob(SERVER_ID, digest, content.size.toLong(), 0, content) }
        }
        val orphan = root.resolve(serverHash()).resolve("blobs").resolve(digest)
        assertTrue(orphan.isFile)
        assertNull(database.mobileWebUi().getBlob(SERVER_ID, digest))

        val report = store.collectGarbage(SERVER_ID, maxGenerations = 1, maxBytes = 256 * 1024 * 1024)
        assertTrue(report.removedBytes >= content.size)
        assertFalse(orphan.exists())
    }

    @Test
    fun globalGarbageCollectionRemovesProfilelessOrphanAndRetainsMalformedRoot() = runBlocking {
        prepareHealthyGeneration(store)
        val unknownRoot = root.resolve("a".repeat(64)).resolve("blobs")
        assertTrue(unknownRoot.mkdirs())
        val content = "unknown".toByteArray()
        val digest = contentDigest(content)
        val unknownBlob = unknownRoot.resolve(digest)
        unknownBlob.writeBytes(content)
        val malformed = root.resolve("not-a-server-root")
        assertTrue(malformed.mkdirs())
        malformed.resolve("orphan").writeText("keep")

        val report = store.collectGlobalGarbage(maxBytes = 1)

        assertTrue(report.blockedGenerations > 0)
        assertTrue(report.unownedFiles > 0)
        assertTrue(report.removedBytes >= content.size)
        assertFalse(unknownBlob.exists())
        assertTrue(malformed.exists())
    }

    @Test
    fun globalGarbageCollectionRetainsRootsOwnedByAnyResetJournal() = runBlocking {
        val journalSuffixes = listOf(".reset.json", ".reset.json.tmp", ".reset-trash")
        val hashes = listOf("b", "c", "d").map { it.repeat(64) }
        val orphanFiles = hashes.map { hash ->
            val directory = root.resolve(hash).resolve("blobs")
            assertTrue(directory.mkdirs())
            directory.resolve(emptyDigest()).apply { writeText("reset-owned") }
        }
        hashes.zip(journalSuffixes).forEach { (hash, suffix) ->
            val journal = root.resolve(hash + suffix)
            if (suffix == ".reset-trash") {
                assertTrue(journal.mkdirs())
                journal.resolve("orphan").writeText("reset-owned")
            } else {
                journal.writeText("{}")
            }
        }

        val report = store.collectGlobalGarbage(maxBytes = 1)

        orphanFiles.forEach { orphan -> assertTrue(orphan.isFile) }
        hashes.zip(journalSuffixes).forEach { (hash, suffix) ->
            assertTrue(root.resolve(hash + suffix).exists())
        }
        assertEquals(0L, report.removedBytes)
        assertTrue(report.unownedFiles > 0)
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
    fun completeOwnedPartialCountsAsAlreadyDownloadedForSpaceAdmission() = runBlocking {
        val content = "admission-complete-part".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )
        val occupied = root.walkTopDown().filter(File::isFile).sumOf(File::length)

        val admission = store.prepareDownloadSpace(
            SERVER_ID,
            manifest,
            serverBudgetBytes = occupied,
            globalBudgetBytes = occupied,
        )

        assertTrue(admission.allowed)
        assertEquals(0L, admission.requiredBytes)
        assertTrue(admission.serverBytes <= occupied)
        assertTrue(partialDirectory.resolve("$digest.part").isFile)
    }

    @Test
    fun corruptCompletePartialIsRemovedBeforeSpaceAdmission() = runBlocking {
        val expected = "admission-complete-part".toByteArray()
        val corrupt = expected.copyOf().also { it[0] = 'x'.code.toByte() }
        val manifest = manifestWithFile(expected)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(expected)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(corrupt)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${expected.size}}",
        )

        val admission = store.prepareDownloadSpace(SERVER_ID, manifest, serverBudgetBytes = 1)

        assertFalse(admission.allowed)
        assertEquals(expected.size.toLong(), admission.requiredBytes)
        assertEquals("server_budget", admission.blockedReason)
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertFalse(partialDirectory.resolve("$digest.meta").exists())
    }

    @Test
    fun localGarbageCollectionAccountsForRetainedPrefixBeforeAdmission() = runBlocking {
        val oldTarget = prepareHealthyGeneration(store)
        store.clearServingToBaseline(SERVER_ID)
        val content = "gc-retained-prefix".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val prefix = content.copyOf(content.size / 2)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(prefix)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )
        val orphanPayload = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val orphanDigest = contentDigest(orphanPayload)
        val orphanBlob = root.resolve(serverHash()).resolve("blobs").resolve(orphanDigest)
        orphanBlob.writeBytes(orphanPayload.copyOf().also { it[0] = 'y'.code.toByte() })
        val oldGeneration = requireNotNull(database.mobileWebUi().getGeneration(SERVER_ID, oldTarget.generationId))
        val oldDirectory = requireNotNull(root.resolve(serverHash()).resolve(oldGeneration.manifestPath).parentFile)
        val oldBlob = root.resolve(serverHash()).resolve("blobs").resolve(emptyDigest())
        val oldBytes = oldDirectory.walkTopDown().filter(File::isFile).sumOf(File::length) + oldBlob.length()
        val currentBytes = root.resolve(serverHash()).walkTopDown().filter(File::isFile).sumOf(File::length)
        val budget = currentBytes - oldBytes + (content.size - prefix.size)

        val report = store.collectGarbage(SERVER_ID, maxBytes = budget)

        assertTrue(report.removedGenerations > 0)
        assertTrue(report.unownedFiles > 0)
        assertFalse(oldDirectory.exists())
        assertTrue(orphanBlob.isFile)
        assertEquals(1, report.removedBlobs)
        val admission = store.prepareDownloadSpace(
            SERVER_ID,
            manifest,
            serverBudgetBytes = budget,
            globalBudgetBytes = budget,
        )
        assertTrue(admission.allowed)
        assertEquals((content.size - prefix.size).toLong(), admission.requiredBytes)
        assertTrue(admission.serverBytes + admission.requiredBytes <= budget)
        assertEquals(
            root.resolve(serverHash()).walkTopDown().filter(File::isFile).sumOf(File::length),
            admission.serverBytes,
        )
    }

    @Test
    fun globalGarbageUsesRetainedPartialInPhysicalBudget() = runBlocking {
        val content = "global-retained-prefix".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val prefix = content.copyOf(content.size / 2)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(prefix)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )

        val serverB = "server-2"
        val oldContent = "global-old-generation-content".toByteArray()
        val oldManifest = manifestWithFile(oldContent)
        val oldTarget = targetFor(oldManifest, serverB)
        store.commitManifest(serverB, oldTarget, mobileWebUiManifestBytes(oldManifest))
        store.setDesired(serverB, releaseView(serverB, oldTarget), oldTarget)
        store.appendBlob(serverB, contentDigest(oldContent), oldContent.size.toLong(), 0, oldContent)
        assertTrue(store.openAttempt(serverB, oldTarget.generationId, "global-old", FINGERPRINT))
        store.commitHealthy(serverB, oldTarget.generationId, "global-old")
        store.clearServingToBaseline(serverB)
        store.setDesired(serverB, releaseView(serverB, null), null)

        val oldGeneration = requireNotNull(database.mobileWebUi().getGeneration(serverB, oldTarget.generationId))
        val oldDirectory = requireNotNull(root.resolve(serverHash(serverB)).resolve(oldGeneration.manifestPath).parentFile)
        val oldBlob = root.resolve(serverHash(serverB)).resolve("blobs").resolve(contentDigest(oldContent))
        val oldBytes = oldDirectory.walkTopDown().filter(File::isFile).sumOf(File::length) + oldBlob.length()
        val currentBytes = root.walkTopDown().filter(File::isFile).sumOf(File::length)
        val remaining = content.size - prefix.size
        val budget = currentBytes - oldBytes + remaining

        val report = store.collectGlobalGarbage(maxBytes = budget)

        assertTrue(report.removedGenerations > 0)
        assertTrue(report.removedBlobs > 0)
        assertFalse(oldDirectory.exists())
        assertFalse(oldBlob.exists())
        assertTrue(root.walkTopDown().filter(File::isFile).sumOf(File::length) <= budget)
        val admission = store.prepareDownloadSpace(
            SERVER_ID,
            manifest,
            serverBudgetBytes = Long.MAX_VALUE,
            globalBudgetBytes = budget,
        )
        assertTrue(admission.allowed)
        assertEquals(remaining.toLong(), admission.requiredBytes)
    }

    @Test
    fun malformedPartialMetadataIsRemovedAndRequiresFullSpace() = runBlocking {
        val content = "malformed-partial".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content.copyOf(content.size / 2))
        partialDirectory.resolve("$digest.meta").writeText("{malformed")

        val admission = store.prepareDownloadSpace(SERVER_ID, manifest, serverBudgetBytes = 1)

        assertFalse(admission.allowed)
        assertEquals(content.size.toLong(), admission.requiredBytes)
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertFalse(partialDirectory.resolve("$digest.meta").exists())
    }

    @Test
    fun duplicatePartialMetadataKeyIsRemovedAndRequiresFullSpace() = runBlocking {
        val content = "duplicate-partial".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content.copyOf(content.size / 2))
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )

        val admission = store.prepareDownloadSpace(SERVER_ID, manifest, serverBudgetBytes = 1)

        assertFalse(admission.allowed)
        assertEquals(content.size.toLong(), admission.requiredBytes)
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertFalse(partialDirectory.resolve("$digest.meta").exists())
    }

    @Test
    fun partialMetadataSizeMismatchIsRemovedAndRequiresFullSpace() = runBlocking {
        val content = "mismatched-partial".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content.copyOf(content.size / 2))
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size + 1}}",
        )

        val admission = store.prepareDownloadSpace(SERVER_ID, manifest, serverBudgetBytes = 1)

        assertFalse(admission.allowed)
        assertEquals(content.size.toLong(), admission.requiredBytes)
        assertFalse(partialDirectory.resolve("$digest.part").exists())
        assertFalse(partialDirectory.resolve("$digest.meta").exists())
    }

    @Test
    fun duplicateDigestCountsOnceForDownloadSpaceAdmission() = runBlocking {
        val content = "space-test".toByteArray()
        val manifest = manifestWithDuplicateFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)

        val admission = store.prepareDownloadSpace(SERVER_ID, manifest)
        assertEquals(content.size.toLong(), admission.requiredBytes)
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

    @Test
    fun candidateLeaseIsProcessStateWhileServingRemainsCommitted() = runBlocking {
        val target = prepareHealthyGeneration(store)
        store.clearServingToBaseline(SERVER_ID)
        store.setDesired(SERVER_ID, releaseView(target), target)

        assertTrue(store.openAttempt(SERVER_ID, target.generationId, "candidate", FINGERPRINT))
        assertEquals(
            MobileWebUiAttemptLease(SERVER_ID, target.generationId, "candidate"),
            store.attempt.value,
        )
        assertNull(store.serving.value)

        store.commitHealthy(SERVER_ID, target.generationId, "candidate")
        assertNull(store.attempt.value)
        assertEquals(MobileWebUiServing(SERVER_ID, target.generationId), store.serving.value)
    }

    @Test
    fun corruptBlobDeleteFailurePreservesDatabaseOwner() = runBlocking {
        val target = prepareHealthyGeneration(store)
        val digest = emptyDigest()
        val blob = root.resolve(serverHash()).resolve("blobs").resolve(digest)
        blob.writeBytes("corrupt".toByteArray())
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deletePhysicalFile = { false },
        )

        assertThrows(IOException::class.java) {
            runBlocking { failingStore.readVerifiedManifest(SERVER_ID, target) }
        }
        assertTrue(blob.isFile)
        assertNotNull(database.mobileWebUi().getBlob(SERVER_ID, digest))
    }

    @Test
    fun rollbackStorageFailureFallsBackToBaselineAndPreservesBlobOwner() = runBlocking {
        prepareHealthyGeneration(store)
        val candidateContent = "rollback-failure".toByteArray()
        val candidateManifest = manifestWithFile(candidateContent)
        val candidate = targetFor(candidateManifest)
        store.commitManifest(SERVER_ID, candidate, mobileWebUiManifestBytes(candidateManifest))
        store.setDesired(SERVER_ID, releaseView(candidate), candidate)
        store.appendBlob(
            SERVER_ID,
            contentDigest(candidateContent),
            candidateContent.size.toLong(),
            0,
            candidateContent,
        )
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deletePhysicalFile = { false },
        )
        assertTrue(failingStore.openAttempt(SERVER_ID, candidate.generationId, "candidate", FINGERPRINT))
        val fallbackBlob = root.resolve(serverHash()).resolve("blobs").resolve(emptyDigest())
        fallbackBlob.writeBytes("corrupt".toByteArray())

        val result = failingStore.rollbackAttemptAndRejectResult(
            SERVER_ID,
            candidate.generationId,
            "candidate",
            FINGERPRINT,
            "health_timeout",
        )
        val state = requireNotNull(database.mobileWebUi().getState(SERVER_ID))
        assertTrue(result.fallbackUnavailable)
        assertNull(state.attemptingGenerationId)
        assertNull(state.attemptingNonce)
        assertNull(state.fallbackGenerationId)
        assertNull(failingStore.attempt.value)
        assertNull(failingStore.serving.value)
        assertEquals("fallback_unavailable", database.mobileWebUi().getReject(SERVER_ID, candidate.targetKey, FINGERPRINT)?.reason)
        assertTrue(fallbackBlob.isFile)
    }

    @Test
    fun corruptFallbackManifestIsVisibleAsFallbackUnavailable() = runBlocking {
        val fallback = prepareHealthyGeneration(store)
        val candidateContent = "candidate-corrupt-fallback".toByteArray()
        val candidateManifest = manifestWithFile(candidateContent)
        val candidate = targetFor(candidateManifest)
        store.commitManifest(SERVER_ID, candidate, mobileWebUiManifestBytes(candidateManifest))
        store.setDesired(SERVER_ID, releaseView(candidate), candidate)
        store.appendBlob(SERVER_ID, contentDigest(candidateContent), candidateContent.size.toLong(), 0, candidateContent)
        assertTrue(store.openAttempt(SERVER_ID, candidate.generationId, "candidate", FINGERPRINT))

        val fallbackRow = requireNotNull(database.mobileWebUi().getGeneration(SERVER_ID, fallback.generationId))
        root.resolve(serverHash()).resolve(fallbackRow.manifestPath).writeText("{corrupt")

        val result = store.rollbackAttemptAndRejectResult(
            SERVER_ID,
            candidate.generationId,
            "candidate",
            FINGERPRINT,
            "render_failed",
        )

        assertTrue(result.fallbackUnavailable)
        assertNull(database.mobileWebUi().getState(SERVER_ID)?.servingGenerationId)
        assertEquals("fallback_unavailable", database.mobileWebUi().getReject(SERVER_ID, candidate.targetKey, FINGERPRINT)?.reason)
        assertNull(store.serving.value)
    }

    @Test
    fun candidateOpenStorageFailureStaysInCoordinatorOwnerWithObservableError() = runBlocking {
        val content = "open-failure".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        val failingStore = MobileWebUiStore(
            root,
            database.mobileWebUi(),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            deletePhysicalFile = { false },
        )
        failingStore.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        failingStore.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        failingStore.setDesired(SERVER_ID, releaseView(target), target)
        val errors = mutableListOf<String>()
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = failingStore,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> null },
            startHttp = { },
            onError = errors::add,
            onRetryTrigger = { },
        )
        coordinator.restoreLocal(SERVER_ID)
        root.resolve(serverHash()).resolve("blobs").resolve(contentDigest(content))
            .writeBytes("corrupt".toByteArray())

        assertEquals(null, coordinator.openAttempt(SERVER_ID, target.generationId))
        assertTrue(errors.any { it.contains("候选版本校验失败") })
        assertNull(database.mobileWebUi().getState(SERVER_ID)?.attemptingGenerationId)
        coordinatorJob.cancel()
    }

    @Test
    fun staleServingRollbackCannotReplaceAnActiveCandidateLease() = runBlocking {
        val committed = prepareHealthyGeneration(store)
        val content = "candidate".toByteArray()
        val candidateManifest = manifestWithFile(content)
        val candidate = targetFor(candidateManifest)
        store.commitManifest(SERVER_ID, candidate, mobileWebUiManifestBytes(candidateManifest))
        store.setDesired(SERVER_ID, releaseView(candidate), candidate)
        store.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        assertTrue(store.openAttempt(SERVER_ID, candidate.generationId, "candidate", FINGERPRINT))
        assertEquals(committed.generationId, store.serving.value?.generationId)
        assertEquals(candidate.generationId, store.servingGenerationId())

        assertEquals(
            committed.generationId,
            store.rejectServingAndRollback(SERVER_ID, committed.generationId, FINGERPRINT, "stale"),
        )
        assertEquals(candidate.generationId, store.attempt.value?.generationId)
        assertEquals(candidate.generationId, store.servingGenerationId())

        assertEquals(
            committed.generationId,
            store.rejectServingAndRollback(SERVER_ID, candidate.generationId, FINGERPRINT, "candidate_failed"),
        )
        assertNull(store.attempt.value)
        assertEquals(committed.generationId, store.serving.value?.generationId)
        assertEquals(committed.generationId, store.servingGenerationId())
    }

    @Test
    fun explicitRetryClearsOnlyTheSelectedTargetRejection() = runBlocking {
        val first = prepareHealthyGeneration(store)
        val second = targetFor(manifestWithFile("other".toByteArray()))
        store.markRejected(SERVER_ID, first, FINGERPRINT, "permanent")
        store.markRejected(SERVER_ID, second, FINGERPRINT, "permanent")

        store.clearReject(SERVER_ID, first, FINGERPRINT)

        assertNull(database.mobileWebUi().getReject(SERVER_ID, first.targetKey, FINGERPRINT))
        assertNotNull(database.mobileWebUi().getReject(SERVER_ID, second.targetKey, FINGERPRINT))
    }

    @Test
    fun previewRejectFallbackExposesOnlyCurrentReleaseTargetForExplicitRetry() = runBlocking {
        val stableContent = "stable-current".toByteArray()
        val previewContent = "preview-current".toByteArray()
        val oldContent = "old-not-in-release".toByteArray()
        val stable = targetFor(manifestWithFile(stableContent))
        val preview = targetFor(manifestWithFile(previewContent))
        val old = targetFor(manifestWithFile(oldContent))
        for ((target, content) in listOf(stable to stableContent, preview to previewContent, old to oldContent)) {
            store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifestWithFile(content)))
            store.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        }
        store.markRejected(SERVER_ID, preview, FINGERPRINT, "preview_failed")
        store.markRejected(SERVER_ID, old, FINGERPRINT, "old_target")

        val commandId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> commandId },
            startHttp = { },
            onError = { },
            onRetryTrigger = { },
        )
        val release = releaseView(SERVER_ID, stable, preview)
        coordinator.restoreLocal(SERVER_ID)
        coordinator.resolve(SERVER_ID)
        assertTrue(coordinator.onReply(releaseReply(commandId, release)))
        assertEquals(stable.generationId, database.mobileWebUi().getState(SERVER_ID)?.desiredGenerationId)
        assertTrue(coordinator.retryAvailable.value)
        assertNotNull(database.mobileWebUi().getReject(SERVER_ID, preview.targetKey, FINGERPRINT))
        assertNotNull(database.mobileWebUi().getReject(SERVER_ID, old.targetKey, FINGERPRINT))

        assertTrue(coordinator.retryCurrentUi(SERVER_ID))
        coordinator.resolve(SERVER_ID)
        assertTrue(coordinator.onReply(releaseReply(commandId, release)))
        assertEquals(preview.generationId, database.mobileWebUi().getState(SERVER_ID)?.desiredGenerationId)
        assertNull(database.mobileWebUi().getReject(SERVER_ID, preview.targetKey, FINGERPRINT))
        assertNotNull(database.mobileWebUi().getReject(SERVER_ID, old.targetKey, FINGERPRINT))
        assertFalse(coordinator.retryAvailable.value)
        coordinatorJob.cancel()
    }

    @Test
    fun contentPrepareReplyTypeMismatchClearsEnsureAndRechecksRelease() = runBlocking {
        val target = targetFor(validManifest())
        val commandIds = listOf("release-1", "prepare-1", "release-2")
        var commandIndex = 0
        val errors = mutableListOf<String>()
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> commandIds[commandIndex++] },
            startHttp = { },
            onError = errors::add,
            onRetryTrigger = { },
        )

        coordinator.restoreLocal(SERVER_ID)
        coordinator.resolve(SERVER_ID)
        assertTrue(coordinator.onReply(releaseReply("release-1", releaseView(target))))
        assertEquals(2, commandIndex)

        assertTrue(
            coordinator.onReply(
                WireEnvelope(
                    v = WIRE_PROTOCOL_VERSION,
                    kind = WireKind.REPLY,
                    type = "unexpected.reply",
                    id = "prepare-1",
                    connectionEpoch = 1,
                    payload = JsonObject(emptyMap()),
                ),
            ),
        )
        assertEquals(3, commandIndex)
        assertTrue(errors.any { it.contains("content.prepare reply type") })

        assertTrue(coordinator.onReply(releaseReply("release-2", releaseView(null))))
        assertNull(database.mobileWebUi().getState(SERVER_ID)?.desiredGenerationId)
        coordinatorJob.cancel()
    }

    @Test
    fun completePartialSkipsBlobHttpAfterManifestAdmission() = runBlocking {
        val content = "coordinator-complete-part".toByteArray()
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        val manifestBytes = mobileWebUiManifestBytes(manifest)
        val requests = mutableListOf<MobileWebUiHttpRequest>()
        val commandIds = listOf("release-1", "prepare-1")
        var commandIndex = 0
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> commandIds[commandIndex++] },
            startHttp = requests::add,
            onError = { },
            onRetryTrigger = { },
        )

        coordinator.restoreLocal(SERVER_ID)
        coordinator.resolve(SERVER_ID)
        assertTrue(coordinator.onReply(releaseReply("release-1", releaseView(target))))
        assertTrue(
            coordinator.onReply(
                WireEnvelope(
                    v = WIRE_PROTOCOL_VERSION,
                    kind = WireKind.REPLY,
                    type = "$MOBILE_WEB_UI_CONTENT_PREPARE.ok",
                    id = "prepare-1",
                    connectionEpoch = 1,
                    payload = MOBILE_WEB_UI_JSON.encodeToJsonElement(
                        MobileWebUiContentGrant(
                            targetKey = target.targetKey,
                            manifestDigest = target.manifestDigest,
                            ticket = "ticket",
                            expiresAt = "2026-08-03T12:00:00Z",
                        ),
                    ).jsonObject,
                ),
            ),
        )
        assertEquals(1, requests.size)
        assertEquals("$MOBILE_WEB_UI_MANIFEST_PATH/${target.manifestDigest}", requests.single().path)

        val digest = contentDigest(content)
        val partialDirectory = root.resolve(serverHash()).resolve("partials")
        assertTrue(partialDirectory.mkdirs())
        partialDirectory.resolve("$digest.part").writeBytes(content)
        partialDirectory.resolve("$digest.meta").writeText(
            "{\"sha256\":\"$digest\",\"bytes\":${content.size}}",
        )
        val manifestDigestHeader = digestHeader(mobileWebUiSha256Hex(manifestBytes))
        val manifestRequest = requests.single()

        coordinator.onHttpResponse(
            manifestRequest.requestId,
            MobileWebUiHttpResponse(
                statusCode = 200,
                body = manifestBytes,
                contentLength = manifestBytes.size.toLong(),
                contentRange = null,
                etag = mobileWebUiStrongEtag(target.manifestDigest),
                contentDigest = manifestDigestHeader,
                representationDigest = manifestDigestHeader,
            ),
        )

        assertEquals(target.generationId, coordinator.readyGeneration.value)
        assertTrue(store.hasBlob(SERVER_ID, digest, content.size.toLong()))
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.blobSha256 != null })
        coordinatorJob.cancel()
    }

    @Test
    fun reconnectDoesNotReconcileAwayAProcessOwnedCandidateLease() = runBlocking {
        val coordinatorJob = SupervisorJob()
        val coordinatorScope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined)
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = coordinatorScope,
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> null },
            startHttp = { },
            onError = { },
            onRetryTrigger = { },
        )
        coordinator.restoreLocal(SERVER_ID)
        val content = "reconnect".toByteArray()
        val candidateManifest = manifestWithFile(content)
        val candidate = targetFor(candidateManifest)
        store.commitManifest(SERVER_ID, candidate, mobileWebUiManifestBytes(candidateManifest))
        store.setDesired(SERVER_ID, releaseView(candidate), candidate)
        store.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        assertTrue(store.openAttempt(SERVER_ID, candidate.generationId, "candidate", FINGERPRINT))

        coordinator.restoreLocal(SERVER_ID)

        assertEquals(candidate.generationId, store.attempt.value?.generationId)
        assertEquals(candidate.generationId, store.servingGenerationId())
        coordinatorJob.cancel()
    }

    @Test
    fun serverSwitchCancelsOldCandidateBeforeReturningToItsCommittedFallback() = runBlocking {
        val serverA = SERVER_ID
        val serverB = "server-2"
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> null },
            startHttp = { },
            onError = { },
            onRetryTrigger = { },
        )
        val committedA = prepareHealthyGeneration(store, serverA)
        coordinator.restoreLocal(serverA)
        val candidateContent = "server-a-candidate".toByteArray()
        val candidate = targetFor(manifestWithFile(candidateContent), serverA)
        store.commitManifest(serverA, candidate, mobileWebUiManifestBytes(manifestWithFile(candidateContent)))
        store.setDesired(serverA, releaseView(serverA, candidate), candidate)
        store.appendBlob(
            serverA,
            contentDigest(candidateContent),
            candidateContent.size.toLong(),
            0,
            candidateContent,
        )
        assertTrue(store.openAttempt(serverA, candidate.generationId, "server-a-candidate", FINGERPRINT))
        assertEquals(candidate.generationId, store.servingGenerationId())

        // Switching away must durably cancel A before B is allowed to own the process presentation.
        coordinator.restoreLocal(serverB)
        assertNull(store.attempt.value)
        assertNull(store.serving.value)
        val stateA = requireNotNull(database.mobileWebUi().getState(serverA))
        assertNull(stateA.attemptingGenerationId)
        assertNull(stateA.attemptingNonce)
        assertEquals(committedA.generationId, stateA.servingGenerationId)

        // Returning to A restores only its committed fallback; the rejected candidate is not inherited.
        coordinator.restoreLocal(serverA)
        assertEquals(MobileWebUiServing(serverA, committedA.generationId), store.serving.value)
        assertNull(store.attempt.value)

        // A defensive oracle for a previously lost process lease: durable A attempting state
        // must still be reconciled even though this process already restored A once.
        assertTrue(store.openAttempt(serverA, candidate.generationId, "server-a-stale", FINGERPRINT))
        store.restoreCommittedServing(serverB)
        assertNull(store.attempt.value)
        assertNotNull(database.mobileWebUi().getState(serverA)?.attemptingGenerationId)
        coordinator.restoreLocal(serverA)
        assertNull(store.attempt.value)
        assertEquals(MobileWebUiServing(serverA, committedA.generationId), store.serving.value)
        assertNull(database.mobileWebUi().getState(serverA)?.attemptingGenerationId)
        coordinatorJob.cancel()
    }

    @Test
    fun releaseTargetChangeRollsBackCandidateBeforePreparingNextTarget() = runBlocking {
        val coordinatorJob = SupervisorJob()
        val coordinator = MobileWebUiCoordinator(
            store = store,
            scope = CoroutineScope(coordinatorJob + Dispatchers.Unconfined),
            nativeBuild = MOBILE_WEB_UI_NATIVE_BUILD,
            sendCommand = { _, _ -> "01ARZ3NDEKTSV4RRFFQ69G5FAV" },
            startHttp = { },
            onError = { },
            onRetryTrigger = { },
        )
        val committed = prepareHealthyGeneration(store)
        coordinator.restoreLocal(SERVER_ID)
        val candidateContent = "candidate-change".toByteArray()
        val candidateManifest = manifestWithFile(candidateContent)
        val candidate = targetFor(candidateManifest)
        store.commitManifest(SERVER_ID, candidate, mobileWebUiManifestBytes(candidateManifest))
        store.setDesired(SERVER_ID, releaseView(candidate), candidate)
        store.appendBlob(SERVER_ID, contentDigest(candidateContent), candidateContent.size.toLong(), 0, candidateContent)
        assertTrue(store.openAttempt(SERVER_ID, candidate.generationId, "candidate", FINGERPRINT))

        val nextTarget = targetFor(manifestWithFile("next".toByteArray()))
        val nextView = releaseView(nextTarget)
        coordinator.resolve(SERVER_ID)
        val reply = WireEnvelope(
            v = WIRE_PROTOCOL_VERSION,
            kind = WireKind.REPLY,
            type = "$MOBILE_WEB_UI_RELEASE_GET.ok",
            id = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
            connectionEpoch = 1,
            payload = MOBILE_WEB_UI_JSON.encodeToJsonElement(nextView).jsonObject,
        )
        assertTrue(coordinator.onReply(reply))

        assertNull(store.attempt.value)
        assertEquals(committed.generationId, store.servingGenerationId())
        assertEquals(nextTarget.generationId, database.mobileWebUi().getState(SERVER_ID)?.desiredGenerationId)
        coordinatorJob.cancel()
    }

    private suspend fun prepareHealthyGeneration(
        store: MobileWebUiStore,
        serverId: String = SERVER_ID,
    ): MobileWebUiTarget {
        val manifest = validManifest()
        val bytes = mobileWebUiManifestBytes(manifest)
        val target = targetFor(manifest, serverId)
        store.commitManifest(serverId, target, bytes)
        store.ensureEmptyBlob(serverId, emptyDigest())
        store.setDesired(serverId, releaseView(serverId, target), target)
        assertTrue(store.openAttempt(serverId, target.generationId, NONCE, FINGERPRINT))
        store.commitHealthy(serverId, target.generationId, NONCE)
        return target
    }

    private suspend fun prepareHealthyFileGeneration(content: ByteArray): MobileWebUiTarget {
        val manifest = manifestWithFile(content)
        val target = targetFor(manifest)
        store.commitManifest(SERVER_ID, target, mobileWebUiManifestBytes(manifest))
        store.setDesired(SERVER_ID, releaseView(target), target)
        store.appendBlob(SERVER_ID, contentDigest(content), content.size.toLong(), 0, content)
        val nonce = "presentation-initial-${target.generationId}"
        assertTrue(store.openAttempt(SERVER_ID, target.generationId, nonce, FINGERPRINT))
        store.commitHealthy(SERVER_ID, target.generationId, nonce)
        return target
    }

    private fun assertPresentationBody(path: String, expected: ByteArray, mismatches: AtomicInteger) {
        val response = store.handlePresentation(path) ?: return
        val body = requireNotNull(response.data).use { it.readBytes() }
        if (!body.contentEquals(expected)) mismatches.incrementAndGet()
    }

    private fun targetFor(
        manifest: MobileWebUiManifest,
        serverId: String = SERVER_ID,
    ): MobileWebUiTarget {
        val bytes = mobileWebUiManifestBytes(manifest)
        val digest = mobileWebUiManifestDigest(manifest)
        return MobileWebUiTarget(
            targetKey = deriveMobileWebUiTargetKey(serverId, manifest.generationId, digest),
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

    private fun manifestWithDuplicateFile(content: ByteArray): MobileWebUiManifest {
        val digest = contentDigest(content)
        val file = MobileWebUiManifestFile("mobile.html", digest, content.size.toLong(), "text/html")
        val duplicate = MobileWebUiManifestFile("other.html", digest, content.size.toLong(), "text/html")
        val provisional = validManifest().copy(
            files = listOf(file, duplicate),
            unpackedSizeBytes = content.size.toLong() * 2,
            fileCount = 2,
            generationId = "0".repeat(64),
        )
        return provisional.copy(generationId = mobileWebUiGenerationIdentityDigest(provisional))
    }

    private fun contentDigest(content: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(content).joinToString("") { "%02x".format(it) }

    private fun digestHeader(digest: String): String =
        "sha-256=:${Base64.getEncoder().encodeToString(digest.hexBytes())}:"

    private fun String.hexBytes(): ByteArray = chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    private fun releaseView(target: MobileWebUiTarget?): MobileWebUiReleaseView =
        releaseView(SERVER_ID, target)

    private fun releaseView(
        serverId: String,
        target: MobileWebUiTarget?,
    ): MobileWebUiReleaseView = releaseView(serverId, target, null)

    private fun releaseView(
        serverId: String,
        stable: MobileWebUiTarget?,
        preview: MobileWebUiTarget?,
    ): MobileWebUiReleaseView =
        MobileWebUiReleaseView(
            serverId = serverId,
            releaseEpoch = "00000000-0000-4000-8000-000000000001",
            sequence = 1,
            selectionDigest = mobileWebUiSelectionDigest(
                serverId,
                stable?.targetKey,
                preview?.targetKey,
            ),
            stable = stable,
            preview = preview,
        )

    private fun releaseReply(commandId: String, view: MobileWebUiReleaseView): WireEnvelope = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.REPLY,
        type = "$MOBILE_WEB_UI_RELEASE_GET.ok",
        id = commandId,
        connectionEpoch = 1,
        payload = MOBILE_WEB_UI_JSON.encodeToJsonElement(view).jsonObject,
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

    private fun serverHash(serverId: String = SERVER_ID): String = MessageDigest.getInstance("SHA-256")
        .digest(serverId.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SERVER_ID = "server-1"
        const val NONCE = "test-nonce"
        const val FINGERPRINT = "android-45-1-7"
    }
}
