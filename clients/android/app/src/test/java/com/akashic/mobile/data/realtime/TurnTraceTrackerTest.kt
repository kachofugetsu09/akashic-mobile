package com.akashic.mobile.data.realtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTraceTrackerTest {
    private class FakeClock : TurnTraceClock {
        var nowElapsed = 1_000L
        var nowWall = 1_700_000_000_000L
        override fun elapsedMillis(): Long = nowElapsed
        override fun wallMillis(): Long = nowWall
    }

    private val clock = FakeClock()
    private val lines = mutableListOf<String>()

    private fun tracker(maxEntries: Int = 32) = TurnTraceTracker(clock, maxEntries) { lines += it }

    private fun lastLine(): String = lines.last()

    private fun bindSend(tracker: TurnTraceTracker, sessionId: String, clientMessageId: String) {
        val origin = tracker.captureSendOrigin()
        tracker.recordSend(sessionId, clientMessageId, origin)
    }

    @Test
    fun sendEntryMigratesToTurnTripleOnTurnStarted() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-1")
        assertTrue(lastLine().contains("cmid=cmid-1 stage=send_requested mono=1000"))
        assertTrue(lastLine().contains("since_send=0"))
        clock.nowElapsed += 50
        tracker.onTurnStartedReceived("mobile:test", "turn-1", "cmid-1")

        assertEquals(1, tracker.entryCount())
        assertTrue(lastLine().contains("turn=turn-1 cmid=cmid-1 stage=turn_started_received"))
        assertTrue(lastLine().contains("since_send=50"))
        assertTrue(lastLine().contains("mono=1050"))
        assertTrue(lastLine().contains("wall=2023-11-14T22:13:20Z"))

        clock.nowElapsed += 25
        tracker.onThinkingReceived("mobile:test", "turn-1")
        assertTrue(lastLine().contains("stage=first_thinking_received"))
        assertTrue(lastLine().contains("since_send=75"))

        clock.nowElapsed += 75
        tracker.onAnswerReceived("mobile:test", "turn-1")
        assertTrue(lastLine().contains("stage=first_answer_received"))
        assertTrue(lastLine().contains("since_send=150"))
    }

    @Test
    fun sendRequestedBindsEntryOriginNotCmidTime() {
        val tracker = tracker()
        val origin = tracker.captureSendOrigin()
        // 1. 深层校验占用 40ms 后才生成 cmid；send_requested 必须绑定入口 origin
        clock.nowElapsed += 40
        tracker.recordSend("mobile:test", "cmid-2", origin)
        assertTrue(lastLine().contains("stage=send_requested"))
        assertTrue(lastLine().contains("mono=1000"))
        assertTrue(lastLine().contains("since_send=0"))
        // 2. 本地 outbox 提交耗时从入口 origin 起算，包含校验耗时
        clock.nowElapsed += 10
        tracker.onLocalOutboxCommitted("mobile:test", "cmid-2")
        assertTrue(lastLine().contains("stage=local_outbox_committed mono=1050"))
        assertTrue(lastLine().contains("since_send=50"))
    }

    @Test
    fun sendRequestedFirstOnlyDespiteRepeatedBind() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-3")
        clock.nowElapsed += 10
        bindSend(tracker, "mobile:test", "cmid-3")
        assertEquals(1, lines.count { it.contains("stage=send_requested") })
    }

    @Test
    fun logicalFirstOnlyDespitePhysicalReplay() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-4")
        tracker.onTurnStartedReceived("mobile:test", "turn-2", "cmid-4")

        clock.nowElapsed += 10
        tracker.onThinkingReceived("mobile:test", "turn-2")
        clock.nowElapsed += 1_000
        tracker.onThinkingReceived("mobile:test", "turn-2")
        tracker.onAnswerReceived("mobile:test", "turn-2")
        tracker.onAnswerReceived("mobile:test", "turn-2")

        val thinkingLines = lines.count { it.contains("stage=first_thinking") }
        val answerLines = lines.count { it.contains("stage=first_answer") }
        assertEquals(1, thinkingLines)
        assertEquals(1, answerLines)
    }

    @Test
    fun missingSendOriginKeepsDurationExplicitlyUnknown() {
        val tracker = tracker()
        tracker.onTurnStartedReceived("mobile:test", "turn-3", null)
        tracker.onTerminalReceived("mobile:test", "turn-3", "completed")
        assertTrue(lastLine().contains("stage=terminal_received status=completed"))
        assertTrue(lastLine().contains("since_send=missing_origin"))
        tracker.onRoomCommitted("mobile:test", "turn-3", TurnTraceStage.TERMINAL_COMMITTED)
        assertTrue(lastLine().contains("stage=terminal_committed mono="))
        assertTrue(lastLine().contains("since_send=missing_origin"))
    }

    @Test
    fun entryRemovedAfterAllFourPostTerminalMilestones() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-5")
        tracker.onTurnStartedReceived("mobile:test", "turn-4", "cmid-5")
        tracker.onTerminalReceived("mobile:test", "turn-4", "completed")
        tracker.onRoomCommitted("mobile:test", "turn-4", TurnTraceStage.TERMINAL_COMMITTED)
        assertEquals(1, tracker.entryCount())

        tracker.onTurnCleared("mobile:test", "turn-4")
        assertEquals(1, tracker.entryCount())
        tracker.onCanStopFalse("mobile:test", "turn-4")
        assertEquals(1, tracker.entryCount())
        tracker.onComposerReady("mobile:test", "turn-4")
        assertEquals(1, tracker.entryCount())

        tracker.onWebViewPatch(
            sessionId = "mobile:test",
            turnId = "turn-4",
            clientMessageId = "cmid-5",
            thinkingDelta = false,
            answerDelta = false,
            terminal = true,
        )
        assertEquals(0, tracker.entryCount())
        assertTrue(lastLine().contains("stage=webview_terminal_dispatched"))

        // 4. 收尾同时清掉反向映射：迟到 ACK 只能 origin=missing，不能复活条目
        tracker.onServerAckReceived("cmid-5")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-5 stage=server_ack_received"))
        assertEquals(0, tracker.entryCount())
    }

    @Test
    fun uiProjectionBeforeBindingLogsMissingOrigin() {
        val tracker = tracker()
        tracker.onUiProjected(
            sessionId = "mobile:test",
            turnId = "turn-5",
            clientMessageId = null,
            hasThinking = true,
            hasAnswer = false,
            streaming = true,
        )
        assertEquals(0, tracker.entryCount())
        assertTrue(lastLine().contains("origin=missing"))
    }

    @Test
    fun boundedByMaxEntriesEvictsOldest() {
        val tracker = tracker(maxEntries = 4)
        for (index in 1..4) {
            tracker.onTurnStartedReceived("mobile:test", "turn-b$index", "cmid-b$index")
        }
        assertEquals(4, tracker.entryCount())
        tracker.onTurnStartedReceived("mobile:test", "turn-b5", "cmid-b5")
        assertEquals(4, tracker.entryCount())
        assertFalse(tracker.hasEntryForTest("mobile:test", "turn-b1"))
        assertTrue(tracker.hasEntryForTest("mobile:test", "turn-b5"))
    }

    @Test
    fun lruEvictionClearsReverseMappingWithoutResurrection() {
        val tracker = tracker(maxEntries = 4)
        for (index in 1..4) {
            tracker.onTurnStartedReceived("mobile:test", "turn-b$index", "cmid-b$index")
        }
        tracker.onTurnStartedReceived("mobile:test", "turn-b5", "cmid-b5")
        assertEquals(4, tracker.entryCount())

        // 被淘汰 turn 的迟到 UI 事件只能 origin=missing，不能通过陈旧映射复活条目
        tracker.onUiProjected(
            sessionId = "mobile:test",
            turnId = "turn-b1",
            clientMessageId = "cmid-b1",
            hasThinking = true,
            hasAnswer = false,
            streaming = true,
        )
        assertEquals(4, tracker.entryCount())
        assertTrue(lastLine().contains("origin=missing"))
        assertFalse(tracker.hasEntryForTest("mobile:test", "turn-b1"))
    }

    @Test
    fun resetClearsAllEntries() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-6")
        tracker.onTurnStartedReceived("mobile:test", "turn-6", "cmid-6")
        tracker.reset()
        assertEquals(0, tracker.entryCount())
    }

    @Test
    fun resetAlsoClearsMissingOriginDedup() {
        val tracker = tracker()
        // 1. reset 前产生 missing 日志
        tracker.onLocalOutboxCommitted("mobile:test", "cmid-lost")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-lost"))
        // 2. reset 后相同 key/stage 必须再次产生日志，而不是被去重吞掉
        tracker.reset()
        tracker.onLocalOutboxCommitted("mobile:test", "cmid-lost")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-lost"))
        assertEquals(2, lines.count { it.contains("origin=missing key=send:cmid-lost") })
    }

    @Test
    fun localCommitDispatchAndAckUseOriginMonotonicDelta() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-7")
        clock.nowElapsed += 20
        tracker.onLocalOutboxCommitted("mobile:test", "cmid-7")
        assertTrue(lastLine().contains("stage=local_outbox_committed"))
        assertTrue(lastLine().contains("since_send=20"))
        clock.nowElapsed += 30
        tracker.onSocketDispatched("mobile:test", "cmid-7")
        assertTrue(lastLine().contains("stage=socket_dispatched"))
        assertTrue(lastLine().contains("since_send=50"))
        clock.nowElapsed += 10
        tracker.onTurnStartedReceived("mobile:test", "turn-7", "cmid-7")
        assertTrue(lastLine().contains("turn=turn-7"))
        assertTrue(lastLine().contains("stage=turn_started_received"))
        assertTrue(lastLine().contains("since_send=60"))
    }

    @Test
    fun ackBeforeTurnStartedStaysOnSendKey() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-8")
        clock.nowElapsed += 60
        tracker.onServerAckReceived("cmid-8")
        assertTrue(lastLine().contains("stage=server_ack_received"))
        assertTrue(lastLine().contains("since_send=60"))
        clock.nowElapsed += 5
        tracker.onServerAckCommitted("cmid-8")
        assertTrue(lastLine().contains("stage=server_ack_committed"))
        assertTrue(lastLine().contains("since_send=65"))
        assertEquals(1, tracker.entryCount())
    }

    @Test
    fun ackAfterTurnStartedResolvesSameTripleByClientMessageId() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-9")
        clock.nowElapsed += 30
        tracker.onSocketDispatched("mobile:test", "cmid-9")
        // 1. turn.started 先于 ACK 到达：entry 迁移到 turn 键
        clock.nowElapsed += 40
        tracker.onTurnStartedReceived("mobile:test", "turn-9", "cmid-9")
        assertTrue(lastLine().contains("turn=turn-9"))
        // 2. 后到的 ACK 必须按 cmid 反查到同一 entry，不能 origin=missing
        clock.nowElapsed += 50
        tracker.onServerAckReceived("cmid-9")
        assertTrue(lastLine().contains("turn=turn-9 cmid=cmid-9 stage=server_ack_received"))
        assertTrue(lastLine().contains("since_send=120"))
        clock.nowElapsed += 5
        tracker.onServerAckCommitted("cmid-9")
        assertTrue(lastLine().contains("turn=turn-9 cmid=cmid-9 stage=server_ack_committed"))
        assertTrue(lastLine().contains("since_send=125"))
        assertEquals(1, tracker.entryCount())
    }

    @Test
    fun sendErrorRecordsOutcomeAndRemovesEntry() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-10")
        clock.nowElapsed += 70
        tracker.onSendError("cmid-10", "session_not_found")
        assertTrue(lastLine().contains("stage=send_error status=session_not_found"))
        assertTrue(lastLine().contains("since_send=70"))
        assertEquals(0, tracker.entryCount())
        // 迟到的 ACK 只能 origin=missing，不能复活失败 entry 伪装活动 turn
        tracker.onServerAckReceived("cmid-10")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-10 stage=server_ack_received"))
        assertEquals(0, tracker.entryCount())
    }

    @Test
    fun sendErrorDefiniteOutcomeClosesMigratedEntry() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-11")
        tracker.onTurnStartedReceived("mobile:test", "turn-11", "cmid-11")
        clock.nowElapsed += 80
        tracker.onSendError("cmid-11", "session_not_found")
        assertTrue(
            lastLine().contains(
                "turn=turn-11 cmid=cmid-11 stage=send_error status=session_not_found",
            ),
        )
        assertEquals(0, tracker.entryCount())
        assertFalse(tracker.hasEntryForTest("mobile:test", "turn-11"))
    }

    @Test
    fun sendErrorUnknownOutcomeKeepsEntryBeforeTurnStarted() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-15")
        clock.nowElapsed += 40
        tracker.onSendError("cmid-15", "command_outcome_unknown")
        assertTrue(lastLine().contains("stage=send_error status=command_outcome_unknown"))
        assertTrue(lastLine().contains("since_send=40"))
        assertEquals(1, tracker.entryCount())

        // 1. 结果未知保留 entry：后续 turn 阶段仍以原 send origin 记录
        clock.nowElapsed += 10
        tracker.onTurnStartedReceived("mobile:test", "turn-15", "cmid-15")
        assertTrue(lastLine().contains("turn=turn-15 cmid=cmid-15 stage=turn_started_received"))
        assertTrue(lastLine().contains("since_send=50"))
        clock.nowElapsed += 20
        tracker.onThinkingReceived("mobile:test", "turn-15")
        assertTrue(lastLine().contains("stage=first_thinking_received"))
        assertTrue(lastLine().contains("since_send=70"))
        clock.nowElapsed += 30
        tracker.onAnswerReceived("mobile:test", "turn-15")
        assertTrue(lastLine().contains("stage=first_answer_received"))
        assertTrue(lastLine().contains("since_send=100"))
        clock.nowElapsed += 50
        tracker.onTerminalReceived("mobile:test", "turn-15", "completed")
        assertTrue(lastLine().contains("stage=terminal_received status=completed"))
        assertTrue(lastLine().contains("since_send=150"))
        assertTrue(tracker.hasEntryForTest("mobile:test", "turn-15"))
    }

    @Test
    fun sendErrorUnknownOutcomeKeepsMigratedEntryAfterTurnStarted() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-16")
        tracker.onTurnStartedReceived("mobile:test", "turn-16", "cmid-16")
        clock.nowElapsed += 60
        tracker.onSendError("cmid-16", "command_outcome_unknown")
        assertTrue(
            lastLine().contains(
                "turn=turn-16 cmid=cmid-16 stage=send_error status=command_outcome_unknown",
            ),
        )
        assertTrue(lastLine().contains("since_send=60"))
        assertEquals(1, tracker.entryCount())
        assertTrue(tracker.hasEntryForTest("mobile:test", "turn-16"))

        // 1. 反向映射保留：迟到 ACK 仍解析到同一 entry，不是 origin=missing
        clock.nowElapsed += 5
        tracker.onServerAckReceived("cmid-16")
        assertTrue(lastLine().contains("turn=turn-16 cmid=cmid-16 stage=server_ack_received"))
        assertTrue(lastLine().contains("since_send=65"))

        // 2. 后续 thinking/answer/final 仍以原 send origin 记录
        clock.nowElapsed += 20
        tracker.onThinkingReceived("mobile:test", "turn-16")
        assertTrue(lastLine().contains("stage=first_thinking_received"))
        assertTrue(lastLine().contains("since_send=85"))
        clock.nowElapsed += 30
        tracker.onAnswerReceived("mobile:test", "turn-16")
        assertTrue(lastLine().contains("since_send=115"))
        clock.nowElapsed += 40
        tracker.onTerminalReceived("mobile:test", "turn-16", "cancelled")
        assertTrue(lastLine().contains("stage=terminal_received status=cancelled"))
        assertTrue(lastLine().contains("since_send=155"))
        assertTrue(tracker.hasEntryForTest("mobile:test", "turn-16"))
    }

    @Test
    fun sendErrorInProgressKeepsEntryAndOriginThroughFullTurn() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-17")
        clock.nowElapsed += 40
        tracker.onSendError("cmid-17", "command_in_progress")
        assertTrue(lastLine().contains("stage=send_error status=command_in_progress"))
        assertTrue(lastLine().contains("since_send=40"))
        assertEquals(1, tracker.entryCount())

        // 1. 原命令仍在执行：保留 entry 与反向映射，后续 turn 阶段以原 send origin 记录
        clock.nowElapsed += 10
        tracker.onTurnStartedReceived("mobile:test", "turn-17", "cmid-17")
        assertTrue(lastLine().contains("turn=turn-17 cmid=cmid-17 stage=turn_started_received"))
        assertTrue(lastLine().contains("since_send=50"))
        clock.nowElapsed += 20
        tracker.onThinkingReceived("mobile:test", "turn-17")
        assertTrue(lastLine().contains("stage=first_thinking_received"))
        assertTrue(lastLine().contains("since_send=70"))
        clock.nowElapsed += 30
        tracker.onAnswerReceived("mobile:test", "turn-17")
        assertTrue(lastLine().contains("stage=first_answer_received"))
        assertTrue(lastLine().contains("since_send=100"))
        clock.nowElapsed += 50
        tracker.onTerminalReceived("mobile:test", "turn-17", "completed")
        assertTrue(lastLine().contains("stage=terminal_received status=completed"))
        assertTrue(lastLine().contains("since_send=150"))
        assertTrue(tracker.hasEntryForTest("mobile:test", "turn-17"))
        // 2. 全程无 origin=missing，since_send 全部来自原 send origin
        assertFalse(lines.any { it.contains("origin=missing") })
    }

    @Test
    fun enqueueMessageSourceOrderCapturesSendOriginBeforeLaunchAndRecordsInsideLock() {
        // 1. 读真实源码断言精确顺序：captureSendOrigin < scope.launch < perform < withLock < recordSend
        val source = File("src/main/java/com/akashic/mobile/data/realtime/RealtimeSession.kt").readText()
        val start = source.indexOf("private fun enqueueMessage(")
        assertTrue("RealtimeSession.enqueueMessage 源码缺失", start >= 0)
        // 1. 从函数体开括号起计深度，避免签名里 onPersisted 默认值 {} 干扰
        val bodyOpen = source.indexOf("\n    ) {", start) + 1
        assertTrue("RealtimeSession.enqueueMessage 函数体缺失", bodyOpen > start)
        var depth = 1
        var end = bodyOpen
        while (end < source.length) {
            when (source[end]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) break
                }
            }
            end += 1
        }
        assertTrue("RealtimeSession.enqueueMessage 源码括号不闭合", depth == 0)
        val capture = source.indexOf("val sendOrigin = turnTrace.captureSendOrigin()", start)
        val launch = source.indexOf("scope.launch {", start)
        val perform = source.indexOf("attachmentOperations.perform {", start)
        val lock = source.indexOf("mutex.withLock {", start)
        val record = source.indexOf("turnTrace.recordSend(sessionId, clientMessageId, sendOrigin)", start)
        assertTrue("captureSendOrigin 必须位于 enqueueMessage 内", capture >= start && capture < end)
        assertTrue("captureSendOrigin 必须先于 scope.launch", capture < launch)
        assertTrue("scope.launch 必须先于 attachmentOperations.perform", launch < perform)
        assertTrue("attachmentOperations.perform 必须先于 mutex.withLock", perform < lock)
        assertTrue("mutex.withLock 必须先于 recordSend", lock < record)
        assertTrue("recordSend 必须位于 enqueueMessage 内", record < end)
    }

    @Test
    fun terminalStatusPreservesAuthoritativeValues() {
        val tracker = tracker()
        tracker.onTurnStartedReceived("mobile:test", "turn-13a", "cmid-13a")
        tracker.onTerminalReceived("mobile:test", "turn-13a", "cancelled")
        assertTrue(lastLine().contains("stage=terminal_received status=cancelled"))
        tracker.onTurnStartedReceived("mobile:test", "turn-13b", null)
        tracker.onTerminalReceived("mobile:test", "turn-13b", "idle")
        assertTrue(lastLine().contains("stage=terminal_received status=idle"))
        tracker.onTurnStartedReceived("mobile:test", "turn-13c", null)
        tracker.onTerminalReceived("mobile:test", "turn-13c", "failed")
        assertTrue(lastLine().contains("stage=terminal_received status=failed"))
    }

    @Test
    fun missingSendEntryLogsOriginMissingInsteadOfSilentReturn() {
        val tracker = tracker()
        tracker.onLocalOutboxCommitted("mobile:test", "cmid-lost")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-lost stage=local_outbox_committed"))
        tracker.onServerAckReceived("cmid-lost")
        assertTrue(lastLine().contains("origin=missing key=send:cmid-lost stage=server_ack_received"))
        tracker.onTerminalReceived("mobile:test", "turn-lost", "completed")
        assertTrue(lastLine().contains("origin=missing"))
        assertTrue(lastLine().contains("stage=terminal_received status=completed"))
        assertEquals(0, tracker.entryCount())
    }

    @Test
    fun localOutboxCommitAndDispatchValidateSession() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-12")
        try {
            tracker.onLocalOutboxCommitted("other:session", "cmid-12")
            throw AssertionError("session mismatch must fail loudly")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("session mismatch"))
        }
        try {
            tracker.onSocketDispatched("other:session", "cmid-12")
            throw AssertionError("session mismatch must fail loudly")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("session mismatch"))
        }
    }

    @Test
    fun cmidReverseLookupRejectedWhenSameSessionReusesTurn() {
        val tracker = tracker()
        bindSend(tracker, "mobile:test", "cmid-14")
        tracker.onTurnStartedReceived("mobile:test", "turn-11a", "cmid-14")
        // 同 session 复用 cmid 指向旧 turn：反查 entry 的 turnId 不匹配必须 fail-loud
        try {
            tracker.onUiProjected(
                sessionId = "mobile:test",
                turnId = "turn-11b",
                clientMessageId = "cmid-14",
                hasThinking = true,
                hasAnswer = false,
                streaming = true,
            )
            throw AssertionError("turn mismatch must fail loudly")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("turn mismatch"))
        }
    }

    @Test
    fun turnStartedReplayFillsMissingClientMessageId() {
        val tracker = tracker()
        tracker.onTurnStartedReceived("mobile:test", "turn-9", null)
        assertEquals(1, tracker.entryCount())
        // 1. 已有 entry 补写 cmid：后续阶段日志带完整三元组
        tracker.onTurnStartedReceived("mobile:test", "turn-9", "cmid-9")
        tracker.onTerminalReceived("mobile:test", "turn-9", "completed")
        assertTrue(lastLine().contains("turn=turn-9 cmid=cmid-9 stage=terminal_received"))
        // 2. cmid 不一致时 fail-loud
        try {
            tracker.onTurnStartedReceived("mobile:test", "turn-9", "cmid-wrong")
            throw AssertionError("cmid mismatch must fail loudly")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("client_message_id mismatch"))
        }
    }

    @Test
    fun lateEventAfterFullClosureNeverCreatesFreshEntry() {
        val tracker = tracker()
        tracker.onTurnStartedReceived("mobile:test", "turn-10", "cmid-10")
        tracker.onTerminalReceived("mobile:test", "turn-10", "completed")
        tracker.onRoomCommitted("mobile:test", "turn-10", TurnTraceStage.TERMINAL_COMMITTED)
        tracker.onTurnCleared("mobile:test", "turn-10")
        tracker.onCanStopFalse("mobile:test", "turn-10")
        tracker.onComposerReady("mobile:test", "turn-10")
        tracker.onWebViewPatch("mobile:test", "turn-10", "cmid-10", false, false, true)
        assertEquals(0, tracker.entryCount())

        // 已收尾 turn 的晚事件只能 origin=missing，绝不重建条目
        tracker.onTerminalReceived("mobile:test", "turn-10", "interrupted")
        tracker.onRoomCommitted("mobile:test", "turn-10", TurnTraceStage.TERMINAL_COMMITTED)
        tracker.onThinkingReceived("mobile:test", "turn-10")
        assertEquals(0, tracker.entryCount())
        assertTrue(lines.any { it.contains("origin=missing key=turn:mobile:test:turn-10") })
    }
}
