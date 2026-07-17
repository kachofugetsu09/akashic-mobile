package com.akashic.mobile.data.realtime

import android.net.Uri
import android.os.Build
import com.akashic.mobile.data.local.AttachmentDraftStore
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.AppPreferences
import com.akashic.mobile.data.local.ConversationEntity
import com.akashic.mobile.data.local.LocalDeliveryStore
import com.akashic.mobile.data.local.RealtimeCursorEntity
import com.akashic.mobile.data.local.ServerProfileEntity
import com.akashic.mobile.domain.model.ConnectionPhase
import com.akashic.mobile.domain.model.ConnectionState
import com.akashic.mobile.domain.model.EndpointRoute
import com.akashic.mobile.domain.model.ServerEndpoint
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class MobileSessionState(
    val initialized: Boolean = false,
    val scanGeneration: Long = 0,
    val connection: ConnectionState = ConnectionState(),
    val hasProfile: Boolean = false,
    val serverId: String? = null,
    val pairingConfirmationCode: String? = null,
    val currentSessionId: String? = null,
    val errorMessage: String? = null,
)

internal object FullJitterBackoff {
    fun maximumDelayMillis(retry: Int): Long {
        require(retry >= 0) { "retry must not be negative" }
        val exponent = min(retry, 8)
        return min(30_000L, 500L * (1L shl exponent))
    }

    fun nextDelayMillis(retry: Int, random: Random = Random.Default): Long =
        random.nextLong(maximumDelayMillis(retry) + 1)
}

internal const val MAX_MESSAGE_TEXT_LENGTH = 65_536

internal fun outgoingMessageTextLength(text: String): Int =
    text.codePointCount(0, text.length)

internal fun validateOutgoingMessageLength(text: String): String {
    val body = text.trim()
    require(outgoingMessageTextLength(body) <= MAX_MESSAGE_TEXT_LENGTH) {
        "Message text exceeds $MAX_MESSAGE_TEXT_LENGTH characters"
    }
    return body
}

internal fun validateOutgoingMessageText(text: String): String =
    validateOutgoingMessageLength(text).also { require(it.isNotEmpty()) { "Message text is empty" } }

internal enum class TerminalProtocolAction {
    FAIL_ACTIVE_COMMAND,
    PRESERVE_OUTBOX,
}

internal fun terminalProtocolAction(code: Int, hasActiveCommand: Boolean): TerminalProtocolAction? = when (code) {
    CLOSE_COMMAND_REJECTED -> if (hasActiveCommand) {
        TerminalProtocolAction.FAIL_ACTIVE_COMMAND
    } else {
        TerminalProtocolAction.PRESERVE_OUTBOX
    }
    4400, 4406 -> TerminalProtocolAction.PRESERVE_OUTBOX
    else -> null
}

internal enum class ConnectionDeadlinePhase {
    CHALLENGE,
    AUTHENTICATION,
    SYNC,
}

