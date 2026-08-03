package com.akashic.mobile.data.realtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.database.sqlite.SQLiteException
import android.webkit.WebResourceResponse
import com.akashic.mobile.data.local.MobileWebUiBlobEntity
import com.akashic.mobile.data.local.MobileWebUiDao
import com.akashic.mobile.data.local.MobileWebUiGenerationEntity
import com.akashic.mobile.data.local.MobileWebUiRejectEntity
import com.akashic.mobile.data.local.MobileWebUiStateEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

data class MobileWebUiServing(val serverId: String, val generationId: String)
data class MobileWebUiAttemptLease(val serverId: String, val generationId: String, val nonce: String)
data class MobileWebUiRollbackResult(
    val generationId: String?,
    val fallbackUnavailable: Boolean,
)
data class MobileWebUiGcReport(
    val removedGenerations: Int,
    val removedBlobs: Int,
    val removedBytes: Long,
    val blockedGenerations: Int = 0,
    val unownedFiles: Int = 0,
)

data class MobileWebUiSpaceAdmission(
    val allowed: Boolean,
    val requiredBytes: Long,
    val usableSpaceBytes: Long,
    val serverBytes: Long,
    val serverBudgetBytes: Long,
    val globalBytes: Long,
    val globalBudgetBytes: Long,
    val blockedReason: String? = null,
    val targetKey: String? = null,
)

class MobileWebUiResetCleanupPendingException(message: String) : IOException(message)

@kotlinx.serialization.Serializable
private data class MobileWebUiResetMarker(
    val serverId: String,
    val targetKey: String?,
    val fingerprint: String?,
    val createdAt: Long,
    val physicalDeleted: Boolean,
)

/** 让 reset 根目录的 journal 变更在掉电后仍可恢复。 */
private fun fsyncMobileWebUiDirectory(directory: File) {
    // 1. 使用 API 26+ POSIX bridge 打开目录，不硬编码架构相关的 O_DIRECTORY。
    val descriptor = try {
        Os.open(
            directory.absolutePath,
            OsConstants.O_RDONLY,
            0,
        )
    } catch (error: ErrnoException) {
        throw IOException("无法打开 WebUI reset 根目录进行 fsync: $directory", error)
    }

    // 2. 通过 fd 元数据确认打开的对象仍是目录，再执行 fsync。
    var failure: IOException? = null
    try {
        val mode = try {
            Os.fstat(descriptor).st_mode
        } catch (error: ErrnoException) {
            throw IOException("无法检查 WebUI reset 根目录 fd: $directory", error)
        }
        if (!OsConstants.S_ISDIR(mode)) {
            throw IOException("WebUI reset 根目录不是目录: $directory")
        }
        try {
            Os.fsync(descriptor)
        } catch (error: ErrnoException) {
            throw IOException("无法 fsync WebUI reset 根目录: $directory", error)
        }
    } catch (error: IOException) {
        failure = error
    }

    // 3. 独立关闭 fd，并保留首个失败。
    try {
        Os.close(descriptor)
    } catch (error: ErrnoException) {
        val closeFailure = IOException("无法关闭 WebUI reset 根目录 fd: $directory", error)
        if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
    }
    failure?.let { throw it }
}

