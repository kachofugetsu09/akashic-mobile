package com.akashic.mobile.data.realtime

import android.os.Build
import android.net.Uri
import android.database.sqlite.SQLiteException
import android.util.Log
import com.akashic.mobile.data.local.AttachmentDraftStore
import com.akashic.mobile.data.local.AttachmentTransferEntity
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.AppPreferences
import com.akashic.mobile.data.local.ConversationEntity
import com.akashic.mobile.data.local.HistoryProjectionProgress
import com.akashic.mobile.data.local.LocalDeliveryStore
import com.akashic.mobile.data.local.MessageEntity
import com.akashic.mobile.data.local.MediaAttachmentEntity
import com.akashic.mobile.data.local.MediaCacheStore
import com.akashic.mobile.data.local.MessageContentStore
import com.akashic.mobile.data.local.NotificationTargetProjection
import com.akashic.mobile.data.local.OutboxCommandEntity
import com.akashic.mobile.data.local.PendingTurnStopEntity
import com.akashic.mobile.data.local.RealtimeCursorEntity
import com.akashic.mobile.data.local.RemoveUnavailableConversationResult
import com.akashic.mobile.data.local.ServerProfileEntity
import com.akashic.mobile.data.local.isRemoteMissingIn
import com.akashic.mobile.domain.model.ConnectionPhase
import com.akashic.mobile.domain.model.ConnectionState
import com.akashic.mobile.domain.model.EndpointRoute
import com.akashic.mobile.domain.model.ServerEndpoint
import com.akashic.mobile.data.realtime.pluginui.PluginUiAssetStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiCatalogStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiResultStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiCoordinator
import com.akashic.mobile.data.realtime.pluginui.PluginUiHttpRequest
import java.time.Instant
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class MobileSessionState(
    val initialized: Boolean = false,
    val scanGeneration: Long = 0,
    val projectionGeneration: Long = 0,
    val connection: ConnectionState = ConnectionState(),
    val hasProfile: Boolean = false,
    val serverId: String? = null,
    val pairingConfirmationCode: String? = null,
    val currentSessionId: String? = null,
    val remoteSessionIds: Set<String>? = null,
    val activeTurnId: String? = null,
    val activeSessionIds: Set<String> = emptySet(),
    val hasActiveAttachmentDownload: Boolean = false,
    val transferNetwork: TransferNetworkState = TransferNetworkState(TransferNetworkKind.UNAVAILABLE, false),
    val meteredLargeTransferApproved: Boolean = false,
    val isReloadingHistory: Boolean = false,
    val isStopping: Boolean = false,
    val outputCompleted: Boolean = false,
    val commands: List<RemoteCommandItem> = emptyList(),
    val errorMessage: String? = null,
)

internal fun decodeDeviceRevocationReason(payload: JsonObject): String =
    jsonStringValue(payload["reason"]).orEmpty()

internal enum class NotificationTargetOpenResult {
    OPENED,
    WAITING_FOR_SYNC,
    STALE,
}

internal fun notificationTargetOpenResult(
    projection: NotificationTargetProjection,
    connectionPhase: ConnectionPhase,
): NotificationTargetOpenResult = when (projection) {
    NotificationTargetProjection.AVAILABLE -> NotificationTargetOpenResult.OPENED
    NotificationTargetProjection.MISSING -> if (connectionPhase == ConnectionPhase.READY) {
        NotificationTargetOpenResult.STALE
    } else {
        NotificationTargetOpenResult.WAITING_FOR_SYNC
    }
    NotificationTargetProjection.WRONG_SERVER,
    NotificationTargetProjection.WRONG_SESSION -> NotificationTargetOpenResult.STALE
}

internal object FullJitterBackoff {
    fun maximumDelayMillis(retry: Int): Long {
        require(retry >= 0) { "retry must not be negative" }
        val exponent = min(retry, 8)
        return min(30_000L, 500L * (1L shl exponent))
    }

    fun nextDelayMillis(retry: Int, random: Random = Random.Default): Long =
        random.nextLong(maximumDelayMillis(retry) + 1)
}

internal enum class TerminalProtocolAction {
    FAIL_ACTIVE_COMMAND,
    PRESERVE_OUTBOX,
    REVOKE_DEVICE,
}

internal fun terminalProtocolAction(code: Int): TerminalProtocolAction? = when (code) {
    4400 -> TerminalProtocolAction.PRESERVE_OUTBOX
    4406 -> TerminalProtocolAction.PRESERVE_OUTBOX
    4403 -> TerminalProtocolAction.REVOKE_DEVICE
    4410 -> TerminalProtocolAction.FAIL_ACTIVE_COMMAND
    else -> null
}

internal enum class ConnectionDeadlinePhase {
    CHALLENGE,
    AUTHENTICATION,
    SYNC,
}

internal fun ConnectionDeadlinePhase.deadlineMillis(): Long = when (this) {
    ConnectionDeadlinePhase.CHALLENGE,
    ConnectionDeadlinePhase.AUTHENTICATION -> 10_000L
    ConnectionDeadlinePhase.SYNC -> 20_000L
}

internal fun ConnectionDeadlinePhase.timeoutMessage(): String = when (this) {
    ConnectionDeadlinePhase.CHALLENGE -> "等待电脑握手超时，正在重新连接"
    ConnectionDeadlinePhase.AUTHENTICATION -> "设备认证超时，正在重新连接"
    ConnectionDeadlinePhase.SYNC -> "消息同步长时间无进展，正在重新连接"
}

internal data class ConnectionPhaseDeadline(
    val generation: Long,
    val phase: ConnectionDeadlinePhase,
)

internal fun shouldReplacePhaseDeadline(
    current: ConnectionPhaseDeadline?,
    generation: Long,
    next: ConnectionDeadlinePhase,
): Boolean = current == null ||
    current.generation != generation ||
    current.phase.ordinal <= next.ordinal

internal fun shouldApplyCandidateOpen(
    connectionPhase: ConnectionPhase,
    hasActiveCandidate: Boolean,
    candidateGeneration: Long,
    pairingConfirmationGeneration: Long?,
): Boolean = !hasActiveCandidate &&
    pairingConfirmationGeneration != candidateGeneration &&
    (
        connectionPhase == ConnectionPhase.CONNECTING ||
            connectionPhase == ConnectionPhase.SERVER_CHALLENGE ||
            connectionPhase == ConnectionPhase.DEGRADED
    )

internal fun shouldRefreshSyncDeadline(
    phaseBeforeFrame: ConnectionPhase,
    phaseAfterFrame: ConnectionPhase,
): Boolean = phaseBeforeFrame == ConnectionPhase.SYNCING &&
    phaseAfterFrame == ConnectionPhase.SYNCING

internal fun shouldApplyQueuedDeviceRevocation(
    currentEpoch: Long,
    queuedEpoch: Long,
    currentGeneration: Long,
    queuedGeneration: Long,
    ownerGenerationCurrent: Boolean,
): Boolean = currentEpoch == queuedEpoch &&
    (ownerGenerationCurrent || currentGeneration == queuedGeneration)

internal fun historyStartPage(
    remoteMessageCount: Int,
    local: HistoryProjectionProgress,
    forceReload: Boolean,
    pageSize: Int,
): Int? {
    if (remoteMessageCount <= 0) return null
    if (forceReload) return 1
    val contiguous = if (local.messageCount == 0) {
        local.maxServerSeq == null
    } else {
        local.maxServerSeq == local.messageCount.toLong() - 1
    }
    if (!contiguous || local.messageCount > remoteMessageCount) return 1
    if (local.messageCount == remoteMessageCount) return null
    return local.messageCount / pageSize + 1
}

/** 从本地连续进度继续历史请求，并在传输失效后停止当前批次。 */
internal suspend fun requestHistoryBatch(
    sessions: List<RemoteSessionSummary>,
    startPage: suspend (RemoteSessionSummary) -> Int?,
    requestPage: (String, Int) -> Boolean,
) {
    for (session in sessions) {
        if (session.messageCount <= 0) continue
        val page = startPage(session) ?: continue
        if (!requestPage(session.sessionId, page)) return
    }
}

internal class NetworkRecoveryLatch {
    private var unavailableGeneration: Long? = null
    private var recoveredGeneration: Long? = null

    /** 记录网络边沿，并把一次恢复绑定到当时的连接代际。 */
    fun onNetworkState(
        generation: Long,
        previous: TransferNetworkState,
        next: TransferNetworkState,
        hasConnectionTarget: Boolean,
    ) {
        if (!hasConnectionTarget) {
            reset()
            return
        }
        if (next.kind == TransferNetworkKind.UNAVAILABLE) {
            unavailableGeneration = generation
            recoveredGeneration = null
            return
        }
        if (
            previous.kind == TransferNetworkKind.UNAVAILABLE &&
            unavailableGeneration == generation
        ) {
            unavailableGeneration = null
            recoveredGeneration = generation
        }
    }

    fun onGenerationStarted(generation: Long, network: TransferNetworkState) {
        recoveredGeneration = null
        unavailableGeneration = if (network.kind == TransferNetworkKind.UNAVAILABLE) generation else null
    }

    fun consume(generation: Long): Boolean {
        if (recoveredGeneration != generation) return false
        recoveredGeneration = null
        return true
    }

    fun onConnectionProgress(generation: Long) {
        if (recoveredGeneration == generation) recoveredGeneration = null
    }

    fun reset() {
        unavailableGeneration = null
        recoveredGeneration = null
    }
}