internal fun ConnectionDeadlinePhase.deadlineMillis(): Long = when (this) {
    ConnectionDeadlinePhase.CHALLENGE,
    ConnectionDeadlinePhase.AUTHENTICATION,
    -> 10_000L
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

internal fun shouldApplyCandidateOpen(
    connectionPhase: ConnectionPhase,
    hasActiveCandidate: Boolean,
    candidateGeneration: Long,
    pairingConfirmationGeneration: Long?,
): Boolean = !hasActiveCandidate &&
    pairingConfirmationGeneration != candidateGeneration &&
    connectionPhase in setOf(
        ConnectionPhase.CONNECTING,
        ConnectionPhase.SERVER_CHALLENGE,
        ConnectionPhase.DEGRADED,
    )

internal fun shouldAcceptCandidateFrame(
    currentGeneration: Long,
    activeCandidate: SocketCandidateId?,
    candidateId: SocketCandidateId,
): Boolean = candidateId.generation == currentGeneration &&
    (activeCandidate == null || candidateId == activeCandidate)

internal fun historySessionsToFetch(payload: SessionListPayload): List<String> {
    require(payload.items.distinctBy(RemoteSessionSummary::sessionId).size == payload.items.size) {
        "Session catalog contains duplicate ids"
    }
    payload.items.forEach { require(it.messageCount >= 0) { "History message_count must not be negative" } }
    return payload.items.filter { it.messageCount > 0 }.map(RemoteSessionSummary::sessionId)
}

internal fun nextHistoryPage(payload: HistoryPagePayload): Int? {
    require(payload.total >= 0 && payload.page > 0 && payload.pageSize > 0) { "Invalid history pagination" }
    require(payload.items.size <= payload.pageSize) { "History page exceeds page_size" }
    return if (Math.multiplyExact(payload.page.toLong(), payload.pageSize.toLong()) < payload.total.toLong()) {
        Math.addExact(payload.page, 1)
    } else {
        null
    }
}

class RealtimeSession(
    private val database: AppDatabase,
    private val deliveryStore: LocalDeliveryStore,
    private val attachmentDrafts: AttachmentDraftStore,
    private val preferences: AppPreferences,
    private val deviceKeys: DeviceKeyStore,
    private val scope: CoroutineScope,
    allowInsecureTransport: Boolean,
) : RealtimeSocketListener {
    private data class PendingSyncCommand(
        val generation: Long,
        val type: String,
        val sessionId: String?,
        val page: Int?,
    )

    private data class PendingPairing(
        val qr: PairingQrPayload,
        val keyAlias: String,
        val claim: PairClaimMaterial,
    )

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutex = Mutex()
    private val attachmentOperations = AttachmentOperationOwner()
    private val socket = RealtimeWebSocketClient(this, allowInsecureTransport)
    private val outboxFlight = SingleFlightOutbox()
    private val uploads = AttachmentUploadCoordinator(
        dao = database.attachmentTransfers(),
        sourceFile = attachmentDrafts::fileFor,
        sendCommand = ::sendAttachmentCommand,
        sendBinary = socket::sendBinary,
        onTransportUnavailable = ::scheduleReconnect,
        onUploadFailed = { message ->
            mutableState.value = mutableState.value.copy(errorMessage = message)
        },
    )
    private val mutableState = MutableStateFlow(MobileSessionState())
    val state: StateFlow<MobileSessionState> = mutableState.asStateFlow()

    private var profile: ServerProfileEntity? = null
    private var pendingPairing: PendingPairing? = null
    private var pairingConfirmationGeneration: Long? = null
    private val challengedCandidates = mutableSetOf<SocketCandidateId>()
    private val candidateEndpoints = mutableMapOf<SocketCandidateId, ServerEndpoint>()
    private var activeCandidate: SocketCandidateId? = null
    private var activeEpoch: Long? = null
    private var retryCount = 0
    private var reconnectJob: Job? = null
    private var phaseDeadlineJob: Job? = null
    private var phaseDeadline: ConnectionPhaseDeadline? = null
    private var ackJob: Job? = null
    private var pendingAckCount = 0
    private var pendingAckSeq = 0L
    private var syncGeneration = 0L
    private var completedSyncGeneration = 0L
    private var projectionSyncGeneration: Long? = null
    private val pendingSyncCommands = mutableMapOf<String, PendingSyncCommand>()
    private val requestedHistoryPages = mutableSetOf<Triple<Long, String, Int>>()

    fun start() {
        scope.launch {
            attachmentDrafts.reconcile()
            mutex.withLock {
                val settings = preferences.settings.first()
                profile = settings.currentServerId?.let { database.serverProfiles().get(it) }
                val selected = profile?.let { currentProfile ->
                    settings.currentSessionId?.let { sessionId ->
                        database.conversations().getForServer(currentProfile.serverId, sessionId)?.sessionId
                    }
                }
                if (selected != settings.currentSessionId) preferences.selectSession(null)
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

    fun beginPairing(rawQr: String) {
        scope.launch {
            try {
                mutex.withLock {
                    val qr = PairingQrDecoder.decode(rawQr)
                    check(profile == null) { "Remove the current server before pairing another one" }
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
                ackJob?.cancel()
                ackJob = null
                socket.close(reason = "user requested re-pairing")
                pendingPairing = null
                profile = null
                resetGenerationState()
                preferences.selectServer(null)
                preferences.selectSession(null)
                mutableState.value = MobileSessionState(
                    initialized = true,
                    scanGeneration = mutableState.value.scanGeneration + 1,
                )
            }
        }
    }

    /** 把系统文档复制为可跨重启续传的附件草稿。 */
    fun addAttachments(uris: List<Uri>) {
        scope.launch {
            try {
                val target = mutex.withLock {
                    val currentProfile = requireNotNull(profile) { "Pair a server before attaching" }
                    currentProfile.serverId to ensureCurrentSession(currentProfile)
                }
                attachmentOperations.perform {
                    attachmentDrafts.import(
                        serverId = target.first,
                        sessionId = target.second,
                        uris = uris,
                        now = System.currentTimeMillis(),
                    )
                }
                mutex.withLock {
                    if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                        uploads.resumeIfIdle(target.first)
                    }
                }
            } catch (error: IllegalArgumentException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = error.message)
                }
            } catch (error: java.io.IOException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = "读取附件失败：${error.message}")
                }
            } catch (error: SecurityException) {
                mutex.withLock {
                    mutableState.value = mutableState.value.copy(errorMessage = "没有读取附件的权限")
                }
            }
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
            attachmentOperations.perform {
                attachmentDrafts.retry(attachmentId, System.currentTimeMillis())
            }
            mutex.withLock {
                profile?.serverId?.let { serverId ->
                    if (mutableState.value.connection.phase == ConnectionPhase.READY) {
                        uploads.resumeIfIdle(serverId)
                    }
                }
            }
        }
    }

    /** 创建本地消息和 outbox 命令，并在链路可用时立即发送。 */
    fun sendMessage(
        text: String,
        expectedAttachmentIds: List<String>,
        onPersisted: (Boolean) -> Unit = {},
    ) {
        scope.launch {
            withSendResult(onPersisted) { reportResult ->
                attachmentOperations.perform {
                    mutex.withLock {
                        // 1. 确定当前手机会话和点击时可见的附件集合
                        val currentProfile = requireNotNull(profile) { "Pair a server before sending" }
                        val body = validateOutgoingMessageLength(text)
                        val sessionId = ensureCurrentSession(currentProfile)
                        require(MOBILE_SESSION.matches(sessionId)) { "Invalid mobile session_id" }
                        val attachments = database.attachmentTransfers().drafts(currentProfile.serverId, sessionId)
                        if (!attachmentDraftMatchesExpected(
                                attachments.map { it.attachmentId },
                                expectedAttachmentIds,
                            )
                        ) {
                            mutableState.value = mutableState.value.copy(
                                errorMessage = "附件草稿已变化，请确认后重试",
                            )
                            reportResult(false)
                            return@withLock
                        }
                        require(body.isNotEmpty() || attachments.isNotEmpty()) { "消息和附件不能同时为空" }
                        require(attachments.all { it.state == "ready" }) { "请等待附件上传完成" }

                        // 2. 持久化消息和可重放命令
                        val now = System.currentTimeMillis()
                        val attachmentIds = attachments.map { it.attachmentId }
                        val displayText = body.ifBlank {
                            attachments.joinToString("、", prefix = "附件：") { it.filename }
                        }
                        val pending = preparePendingMessageSend(
                            serverId = currentProfile.serverId,
                            sessionId = sessionId,
                            body = body,
                            now = now,
                            mediaRefs = attachmentIds,
                            displayText = displayText,
                        )
                        deliveryStore.enqueueMessage(
                            conversation = conversationForMessage(
                                currentProfile,
                                sessionId,
                                displayText,
                                now,
                            ),
                            message = pending.message,
                            command = pending.command,
                            attachmentIds = attachmentIds,
                        )
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
                val now = System.currentTimeMillis()
                val sessionId = "mobile:${UUID.randomUUID()}"
                database.conversations().upsert(
                    ConversationEntity(sessionId, currentProfile.serverId, "新对话", now),
                )

                // 2. 切换当前会话
                preferences.selectSession(sessionId)
                mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
            }
        }
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

                // 2. 持久化选择
                preferences.selectSession(sessionId)
                mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
            }
        }
    }

    override fun onOpen(candidateId: SocketCandidateId, endpoint: ServerEndpoint) {
        scope.launch {
            mutex.withLock {
                if (candidateId.generation != currentGeneration()) return@withLock
                candidateEndpoints[candidateId] = endpoint
                if (
                    !shouldApplyCandidateOpen(
                        connectionPhase = mutableState.value.connection.phase,
                        hasActiveCandidate = activeCandidate != null,
                        candidateGeneration = candidateId.generation,
                        pairingConfirmationGeneration = pairingConfirmationGeneration,
                    )
                ) {
                    if (activeCandidate != null) {
                        socket.rejectPreAuth(candidateId, 4406, "late candidate after authentication")
                    }
                    return@withLock
                }
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
        scope.launch {
            mutex.withLock {
                if (!shouldAcceptCandidateFrame(currentGeneration(), activeCandidate, candidateId)) {
                    if (candidateId.generation == currentGeneration() && activeCandidate != null) {
                        socket.rejectPreAuth(candidateId, 4406, "late candidate after authentication")
                    }
                    return@withLock
                }
                try {
                    handleEnvelope(candidateId, envelope)
                } catch (error: IllegalArgumentException) {
                    val message = "连接协议校验失败：${error.message}"
                    mutableState.value = mutableState.value.copy(
                        errorMessage = message,
                    )
                    if (candidateId == activeCandidate) {
                        check(socket.closeActive(candidateId, 4406, "invalid authenticated server frame")) {
                            "Active realtime candidate disappeared during protocol rejection"
                        }
                        scheduleReconnect(message)
                    } else {
                        socket.rejectPreAuth(candidateId, 4406, "invalid pre-auth server frame")
                    }
                }
            }
        }
    }

    override fun onClosed(candidateId: SocketCandidateId, code: Int, reason: String) {
        scope.launch {
            mutex.withLock {
                if (candidateId != activeCandidate) return@withLock
                val action = terminalProtocolAction(code, outboxFlight.commandId != null)
                if (action == null) {
                    scheduleReconnect("连接关闭：$code $reason")
                } else {
                    handleTerminalProtocolClose(code, action)
                }
            }
        }
    }

    override fun onFailure(candidateId: SocketCandidateId, error: Throwable) {
        scope.launch {
            mutex.withLock {
                if (candidateId == activeCandidate) scheduleReconnect(error.message ?: "连接失败")
            }
        }
    }

    override fun onProtocolFailure(candidateId: SocketCandidateId, error: IllegalArgumentException) {
        scope.launch {
            mutex.withLock {
                if (activeCandidate == null || candidateId == activeCandidate) {
                    mutableState.value = mutableState.value.copy(errorMessage = error.message)
                }
            }
        }
    }

    override fun onRaceExhausted(generation: Long, error: Throwable) {
        scope.launch {
            mutex.withLock {
                if (generation == currentGeneration()) scheduleReconnect(error.message ?: "所有 endpoint 均不可用")
            }
        }
    }

    private suspend fun handleEnvelope(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (candidateId.generation != currentGeneration()) return
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
        preferences.selectServer(saved.serverId)
        profile = saved
        pendingPairing = null
        pairingConfirmationGeneration = null
        mutableState.value = MobileSessionState(
            initialized = true,
            connection = ConnectionState(phase = ConnectionPhase.CONNECTING),
            hasProfile = true,
            serverId = saved.serverId,
        )
        socket.close(reason = "pairing accepted; reconnecting with device proof")
        connectProfile(saved)
    }

    private suspend fun handleAuthAccepted(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        require(candidateId in challengedCandidates) { "auth.accepted arrived before a verified challenge" }
        require(envelope.kind == WireKind.CONTROL) { "auth.accepted must be a control frame" }
        val accepted = ProtocolCodec.decodePayload<AuthAcceptedPayload>(envelope.payload)
        require(accepted.connectionEpoch == envelope.connectionEpoch) { "auth.accepted epoch mismatch" }
        val currentProfile = requireNotNull(profile)
        require(accepted.deviceId == currentProfile.deviceId) { "auth.accepted device mismatch" }
        if (!socket.promote(candidateId)) return
        activeCandidate = candidateId
        activeEpoch = accepted.connectionEpoch
        outboxFlight.reset()
        syncGeneration += 1
        completedSyncGeneration = 0
        projectionSyncGeneration = null
        pendingSyncCommands.clear()
        requestedHistoryPages.clear()
        retryCount = 0
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
                        put("active_turns", kotlinx.serialization.json.JsonArray(emptyList()))
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
            errorMessage = null,
        )
        armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.SYNC)
        database.outbox().resetInFlight(currentProfile.serverId)
    }

    private suspend fun handleAuthenticatedFrame(candidateId: SocketCandidateId, envelope: WireEnvelope) {
        if (candidateId != activeCandidate) return
        require(envelope.connectionEpoch == activeEpoch) { "Authenticated frame epoch mismatch" }
        when (envelope.kind) {
            WireKind.EVENT -> {
                val currentProfile = requireNotNull(profile)
                val eventSeq = deliveryStore.applyEvent(
                    serverId = currentProfile.serverId,
                    deviceId = currentProfile.deviceId,
                    envelope = envelope,
                    updatedAt = System.currentTimeMillis(),
                    preservedSessionId = mutableState.value.currentSessionId,
                )
                recordAck(eventSeq)
                when (envelope.type) {
                    "sync.completed" -> {
                        if (completedSyncGeneration == syncGeneration) return
                        completedSyncGeneration = syncGeneration
                        beginProjectionSync()
                    }
                    "sync.reset_required" -> beginResetRebuild()
                    "session.list" -> {
                        if (hasPendingSyncCommand("session.list")) {
                            reconcileCatalogAndRequestHistory(currentProfile.serverId, envelope)
                        }
                    }
                    "history.page" -> requestNextHistoryPage(envelope)
                    "attachment.progress" -> uploads.onProgress(
                        ProtocolCodec.decodePayload(envelope.payload),
                    )
                    "connection.degraded" -> mutableState.value = mutableState.value.copy(
                        connection = mutableState.value.connection.copy(phase = ConnectionPhase.DEGRADED),
                    )
                }
            }
            WireKind.REPLY -> {
                if (uploads.onReply(envelope)) return
                val id = requireNotNull(envelope.id)
                if (id in pendingSyncCommands && envelope.type.endsWith(".error")) {
                    val pending = requireNotNull(pendingSyncCommands.remove(id))
                    require(pending.generation == syncGeneration) { "收到旧 generation 的历史同步错误" }
                    require(envelope.type == "${pending.type}.error") { "历史同步错误类型不匹配" }
                    scheduleReconnect(
                        envelope.payload["message"]?.toString()?.trim('"') ?: "历史同步失败",
                    )
                    return
                }
                when (envelope.type) {
                    "message.send.ok" -> {
                        require(id == outboxFlight.commandId) { "收到非活动 outbox 命令的 reply: $id" }
                        attachmentDrafts.deleteSentFiles(
                            deliveryStore.acknowledgeOutbox(id, System.currentTimeMillis()),
                        )
                        outboxFlight.complete(id)
                        flushOutbox()
                    }
                    "message.send.error" -> {
                        require(id == outboxFlight.commandId) { "收到非活动 outbox 命令的 reply: $id" }
                        val code = envelope.payload["code"]?.jsonPrimitive?.contentOrNull
                        val message = envelope.payload["message"]?.jsonPrimitive?.contentOrNull ?: "消息发送失败"
                        when (messageSendFailureDisposition(code)) {
                            OutboxFailureDisposition.RETRY_ORIGINAL -> {
                                deliveryStore.retryOutbox(id)
                                outboxFlight.complete(id)
                                check(socket.closeActive(candidateId, 1012, "command outcome unknown"))
                                scheduleReconnect(message)
                            }
                            OutboxFailureDisposition.FAIL -> {
                                deliveryStore.failOutbox(id, System.currentTimeMillis())
                                outboxFlight.complete(id)
                                mutableState.value = mutableState.value.copy(errorMessage = message)
                                flushOutbox()
                            }
                        }
                    }
                    "session.list.ok", "history.get.ok" -> completeSyncReply(envelope)
                    else -> {
                        require(envelope.type.endsWith(".error")) { "Unexpected reply type: ${envelope.type}" }
                        mutableState.value = mutableState.value.copy(
                            errorMessage = envelope.payload["message"]?.toString()?.trim('"') ?: "历史同步失败",
                        )
                    }
                }
            }
            else -> error("Unexpected authenticated server frame: ${envelope.kind}")
        }
        if (mutableState.value.connection.phase == ConnectionPhase.SYNCING) {
            armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.SYNC)
        }
    }

    private fun requestSessionList() {
        if (hasPendingSyncCommand("session.list")) return
        sendSyncCommand(type = "session.list", sessionId = null, payload = buildJsonObject {})
    }

    private fun hasPendingSyncCommand(type: String): Boolean =
        pendingSyncCommands.values.any { it.generation == syncGeneration && it.type == type }

    /** 用本轮主动目录对账投影，再拉取所有非空会话历史。 */
    private suspend fun reconcileCatalogAndRequestHistory(serverId: String, envelope: WireEnvelope) {
        val payload = ProtocolCodec.decodePayload<SessionListPayload>(envelope.payload)
        val historySessionIds = historySessionsToFetch(payload)
        deliveryStore.reconcileSessionCatalog(
            serverId = serverId,
            remoteSessionIds = payload.items.mapTo(mutableSetOf(), RemoteSessionSummary::sessionId),
            preservedSessionId = mutableState.value.currentSessionId,
        )
        historySessionIds.forEach { requestHistoryPage(it, page = 1) }
    }

    private fun requestNextHistoryPage(envelope: WireEnvelope) {
        val sessionId = requireNotNull(envelope.sessionId) { "History page has no session_id" }
        val payload = ProtocolCodec.decodePayload<HistoryPagePayload>(envelope.payload)
        if (Triple(syncGeneration, sessionId, payload.page) !in requestedHistoryPages) return
        nextHistoryPage(payload)?.let { requestHistoryPage(sessionId, it) }
    }

    private fun requestHistoryPage(sessionId: String, page: Int) {
        if (!requestedHistoryPages.add(Triple(syncGeneration, sessionId, page))) return
        sendSyncCommand(
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
    ) {
        val epoch = requireNotNull(activeEpoch) { "History sync requires an authenticated connection" }
        val candidate = requireNotNull(activeCandidate) { "History sync requires an active endpoint" }
        val commandId = Ulid.next()
        pendingSyncCommands[commandId] = PendingSyncCommand(
            generation = syncGeneration,
            type = type,
            sessionId = sessionId,
            page = payload["page"]?.jsonPrimitive?.longOrNull?.also {
                require(it in 1..Int.MAX_VALUE) { "历史同步 page 超出范围" }
            }?.toInt(),
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
        }
    }

    private suspend fun completeSyncReply(envelope: WireEnvelope) {
        val commandId = requireNotNull(envelope.id)
        val pending = requireNotNull(pendingSyncCommands.remove(commandId)) {
            "收到未知历史同步 reply: $commandId"
        }
        require(pending.generation == syncGeneration) { "收到旧 generation 的历史同步 reply" }
        require(envelope.type == "${pending.type}.ok") { "历史同步 reply 类型不匹配" }
        require(envelope.sessionId == pending.sessionId) { "历史同步 reply session 不匹配" }
        if (pending.page != null) {
            require(envelope.payload["page"]?.jsonPrimitive?.longOrNull == pending.page.toLong()) {
                "历史同步 reply page 不匹配"
            }
        }
        finishProjectionSyncIfComplete()
    }

    private fun beginProjectionSync() {
        projectionSyncGeneration = syncGeneration
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.SYNCING),
            errorMessage = null,
        )
        requestSessionList()
    }

    /** 从 reset event 的新 cursor 开始重建当前服务端投影视图。 */
    private fun beginResetRebuild() {
        syncGeneration += 1
        completedSyncGeneration = syncGeneration
        pendingSyncCommands.clear()
        requestedHistoryPages.clear()
        beginProjectionSync()
    }

    private suspend fun finishProjectionSyncIfComplete() {
        if (projectionSyncGeneration != syncGeneration || pendingSyncCommands.isNotEmpty()) return
        projectionSyncGeneration = null
        cancelPhaseDeadline()
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.READY),
        )
        uploads.onConnectionReady(requireNotNull(profile).serverId)
        flushOutbox()
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

    private suspend fun flushOutbox() {
        if (outboxFlight.commandId != null) return
        val currentProfile = requireNotNull(profile)
        val epoch = activeEpoch ?: return
        val candidate = activeCandidate ?: return
        val command = database.outbox().pending(currentProfile.serverId).firstOrNull() ?: return
        val stored = ProtocolCodec.decode(command.envelopeJson)
        val wire = stored.copy(connectionEpoch = epoch)
        deliveryStore.markOutboxAttempt(command.commandId, System.currentTimeMillis())
        check(outboxFlight.claim(command.commandId)) { "Outbox acquired more than one active command" }
        if (!socket.send(candidate, wire)) {
            deliveryStore.retryOutbox(command.commandId)
            outboxFlight.complete(command.commandId)
            scheduleReconnect("消息命令未进入 WebSocket 队列")
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
        reconnectJob?.cancel()
        resetGenerationState()
        currentGenerationValue = socket.connectRace(
            qr.lanEndpoints.lanEndpoints(qr.tlsSpkiPins),
            qr.tunnelEndpoints.tunnelEndpoints(),
        )
    }

    private fun connectProfile(value: ServerProfileEntity) {
        reconnectJob?.cancel()
        resetGenerationState()
        val pins = json.decodeFromString<List<String>>(value.tlsSpkiPinsJson)
        val lan = json.decodeFromString<List<String>>(value.lanEndpointsJson).lanEndpoints(pins)
        val tunnel = json.decodeFromString<List<String>>(value.tunnelEndpointsJson).tunnelEndpoints()
        currentGenerationValue = socket.connectRace(lan, tunnel)
        mutableState.value = mutableState.value.copy(
            connection = ConnectionState(phase = ConnectionPhase.CONNECTING, retryCount = retryCount),
            hasProfile = true,
            serverId = value.serverId,
        )
    }

    /** 为当前连接代际设置唯一的应用层阶段截止时间。 */
    private fun armPhaseDeadline(generation: Long, phase: ConnectionDeadlinePhase) {
        require(generation == currentGeneration()) { "连接阶段截止时间属于旧代际" }
        if (phase != ConnectionDeadlinePhase.SYNC && pairingConfirmationGeneration == generation) return
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

    private fun cancelPhaseDeadline() {
        phaseDeadlineJob?.cancel()
        phaseDeadlineJob = null
        phaseDeadline = null
    }

    private suspend fun conversationForMessage(
        currentProfile: ServerProfileEntity,
        sessionId: String,
        body: String,
        now: Long,
    ): ConversationEntity {
        val current = database.conversations().get(sessionId)
        require(current == null || current.serverId == currentProfile.serverId) {
            "Current session belongs to another server"
        }
        val title = if (current == null || current.title == "新对话") {
            body.lineSequence().first().take(32)
        } else {
            current.title
        }
        return ConversationEntity(sessionId, currentProfile.serverId, title, now)
    }

    private suspend fun ensureCurrentSession(currentProfile: ServerProfileEntity): String {
        val existing = mutableState.value.currentSessionId
        if (existing != null) return existing
        val sessionId = "mobile:${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        database.conversations().upsert(
            ConversationEntity(sessionId, currentProfile.serverId, "新对话", now),
        )
        preferences.selectSession(sessionId)
        mutableState.value = mutableState.value.copy(currentSessionId = sessionId)
        return sessionId
    }

    private fun scheduleReconnect(message: String) {
        cancelPhaseDeadline()
        if (reconnectJob?.isActive == true) return
        activeCandidate = null
        activeEpoch = null
        outboxFlight.reset()
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
        pendingSyncCommands.clear()
        requestedHistoryPages.clear()
        projectionSyncGeneration = null
        uploads.onDisconnected()
        retryCount += 1
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(
                phase = ConnectionPhase.DEGRADED,
                retryCount = retryCount,
            ),
            errorMessage = message,
        )
        reconnectJob = scope.launch {
            delay(FullJitterBackoff.nextDelayMillis(retryCount))
            mutex.withLock {
                pendingPairing?.let { connectQr(it.qr) } ?: profile?.let(::connectProfile)
            }
        }
    }

    /** 隔离被服务端拒绝的命令，或停止继续撞击不兼容协议。 */
    private suspend fun handleTerminalProtocolClose(code: Int, action: TerminalProtocolAction) {
        cancelPhaseDeadline()
        val currentProfile = requireNotNull(profile)
        val commandId = outboxFlight.commandId
        if (action == TerminalProtocolAction.FAIL_ACTIVE_COMMAND && commandId != null) {
            deliveryStore.failOutbox(commandId, System.currentTimeMillis())
            outboxFlight.complete(commandId)
            scheduleReconnect("消息格式无效，已标记发送失败")
            return
        }

        database.outbox().resetInFlight(currentProfile.serverId)
        outboxFlight.reset()
        activeCandidate = null
        activeEpoch = null
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
        uploads.onDisconnected()
        mutableState.value = mutableState.value.copy(
            connection = mutableState.value.connection.copy(phase = ConnectionPhase.CLOSED),
            errorMessage = "协议不兼容（$code），请升级电脑端或手机客户端",
        )
    }

    private fun resetGenerationState() {
        cancelPhaseDeadline()
        challengedCandidates.clear()
        candidateEndpoints.clear()
        activeCandidate = null
        activeEpoch = null
        outboxFlight.reset()
        pendingSyncCommands.clear()
        requestedHistoryPages.clear()
        projectionSyncGeneration = null
        uploads.onDisconnected()
    }

    private fun control(type: String, payload: kotlinx.serialization.json.JsonObject) = WireEnvelope(
        v = WIRE_PROTOCOL_VERSION,
        kind = WireKind.CONTROL,
        type = type,
        payload = payload,
    )

    private var currentGenerationValue = 0L

    private fun currentGeneration(): Long = currentGenerationValue

    private fun List<String>.lanEndpoints(pins: List<String>): List<ServerEndpoint> =
        map { ServerEndpoint(it, pins, EndpointRoute.LAN) }

    private fun List<String>.tunnelEndpoints(): List<ServerEndpoint> =
        map { ServerEndpoint(it, emptyList(), EndpointRoute.TUNNEL) }

    private companion object {
        val CAPABILITIES = listOf("chat", "streaming", "tools", "proactive", "attachments-v1")
        val MOBILE_SESSION = Regex("^mobile:(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
        const val HISTORY_PAGE_SIZE = 10
        const val ACK_DELAY_MILLIS = 100L
        const val ACK_EVENT_LIMIT = 32
    }
}
