package com.akashic.mobile.data.realtime

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.akashic.mobile.App
import com.akashic.mobile.MainActivity
import com.akashic.mobile.data.local.MessageWithBlocks
import com.akashic.mobile.domain.model.ConnectionPhase
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
    fun streamsLongTurnThroughVisibleWebView() = runBlocking<Unit> {
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
        session.selectSession(sessionId)
        withTimeout(TIMEOUT_MILLIS) { session.state.first { it.currentSessionId == sessionId } }

        // 基准开始前处理系统权限，避免弹窗暂停待测页面。
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                app.packageName, Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitWebViewReady(scenario)
            installFrameRecorder(scenario)
            if (arguments.getString("perfInteractions") == "true") {
                startComposerEdits(scenario)
            }
            val startedAt = SystemClock.elapsedRealtime()
            session.sendMessage("Android 流式性能基准")
            withTimeout(TIMEOUT_MILLIS) { session.state.first { it.activeTurnId != null } }
            withTimeout(TIMEOUT_MILLIS) { session.state.first { it.activeTurnId == null } }
            val completedGraph = graph(app, sessionId) { messages ->
                messages.count { message ->
                    message.message.role == "assistant" &&
                        message.message.deliveryState == "complete" &&
                        message.message.text.length == 1_237
                } == 1
            }
            val completed = completedGraph.single { message ->
                message.message.role == "assistant" &&
                    message.message.deliveryState == "complete" &&
                    message.message.text.length == 1_237
            }
            val completionMillis = SystemClock.elapsedRealtime() - startedAt
            val frameSummary = readFrameSummary(scenario)

            Log.i(
                "AkashicStreamPerf",
                "stage=frame_summary completion_ms=$completionMillis json=${frameSummary.rawJson}",
            )
            assertEquals(8_213, completed.blocks.filter { it.kind == "thinking" }.sumOf { it.content.length })
            assertEquals(6, completed.blocks.count { it.kind == "tool" && it.status == "completed" })
            assertTrue("流式 turn 应在 25 秒内完成，实际 ${completionMillis}ms", completionMillis < 25_000)
            assertTrue("可见文字更新不足: ${frameSummary.renderCount}", frameSummary.renderCount >= 40)
            assertTrue("可见文字更新 p50 过慢: ${frameSummary.renderP50}ms", frameSummary.renderP50 <= 100.0)
            assertTrue("可见文字更新 p95 过慢: ${frameSummary.renderP95}ms", frameSummary.renderP95 <= 175.0)
            assertTrue("页面帧 p95 过慢: ${frameSummary.frameP95}ms", frameSummary.frameP95 <= 75.0)
            awaitVisibleAnswer(scenario, completed)
            if (arguments.getString("perfInteractions") == "true") {
                val metrics = Json.parseToJsonElement(frameSummary.rawJson).jsonObject
                val edits = requireNotNull(metrics["edits"]).jsonPrimitive.int
                val expectedDraft = "performance draft $edits"
                assertTrue("未执行足够的输入交互", edits >= 10)
                assertEquals(expectedDraft, requireNotNull(metrics["draftText"]).jsonPrimitive.content)
                withTimeout(TIMEOUT_MILLIS) {
                    app.container.database.composerDrafts()
                        .observe(requireNotNull(session.state.value.serverId), sessionId)
                        .first { it?.text == expectedDraft }
                }
                // 重建真实宿主，验证旧 pump 释放及新 collector 恢复后不丢内容和草稿。
                scenario.recreate()
                awaitWebViewReady(scenario)
                awaitVisibleAnswer(scenario, completed)
                withTimeout(TIMEOUT_MILLIS) {
                    while (evaluateJavascript(
                            scenario,
                            "document.querySelector('.mobile-composer textarea')?.value === ${org.json.JSONObject.quote(expectedDraft)}",
                        ) != "true"
                    ) kotlinx.coroutines.delay(50)
                }
            }
        }
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

    /** 等待完整终态正文进入 DOM，不以数据库完成代替可见完成。 */
    private suspend fun awaitVisibleAnswer(
        scenario: ActivityScenario<MainActivity>,
        completed: MessageWithBlocks,
    ) {
        val expected = completed.message.text.replace("## ", "").replace(Regex("\\s+"), "")
        val messageId = org.json.JSONObject.quote(completed.message.messageId)
        val expectedJson = org.json.JSONObject.quote(expected)
        withTimeout(TIMEOUT_MILLIS) {
            while (evaluateJavascript(scenario, """
                (() => {
                  const message = document.querySelector('[data-message-id="' + CSS.escape($messageId) + '"]');
                  return Boolean(message && !message.classList.contains('streaming') &&
                    message.textContent.replace(/\s+/g, '').includes($expectedJson));
                })()
            """.trimIndent()) != "true") kotlinx.coroutines.delay(50)
        }
    }

    private suspend fun awaitWebViewReady(scenario: ActivityScenario<MainActivity>) {
        withTimeout(TIMEOUT_MILLIS) {
            while (true) {
                var ready = false
                scenario.onActivity { activity ->
                    ready = activity.window.decorView.findWebView()?.progress == 100
                }
                if (ready && evaluateJavascript(
                        scenario,
                        "Boolean(document.querySelector('.mobile-composer textarea') && window.AkashicMobile)",
                    ) == "true"
                ) return@withTimeout
                kotlinx.coroutines.delay(50)
            }
        }
    }

    private suspend fun installFrameRecorder(scenario: ActivityScenario<MainActivity>) {
        evaluateJavascript(
            scenario,
            """
            (() => {
              const state = {
                last: 0, gaps: [], renderTimes: [], lastText: "", messageId: null,
                snapshots: 0, snapshotChars: 0, streamPatches: 0, statePatches: 0,
                longTasks: [], edits: 0, editTimer: null,
              };
              window.__akashicFramePerf = state;
              new PerformanceObserver((list) => {
                state.longTasks.push(...list.getEntries().map((entry) => entry.duration));
              }).observe({ type: "longtask" });
              window.addEventListener("message", (event) => {
                if (typeof event.data !== "string") return;
                const envelope = JSON.parse(event.data);
                if (envelope.type === "mobile.snapshot") {
                  state.snapshots++;
                  state.snapshotChars += event.data.length;
                } else if (envelope.type === "mobile.stream-patch") state.streamPatches++;
                else if (envelope.type === "mobile.state-patch") state.statePatches++;
              });
              const tick = (now) => {
                if (state.last > 0) state.gaps.push(now - state.last);
                state.last = now;
                requestAnimationFrame(tick);
              };
              const recordVisibleText = () => {
                let message = state.messageId === null
                  ? document.querySelector('[data-message-id^="assistant:"].streaming')
                  : document.querySelector('[data-message-id="' + CSS.escape(state.messageId) + '"]');
                if (!message) return;
                state.messageId ??= message.getAttribute("data-message-id");
                const text = message.textContent ?? "";
                if (text === state.lastText) return;
                state.lastText = text;
                state.renderTimes.push(performance.now());
              };
              new MutationObserver(recordVisibleText).observe(document.body, {
                attributes: true,
                childList: true,
                characterData: true,
                subtree: true,
              });
              requestAnimationFrame(tick);
              return true;
            })()
            """.trimIndent(),
        )
    }

    /** 用真实输入事件触发草稿保存，与流式输出重叠。 */
    private suspend fun startComposerEdits(scenario: ActivityScenario<MainActivity>) {
        evaluateJavascript(scenario, """
            (() => {
              const state = window.__akashicFramePerf;
              const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set;
              state.editTimer = setInterval(() => {
                if (!document.querySelector('.mobile-message-anchor.streaming')) return;
                const input = document.querySelector('.mobile-composer textarea');
                if (!input) throw new Error('基准输入框丢失');
                setter.call(input, 'performance draft ' + (++state.edits));
                input.dispatchEvent(new Event('input', { bubbles: true }));
              }, 350);
              return true;
            })()
        """.trimIndent())
    }

    private suspend fun readFrameSummary(
        scenario: ActivityScenario<MainActivity>,
    ): FrameSummary {
        val encoded = evaluateJavascript(
            scenario,
            """
        (() => {
          const state = window.__akashicFramePerf;
          clearInterval(state.editTimer);
          const values = [...(window.__akashicFramePerf?.gaps ?? [])].sort((a, b) => a - b);
          const renderTimes = window.__akashicFramePerf?.renderTimes ?? [];
          const renderGaps = renderTimes.slice(1).map((value, index) => value - renderTimes[index]).sort((a, b) => a - b);
          const percentile = (p) => values.length === 0 ? -1 : values[Math.floor((values.length - 1) * p)];
          const renderPercentile = (p) => renderGaps.length === 0 ? -1 : renderGaps[Math.floor((renderGaps.length - 1) * p)];
          return JSON.stringify({
            count: values.length,
            p50: percentile(0.50),
            p95: percentile(0.95),
            p99: percentile(0.99),
            max: percentile(1),
            over16: values.filter((value) => value > 16.7).length,
            over33: values.filter((value) => value > 33.3).length,
            over50: values.filter((value) => value > 50).length,
            renderCount: renderTimes.length,
            renderP50: renderPercentile(0.50),
            renderP95: renderPercentile(0.95),
            renderP99: renderPercentile(0.99),
            renderMax: renderPercentile(1),
            snapshots: state.snapshots,
            snapshotChars: state.snapshotChars,
            streamPatches: state.streamPatches,
            statePatches: state.statePatches,
            longTasks: state.longTasks.length,
            longTaskMillis: state.longTasks.reduce((sum, duration) => sum + duration, 0),
            edits: state.edits,
            draftText: document.querySelector('.mobile-composer textarea')?.value,
          });
        })()
        """.trimIndent(),
        )
        val rawJson = Json.parseToJsonElement(encoded).jsonPrimitive.content
        val value = Json.parseToJsonElement(rawJson).jsonObject
        return FrameSummary(
            frameP95 = requireNotNull(value["p95"]).jsonPrimitive.double,
            renderCount = requireNotNull(value["renderCount"]).jsonPrimitive.double.toInt(),
            renderP50 = requireNotNull(value["renderP50"]).jsonPrimitive.double,
            renderP95 = requireNotNull(value["renderP95"]).jsonPrimitive.double,
            rawJson = rawJson,
        )
    }

    private suspend fun evaluateJavascript(
        scenario: ActivityScenario<MainActivity>,
        script: String,
    ): String {
        val result = CompletableDeferred<String>()
        scenario.onActivity { activity ->
            requireNotNull(activity.window.decorView.findWebView()).evaluateJavascript(script) {
                result.complete(it)
            }
        }
        return withTimeout(TIMEOUT_MILLIS) { result.await() }
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findWebView()?.let { return it }
        }
        return null
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

    private data class FrameSummary(
        val frameP95: Double,
        val renderCount: Int,
        val renderP50: Double,
        val renderP95: Double,
        val rawJson: String,
    )
}
