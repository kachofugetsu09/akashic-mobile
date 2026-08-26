package com.akashic.mobile.data.realtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStopCoordinatorTest {
    @Test
    fun duplicateStopAndReconnectReuseOneCommandIdentity() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val persisted = mutableListOf<TurnStopRequest>()
        var changes = 0
        val coordinator = coordinator(
            sent = sent,
            onPersist = persisted::add,
            onStateChanged = { changes += 1 },
        )
        coordinator.onTurnStarted("akashic:one", "turn-1")

        val first = coordinator.requestStop("akashic:one")
        val duplicate = coordinator.requestStop("akashic:one")
        coordinator.onConnectionReady()

        assertEquals(first, duplicate)
        assertEquals(listOf(first, first), sent)
        assertEquals(listOf(first), persisted)
        assertTrue(coordinator.isStopping("akashic:one"))
        assertTrue(changes >= 2)
    }

    @Test
    fun stopIntentIsPersistedBeforeTransportSend() = runBlocking {
        val order = mutableListOf<String>()
        val coordinator = TurnStopCoordinator(
            send = {
                order += "send"
                true
            },
            onPersist = { order += "persist" },
            onRemovePersisted = {},
            onAuthoritativeTerminal = {},
            onTransportUnavailable = {},
            onError = {},
            onStateChanged = {},
        )
        coordinator.onTurnStarted("akashic:one", "turn-1")

        coordinator.requestStop("akashic:one")

        assertEquals(listOf("persist", "send"), order)
    }

    @Test
    fun resumeUsesTurnIdsWhileUiKeepsSessionIds() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("akashic:one", "turn-1")
        coordinator.onTurnStarted("akashic:two", "turn-2")

        assertEquals(setOf("akashic:one", "akashic:two"), coordinator.activeSessionIds())
        assertEquals(setOf("turn-1", "turn-2"), coordinator.activeTurnIds())

        coordinator.onTurnTerminal("akashic:one", "turn-1")
        assertEquals(setOf("akashic:two"), coordinator.activeSessionIds())
        assertEquals(setOf("turn-2"), coordinator.activeTurnIds())
    }

    @Test
    fun outputCompletedClearsOnTerminalAndNewTurn() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("akashic:one", "turn-1")
        assertFalse(coordinator.isOutputCompleted("akashic:one"))

        coordinator.onOutputCompleted("akashic:one", "turn-1")
        assertTrue(coordinator.isOutputCompleted("akashic:one"))

        coordinator.onTurnTerminal("akashic:one", "turn-1")
        assertFalse(coordinator.isOutputCompleted("akashic:one"))

        coordinator.onTurnStarted("akashic:one", "turn-2")
        assertFalse(coordinator.isOutputCompleted("akashic:one"))
        coordinator.onOutputCompleted("akashic:one", "turn-2")
        assertTrue(coordinator.isOutputCompleted("akashic:one"))
    }

    @Test
    fun outputCompletedForInactiveTurnFailsLoud() {
        val coordinator = coordinator()
        assertThrows(IllegalArgumentException::class.java) {
            coordinator.onOutputCompleted("akashic:one", "turn-1")
        }
    }

    @Test
    fun processRestartRestoresAndReplaysPersistedStopIdentity() = runBlocking {
        val persisted = mutableListOf<TurnStopRequest>()
        val first = coordinator(onPersist = persisted::add)
        first.onTurnStarted("akashic:one", "turn-1")
        val request = first.requestStop("akashic:one")

        val replayed = mutableListOf<TurnStopRequest>()
        val restored = coordinator(
            sent = replayed,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
        )
        restored.restore(mapOf("akashic:one" to "turn-1"), persisted.toList())
        restored.onConnectionReady()

        assertEquals(listOf(request), replayed)
        assertTrue(restored.isStopping("akashic:one"))
        restored.onTurnTerminal("akashic:one", "turn-1")
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun confirmedStopRemovesDurableIntentWithoutReplayingIt() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val persisted = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(
            sent = sent,
            onPersist = persisted::add,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
        )
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        assertTrue(coordinator.onReply(okReply(request)))
        coordinator.onConnectionReady()

        assertTrue(persisted.isEmpty())
        assertEquals(listOf(request), sent)
        assertTrue(coordinator.isStopping("akashic:one"))
    }

    @Test
    fun restoreDeletesStopWhoseTurnAlreadyReachedTerminalState() = runBlocking {
        val request = TurnStopRequest("stop-1", "akashic:one", "turn-1")
        val removed = mutableListOf<String>()
        val coordinator = coordinator(onRemovePersisted = removed::add)

        coordinator.restore(emptyMap(), listOf(request))

        assertEquals(listOf("stop-1"), removed)
        assertFalse(coordinator.isStopping("akashic:one"))
    }

    @Test
    fun stopReplyClearsPendingAndProtocolErrorIsVisible() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val errors = mutableListOf<String>()
        val persisted = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(
            sent = sent,
            onPersist = persisted::add,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
            onError = errors::add,
        )
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        assertTrue(
            coordinator.onReply(
                WireEnvelope(
                    v = 1,
                    kind = WireKind.REPLY,
                    type = "turn.stop.error",
                    id = request.commandId,
                    sessionId = request.sessionId,
                    turnId = request.turnId,
                    payload = buildJsonObject { put("message", "目标 turn 已结束") },
                ),
            ),
        )
        assertFalse(coordinator.isStopping("akashic:one"))
        assertTrue(persisted.isEmpty())
        assertEquals(listOf("目标 turn 已结束"), errors)
    }

    @Test
    fun turnNotActiveReplyKeepsUnconfirmedActiveProjection() = runBlocking {
        val reconciled = mutableListOf<TurnStopRequest>()
        val errors = mutableListOf<String>()
        val persisted = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(
            onPersist = persisted::add,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
            onAuthoritativeTerminal = reconciled::add,
            onError = errors::add,
        )
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        assertTrue(coordinator.onReply(errorReply(request, code = "turn_not_active")))

        assertTrue(reconciled.isEmpty())
        assertEquals("turn-1", coordinator.activeTurnId("akashic:one"))
        assertTrue(persisted.isEmpty())
        assertEquals(listOf("当前会话没有正在生成的内容"), errors)
    }

    @Test
    fun alreadyTerminalNonCompletedOkHealsStaleActiveProjection() = runBlocking {
        val reconciled = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(onAuthoritativeTerminal = reconciled::add)
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        assertTrue(
            coordinator.onReply(
                okReply(
                    request,
                    status = "already_terminal",
                    terminalStatus = "cancelled",
                ),
            ),
        )

        assertEquals(listOf(request), reconciled)
        assertEquals(null, coordinator.activeTurnId("akashic:one"))
    }

    @Test
    fun alreadyCompletedOkWaitsForCanonicalFinalProjection() = runBlocking {
        val reconciled = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(onAuthoritativeTerminal = reconciled::add)
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        assertTrue(
            coordinator.onReply(
                okReply(
                    request,
                    status = "already_terminal",
                    terminalStatus = "completed",
                ),
            ),
        )

        assertTrue(reconciled.isEmpty())
        assertEquals("turn-1", coordinator.activeTurnId("akashic:one"))

        coordinator.onTurnTerminal("akashic:one", "turn-1")

        assertEquals(null, coordinator.activeTurnId("akashic:one"))
    }

    @Test
    fun terminalThenReplyAllowsStoppingNextTurn() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(sent = sent)
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")

        coordinator.onTurnTerminal("akashic:one", "turn-1")

        assertEquals(null, coordinator.activeTurnId("akashic:one"))
        assertFalse(coordinator.isStopping("akashic:one"))
        assertTrue(
            coordinator.onReply(
                WireEnvelope(
                    v = 1,
                    kind = WireKind.REPLY,
                    type = "turn.stop.ok",
                    id = request.commandId,
                    sessionId = request.sessionId,
                    turnId = request.turnId,
                    payload = buildJsonObject { put("status", "interrupted") },
                ),
            ),
        )
        coordinator.onTurnStarted("akashic:one", "turn-2")

        val next = coordinator.requestStop("akashic:one")

        assertEquals("turn-2", next.turnId)
    }

    @Test
    fun replyThenTerminalAllowsStoppingNextTurn() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")
        assertTrue(coordinator.onReply(okReply(request)))

        coordinator.onTurnTerminal("akashic:one", "turn-1")
        coordinator.onTurnStarted("akashic:one", "turn-2")

        assertEquals("turn-2", coordinator.requestStop("akashic:one").turnId)
    }

    @Test
    fun terminalThenErrorAllowsStoppingNextTurn() = runBlocking {
        val errors = mutableListOf<String>()
        val coordinator = coordinator(onError = errors::add)
        coordinator.onTurnStarted("akashic:one", "turn-1")
        val request = coordinator.requestStop("akashic:one")
        coordinator.onTurnTerminal("akashic:one", "turn-1")

        assertTrue(coordinator.onReply(errorReply(request)))
        coordinator.onTurnStarted("akashic:one", "turn-2")

        assertEquals("turn-2", coordinator.requestStop("akashic:one").turnId)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun interruptedTerminalStatusPreservesRealPayloadValues() {
        assertEquals(
            "interrupted",
            interruptedTerminalStatus(buildJsonObject { put("status", "interrupted") }),
        )
        assertEquals("cancelled", interruptedTerminalStatus(buildJsonObject { put("status", "cancelled") }))
        assertEquals("failed", interruptedTerminalStatus(buildJsonObject { put("status", "failed") }))
        assertEquals("idle", interruptedTerminalStatus(buildJsonObject { put("status", "idle") }))
    }

    @Test
    fun interruptedTerminalStatusMissingOrBlankFailsLoud() {
        assertThrows(IllegalArgumentException::class.java) {
            interruptedTerminalStatus(buildJsonObject {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            interruptedTerminalStatus(buildJsonObject { put("status", " ") })
        }
    }

    @Test
    fun interruptedTerminalStatusRejectsStatusOutsideWhitelist() {
        assertThrows(IllegalArgumentException::class.java) {
            interruptedTerminalStatus(buildJsonObject { put("status", "completed") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            interruptedTerminalStatus(buildJsonObject { put("status", "running") })
        }
    }

    @Test
    fun authoritativeTerminalStatusKeepsCompletedOnItsOwnPath() {
        val request = TurnStopRequest("stop-completed", "akashic:one", "turn-1")
        // completed 不进 interruptedTerminalStatus 白名单，仍由 already_terminal 权威路径核对
        assertEquals(
            null,
            authoritativeTerminalStatus(
                okReply(request, status = "already_terminal", terminalStatus = "completed"),
            ),
        )
    }

    private fun okReply(
        request: TurnStopRequest,
        status: String = "interrupted",
        terminalStatus: String? = null,
    ) = WireEnvelope(
        v = 1,
        kind = WireKind.REPLY,
        type = "turn.stop.ok",
        id = request.commandId,
        sessionId = request.sessionId,
        turnId = request.turnId,
        payload = buildJsonObject {
            put("status", status)
            terminalStatus?.let { put("terminal_status", it) }
        },
    )

    private fun errorReply(request: TurnStopRequest, code: String? = null) = WireEnvelope(
        v = 1,
        kind = WireKind.REPLY,
        type = "turn.stop.error",
        id = request.commandId,
        sessionId = request.sessionId,
        turnId = request.turnId,
        payload = buildJsonObject {
            code?.let { put("code", it) }
            put("message", "当前会话没有正在生成的内容")
        },
    )

    private fun coordinator(
        sent: MutableList<TurnStopRequest> = mutableListOf(),
        onPersist: suspend (TurnStopRequest) -> Unit = {},
        onRemovePersisted: suspend (String) -> Unit = {},
        onAuthoritativeTerminal: suspend (TurnStopRequest) -> Unit = {},
        onError: (String) -> Unit = {},
        onStateChanged: () -> Unit = {},
    ) = TurnStopCoordinator(
        send = { request -> sent.add(request) },
        onPersist = onPersist,
        onRemovePersisted = onRemovePersisted,
        onAuthoritativeTerminal = onAuthoritativeTerminal,
        onTransportUnavailable = {},
        onError = onError,
        onStateChanged = onStateChanged,
    )
}
