package com.akashic.mobile.data.realtime

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnStopCoordinatorTest {
    @Test
    fun duplicateStopAndReconnectReuseOneCommandIdentity() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        var changes = 0
        val coordinator = coordinator(sent = sent, onStateChanged = { changes += 1 })
        coordinator.onTurnStarted("mobile:one", "turn-1")

        val first = coordinator.requestStop("mobile:one")
        val duplicate = coordinator.requestStop("mobile:one")
        coordinator.onConnectionReady()

        assertEquals(first, duplicate)
        assertEquals(listOf(first, first), sent)
        assertTrue(coordinator.isStopping("mobile:one"))
        assertTrue(changes >= 2)
    }

    @Test
    fun resumeIdentityUsesTurnIdsInsteadOfSessionIds() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("mobile:one", "turn-1")
        coordinator.onTurnStarted("mobile:two", "turn-2")

        assertEquals(setOf("turn-1", "turn-2"), coordinator.activeTurnIds())
    }

    @Test
    fun processRestartRestoresAndReplaysThePersistedStopIdentity() = runBlocking {
        val persisted = mutableListOf<TurnStopRequest>()
        val first = coordinator(onPersist = persisted::add)
        first.onTurnStarted("mobile:one", "turn-1")
        val request = first.requestStop("mobile:one")

        val replayed = mutableListOf<TurnStopRequest>()
        val restored = coordinator(
            sent = replayed,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
        )
        restored.restore(mapOf("mobile:one" to "turn-1"), persisted)
        restored.onConnectionReady()

        assertEquals(listOf(request), replayed)
        assertTrue(restored.isStopping("mobile:one"))
        restored.onTurnTerminal("mobile:one", "turn-1")
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun stopReplyClearsPendingAndProtocolErrorIsVisible() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val errors = mutableListOf<String>()
        val coordinator = coordinator(sent = sent, onError = errors::add)
        coordinator.onTurnStarted("mobile:one", "turn-1")
        val request = coordinator.requestStop("mobile:one")

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
        assertFalse(coordinator.isStopping("mobile:one"))
        assertEquals(listOf("目标 turn 已结束"), errors)
    }

    @Test
    fun terminalThenReplyAllowsStoppingNextTurn() = runBlocking {
        val sent = mutableListOf<TurnStopRequest>()
        val coordinator = coordinator(sent = sent)
        coordinator.onTurnStarted("mobile:one", "turn-1")
        val request = coordinator.requestStop("mobile:one")

        coordinator.onTurnTerminal("mobile:one", "turn-1")

        assertEquals(null, coordinator.activeTurnId("mobile:one"))
        assertFalse(coordinator.isStopping("mobile:one"))
        assertTrue(coordinator.onReply(okReply(request)))
        coordinator.onTurnStarted("mobile:one", "turn-2")

        val next = coordinator.requestStop("mobile:one")

        assertEquals("turn-2", next.turnId)
    }

    @Test
    fun replyThenTerminalAllowsStoppingNextTurn() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("mobile:one", "turn-1")
        val request = coordinator.requestStop("mobile:one")
        assertTrue(coordinator.onReply(okReply(request)))

        coordinator.onTurnTerminal("mobile:one", "turn-1")
        coordinator.onTurnStarted("mobile:one", "turn-2")

        assertEquals("turn-2", coordinator.requestStop("mobile:one").turnId)
    }

    @Test
    fun terminalThenErrorAllowsStoppingNextTurn() = runBlocking {
        val errors = mutableListOf<String>()
        val coordinator = coordinator(onError = errors::add)
        coordinator.onTurnStarted("mobile:one", "turn-1")
        val request = coordinator.requestStop("mobile:one")
        coordinator.onTurnTerminal("mobile:one", "turn-1")

        assertTrue(coordinator.onReply(errorReply(request)))
        coordinator.onTurnStarted("mobile:one", "turn-2")

        assertEquals("turn-2", coordinator.requestStop("mobile:one").turnId)
        assertTrue(errors.isEmpty())
    }

    private fun okReply(request: TurnStopRequest) = WireEnvelope(
        v = 1,
        kind = WireKind.REPLY,
        type = "turn.stop.ok",
        id = request.commandId,
        sessionId = request.sessionId,
        turnId = request.turnId,
        payload = buildJsonObject { put("status", "interrupted") },
    )

    private fun errorReply(request: TurnStopRequest) = WireEnvelope(
        v = 1,
        kind = WireKind.REPLY,
        type = "turn.stop.error",
        id = request.commandId,
        sessionId = request.sessionId,
        turnId = request.turnId,
        payload = buildJsonObject { put("message", "当前会话没有正在生成的内容") },
    )

    private fun coordinator(
        sent: MutableList<TurnStopRequest> = mutableListOf(),
        onPersist: suspend (TurnStopRequest) -> Unit = {},
        onRemovePersisted: suspend (String) -> Unit = {},
        onClearPersisted: suspend () -> Unit = {},
        onError: (String) -> Unit = {},
        onStateChanged: () -> Unit = {},
    ) = TurnStopCoordinator(
        send = { request -> sent.add(request) },
        onPersist = onPersist,
        onRemovePersisted = onRemovePersisted,
        onClearPersisted = onClearPersisted,
        onTransportUnavailable = {},
        onError = onError,
        onStateChanged = onStateChanged,
    )
}
