package com.akashic.mobile.data.realtime

import android.database.sqlite.SQLiteException
import java.io.IOException
import java.util.UUID
import com.akashic.mobile.data.local.MobileWebUiStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class MobileWebUiHttpAction {
    ACCEPT,
    REFRESH_TICKET,
    RESOLVE_RELEASE,
    RESET_RANGE,
    REJECT_TARGET,
    RETRY_AFTER,
}

internal enum class MobileWebUiCommandAction {
    CONTINUE,
    RESOLVE_RELEASE,
    WAIT_FOR_AUTH,
    RETRY_AFTER,
    REPORT_UNKNOWN,
}

data class MobileWebUiResetEvent(val serverId: String, val sequence: Long)

internal fun classifyMobileWebUiCommandError(code: String?): MobileWebUiCommandAction = when (code) {
    null -> MobileWebUiCommandAction.REPORT_UNKNOWN
    "target_not_found", "target_changed" -> MobileWebUiCommandAction.RESOLVE_RELEASE
    "capability_required", "unauthorized", "auth_required" -> MobileWebUiCommandAction.WAIT_FOR_AUTH
    "release_store_corrupt", "temporarily_unavailable" -> MobileWebUiCommandAction.RETRY_AFTER
    else -> MobileWebUiCommandAction.REPORT_UNKNOWN
}

/** Classify an authenticated WebUI HTTP response without throwing into realtime ownership. */
internal fun classifyMobileWebUiHttp(
    kind: String,
    statusCode: Int,
    errorCode: String? = null,
): MobileWebUiHttpAction = when {
    (kind == "manifest" && statusCode == 200) || (kind == "blob" && statusCode == 206) ->
        MobileWebUiHttpAction.ACCEPT
    statusCode == 401 && errorCode == "invalid_ticket" -> MobileWebUiHttpAction.REFRESH_TICKET
    statusCode == 401 -> MobileWebUiHttpAction.RETRY_AFTER
    statusCode == 409 && errorCode == "target_changed" -> MobileWebUiHttpAction.RESOLVE_RELEASE
    statusCode == 412 -> MobileWebUiHttpAction.RESET_RANGE
    statusCode == 416 -> MobileWebUiHttpAction.RESET_RANGE
    statusCode == 404 && errorCode == "resource_not_found" -> MobileWebUiHttpAction.REJECT_TARGET
    statusCode == 500 && errorCode == "release_store_corrupt" -> MobileWebUiHttpAction.RETRY_AFTER
    statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599 ->
        MobileWebUiHttpAction.RETRY_AFTER
    statusCode in 400..499 -> MobileWebUiHttpAction.REJECT_TARGET
    else -> MobileWebUiHttpAction.RETRY_AFTER
}

private const val MOBILE_WEB_UI_MAX_RETRIES = 3
private val MOBILE_WEB_UI_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
private val MOBILE_WEB_UI_ERROR_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = true
}