/** 持有每个服务端的不可变 WebUI 文件及少量 Room 指针元数据。 */
class MobileWebUiStore(
    private val root: File,
    private val dao: MobileWebUiDao,
    private val nativeBuild: Int,
    private val deletePhysicalFile: (File) -> Boolean = { it.delete() },
    private val deletePartialFile: (File) -> Boolean = { it.delete() },
    private val deleteDirectory: (File) -> Boolean = { it.deleteRecursively() },
    private val deleteResetMarker: (File) -> Boolean = { it.delete() },
    private val renameFile: (File, File) -> Boolean = { source, target -> source.renameTo(target) },
    private val syncRootDirectory: () -> Unit = { fsyncMobileWebUiDirectory(root) },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var activeServing: Serving? = null

    @Volatile
    private var servingManifest: MobileWebUiManifest? = null

    private val servingState = MutableStateFlow<MobileWebUiServing?>(null)
    val serving: StateFlow<MobileWebUiServing?> = servingState.asStateFlow()
    private val attemptState = MutableStateFlow<MobileWebUiAttemptLease?>(null)
    val attempt: StateFlow<MobileWebUiAttemptLease?> = attemptState.asStateFlow()

    init {
        require(nativeBuild > 0) { "WebUI native build 必须为正数" }
        if (!root.exists() && !root.mkdirs()) throw IOException("无法创建 WebUI 缓存根目录: $root")
        require(root.isDirectory) { "WebUI 缓存根目录不是目录: $root" }
    }

    /** 执行一个服务端范围内的操作，禁止并行提交指针或文件。 */
    suspend fun <T> withServerLock(serverId: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(serverId) { Mutex() }
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    suspend fun state(serverId: String): MobileWebUiStateEntity? = dao.getState(serverId)

    fun hasPendingReset(serverId: String): Boolean =
        resetMarkerFile(serverId).exists() ||
            resetMarkerTemporaryFile(serverId).exists() ||
            resetTrashDirectory(serverId).exists()

    suspend fun reconcilePendingResetIfNeeded(serverId: String) = withServerLock(serverId) {
        reconcilePendingReset(serverId)
    }

    suspend fun isPermanentlyRejected(
        serverId: String,
        target: MobileWebUiTarget,
        fingerprint: String,
    ): Boolean = withServerLock(serverId) {
        dao.getReject(serverId, target.targetKey, fingerprint)?.retryAfter == null
    }

    suspend fun hasVerifiedGeneration(serverId: String, generationId: String): Boolean =
        withServerLock(serverId) {
            val generation = dao.getGeneration(serverId, generationId) ?: return@withServerLock false
            try {
                readVerifiedGeneration(serverId, generation)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    suspend fun setDesired(serverId: String, view: MobileWebUiReleaseView, target: MobileWebUiTarget?) {
        validateMobileWebUiReleaseView(view, serverId)
        target?.let { validateMobileWebUiTarget(it, serverId) }
        withServerLock(serverId) {
            val previous = dao.getState(serverId)
            dao.upsertState(
                (previous ?: baselineState(serverId)).copy(
                    desiredChannel = when {
                        target == null -> "baseline"
                        target == view.preview -> "preview"
                        else -> "stable"
                    },
                    desiredTargetKey = target?.targetKey,
                    desiredGenerationId = target?.generationId,
                    desiredManifestDigest = target?.manifestDigest,
                    releaseEpoch = view.releaseEpoch,
                    releaseSequence = view.sequence,
                    selectionDigest = view.selectionDigest,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** 发布选择仅为 baseline 时，清除持久化的远程 serving 指针。 */
    suspend fun clearServingToBaseline(serverId: String) = withServerLock(serverId) {
        val state = dao.getState(serverId) ?: baselineState(serverId)
        dao.upsertState(
            state.copy(
                servingGenerationId = null,
                fallbackGenerationId = null,
                attemptingGenerationId = null,
                attemptingNonce = null,
                attemptingStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        attemptState.value = null
        setCommittedServing(null, null)
    }

    /** 仅当同一服务端拥有的状态仍选择 baseline 时清除 serving。 */
    suspend fun applyDesiredBaselineForSession(serverId: String): Boolean = withServerLock(serverId) {
        val state = dao.getState(serverId) ?: return@withServerLock false
        if (state.desiredChannel != "baseline") return@withServerLock false
        dao.upsertState(
            state.copy(
                servingGenerationId = null,
                fallbackGenerationId = null,
                attemptingGenerationId = null,
                attemptingNonce = null,
                attemptingStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        attemptState.value = null
        setCommittedServing(null, null)
        true
    }

    suspend fun markRejected(
        serverId: String,
        target: MobileWebUiTarget,
        fingerprint: String,
        reason: String,
        retryAfter: Long? = null,
    ) = withServerLock(serverId) {
        validateMobileWebUiTarget(target, serverId)
        require(fingerprint.isNotBlank() && fingerprint.length <= 256) { "WebUI compatibility fingerprint 无效" }
        require(reason.isNotBlank() && reason.length <= 512) { "WebUI reject reason 无效" }
        dao.upsertReject(
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = target.targetKey,
                compatibilityFingerprint = fingerprint,
                reason = reason,
                firstRejectedAt = System.currentTimeMillis(),
                retryAfter = retryAfter,
            ),
        )
    }

    suspend fun isRejected(serverId: String, target: MobileWebUiTarget, fingerprint: String): Boolean =
        withServerLock(serverId) {
            val rejected = dao.getReject(serverId, target.targetKey, fingerprint) ?: return@withServerLock false
            rejected.retryAfter == null || rejected.retryAfter > System.currentTimeMillis()
        }

    suspend fun isGenerationRejected(
        serverId: String,
        generationId: String,
        fingerprint: String,
    ): Boolean = withServerLock(serverId) {
        val generation = dao.getGeneration(serverId, generationId) ?: return@withServerLock false
        val rejected = dao.getReject(serverId, generation.targetKey, fingerprint) ?: return@withServerLock false
        rejected.retryAfter == null || rejected.retryAfter > System.currentTimeMillis()
    }

    suspend fun clearReject(serverId: String, target: MobileWebUiTarget, fingerprint: String) =
        withServerLock(serverId) {
            if (dao.getReject(serverId, target.targetKey, fingerprint) != null) {
                dao.deleteReject(serverId, target.targetKey, fingerprint)
            }
        }

    suspend fun rejectGeneration(
        serverId: String,
        generationId: String,
        fingerprint: String,
        reason: String,
    ): Boolean = withServerLock(serverId) {
        val generation = dao.getGeneration(serverId, generationId) ?: return@withServerLock false
        val target = targetFromGeneration(generation)
        dao.upsertReject(
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = target.targetKey,
                compatibilityFingerprint = fingerprint,
                reason = reason,
                firstRejectedAt = System.currentTimeMillis(),
                retryAfter = null,
            ),
        )
        true
    }

    /** 完整扫描引用后，只裁切未固定的派生 generation/blob。 */
    suspend fun collectGarbage(
        serverId: String,
        maxGenerations: Int = 4,
        maxBytes: Long = 256L * 1024 * 1024,
    ): MobileWebUiGcReport = withServerLock(serverId) {
        require(maxGenerations >= 1 && maxBytes > 0) { "WebUI GC budget 无效" }
        val orphanBlobs = reconcileOrphanBlobs(serverId)
        val orphanGenerations = collectOrphanGenerations(serverId)
        val state = dao.getState(serverId)
        val pinned = setOfNotNull(
            state?.servingGenerationId,
            state?.fallbackGenerationId,
            state?.desiredGenerationId,
            state?.attemptingGenerationId,
        )
        val partialRemovedBytes = reconcilePartials(serverId, state ?: baselineState(serverId))
        val generations = dao.listGenerations(serverId).sortedByDescending { it.lastUsedAt }.toMutableList()
        var totalBytes = generations.sumOf { generationDirectoryBytes(serverId, it.generationId) } +
            dao.listBlobs(serverId).sumOf { blobFile(serverId, it.sha256).length() } +
            orphanGenerations.remainingBytes
        var removedGenerations = orphanGenerations.removedGenerations
        var removedBytes = partialRemovedBytes + orphanBlobs.removedBytes + orphanGenerations.removedBytes
        var blockedGenerations = orphanGenerations.blockedGenerations
        var unownedFiles = orphanBlobs.unownedFiles + orphanGenerations.unownedFiles
        for (generation in generations.toList().asReversed()) {
            if (generation.generationId in pinned) continue
            if (generations.size <= maxGenerations && totalBytes <= maxBytes) break
            val directory = generationDirectory(serverId, generation.generationId)
            val bytes = generationDirectoryBytes(serverId, generation.generationId)
            if (directory.exists() && !deleteDirectory(directory)) {
                blockedGenerations += 1
                unownedFiles += 1
                continue
            }
            dao.deleteGeneration(serverId, generation.generationId)
            generations.remove(generation)
            removedGenerations += 1
            totalBytes -= bytes
            removedBytes += bytes
        }
        val referenced = HashSet<String>()
        var pinnedManifestUnverified = false
        generations.forEach { generation ->
            val file = resolvePrivateFile(serverId, generation.manifestPath)
            if (!file.isFile) {
                if (generation.generationId in pinned) {
                    blockedGenerations += 1
                    pinnedManifestUnverified = true
                }
                return@forEach
            }
            try {
                val text = file.readText()
                requireNoDuplicateJsonKeys(text)
                val manifest = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(text)
                validateMobileWebUiManifest(manifest)
                referenced += manifest.files.map { it.sha256 }
            } catch (_: IllegalArgumentException) {
                if (generation.generationId in pinned) {
                    blockedGenerations += 1
                    pinnedManifestUnverified = true
                }
            }
        }
        var removedBlobs = 0
        if (!pinnedManifestUnverified) {
            dao.listBlobs(serverId).forEach { blob ->
                if (blob.sha256 in referenced) return@forEach
                val file = resolvePrivateFile(serverId, blob.relativePath)
                val bytes = file.length()
                if (file.exists() && !file.delete()) {
                    unownedFiles += 1
                    return@forEach
                }
                dao.deleteBlob(serverId, blob.sha256)
                removedBlobs += 1
                removedBytes += bytes
                totalBytes -= bytes
            }
        }
        if (generations.size > maxGenerations || totalBytes > maxBytes) {
            blockedGenerations += (generations.size - maxGenerations).coerceAtLeast(1)
        }
        MobileWebUiGcReport(
            removedGenerations,
            removedBlobs,
            removedBytes,
            blockedGenerations,
            unownedFiles,
        )
    }

    /** 在保留所有持久化指针的同时，执行进程级派生 WebUI 预算。 */
    suspend fun collectGlobalGarbage(maxBytes: Long = 512L * 1024 * 1024): MobileWebUiGcReport {
        require(maxBytes > 0) { "WebUI global GC budget 无效" }
        var removedGenerations = 0
        var removedBlobs = 0
        var removedBytes = 0L
        var blockedGenerations = 0
        var unownedFiles = 0
        val servers = (
            dao.listStates().map { it.serverId } +
                dao.listAllGenerations().map { it.serverId } +
                dao.listAllBlobs().map { it.serverId }
            )
            .toSet()
        val profileRoots = dao.listKnownServerIds().map { it.sha256() }.toSet()
        val knownServerRoots = servers.map { it.sha256() }.toSet()
        val rootEntries = root.listFiles()?.toList().orEmpty()
        val resetJournalRoots = rootEntries.mapNotNull { entry ->
            val hash = when {
                entry.name.endsWith(".reset.json.tmp") -> entry.name.removeSuffix(".reset.json.tmp")
                entry.name.endsWith(".reset.json") -> entry.name.removeSuffix(".reset.json")
                entry.name.endsWith(".reset-trash") -> entry.name.removeSuffix(".reset-trash")
                else -> null
            }
            hash?.takeIf { MOBILE_WEB_UI_SHA256.matches(it) }
        }.toSet()
        rootEntries
            .filter {
                it.isDirectory && MOBILE_WEB_UI_SHA256.matches(it.name) &&
                    it.name !in profileRoots && it.name !in knownServerRoots &&
                    it.name !in resetJournalRoots
            }
            .forEach { orphanRoot ->
                val bytes = orphanRoot.walkTopDown().filter(File::isFile).sumOf(File::length)
                if (deleteDirectory(orphanRoot)) {
                    removedBytes += bytes
                } else {
                    blockedGenerations += 1
                    unownedFiles += orphanRoot.walkTopDown().count(File::isFile).coerceAtLeast(1)
                }
            }
        val remainingRootEntries = root.listFiles()?.toList().orEmpty()
        val unknownRootFiles = remainingRootEntries
            .filter { it.isDirectory && MOBILE_WEB_UI_SHA256.matches(it.name) && it.name !in knownServerRoots }
            .sumOf { serverRoot -> serverRoot.walkTopDown().count(File::isFile) }
        val rootArtifacts = remainingRootEntries
            .filter { entry ->
                entry.name !in knownServerRoots &&
                    !(entry.isDirectory && MOBILE_WEB_UI_SHA256.matches(entry.name))
            }
            .sumOf { entry -> if (entry.isDirectory) entry.walkTopDown().count(File::isFile) else 1 }
        unownedFiles += unknownRootFiles + rootArtifacts
        servers.forEach { serverId ->
            val orphan = withServerLock(serverId) { collectOrphanGenerations(serverId) }
            removedGenerations += orphan.removedGenerations
            removedBytes += orphan.removedBytes
            blockedGenerations += orphan.blockedGenerations
            unownedFiles += orphan.unownedFiles
        }
        while (derivedBytes() > maxBytes) {
            val candidates = dao.listAllGenerations().sortedBy { it.lastUsedAt }
            var removed = false
            for (candidate in candidates) {
                val result = withServerLock(candidate.serverId) {
                    val state = dao.getState(candidate.serverId)
                    val pinned = setOfNotNull(
                        state?.servingGenerationId,
                        state?.fallbackGenerationId,
                        state?.desiredGenerationId,
                        state?.attemptingGenerationId,
                    )
                    val current = dao.getGeneration(candidate.serverId, candidate.generationId)
                    if (current == null || current.generationId in pinned) return@withServerLock null
                    val directory = generationDirectory(candidate.serverId, current.generationId)
                    val bytes = generationDirectoryBytes(candidate.serverId, current.generationId)
                    if (directory.exists() && !deleteDirectory(directory)) {
                        return@withServerLock GlobalGcResult(0, 0, 0, 1, 1)
                    }
                    dao.deleteGeneration(candidate.serverId, current.generationId)
                    GlobalGcResult(1, 0, bytes, 0, 0)
                }
                if (result == null) continue
                removedGenerations += result.generations
                removedBytes += result.bytes
                blockedGenerations += result.blocked
                unownedFiles += result.unownedFiles
                removed = result.generations > 0
                if (removed) break
            }
            if (!removed) {
                val states = dao.listStates().associateBy { it.serverId }
                blockedGenerations += candidates.count { candidate ->
                    val state = states[candidate.serverId]
                    candidate.generationId in setOfNotNull(
                        state?.servingGenerationId,
                        state?.fallbackGenerationId,
                        state?.desiredGenerationId,
                        state?.attemptingGenerationId,
                    )
                }
                break
            }
        }
        servers.forEach { serverId ->
            withServerLock(serverId) {
                val refs = referencedBlobDigests(serverId)
                if (refs.pinnedManifestUnverified) return@withServerLock
                dao.listBlobs(serverId).forEach { blob ->
                    if (blob.sha256 in refs.digests || derivedBytes() <= maxBytes) return@forEach
                    val file = blobFile(serverId, blob.sha256)
                    val bytes = file.length()
                    if (file.exists() && !file.delete()) {
                        unownedFiles += 1
                    } else {
                        dao.deleteBlob(serverId, blob.sha256)
                        removedBlobs += 1
                        removedBytes += bytes
                    }
                }
                unownedFiles += reconcileOrphanBlobs(serverId).unownedFiles
            }
        }
        if (derivedBytes() > maxBytes) blockedGenerations += 1
        return MobileWebUiGcReport(removedGenerations, removedBlobs, removedBytes, blockedGenerations, unownedFiles)
    }

    /** 先回收派生缓存；只有磁盘和预算覆盖缺失 blob 时才允许 manifest。 */
    suspend fun prepareDownloadSpace(
        serverId: String,
        manifest: MobileWebUiManifest,
        serverBudgetBytes: Long = 256L * 1024 * 1024,
        globalBudgetBytes: Long = 512L * 1024 * 1024,
    ): MobileWebUiSpaceAdmission {
        validateMobileWebUiManifest(manifest)
        collectGarbage(serverId, maxBytes = serverBudgetBytes)
        collectGlobalGarbage(maxBytes = globalBudgetBytes)
        return withServerLock(serverId) {
            val seenDigests = HashSet<String>(manifest.files.size)
            val requiredBytes = manifest.files.sumOf { file ->
                if (!seenDigests.add(file.sha256)) 0L
                else {
                    val blob = blobFile(serverId, file.sha256)
                    if (blob.isFile && blob.length() == file.sizeBytes && blob.sha256() == file.sha256) 0L
                    else file.sizeBytes
                }
            }
            val serverBytes = derivedServerBytesLocked(serverId)
            val globalBytes = derivedBytes()
            val usable = serverDirectory(serverId).usableSpace
            val blockedReason = when {
                serverBytes + requiredBytes > serverBudgetBytes -> "server_budget"
                globalBytes + requiredBytes > globalBudgetBytes -> "global_budget"
                usable < requiredBytes -> "disk_space"
                else -> null
            }
            MobileWebUiSpaceAdmission(
                allowed = blockedReason == null,
                requiredBytes = requiredBytes,
                usableSpaceBytes = usable,
                serverBytes = serverBytes,
                serverBudgetBytes = serverBudgetBytes,
                globalBytes = globalBytes,
                globalBudgetBytes = globalBudgetBytes,
                blockedReason = blockedReason,
            )
        }
    }

    /** 只有 canonical 字节和所有 blob 一致时才返回已验证 manifest。 */
    suspend fun readVerifiedManifest(
        serverId: String,
        target: MobileWebUiTarget,
    ): MobileWebUiManifest? = withServerLock(serverId) {
        readVerifiedManifestLocked(serverId, target)
    }

    /** 验证摘要和目标身份后，原子提交 canonical manifest。 */
    suspend fun commitManifest(
        serverId: String,
        target: MobileWebUiTarget,
        bytes: ByteArray,
    ): MobileWebUiManifest = withServerLock(serverId) {
        // 1. 在接触缓存前校验 wire 字节。
        validateMobileWebUiTarget(target, serverId)
        require(bytes.size.toLong() == target.manifestSizeBytes) { "WebUI manifest 长度不一致" }
        require(bytes.sha256() == target.manifestDigest) { "WebUI manifest 摘要不一致" }
        val manifestText = bytes.toString(StandardCharsets.UTF_8)
        requireNoDuplicateJsonKeys(manifestText)
        val manifest = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(manifestText)
        validateMobileWebUiManifest(manifest, target)
        require(bytes.contentEquals(mobileWebUiManifestBytes(manifest))) {
            "WebUI manifest 不是 canonical JSON"
        }

        // 2. 只发布不可变 generation 目录；blob 仍是 CAS 对象。
        val generation = generationDirectory(serverId, target.generationId)
        if (generation.exists()) {
            val existing = generation.resolve("manifest.json")
            require(existing.isFile && existing.readBytes().contentEquals(bytes)) {
                "WebUI generation 已存在但 manifest 内容不同"
            }
        } else {
            val staging = stagingGenerationDirectory(serverId, target.generationId)
            if (staging.exists() && !staging.deleteRecursively()) throw IOException("无法清理 WebUI manifest staging")
            ensureDirectory(requireNotNull(staging.parentFile), "无法创建 WebUI manifest staging 父目录")
            ensureDirectory(staging, "无法创建 WebUI manifest staging 目录")
            val temporary = staging.resolve(".manifest.${System.nanoTime()}.tmp")
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!renameFile(temporary, staging.resolve("manifest.json"))) {
                throw IOException("WebUI manifest 临时文件提交失败")
            }
            ensureDirectory(requireNotNull(generation.parentFile), "无法创建 WebUI generation 父目录")
            if (!renameFile(staging, generation)) throw IOException("WebUI generation 原子提交失败")
        }
        val now = System.currentTimeMillis()
        dao.upsertGeneration(manifestEntity(serverId, target, manifest, generation, now))
        manifest
    }

    /** 返回摘要绑定的 partial blob 持久化 offset，并删除不匹配的 partial。 */
    suspend fun blobOffset(serverId: String, sha256: String, bytes: Long): Long =
        withServerLock(serverId) {
            validateBlobRequest(sha256, bytes)
            blobOffsetLocked(serverId, sha256, bytes)
        }

    /** 仅在服务端 Range validator 失败后丢弃可续传的 partial blob。 */
    suspend fun resetBlobPartial(serverId: String, sha256: String, bytes: Long) =
        withServerLock(serverId) {
            validateBlobRequest(sha256, bytes)
            val part = blobPart(serverId, sha256)
            val metadata = blobPartMetadata(serverId, sha256)
            if (part.exists() && !deletePartialFile(part)) throw IOException("无法删除 WebUI partial blob: $sha256")
            if (metadata.exists() && !deletePartialFile(metadata)) throw IOException("无法删除 WebUI partial 元数据: $sha256")
        }

    /** 追加一个摘要绑定的 range，完整哈希校验后才发布 blob。 */
    suspend fun appendBlob(
        serverId: String,
        sha256: String,
        expectedBytes: Long,
        offset: Long,
        chunk: ByteArray,
    ): Long = withServerLock(serverId) {
        validateBlobRequest(sha256, expectedBytes)
        val current = blobOffsetLocked(serverId, sha256, expectedBytes)
        require(offset == current) { "WebUI blob offset 不连续" }
        require(chunk.isNotEmpty() && chunk.size.toLong() <= expectedBytes - offset) {
            "WebUI blob chunk 大小无效"
        }
        val part = blobPart(serverId, sha256)
        RandomAccessFile(part, "rw").use { file ->
            file.seek(offset)
            file.write(chunk)
            file.fd.sync()
        }
        val next = offset + chunk.size
        if (next == expectedBytes) publishBlob(serverId, sha256, expectedBytes)
        next
    }

    /** 空 range 没有可追加 chunk；证明摘要后直接发布空 blob。 */
    suspend fun ensureEmptyBlob(serverId: String, sha256: String): Boolean = withServerLock(serverId) {
        validateBlobRequest(sha256, 0)
        val blob = blobFile(serverId, sha256)
        if (blob.isFile && blob.length() == 0L && blob.sha256() == sha256) return@withServerLock true
        val part = blobPart(serverId, sha256)
        ensureDirectory(requireNotNull(part.parentFile), "无法创建 WebUI empty blob 目录")
        FileOutputStream(part, false).use { it.fd.sync() }
        publishBlob(serverId, sha256, 0)
        true
    }

    suspend fun hasBlob(serverId: String, sha256: String, bytes: Long): Boolean =
        withServerLock(serverId) {
            validateBlobRequest(sha256, bytes)
            val blob = blobFile(serverId, sha256)
            if (!blob.isFile || blob.length() != bytes) return@withServerLock false
            if (blob.sha256() != sha256) {
                if (!blob.delete()) throw IOException("无法删除损坏 WebUI blob: $sha256")
                dao.deleteBlob(serverId, sha256)
                return@withServerLock false
            }
            dao.upsertBlob(
                MobileWebUiBlobEntity(
                    serverId = serverId,
                    sha256 = sha256,
                    bytes = bytes,
                    relativePath = relativePath(serverId, blob),
                    lastUsedAt = System.currentTimeMillis(),
                ),
            )
            true
        }

    /** 在持久化 attempt marker 仍归属当前 owner 时，校验并打开一个候选租约。 */
    suspend fun openAttempt(
        serverId: String,
        generationId: String,
        nonce: String,
        rejectionFingerprint: String,
    ): Boolean = withServerLock(serverId) {
        require(generationId.isNotBlank() && nonce.isNotBlank()) { "WebUI attempt marker 无效" }
        val state = dao.getState(serverId) ?: return@withServerLock false
        if (state.desiredGenerationId != generationId) return@withServerLock false
        val generation = dao.getGeneration(serverId, generationId) ?: return@withServerLock false
        val rejected = dao.getReject(serverId, generation.targetKey, rejectionFingerprint)
        if (rejected != null && (rejected.retryAfter == null || rejected.retryAfter > System.currentTimeMillis())) {
            return@withServerLock false
        }
        val manifest = try {
            readVerifiedGeneration(serverId, generation)
        } catch (_: IllegalArgumentException) {
            return@withServerLock false
        }
        dao.upsertState(
            state.copy(
                fallbackGenerationId = state.servingGenerationId ?: state.fallbackGenerationId,
                attemptingGenerationId = generationId,
                attemptingNonce = nonce,
                attemptingStartedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        attemptState.value = MobileWebUiAttemptLease(serverId, generationId, nonce)
        setPresentation(Serving(serverId, generationId), manifest)
        true
    }

    suspend fun isAttemptCurrent(serverId: String, generationId: String, nonce: String): Boolean =
        withServerLock(serverId) {
            val state = dao.getState(serverId) ?: return@withServerLock false
            state.attemptingGenerationId == generationId && state.attemptingNonce == nonce
        }

    /** 只有精确的进程租约仍有效时才取消候选，并保留其缓存。 */
    suspend fun rollbackAttempt(serverId: String, generationId: String, nonce: String): Boolean =
        withServerLock(serverId) {
            val state = dao.getState(serverId) ?: return@withServerLock false
            if (state.attemptingGenerationId != generationId || state.attemptingNonce != nonce) {
                if (attemptState.value?.serverId == serverId &&
                    attemptState.value?.generationId == generationId &&
                    attemptState.value?.nonce == nonce
                ) {
                    attemptState.value = null
                }
                return@withServerLock false
            }
            val fallbackId = state.fallbackGenerationId
            val fallback = fallbackId?.let { id ->
                dao.getGeneration(serverId, id)?.let { generation ->
                    try {
                        readVerifiedGeneration(serverId, generation)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
            val nextState = state.copy(
                servingGenerationId = fallback?.let { fallbackId },
                fallbackGenerationId = fallback?.let { fallbackId },
                attemptingGenerationId = null,
                attemptingNonce = null,
                attemptingStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertState(nextState)
            attemptState.value = null
            setCommittedServing(fallback?.let { Serving(serverId, requireNotNull(fallbackId)) }, fallback)
            true
        }

    suspend fun commitHealthy(serverId: String, generationId: String, nonce: String) =
        withServerLock(serverId) {
            val state = requireNotNull(dao.getState(serverId)) { "WebUI state 未初始化" }
            require(state.attemptingGenerationId == generationId && state.attemptingNonce == nonce) {
                "WebUI healthy nonce 不匹配"
            }
            val generation = requireNotNull(dao.getGeneration(serverId, generationId)) {
                "WebUI generation 未准备完成"
            }
            require(state.desiredGenerationId == generationId && state.desiredTargetKey == generation.targetKey) {
                "WebUI healthy lease 已不再是当前 desired target"
            }
            val manifest = readVerifiedGeneration(serverId, generation)
            val now = System.currentTimeMillis()
            val nextState = state.copy(
                servingGenerationId = generationId,
                fallbackGenerationId = state.servingGenerationId ?: state.fallbackGenerationId,
                attemptingGenerationId = null,
                attemptingNonce = null,
                attemptingStartedAt = null,
                lastHealthyAt = now,
                updatedAt = now,
            )
            dao.commitHealthyDurableState(nextState, generationId, now)
            attemptState.value = null
            setCommittedServing(Serving(serverId, generationId), manifest)
        }

    /** 在一个持久化事务中回滚候选并拒绝其精确目标。 */
    suspend fun rollbackAttemptAndReject(
        serverId: String,
        generationId: String,
        nonce: String,
        fingerprint: String,
        reason: String,
    ): String? = rollbackAttemptAndRejectResult(
        serverId,
        generationId,
        nonce,
        fingerprint,
        reason,
    ).generationId

    suspend fun rollbackAttemptAndRejectResult(
        serverId: String,
        generationId: String,
        nonce: String,
        fingerprint: String,
        reason: String,
    ): MobileWebUiRollbackResult = withServerLock(serverId) {
        rollbackAndRejectLocked(serverId, generationId, nonce, fingerprint, reason)
    }

    /** 拒绝已提交的 serving generation，并原子切换到已验证 fallback。 */
    suspend fun rejectServingAndRollback(
        serverId: String,
        generationId: String,
        fingerprint: String,
        reason: String,
    ): String? = rollbackServingAndRejectResult(
        serverId,
        generationId,
        fingerprint,
        reason,
    ).generationId

    suspend fun rollbackServingAndRejectResult(
        serverId: String,
        generationId: String,
        fingerprint: String,
        reason: String,
    ): MobileWebUiRollbackResult = withServerLock(serverId) {
        rollbackAndRejectLocked(serverId, generationId, null, fingerprint, reason)
    }

    /** 拒绝 serving 候选，并强制持久化展示回到 baseline。 */
    suspend fun rejectServingAndBaseline(
        serverId: String,
        generationId: String,
        fingerprint: String,
        reason: String,
    ) = withServerLock(serverId) {
        val state = dao.getState(serverId) ?: return@withServerLock
        val generation = dao.getGeneration(serverId, generationId)
        val nextState = state.copy(
            servingGenerationId = null,
            fallbackGenerationId = null,
            attemptingGenerationId = null,
            attemptingNonce = null,
            attemptingStartedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        val rejection = generation?.let {
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = it.targetKey,
                compatibilityFingerprint = fingerprint,
                reason = reason,
                firstRejectedAt = System.currentTimeMillis(),
                retryAfter = null,
            )
        }
        dao.rollbackAndRejectDurableState(nextState, rejection)
        attemptState.value = null
        setCommittedServing(null, null)
    }

    private suspend fun rollbackAndRejectLocked(
        serverId: String,
        generationId: String,
        nonce: String?,
        fingerprint: String,
        reason: String,
    ): MobileWebUiRollbackResult {
        val state = dao.getState(serverId) ?: return MobileWebUiRollbackResult(null, false)
        val generation = dao.getGeneration(serverId, generationId)
        val attemptMatches = state.attemptingGenerationId == generationId &&
            (nonce == null || state.attemptingNonce == nonce)
        val servingMatches = state.attemptingGenerationId == null && state.servingGenerationId == generationId
        val shouldRollback = attemptMatches || servingMatches
        var fallbackUnavailable = false
        val fallback = if (shouldRollback) state.fallbackGenerationId?.let { fallbackId ->
            dao.getGeneration(serverId, fallbackId)?.let { fallbackGeneration ->
                try {
                    readVerifiedGeneration(serverId, fallbackGeneration)
                } catch (_: IllegalArgumentException) {
                    fallbackUnavailable = true
                    null
                } catch (_: IOException) {
                    fallbackUnavailable = true
                    null
                } catch (_: SQLiteException) {
                    fallbackUnavailable = true
                    null
                } catch (_: SecurityException) {
                    fallbackUnavailable = true
                    null
                }
            }
        } else null
        val healthyFallback = fallback?.let { state.fallbackGenerationId }
        val nextState = if (shouldRollback) {
            state.copy(
                servingGenerationId = if (fallbackUnavailable) null else healthyFallback,
                fallbackGenerationId = if (fallbackUnavailable || attemptMatches) healthyFallback else null,
                attemptingGenerationId = if (attemptMatches) null else state.attemptingGenerationId,
                attemptingNonce = if (attemptMatches) null else state.attemptingNonce,
                attemptingStartedAt = if (attemptMatches) null else state.attemptingStartedAt,
                updatedAt = System.currentTimeMillis(),
            )
        } else {
            state
        }
        val rejection = generation?.let {
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = it.targetKey,
                compatibilityFingerprint = fingerprint,
                reason = if (fallbackUnavailable) "fallback_unavailable" else reason,
                firstRejectedAt = System.currentTimeMillis(),
                retryAfter = null,
            )
        }
        dao.rollbackAndRejectDurableState(nextState, rejection)
        if (shouldRollback) {
            attemptState.value = null
            setCommittedServing(
                if (fallbackUnavailable) null else healthyFallback?.let { Serving(serverId, it) },
                if (fallbackUnavailable) null else fallback,
            )
        }
        return MobileWebUiRollbackResult(
            generationId = if (shouldRollback) healthyFallback else state.servingGenerationId,
            fallbackUnavailable = fallbackUnavailable,
        )
    }

    /** 只恢复持久化 serving 指针；任意缓存 generation 都不能直接成为 serving。 */
    suspend fun restoreCommittedServing(serverId: String): Boolean = withServerLock(serverId) {
        val state = dao.getState(serverId)
        val generationId = state?.servingGenerationId
        if (generationId == null) {
            attemptState.value = null
            setCommittedServing(null, null)
            return@withServerLock true
        }
        val generation = dao.getGeneration(serverId, generationId)
        val manifest = generation?.let {
            try {
                readVerifiedGeneration(serverId, it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (manifest != null) {
            attemptState.value = null
            setCommittedServing(Serving(serverId, generationId), manifest)
            dao.touchGeneration(serverId, generationId, System.currentTimeMillis())
            return@withServerLock true
        }
        val fallbackId = state.fallbackGenerationId
        val fallback = fallbackId?.let { dao.getGeneration(serverId, it) }
        val fallbackManifest = fallback?.let {
            try {
                readVerifiedGeneration(serverId, it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        dao.upsertState(
            state.copy(
                servingGenerationId = fallbackManifest?.let { fallbackId },
                fallbackGenerationId = fallbackManifest?.let { fallbackId },
                updatedAt = System.currentTimeMillis(),
            ),
        )
        attemptState.value = null
        setCommittedServing(fallbackManifest?.let { Serving(serverId, requireNotNull(fallback).generationId) }, fallbackManifest)
        false
    }

    fun servingServerId(): String? = activeServing?.serverId

    fun servingGenerationId(): String? = activeServing?.generationId

    /** 只对账派生文件；不触碰业务 Room 行。 */
    suspend fun reconcile(serverId: String) = withServerLock(serverId) {
        reconcilePendingReset(serverId)
        val serverRoot = serverDirectory(serverId)
        reconcileOrphanBlobs(serverId)
        if (serverRoot.exists()) {
            serverRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .forEach { if (!it.delete()) throw IOException("无法删除 WebUI 临时文件: $it") }
        }
        reconcileOrphanStaging(serverId)
        val state = dao.getState(serverId) ?: run {
            reconcilePartials(serverId, baselineState(serverId))
            reconcileOrphanGenerations(serverId)
            return@withServerLock
        }
        reconcilePartials(serverId, state)
        reconcileOrphanGenerations(serverId)
        if (state.attemptingGenerationId != null) {
            val attempt = dao.getGeneration(serverId, state.attemptingGenerationId)
            val fallback = state.fallbackGenerationId?.let { dao.getGeneration(serverId, it) }
            val fallbackManifest = fallback?.let {
                try {
                    readVerifiedGeneration(serverId, it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            val healthyFallback = fallbackManifest?.let { fallback.generationId }
            val recoveredState = state.copy(
                servingGenerationId = healthyFallback,
                fallbackGenerationId = healthyFallback,
                attemptingGenerationId = null,
                attemptingNonce = null,
                attemptingStartedAt = null,
                updatedAt = System.currentTimeMillis(),
            )
            val rejection = attempt?.let {
                MobileWebUiRejectEntity(
                    serverId = serverId,
                    targetKey = it.targetKey,
                    compatibilityFingerprint = compatibilityFingerprint(),
                    reason = "attempt_recovered_after_restart",
                    firstRejectedAt = System.currentTimeMillis(),
                    retryAfter = null,
                )
            }
            dao.recoverAttemptDurableState(recoveredState, rejection)
            attemptState.value = null
            setCommittedServing(fallbackManifest?.let { Serving(serverId, requireNotNull(healthyFallback)) }, fallbackManifest)
            return@withServerLock
        }
        val servingGeneration = state.servingGenerationId
        if (servingGeneration != null && dao.getGeneration(serverId, servingGeneration) == null) {
            dao.upsertState(state.copy(servingGenerationId = null))
            attemptState.value = null
            setCommittedServing(null, null)
        }
    }

    private suspend fun reconcilePartials(serverId: String, state: MobileWebUiStateEntity): Long {
        val keep = state.desiredGenerationId?.let { generationId ->
            dao.getGeneration(serverId, generationId)?.let { generation ->
                try {
                    val manifest = readVerifiedManifestMetadata(serverId, generation)
                    manifest.files.map { it.sha256 }.toSet()
                } catch (_: IllegalArgumentException) {
                    emptySet()
                }
            }
        }.orEmpty()
        val partials = serverDirectory(serverId).resolve("partials")
        if (!partials.isDirectory) return 0L
        var removedBytes = 0L
        partials.listFiles()?.toList().orEmpty()
            .filter { it.isFile && it.name.endsWith(".meta") }
            .forEach { metadata ->
                val digest = metadata.name.removeSuffix(".meta")
                val part = metadata.resolveSibling("$digest.part")
                val metadataValue = try {
                    MOBILE_WEB_UI_JSON.decodeFromString<BlobPartMetadata>(metadata.readText())
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (metadataValue == null || metadataValue.sha256 != digest || digest !in keep ||
                    metadataValue.bytes !in 0..MOBILE_WEB_UI_MAX_FILE_BYTES || part.length() > metadataValue.bytes
                ) {
                    removedBytes += part.length() + metadata.length()
                    deletePart(part, metadata)
                }
            }
        partials.listFiles()?.toList().orEmpty()
            .filter { it.isFile && it.name.endsWith(".part") &&
                !it.resolveSibling(it.name.removeSuffix(".part") + ".meta").isFile
            }
            .forEach {
                removedBytes += it.length()
                if (!it.delete()) throw IOException("无法删除无 owner 的 WebUI partial: $it")
            }
        return removedBytes
    }

    private suspend fun reconcileOrphanBlobs(serverId: String): BlobReconcileResult {
        val directory = serverDirectory(serverId).resolve("blobs")
        var removedBytes = 0L
        var unownedFiles = 0
        dao.listBlobs(serverId).forEach { row ->
            val file = blobFile(serverId, row.sha256)
            if (!file.isFile || file.length() != row.bytes || file.sha256() != row.sha256) {
                if (file.isFile) {
                    val bytes = file.length()
                    if (!file.delete()) {
                        unownedFiles += 1
                        return@forEach
                    }
                    removedBytes += bytes
                }
                dao.deleteBlob(serverId, row.sha256)
            }
        }
        directory.listFiles()?.toList().orEmpty().filter { it.isFile }.forEach { file ->
            if (!MOBILE_WEB_UI_SHA256.matches(file.name)) {
                unownedFiles += 1
                return@forEach
            }
            if (dao.getBlob(serverId, file.name) == null) {
                val bytes = file.length()
                if (bytes <= MOBILE_WEB_UI_MAX_FILE_BYTES && file.sha256() == file.name) {
                    if (file.delete()) removedBytes += bytes else unownedFiles += 1
                } else {
                    unownedFiles += 1
                }
            }
        }
        return BlobReconcileResult(removedBytes, unownedFiles)
    }

    private fun generationDirectoryBytes(serverId: String, generationId: String): Long =
        generationDirectory(serverId, generationId).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

    private data class BlobReconcileResult(val removedBytes: Long, val unownedFiles: Int)

    private data class GlobalGcResult(
        val generations: Int,
        val blobs: Int,
        val bytes: Long,
        val blocked: Int,
        val unownedFiles: Int,
    )

    private data class OrphanGenerationGcResult(
        val removedGenerations: Int,
        val removedBytes: Long,
        val blockedGenerations: Int,
        val unownedFiles: Int,
        val remainingBytes: Long,
    )

    private data class BlobReferences(
        val digests: Set<String>,
        val pinnedManifestUnverified: Boolean,
    )

    private suspend fun referencedBlobDigests(serverId: String): BlobReferences {
        val result = HashSet<String>()
        val state = dao.getState(serverId)
        val pinned = setOfNotNull(
            state?.servingGenerationId,
            state?.fallbackGenerationId,
            state?.desiredGenerationId,
            state?.attemptingGenerationId,
        )
        var pinnedManifestUnverified = false
        dao.listGenerations(serverId).forEach { generation ->
            val file = resolvePrivateFile(serverId, generation.manifestPath)
            if (!file.isFile) {
                if (generation.generationId in pinned) pinnedManifestUnverified = true
                return@forEach
            }
            try {
                val text = file.readText()
                requireNoDuplicateJsonKeys(text)
                val manifest = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(text)
                validateMobileWebUiManifest(manifest)
                result += manifest.files.map { it.sha256 }
            } catch (_: IllegalArgumentException) {
                if (generation.generationId in pinned) pinnedManifestUnverified = true
            }
        }
        return BlobReferences(result, pinnedManifestUnverified)
    }

    private suspend fun derivedBytes(): Long {
        return root.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
    }

    private suspend fun derivedServerBytesLocked(serverId: String): Long =
        serverDirectory(serverId).walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)

    private suspend fun reconcileOrphanGenerations(serverId: String) {
        val generations = dao.listGenerations(serverId).map { it.generationId }.toSet()
        val directory = serverDirectory(serverId).resolve("generations")
        directory.listFiles()?.toList().orEmpty()
            .filter { it.isDirectory && it.name !in generations }
            .forEach {
                if (!deleteDirectory(it)) throw IOException("无法删除无 Room owner 的 WebUI generation: $it")
            }
    }

    private suspend fun collectOrphanGenerations(serverId: String): OrphanGenerationGcResult {
        val generations = dao.listGenerations(serverId).map { it.generationId }.toSet()
        val directory = serverDirectory(serverId).resolve("generations")
        var removedGenerations = 0
        var removedBytes = 0L
        var blockedGenerations = 0
        var unownedFiles = 0
        var remainingBytes = 0L
        directory.listFiles()?.toList().orEmpty()
            .filter { it.isDirectory && it.name !in generations }
            .forEach { orphan ->
                val bytes = generationDirectoryBytes(serverId, orphan.name)
                if (deleteDirectory(orphan)) {
                    removedGenerations += 1
                    removedBytes += bytes
                } else {
                    blockedGenerations += 1
                    unownedFiles += 1
                    remainingBytes += bytes
                }
            }
        return OrphanGenerationGcResult(
            removedGenerations,
            removedBytes,
            blockedGenerations,
            unownedFiles,
            remainingBytes,
        )
    }

    private suspend fun reconcilePendingReset(serverId: String) {
        val markerFile = resetMarkerFile(serverId)
        val temporary = resetMarkerTemporaryFile(serverId)
        val trash = resetTrashDirectory(serverId)
        if (!markerFile.exists() && !trash.exists()) {
            deleteResetTemporaryFile(temporary)
            return
        }
        if (activeServing?.serverId == serverId || attemptState.value?.serverId == serverId) {
            attemptState.value = null
            setCommittedServing(null, null)
        }
        require(markerFile.isFile) { "WebUI reset marker 缺失: $markerFile" }
        val marker = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiResetMarker>(markerFile.readText())
        require(marker.serverId == serverId) { "WebUI reset marker server 不匹配" }
        marker.targetKey?.let { require(MOBILE_WEB_UI_SHA256.matches(it)) { "WebUI reset marker target 无效" } }
        marker.fingerprint?.let { require(it.isNotBlank() && it.length <= 256) { "WebUI reset marker fingerprint 无效" } }
        require((marker.targetKey == null) == (marker.fingerprint == null)) {
            "WebUI reset marker target/fingerprint 必须成对"
        }
        require(marker.createdAt > 0) {
            "WebUI reset marker 状态无效"
        }
        deleteResetTemporaryFile(temporary)
        val directory = serverDirectory(serverId)
        if (directory.exists() && trash.exists()) {
            throw IOException("WebUI reset marker 同时存在 live 与 trash 目录")
        }
        if (directory.exists()) {
            if (!renameFile(directory, trash)) {
                throw IOException("无法恢复待重置 WebUI 缓存: $directory")
            }
            syncRootDirectory()
        }
        if (trash.exists()) {
            if (!deleteDirectory(trash)) {
                throw IOException("无法完成待恢复 WebUI reset: $trash")
            }
            syncRootDirectory()
        }
        if (!marker.physicalDeleted) {
            writeResetMarker(markerFile, marker.copy(physicalDeleted = true))
        }
        val reject = if (marker.targetKey != null) {
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = marker.targetKey,
                compatibilityFingerprint = requireNotNull(marker.fingerprint),
                reason = "manual_reset",
                firstRejectedAt = marker.createdAt,
                retryAfter = null,
            )
        } else {
            null
        }
        dao.resetServerDurableState(serverId, baselineState(serverId), reject)
        if (!deleteResetMarker(markerFile)) {
            throw MobileWebUiResetCleanupPendingException("WebUI reset 已提交，清理 marker 待重试: $markerFile")
        }
        syncRootDirectory()
    }

    private fun reconcileOrphanStaging(serverId: String) {
        val staging = serverDirectory(serverId).resolve("staging")
        staging.listFiles()?.toList().orEmpty()
            .filter { it.isDirectory }
            .forEach {
                if (!it.deleteRecursively()) throw IOException("无法删除无 owner 的 WebUI staging: $it")
            }
    }

    private suspend fun readVerifiedManifestMetadata(
        serverId: String,
        generation: MobileWebUiGenerationEntity,
    ): MobileWebUiManifest {
        val file = resolvePrivateFile(serverId, generation.manifestPath)
        val text = file.readText()
        requireNoDuplicateJsonKeys(text)
        val manifest = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(text)
        validateMobileWebUiManifest(manifest)
        require(manifest.generationId == generation.generationId)
        return manifest
    }

    /** 显式清除此服务端的派生 WebUI 缓存和指针。 */
    suspend fun resetServer(
        serverId: String,
        manualRejectTarget: MobileWebUiTarget? = null,
        fingerprint: String? = null,
        manualRejectTargetKey: String? = manualRejectTarget?.targetKey,
    ) = withServerLock(serverId) {
        val manualReject = if (manualRejectTarget != null || manualRejectTargetKey != null) {
            require(fingerprint != null) { "WebUI manual reset 缺少 compatibility fingerprint" }
            require(fingerprint.isNotBlank() && fingerprint.length <= 256) {
                "WebUI manual reset compatibility fingerprint 无效"
            }
            val targetKey = manualRejectTarget?.let {
                validateMobileWebUiTarget(it, serverId)
                it.targetKey
            } ?: manualRejectTargetKey
            require(targetKey != null && MOBILE_WEB_UI_SHA256.matches(targetKey)) {
                "WebUI manual reset target_key 无效"
            }
            MobileWebUiRejectEntity(
                serverId = serverId,
                targetKey = targetKey,
                compatibilityFingerprint = fingerprint,
                reason = "manual_reset",
                firstRejectedAt = System.currentTimeMillis(),
                retryAfter = null,
            )
        } else {
            null
        }
        reconcilePendingReset(serverId)
        val directory = serverDirectory(serverId)
        val marker = MobileWebUiResetMarker(
            serverId = serverId,
            targetKey = manualReject?.targetKey,
            fingerprint = manualReject?.compatibilityFingerprint,
            createdAt = manualReject?.firstRejectedAt ?: System.currentTimeMillis(),
            physicalDeleted = false,
        )
        val markerFile = resetMarkerFile(serverId)
        val trash = resetTrashDirectory(serverId)
        // 1. 接触文件前停止 serving；部分删除不能留下可用的 WebView 目标。
        if (activeServing?.serverId == serverId || attemptState.value?.serverId == serverId) {
            attemptState.value = null
            setCommittedServing(null, null)
        }
        if (markerFile.exists() || trash.exists()) throw IOException("WebUI reset 已有未完成事务")
        // 2. 移动或删除任何缓存前，持久化可恢复的 reset 意图。
        ensureDirectory(root, "无法创建 WebUI reset marker 目录")
        writeResetMarker(markerFile, marker)
        if (directory.exists()) {
            if (!renameFile(directory, trash)) {
                throw IOException("无法移动待重置 WebUI 缓存: $directory")
            }
            syncRootDirectory()
        }
        // 3. 删除 trash；失败时保留 marker 和 Room owner 以便恢复。
        if (trash.exists()) {
            if (!deleteDirectory(trash)) {
                throw IOException("无法重置 WebUI 缓存: $trash")
            }
            syncRootDirectory()
        }
        // 4. 清理物理文件后记录完成状态；后续 DB 失败仍可重放。
        writeResetMarker(markerFile, marker.copy(physicalDeleted = true))
        // 5. 只有物理清理成功后，才清除指针并写入手动拒绝。
        dao.resetServerDurableState(serverId, baselineState(serverId), manualReject)
        if (markerFile.exists()) {
            if (!deleteResetMarker(markerFile)) {
                throw MobileWebUiResetCleanupPendingException("WebUI reset 已提交，清理 marker 待重试: $markerFile")
            }
            syncRootDirectory()
        }
    }

    fun handlePresentation(path: String): WebResourceResponse? {
        val segments = path.split('/', limit = 3)
        if (segments.size != 3) return null
        val current = activeServing ?: return null
        if (segments[0] != current.serverId.sha256() || segments[1] != current.generationId) return null
        val manifest = servingManifest ?: return null
        return servePath(current, manifest, segments[2])
    }

    private fun servePath(current: Serving, manifest: MobileWebUiManifest, path: String): WebResourceResponse? {
        val normalized = try {
            normalizeMobileWebUiPath(path)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val file = manifest.files.firstOrNull { it.path == normalized } ?: return null
        val blob = blobFile(current.serverId, file.sha256)
        if (!blob.isFile || blob.length() != file.sizeBytes) return null
        return try {
            WebResourceResponse(
                file.mime,
                if (file.mime.startsWith("text/") || file.mime == "application/json") "utf-8" else null,
                200,
                "OK",
                mapOf(
                    "Cache-Control" to if (file.path == manifest.entrypoint) "no-store" else {
                        "public, max-age=31536000, immutable"
                    },
                    "Content-Length" to file.sizeBytes.toString(),
                    "X-Content-Type-Options" to "nosniff",
                ),
                FileInputStream(blob),
            )
        } catch (_: IOException) {
            null
        }
    }

    private fun manifestEntity(
        serverId: String,
        target: MobileWebUiTarget,
        manifest: MobileWebUiManifest,
        generation: File,
        now: Long,
    ) = MobileWebUiGenerationEntity(
        serverId = serverId,
        generationId = target.generationId,
        targetKey = target.targetKey,
        manifestDigest = target.manifestDigest,
        manifestPath = relativePath(serverId, generation.resolve("manifest.json")),
        entrypoint = manifest.entrypoint,
        bridgeProtocolMin = manifest.bridgeProtocolMin,
        bridgeProtocolMax = manifest.bridgeProtocolMax,
        snapshotProtocolMin = manifest.snapshotProtocolMin,
        snapshotProtocolMax = manifest.snapshotProtocolMax,
        minimumNativeBuild = manifest.minimumNativeBuild,
        platformsJson = MOBILE_WEB_UI_JSON.encodeToString(manifest.platforms),
        sourceRepository = manifest.sourceRepository,
        sourceCommit = manifest.sourceCommit,
        sourceTree = manifest.sourceTree,
        inputDigest = manifest.inputDigest,
        buildContextDigest = manifest.buildContextDigest,
        dirtyProvenanceJson = manifest.dirtyProvenance?.let { MOBILE_WEB_UI_JSON.encodeToString(it) },
        reproducible = manifest.reproducible,
        builderIdentityJson = MOBILE_WEB_UI_JSON.encodeToString(manifest.builderIdentity),
        unpackedSizeBytes = manifest.unpackedSizeBytes,
        fileCount = manifest.fileCount,
        verifiedAt = now,
        lastUsedAt = now,
    )

    private fun targetFromGeneration(generation: MobileWebUiGenerationEntity): MobileWebUiTarget {
        val target = MobileWebUiTarget(
            targetKey = generation.targetKey,
            generationId = generation.generationId,
            manifestDigest = generation.manifestDigest,
            manifestSizeBytes = resolvePrivateFile(generation.serverId, generation.manifestPath).length(),
            bridgeProtocolMin = generation.bridgeProtocolMin,
            bridgeProtocolMax = generation.bridgeProtocolMax,
            snapshotProtocolMin = generation.snapshotProtocolMin,
            snapshotProtocolMax = generation.snapshotProtocolMax,
            minimumNativeBuild = generation.minimumNativeBuild,
            platforms = MOBILE_WEB_UI_JSON.decodeFromString(generation.platformsJson),
        )
        validateMobileWebUiTarget(target, generation.serverId)
        return target
    }

    private fun setPresentation(next: Serving?, manifest: MobileWebUiManifest?) {
        activeServing = next
        servingManifest = manifest
    }

    private fun setDurableServing(next: Serving?) {
        servingState.value = next?.let { MobileWebUiServing(it.serverId, it.generationId) }
    }

    private fun setCommittedServing(next: Serving?, manifest: MobileWebUiManifest?) {
        setPresentation(next, manifest)
        setDurableServing(next)
    }

    private fun ensureDirectory(directory: File, message: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException(message)
    }

    private suspend fun blobOffsetLocked(serverId: String, sha256: String, bytes: Long): Long {
        val part = blobPart(serverId, sha256)
        val metadata = blobPartMetadata(serverId, sha256)
        if (metadata.isFile) {
            val stored = MOBILE_WEB_UI_JSON.decodeFromString<BlobPartMetadata>(metadata.readText())
            if (stored.sha256 != sha256 || stored.bytes != bytes || part.length() > bytes) {
                deletePart(part, metadata)
            }
        } else if (part.exists()) {
            deletePart(part, metadata)
        }
        if (!metadata.isFile) {
            ensureDirectory(requireNotNull(metadata.parentFile), "无法创建 WebUI partial metadata 目录")
            FileOutputStream(metadata).use { output ->
                output.write(MOBILE_WEB_UI_JSON.encodeToString(BlobPartMetadata(sha256, bytes)).toByteArray())
                output.fd.sync()
            }
        }
        if (bytes == 0L && !blobFile(serverId, sha256).isFile) {
            FileOutputStream(part, false).use { it.fd.sync() }
            publishBlob(serverId, sha256, 0)
            return 0
        }
        return part.length().coerceAtMost(bytes)
    }

    private suspend fun publishBlob(serverId: String, sha256: String, expectedBytes: Long) {
        val part = blobPart(serverId, sha256)
        require(part.isFile && part.length() == expectedBytes) { "WebUI blob 未完整下载" }
        require(part.sha256() == sha256) { "WebUI blob 摘要不一致" }
        val destination = blobFile(serverId, sha256)
        ensureDirectory(requireNotNull(destination.parentFile), "无法创建 WebUI blob 目录")
        if (destination.exists() && (destination.length() != expectedBytes || destination.sha256() != sha256)) {
            if (!deletePhysicalFile(destination)) throw IOException("无法删除损坏 WebUI blob")
        }
        if (!destination.exists() && !renameFile(part, destination)) throw IOException("WebUI blob 原子提交失败")
        deletePart(part, blobPartMetadata(serverId, sha256))
        val now = System.currentTimeMillis()
        dao.upsertBlob(
            MobileWebUiBlobEntity(
                serverId = serverId,
                sha256 = sha256,
                bytes = expectedBytes,
                relativePath = relativePath(serverId, destination),
                lastUsedAt = now,
            ),
        )
    }

    private suspend fun readVerifiedGeneration(
        serverId: String,
        generation: MobileWebUiGenerationEntity,
    ): MobileWebUiManifest {
        val file = resolvePrivateFile(serverId, generation.manifestPath)
        require(file.isFile && file.sha256() == generation.manifestDigest) {
            "WebUI generation manifest 摘要不一致"
        }
        val bytes = file.readBytes()
        require(bytes.size <= MOBILE_WEB_UI_MAX_MANIFEST_BYTES) { "WebUI manifest 超过大小上限" }
        val manifestText = bytes.toString(StandardCharsets.UTF_8)
        requireNoDuplicateJsonKeys(manifestText)
        val manifest = MOBILE_WEB_UI_JSON.decodeFromString<MobileWebUiManifest>(manifestText)
        validateMobileWebUiManifest(manifest)
        require(bytes.contentEquals(mobileWebUiManifestBytes(manifest))) { "WebUI manifest 非 canonical JSON" }
        require(generation.generationId == manifest.generationId) { "WebUI generation metadata 不匹配" }
        require(generation.manifestDigest == mobileWebUiManifestDigest(manifest)) {
            "WebUI generation manifest metadata 不匹配"
        }
        require(generation.targetKey == deriveMobileWebUiTargetKey(serverId, manifest.generationId, generation.manifestDigest)) {
            "WebUI generation target metadata 不匹配"
        }
        require(generation.entrypoint == manifest.entrypoint)
        require(generation.bridgeProtocolMin == manifest.bridgeProtocolMin)
        require(generation.bridgeProtocolMax == manifest.bridgeProtocolMax)
        require(generation.snapshotProtocolMin == manifest.snapshotProtocolMin)
        require(generation.snapshotProtocolMax == manifest.snapshotProtocolMax)
        require(generation.minimumNativeBuild == manifest.minimumNativeBuild)
        require(generation.unpackedSizeBytes == manifest.unpackedSizeBytes)
        require(generation.fileCount == manifest.fileCount)
        require(manifest.files.all { item ->
            val blob = blobFile(serverId, item.sha256)
            val valid = blob.isFile && blob.length() == item.sizeBytes && blob.sha256() == item.sha256
            if (!valid) {
                if (blob.isFile && !deletePhysicalFile(blob)) {
                    throw IOException("无法删除损坏 WebUI blob: ${item.sha256}")
                }
                dao.deleteBlob(serverId, item.sha256)
            }
            valid
        }) { "WebUI generation 缺少已验证 blob" }
        return manifest
    }

    private suspend fun readVerifiedManifestLocked(
        serverId: String,
        target: MobileWebUiTarget,
    ): MobileWebUiManifest? {
        validateMobileWebUiTarget(target, serverId)
        val generation = dao.getGeneration(serverId, target.generationId) ?: return null
        if (generation.targetKey != target.targetKey || generation.manifestDigest != target.manifestDigest) return null
        val manifest = try {
            readVerifiedGeneration(serverId, generation)
        } catch (_: IllegalArgumentException) {
            return null
        }
        validateMobileWebUiManifest(manifest, target)
        return manifest
    }

    private fun baselineState(serverId: String) = MobileWebUiStateEntity(
        serverId = serverId,
        desiredChannel = "baseline",
        desiredTargetKey = null,
        desiredGenerationId = null,
        desiredManifestDigest = null,
        releaseEpoch = null,
        releaseSequence = null,
        selectionDigest = null,
        servingGenerationId = null,
        fallbackGenerationId = null,
        attemptingGenerationId = null,
        attemptingNonce = null,
        attemptingStartedAt = null,
        lastHealthyAt = null,
        updatedAt = System.currentTimeMillis(),
    )

    private fun compatibilityFingerprint(): String =
        "android-$nativeBuild-$MOBILE_WEB_UI_BRIDGE_PROTOCOL-$MOBILE_WEB_UI_SNAPSHOT_PROTOCOL"

    private fun validateBlobRequest(sha256: String, bytes: Long) {
        require(MOBILE_WEB_UI_SHA256.matches(sha256)) { "WebUI blob 摘要无效" }
        require(bytes in 0..MOBILE_WEB_UI_MAX_FILE_BYTES) { "WebUI blob 大小无效" }
    }

    private fun serverDirectory(serverId: String): File = root.resolve(serverId.sha256())

    private fun resetMarkerFile(serverId: String): File =
        root.resolve("${serverId.sha256()}.reset.json")

    private fun resetMarkerTemporaryFile(serverId: String): File =
        root.resolve("${serverId.sha256()}.reset.json.tmp")

    private fun resetTrashDirectory(serverId: String): File =
        root.resolve("${serverId.sha256()}.reset-trash")

    private fun deleteResetTemporaryFile(file: File): Boolean {
        if (!file.exists()) return false
        if (!file.delete()) {
            throw IOException("无法清理 WebUI reset marker 临时文件: $file")
        }
        syncRootDirectory()
        return true
    }

    private fun writeResetMarker(file: File, marker: MobileWebUiResetMarker) {
        val temporary = file.resolveSibling("${file.name}.tmp")
        deleteResetTemporaryFile(temporary)
        FileOutputStream(temporary, false).use { output ->
            output.write(MOBILE_WEB_UI_JSON.encodeToString(marker).toByteArray())
            output.fd.sync()
        }
        if (!renameFile(temporary, file)) {
            throw IOException("无法原子提交 WebUI reset marker: $file")
        }
        syncRootDirectory()
    }

    private fun generationDirectory(serverId: String, generationId: String): File =
        serverDirectory(serverId).resolve("generations").resolve(generationId)

    private fun stagingGenerationDirectory(serverId: String, generationId: String): File =
        serverDirectory(serverId).resolve("staging").resolve(generationId)

    private fun blobFile(serverId: String, sha256: String): File =
        serverDirectory(serverId).resolve("blobs").resolve(sha256)

    private fun blobPart(serverId: String, sha256: String): File =
        serverDirectory(serverId).resolve("partials").resolve("$sha256.part")

    private fun blobPartMetadata(serverId: String, sha256: String): File =
        serverDirectory(serverId).resolve("partials").resolve("$sha256.meta")

    private fun resolvePrivateFile(serverId: String, relative: String): File {
        val base = serverDirectory(serverId).canonicalFile
        val file = base.resolve(relative).canonicalFile
        require(file == base || file.path.startsWith("${base.path}${File.separator}")) {
            "WebUI 私有路径逃逸"
        }
        return file
    }

    private fun relativePath(serverId: String, file: File): String =
        file.canonicalFile.relativeTo(serverDirectory(serverId).canonicalFile).path

    private fun deletePart(part: File, metadata: File) {
        if (part.exists() && !deletePartialFile(part)) throw IOException("无法删除 WebUI partial: $part")
        if (metadata.exists() && !deletePartialFile(metadata)) throw IOException("无法删除 WebUI partial metadata: $metadata")
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun String.sha256(): String = toByteArray(Charsets.UTF_8).sha256()

    private data class Serving(val serverId: String, val generationId: String)

    @kotlinx.serialization.Serializable
    private data class BlobPartMetadata(val sha256: String, val bytes: Long)
}
