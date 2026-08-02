package com.akashic.mobile.data.realtime

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashic.mobile.App
import com.akashic.mobile.data.local.MessageWithBlocks
import com.akashic.mobile.domain.model.ConnectionPhase
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedGatewayDeviceTest {
    @Test
    fun loadsAkashaRecallCardOverHttps() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val offer = String(
            Base64.decode(requireNotNull(arguments.getString("pairingOfferBase64")), Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val sessionId = requireNotNull(arguments.getString("historySessionId"))
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession

        session.start()
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.initialized } }
        session.beginPairing(offer)
        withTimeout(TIMEOUT_MILLIS) {
            session.state.first { it.hasProfile && it.connection.phase == ConnectionPhase.READY }
        }
        val plugin = withTimeout(TIMEOUT_MILLIS) {
            session.pluginUi.catalog.first { catalog ->
                !catalog.updating && catalog.plugins.any { it.id == "akasha@builtin" }
            }.plugins.single { it.id == "akasha@builtin" }
        }

        val requestId = "device-akasha-${SystemClock.elapsedRealtime()}"
        val startedAt = SystemClock.elapsedRealtime()
        session.queryPluginUi(
            requestId = requestId,
            ownerId = "device:akasha",
            slot = "turn.before_reasoning",
            sessionId = sessionId,
            turnId = null,
            pluginId = plugin.id,
            method = "recall.current",
            payloadJson = """{"message_id":"isolated-assistant"}""",
            cacheMode = "none",
            transportMode = "https",
        )
        val response = withTimeout(TIMEOUT_MILLIS) {
            session.pluginUi.results.first { it.requestId == requestId }
        }
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
        val resultJson = requireNotNull(response.resultJson) {
            "Akasha HTTPS query 失败: ${response.error}"
        }
        val result = Json.parseToJsonElement(resultJson).jsonObject

        Log.i(
            "AkashicDeviceGate",
            "akasha_https_elapsed_ms=$elapsedMillis response_bytes=${resultJson.toByteArray().size}",
        )
        assertEquals("akasha.recall-card.v1", result["schema"]?.jsonPrimitive?.content)
        assertEquals(40, result["left"]?.jsonArray?.size)
        assertTrue(resultJson.toByteArray().size < 192 * 1024)
        assertTrue("Akasha 首次 HTTPS 查询耗时 ${elapsedMillis}ms", elapsedMillis < 3_000)
    }

    @Test
    fun pairSendAndReceiveFixedMedia() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val offer = String(
            Base64.decode(requireNotNull(arguments.getString("pairingOfferBase64")), Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val sessionId = requireNotNull(arguments.getString("historySessionId"))
        val expectedMediaType = arguments.getString("expectedMediaType") ?: "image/gif"
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession

        session.start()
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.initialized } }
        session.beginPairing(offer)
        val ready = withTimeout(TIMEOUT_MILLIS) {
            session.state.first { it.hasProfile && it.connection.phase == ConnectionPhase.READY }
        }
        val serverId = requireNotNull(ready.serverId)
        withTimeout(TIMEOUT_MILLIS) {
            app.container.database.conversations().observeForServer(serverId)
                .first { conversations -> conversations.any { it.sessionId == sessionId } }
        }

        session.selectSession(sessionId)
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.currentSessionId == sessionId } }
        val initial = graph(app, sessionId) { it.size >= 2 }
        assertEquals(1, initial.count { it.message.text == "这是隔离 Gateway 的历史消息" })
        assertEquals(1, initial.count { it.message.text == "历史同步成功后应只出现一次。" })

        session.sendMessage("Android 隔离端到端")
        val received = graph(app, sessionId) { messages ->
            messages.any { message ->
                message.message.text.startsWith(ISOLATED_REPLY_PREFIX) &&
                    message.attachmentLinks.any { it.attachment.state == "cached" }
            }
        }
        val reply = received.single {
            it.message.text.startsWith(ISOLATED_REPLY_PREFIX)
        }
        val attachment = reply.attachmentLinks.single().attachment
        assertEquals(expectedMediaType, attachment.contentType)
        assertTrue(File(requireNotNull(attachment.cachePath)).isFile)
    }

    @Test
    fun processRestartResumesWithoutHistoryDuplicates() = runBlocking {
        val sessionId = requireNotNull(
            InstrumentationRegistry.getArguments().getString("historySessionId"),
        )
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession

        session.start()
        withTimeout(TIMEOUT_MILLIS) {
            session.state.first { it.hasProfile && it.connection.phase == ConnectionPhase.READY }
        }
        session.selectSession(sessionId)
        val restored = graph(app, sessionId) { messages ->
            messages.any { it.message.text.startsWith(ISOLATED_REPLY_PREFIX) }
        }

        assertEquals(1, restored.count { it.message.text == "这是隔离 Gateway 的历史消息" })
        assertEquals(1, restored.count { it.message.text == "历史同步成功后应只出现一次。" })
        assertEquals(
            1,
            restored.count { it.message.text.startsWith(ISOLATED_REPLY_PREFIX) },
        )
    }

    @Test
    fun oversizedHistorySurvivesProjectionReloadExactly() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val offer = String(
            Base64.decode(requireNotNull(arguments.getString("pairingOfferBase64")), Base64.DEFAULT),
            Charsets.UTF_8,
        )
        val sessionId = requireNotNull(arguments.getString("historySessionId"))
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession
        val expectedContent = "长正文🌙\n".repeat(40_000)

        session.start()
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.initialized } }
        session.beginPairing(offer)
        val ready = withTimeout(TIMEOUT_MILLIS) {
            session.state.first { it.hasProfile && it.connection.phase == ConnectionPhase.READY }
        }
        session.selectSession(sessionId)
        val initial = graph(app, sessionId) { messages ->
            messages.any { it.message.text == expectedContent }
        }.single { it.message.text == expectedContent }
        val before = historySnapshot(initial)
        assertEquals(listOf("thinking", "tool", "thinking"), before.blocks.map { it.kind })

        session.reloadFromServer()
        withTimeout(TIMEOUT_MILLIS) {
            session.state.first {
                it.projectionGeneration > ready.projectionGeneration &&
                    it.connection.phase == ConnectionPhase.READY
            }
        }
        val restored = graph(app, sessionId) { messages ->
            messages.any { it.message.messageId == before.messageId && it.message.text == expectedContent }
        }.single { it.message.messageId == before.messageId }

        assertEquals(before, historySnapshot(restored))
        assertEquals(null, app.container.database.messageContentTransfers().get(before.messageId))
        Log.i(
            "AkashicDeviceGate",
            "history_content_bytes=${expectedContent.toByteArray().size} blocks=${before.blocks.size}",
        )
    }

    private fun historySnapshot(message: MessageWithBlocks): HistorySnapshot = HistorySnapshot(
        messageId = message.message.messageId,
        role = message.message.role,
        text = message.message.text,
        serverSeq = message.message.serverSeq,
        blocks = message.blocks.sortedBy { it.ordinal }.map {
            BlockSnapshot(it.ordinal, it.kind, it.status, it.content)
        },
    )

    private suspend fun graph(
        app: App,
        sessionId: String,
        predicate: (List<MessageWithBlocks>) -> Boolean,
    ): List<MessageWithBlocks> = withTimeout(TIMEOUT_MILLIS) {
        app.container.database.messages().observeMessageGraph(sessionId).first(predicate)
    }

    private companion object {
        const val ISOLATED_REPLY_PREFIX = "## WebUI 试点"
        const val TIMEOUT_MILLIS = 60_000L
    }

    private data class HistorySnapshot(
        val messageId: String,
        val role: String,
        val text: String,
        val serverSeq: Long?,
        val blocks: List<BlockSnapshot>,
    )

    private data class BlockSnapshot(
        val ordinal: Int,
        val kind: String,
        val status: String,
        val content: String,
    )
}
