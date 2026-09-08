package com.akashic.mobile.data.realtime

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashic.mobile.App
import com.akashic.mobile.data.local.MessageWithAttachments
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
    fun historicalArtifactsReachRoomCacheAndSharedWebUi() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        val offer = String(Base64.decode(requireNotNull(arguments.getString("pairingOfferBase64")), Base64.DEFAULT), Charsets.UTF_8)
        val sessionId = requireNotNull(arguments.getString("historySessionId"))
        val otherSessionId = requireNotNull(arguments.getString("otherSessionId"))
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession
        session.start()
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.initialized } }
        session.beginPairing(offer)
        val ready = withTimeout(TIMEOUT_MILLIS) { session.state.first { it.hasProfile && it.connection.phase == ConnectionPhase.READY } }
        val serverId = requireNotNull(ready.serverId)
        session.selectSession(sessionId)
        val rows = graph(app, sessionId) { messages -> messages.size == 5 && messages.sumOf { row -> row.attachmentLinks.count { it.attachment.state == "cached" } } == 2 }
        assertEquals((0L..4L).toList(), rows.map { it.message.serverSeq })
        assertTrue(rows.single { it.message.messageId == "legacy-answer" }.message.bodyJson.contains("history.transcript"))
        assertTrue(rows.single { it.message.messageId == "legacy-record" }.message.bodyJson.contains("history.record"))
        val media = rows.flatMap { it.attachmentLinks }.map { it.attachment }
        for (item in media) {
            assertEquals(com.akashic.mobile.data.local.artifactCacheId(serverId, requireNotNull(item.artifactId)), item.cacheId)
            val file = File(item.cachePath)
            assertTrue(file.isFile)
            assertEquals(item.sizeBytes, file.length())
            assertEquals(item.sha256, java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
        }
        val empty = media.single { it.sizeBytes == 0L }
        assertEquals(null, empty.filename)
        assertEquals(null, empty.contentType)
        session.selectSession(otherSessionId)
        val other = graph(app, otherSessionId) { it.size == 1 && it.single().attachmentLinks.singleOrNull()?.attachment?.state == "cached" }
        assertEquals(media.single { it.sizeBytes > 0 }.cacheId, other.single().attachmentLinks.single().attachment.cacheId)
        assertEquals(2, app.container.database.mediaAttachments().all().size)

        // 2. 真实 Room -> ViewModel -> WebView：诊断行仍在同步事实中，但不进入聊天和待发送提示。
        session.selectSession(sessionId)
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.currentSessionId == sessionId && it.connection.phase == ConnectionPhase.READY } }
        app.container.database.messages().upsert(com.akashic.mobile.data.local.MessageEntity(
            "local-rejected", null, sessionId, "user", "保留但不排队的旧失败正文", "failed", 1, 1,
        ))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.packageName, android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        androidx.test.core.app.ActivityScenario.launch(com.akashic.mobile.MainActivity::class.java).use { activity ->
            activity.onActivity {
                // 隔离夹具可在安全锁屏上进入前台；不改变设备锁或正式应用。
                it.setShowWhenLocked(true)
                it.setTurnScreenOn(true)
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            var rendered = ""
            val text = kotlinx.coroutines.withTimeoutOrNull(TIMEOUT_MILLIS) {
                while (!rendered.contains("原回答完整保留")) {
                    val reply = kotlinx.coroutines.CompletableDeferred<String>()
                    activity.onActivity { screen ->
                        val web = findWebView(screen.window.decorView)
                        if (web == null) reply.complete("") else web.evaluateJavascript("document.body.innerText") { value ->
                            reply.complete(Json.decodeFromString<String>(value))
                        }
                    }
                    rendered = reply.await()
                    if (!rendered.contains("原回答完整保留")) kotlinx.coroutines.delay(100)
                }
                rendered
            } ?: error("Shared WebUI did not show history: $rendered")
            assertTrue(!text.contains("旧记录归档"))
            assertTrue(!text.contains("legacy-attribution-unknown"))
            assertTrue(!text.contains("消息详情"))
            assertTrue(!text.contains("条待发送"))
        }
        assertEquals("failed", app.container.database.messages().get("local-rejected")?.deliveryState)
        assertEquals(4L, app.container.database.messages().historyProjectionProgress(sessionId).maxServerSeq)
        Log.i("AkashicDeviceGate", "artifact_history_rows=5 artifact_cache_files=2 raw_head_seq=4 shared_webui=verified")
    }

    private fun findWebView(view: android.view.View): android.webkit.WebView? {
        if (view is android.webkit.WebView) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    @Test
    fun freshEmptyCoreBecomesReadyAndCreatesFirstSession() = runBlocking<Unit> {
        val offer = String(
            Base64.decode(
                requireNotNull(
                    InstrumentationRegistry.getArguments().getString("pairingOfferBase64"),
                ),
                Base64.DEFAULT,
            ),
            Charsets.UTF_8,
        )
        val app = ApplicationProvider.getApplicationContext<App>()
        val session = app.container.realtimeSession

        session.start()
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.initialized } }
        session.beginPairing(offer)
        val emptyReady = withTimeout(TIMEOUT_MILLIS) {
            session.state.first {
                it.hasProfile && it.connection.phase == ConnectionPhase.READY
            }
        }
        assertEquals(null, emptyReady.currentSessionId)

        session.createSession()
        val created = withTimeout(TIMEOUT_MILLIS) {
            session.state.first { state ->
                state.currentSessionId?.matches(Regex("^akashic:[0-9a-f]{32}$")) == true
            }
        }
        val sessionId = requireNotNull(created.currentSessionId)
        assertEquals(sessionId, app.container.database.conversations().get(sessionId)?.sessionId)
        val catalog = withTimeout(TIMEOUT_MILLIS) {
            session.modelCatalog.state.first {
                it.sessionId == sessionId &&
                    !it.loading &&
                    it.generationId != null &&
                    it.errorMessage == null
            }
        }
        assertTrue(catalog.runtimes.isNotEmpty())
    }

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
        assertTrue(before.bodyJson != "{}")

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
            "history_content_bytes=${expectedContent.toByteArray().size} body_bytes=${before.bodyJson.toByteArray().size}",
        )
    }

    private fun historySnapshot(message: MessageWithAttachments): HistorySnapshot = HistorySnapshot(
        messageId = message.message.messageId,
        role = message.message.role,
        text = message.message.text,
        serverSeq = message.message.serverSeq,
        bodyJson = message.message.bodyJson,
        metadataJson = message.message.metadataJson,
        attachmentsJson = message.message.attachmentsJson,
    )

    private suspend fun graph(
        app: App,
        sessionId: String,
        predicate: (List<MessageWithAttachments>) -> Boolean,
    ): List<MessageWithAttachments> = withTimeout(TIMEOUT_MILLIS) {
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
        val bodyJson: String,
        val metadataJson: String,
        val attachmentsJson: String,
    )

}