/** Resolves one server release, ensures immutable blobs, and leaves activation to the UI session boundary. */
internal class MobileWebUiCoordinator(
    private val store: MobileWebUiStore,
    private val scope: CoroutineScope,
    private val nativeBuild: Int,
    private val sendCommand: (String, JsonObject) -> String?,
    private val startHttp: (MobileWebUiHttpRequest) -> Unit,
    private val onError: (String) -> Unit,
    private val onRetryTrigger: (String) -> Unit,
) {
    private data class EnsureContext(
        val token: Long,
        val serverId: String,
        val target: MobileWebUiTarget,
        var grant: MobileWebUiContentGrant? = null,
        var manifest: MobileWebUiManifest? = null,
        var fileIndex: Int = 0,
        var refreshes: Int = 0,
        val rangeResets: MutableMap<String, Int> = mutableMapOf(),
        var transientFailures: Int = 0,
    )

    private enum class HttpKind { MANIFEST, BLOB }

    private data class PendingHttp(
        val requestId: String,
        val context: EnsureContext,
        val kind: HttpKind,
        val sha256: String? = null,
        val sizeBytes: Long? = null,
        val offset: Long? = null,
    )

    private var token = 0L
    private var currentServerId: String? = null
    private var pendingCommandId: String? = null
    private var pendingCommandKind: String? = null
    private var ensure: EnsureContext? = null
    private var pendingHttp: PendingHttp? = null
    private var lastReleaseView: MobileWebUiReleaseView? = null
    private var retryJob: Job? = null
    private var retryTargetKey: String? = null
    private var retryAttempts = 0
    private var manualRetryRequested = false
    private var resolveInFlight = false
    private var resolveDirty = false
    private var resolveRetryJob: Job? = null
    private var resolveRetryAttempts = 0

    private val readyGenerationState = MutableStateFlow<String?>(null)
    val readyGeneration: StateFlow<String?> = readyGenerationState.asStateFlow()
    private val waitForSpaceState = MutableStateFlow<MobileWebUiSpaceAdmission?>(null)
    val waitForSpace: StateFlow<MobileWebUiSpaceAdmission?> = waitForSpaceState.asStateFlow()
    private var resetSequence = 0L
    private val resetEventState = MutableStateFlow<MobileWebUiResetEvent?>(null)
    val resetEvent: StateFlow<MobileWebUiResetEvent?> = resetEventState.asStateFlow()

    suspend fun onAuthenticated(serverId: String) {
        if (currentServerId != serverId) {
            resolveInFlight = false
            resolveDirty = false
            resolveRetryJob?.cancel()
            resolveRetryJob = null
            resolveRetryAttempts = 0
            manualRetryRequested = false
            readyGenerationState.value = null
            waitForSpaceState.value = null
        }
        currentServerId = serverId
        try {
            restoreLocal(serverId)
            resolve(serverId)
        } catch (error: IOException) {
            onError("WebUI 本地缓存恢复失败：${error.message}")
        } catch (error: SQLiteException) {
            onError("WebUI 本地状态数据库暂不可用：${error.message}")
        } catch (error: SecurityException) {
            onError("WebUI 本地缓存无权访问：${error.message}")
        }
    }

    /** Restore only the durable committed WebUI before the first UI composition. */
    suspend fun restoreLocal(serverId: String): Boolean {
        currentServerId = serverId
        store.reconcile(serverId)
        val restored = store.restoreCommittedServing(serverId)
        val state = store.state(serverId)
        val desiredGeneration = state?.desiredGenerationId
        if (
            desiredGeneration != null &&
            !store.isGenerationRejected(serverId, desiredGeneration, compatibilityFingerprint()) &&
            store.hasVerifiedGeneration(serverId, desiredGeneration)
        ) {
            readyGenerationState.value = desiredGeneration
        }
        return restored
    }

    fun onDisconnected() {
        token += 1
        pendingCommandId = null
        pendingCommandKind = null
        pendingHttp = null
        ensure = null
        lastReleaseView = null
        retryJob?.cancel()
        retryJob = null
        retryTargetKey = null
        retryAttempts = 0
        resolveInFlight = false
        resolveDirty = false
        resolveRetryJob?.cancel()
        resolveRetryJob = null
        resolveRetryAttempts = 0
        manualRetryRequested = false
        waitForSpaceState.value = null
    }

    fun onControlHint(serverId: String) {
        if (serverId != currentServerId) return
        resolve(serverId)
    }

    /** Resolve current release pointers; hints never apply a target directly. */
    fun resolve(serverId: String? = currentServerId) {
        val selectedServer = serverId ?: return
        if (selectedServer != currentServerId) return
        resolveRetryJob?.cancel()
        resolveRetryJob = null
        resolveRetryAttempts = 0
        if (resolveInFlight) {
            resolveDirty = true
            return
        }
        beginResolve(selectedServer)
    }

    private fun beginResolve(selectedServer: String) {
        if (selectedServer != currentServerId) return
        token += 1
        retryJob?.cancel()
        retryJob = null
        pendingCommandId = null
        pendingCommandKind = null
        pendingHttp = null
        ensure = null
        val commandId = sendCommand(MOBILE_WEB_UI_RELEASE_GET, kotlinx.serialization.json.buildJsonObject {})
        if (commandId == null) {
            resolveInFlight = false
            onError("WebUI 发布检查等待认证连接")
            return
        }
        resolveInFlight = true
        pendingCommandId = commandId
        pendingCommandKind = MOBILE_WEB_UI_RELEASE_GET
    }

    /** Route authenticated WebSocket replies to the single release/prepare owner. */
    suspend fun onReply(envelope: WireEnvelope): Boolean {
        val id = envelope.id ?: return false
        if (id != pendingCommandId) return false
        val kind = pendingCommandKind
        pendingCommandId = null
        pendingCommandKind = null
        when (kind) {
            MOBILE_WEB_UI_RELEASE_GET -> {
                try {
                    if (envelope.type != "$MOBILE_WEB_UI_RELEASE_GET.ok" &&
                        envelope.type != "$MOBILE_WEB_UI_RELEASE_GET.error"
                    ) {
                        onError("WebUI release reply type 不匹配")
                        return true
                    }
                    if (envelope.type.endsWith(".error")) {
                        handleCommandError(
                            null,
                            envelope.payload["code"]?.jsonPrimitive?.content,
                            envelope.payload["message"]?.jsonPrimitive?.content ?: "WebUI 发布检查失败",
                        )
                        return true
                    }
                    val view = try {
                        ProtocolCodec.decodePayload<MobileWebUiReleaseView>(envelope.payload)
                    } catch (_: SerializationException) {
                        onError("WebUI 发布检查响应无效")
                        return true
                    } catch (_: IllegalArgumentException) {
                        onError("WebUI 发布检查响应无效")
                        return true
                    }
                    try {
                        handleReleaseView(view)
                    } catch (_: IllegalArgumentException) {
                        onError("WebUI 发布选择不符合合同")
                    } catch (error: SQLiteException) {
                        scheduleResolveRetry(currentServerId, "WebUI 发布状态写入失败：${error.message}")
                    } catch (error: SecurityException) {
                        scheduleResolveRetry(currentServerId, "WebUI 发布缓存无权访问：${error.message}")
                    }
                } finally {
                    finishResolve(currentServerId)
                }
                return true
            }
            MOBILE_WEB_UI_CONTENT_PREPARE -> {
                if (envelope.type != "$MOBILE_WEB_UI_CONTENT_PREPARE.ok" && envelope.type != "$MOBILE_WEB_UI_CONTENT_PREPARE.error") {
                    onError("WebUI content.prepare reply type 不匹配")
                    return true
                }
                val context = ensure ?: return true
                if (envelope.type.endsWith(".error")) {
                    handleCommandError(
                        context,
                        envelope.payload["code"]?.jsonPrimitive?.content,
                        envelope.payload["message"]?.jsonPrimitive?.content ?: "WebUI 内容授权失败",
                    )
                    return true
                }
                val grant = try {
                    ProtocolCodec.decodePayload<MobileWebUiContentGrant>(envelope.payload)
                } catch (_: SerializationException) {
                    onError("WebUI 内容授权响应无效")
                    ensure = null
                    resolve(context.serverId)
                    return true
                } catch (_: IllegalArgumentException) {
                    onError("WebUI 内容授权响应无效")
                    ensure = null
                    resolve(context.serverId)
                    return true
                }
                try {
                    validateMobileWebUiContentGrant(grant, context.target)
                } catch (_: IllegalArgumentException) {
                    onError("WebUI 内容授权响应无效")
                    ensure = null
                    resolve(context.serverId)
                    return true
                }
                context.grant = grant
                requestManifest(context)
                return true
            }
            else -> return false
        }
    }

    /** Deliver an HTTP result to the matching manifest/blob operation. */
    suspend fun onHttpResponse(requestId: String, response: MobileWebUiHttpResponse) {
        val pending = pendingHttp ?: return
        if (pending.requestId != requestId) return
        pendingHttp = null
        if (!isCurrent(pending.context)) return
        when (classifyMobileWebUiHttp(pending.kind.name.lowercase(), response.statusCode, mobileWebUiErrorCode(response))) {
            MobileWebUiHttpAction.ACCEPT -> try {
                when (pending.kind) {
                    HttpKind.MANIFEST -> handleManifestHttp(pending.context, response)
                    HttpKind.BLOB -> handleBlobHttp(pending, response)
                }
            } catch (_: IllegalArgumentException) {
                rejectCurrentTargetSafely(pending.context, "wire_invalid")
            } catch (_: IllegalStateException) {
                rejectCurrentTargetSafely(pending.context, "wire_invalid")
            } catch (_: IOException) {
                scheduleRetryAfter(pending.context, "缓存写入失败")
            } catch (error: SQLiteException) {
                scheduleRetryAfter(pending.context, "缓存数据库暂不可用：${error.message}")
            } catch (error: SecurityException) {
                scheduleRetryAfter(pending.context, "缓存无权访问：${error.message}")
            }
            MobileWebUiHttpAction.REFRESH_TICKET -> {
                if (pending.context.refreshes == 0) {
                    pending.context.refreshes += 1
                    requestPrepare(pending.context)
                } else {
                    scheduleRetryAfter(pending.context, "授权票据暂时无效")
                }
            }
            MobileWebUiHttpAction.RESOLVE_RELEASE -> resolve(pending.context.serverId)
            MobileWebUiHttpAction.RESET_RANGE -> resetRangeAndRetry(pending, response.statusCode)
            MobileWebUiHttpAction.REJECT_TARGET -> rejectCurrentTargetSafely(
                pending.context,
                mobileWebUiErrorCode(response) ?: "http_${response.statusCode}",
            )
            MobileWebUiHttpAction.RETRY_AFTER -> scheduleRetryAfter(
                pending.context,
                "HTTP ${response.statusCode}",
            )
        }
    }

    fun onHttpFailure(requestId: String, message: String) {
        val pending = pendingHttp ?: return
        if (pending.requestId != requestId) return
        pendingHttp = null
        if (isCurrent(pending.context)) scheduleRetryAfter(pending.context, message)
    }

    /** Convert an owner-side storage failure into the same bounded retry state as HTTP failure. */
    fun onOwnerFailure(requestId: String, message: String) {
        val pending = pendingHttp ?: return
        if (pending.requestId != requestId) return
        pendingHttp = null
        if (isCurrent(pending.context)) scheduleRetryAfter(pending.context, message)
    }

    suspend fun state(serverId: String): MobileWebUiStateEntity? = store.state(serverId)

    /** Apply a server-selected baseline only at the next real UI session boundary. */
    suspend fun applyDesiredBaselineForSession(serverId: String): Boolean {
        if (serverId != currentServerId) return false
        val applied = store.applyDesiredBaselineForSession(serverId)
        if (applied) invalidateOwner()
        return applied
    }

    suspend fun collectGarbage(serverId: String): MobileWebUiGcReport {
        val wasWaitingForSpace = waitForSpaceState.value != null
        invalidateOwner()
        val local = store.collectGarbage(serverId)
        val global = store.collectGlobalGarbage()
        val report = MobileWebUiGcReport(
            removedGenerations = local.removedGenerations + global.removedGenerations,
            removedBlobs = local.removedBlobs + global.removedBlobs,
            removedBytes = local.removedBytes + global.removedBytes,
            blockedGenerations = local.blockedGenerations + global.blockedGenerations,
            unownedFiles = local.unownedFiles + global.unownedFiles,
        )
        if (wasWaitingForSpace && currentServerId == serverId) {
            waitForSpaceState.value = null
            resolve(serverId)
        }
        return report
    }

    suspend fun resetServer(serverId: String) {
        val before = store.state(serverId)
        val resetTarget = lastReleaseView?.let { view ->
            sequenceOf(view.preview, view.stable).filterNotNull().firstOrNull {
                it.targetKey == before?.desiredTargetKey
            }
        }
        val resetTargetKey = before?.desiredTargetKey ?: resetTarget?.targetKey
        invalidateOwner()
        readyGenerationState.value = null
        waitForSpaceState.value = null
        emitResetEvent(serverId)
        store.resetServer(
            serverId,
            manualRejectTarget = resetTarget,
            fingerprint = resetTargetKey?.let { compatibilityFingerprint() },
            manualRejectTargetKey = resetTargetKey,
        )
    }

    /** Revoke remote presentation immediately while retaining verified cache files. */
    suspend fun revokeServer(serverId: String) {
        if (serverId != currentServerId) return
        invalidateOwner()
        lastReleaseView = null
        readyGenerationState.value = null
        waitForSpaceState.value = null
        emitResetEvent(serverId)
        store.clearServingToBaseline(serverId)
    }

    /** Clear only the exact manual-reset rejection after an explicit user retry. */
    suspend fun retryCurrentUi(serverId: String): Boolean {
        if (serverId != currentServerId) return false
        manualRetryRequested = true
        waitForSpaceState.value = null
        resolve(serverId)
        return true
    }

    suspend fun invalidateReady(generationId: String) {
        if (readyGenerationState.value == generationId) readyGenerationState.value = null
    }

    suspend fun openAttempt(serverId: String, generationId: String): String? {
        if (readyGenerationState.value != generationId) return null
        val nonce = UUID.randomUUID().toString()
        try {
            if (!store.openAttempt(serverId, generationId, nonce, compatibilityFingerprint())) return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return nonce
    }

    suspend fun commitHealthy(serverId: String, generationId: String, nonce: String) =
        store.commitHealthy(serverId, generationId, nonce)

    suspend fun rejectServing(
        serverId: String,
        generationId: String,
        reason: String,
        forceBaseline: Boolean = false,
    ): String? {
        if (forceBaseline) {
            if (readyGenerationState.value == generationId) readyGenerationState.value = null
            store.rejectServingAndBaseline(
                serverId,
                generationId,
                compatibilityFingerprint(),
                reason,
            )
            return null
        }
        val fallback = store.rejectServingAndRollback(
            serverId,
            generationId,
            compatibilityFingerprint(),
            reason,
        )
        if (readyGenerationState.value == generationId) readyGenerationState.value = null
        return fallback
    }

    private suspend fun handleReleaseView(view: MobileWebUiReleaseView) {
        val serverId = currentServerId ?: return
        validateMobileWebUiReleaseView(view, serverId)
        lastReleaseView = view
        waitForSpaceState.value = null
        if (manualRetryRequested) {
            sequenceOf(view.preview, view.stable).filterNotNull().forEach {
                store.clearManualResetReject(serverId, it, compatibilityFingerprint())
            }
            manualRetryRequested = false
        }
        val target = compatibleTarget(serverId, view.preview) ?: compatibleTarget(serverId, view.stable)
        store.setDesired(serverId, view, target)
        if (target == null) {
            ensure = null
            readyGenerationState.value = null
            retryTargetKey = null
            retryAttempts = 0
            return
        }
        if (retryTargetKey != target.targetKey) {
            retryTargetKey = target.targetKey
            retryAttempts = 0
        }
        startEnsure(serverId, target)
    }

    private suspend fun startEnsure(serverId: String, target: MobileWebUiTarget) {
        val context = EnsureContext(++token, serverId, target)
        ensure = context
        readyGenerationState.value = null
        val ready = store.readVerifiedManifest(serverId, target)
        if (ready != null) {
            readyGenerationState.value = target.generationId
            ensure = null
            return
        }
        requestPrepare(context)
    }

    private suspend fun compatibleTarget(serverId: String, target: MobileWebUiTarget?): MobileWebUiTarget? {
        target ?: return null
        validateMobileWebUiTarget(target, serverId)
        val fingerprint = compatibilityFingerprint()
        if (target.minimumNativeBuild > nativeBuild ||
            MOBILE_WEB_UI_BRIDGE_PROTOCOL !in target.bridgeProtocolMin..target.bridgeProtocolMax ||
            MOBILE_WEB_UI_SNAPSHOT_PROTOCOL !in target.snapshotProtocolMin..target.snapshotProtocolMax ||
            "android" !in target.platforms
        ) {
            store.markRejected(serverId, target, fingerprint, "native_compatibility")
            return null
        }
        if (store.isRejected(serverId, target, fingerprint)) return null
        return target
    }

    private fun requestPrepare(context: EnsureContext) {
        if (!isCurrent(context)) return
        val commandId = sendCommand(
            MOBILE_WEB_UI_CONTENT_PREPARE,
            kotlinx.serialization.json.buildJsonObject {
                put("target_key", context.target.targetKey)
            },
        ) ?: run {
            onError("WebUI 内容授权等待认证连接")
            return
        }
        pendingCommandId = commandId
        pendingCommandKind = MOBILE_WEB_UI_CONTENT_PREPARE
    }

    private suspend fun handleCommandError(context: EnsureContext?, code: String?, message: String) {
        when (classifyMobileWebUiCommandError(code)) {
            MobileWebUiCommandAction.RESOLVE_RELEASE -> {
                ensure = null
                resolve(context?.serverId ?: currentServerId)
            }
            MobileWebUiCommandAction.WAIT_FOR_AUTH -> {
                ensure = null
                onError("WebUI 等待认证能力：$message")
            }
            MobileWebUiCommandAction.RETRY_AFTER -> {
                if (context != null) scheduleRetryAfter(context, message)
                else scheduleResolveRetry(currentServerId, message)
            }
            MobileWebUiCommandAction.REPORT_UNKNOWN -> {
                ensure = null
                onError("WebUI 服务端错误${code?.let { "[$it]" }.orEmpty()}：$message")
            }
            MobileWebUiCommandAction.CONTINUE -> Unit
        }
    }

    private fun requestManifest(context: EnsureContext) {
        val grant = requireNotNull(context.grant) { "WebUI content.prepare 未返回 ticket" }
        val requestId = UUID.randomUUID().toString()
        pendingHttp = PendingHttp(requestId, context, HttpKind.MANIFEST)
        startHttp(
            MobileWebUiHttpRequest(
                requestId = requestId,
                path = "$MOBILE_WEB_UI_MANIFEST_PATH/${context.target.manifestDigest}",
                ticket = grant.ticket,
            ),
        )
    }

    private suspend fun handleManifestHttp(context: EnsureContext, response: MobileWebUiHttpResponse) {
        require(response.statusCode == 200) { "WebUI manifest HTTP 状态异常: ${response.statusCode}" }
        require(response.contentLength == context.target.manifestSizeBytes) {
            "WebUI manifest Content-Length 不一致"
        }
        require(response.etag == mobileWebUiStrongEtag(context.target.manifestDigest)) {
            "WebUI manifest ETag 无效"
        }
        require(mobileWebUiContentDigestMatches(response.contentDigest, context.target.manifestDigest)) {
            "WebUI manifest Content-Digest 无效"
        }
        require(mobileWebUiContentDigestMatches(response.representationDigest, context.target.manifestDigest)) {
            "WebUI manifest Repr-Digest 无效"
        }
        require(response.body.size.toLong() == context.target.manifestSizeBytes) {
            "WebUI manifest HTTP 长度不一致"
        }
        val manifest = store.commitManifest(context.serverId, context.target, response.body)
        val admission = store.prepareDownloadSpace(context.serverId, manifest)
        if (!admission.allowed) {
            ensure = null
            waitForSpaceState.value = admission
            onError(
                "WebUI 等待缓存空间（${admission.blockedReason}）：" +
                    "需新增 ${admission.requiredBytes} B，可用磁盘 ${admission.usableSpaceBytes} B，" +
                    "服务端缓存 ${admission.serverBytes}/${admission.serverBudgetBytes} B，" +
                    "全局缓存 ${admission.globalBytes}/${admission.globalBudgetBytes} B；" +
                    "请清理后重试",
            )
            return
        }
        context.manifest = manifest
        context.fileIndex = 0
        downloadNextFile(context)
    }

    private suspend fun downloadNextFile(context: EnsureContext) {
        while (context.fileIndex < requireNotNull(context.manifest).files.size) {
            val file = requireNotNull(context.manifest).files[context.fileIndex]
            if (store.hasBlob(context.serverId, file.sha256, file.sizeBytes)) {
                context.fileIndex += 1
                continue
            }
            if (file.sizeBytes == 0L) {
                store.ensureEmptyBlob(context.serverId, file.sha256)
                context.fileIndex += 1
                continue
            }
            val offset = store.blobOffset(context.serverId, file.sha256, file.sizeBytes)
            val grant = requireNotNull(context.grant) { "WebUI blob 下载缺少 ticket" }
            val requestId = UUID.randomUUID().toString()
            pendingHttp = PendingHttp(
                requestId = requestId,
                context = context,
                kind = HttpKind.BLOB,
                sha256 = file.sha256,
                sizeBytes = file.sizeBytes,
                offset = offset,
            )
            startHttp(
                MobileWebUiHttpRequest(
                    requestId = requestId,
                    path = "$MOBILE_WEB_UI_BLOB_PATH/${file.sha256}",
                    ticket = grant.ticket,
                    blobSha256 = file.sha256,
                    byteLength = file.sizeBytes,
                    offset = offset,
                ),
            )
            return
        }
        require(store.readVerifiedManifest(context.serverId, context.target) != null) {
            "WebUI generation 完成后验证失败"
        }
        readyGenerationState.value = context.target.generationId
        ensure = null
        scope.launch {
            try {
                store.collectGarbage(context.serverId)
                store.collectGlobalGarbage()
            } catch (error: IOException) {
                onError("WebUI 缓存整理失败：${error.message}")
            } catch (error: IllegalArgumentException) {
                onError("WebUI 缓存整理失败：${error.message}")
            }
        }
    }

    private suspend fun handleBlobHttp(pending: PendingHttp, response: MobileWebUiHttpResponse) {
        require(response.statusCode == 206) { "WebUI blob HTTP 状态异常: ${response.statusCode}" }
        val sha256 = requireNotNull(pending.sha256)
        val expectedBytes = requireNotNull(pending.sizeBytes)
        val offset = requireNotNull(pending.offset)
        require(response.contentLength == response.body.size.toLong()) {
            "WebUI blob Content-Length 不一致"
        }
        require(response.etag == mobileWebUiStrongEtag(sha256)) { "WebUI blob ETag 无效" }
        require(mobileWebUiContentDigestMatches(response.representationDigest, sha256)) {
            "WebUI blob Repr-Digest 无效"
        }
        require(mobileWebUiContentDigestMatches(response.contentDigest, mobileWebUiSha256Hex(response.body))) {
            "WebUI blob Content-Digest 无效"
        }
        require(response.body.isNotEmpty()) { "WebUI blob HTTP 响应为空" }
        val range = requireNotNull(response.contentRange) { "WebUI blob 缺少 Content-Range" }
        val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(range)
            ?: error("WebUI blob Content-Range 无效")
        require(match.groupValues[1].toLong() == offset)
        require(match.groupValues[2].toLong() == offset + response.body.size - 1)
        require(match.groupValues[3].toLong() == expectedBytes)
        val next = store.appendBlob(contextServer(pending), sha256, expectedBytes, offset, response.body)
        require(next == expectedBytes || next == offset + response.body.size) {
            "WebUI blob offset 推进异常"
        }
        if (next == expectedBytes) pending.context.fileIndex += 1
        downloadNextFile(pending.context)
    }

    private suspend fun resetRangeAndRetry(pending: PendingHttp, statusCode: Int) {
        val sha256 = pending.sha256
        val sizeBytes = pending.sizeBytes
        if (pending.kind != HttpKind.BLOB || sha256 == null || sizeBytes == null) {
            rejectCurrentTargetSafely(pending.context, "manifest_range_$statusCode")
            return
        }
        val attempts = pending.context.rangeResets.getOrDefault(sha256, 0)
        if (attempts >= 1) {
            rejectCurrentTargetSafely(pending.context, "range_retry_exhausted")
            return
        }
        pending.context.rangeResets[sha256] = attempts + 1
        try {
            store.resetBlobPartial(pending.context.serverId, sha256, sizeBytes)
            downloadNextFile(pending.context)
        } catch (_: IllegalArgumentException) {
            rejectCurrentTargetSafely(pending.context, "range_reset_invalid")
        } catch (_: IOException) {
            scheduleRetryAfter(pending.context, "partial 清理失败")
        }
    }

    private suspend fun rejectCurrentTarget(context: EnsureContext, reason: String) {
        if (!isCurrent(context)) return
        context.manifest?.files?.forEach { file ->
            try {
                store.resetBlobPartial(context.serverId, file.sha256, file.sizeBytes)
            } catch (_: IllegalArgumentException) {
                // Manifest validation already owns the digest/size boundary.
            } catch (_: IOException) {
                onError("WebUI partial 清理失败：${file.path}")
            }
        }
        store.markRejected(context.serverId, context.target, compatibilityFingerprint(), reason)
        val release = lastReleaseView
        val stable = release?.stable
            ?.takeUnless { it.targetKey == context.target.targetKey }
            ?.let { candidate ->
                try {
                    compatibleTarget(context.serverId, candidate)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        ensure = null
        readyGenerationState.value = null
        if (release != null && stable != null) {
            store.setDesired(context.serverId, release, stable)
            startEnsure(context.serverId, stable)
            return
        }
        if (release != null) store.setDesired(context.serverId, release, null)
        onError("WebUI target 已拒绝（$reason），保留当前界面并在下一次会话使用内置界面")
    }

    private suspend fun rejectCurrentTargetSafely(context: EnsureContext, reason: String) {
        try {
            rejectCurrentTarget(context, reason)
        } catch (error: IOException) {
            scheduleRetryAfter(context, "WebUI 拒绝目标时缓存写入失败：${error.message}")
        } catch (error: SQLiteException) {
            scheduleRetryAfter(context, "WebUI 拒绝目标时数据库暂不可用：${error.message}")
        } catch (error: SecurityException) {
            scheduleRetryAfter(context, "WebUI 拒绝目标时缓存无权访问：${error.message}")
        }
    }

    private fun scheduleRetryAfter(context: EnsureContext, message: String) {
        if (!isCurrent(context)) return
        retryAttempts += 1
        ensure = null
        val retryNumber = retryAttempts
        if (retryNumber > MOBILE_WEB_UI_MAX_RETRIES) {
            onError("WebUI 暂时不可用：$message；保留当前健康版本")
            return
        }
        val backoff = MOBILE_WEB_UI_RETRY_BACKOFF_MILLIS[retryNumber - 1]
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(backoff)
            if (currentServerId == context.serverId) onRetryTrigger(context.serverId)
        }
    }

    private fun scheduleResolveRetry(serverId: String?, message: String) {
        if (serverId == null || serverId != currentServerId) return
        resolveRetryAttempts += 1
        if (resolveRetryAttempts > MOBILE_WEB_UI_MAX_RETRIES) {
            onError("WebUI 发布检查暂时不可用：$message；已停止自动重试")
            return
        }
        val backoff = MOBILE_WEB_UI_RETRY_BACKOFF_MILLIS[resolveRetryAttempts - 1]
        resolveRetryJob?.cancel()
        resolveRetryJob = scope.launch {
            delay(backoff)
            if (currentServerId == serverId) {
                if (resolveInFlight) resolveDirty = true else beginResolve(serverId)
            }
        }
    }

    private fun finishResolve(serverId: String?) {
        if (serverId == null || serverId != currentServerId) return
        resolveInFlight = false
        if (resolveDirty) {
            resolveDirty = false
            beginResolve(serverId)
        }
    }

    private fun mobileWebUiErrorCode(response: MobileWebUiHttpResponse): String? {
        if (response.body.isEmpty()) return null
        return try {
            val payload = MOBILE_WEB_UI_ERROR_JSON.decodeFromString<JsonObject>(
                response.body.toString(Charsets.UTF_8),
            )
            payload["code"]?.jsonPrimitive?.content
                ?: payload["error"]?.let { value ->
                    if (value is JsonObject) value["code"]?.jsonPrimitive?.content else null
                }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun contextServer(pending: PendingHttp): String = pending.context.serverId

    private fun isCurrent(context: EnsureContext): Boolean = ensure === context && context.token == token

    private fun invalidateOwner() {
        token += 1
        pendingCommandId = null
        pendingCommandKind = null
        pendingHttp = null
        ensure = null
        retryJob?.cancel()
        retryJob = null
        retryTargetKey = null
        retryAttempts = 0
        resolveInFlight = false
        resolveDirty = false
        resolveRetryJob?.cancel()
        resolveRetryJob = null
        resolveRetryAttempts = 0
        manualRetryRequested = false
        readyGenerationState.value = null
    }

    private fun compatibilityFingerprint(): String =
        "android-$nativeBuild-$MOBILE_WEB_UI_BRIDGE_PROTOCOL-$MOBILE_WEB_UI_SNAPSHOT_PROTOCOL"

    private fun emitResetEvent(serverId: String) {
        resetSequence += 1
        resetEventState.value = MobileWebUiResetEvent(serverId, resetSequence)
    }

    suspend fun rejectActivation(serverId: String, generationId: String, nonce: String, reason: String): String? {
        val fallback = store.rollbackAttemptAndReject(
            serverId,
            generationId,
            nonce,
            compatibilityFingerprint(),
            reason,
        )
        if (readyGenerationState.value == generationId) readyGenerationState.value = null
        return fallback
    }
}