class RealtimeSession(
    private val database: AppDatabase,
    private val deliveryStore: LocalDeliveryStore,
    private val attachmentDrafts: AttachmentDraftStore,
    private val mediaCache: MediaCacheStore,
    private val messageContentStore: MessageContentStore,
    private val preferences: AppPreferences,
    private val deviceKeys: DeviceKeyStore,
    private val transferNetwork: StateFlow<TransferNetworkState>,
    pluginUiAssetStore: PluginUiAssetStore,
    pluginUiCatalogStore: PluginUiCatalogStore,
    pluginUiResultStore: PluginUiResultStore,
    mobileWebUiStore: MobileWebUiStore,
    nativeBuild: Int,
    private val scope: CoroutineScope,
    allowInsecureTransport: Boolean,
    private val onRuntimeError: (String, Throwable) -> Unit,
    private val turnTrace: TurnTraceTracker,
) : RealtimeSocketListener {
    private data class PendingSyncCommand(
        val generation: Long,
        val type: String,
        val sessionId: String?,
        val page: Int?,
        val afterSeq: Long?,
    )

    private data class PendingPairing(
        val qr: PairingQrPayload,
        val keyAlias: String,
        val claim: PairClaimMaterial,
    )

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutex = Mutex()
    private lateinit var mobileWebUiOwner: MobileWebUiCoordinator
    private val attachmentOperations = AttachmentOperationOwner()
    private val socket = RealtimeWebSocketClient(this, allowInsecureTransport)
    internal val mobileWebUi = MobileWebUiCoordinator(
        store = mobileWebUiStore,
        scope = scope,
        nativeBuild = nativeBuild,
        sendCommand = ::sendMobileWebUiCommand,
        startHttp = ::startMobileWebUiHttp,
        onError = { message -> mutableState.value = mutableState.value.copy(errorMessage = message) },
        onRetryTrigger = { serverId ->
            scope.launch {
                mutex.withLock {
                    if (profile?.serverId == serverId) mobileWebUiOwner.resolve(serverId)
                }
            }
        },
    )
    init {
        mobileWebUiOwner = mobileWebUi
    }
    private val uploads = AttachmentUploadCoordinator(
        dao = database.attachmentTransfers(),
        sourceFile = attachmentDrafts::fileFor,
        sendCommand = ::sendAttachmentCommand,
        sendBinary = socket::sendBinary,
        onTransportUnavailable = ::scheduleReconnect,
        onUploadFailed = { message ->
            mutableState.value = mutableState.value.copy(errorMessage = message)
        },
        canTransfer = ::canUploadAttachment,
    )
    private val downloads = AttachmentDownloadCoordinator(
        dao = database.mediaAttachments(),
        cache = mediaCache,
        sendCommand = ::sendAttachmentCommand,
        onTransportUnavailable = ::scheduleReconnect,
        onDownloadFailed = { message ->
            mutableState.value = mutableState.value.copy(errorMessage = message)
        },
        onStateChanged = ::publishDownloadState,
        canTransfer = ::canDownloadAttachment,
    )
    private val messageDownloads = MessageContentDownloadCoordinator(
        dao = database.messageContentTransfers(),
        store = messageContentStore,
        sendCommand = ::sendAttachmentCommand,
        startHttpDownload = ::startMessageContentHttpDownload,
        commit = deliveryStore::commitRestoredMessageContent,
        onTransportUnavailable = ::scheduleReconnect,
        onDownloadFailed = { message ->
            Log.e(TAG, message)
            mutableState.value = mutableState.value.copy(errorMessage = message)
        },
    )
    private val stops = TurnStopCoordinator(
        send = ::sendTurnStopCommand,
        onPersist = ::persistTurnStop,
        onRemovePersisted = ::removePersistedTurnStop,
        onAuthoritativeTerminal = { request ->
            deliveryStore.reconcileInterruptedTurn(
                sessionId = request.sessionId,
                turnId = request.turnId,
                updatedAt = System.currentTimeMillis(),
            )
        },
        onTransportUnavailable = ::scheduleReconnect,
        onError = { message ->
            mutableState.value = mutableState.value.copy(errorMessage = message)
        },
        onStateChanged = ::publishTurnState,
    )
    private val mutableState = MutableStateFlow(MobileSessionState())
    val state: StateFlow<MobileSessionState> = mutableState.asStateFlow()
    val pluginUi = PluginUiCoordinator(
        assetStore = pluginUiAssetStore,
        catalogStore = pluginUiCatalogStore,
        resultStore = pluginUiResultStore,
        startHttpQuery = ::startPluginUiHttpQuery,
        send = ::sendPluginUiCommand,
    )
    val runtimeInspection = RuntimeInspectionCoordinator(
        ::sendRuntimeInspectionCommand,
    )
    val modelCatalog = ModelCatalogCoordinator(::sendModelCatalogCommand)
    private val started = AtomicBoolean(false)
    private var meteredLargeTransferApproved = false
    private var profile: ServerProfileEntity? = null
    private var pendingPairing: PendingPairing? = null
    @Volatile
    private var deviceRevoked = false
    @Volatile
    private var pendingRevokedGeneration: Long? = null
    @Volatile
    private var pendingRevocationEpoch: Long? = null
    private val revocationEpoch = AtomicLong(0)
    private var deviceRevokeCleanupStarted = false
    private val revokedGenerations = ConcurrentHashMap.newKeySet<Long>()
    private var pairingConfirmationGeneration: Long? = null
    private val challengedCandidates = mutableSetOf<SocketCandidateId>()
    private val candidateEndpoints = mutableMapOf<SocketCandidateId, ServerEndpoint>()
    private var activeCandidate: SocketCandidateId? = null
    private var activeEpoch: Long? = null
    private var retryCount = 0
    private var reconnectJob: Job? = null
    private val networkRecovery = NetworkRecoveryLatch()
    private var phaseDeadlineJob: Job? = null
    private var phaseDeadline: ConnectionPhaseDeadline? = null
    private var ackJob: Job? = null
    private var pendingAckCount = 0
    private var pendingAckSeq = 0L
    private var activeOutboxCommandId: String? = null
    private var syncGeneration = 0L
    private var completedSyncGeneration = 0L
    private var resetRebuildGeneration: Long? = null
    private val pendingSyncCommands = mutableMapOf<String, PendingSyncCommand>()
    private val requestedHistoryPages = mutableSetOf<Triple<Long, String, Int>>()
    private val requestedHistoryCursors = mutableSetOf<Triple<Long, String, Long>>()
    private var pendingCommandListId: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            transferNetwork.collectLatest(::applyTransferNetwork)
        }
        scope.launch {
            val cacheReady = try {
                attachmentDrafts.reconcile()
                mediaCache.reconcile()
                messageContentStore.reconcile()
                true
            } catch (error: IOException) {
                failStartup("startup_cache_io", "启动缓存检查失败：${error.message}", error)
                false
            } catch (error: SecurityException) {
                failStartup("startup_cache_security", "启动缓存路径不安全：${error.message}", error)
                false
            }
            if (!cacheReady) return@launch
            mutex.withLock {
                val settings = preferences.settings.first()
                profile = settings.currentServerId?.let { database.serverProfiles().get(it) }
                    ?: database.serverProfiles().first()
                val selected = profile?.let {
                    deliveryStore.restoreSelectedSession(it.serverId, settings.currentSessionId)
                }
                if (selected != settings.currentSessionId) preferences.selectSession(selected)
                profile?.let { selectedProfile ->
                    try {
                        mobileWebUi.restoreLocal(selectedProfile.serverId)
                    } catch (error: IOException) {
                        mutableState.value = mutableState.value.copy(
                            errorMessage = "WebUI 本地缓存恢复失败：${error.message}",
                        )
                    } catch (error: SQLiteException) {
                        mutableState.value = mutableState.value.copy(
                            errorMessage = "WebUI 本地状态数据库暂不可用：${error.message}",
                        )
                    } catch (error: SecurityException) {
                        mutableState.value = mutableState.value.copy(
                            errorMessage = "WebUI 本地缓存无权访问：${error.message}",
                        )
                    }
                }
                mutableState.value = mutableState.value.copy(
                    initialized = true,
                    hasProfile = profile != null,
                    serverId = profile?.serverId,
                    currentSessionId = selected,
                )
                profile?.let {
                    preferences.selectServer(it.serverId)
                    database.outbox().resetInFlight(it.serverId)
                    connectProfile(it)
                }
            }
        }
    }

    /** Activity 回到前台时，重新检查当前 WebUI 发布一次。 */
    fun onForeground() {
        scope.launch {
            mutex.withLock {
                val current = profile
                if (current == null) return@withLock
                when (mutableState.value.connection.phase) {
                    ConnectionPhase.AUTHENTICATED,
                    ConnectionPhase.SYNCING,
                    ConnectionPhase.READY,
                    ConnectionPhase.DEGRADED,
                    -> mobileWebUiOwner.resolve(current.serverId)
                    else -> Unit
                }
            }
        }
    }

    suspend fun collectMobileWebUiGarbage(serverId: String): MobileWebUiGcReport =
        mutex.withLock {
            require(profile?.serverId == serverId) { "WebUI GC 服务端 owner 已变化" }
            mobileWebUi.collectGarbage(serverId)
        }

    suspend fun resetMobileWebUi(serverId: String) = mutex.withLock {
        require(profile?.serverId == serverId) { "WebUI reset 服务端 owner 已变化" }
        mobileWebUi.resetServer(serverId)
    }

    suspend fun retryMobileWebUi(serverId: String): Boolean = mutex.withLock {
        require(profile?.serverId == serverId) { "WebUI retry 服务端 owner 已变化" }
        mobileWebUi.retryCurrentUi(serverId)
    }

    fun beginPairing(rawQr: String) {
        scope.launch {
            try {
                mutex.withLock {
                    val qr = PairingQrDecoder.decode(rawQr)
                    check(profile == null) { "Remove the current server before pairing another one" }
                    revocationEpoch.incrementAndGet()
                    deviceRevoked = false
                    pendingRevokedGeneration = null
                    pendingRevocationEpoch = null
                    deviceRevokeCleanupStarted = false
                    val alias = deviceKeys.aliasForServer(qr.serverId)
                    if (!deviceKeys.contains(alias)) deviceKeys.create(alias)
                    val claim = PairingTranscripts.createClaim(
                        qr = qr,
                        devicePublicKeyDer = deviceKeys.publicKeyDer(alias),
                        deviceName = Build.MODEL.take(128).ifBlank { "Android" },
                        capabilities = CAPABILITIES,
                        clientNonce = randomUrlNonce(),
                        signer = { deviceKeys.sign(alias, it) },
                    )
                    pendingPairing = PendingPairing(qr, alias, claim)
                    pairingConfirmationGeneration = null
                    mutableState.value = MobileSessionState(
                        initialized = true,
                        connection = ConnectionState(phase = ConnectionPhase.CONNECTING),
                    )
                    stops.reset()
                    connectQr(qr)
                }
            } catch (error: IllegalArgumentException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(
                        initialized = true,
                        scanGeneration = mutableState.value.scanGeneration + 1,
                        errorMessage = "二维码无效：${error.message}",
                    )
                }
            }
        }
    }

    /** 暂停当前连接并进入扫码页，保留设备密钥、会话和本地消息。 */
    fun restartPairing() {
        scope.launch {
            mutex.withLock {
                reconnectJob?.cancel()
                reconnectJob = null
                cancelPhaseDeadline()
                ackJob?.cancel()
                ackJob = null
                socket.close(reason = "user requested re-pairing")
                uploads.onDisconnected()
                downloads.onDisconnected()
                messageDownloads.onDisconnected()
                pluginUi.onPairingRestarted()
                pendingPairing = null
                pairingConfirmationGeneration = null
                profile = null
                revocationEpoch.incrementAndGet()
                deviceRevoked = false
                pendingRevokedGeneration = null
                pendingRevocationEpoch = null
                deviceRevokeCleanupStarted = false
                resetGenerationState()
                networkRecovery.reset()
                stops.reset()
                preferences.selectServer(null)
                mutableState.value = MobileSessionState(
                    initialized = true,
                    scanGeneration = mutableState.value.scanGeneration + 1,
                    currentSessionId = mutableState.value.currentSessionId,
                )
            }
        }
    }

    /** 保留配对身份，清除服务端投影后重新拉取全部手机会话。 */
    fun reloadFromServer() {
        scope.launch {
            mutex.withLock {
                // 1. 协议错误时连接无法进入 READY；重建入口只保护本地工作与并发传输
                val currentProfile = requireNotNull(profile) { "Pair a server before reloading history" }
                if (deviceRevoked) {
                    mutableState.value = endHistoryReload(
                        mutableState.value,
                        "设备配对已撤销：请重新配对",
                    )
                    return@withLock
                }
                check(!mutableState.value.isReloadingHistory) { "History reload is already running" }
                check(mutableState.value.activeTurnId == null) { "History reload cannot interrupt an active turn" }
                check(!mutableState.value.hasActiveAttachmentDownload) {
                    "History reload cannot interrupt an attachment download"
                }
                check(!messageDownloads.hasActiveDownload()) {
                    "History reload cannot interrupt a message content download"
                }
                val reloadOnCurrentConnection =
                    mutableState.value.connection.phase == ConnectionPhase.READY
                mutableState.value = mutableState.value.copy(
                    connection = if (reloadOnCurrentConnection) {
                        mutableState.value.connection.copy(phase = ConnectionPhase.SYNCING)
                    } else {
                        mutableState.value.connection
                    },
                    isReloadingHistory = true,
                    errorMessage = null,
                )

                // 2. 清理本地投影；即使附件文件清理失败，也继续恢复消息视图
                var cleanupHandled = false
                val cleanupError = try {
                    deliveryStore.clearReloadableCache(
                        serverId = currentProfile.serverId,
                        preservedSessionId = mutableState.value.currentSessionId,
                    )
                    messageContentStore.reconcile()
                    cleanupHandled = true
                    null
                } catch (error: IOException) {
                    cleanupHandled = true
                    "部分附件缓存未清理：${error.message}"
                } catch (error: SecurityException) {
                    cleanupHandled = true
                    "附件缓存路径不安全：${error.message}"
                } catch (error: SQLiteException) {
                    cleanupHandled = true
                    "本地消息缓存清理失败：${error.message}"
                } finally {
                    if (!cleanupHandled) {
                        mutableState.value = mutableState.value.copy(
                            connection = if (reloadOnCurrentConnection) {
                                mutableState.value.connection.copy(phase = ConnectionPhase.READY)
                            } else {
                                mutableState.value.connection
                            },
                            isReloadingHistory = false,
                        )
                    }
                }

                // 3. 健康连接直接重建；错误连接清掉毒投影后从同一 durable cursor 重连重放
                if (reloadOnCurrentConnection) {
                    beginResetRebuild()
                } else {
                    socket.close(reason = "本地消息缓存已清理，重新连接并同步")
                    scheduleReconnect("本地消息缓存已清理，正在重新连接并同步")
                }
                if (cleanupError != null) {
                    mutableState.value = mutableState.value.copy(errorMessage = cleanupError)
                }
            }
        }
    }

    /** 把系统文档复制为可跨重启续传的附件草稿。 */
    fun addAttachments(uris: List<Uri>) {
        scope.launch {
            try {
                importAttachments(uris, targetSessionId = null)
            } catch (error: IllegalArgumentException) {
                publishAttachmentError(error.message)
            } catch (error: java.io.IOException) {
                publishAttachmentError("读取附件失败：${error.message}")
            } catch (error: SecurityException) {
                publishAttachmentError("没有读取附件的权限")
            }
        }
    }

    /** 把分享附件固定导入指定会话，并把真实结果返回给分享队列 owner。 */
    fun addSharedAttachments(
        sessionId: String,
        uris: List<Uri>,
        attachmentIds: List<String>,
        onComplete: (String?) -> Unit,
    ) {
        scope.launch {
            val errorMessage = try {
                importAttachments(
                    uris,
                    targetSessionId = sessionId,
                    attachmentIds = attachmentIds,
                )
                null
            } catch (error: IllegalArgumentException) {
                error.message ?: "共享附件无法加入当前会话"
            } catch (_: java.io.IOException) {
                "读取附件失败，请确认来源仍可访问"
            } catch (error: SecurityException) {
                "没有读取附件的权限"
            } catch (_: android.database.sqlite.SQLiteException) {
                "本地附件记录失败，请重试"
            }
            onComplete(errorMessage)
        }
    }

    /** 解析目标会话、复制附件并恢复既有上传协调器。 */
    private suspend fun importAttachments(
        uris: List<Uri>,
        targetSessionId: String?,
        attachmentIds: List<String>? = null,
    ) {
        // 1. 在连接 owner 内确认目标仍属于当前电脑
        val target = mutex.withLock {
            val currentProfile = requireNotNull(profile) { "Pair a server before attaching" }
            val sessionId = targetSessionId ?: ensureCurrentSession(currentProfile)
            val conversation = requireNotNull(database.conversations().get(sessionId)) {
                "附件对应的会话不存在: $sessionId"
            }
            require(conversation.serverId == currentProfile.serverId) { "附件会话不属于当前电脑" }
            require(!isRemoteMissingSession(currentProfile.serverId, sessionId)) {
                "这段会话已不在电脑上，请新建会话后添加附件"
            }
            currentProfile.serverId to sessionId
        }

        // 2. 复用唯一附件仓库完成私有复制，再唤醒上传队列
        attachmentOperations.perform {
            attachmentDrafts.import(
                serverId = target.first,
                sessionId = target.second,
                uris = uris,
                now = System.currentTimeMillis(),
                attachmentIds = attachmentIds,
            )
        }
        mutex.withLock {
            if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                uploads.resumeIfIdle(target.first)
            }
        }
    }

    private suspend fun publishAttachmentError(message: String?) {
        mutex.withLock {
            mutableState.value = mutableState.value.copy(errorMessage = message)
        }
    }

    fun removeAttachment(attachmentId: String) {
        scope.launch {
            try {
                attachmentOperations.perform { attachmentDrafts.remove(attachmentId) }
            } catch (error: IllegalStateException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = error.message)
                }
            }
        }
    }

    fun retryAttachment(attachmentId: String) {
        scope.launch {
            try {
                attachmentOperations.perform {
                    val transfer = requireNotNull(database.attachmentTransfers().get(attachmentId)) {
                        "Unknown attachment: $attachmentId"
                    }
                    require(!isRemoteMissingSession(transfer.serverId, transfer.sessionId)) {
                        "这段会话已不在电脑上，请新建会话后添加附件"
                    }
                    attachmentDrafts.retry(attachmentId, System.currentTimeMillis())
                }
                mutex.withLock {
                    profile?.serverId?.let { serverId ->
                        if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                            uploads.resumeIfIdle(serverId)
                        }
                    }
                }
            } catch (error: IllegalArgumentException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = error.message)
                }
            }
        }
    }

    /** 用户确认本次运行允许大附件使用计费网络，并恢复原上传队列。 */
    fun continueLargeTransfersOnMeteredNetwork() {
        scope.launch {
            mutex.withLock {
                check(transferNetwork.value.kind == TransferNetworkKind.METERED) {
                    "当前网络不是按流量计费网络"
                }
                meteredLargeTransferApproved = true
                mutableState.value = mutableState.value.copy(meteredLargeTransferApproved = true)
                profile?.serverId?.let { serverId ->
                    if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                        uploads.resumeIfIdle(serverId)
                    }
                }
            }
        }
    }

    /** 原位恢复一条失败用户消息。 */
    fun retryFailedMessage(messageId: String) {
        scope.launch {
            mutex.withLock {
                val message = requireNotNull(database.messages().get(messageId)) {
                    "Unknown failed message: $messageId"
                }
                val currentProfile = requireNotNull(profile) { "Pair a server before retrying" }
                if (isRemoteMissingSession(currentProfile.serverId, message.sessionId)) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "这段会话已不在电脑上，请新建会话后继续",
                    )
                    return@withLock
                }
                val now = System.currentTimeMillis()
                deliveryStore.retryFailedMessage(messageId, Ulid.next(now), now)
                mutableState.value = mutableState.value.copy(errorMessage = null)
                if (mutableState.value.connection.phase == ConnectionPhase.READY) flushOutbox()
            }
        }
    }

    fun retryDownloadedAttachment(attachmentId: String) {
        scope.launch {
            try {
                mutex.withLock {
                    downloads.retry(attachmentId)
                }
            } catch (error: IllegalArgumentException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = error.message)
                }
            }
        }
    }

    fun touchDownloadedAttachment(attachmentId: String) {
        scope.launch {
            check(database.mediaAttachments().touch(attachmentId, System.currentTimeMillis()) == 1) {
                "附件未缓存完成: $attachmentId"
            }
        }
    }

    /** 创建本地消息和 outbox 命令，并在链路可用时立即发送。 */
    fun sendMessage(
        text: String,
        replyToMessageId: String? = null,
        expectedAttachmentIds: List<String> = emptyList(),
        onPersisted: (Boolean) -> Unit = {},
        targetSessionId: String? = null,
        sentDraftRevision: Long? = null,
    ) {
        enqueueMessage(
            text,
            includeDraftAttachments = true,
            includeModelSelection = true,
            replyToMessageId = replyToMessageId,
            targetSessionId = targetSessionId,
            expectedAttachmentIds = expectedAttachmentIds,
            onPersisted = onPersisted,
            sentDraftRevision = sentDraftRevision,
        )
    }

    /** 从系统通知向指定手机会话发送纯文本回复。 */
    fun sendNotificationReply(sessionId: String, text: String) {
        enqueueMessage(
            text,
            includeDraftAttachments = false,
            includeModelSelection = false,
            replyToMessageId = null,
            targetSessionId = sessionId,
        )
    }

    /** 发送不携带或消费附件草稿的纯文本命令。 */
    fun sendCommand(command: String) {
        enqueueMessage(
            command,
            includeDraftAttachments = false,
            includeModelSelection = true,
            replyToMessageId = null,
            targetSessionId = null,
        )
    }

    fun refreshRuntimeInspection() {
        scope.launch {
            mutex.withLock {
                runtimeInspection.refresh()
            }
        }
    }

    fun selectModel(runtimeId: String, reasoningEffort: String) {
        modelCatalog.select(runtimeId, reasoningEffort)
    }

    fun openRuntimeDocument(documentId: String) {
        scope.launch {
            mutex.withLock {
                runtimeInspection.openDocument(documentId)
            }
        }
    }

    fun openRuntimeMcp(ownerId: String, serverName: String) {
        scope.launch {
            mutex.withLock {
                runtimeInspection.openMcp(ownerId, serverName)
            }
        }
    }

    fun openRuntimeJob(jobId: String) {
        scope.launch {
            mutex.withLock {
                runtimeInspection.openJob(jobId)
            }
        }
    }

    fun clearRuntimeInspectionDetail() {
        scope.launch {
            mutex.withLock {
                runtimeInspection.clearDetail()
            }
        }
    }

    /** 从 Web UI 发起一个绑定 owner、槽位、会话和轮次的插件查询。 */
    fun queryPluginUi(
        requestId: String,
        ownerId: String,
        slot: String,
        sessionId: String?,
        turnId: String?,
        pluginId: String,
        method: String,
        payloadJson: String,
        cacheMode: String,
        transportMode: String,
    ) {
        scope.launch {
            mutex.withLock {
                if (sessionId != null && !MOBILE_SESSION.matches(sessionId)) {
                    pluginUi.reject(requestId, "插件请求的会话无效")
                    return@withLock
                }
                val currentProfile = profile
                if (
                    sessionId != null && currentProfile != null &&
                    isRemoteMissingSession(currentProfile.serverId, sessionId)
                ) {
                    pluginUi.reject(requestId, "会话已不在电脑上")
                    return@withLock
                }
                if (turnId != null && turnId.length !in 1..512) {
                    pluginUi.reject(requestId, "插件请求的轮次无效")
                    return@withLock
                }
                pluginUi.query(
                    requestId = requestId,
                    ownerId = ownerId,
                    slot = slot,
                    sessionId = sessionId,
                    turnId = turnId,
                    pluginId = pluginId,
                    method = method,
                    payloadJson = payloadJson,
                    cacheMode = cacheMode,
                    cacheScope = requireNotNull(currentProfile).serverId,
                    transportMode = transportMode,
                )
            }
        }
    }

    /** 从当前已认证候选派生 HTTPS origin，并在 realtime mutex 外读取结果。 */
    private fun startPluginUiHttpQuery(request: PluginUiHttpRequest) {
        val candidate = requireNotNull(activeCandidate) {
            "plugin UI HTTPS query 缺少活动连接"
        }
        val endpoint = requireNotNull(candidateEndpoints[candidate]) {
            "plugin UI HTTPS query 缺少活动端点"
        }
        scope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    socket.executePluginUiHttp(endpoint, request)
                }
            } catch (error: IOException) {
                mutex.withLock {
                    pluginUi.onHttpQueryFailure(
                        request.commandId,
                        "插件 HTTPS 请求失败：${error.message}",
                    )
                }
                return@launch
            } catch (error: SecurityException) {
                mutex.withLock {
                    pluginUi.onHttpQueryFailure(
                        request.commandId,
                        "插件 HTTPS 请求未通过安全校验",
                    )
                }
                return@launch
            }
            mutex.withLock {
                pluginUi.onHttpQueryResponse(request.commandId, response)
            }
        }
    }

    /** 从当前已认证端点下载一个正文 Range，并把结果串回 realtime 状态机。 */
    private fun startMessageContentHttpDownload(request: MessageContentHttpRequest) {
        val candidate = requireNotNull(activeCandidate) {
            "消息正文恢复缺少活动连接"
        }
        val endpoint = requireNotNull(candidateEndpoints[candidate]) {
            "消息正文恢复缺少活动端点"
        }
        scope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    socket.executeMessageContentHttp(endpoint, request)
                }
            } catch (error: IOException) {
                mutex.withLock {
                    messageDownloads.onHttpFailure(
                        request.commandId,
                        "消息正文 HTTPS 请求失败：${error.message}",
                    )
                }
                return@launch
            } catch (error: SecurityException) {
                mutex.withLock {
                    messageDownloads.failActive(
                        "消息正文 HTTPS 请求未通过安全校验",
                        discard = false,
                    )
                }
                return@launch
            }
            mutex.withLock {
                try {
                    messageDownloads.onHttpResponse(request.commandId, response)
                } catch (error: IllegalArgumentException) {
                    messageDownloads.failActive(
                        "消息正文 HTTP 协议校验失败：${error.message}",
                        discard = true,
                    )
                } catch (error: IllegalStateException) {
                    messageDownloads.failActive(
                        "消息正文恢复状态失败：${error.message}",
                        discard = true,
                    )
                } catch (error: IOException) {
                    messageDownloads.failActive(
                        "消息正文缓存读取失败：${error.message}",
                        discard = false,
                    )
                } catch (error: SQLiteException) {
                    messageDownloads.failActive(
                        "消息正文恢复数据库失败：${error.message}",
                        discard = false,
                    )
                } catch (error: ArithmeticException) {
                    messageDownloads.failActive("消息正文恢复长度溢出", discard = true)
                } catch (error: SecurityException) {
                    messageDownloads.failActive("消息正文恢复路径不安全", discard = true)
                }
            }
        }
    }

    /** 从当前已认证端点下载 WebUI manifest/blob，并把结果交回唯一 OTA owner。 */
    private fun startMobileWebUiHttp(request: MobileWebUiHttpRequest) {
        val candidate = requireNotNull(activeCandidate) { "WebUI HTTPS 请求缺少活动连接" }
        val endpoint = requireNotNull(candidateEndpoints[candidate]) { "WebUI HTTPS 请求缺少活动端点" }
        scope.launch {
            val response = try {
                withContext(Dispatchers.IO) {
                    socket.executeMobileWebUiHttp(endpoint, request)
                }
            } catch (error: IOException) {
                mutex.withLock {
                    mobileWebUi.onHttpFailure(request.requestId, "WebUI HTTPS 请求失败：${error.message}")
                }
                return@launch
            } catch (error: SecurityException) {
                mutex.withLock {
                    mobileWebUi.onHttpFailure(request.requestId, "WebUI HTTPS 请求未通过安全校验")
                }
                return@launch
            }
            mutex.withLock {
                mobileWebUi.onHttpResponse(request.requestId, response)
            }
        }
    }

    fun cancelPluginUiOwner(ownerId: String) {
        scope.launch {
            mutex.withLock {
                pluginUi.cancelOwner(ownerId)
            }
        }
    }

    @Suppress("SuspiciousIndentation") // Android lint 误把带标签的局部 return 识别为续行
    private fun enqueueMessage(
        text: String,
        includeDraftAttachments: Boolean,
        includeModelSelection: Boolean,
        replyToMessageId: String?,
        targetSessionId: String?,
        expectedAttachmentIds: List<String> = emptyList(),
        onPersisted: (Boolean) -> Unit = {},
        sentDraftRevision: Long? = null,
    ) {
        // 0. 发送入口即捕获 origin，cmid 生成后绑定；深层校验失败不产生观测
        val sendOrigin = turnTrace.captureSendOrigin()
        scope.launch {
            withSendResult(onPersisted) { reportResult ->
                attachmentOperations.perform {
                    mutex.withLock {
                        // 1. 确定当前手机会话
                        val currentProfile = profile ?: run {
                            mutableState.value = mutableState.value.copy(errorMessage = "请先连接电脑")
                            reportResult(false)
                            return@withLock
                        }
                        val body = text.trim()
                        val sessionId = targetSessionId ?: ensureCurrentSession(currentProfile)
                        require(MOBILE_SESSION.matches(sessionId)) { "Invalid mobile session_id" }
                        require(sentDraftRevision == null || sentDraftRevision in 1..9_007_199_254_740_991) {
                            "会话草稿 revision 无效"
                        }
                        if (targetSessionId != null) {
                            val conversation = requireNotNull(database.conversations().get(sessionId)) {
                                "Unknown notification session: $sessionId"
                            }
                            require(conversation.serverId == currentProfile.serverId) {
                                "Notification session belongs to another server"
                            }
                        }
                        if (isRemoteMissingSession(currentProfile.serverId, sessionId)) {
                            mutableState.value = mutableState.value.copy(
                                errorMessage = "这段会话已不在电脑上，请新建会话后继续",
                            )
                            reportResult(false)
                            return@withLock
                        }
                        val attachments = if (includeDraftAttachments) {
                            database.attachmentTransfers().drafts(currentProfile.serverId, sessionId)
                        } else {
                            emptyList()
                        }
                        if (includeDraftAttachments && !attachmentDraftMatchesExpected(
                                attachments.map { it.attachmentId },
                                expectedAttachmentIds,
                            )
                        ) {
                            mutableState.value = mutableState.value.copy(errorMessage = "附件草稿已变化，请确认后重试")
                            reportResult(false)
                            return@withLock
                        }
                        if (body.isEmpty() && attachments.isEmpty()) {
                            mutableState.value = mutableState.value.copy(errorMessage = "消息和附件不能同时为空")
                            reportResult(false)
                            return@withLock
                        }
                        if (attachments.any { it.state != "ready" }) {
                            mutableState.value = mutableState.value.copy(errorMessage = "请等待附件上传完成")
                            reportResult(false)
                            return@withLock
                        }
                        val replyTarget = replyToMessageId?.let { messageId ->
                            val target = database.messages().get(messageId)
                            if (target == null) {
                                mutableState.value = mutableState.value.copy(errorMessage = "被引用的消息已不可用")
                                reportResult(false)
                                return@withLock
                            }
                            if (target.sessionId != sessionId || target.role !in setOf("user", "assistant")) {
                                mutableState.value = mutableState.value.copy(errorMessage = "被引用的消息已不可用")
                                reportResult(false)
                                return@withLock
                            }
                            target
                        }

                        // 2. 持久化消息和可重放命令
                        val now = System.currentTimeMillis()
                        val commandId = Ulid.next(now)
                        val clientMessageId = commandId
                        turnTrace.recordSend(sessionId, clientMessageId, sendOrigin)
                        val modelSelection = if (includeModelSelection) {
                            modelCatalog.selectionFor(sessionId)
                        } else {
                            null
                        }
                        val payload = MessageSendPayload(
                            clientMessageId = clientMessageId,
                            sessionId = sessionId,
                            text = body,
                            mediaRefs = attachments.map { it.attachmentId },
                            clientCreatedAt = Instant.ofEpochMilli(now).toString(),
                            replyTo = replyTarget?.let { target ->
                                messageReplyReference(target.messageId, target.clientMessageId)
                            },
                            modelRuntimeId = modelSelection?.runtimeId,
                            modelReasoningEffort = modelSelection?.reasoningEffort,
                        )
                        val cachedAttachments = try {
                            attachments.map { transfer ->
                                mediaCache.importOutbound(
                                    transfer,
                                    attachmentDrafts.fileFor(transfer.attachmentId),
                                    now,
                                )
                            }
                        } catch (error: IOException) {
                            mutableState.value = mutableState.value.copy(
                                errorMessage = "保存已发送附件失败：${error.message}",
                            )
                            reportResult(false)
                            return@withLock
                        } catch (error: SecurityException) {
                            mutableState.value = mutableState.value.copy(errorMessage = "附件缓存路径不安全")
                            reportResult(false)
                            return@withLock
                        } catch (error: IllegalArgumentException) {
                            mutableState.value = mutableState.value.copy(errorMessage = error.message)
                            reportResult(false)
                            return@withLock
                        } catch (error: IllegalStateException) {
                            mutableState.value = mutableState.value.copy(errorMessage = error.message)
                            reportResult(false)
                            return@withLock
                        } catch (error: ArithmeticException) {
                            mutableState.value = mutableState.value.copy(errorMessage = "附件缓存配额计算溢出")
                            reportResult(false)
                            return@withLock
                        }
                        val envelope = WireEnvelope(
                            v = WIRE_PROTOCOL_VERSION,
                            kind = WireKind.COMMAND,
                            type = "message.send",
                            id = commandId,
                            connectionEpoch = 1,
                            sessionId = sessionId,
                            payload = ProtocolCodec.json().encodeToJsonElement(MessageSendPayload.serializer(), payload).jsonObject,
                        )
                        deliveryStore.enqueueMessage(
                            conversation = conversationForMessage(
                                currentProfile,
                                sessionId,
                                body.ifBlank { attachments.joinToString("、") { it.filename } },
                                now,
                            ),
                            message = MessageEntity(
                                // TODO(deprecated): optimistic 本地临时 ID 命名空间，canonical 身份由服务端 history 投影原子迁移
                                messageId = "user:$clientMessageId",
                                clientMessageId = clientMessageId,
                                sessionId = sessionId,
                                role = "user",
                                text = body.ifBlank {
                                    attachments.joinToString("、", prefix = "附件：") { it.filename }
                                },
                                deliveryState = "pending",
                                createdAt = now,
                                updatedAt = now,
                                replyToMessageId = replyTarget?.messageId,
                                replyRole = replyTarget?.role,
                                replyPreview = replyTarget?.let { target ->
                                    target.text.trim().replace(Regex("\\s+"), " ").take(512)
                                        .ifBlank { "[无文字消息]" }
                                },
                            ),
                            command = OutboxCommandEntity(
                                commandId = commandId,
                                serverId = currentProfile.serverId,
                                envelopeJson = ProtocolCodec.encode(envelope),
                                state = "pending",
                                attemptCount = 0,
                                createdAt = now,
                                lastAttemptAt = null,
                            ),
                            attachments = cachedAttachments,
                            sentDraftRevision = sentDraftRevision,
                        )
                        turnTrace.onLocalOutboxCommitted(sessionId, clientMessageId)
                        reportResult(true)
                        if (mutableState.value.connection.phase == ConnectionPhase.READY) flushOutbox()
                    }
                }
            }
        }
    }

    /** 创建并选中一个独立的手机会话。 */
    fun createSession() {
        scope.launch {
            mutex.withLock {
                // 1. 创建本地会话
                val currentProfile = requireNotNull(profile) { "Pair a server before creating a session" }
                val sessionId = createLocalSession(currentProfile)

                // 2. 切换当前会话
                preferences.selectSession(sessionId)
                mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
                publishTurnState()
            }
        }
    }

    /** 删除电脑端已不存在的本机会话副本，并选择下一段可用会话。 */
    fun removeUnavailableSession(sessionId: String) {
        scope.launch {
            mutex.withLock {
                // 1. 重新核对服务端目录，避免删除刚恢复的会话
                require(MOBILE_SESSION.matches(sessionId)) { "Invalid mobile session_id" }
                val currentProfile = requireNotNull(profile) { "Pair a server before removing a session" }
                val remoteSessionIds = mutableState.value.remoteSessionIds
                if (remoteSessionIds == null || sessionId in remoteSessionIds) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "会话状态已经更新，请重新打开会话列表",
                    )
                    return@withLock
                }
                if (sessionId in stops.activeSessionIds()) {
                    mutableState.value = mutableState.value.copy(errorMessage = "会话仍在运行，暂时不能移除")
                    return@withLock
                }

                // 2. 删除无待发送工作的本地投影
                val result = try {
                    deliveryStore.removeUnavailableConversation(currentProfile.serverId, sessionId)
                } catch (error: IOException) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "清理会话缓存失败：${error.message}",
                    )
                    return@withLock
                } catch (error: SecurityException) {
                    mutableState.value = mutableState.value.copy(errorMessage = "会话缓存路径不安全")
                    return@withLock
                }
                if (result != RemoveUnavailableConversationResult.REMOVED) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = when (result) {
                            RemoveUnavailableConversationResult.HAS_LOCAL_WORK ->
                                "会话还有未发送的消息或附件，暂时不能移除"
                            RemoveUnavailableConversationResult.NOT_REMOTE ->
                                "这是本机新建的会话，不需要清理"
                            RemoveUnavailableConversationResult.REMOVED -> error("unreachable")
                        },
                    )
                    return@withLock
                }

                // 3. 当前会话被移除时，切到最近可用会话；没有则创建新会话
                if (mutableState.value.currentSessionId == sessionId) {
                    val nextSessionId = database.conversations()
                        .observeSummaries(currentProfile.serverId)
                        .first()
                        .firstOrNull { !it.isRemoteMissingIn(remoteSessionIds) }
                        ?.sessionId
                        ?: createLocalSession(currentProfile)
                    preferences.selectSession(nextSessionId)
                    mutableState.value = mutableState.value.copy(currentSessionId = nextSessionId)
                    publishTurnState()
                }
            }
        }
    }

    fun stopCurrentTurn() {
        scope.launch {
            mutex.withLock {
                val sessionId = mutableState.value.currentSessionId
                if (sessionId == null || stops.activeTurnId(sessionId) == null) {
                    mutableState.value = mutableState.value.copy(
                        errorMessage = "当前会话没有正在生成的内容",
                    )
                    return@withLock
                }
                stops.requestStop(sessionId)
            }
        }
    }

    fun dismissError() {
        scope.launch {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(errorMessage = null)
            }
        }
    }

    /** 核对持久投影后选择通知会话，并区分待同步与过期目标。 */
    internal suspend fun openNotificationTarget(
        sessionId: String?,
        messageId: String?,
    ): NotificationTargetOpenResult = mutex.withLock {
        // 1. Intent 是外部边界；非法身份直接作为过期通知显式失败
        if (
            sessionId == null || !MOBILE_SESSION.matches(sessionId) ||
            (messageId != null && messageId.length !in 1..512)
        ) {
            mutableState.value = mutableState.value.copy(errorMessage = STALE_NOTIFICATION_MESSAGE)
            return@withLock NotificationTargetOpenResult.STALE
        }
        val currentProfile = profile ?: return@withLock NotificationTargetOpenResult.WAITING_FOR_SYNC

        // 2. 本地命中无需等待网络；缺失投影只有在完整同步后才能判定过期
        val projection = deliveryStore.notificationTargetProjection(
            currentProfile.serverId,
            sessionId,
            messageId,
        )
        val result = notificationTargetOpenResult(projection, mutableState.value.connection.phase)
        when (result) {
            NotificationTargetOpenResult.OPENED -> selectKnownSession(currentProfile, sessionId)
            NotificationTargetOpenResult.STALE -> {
                mutableState.value = mutableState.value.copy(errorMessage = STALE_NOTIFICATION_MESSAGE)
            }
            NotificationTargetOpenResult.WAITING_FOR_SYNC -> Unit
        }
        result
    }

    /** 选择属于当前电脑的现有手机会话。 */
    fun selectSession(sessionId: String) {
        scope.launch {
            mutex.withLock {
                // 1. 校验会话归属
                require(MOBILE_SESSION.matches(sessionId)) { "Invalid mobile session_id" }
                val currentProfile = requireNotNull(profile) { "Pair a server before selecting a session" }
                val conversation = requireNotNull(database.conversations().get(sessionId)) {
                    "Unknown mobile session: $sessionId"
                }
                require(conversation.serverId == currentProfile.serverId) { "Mobile session belongs to another server" }
                selectKnownSession(currentProfile, sessionId)
            }
        }
    }

    /** 持久化已验证会话的选择，并维持进入会话时的阅读恢复语义。 */
    private suspend fun selectKnownSession(currentProfile: ServerProfileEntity, sessionId: String) {
        // 1. 主动进入另一会话时从最新消息开始
        if (mutableState.value.currentSessionId != sessionId) {
            deliveryStore.clearReadingPosition(
                sessionId = sessionId,
                expectedServerId = currentProfile.serverId,
                updatedAt = System.currentTimeMillis(),
            )
        }

        // 2. 同步持久选择与进程内投影
        preferences.selectSession(sessionId)
        mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
        if (mutableState.value.connection.phase == ConnectionPhase.READY) {
            modelCatalog.onSessionSelected(sessionId)
        }
        publishTurnState()
    }

    override fun onOpen(candidateId: SocketCandidateId, endpoint: ServerEndpoint) {
        scope.launch {
            mutex.withLock {
                if (candidateId.generation != currentGeneration()) return@withLock
                if (deviceRevoked || candidateId.generation in revokedGenerations) {
                    socket.reject(candidateId, 4403, "device revoked")
                    return@withLock
                }
                candidateEndpoints[candidateId] = endpoint
                if (
                    !shouldApplyCandidateOpen(
                        connectionPhase = mutableState.value.connection.phase,
                        hasActiveCandidate = activeCandidate != null,
                        candidateGeneration = candidateId.generation,
                        pairingConfirmationGeneration = pairingConfirmationGeneration,
                    )
                ) {
                    return@withLock
                }
                networkRecovery.onConnectionProgress(candidateId.generation)
                armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.CHALLENGE)
                mutableState.value = mutableState.value.copy(
                    connection = mutableState.value.connection.copy(
                        phase = ConnectionPhase.SERVER_CHALLENGE,
                        endpoint = endpoint,
                    ),
                    errorMessage = null,
                )
            }
        }
    }

    override fun onEnvelope(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (
            candidateId.generation != currentGeneration() ||
            candidateId.generation in revokedGenerations ||
            !socket.isGenerationCurrent(candidateId.generation)
        ) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (
                    candidateId.generation != currentGeneration() ||
                    candidateId.generation in revokedGenerations ||
                    deviceRevoked ||
                    !socket.isGenerationCurrent(candidateId.generation)
                ) return@withLock
                try {
                    handleEnvelope(candidateId, envelope)
                } catch (error: IllegalArgumentException) {
                    failCandidateProtocol(candidateId, envelope, error, "连接协议校验失败：${error.message}")
                } catch (error: ArithmeticException) {
                    failCandidateProtocol(candidateId, envelope, error, "连接协议数值溢出")
                } catch (error: SQLiteException) {
                    failCandidateProtocol(candidateId, envelope, error, "本地数据库处理失败：${error.message}")
                } catch (error: IOException) {
                    failCandidateProtocol(candidateId, envelope, error, "本地文件处理失败：${error.message}")
                } catch (error: SecurityException) {
                    failCandidateProtocol(candidateId, envelope, error, "本地缓存路径不安全：${error.message}")
                }
            }
        }
    }

    override fun onBinary(candidateId: SocketCandidateId, chunk: AttachmentChunkCodec.DecodedChunk) {
        if (
            candidateId.generation != currentGeneration() ||
            candidateId.generation in revokedGenerations ||
            !socket.isGenerationCurrent(candidateId.generation)
        ) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (
                    candidateId.generation != currentGeneration() ||
                    candidateId.generation in revokedGenerations ||
                    deviceRevoked ||
                    !socket.isGenerationCurrent(candidateId.generation)
                ) return@withLock
                if (candidateId != activeCandidate) {
                    socket.reject(candidateId, 4406, "binary frame before authentication")
                    return@withLock
                }
                try {
                    downloads.onBinary(chunk)
                } catch (error: IllegalArgumentException) {
                    failDownloadConnection("附件下载协议校验失败：${error.message}")
                } catch (error: IOException) {
                    failDownloadConnection("附件缓存写入失败：${error.message}")
                } catch (error: SQLiteException) {
                    failDownloadConnection("附件下载数据库失败：${error.message}")
                } catch (error: ArithmeticException) {
                    failDownloadConnection("附件下载长度溢出")
                } catch (error: SecurityException) {
                    failDownloadConnection("附件缓存路径不安全")
                }
            }
        }
    }

    override fun onClosed(candidateId: SocketCandidateId, code: Int, reason: String) {
        val action = terminalProtocolAction(code)
        if (action == TerminalProtocolAction.REVOKE_DEVICE) {
            if (
                candidateId.generation != currentGeneration() &&
                candidateId.generation != pendingRevokedGeneration
            ) return
            markDeviceRevokedImmediately(candidateId.generation)?.let { epoch ->
                enqueueDeviceRevocation(candidateId.generation, epoch, reason)
            }
            return
        }
        if (candidateId.generation != currentGeneration()) return
        scope.launch {
            mutex.withLock {
                if (candidateId == activeCandidate && action != null) {
                    failProtocolConnection(code, action)
                } else if (candidateId == activeCandidate) {
                    scheduleReconnect("连接关闭：$code $reason")
                }
            }
        }
    }

    override fun onRevoked(candidateId: SocketCandidateId, code: Int, reason: String) {
        markDeviceRevokedImmediately(candidateId.generation)?.let { epoch ->
            enqueueDeviceRevocation(candidateId.generation, epoch, reason)
        }
    }

    override fun onFailure(candidateId: SocketCandidateId, error: Throwable) {
        Log.e(TAG, "实时连接候选失败: candidate=$candidateId", error)
        scope.launch {
            mutex.withLock {
                if (deviceRevoked) return@withLock
                if (candidateId == activeCandidate) scheduleReconnect(error.message ?: "连接失败")
            }
        }
    }

    override fun onProtocolFailure(candidateId: SocketCandidateId, error: IllegalArgumentException) {
        scope.launch {
            mutex.withLock {
                onRuntimeError(
                    "operation=decode candidate_generation=${candidateId.generation} " +
                        "candidate_ordinal=${candidateId.ordinal}",
                    error,
                )
                mutableState.value = mutableState.value.copy(errorMessage = error.message)
            }
        }
    }

    override fun onRaceExhausted(generation: Long, error: Throwable) {
        Log.e(TAG, "实时连接候选全部失败: generation=$generation", error)
        scope.launch {
            mutex.withLock {
                if (generation == currentGeneration()) scheduleReconnect(error.message ?: "所有 endpoint 均不可用")
            }
        }
    }

    private suspend fun handleEnvelope(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (candidateId.generation != currentGeneration()) return
        if (candidateId.generation in revokedGenerations || deviceRevoked) return
        if (!socket.isGenerationCurrent(candidateId.generation)) return
        if (activeCandidate != null && candidateId != activeCandidate) return
        when (envelope.type) {
            "server.challenge" -> handleChallenge(candidateId, envelope)
            "pair.pending" -> handlePairPending(candidateId, envelope)
            "pair.accepted" -> handlePairAccepted(candidateId, envelope)
            "auth.accepted" -> handleAuthAccepted(candidateId, envelope)
            "protocol.error" -> {
                val error = ProtocolCodec.decodePayload<ProtocolErrorPayload>(envelope.payload)
                mutableState.value = mutableState.value.copy(errorMessage = error.message)
            }
            else -> handleAuthenticatedFrame(candidateId, envelope)
        }
    }

    private fun handleChallenge(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        require(envelope.kind == WireKind.CONTROL) { "server.challenge must be a control frame" }
        val challenge = ProtocolCodec.decodePayload<ServerChallengePayload>(envelope.payload)
        val pairing = pendingPairing
        val expectedServerId = pairing?.qr?.serverId ?: requireNotNull(profile).serverId
        val expectedFingerprint = pairing?.qr?.serverApplicationKeyFingerprint
            ?: requireNotNull(profile).applicationKeyFingerprint
        PairingTranscripts.verifyServerChallenge(challenge, expectedServerId, expectedFingerprint)
        challengedCandidates += candidateId
        if (pairing != null) {
            check(socket.send(candidateId, control("pair.claim", pairing.claim.payload)))
            armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.AUTHENTICATION)
            return
        }
        val currentProfile = requireNotNull(profile)
        val proof = PairingTranscripts.createDeviceProof(
            challenge = challenge,
            deviceId = currentProfile.deviceId,
            clientNonce = randomUrlNonce(),
            signer = { deviceKeys.sign(currentProfile.keyAlias, it) },
        )
        check(socket.send(candidateId, control("device.proof", proof)))
        armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.AUTHENTICATION)
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.DEVICE_PROOF),
        )
    }

    private fun handlePairPending(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        require(candidateId in challengedCandidates) { "pair.pending arrived before a verified challenge" }
        val pairing = requireNotNull(pendingPairing)
        val pending = ProtocolCodec.decodePayload<PairPendingPayload>(envelope.payload)
        require(pending.pairingId == pairing.qr.pairingId) { "pair.pending pairing_id mismatch" }
        require(pending.confirmationCode == pairing.claim.confirmationCode) { "Pairing confirmation code mismatch" }
        pairingConfirmationGeneration = candidateId.generation
        cancelPhaseDeadline()
        mutableState.value = mutableState.value.copy(pairingConfirmationCode = pending.confirmationCode)
    }

    private suspend fun handlePairAccepted(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        require(candidateId in challengedCandidates) { "pair.accepted arrived before a verified challenge" }
        val pairing = requireNotNull(pendingPairing)
        val accepted = ProtocolCodec.decodePayload<PairAcceptedPayload>(envelope.payload)
        require(accepted.pairingId == pairing.qr.pairingId) { "pair.accepted pairing_id mismatch" }
        val now = System.currentTimeMillis()
        val saved = ServerProfileEntity(
            serverId = pairing.qr.serverId,
            displayName = pairing.qr.serverId,
            deviceId = accepted.deviceId,
            keyAlias = pairing.keyAlias,
            applicationKeyFingerprint = pairing.qr.serverApplicationKeyFingerprint,
            lanEndpointsJson = json.encodeToString(pairing.qr.lanEndpoints),
            tunnelEndpointsJson = json.encodeToString(pairing.qr.tunnelEndpoints),
            tlsSpkiPinsJson = json.encodeToString(pairing.qr.tlsSpkiPins),
            createdAt = now,
        )
        deliveryStore.savePairedProfile(
            saved,
            RealtimeCursorEntity(accepted.deviceId, saved.serverId, 0, 0, now),
        )
        val previousSessionId = preferences.settings.first().currentSessionId
        val selectedSessionId = deliveryStore.restoreSelectedSession(
            saved.serverId,
            previousSessionId,
        )
        preferences.selectServer(saved.serverId)
        preferences.selectSession(selectedSessionId)
        profile = saved
        pendingPairing = null
        pairingConfirmationGeneration = null
        mutableState.value = MobileSessionState(
            initialized = true,
            connection = ConnectionState(phase = ConnectionPhase.CONNECTING),
            hasProfile = true,
            serverId = saved.serverId,
            currentSessionId = selectedSessionId,
        )
        socket.close(reason = "pairing accepted; reconnecting with device proof")
        connectProfile(saved)
    }

    private suspend fun handleAuthAccepted(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (
            candidateId.generation != currentGeneration() ||
            candidateId.generation in revokedGenerations ||
            deviceRevoked ||
            !socket.isGenerationCurrent(candidateId.generation)
        ) return
        require(candidateId in challengedCandidates) { "auth.accepted arrived before a verified challenge" }
        require(envelope.kind == WireKind.CONTROL) { "auth.accepted must be a control frame" }
        val accepted = ProtocolCodec.decodePayload<AuthAcceptedPayload>(envelope.payload)
        require(accepted.connectionEpoch == envelope.connectionEpoch) { "auth.accepted epoch mismatch" }
        val currentProfile = requireNotNull(profile)
        require(accepted.deviceId == currentProfile.deviceId) { "auth.accepted device mismatch" }
        if (!socket.promote(candidateId)) return
        activeCandidate = candidateId
        activeEpoch = accepted.connectionEpoch
        syncGeneration += 1
        completedSyncGeneration = 0
        resetRebuildGeneration = null
        pendingSyncCommands.clear()
        requestedHistoryPages.clear()
        requestedHistoryCursors.clear()
        retryCount = 0
        restoreTurnStops(currentProfile.serverId)
        val cursor = requireNotNull(database.realtimeCursors().get(currentProfile.deviceId))
        check(
            socket.send(
                candidateId,
                WireEnvelope(
                    v = WIRE_PROTOCOL_VERSION,
                    kind = WireKind.CONTROL,
                    type = "resume",
                    connectionEpoch = accepted.connectionEpoch,
                    payload = buildJsonObject {
                        put("last_ack", cursor.lastAcknowledgedEventSeq)
                        put(
                            "active_turns",
                            kotlinx.serialization.json.JsonArray(
                                stops.activeTurnIds().map(::JsonPrimitive),
                            ),
                        )
                    },
                ),
            ),
        )
        mutableState.value = mutableState.value.copy(
            connection = ConnectionState(
                phase = ConnectionPhase.SYNCING,
                endpoint = candidateEndpoints[candidateId],
                connectionEpoch = accepted.connectionEpoch,
            ),
            remoteSessionIds = null,
            errorMessage = null,
        )
        armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.SYNC)
        database.outbox().resetInFlight(currentProfile.serverId)
        mobileWebUi.onAuthenticated(currentProfile.serverId)
    }

    private suspend fun handleAuthenticatedFrame(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (candidateId != activeCandidate) return
        require(envelope.connectionEpoch == activeEpoch) { "Authenticated frame epoch mismatch" }
        val phaseBeforeFrame = mutableState.value.connection.phase
        applyAuthenticatedFrame(envelope)
        if (
            shouldRefreshSyncDeadline(
                phaseBeforeFrame = phaseBeforeFrame,
                phaseAfterFrame = mutableState.value.connection.phase,
            )
        ) {
            refreshSyncDeadline()
        }
        networkRecovery.onConnectionProgress(candidateId.generation)
    }

    /** 应用一个已通过候选与 epoch 校验的服务端帧。 */
    private suspend fun applyAuthenticatedFrame(envelope: WireEnvelope) {
        when (envelope.kind) {
            WireKind.EVENT -> {
                val currentProfile = requireNotNull(profile)
                recordEnvelopeTrace(envelope)
                val eventSeq = deliveryStore.applyEvent(
                    serverId = currentProfile.serverId,
                    deviceId = currentProfile.deviceId,
                    envelope = envelope,
                    updatedAt = System.currentTimeMillis(),
                    preservedSessionId = mutableState.value.currentSessionId,
                )
                recordRoomCommitTrace(envelope)
                recordAck(eventSeq)
                when (envelope.type) {
                    "turn.started" -> {
                        rememberRemoteSession(requireNotNull(envelope.sessionId))
                        stops.onTurnStarted(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                    }
                    "turn.interrupted" -> {
                        stops.onTurnTerminal(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                        turnTrace.onTurnCleared(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                    }
                    "turn.output.completed" -> {
                        stops.onOutputCompleted(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                    }
                    "message.final" -> {
                        rememberRemoteSession(requireNotNull(envelope.sessionId))
                        stops.onTurnTerminal(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                        turnTrace.onTurnCleared(
                            requireNotNull(envelope.sessionId),
                            requireNotNull(envelope.turnId),
                        )
                        downloads.resumeIfIdle(currentProfile.serverId)
                    }
                    "sync.completed" -> {
                        if (completedSyncGeneration == syncGeneration) return
                        completedSyncGeneration = syncGeneration
                        requestSessionList()
                    }
                    "sync.reset_required" -> {
                        messageDownloads.onDisconnected()
                        messageContentStore.reconcile()
                        beginResetRebuild()
                    }
                    "session.list" -> {
                        applyRemoteSessionList(envelope)
                        if (hasPendingSyncCommand("session.list")) {
                            requestAllHistory(envelope)
                        }
                    }
                    "history.page" -> {
                        rememberRemoteSession(requireNotNull(envelope.sessionId))
                        val healedTurnId = reconcileHistoryTurnTerminals(envelope)
                        if (healedTurnId != null) {
                            val sessionId = requireNotNull(envelope.sessionId)
                            turnTrace.onTerminalReceived(sessionId, healedTurnId, "completed")
                            turnTrace.onRoomCommitted(sessionId, healedTurnId, TurnTraceStage.TERMINAL_COMMITTED)
                            turnTrace.onTurnCleared(sessionId, healedTurnId)
                        }
                        requestNextHistoryPage(envelope)
                        downloads.resumeIfIdle(currentProfile.serverId)
                        messageDownloads.resumeIfIdle(currentProfile.serverId)
                    }
                    "attachment.progress" -> uploads.onProgress(
                        ProtocolCodec.decodePayload(envelope.payload),
                    )
                    "message.proactive" -> {
                        rememberRemoteSession(requireNotNull(envelope.sessionId))
                        downloads.resumeIfIdle(currentProfile.serverId)
                    }
                    "session.created", "session.updated" ->
                        rememberRemoteSession(requireNotNull(envelope.sessionId))
                    "connection.degraded" -> mutableState.value = mutableState.value.copy(
                        connection = mutableState.value.connection.copy(phase = ConnectionPhase.DEGRADED),
                    )
                }
            }
            WireKind.REPLY -> {
                val authoritativeTerminalStatus = authoritativeTerminalStatus(envelope)
                if (stops.onReply(envelope)) {
                    authoritativeTerminalStatus?.let { terminalStatus ->
                        val sessionId = requireNotNull(envelope.sessionId)
                        val turnId = requireNotNull(envelope.turnId)
                        turnTrace.onTerminalReceived(sessionId, turnId, terminalStatus)
                        turnTrace.onRoomCommitted(sessionId, turnId, TurnTraceStage.TERMINAL_COMMITTED)
                        turnTrace.onTurnCleared(sessionId, turnId)
                    }
                    return
                }
                if (envelope.type.startsWith("attachment.download.")) {
                    try {
                        check(downloads.onReply(envelope)) { "收到未知附件下载 reply" }
                    } catch (error: IllegalArgumentException) {
                        failDownloadConnection("附件下载 reply 无效：${error.message}")
                    } catch (error: IllegalStateException) {
                        failDownloadConnection("附件下载状态失败：${error.message}")
                    } catch (error: IOException) {
                        failDownloadConnection("附件缓存提交失败：${error.message}")
                    } catch (error: SQLiteException) {
                        failDownloadConnection("附件下载数据库失败：${error.message}")
                    } catch (error: ArithmeticException) {
                        failDownloadConnection("附件下载长度溢出")
                    } catch (error: SecurityException) {
                        failDownloadConnection("附件缓存路径不安全")
                    }
                    return
                }
                if (uploads.onReply(envelope)) return
                if (messageDownloads.onReply(envelope)) return
                if (pluginUi.onReply(envelope)) return
                if (mobileWebUi.onReply(envelope)) return
                if (modelCatalog.onReply(envelope)) return
                if (runtimeInspection.onReply(envelope)) return
                val id = requireNotNull(envelope.id)
                if (envelope.type == "command.list.ok") {
                    applyCommandListReply(envelope)
                    return
                }
                if (envelope.type == "command.list.error" && id == pendingCommandListId) {
                    pendingCommandListId = null
                    mutableState.value = mutableState.value.copy(
                        errorMessage = envelope.payload["message"]?.jsonPrimitive?.content
                            ?: "加载快捷命令失败",
                    )
                    return
                }
                if (id in pendingSyncCommands && envelope.type.endsWith(".error")) {
                    val pending = requireNotNull(pendingSyncCommands.remove(id))
                    require(pending.generation == syncGeneration) { "收到旧 generation 的历史同步错误" }
                    require(envelope.type == "${pending.type}.error") { "历史同步错误类型不匹配" }
                    val errorCode = envelope.payload["code"]?.jsonPrimitive?.content
                    if (
                        pending.type == "history.get" &&
                        pending.afterSeq != null &&
                        pending.page != null &&
                        errorCode in LEGACY_HISTORY_FALLBACK_CODES
                    ) {
                        val sessionId = requireNotNull(pending.sessionId)
                        requestedHistoryCursors.remove(
                            Triple(syncGeneration, sessionId, pending.afterSeq),
                        )
                        requestedHistoryPages.remove(
                            Triple(syncGeneration, sessionId, pending.page),
                        )
                        requestLegacyHistoryPage(sessionId, pending.page)
                        return
                    }
                    mutableState.value = mutableState.value.copy(
                        errorMessage = envelope.payload["message"]?.toString()?.trim('"') ?: "历史同步失败",
                    )
                    socket.close(reason = "history sync rejected")
                    scheduleReconnect("历史同步失败，正在重新连接")
                    return
                }
                when (envelope.type) {
                    "message.send.ok" -> {
                        require(id == activeOutboxCommandId) { "收到非活动 outbox 命令的 ACK: $id" }
                        // 1. ACK 到达先记 received，Room 事务提交后记 committed；两次记录间不新增任何发送
                        turnTrace.onServerAckReceived(id)
                        val sentFiles = deliveryStore.acknowledgeOutbox(id, System.currentTimeMillis())
                        turnTrace.onServerAckCommitted(id)
                        activeOutboxCommandId = null
                        attachmentDrafts.deleteSentFiles(sentFiles)
                        flushOutbox()
                    }
                    "message.send.error" -> {
                        require(id == activeOutboxCommandId) { "收到非活动 outbox 命令的错误: $id" }
                        val code = envelope.payload["code"]?.jsonPrimitive?.content
                            ?: error("message.send.error 缺少 code")
                        turnTrace.onSendError(id, code)
                        if (code == "session_not_found") {
                            val sessionId = requireNotNull(envelope.sessionId) {
                                "session_not_found 缺少 session_id"
                            }
                            check(database.conversations().markRemoteKnown(sessionId) == 1) {
                                "session_not_found 对应的会话投影不存在: $sessionId"
                            }
                            val remoteIds = requireNotNull(mutableState.value.remoteSessionIds) {
                                "session_not_found 发生在会话目录同步完成前"
                            }
                            mutableState.value = mutableState.value.copy(
                                remoteSessionIds = remoteIds - sessionId,
                            )
                        }
                        deliveryStore.retainFailedOutbox(
                            id,
                            outcomeUnknown = code == "command_outcome_unknown",
                            updatedAt = System.currentTimeMillis(),
                        )
                        activeOutboxCommandId = null
                        mutableState.value = mutableState.value.copy(
                            errorMessage = if (code == "command_outcome_unknown") {
                                "消息结果待确认，请在原消息下方核对"
                            } else {
                                envelope.payload["message"]?.jsonPrimitive?.content ?: "消息发送失败"
                            },
                        )
                        flushOutbox()
                    }
                    "session.list.ok", "history.get.ok" -> completeSyncReply(envelope)
                    "device.update.ok" -> Unit
                    // 仅旧 Core 不支持的 unsupported_command 静默降级；其他错误
                    //（invalid_payload 等）保留可见，不得伪装成成功。
                    "device.update.error" -> {
                        val code = envelope.payload["code"]?.jsonPrimitive?.content
                        if (code != "unsupported_command") {
                            mutableState.value = mutableState.value.copy(
                                errorMessage = envelope.payload["message"]?.toString()?.trim('"')
                                    ?: "设备能力更新失败",
                            )
                        }
                    }
                    else -> {
                        require(envelope.type.endsWith(".error")) { "Unexpected reply type: ${envelope.type}" }
                        mutableState.value = mutableState.value.copy(
                            errorMessage = envelope.payload["message"]?.toString()?.trim('"') ?: "历史同步失败",
                        )
                    }
                }
            }
            WireKind.CONTROL -> {
                when (envelope.type) {
                    "plugin.ui.changed" -> pluginUi.onCatalogChanged()
                    MOBILE_WEB_UI_RELEASE_CHANGED -> {
                        val serverId = requireNotNull(profile).serverId
                        val hint = decodeMobileWebUiReleaseChangedPayload(envelope.payload)
                        require(hint.serverId == serverId) {
                            "WebUI release hint server_id 不匹配"
                        }
                        mobileWebUi.onControlHint(serverId)
                    }
                    "device.revoked" -> {
                        handleDeviceRevoked(
                            currentGeneration(),
                            decodeDeviceRevocationReason(envelope.payload),
                        )
                    }
                    else -> error("Unexpected authenticated control: ${envelope.type}")
                }
            }
            else -> error("Unexpected authenticated server frame: ${envelope.kind}")
        }
    }

    private fun requestSessionList() {
        if (hasPendingSyncCommand("session.list")) return
        sendSyncCommand(type = "session.list", sessionId = null, payload = buildJsonObject {})
    }

    private fun requestCommandList() {
        if (pendingCommandListId != null) return
        val epoch = requireNotNull(activeEpoch) { "快捷命令同步需要认证连接" }
        val candidate = requireNotNull(activeCandidate) { "快捷命令同步需要可用 endpoint" }
        val commandId = Ulid.next()
        pendingCommandListId = commandId
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = "command.list",
                id = commandId,
                connectionEpoch = epoch,
                payload = buildJsonObject {},
            ),
        )
        if (!sent) {
            pendingCommandListId = null
            scheduleReconnect("快捷命令未进入 WebSocket 队列")
        }
    }

    /** 校验服务端命令目录并发布给界面。 */
    private fun applyCommandListReply(envelope: WireEnvelope) {
        val commandId = requireNotNull(envelope.id)
        require(commandId == pendingCommandListId) { "收到未知快捷命令 reply: $commandId" }
        val payload = ProtocolCodec.decodePayload<CommandListPayload>(envelope.payload)
        val commands = validateCommandCatalog(payload.items)
        pendingCommandListId = null
        mutableState.value = mutableState.value.copy(commands = commands)
    }

    private fun sendPluginUiCommand(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
        sessionId: String? = null,
        turnId: String? = null,
    ): String? {
        val epoch = activeEpoch ?: return null
        val candidate = activeCandidate ?: return null
        val commandId = Ulid.next()
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = type,
                id = commandId,
                connectionEpoch = epoch,
                sessionId = sessionId,
                turnId = turnId,
                payload = payload,
            ),
        )
        if (!sent) {
            scheduleReconnect("插件 UI 命令未进入 WebSocket 队列: $type")
            return null
        }
        return commandId
    }

    private fun sendRuntimeInspectionCommand(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): String? {
        val epoch = activeEpoch ?: return null
        val candidate = activeCandidate ?: return null
        val commandId = Ulid.next()
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = type,
                id = commandId,
                connectionEpoch = epoch,
                payload = payload,
            ),
        )
        if (!sent) {
            scheduleReconnect("运行时检查命令未进入 WebSocket 队列: $type")
            return null
        }
        return commandId
    }

    private fun sendModelCatalogCommand(type: String, sessionId: String): String? {
        val epoch = activeEpoch ?: return null
        val candidate = activeCandidate ?: return null
        val commandId = Ulid.next()
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = type,
                id = commandId,
                connectionEpoch = epoch,
                sessionId = sessionId,
                payload = buildJsonObject {},
            ),
        )
        if (!sent) {
            scheduleReconnect("模型目录命令未进入 WebSocket 队列: $type")
            return null
        }
        return commandId
    }

    private fun hasPendingSyncCommand(type: String): Boolean =
        pendingSyncCommands.values.any { it.generation == syncGeneration && it.type == type }

    private suspend fun requestAllHistory(envelope: WireEnvelope) {
        val payload = ProtocolCodec.decodePayload<SessionListPayload>(envelope.payload)
        val forceReload = resetRebuildGeneration == syncGeneration
        for (session in payload.items) {
            if (session.messageCount <= 0) continue
            val local = database.messages().historyProjectionProgress(session.sessionId)
            val contiguous = if (local.messageCount == 0) {
                local.maxServerSeq == null
            } else {
                local.maxServerSeq == local.messageCount.toLong() - 1
            }
            val afterSeq = when {
                forceReload || !contiguous || local.messageCount > session.messageCount -> -1L
                local.messageCount == session.messageCount -> null
                else -> local.maxServerSeq ?: -1L
            } ?: continue
            val legacyPage = historyStartPage(
                remoteMessageCount = session.messageCount,
                local = local,
                forceReload = forceReload,
                pageSize = HISTORY_PAGE_SIZE,
            ) ?: continue
            if (!requestHistoryCursor(
                    session.sessionId,
                    afterSeq,
                    snapshotMaxSeq = null,
                    legacyPage = legacyPage,
                )
            ) return
        }
    }

    private fun applyRemoteSessionList(envelope: WireEnvelope) {
        val payload = ProtocolCodec.decodePayload<SessionListPayload>(envelope.payload)
        mutableState.value = mutableState.value.copy(
            remoteSessionIds = payload.items.mapTo(linkedSetOf()) { it.sessionId },
        )
    }

    private fun rememberRemoteSession(sessionId: String) {
        val remoteSessionIds = mutableState.value.remoteSessionIds ?: return
        if (sessionId in remoteSessionIds) return
        mutableState.value = mutableState.value.copy(remoteSessionIds = remoteSessionIds + sessionId)
    }

    /** 记录事件信封的物理接收阶段；turn.started 在 applyEvent 前完成 send->turn 绑定。 */
    private fun recordEnvelopeTrace(envelope: WireEnvelope) {
        val sessionId = envelope.sessionId ?: return
        val turnId = envelope.turnId ?: return
        when (envelope.type) {
            "turn.started" -> turnTrace.onTurnStartedReceived(
                sessionId,
                turnId,
                envelope.payload["client_message_id"]?.jsonPrimitive?.contentOrNull,
            )
            "react.thinking.delta" -> turnTrace.onThinkingReceived(sessionId, turnId)
            "answer.delta" -> turnTrace.onAnswerReceived(sessionId, turnId)
            "message.final" -> turnTrace.onTerminalReceived(sessionId, turnId, "completed")
            "turn.interrupted" -> turnTrace.onTerminalReceived(
                sessionId,
                turnId,
                interruptedTerminalStatus(envelope.payload),
            )
            else -> Unit
        }
    }

    /** 记录事件在 LocalDeliveryStore Room 事务提交后的阶段。 */
    private fun recordRoomCommitTrace(envelope: WireEnvelope) {
        val sessionId = envelope.sessionId ?: return
        val turnId = envelope.turnId ?: return
        when (envelope.type) {
            "turn.started" -> turnTrace.onRoomCommitted(
                sessionId, turnId, TurnTraceStage.TURN_STARTED_COMMITTED,
            )
            "react.thinking.delta" -> turnTrace.onRoomCommitted(
                sessionId, turnId, TurnTraceStage.FIRST_THINKING_COMMITTED,
            )
            "answer.delta" -> turnTrace.onRoomCommitted(
                sessionId, turnId, TurnTraceStage.FIRST_ANSWER_COMMITTED,
            )
            "message.final", "turn.interrupted" -> turnTrace.onRoomCommitted(
                sessionId, turnId, TurnTraceStage.TERMINAL_COMMITTED,
            )
            else -> Unit
        }
    }

    private fun requestNextHistoryPage(envelope: WireEnvelope) {
        val sessionId = requireNotNull(envelope.sessionId) { "History page has no session_id" }
        val payload = ProtocolCodec.decodePayload<HistoryPagePayload>(envelope.payload)
        if (payload.contentRefVersion == null) {
            val page = requireNotNull(payload.page) { "Legacy history page has no page" }
            if (Triple(syncGeneration, sessionId, page) !in requestedHistoryPages) return
            if (Math.multiplyExact(page.toLong(), payload.pageSize.toLong()) < payload.total.toLong()) {
                requestLegacyHistoryPage(sessionId, page + 1)
            }
            return
        }
        require(payload.contentRefVersion == 1) { "History page content_ref_version mismatch" }
        val afterSeq = requireNotNull(payload.afterSeq) { "History page has no after_seq" }
        val nextAfterSeq = requireNotNull(payload.nextAfterSeq) { "History page has no next_after_seq" }
        val snapshotMaxSeq = requireNotNull(payload.snapshotMaxSeq) {
            "History page has no snapshot_max_seq"
        }
        val hasMore = requireNotNull(payload.hasMore) { "History page has no has_more" }
        if (Triple(syncGeneration, sessionId, afterSeq) !in requestedHistoryCursors) return
        require(nextAfterSeq in afterSeq..snapshotMaxSeq) { "History page next cursor is invalid" }
        if (hasMore) {
            require(nextAfterSeq > afterSeq) { "History page cursor did not advance" }
            requestHistoryCursor(sessionId, nextAfterSeq, snapshotMaxSeq)
        }
    }

    private fun requestHistoryCursor(
        sessionId: String,
        afterSeq: Long,
        snapshotMaxSeq: Long?,
        legacyPage: Int? = null,
    ): Boolean {
        if (!requestedHistoryCursors.add(Triple(syncGeneration, sessionId, afterSeq))) return true
        legacyPage?.let { requestedHistoryPages.add(Triple(syncGeneration, sessionId, it)) }
        return sendSyncCommand(
            type = "history.get",
            sessionId = sessionId,
            payload = buildJsonObject {
                put("page_size", HISTORY_PAGE_SIZE)
                put("content_ref_version", 1)
                put("after_seq", afterSeq)
                snapshotMaxSeq?.let { put("snapshot_max_seq", it) }
            },
            legacyPage = legacyPage,
        )
    }

    private fun requestLegacyHistoryPage(sessionId: String, page: Int): Boolean {
        if (!requestedHistoryPages.add(Triple(syncGeneration, sessionId, page))) return true
        return sendSyncCommand(
            type = "history.get",
            sessionId = sessionId,
            payload = buildJsonObject {
                put("page", page)
                put("page_size", HISTORY_PAGE_SIZE)
            },
        )
    }

    private fun sendSyncCommand(
        type: String,
        sessionId: String?,
        payload: kotlinx.serialization.json.JsonObject,
        legacyPage: Int? = null,
    ): Boolean {
        val epoch = requireNotNull(activeEpoch) { "History sync requires an authenticated connection" }
        val candidate = requireNotNull(activeCandidate) { "History sync requires an active endpoint" }
        val commandId = Ulid.next()
        pendingSyncCommands[commandId] = PendingSyncCommand(
            generation = syncGeneration,
            type = type,
            sessionId = sessionId,
            page = legacyPage ?: payload["page"]?.jsonPrimitive?.longOrNull?.also {
                require(it in 1..Int.MAX_VALUE) { "历史同步 page 超出范围" }
            }?.toInt(),
            afterSeq = payload["after_seq"]?.jsonPrimitive?.longOrNull?.also {
                require(it >= -1) { "历史同步 after_seq 超出范围" }
            },
        )
        val sent =
            socket.send(
                candidate,
                WireEnvelope(
                    v = WIRE_PROTOCOL_VERSION,
                    kind = WireKind.COMMAND,
                    type = type,
                    id = commandId,
                    connectionEpoch = epoch,
                    sessionId = sessionId,
                    payload = payload,
                ),
            )
        if (!sent) {
            pendingSyncCommands.remove(commandId)
            scheduleReconnect("历史同步命令未进入 WebSocket 队列")
            return false
        }
        return true
    }

    private suspend fun completeSyncReply(envelope: WireEnvelope) {
        val commandId = requireNotNull(envelope.id)
        val pending = requireNotNull(pendingSyncCommands.remove(commandId)) {
            "收到未知历史同步 reply: $commandId"
        }
        require(pending.generation == syncGeneration) { "收到旧 generation 的历史同步 reply" }
        require(envelope.type == "${pending.type}.ok") { "历史同步 reply 类型不匹配" }
        require(envelope.sessionId == pending.sessionId) { "历史同步 reply session 不匹配" }
        if (envelope.payload["after_seq"] != null) {
            require(envelope.payload["after_seq"]?.jsonPrimitive?.longOrNull == pending.afterSeq) {
                "历史同步 reply after_seq 不匹配"
            }
        } else if (pending.page != null) {
            require(envelope.payload["page"]?.jsonPrimitive?.longOrNull == pending.page.toLong()) {
                "历史同步 reply page 不匹配"
            }
        }
        finishSessionSyncIfComplete()
    }

    /** 从 reset event 的新 cursor 开始重建当前服务端投影视图。 */
    private fun beginResetRebuild() {
        syncGeneration += 1
        completedSyncGeneration = syncGeneration
        resetRebuildGeneration = syncGeneration
        pendingSyncCommands.clear()
        pendingCommandListId = null
        pluginUi.onDisconnected("服务端要求重新同步")
        runtimeInspection.onDisconnected()
        modelCatalog.onDisconnected()
        requestedHistoryPages.clear()
        requestedHistoryCursors.clear()
        mutableState.value = mutableState.value.copy(
            projectionGeneration = mutableState.value.projectionGeneration + 1,
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.SYNCING),
            remoteSessionIds = null,
            errorMessage = null,
        )
        armPhaseDeadline(currentGeneration(), ConnectionDeadlinePhase.SYNC)
        requestSessionList()
    }

    /** 最新代际目录和历史完整落地后，才恢复上传、停止命令与 outbox。 */
    private suspend fun finishSessionSyncIfComplete() {
        if (completedSyncGeneration != syncGeneration) return
        if (mutableState.value.remoteSessionIds == null || pendingSyncCommands.isNotEmpty()) return
        if (mutableState.value.connection.phase == ConnectionPhase.READY) return
        if (resetRebuildGeneration == syncGeneration) resetRebuildGeneration = null
        val currentProfile = requireNotNull(profile)
        ensureCurrentSession(currentProfile)
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.READY),
            isReloadingHistory = false,
        )
        cancelPhaseDeadline()
        requestCommandList()
        pluginUi.onConnectionReady(currentProfile.serverId)
        runtimeInspection.onConnectionReady(currentProfile.serverId)
        modelCatalog.onConnectionReady(
            currentProfile.serverId,
            requireNotNull(mutableState.value.currentSessionId),
        )
        uploads.onConnectionReady(currentProfile.serverId)
        downloads.onConnectionReady(currentProfile.serverId)
        messageDownloads.onConnectionReady(currentProfile.serverId)
        stops.onConnectionReady()
        flushOutbox()
        sendDeviceUpdateCommand()
    }

    private fun sendAttachmentCommand(
        type: String,
        id: String,
        sessionId: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): Boolean {
        val epoch = activeEpoch ?: return false
        val candidate = activeCandidate ?: return false
        return socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = type,
                id = id,
                connectionEpoch = epoch,
                sessionId = sessionId,
                payload = payload,
            ),
        )
    }

    private fun sendTurnStopCommand(request: TurnStopRequest): Boolean {
        val epoch = activeEpoch ?: return false
        val candidate = activeCandidate ?: return false
        return socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = "turn.stop",
                id = request.commandId,
                connectionEpoch = epoch,
                sessionId = request.sessionId,
                turnId = request.turnId,
                payload = buildJsonObject {},
            ),
        )
    }

    /** 认证连接建立后刷新能力声明，让升级 APK 无需重新配对即可接收新事件。 */
    private fun sendDeviceUpdateCommand(): Boolean {
        val epoch = activeEpoch ?: return false
        val candidate = activeCandidate ?: return false
        return socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = "device.update",
                id = Ulid.next(),
                connectionEpoch = epoch,
                payload = buildJsonObject {
                    put(
                        "capabilities",
                        kotlinx.serialization.json.JsonArray(
                            CAPABILITIES.map(::JsonPrimitive),
                        ),
                    )
                },
            ),
        )
    }

    private suspend fun persistTurnStop(request: TurnStopRequest) {
        val currentProfile = requireNotNull(profile) { "停止生成时不存在已配对服务器" }
        database.pendingTurnStops().insert(
            PendingTurnStopEntity(
                commandId = request.commandId,
                serverId = currentProfile.serverId,
                sessionId = request.sessionId,
                turnId = request.turnId,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun removePersistedTurnStop(commandId: String) {
        check(database.pendingTurnStops().delete(commandId) == 1) {
            "持久停止请求不存在: $commandId"
        }
    }

    /** 从 streaming 消息和持久 stop 意图恢复当前服务器的交互状态。 */
    private suspend fun restoreTurnStops(serverId: String) {
        // 1. streaming assistant 消息是活动 turn 的本地持久投影
        val activeMessages = database.messages().activeAssistantTurns(serverId)
        require(activeMessages.distinctBy { it.sessionId }.size == activeMessages.size) {
            "同一会话存在多个 streaming assistant turn"
        }
        val activeTurns = activeMessages.associate { message ->
            val turnId = message.messageId.removePrefix(ASSISTANT_TURN_PREFIX)
            require(turnId.isNotBlank()) { "Streaming assistant turn id is empty" }
            message.sessionId to turnId
        }

        // 2. 仅恢复仍属于活动 turn 的 stop，终态残留由 coordinator 清理
        val pendingStops = database.pendingTurnStops().listForServer(serverId).map { stop ->
            TurnStopRequest(stop.commandId, stop.sessionId, stop.turnId)
        }
        stops.restore(activeTurns, pendingStops)
    }

    /** 用 canonical history 收束漏收 final 的本地活动 turn；返回被收敛的 turnId。 */
    private suspend fun reconcileHistoryTurnTerminals(envelope: WireEnvelope): String? {
        val sessionId = requireNotNull(envelope.sessionId)
        val activeTurnId = stops.activeTurnId(sessionId) ?: return null
        val history = ProtocolCodec.decodePayload<HistoryPagePayload>(envelope.payload)
        if (history.items.any { item ->
                item.role == "assistant" &&
                    item.extra["control_turn_id"]?.jsonPrimitive?.contentOrNull == activeTurnId
            }
        ) {
            stops.onTurnTerminal(sessionId, activeTurnId)
            return activeTurnId
        }
        return null
    }

    private suspend fun flushOutbox() {
        if (activeOutboxCommandId != null) return
        val currentProfile = requireNotNull(profile)
        val epoch = activeEpoch ?: return
        val candidate = activeCandidate ?: return
        while (true) {
            val command = database.outbox().pending(currentProfile.serverId).firstOrNull() ?: return
            val stored = ProtocolCodec.decode(command.envelopeJson)
            val sessionId = stored.sessionId
            if (
                stored.type == "message.send" &&
                sessionId != null &&
                isRemoteMissingSession(currentProfile.serverId, sessionId)
            ) {
                deliveryStore.retainUnsentOutbox(command.commandId, System.currentTimeMillis())
                mutableState.value = mutableState.value.copy(
                    errorMessage = "电脑端已删除一段会话，未发送内容仍保留在本机",
                )
                continue
            }
            val wire = stored.copy(connectionEpoch = epoch)
            deliveryStore.markOutboxAttempt(command.commandId, System.currentTimeMillis())
            activeOutboxCommandId = command.commandId
            if (!socket.send(candidate, wire)) {
                activeOutboxCommandId = null
                deliveryStore.retryOutbox(command.commandId)
            } else {
                // 1. 只有写入 WebSocket 成功才算 dispatched；outbox 仅承载 message.send，session_id 必非空
                turnTrace.onSocketDispatched(
                    requireNotNull(stored.sessionId) { "Outbox 命令缺少 session_id" },
                    command.commandId,
                )
            }
            return
        }
    }

    private fun recordAck(eventSeq: Long) {
        pendingAckSeq = eventSeq
        pendingAckCount += 1
        if (pendingAckCount >= ACK_EVENT_LIMIT) {
            sendPendingAck()
            return
        }
        if (ackJob == null) {
            ackJob = scope.launch {
                delay(ACK_DELAY_MILLIS)
                mutex.withLock { sendPendingAck() }
            }
        }
    }

    private fun sendPendingAck() {
        ackJob?.cancel()
        ackJob = null
        if (pendingAckCount == 0) return
        val epoch = activeEpoch ?: return
        val candidate = activeCandidate ?: return
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.ACK,
                type = "event.ack",
                connectionEpoch = epoch,
                payload = buildJsonObject { put("through_event_seq", pendingAckSeq) },
            ),
        )
        if (sent) pendingAckCount = 0
    }

    private fun connectQr(qr: PairingQrPayload) {
        if (deviceRevoked) return
        reconnectJob?.cancel()
        resetGenerationState()
        turnTrace.reset()
        val generation = socket.connectRace(
            qr.lanEndpoints.lanEndpoints(qr.tlsSpkiPins),
            qr.tunnelEndpoints.tunnelEndpoints(),
        )
        currentGenerationValue = generation
        if (deviceRevoked || generation in revokedGenerations) {
            socket.closeGeneration(generation)
            return
        }
        networkRecovery.onGenerationStarted(currentGenerationValue, mutableState.value.transferNetwork)
        armPhaseDeadline(currentGenerationValue, ConnectionDeadlinePhase.CHALLENGE)
    }

    private fun connectProfile(value: ServerProfileEntity) {
        if (deviceRevoked) return
        reconnectJob?.cancel()
        resetGenerationState()
        val pins = json.decodeFromString<List<String>>(value.tlsSpkiPinsJson)
        val lan = json.decodeFromString<List<String>>(value.lanEndpointsJson).lanEndpoints(pins)
        val tunnel = json.decodeFromString<List<String>>(value.tunnelEndpointsJson).tunnelEndpoints()
        val generation = socket.connectRace(lan, tunnel)
        currentGenerationValue = generation
        if (deviceRevoked || generation in revokedGenerations) {
            socket.closeGeneration(generation)
            return
        }
        networkRecovery.onGenerationStarted(currentGenerationValue, mutableState.value.transferNetwork)
        armPhaseDeadline(currentGenerationValue, ConnectionDeadlinePhase.CHALLENGE)
        mutableState.value = mutableState.value.copy(
            connection = ConnectionState(phase = ConnectionPhase.CONNECTING, retryCount = retryCount),
            hasProfile = true,
            serverId = value.serverId,
        )
    }

    @Suppress("SuspiciousIndentation") // Android lint 误把多行构造调用后的声明识别为续行
    private suspend fun conversationForMessage(
        currentProfile: ServerProfileEntity,
        sessionId: String,
        body: String,
        now: Long,
    ): ConversationEntity {
        val current = database.conversations().get(sessionId)
        require(current == null || current.serverId == currentProfile.serverId) {
            "Conversation belongs to another server"
        }
        val title = if (current == null || current.title == "新对话") {
            body.lineSequence().first().take(32)
        } else {
            current.title
        }
        return ConversationEntity(
            sessionId,
            currentProfile.serverId,
            title,
            now,
            remoteKnown = current?.remoteKnown ?: false,
        )
    }

    private suspend fun ensureCurrentSession(currentProfile: ServerProfileEntity): String {
        val existing = mutableState.value.currentSessionId
        if (existing != null) {
            val conversation = requireNotNull(database.conversations().get(existing)) {
                "Selected mobile session does not exist"
            }
            require(conversation.serverId == currentProfile.serverId) {
                "Selected mobile session belongs to another server"
            }
            return existing
        }
        val sessionId = deliveryStore.restoreSelectedSession(currentProfile.serverId, null)
            ?: createLocalSession(currentProfile)
        preferences.selectSession(sessionId)
        mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
        return sessionId
    }

    private suspend fun createLocalSession(currentProfile: ServerProfileEntity): String {
        val sessionId = "mobile:${UUID.randomUUID()}"
        database.conversations().upsert(
            ConversationEntity(sessionId, currentProfile.serverId, "新对话", System.currentTimeMillis()),
        )
        return sessionId
    }

    private suspend fun isRemoteMissingSession(serverId: String, sessionId: String): Boolean {
        val remoteSessionIds = mutableState.value.remoteSessionIds ?: return false
        if (sessionId in remoteSessionIds) return false
        val conversation = database.conversations().get(sessionId) ?: return false
        require(conversation.serverId == serverId) { "Conversation belongs to another server" }
        return conversation.remoteKnown
    }

    /** 为当前连接代际设置唯一的应用层阶段截止时间。 */
    private fun armPhaseDeadline(generation: Long, phase: ConnectionDeadlinePhase) {
        require(generation == currentGeneration()) { "连接阶段截止时间属于旧代际" }
        if (
            phase != ConnectionDeadlinePhase.SYNC &&
            pairingConfirmationGeneration == generation
        ) {
            return
        }
        if (!shouldReplacePhaseDeadline(phaseDeadline, generation, phase)) return
        phaseDeadlineJob?.cancel()
        val deadline = ConnectionPhaseDeadline(generation, phase)
        phaseDeadline = deadline
        phaseDeadlineJob = scope.launch {
            delay(phase.deadlineMillis())
            mutex.withLock {
                if (phaseDeadline != deadline || currentGeneration() != generation) return@withLock
                phaseDeadline = null
                phaseDeadlineJob = null
                val message = phase.timeoutMessage()
                socket.close(reason = message)
                scheduleReconnect(message)
            }
        }
    }

    private fun refreshSyncDeadline() {
        if (mutableState.value.connection.phase == ConnectionPhase.SYNCING) {
            armPhaseDeadline(currentGeneration(), ConnectionDeadlinePhase.SYNC)
        }
    }

    private fun cancelPhaseDeadline() {
        phaseDeadlineJob?.cancel()
        phaseDeadlineJob = null
        phaseDeadline = null
    }

    private fun scheduleReconnect(message: String) {
        if (deviceRevoked) {
            mutableState.value = mutableState.value.copy(
                connection = mutableState.value.connection.copy(phase = ConnectionPhase.FAILED),
                errorMessage = "设备配对已撤销：请重新配对",
            )
            return
        }
        cancelPhaseDeadline()
        mobileWebUi.onDisconnected()
        if (reconnectJob?.isActive == true) return
        val reconnectImmediately = networkRecovery.consume(currentGeneration())
        activeCandidate = null
        activeEpoch = null
        activeOutboxCommandId = null
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
        uploads.onDisconnected()
        downloads.onDisconnected()
        messageDownloads.onDisconnected()
        pendingSyncCommands.clear()
        pendingCommandListId = null
        pluginUi.onDisconnected("连接已中断")
        runtimeInspection.onDisconnected()
        modelCatalog.onDisconnected()
        requestedHistoryPages.clear()
        requestedHistoryCursors.clear()
        resetRebuildGeneration = null
        retryCount += 1
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(
                phase = ConnectionPhase.DEGRADED,
                retryCount = retryCount,
            ),
            errorMessage = message,
        )
        if (reconnectImmediately) {
            pendingPairing?.let { connectQr(it.qr) } ?: profile?.let(::connectProfile)
            return
        }
        reconnectJob = scope.launch {
            delay(FullJitterBackoff.nextDelayMillis(retryCount))
            mutex.withLock {
                if (deviceRevoked) return@withLock
                pendingPairing?.let { connectQr(it.qr) } ?: profile?.let(::connectProfile)
            }
        }
    }

    private suspend fun handleDeviceRevoked(revokedGeneration: Long, reason: String) {
        if (deviceRevokeCleanupStarted) return
        val epoch = pendingRevocationEpoch
        if (epoch != null && revocationEpoch.get() != epoch) return
        if (pendingRevokedGeneration != null && pendingRevokedGeneration != revokedGeneration) return
        deviceRevokeCleanupStarted = true
        deviceRevoked = true
        pendingRevokedGeneration = revokedGeneration
        if (pendingRevocationEpoch == null) {
            pendingRevocationEpoch = revocationEpoch.incrementAndGet()
        }
        revokedGenerations += revokedGeneration
        socket.closeGeneration(revokedGeneration, 4403, "device revoked")
        reconnectJob?.cancel()
        reconnectJob = null
        cancelPhaseDeadline()
        activeCandidate = null
        activeEpoch = null
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
        var revokeError: String? = null
        try {
            profile?.serverId?.let { mobileWebUi.revokeServer(it) }
        } catch (error: IOException) {
            revokeError = "本地 WebUI 撤销清理失败：${error.message}"
        } catch (error: SQLiteException) {
            revokeError = "本地 WebUI 撤销数据库失败：${error.message}"
        } catch (error: SecurityException) {
            revokeError = "本地 WebUI 撤销权限失败：${error.message}"
        }
        uploads.onDisconnected()
        downloads.onDisconnected()
        messageDownloads.onDisconnected()
        pendingSyncCommands.clear()
        pendingCommandListId = null
        pluginUi.onDisconnected("设备配对已撤销")
        runtimeInspection.onDisconnected()
        modelCatalog.onDisconnected()
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(
                phase = ConnectionPhase.FAILED,
                lastErrorCode = "device_revoked",
            ),
            isReloadingHistory = false,
            errorMessage = revokeError ?: "设备配对已撤销：请重新配对${reason.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}",
        )
    }

    /** 在回调获取 session mutex 前锁存撤销状态，阻断重连竞态。 */
    private fun markDeviceRevokedImmediately(generation: Long): Long? {
        if (deviceRevoked) {
            return pendingRevokedGeneration
                ?.takeIf { it == generation }
                ?.let { pendingRevocationEpoch }
        }
        deviceRevoked = true
        pendingRevokedGeneration = generation
        val epoch = revocationEpoch.incrementAndGet()
        pendingRevocationEpoch = epoch
        revokedGenerations += generation
        reconnectJob?.cancel()
        return epoch
    }

    private fun enqueueDeviceRevocation(generation: Long, epoch: Long, reason: String) {
        scope.launch {
            mutex.withLock {
                if (!shouldApplyQueuedDeviceRevocation(
                        currentEpoch = revocationEpoch.get(),
                        queuedEpoch = epoch,
                        currentGeneration = currentGeneration(),
                        queuedGeneration = generation,
                        ownerGenerationCurrent = socket.isGenerationCurrent(generation),
                    )
                ) return@withLock
                handleDeviceRevoked(generation, reason)
            }
        }
    }

    /** 停止永久协议错误，并按错误类型隔离或保留当前 outbox。 */
    private suspend fun failProtocolConnection(code: Int, action: TerminalProtocolAction) {
        // 1. 收起当前连接态任务
        mobileWebUi.onDisconnected()
        reconnectJob?.cancel()
        reconnectJob = null
        cancelPhaseDeadline()
        activeCandidate = null
        activeEpoch = null
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
        uploads.onDisconnected()
        downloads.onDisconnected()
        messageDownloads.onDisconnected()
        pendingSyncCommands.clear()
        pendingCommandListId = null
        pluginUi.onDisconnected("协议不兼容")
        runtimeInspection.onDisconnected()
        modelCatalog.onDisconnected()
        requestedHistoryPages.clear()
        requestedHistoryCursors.clear()
        resetRebuildGeneration = null

        // 2. 隔离坏命令；只有版本不兼容时才保留等待升级
        val currentProfile = requireNotNull(profile)
        val commandId = activeOutboxCommandId
        if (action == TerminalProtocolAction.FAIL_ACTIVE_COMMAND && commandId != null) {
            deliveryStore.discardFailedOutbox(commandId, System.currentTimeMillis())
            activeOutboxCommandId = null
            scheduleReconnect("消息格式无效，已标记发送失败")
            return
        } else {
            database.outbox().resetInFlight(currentProfile.serverId)
        }
        activeOutboxCommandId = null
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(
                phase = ConnectionPhase.FAILED,
                lastErrorCode = "protocol_$code",
            ),
            isReloadingHistory = false,
            errorMessage = "消息协议不兼容，请确认手机与电脑端版本一致后重新连接",
        )
    }

    private fun failDownloadConnection(message: String) {
        downloads.onDisconnected()
        mutableState.value = mutableState.value.copy(errorMessage = message)
        socket.close(4406, "invalid attachment download state")
        scheduleReconnect(message)
    }

    private fun failStartup(code: String, message: String, error: Throwable) {
        Log.e(TAG, message, error)
        onRuntimeError("operation=startup code=$code", error)
        mutableState.value = mutableState.value.copy(
            initialized = true,
            connection = mutableState.value.connection.copy(
                phase = ConnectionPhase.FAILED,
                lastErrorCode = code,
            ),
            errorMessage = message,
        )
    }

    private fun failCandidateProtocol(
        candidateId: SocketCandidateId,
        envelope: WireEnvelope,
        error: Throwable,
        message: String,
    ) {
        onRuntimeError(
            "operation=envelope candidate_generation=${candidateId.generation} " +
                "candidate_ordinal=${candidateId.ordinal} kind=${envelope.kind} type=${envelope.type} " +
                "event_seq=${envelope.eventSeq} connection_epoch=${envelope.connectionEpoch} " +
                "phase=${mutableState.value.connection.phase}",
            error,
        )
        mutableState.value = mutableState.value.copy(errorMessage = message)
        if (candidateId == activeCandidate) {
            socket.close(4406, "invalid authenticated server frame")
            scheduleReconnect(message)
        } else {
            socket.reject(candidateId, 4406, "invalid pre-auth server frame")
        }
    }

    private fun resetGenerationState() {
        cancelPhaseDeadline()
        challengedCandidates.clear()
        candidateEndpoints.clear()
        activeCandidate = null
        activeEpoch = null
        activeOutboxCommandId = null
        pendingCommandListId = null
        messageDownloads.onDisconnected()
        pluginUi.onDisconnected("连接已重置")
        runtimeInspection.onDisconnected()
        modelCatalog.onDisconnected()
        mobileWebUi.onDisconnected()
    }

    private fun publishTurnState() {
        val sessionId = mutableState.value.currentSessionId
        mutableState.value = mutableState.value.copy(
            activeTurnId = stops.activeTurnId(sessionId),
            activeSessionIds = stops.activeSessionIds(),
            isStopping = stops.isStopping(sessionId),
            outputCompleted = stops.isOutputCompleted(sessionId),
        )
    }

    private fun publishDownloadState(active: Boolean) {
        mutableState.value = mutableState.value.copy(hasActiveAttachmentDownload = active)
    }

    /** 应用网络计费变化，并在策略允许时恢复同一持久化上传队列。 */
    private suspend fun applyTransferNetwork(next: TransferNetworkState) {
        mutex.withLock {
            val previous = mutableState.value.transferNetwork
            networkRecovery.onNetworkState(
                generation = currentGeneration(),
                previous = previous,
                next = next,
                hasConnectionTarget = pendingPairing != null || profile != null,
            )

            // 1. 离开计费网络后撤销一次性授权
            if (next.kind != TransferNetworkKind.METERED) {
                meteredLargeTransferApproved = false
            }
            mutableState.value = mutableState.value.copy(
                transferNetwork = next,
                meteredLargeTransferApproved = meteredLargeTransferApproved,
            )

            // 2. 网络恢复时立即替换退避任务，避免可用链路继续空等
            if (
                mutableState.value.connection.phase == ConnectionPhase.DEGRADED &&
                reconnectJob?.isActive == true &&
                networkRecovery.consume(currentGeneration())
            ) {
                reconnectJob?.cancel()
                reconnectJob = null
                pendingPairing?.let { connectQr(it.qr) } ?: profile?.let(::connectProfile)
            }

            // 3. 网络策略放行后继续既有可断点队列
            profile?.serverId?.let { serverId ->
                if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                    uploads.resumeIfIdle(serverId)
                }
            }
        }
    }

    private suspend fun canUploadAttachment(transfer: AttachmentTransferEntity): Boolean =
        !isRemoteMissingSession(transfer.serverId, transfer.sessionId) &&
            (
                transfer.sizeBytes < LARGE_TRANSFER_BYTES ||
                    transferNetwork.value.kind != TransferNetworkKind.METERED ||
                    meteredLargeTransferApproved
                )

    private suspend fun canDownloadAttachment(transfer: MediaAttachmentEntity): Boolean =
        !isRemoteMissingSession(transfer.serverId, transfer.sessionId)

    private fun control(type: String, payload: kotlinx.serialization.json.JsonObject) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.CONTROL,
        type = type,
        payload = payload,
    )

    private fun sendMobileWebUiCommand(type: String, payload: kotlinx.serialization.json.JsonObject): String? {
        val candidate = activeCandidate ?: return null
        val epoch = activeEpoch ?: return null
        val commandId = Ulid.next()
        val sent = socket.send(
            candidate,
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.COMMAND,
                type = type,
                id = commandId,
                connectionEpoch = epoch,
                payload = payload,
            ),
        )
        return commandId.takeIf { sent }
    }

    @Volatile
    private var currentGenerationValue = 0L

    private fun currentGeneration(): Long = currentGenerationValue

    private fun List<String>.lanEndpoints(pins: List<String>): List<ServerEndpoint> =
        map { ServerEndpoint(it, pins, EndpointRoute.LAN) }

    private fun List<String>.tunnelEndpoints(): List<ServerEndpoint> =
        map { ServerEndpoint(it, emptyList(), EndpointRoute.TUNNEL) }

    private companion object {
        const val TAG = "RealtimeSession"
        const val STALE_NOTIFICATION_MESSAGE = "通知对应的会话或消息已不可用"
        val CAPABILITIES = listOf(
            "chat",
            "streaming",
            "tools",
            "proactive",
            "attachments-v1",
            "history-content-range-v1",
            MOBILE_WEB_UI_CAPABILITY,
            TURN_OUTPUT_COMPLETED_CAPABILITY,
        )
        val LEGACY_HISTORY_FALLBACK_CODES = setOf(
            "invalid_payload",
            "unsupported_content_ref_version",
        )
        val MOBILE_SESSION = Regex("^mobile:(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
        const val HISTORY_PAGE_SIZE = 10
        const val ACK_DELAY_MILLIS = 100L
        const val ACK_EVENT_LIMIT = 32
        const val LARGE_TRANSFER_BYTES = 10L * 1024 * 1024
        /** TODO(deprecated): 流式临时消息 ID 前缀，服务端稳定身份取代后删除 */
        const val ASSISTANT_TURN_PREFIX = "assistant:"
    }
}

internal fun endHistoryReload(state: MobileSessionState, errorMessage: String): MobileSessionState =
    state.copy(isReloadingHistory = false, errorMessage = errorMessage)

private val COMMAND_NAME = Regex("^[a-z][a-z0-9_]{0,31}$")

/** 校验服务端命令目录的跨语言边界并返回原目录。 */
internal fun validateCommandCatalog(items: List<RemoteCommandItem>): List<RemoteCommandItem> {
    // 1. 校验每个命令的名称、说明和所有权
    items.forEach { item ->
        require(COMMAND_NAME.matches(item.command)) { "快捷命令名称无效: ${item.command}" }
        require(
            item.description.isNotBlank() &&
                item.description.codePointCount(0, item.description.length) <= 256,
        ) {
            "快捷命令说明无效: ${item.command}"
        }
        require(item.command != "stop") { "stop 由生成中止控件拥有" }
    }

    // 2. 校验目录内名称唯一
    require(items.map { it.command }.distinct().size == items.size) { "快捷命令名称重复" }
    return items
}
