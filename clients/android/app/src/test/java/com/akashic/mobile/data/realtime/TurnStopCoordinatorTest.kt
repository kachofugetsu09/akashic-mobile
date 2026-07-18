package com.akashic.mobile.data.realtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        coordinator.onTurnStarted("mobile:one", "turn-1")

        val first = coordinator.requestStop("mobile:one")
        val duplicate = coordinator.requestStop("mobile:one")
        coordinator.onConnectionReady()

        assertEquals(first, duplicate)
        assertEquals(listOf(first, first), sent)
        assertEquals(listOf(first), persisted)
        assertTrue(coordinator.isStopping("mobile:one"))
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
            onTransportUnavailable = {},
            onError = {},
            onStateChanged = {},
        )
        coordinator.onTurnStarted("mobile:one", "turn-1")

        coordinator.requestStop("mobile:one")

        assertEquals(listOf("persist", "send"), order)
    }

    @Test
    fun resumeUsesTurnIdsWhileUiKeepsSessionIds() = runBlocking {
        val coordinator = coordinator()
        coordinator.onTurnStarted("mobile:one", "turn-1")
        coordinator.onTurnStarted("mobile:two", "turn-2")

        assertEquals(setOf("mobile:one", "mobile:two"), coordinator.activeSessionIds())
        assertEquals(setOf("turn-1", "turn-2"), coordinator.activeTurnIds())

        coordinator.onTurnTerminal("mobile:one", "turn-1")
        assertEquals(setOf("mobile:two"), coordinator.activeSessionIds())
        assertEquals(setOf("turn-2"), coordinator.activeTurnIds())
    }

    @Test
    fun processRestartRestoresAndReplaysPersistedStopIdentity() = runBlocking {
        val persisted = mutableListOf<TurnStopRequest>()
        val first = coordinator(onPersist = persisted::add)
        first.onTurnStarted("mobile:one", "turn-1")
        val request = first.requestStop("mobile:one")

        val replayed = mutableListOf<TurnStopRequest>()
        val restored = coordinator(
            sent = replayed,
            onRemovePersisted = { commandId -> persisted.removeAll { it.commandId == commandId } },
        )
        restored.restore(mapOf("mobile:one" to "turn-1"), persisted.toList())
        restored.onConnectionReady()

        assertEquals(listOf(request), replayed)
        assertTrue(restored.isStopping("mobile:one"))
        restored.onTurnTerminal("mobile:one", "turn-1")
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
        coordinator.onTurnStarted("mobile:one", "turn-1")
        val request = coordinator.requestStop("mobile:one")

        assertTrue(coordinator.onReply(okReply(request)))
        coordinator.onConnectionReady()

        assertTrue(persisted.isEmpty())
        assertEquals(listOf(request), sent)
        assertTrue(coordinator.isStopping("mobile:one"))
    }

    @Test
    fun restoreDeletesStopWhoseTurnAlreadyReachedTerminalState() = runBlocking {
        val request = TurnStopRequest("stop-1", "mobile:one", "turn-1")
        val removed = mutableListOf<String>()
        val coordinator = coordinator(onRemovePersisted = removed::add)

        coordinator.restore(emptyMap(), listOf(request))

        assertEquals(listOf("stop-1"), removed)
        assertFalse(coordinator.isStopping("mobile:one"))
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
        assertTrue(persisted.isEmpty())
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
        onError: (String) -> Unit = {},
        onStateChanged: () -> Unit = {},
    ) = TurnStopCoordinator(
        send = { request -> sent.add(request) },
        onPersist = onPersist,
        onRemovePersisted = onRemovePersisted,
        onTransportUnavailable = {},
        onError = onError,
        onStateChanged = onStateChanged,
    )
}
