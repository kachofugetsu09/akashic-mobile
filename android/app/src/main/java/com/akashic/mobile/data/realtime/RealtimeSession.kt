package com.akashic.mobile.data.realtime

import android.os.Build
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
import kotlinx.serialization.json.put

data class MobileSessionState(
    val initialized: Boolean = false,
    val scanGeneration: Long = 0,
    val connection: ConnectionState = ConnectionState(),
    val hasProfile: Boolean = false,
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

internal fun validateOutgoingMessageText(text: String): String {
    val body = text.trim()
    require(body.isNotEmpty()) { "Message text is empty" }
    require(outgoingMessageTextLength(body) <= MAX_MESSAGE_TEXT_LENGTH) {
        "Message text exceeds $MAX_MESSAGE_TEXT_LENGTH characters"
    }
    return body
}

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

class RealtimeSession(
    private val database: AppDatabase,
    private val deliveryStore: LocalDeliveryStore,
    private val preferences: AppPreferences,
    private val deviceKeys: DeviceKeyStore,
    private val scope: CoroutineScope,
    allowInsecureTransport: Boolean,
) : RealtimeSocketListener {
    private data class PendingPairing(
        val qr: PairingQrPayload,
        val keyAlias: String,
        val claim: PairClaimMaterial,
    )

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val mutex = Mutex()
    private val socket = RealtimeWebSocketClient(this, allowInsecureTransport)
    private val outboxFlight = SingleFlightOutbox()
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

    fun start() {
        scope.launch {
            mutex.withLock {
                val settings = preferences.settings.first()
                profile = settings.currentServerId?.let { database.serverProfiles().get(it) }
                    ?: database.serverProfiles().first()
                val selected = settings.currentSessionId
                mutableState.value = mutableState.value.copy(
                    initialized = true,
                    hasProfile = profile != null,
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

    fun sendMessage(text: String) {
        val body = validateOutgoingMessageText(text)
        scope.launch {
            mutex.withLock {
                val currentProfile = requireNotNull(profile) { "Pair a server before sending" }
                val sessionId = mutableState.value.currentSessionId
                    ?: "mobile:${UUID.randomUUID()}".also {
                        preferences.selectSession(it)
                        mutableState.value = mutableState.value.copy(currentSessionId = it)
                    }
                require(MOBILE_SESSION.matches(sessionId)) { "Invalid mobile session_id" }
                val now = System.currentTimeMillis()
                val pending = preparePendingMessageSend(currentProfile.serverId, sessionId, body, now)
                deliveryStore.enqueueMessage(
                    conversation = ConversationEntity(sessionId, currentProfile.serverId, "新对话", now),
                    message = pending.message,
                    command = pending.command,
                )
                if (mutableState.value.connection.phase == ConnectionPhase.READY) flushOutbox()
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
                )
                recordAck(eventSeq)
                when (envelope.type) {
                    "sync.completed" -> {
                        mutableState.value = mutableState.value.copy(
                            connection = mutableState.value.connection.copy(phase = ConnectionPhase.READY),
                        )
                        cancelPhaseDeadline()
                        flushOutbox()
                    }
                    "connection.degraded" -> mutableState.value = mutableState.value.copy(
                        connection = mutableState.value.connection.copy(phase = ConnectionPhase.DEGRADED),
                    )
                    "sync.reset_required" -> mutableState.value = mutableState.value.copy(
                        errorMessage = "服务端要求重新同步；当前版本尚不支持历史全量重建",
                    )
                }
            }
            WireKind.REPLY -> {
                val id = requireNotNull(envelope.id)
                require(id == outboxFlight.commandId) { "收到非活动 outbox 命令的 reply: $id" }
                if (envelope.type.endsWith(".ok")) {
                    deliveryStore.acknowledgeOutbox(id, System.currentTimeMillis())
                    outboxFlight.complete(id)
                    flushOutbox()
                } else if (envelope.type.endsWith(".error")) {
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
            }
            else -> error("Unexpected authenticated server frame: ${envelope.kind}")
        }
        if (mutableState.value.connection.phase == ConnectionPhase.SYNCING) {
            armPhaseDeadline(candidateId.generation, ConnectionDeadlinePhase.SYNC)
        }
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

    private fun scheduleReconnect(message: String) {
        cancelPhaseDeadline()
        if (reconnectJob?.isActive == true) return
        activeCandidate = null
        activeEpoch = null
        outboxFlight.reset()
        ackJob?.cancel()
        ackJob = null
        pendingAckCount = 0
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
        val CAPABILITIES = listOf("chat", "streaming", "tools", "proactive")
        val MOBILE_SESSION = Regex("^mobile:(?:[0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")
        const val ACK_DELAY_MILLIS = 100L
        const val ACK_EVENT_LIMIT = 32
    }
}
