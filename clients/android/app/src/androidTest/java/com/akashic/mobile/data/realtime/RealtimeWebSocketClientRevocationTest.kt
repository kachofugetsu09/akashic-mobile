package com.akashic.mobile.data.realtime

import com.akashic.mobile.domain.model.EndpointRoute
import com.akashic.mobile.domain.model.ServerEndpoint
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeWebSocketClientRevocationTest {
    @Test
    fun preAuthRevocationClosesGenerationAndReportsExactlyOnce() {
        val observer = RecordingObserver()
        val sockets = mutableListOf<FakeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val client = client(observer, sockets, listeners)
        val generation = client.connectRace(
            lan = emptyList(),
            tunnel = listOf(endpoint(1), endpoint(2)),
        )

        listeners[0].onClosed(sockets[0], 4403, "revoked")
        listeners[1].onClosed(sockets[1], 4403, "late revoke")

        assertEquals(1, observer.revoked.size)
        assertEquals(1, observer.closed.size)
        assertEquals(0, observer.exhausted.size)
        assertTrue(sockets.all { it.closedCode == 4403 })
        assertFalse(client.isGenerationCurrent(generation))
    }

    @Test
    fun oldGenerationRevocationDoesNotCloseNewRace() {
        val observer = RecordingObserver()
        val sockets = mutableListOf<FakeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val client = client(observer, sockets, listeners)
        val oldGeneration = client.connectRace(emptyList(), listOf(endpoint(3)))
        val newGeneration = client.connectRace(emptyList(), listOf(endpoint(4)))

        listeners[0].onClosed(sockets[0], 4403, "old generation")

        assertEquals(0, observer.revoked.size)
        assertEquals(0, observer.closed.size)
        assertTrue(client.isGenerationCurrent(newGeneration))
        assertFalse(client.isGenerationCurrent(oldGeneration))
        assertEquals(null, sockets[1].closedCode)
    }

    @Test
    fun controlRevokeClosesCurrentRaceBeforeLateFramesCanUseIt() {
        val observer = RecordingObserver()
        val sockets = mutableListOf<FakeSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val client = client(observer, sockets, listeners)
        val generation = client.connectRace(emptyList(), listOf(endpoint(5), endpoint(6)))

        assertTrue(client.closeGeneration(generation, 4403, "control revoke"))
        assertFalse(client.isGenerationCurrent(generation))
        assertTrue(sockets.all { it.closedCode == 4403 })
        assertFalse(client.closeGeneration(generation, 4403, "duplicate revoke"))
    }

    private fun client(
        observer: RecordingObserver,
        sockets: MutableList<FakeSocket>,
        listeners: MutableList<WebSocketListener>,
    ): RealtimeWebSocketClient = RealtimeWebSocketClient(
        listener = observer,
        allowInsecureTransport = true,
        openWebSocket = { _, request, listener ->
            val socket = FakeSocket(request)
            sockets += socket
            listeners += listener
            socket
        },
    )

    private fun endpoint(port: Int) = ServerEndpoint(
        url = "ws://127.0.0.1:$port/realtime",
        tlsSpkiPins = emptyList(),
        route = EndpointRoute.TUNNEL,
    )

    private class RecordingObserver : RealtimeSocketListener {
        val revoked = mutableListOf<SocketCandidateId>()
        val closed = mutableListOf<SocketCandidateId>()
        val exhausted = mutableListOf<Long>()

        override fun onOpen(candidateId: SocketCandidateId, endpoint: ServerEndpoint) = Unit
        override fun onRevoked(candidateId: SocketCandidateId, code: Int, reason: String) {
            revoked += candidateId
        }
        override fun onEnvelope(candidateId: SocketCandidateId, envelope: WireEnvelope) = Unit
        override fun onBinary(candidateId: SocketCandidateId, chunk: AttachmentChunkCodec.DecodedChunk) = Unit
        override fun onClosed(candidateId: SocketCandidateId, code: Int, reason: String) {
            closed += candidateId
        }
        override fun onFailure(candidateId: SocketCandidateId, error: Throwable) = Unit
        override fun onProtocolFailure(candidateId: SocketCandidateId, error: IllegalArgumentException) = Unit
        override fun onRaceExhausted(generation: Long, error: Throwable) {
            exhausted += generation
        }
    }

    private class FakeSocket(private val requestValue: Request) : WebSocket {
        var closedCode: Int? = null
            private set

        override fun request(): Request = requestValue
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closedCode = code
            return true
        }
        override fun cancel() {
            closedCode = 1000
        }
    }
}
