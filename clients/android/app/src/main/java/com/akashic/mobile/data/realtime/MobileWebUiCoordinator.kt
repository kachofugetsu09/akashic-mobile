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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** 在不把错误抛入 realtime owner 的前提下，分类已认证的 WebUI HTTP 响应。 */
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
    statusCode in 400..499 -> MobileWebUiHttpAction.RETRY_AFTER
    else -> MobileWebUiHttpAction.RETRY_AFTER
}

/** 在不可信的 WebUI HTTP 错误正文边界只解码字符串错误码。 */
internal fun decodeMobileWebUiHttpErrorCode(body: ByteArray): String? {
    if (body.isEmpty()) return null
    return try {
        val payload = MOBILE_WEB_UI_ERROR_JSON.decodeFromString<JsonObject>(
            body.toString(Charsets.UTF_8),
        )
        jsonStringValue(payload["code"])
            ?: (payload["error"] as? JsonObject)?.let { error ->
                jsonStringValue(error["code"])
            }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun jsonStringValue(value: JsonElement?): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val MOBILE_WEB_UI_MAX_RETRIES = 3
private const val MAX_SUPERSEDED_COMMANDS = 128
private val MOBILE_WEB_UI_RETRY_BACKOFF_MILLIS = longArrayOf(1_000L, 5_000L, 30_000L)
private val MOBILE_WEB_UI_ERROR_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = true
}

/** 解析一个服务端发布，确保不可变 blob 存在，并把激活留给 UI 会话边界。 */
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
    // 新一轮 Resolve 或断线取消的命令仍由本 owner 认领其迟到回复。
    private val supersededCommandKinds = linkedMapOf<String, String>()
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
    private val processReconciledServers = mutableSetOf<String>()
    private var resetRecoveryInFlight = false

    private val readyGenerationState = MutableStateFlow<String?>(null)
    val readyGeneration: StateFlow<String?> = readyGenerationState.asStateFlow()
    private val retryAvailableState = MutableStateFlow(false)
    val retryAvailable: StateFlow<Boolean> = retryAvailableState.asStateFlow()
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
            retryAvailableState.value = false
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

    /** 只在首次 UI 组合前恢复持久化的已提交 WebUI。 */
    suspend fun restoreLocal(serverId: String): Boolean {
        cancelForeignAttemptBeforeRestore(serverId)
        currentServerId = serverId
        val reconcileNow = synchronized(processReconciledServers) {
            processReconciledServers.add(serverId)
        }
        fun rollbackProcessMarker(force: Boolean = false) {
            if (reconcileNow || force) synchronized(processReconciledServers) {
                processReconciledServers.remove(serverId)
            }
        }
        val stateBeforeReconcile = try {
            store.state(serverId)
        } catch (error: IOException) {
            rollbackProcessMarker()
            throw error
        } catch (error: SQLiteException) {
            rollbackProcessMarker()
            throw error
        } catch (error: SecurityException) {
            rollbackProcessMarker()
            throw error
        }
        val processAttempt = store.attempt.value?.takeIf { it.serverId == serverId }
        val ownsDurableAttempt = processAttempt?.let {
            it.generationId == stateBeforeReconcile?.attemptingGenerationId &&
                it.nonce == stateBeforeReconcile.attemptingNonce
        } == true
        val reconcileRequired = reconcileNow ||
            (stateBeforeReconcile?.attemptingGenerationId != null && !ownsDurableAttempt)
        try {
            if (reconcileRequired) store.reconcile(serverId)
        } catch (error: IOException) {
            rollbackProcessMarker(reconcileRequired)
            throw error
        } catch (error: SQLiteException) {
            rollbackProcessMarker(reconcileRequired)
            throw error
        } catch (error: SecurityException) {
            rollbackProcessMarker(reconcileRequired)
            throw error
        }
        val activeAttempt = store.attempt.value?.takeIf { it.serverId == serverId }
        val restored = if (activeAttempt == null) store.restoreCommittedServing(serverId) else true
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

    /** 在把展示交给另一个服务端前，完成前一个服务端由进程持有的候选版本。 */
    private suspend fun cancelForeignAttemptBeforeRestore(serverId: String) {
        val activeAttempt = store.attempt.value ?: return
        if (activeAttempt.serverId == serverId) return
        val rolledBack = store.rollbackAttempt(
            activeAttempt.serverId,
            activeAttempt.generationId,
            activeAttempt.nonce,
        )
        if (rolledBack || store.attempt.value != activeAttempt) return
        // 进程租约已超过持久 marker；先恢复旧服务端，再恢复新服务端，避免稍后返回时继承陈旧 attempting 行。
        store.reconcile(activeAttempt.serverId)
        check(store.attempt.value != activeAttempt) {
            "WebUI server handoff left a stale candidate lease for ${activeAttempt.serverId}"
        }
    }

    fun onDisconnected() {
        token += 1
        abandonPendingCommand()
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
    }

    fun onControlHint(serverId: String) {
        if (serverId != currentServerId) return
        resolve(serverId)
    }

    /** 解析当前发布指针；hint 不能直接应用目标。 */
    fun resolve(serverId: String? = currentServerId) {
        val selectedServer = serverId ?: return
        if (selectedServer != currentServerId) return
        if (store.hasPendingReset(selectedServer)) {
            if (!resetRecoveryInFlight) {
                resetRecoveryInFlight = true
                scope.launch {
                    try {
                        store.reconcilePendingResetIfNeeded(selectedServer)
                        if (!store.hasPendingReset(selectedServer)) resolve(selectedServer)
                    } catch (error: IOException) {
                        onError("WebUI reset 清理尚未完成：${error.message}")
                    } catch (error: SQLiteException) {
                        onError("WebUI reset 数据库尚未完成：${error.message}")
                    } catch (error: SecurityException) {
                        onError("WebUI reset 缓存无权访问：${error.message}")
                    } finally {
                        resetRecoveryInFlight = false
                    }
                }
            }
            return
        }
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
        abandonPendingCommand()
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

    /** 把已认证的 WebSocket 回复路由到唯一的 release/prepare owner。 */
    suspend fun onReply(envelope: WireEnvelope): Boolean {
        val id = envelope.id ?: return false
        val supersededKind = supersededCommandKinds[id]
        if (supersededKind != null) {
            require(isMobileWebUiReplyFor(supersededKind, envelope.type)) {
                "WebUI superseded $supersededKind reply type mismatch: ${envelope.type}"
            }
            supersededCommandKinds.remove(id)
            return true
        }
        if (id != pendingCommandId) {
            if (mobileWebUiReplyKind(envelope.type) != null) {
                throw IllegalArgumentException("WebUI reply id is not owned: $id")
            }
            return false
        }
        val kind = requireNotNull(pendingCommandKind) { "WebUI pending command kind missing" }
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
                            jsonStringValue(envelope.payload["code"]),
                            jsonStringValue(envelope.payload["message"]) ?: "WebUI 发布检查失败",
                        )
                        return true
                    }
                    val view = try {
                        decodeMobileWebUiReleaseView(envelope.payload)
                    } catch (_: SerializationException) {
                        onError("WebUI 发布检查响应无效")
                        return true
                    } catch (_: IllegalArgumentException) {
                        onError("WebUI 发布检查响应无效")
                        return true
                    }
                    try {
                        handleReleaseView(view)
                    } catch (error: IOException) {
                        scheduleResolveRetry(currentServerId, "WebUI 本地缓存暂不可用：${error.message}")
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
                    val context = ensure
                    ensure = null
                    onError("WebUI content.prepare reply type 不匹配")
                    resolve(context?.serverId ?: currentServerId)
                    return true
                }
                val context = ensure ?: return true
                if (envelope.type.endsWith(".error")) {
                    handleCommandError(
                        context,
                        jsonStringValue(envelope.payload["code"]),
                        jsonStringValue(envelope.payload["message"]) ?: "WebUI 内容授权失败",
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

    /** 把 HTTP 结果交给匹配的 manifest/blob 操作。 */
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

    /** 把 owner 存储失败转换为与 HTTP 失败相同的有界重试状态。 */
    fun onOwnerFailure(requestId: String, message: String) {
        val pending = pendingHttp ?: return
        if (pending.requestId != requestId) return
        pendingHttp = null
        if (isCurrent(pending.context)) scheduleRetryAfter(pending.context, message)
    }

    suspend fun state(serverId: String): MobileWebUiStateEntity? = store.state(serverId)

    /** 只在下一个真实 UI 会话边界应用服务端选择的 baseline。 */
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
        retryAvailableState.value = resetTarget != null
    }

    /** 立即撤销远程展示，但保留已验证的缓存文件。 */
    suspend fun revokeServer(serverId: String) {
        if (serverId != currentServerId) return
        invalidateOwner()
        lastReleaseView = null
        readyGenerationState.value = null
        retryAvailableState.value = false
        waitForSpaceState.value = null
        emitResetEvent(serverId)
        store.clearServingToBaseline(serverId)
    }

    /** 只有用户显式重试时，才清除精确的手动 reset 拒绝记录。 */
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
        } catch (error: IOException) {
            onError("WebUI 候选版本校验失败，当前界面未改变：${error.message}")
            return null
        } catch (error: SQLiteException) {
            onError("WebUI 候选版本状态暂不可用，当前界面未改变：${error.message}")
            return null
        } catch (error: SecurityException) {
            onError("WebUI 候选版本缓存无权访问，当前界面未改变：${error.message}")
            return null
        }
        return nonce
    }

    suspend fun commitHealthy(serverId: String, generationId: String, nonce: String): Boolean =
        store.commitHealthyIfCurrent(serverId, generationId, nonce)

    suspend fun isAttemptCurrent(serverId: String, generationId: String, nonce: String): Boolean =
        store.isAttemptCurrent(serverId, generationId, nonce)

    suspend fun rollbackAttempt(serverId: String, generationId: String, nonce: String): Boolean =
        store.rollbackAttempt(serverId, generationId, nonce)

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
        val rollback = store.rollbackServingAndRejectResult(
            serverId,
            generationId,
            compatibilityFingerprint(),
            reason,
        )
        if (rollback.fallbackUnavailable) {
            onError("WebUI 回滚版本不可验证，已回到内置界面；请重新检查服务端界面")
        }
        if (readyGenerationState.value == generationId) readyGenerationState.value = null
        return rollback.generationId
    }

    private suspend fun handleReleaseView(view: MobileWebUiReleaseView) {
        val serverId = currentServerId ?: return
        if (store.hasPendingReset(serverId)) {
            onError("WebUI reset 清理尚未完成，暂不下载新版本")
            return
        }
        validateMobileWebUiReleaseView(view, serverId)
        lastReleaseView = view
        val waiting = waitForSpaceState.value
        val explicitRetry = manualRetryRequested
        if (explicitRetry) {
            val candidates = sequenceOf(view.preview, view.stable).filterNotNull().toList()
            val retryTarget = candidates.firstOrNull {
                store.isPermanentlyRejected(serverId, it, compatibilityFingerprint())
            }
            retryTarget?.let { store.clearReject(serverId, it, compatibilityFingerprint()) }
            manualRetryRequested = false
        }
        retryAvailableState.value = if (explicitRetry) {
            false
        } else {
            sequenceOf(view.preview, view.stable)
                .filterNotNull()
                .firstOrNull {
                    store.isPermanentlyRejected(serverId, it, compatibilityFingerprint())
                } != null
        }
        val target = compatibleTarget(serverId, view.preview) ?: compatibleTarget(serverId, view.stable)
        val activeAttempt = store.attempt.value?.takeIf { it.serverId == serverId }
        if (activeAttempt != null && (target == null || activeAttempt.generationId != target.generationId)) {
            try {
                store.rollbackAttempt(serverId, activeAttempt.generationId, activeAttempt.nonce)
            } catch (error: IOException) {
                onError("WebUI 版本切换暂未完成，当前界面未改变：${error.message}")
                return
            } catch (error: SQLiteException) {
                onError("WebUI 版本切换状态暂不可用，当前界面未改变：${error.message}")
                return
            } catch (error: SecurityException) {
                onError("WebUI 版本切换缓存无权访问，当前界面未改变：${error.message}")
                return
            }
        }
        store.setDesired(serverId, view, target)
        if (target == null) {
            ensure = null
            readyGenerationState.value = null
            waitForSpaceState.value = null
            retryTargetKey = null
            retryAttempts = 0
            return
        }
        if (!explicitRetry && waiting?.targetKey == target.targetKey) {
            ensure = null
            return
        }
        waitForSpaceState.value = null
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
            waitForSpaceState.value = admission.copy(targetKey = context.target.targetKey)
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
            // 1. blobOffset 可能在重启恢复完整 partial；重新读 verified owner，禁止发送 offset==size。
            if (store.hasBlob(context.serverId, file.sha256, file.sizeBytes)) {
                context.fileIndex += 1
                continue
            }
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
                val local = store.collectGarbage(context.serverId)
                val global = store.collectGlobalGarbage()
                val report = MobileWebUiGcReport(
                    removedGenerations = local.removedGenerations + global.removedGenerations,
                    removedBlobs = local.removedBlobs + global.removedBlobs,
                    removedBytes = local.removedBytes + global.removedBytes,
                    blockedGenerations = local.blockedGenerations + global.blockedGenerations,
                    unownedFiles = local.unownedFiles + global.unownedFiles,
                )
                if (report.blockedGenerations > 0 || report.unownedFiles > 0) {
                    onError(
                        "WebUI 缓存部分未清理：${report.blockedGenerations} 个版本、" +
                            "${report.unownedFiles} 个引用仍保留，未假报释放空间",
                    )
                }
            } catch (error: IOException) {
                onError("WebUI 缓存整理失败：${error.message}")
            } catch (error: SQLiteException) {
                onError("WebUI 缓存数据库整理失败：${error.message}")
            } catch (error: SecurityException) {
                onError("WebUI 缓存整理无权访问：${error.message}")
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
            ?: throw IllegalArgumentException("WebUI blob Content-Range 无效")
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
        } catch (_: IOException) {
            scheduleRetryAfter(pending.context, "partial 清理失败")
        }
    }

    private suspend fun rejectCurrentTarget(context: EnsureContext, reason: String) {
        if (!isCurrent(context)) return
        context.manifest?.files?.forEach { file ->
            try {
                store.resetBlobPartial(context.serverId, file.sha256, file.sizeBytes)
            } catch (_: IOException) {
                onError("WebUI partial 清理失败：${file.path}")
            }
        }
        store.markRejected(context.serverId, context.target, compatibilityFingerprint(), reason)
        val release = lastReleaseView
        retryAvailableState.value = release?.let { currentRelease ->
            sequenceOf(currentRelease.preview, currentRelease.stable)
                .filterNotNull()
                .firstOrNull {
                    store.isPermanentlyRejected(context.serverId, it, compatibilityFingerprint())
                } != null
        } ?: false
        val stable = release?.stable
            ?.takeUnless { it.targetKey == context.target.targetKey }
            ?.let { candidate -> compatibleTarget(context.serverId, candidate) }
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
        return decodeMobileWebUiHttpErrorCode(response.body)
    }

    private fun contextServer(pending: PendingHttp): String = pending.context.serverId

    private fun isCurrent(context: EnsureContext): Boolean = ensure === context && context.token == token

    private fun invalidateOwner() {
        token += 1
        abandonPendingCommand()
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
        resetRecoveryInFlight = false
        readyGenerationState.value = null
    }

    private fun abandonPendingCommand() {
        val id = pendingCommandId
        if (id != null) {
            val kind = requireNotNull(pendingCommandKind) {
                "WebUI pending command kind missing while abandoning $id"
            }
            supersededCommandKinds[id] = kind
            while (supersededCommandKinds.size > MAX_SUPERSEDED_COMMANDS) {
                supersededCommandKinds.remove(supersededCommandKinds.entries.first().key)
            }
        }
        pendingCommandId = null
        pendingCommandKind = null
    }

    private fun mobileWebUiReplyKind(type: String): String? = when {
        type == "$MOBILE_WEB_UI_RELEASE_GET.ok" || type == "$MOBILE_WEB_UI_RELEASE_GET.error" ->
            MOBILE_WEB_UI_RELEASE_GET
        type == "$MOBILE_WEB_UI_CONTENT_PREPARE.ok" ||
            type == "$MOBILE_WEB_UI_CONTENT_PREPARE.error" -> MOBILE_WEB_UI_CONTENT_PREPARE
        else -> null
    }

    private fun isMobileWebUiReplyFor(kind: String, type: String): Boolean =
        mobileWebUiReplyKind(type) == kind

    private fun compatibilityFingerprint(): String =
        "android-$nativeBuild-$MOBILE_WEB_UI_BRIDGE_PROTOCOL-$MOBILE_WEB_UI_SNAPSHOT_PROTOCOL"

    private fun emitResetEvent(serverId: String) {
        resetSequence += 1
        resetEventState.value = MobileWebUiResetEvent(serverId, resetSequence)
    }

    suspend fun rejectActivation(serverId: String, generationId: String, nonce: String, reason: String): String? {
        val rollback = store.rollbackAttemptAndRejectResult(
            serverId,
            generationId,
            nonce,
            compatibilityFingerprint(),
            reason,
        )
        if (rollback.fallbackUnavailable) {
            onError("WebUI 回滚版本不可验证，已回到内置界面；请重新检查服务端界面")
        }
        if (readyGenerationState.value == generationId) readyGenerationState.value = null
        return rollback.generationId
    }
}
