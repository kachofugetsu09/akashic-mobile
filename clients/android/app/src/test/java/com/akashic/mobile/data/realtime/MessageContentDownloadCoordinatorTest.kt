package com.akashic.mobile.data.realtime

import com.akashic.mobile.data.local.MessageContentStore
import com.akashic.mobile.data.local.MessageContentTransferDao
import com.akashic.mobile.data.local.MessageContentTransferEntity
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageContentDownloadCoordinatorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `verified range atomically restores unicode content`() = runBlocking {
        val content = "前缀🌙跨界\n工具结果完整".toByteArray(Charsets.UTF_8)
        val transfer = transfer(content)
        val dao = FakeMessageContentTransferDao(transfer)
        val store = MessageContentStore(temporary.newFolder("content"), dao)
        val commands = mutableListOf<SentCommand>()
        val requests = mutableListOf<MessageContentHttpRequest>()
        var restored: String? = null
        val failures = mutableListOf<String>()
        val coordinator = MessageContentDownloadCoordinator(
            dao = dao,
            store = store,
            sendCommand = { type, id, sessionId, payload ->
                commands += SentCommand(type, id, sessionId, payload)
                true
            },
            startHttpDownload = requests::add,
            commit = { completed, text, _ ->
                restored = text
                assertEquals(1, dao.delete(completed.messageId))
            },
            onTransportUnavailable = failures::add,
            onDownloadFailed = failures::add,
        )

        coordinator.onConnectionReady("server")
        val command = commands.single()
        coordinator.onReply(
            WireEnvelope(
                v = WIRE_PROTOCOL_VERSION,
                kind = WireKind.REPLY,
                type = "message.content.ready",
                id = command.id,
                connectionEpoch = 1,
                sessionId = transfer.sessionId,
                payload = ProtocolCodec.json().encodeToJsonElement(
                    MessageContentGrantPayload.serializer(),
                    MessageContentGrantPayload(
                        messageId = transfer.messageId,
                        byteLength = transfer.byteLength,
                        sha256 = transfer.sha256,
                        path = "/mobile/message-content/v1",
                        ticket = "ticket",
                        expiresAt = "2026-08-02T00:01:00Z",
                    ),
                ).jsonObject,
            ),
        )
        val request = requests.single()
        coordinator.onHttpResponse(
            command.id,
            MessageContentHttpResponse(
                statusCode = 206,
                body = content,
                contentRange = "bytes 0-${content.size - 1}/${content.size}",
                etag = "\"${transfer.sha256}\"",
                contentDigest = digestField(MessageDigest.getInstance("SHA-256").digest(content)),
                representationDigest = digestField(hexToBytes(transfer.sha256)),
            ),
        )

        assertEquals(content.toString(Charsets.UTF_8), restored)
        assertFalse(store.partialFile(transfer).exists())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `reconcile truncates bytes not committed to room offset`() = runBlocking {
        val content = "abcdef".toByteArray()
        val transfer = transfer(content).copy(transferredBytes = 3, state = "downloading")
        val dao = FakeMessageContentTransferDao(transfer)
        val store = MessageContentStore(temporary.newFolder("resume"), dao)
        store.partialFile(transfer).writeBytes(content)

        store.reconcile()

        assertEquals(3, store.partialFile(transfer).length())
        assertEquals(3, dao.get(transfer.messageId)!!.transferredBytes)
    }

    private fun transfer(content: ByteArray) = MessageContentTransferEntity(
        messageId = "mobile:test:4",
        serverId = "server",
        sessionId = "mobile:test",
        byteLength = content.size.toLong(),
        sha256 = sha256(content),
        transferredBytes = 0,
        state = "pending",
        updatedAt = 1,
    )

    private fun digestField(bytes: ByteArray): String =
        "sha-256=:${Base64.getEncoder().encodeToString(bytes)}:"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private data class SentCommand(
        val type: String,
        val id: String,
        val sessionId: String,
        val payload: JsonObject,
    )

    private class FakeMessageContentTransferDao(
        initial: MessageContentTransferEntity,
    ) : MessageContentTransferDao {
        private var value: MessageContentTransferEntity? = initial

        override suspend fun upsert(transfer: MessageContentTransferEntity) {
            value = transfer
        }

        override suspend fun get(messageId: String): MessageContentTransferEntity? =
            value?.takeIf { it.messageId == messageId }

        override suspend fun pending(serverId: String): List<MessageContentTransferEntity> =
            value?.takeIf { it.serverId == serverId && it.state in setOf("pending", "downloading") }
                ?.let(::listOf)
                ?: emptyList()

        override suspend fun all(): List<MessageContentTransferEntity> = listOfNotNull(value)

        override suspend fun updateProgress(
            messageId: String,
            transferredBytes: Long,
            state: String,
            updatedAt: Long,
        ): Int {
            val current = value?.takeIf { it.messageId == messageId } ?: return 0
            value = current.copy(
                transferredBytes = transferredBytes,
                state = state,
                updatedAt = updatedAt,
            )
            return 1
        }

        override suspend fun delete(messageId: String): Int {
            if (value?.messageId != messageId) return 0
            value = null
            return 1
        }
    }
}
