package com.akashic.mobile.data.realtime

import com.akashic.mobile.domain.model.EndpointRoute
import com.akashic.mobile.domain.model.ServerEndpoint
import okio.ByteString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeWebSocketClientTest {
    @Test
    fun `authenticated protocol failure closes winner and reports closure`() {
        val events = RecordingSocketListener()
        val connector = RecordingConnector()
        val client = RealtimeWebSocketClient(events, true, connector)

        client.connectRace(emptyList(), listOf(TEST_ENDPOINT))
        connector.open()
        val candidate = events.opened.single()

        assertTrue(client.promote(candidate))
        assertTrue(client.closeActive(candidate, 4406, "invalid authenticated frame"))
        assertEquals(4406, connector.socket.closeCode)
        connector.completeClose()
        assertEquals(listOf(candidate), events.closed)
    }

    @Test
    fun `pre-auth rejection cannot close promoted winner`() {
        val events = RecordingSocketListener()
        val connector = RecordingConnector()
        val client = RealtimeWebSocketClient(events, true, connector)

        client.connectRace(emptyList(), listOf(TEST_ENDPOINT))
        connector.open()
        val candidate = events.opened.single()

        assertTrue(client.promote(candidate))
        assertFalse(client.rejectPreAuth(candidate, 4406, "invalid pre-auth frame"))
        assertEquals(null, connector.socket.closeCode)
    }

    private class RecordingConnector : WebSocketConnector {
        val socket = RecordingWebSocket()
        private lateinit var listener: WebSocketListener

        override fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket {
            socket.requestValue = request
            this.listener = listener
            return socket
        }

        fun open() {
            listener.onOpen(
                socket,
                Response.Builder()
                    .request(socket.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build(),
            )
        }

        fun completeClose() {
            listener.onClosed(socket, requireNotNull(socket.closeCode), requireNotNull(socket.closeReason))
        }
    }

    private class RecordingWebSocket : WebSocket {
        lateinit var requestValue: Request
        var closeCode: Int? = null
        var closeReason: String? = null

        override fun request(): Request = requestValue

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            closeReason = reason
            return true
        }

        override fun cancel() = Unit
    }

    private class RecordingSocketListener : RealtimeSocketListener {
        val opened = mutableListOf<SocketCandidateId>()
        val closed = mutableListOf<SocketCandidateId>()

        override fun onOpen(candidateId: SocketCandidateId, endpoint: ServerEndpoint) {
            opened += candidateId
        }

        override fun onEnvelope(candidateId: SocketCandidateId, envelope: WireEnvelope) = Unit

        override fun onBinary(candidateId: SocketCandidateId, chunk: AttachmentChunkCodec.DecodedChunk) = Unit

        override fun onClosed(candidateId: SocketCandidateId, code: Int, reason: String) {
            closed += candidateId
        }

        override fun onFailure(candidateId: SocketCandidateId, error: Throwable) = Unit

        override fun onProtocolFailure(candidateId: SocketCandidateId, error: IllegalArgumentException) = Unit

        override fun onRaceExhausted(generation: Long, error: Throwable) = Unit
    }

    private companion object {
        val TEST_ENDPOINT = ServerEndpoint("ws://127.0.0.1:6323/ws", emptyList(), EndpointRoute.TUNNEL)
    }
}
