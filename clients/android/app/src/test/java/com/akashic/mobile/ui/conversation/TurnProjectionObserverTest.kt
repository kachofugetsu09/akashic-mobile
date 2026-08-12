package com.akashic.mobile.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnProjectionObserverTest {
    private fun assistant(
        id: String,
        streaming: Boolean,
        blocks: List<ProcessBlockUi> = emptyList(),
        answer: String = "回答",
        clientMessageId: String? = null,
    ) = MessageUi.AssistantTurn(
        id = id,
        sessionId = "mobile:test",
        intro = null,
        blocks = blocks,
        answer = answer,
        status = if (streaming) AssistantTurnStatus.STREAMING else AssistantTurnStatus.COMPLETE,
        durationSeconds = null,
        createdAtMillis = 1_000,
        clientMessageId = clientMessageId,
    )

    private fun thinking(detail: String) = ProcessBlockUi(
        id = "think-1",
        kind = ProcessBlockKind.THINKING,
        title = "思考",
        detail = detail,
        state = ProcessBlockState.COMPLETED,
    )

    private fun active(
        observer: TurnProjectionObserver,
        sessionId: String,
        turnId: String,
        streaming: Boolean,
        messages: List<MessageUi> = listOf(assistant("assistant:$turnId", streaming)),
    ) = observer.observe(messages, sessionId, turnId)

    @Test
    fun composerReadyFiresOnceAfterTerminalProjection() {
        val observer = TurnProjectionObserver()
        // 1. 流式中：只观测，不触发任何边沿
        val streaming = active(observer, "mobile:test", "turn-a", streaming = true)
        assertEquals("turn-a", streaming.targetTurnId)
        assertEquals(true, streaming.streaming)
        assertFalse(streaming.canStopFalse)
        assertFalse(streaming.composerReady)

        // 2. 权威终态投影：canStop 回 false，composer_ready 恰好一次
        val closed = observer.observe(
            listOf(assistant("assistant:turn-a", streaming = false)),
            "mobile:test",
            null,
        )
        assertTrue(closed.canStopFalse)
        assertTrue(closed.composerReady)

        // 3. 重复 emission 不再触发
        val repeat = observer.observe(
            listOf(assistant("assistant:turn-a", streaming = false)),
            "mobile:test",
            null,
        )
        assertFalse(repeat.canStopFalse)
        assertFalse(repeat.composerReady)
    }

    @Test
    fun terminalProjectionWhileActiveKeepsStreamingEdgeThenFiresOnceOnClose() {
        val observer = TurnProjectionObserver()
        // 1. 活动流式中：只观测
        val streaming = active(observer, "mobile:test", "turn-a", streaming = true)
        assertFalse(streaming.canStopFalse)
        assertFalse(streaming.composerReady)

        // 2. active 仍非空但投影已终态：不得删除 sawStreaming，也不得提前产出
        val stillActive = observer.observe(
            listOf(assistant("assistant:turn-a", streaming = false)),
            "mobile:test",
            "turn-a",
        )
        assertFalse(stillActive.canStopFalse)
        assertFalse(stillActive.composerReady)

        // 3. active 清空后的权威终态投影：同次产出 canStopFalse/composerReady 并清理
        val closed = observer.observe(
            listOf(assistant("assistant:turn-a", streaming = false)),
            "mobile:test",
            null,
        )
        assertTrue(closed.canStopFalse)
        assertTrue(closed.composerReady)

        // 4. 重复 emission 恰好一次
        val repeat = observer.observe(
            listOf(assistant("assistant:turn-a", streaming = false)),
            "mobile:test",
            null,
        )
        assertFalse(repeat.canStopFalse)
        assertFalse(repeat.composerReady)
    }

    @Test
    fun canonicalMessageIdKeepsTerminalProjectionBoundToActiveTurn() {
        val observer = TurnProjectionObserver()
        val clientMessageId = "client-a"
        // 1. 流式临时消息建立 turn 与客户端发件身份的关联
        active(
            observer,
            "mobile:test",
            "turn-a",
            streaming = true,
            messages = listOf(
                assistant(
                    "assistant:turn-a",
                    streaming = true,
                    clientMessageId = clientMessageId,
                ),
            ),
        )

        // 2. terminal 投影已迁移到 canonical 消息 ID，active 尚未清空
        val canonical = assistant(
            "message:canonical",
            streaming = false,
            clientMessageId = clientMessageId,
        )
        val stillActive = observer.observe(listOf(canonical), "mobile:test", "turn-a")
        assertEquals("message:canonical", stillActive.observed?.id)
        assertFalse(stillActive.canStopFalse)

        // 3. coordinator 清空 active 后仍定位同一 canonical 投影并触发一次闭包
        val closed = observer.observe(listOf(canonical), "mobile:test", null)
        assertEquals("turn-a", closed.targetTurnId)
        assertEquals("message:canonical", closed.observed?.id)
        assertTrue(closed.canStopFalse)
        assertTrue(closed.composerReady)

        val repeat = observer.observe(listOf(canonical), "mobile:test", null)
        assertFalse(repeat.canStopFalse)
        assertFalse(repeat.composerReady)
    }

    @Test
    fun controlTurnIdClosesLegacyProjectionWithoutClientIdentity() {
        val observer = TurnProjectionObserver()
        val streaming = assistant(
            "assistant:turn-legacy",
            streaming = true,
        ).copy(controlTurnId = "turn-legacy")
        observer.observe(listOf(streaming), "mobile:test", "turn-legacy")

        val canonical = streaming.copy(
            id = "message:canonical",
            status = AssistantTurnStatus.COMPLETE,
        )
        val closed = observer.observe(listOf(canonical), "mobile:test", null)

        assertEquals("message:canonical", closed.observed?.id)
        assertTrue(closed.canStopFalse)
        assertTrue(closed.composerReady)
    }

    @Test
    fun replacedActiveTurnDoesNotPolluteClosureWithOldStreaming() {
        val observer = TurnProjectionObserver()
        // 1. 旧 turn 曾流式
        active(observer, "mobile:test", "turn-old", streaming = true)

        // 2. active 直接换成新 turn，且新 turn 未流式即终态
        val replaced = observer.observe(
            listOf(assistant("assistant:turn-new", streaming = false)),
            "mobile:test",
            "turn-new",
        )
        assertFalse(replaced.composerReady)

        // 3. 新 turn 关闭：旧 turn 的 streaming 边沿不得误触发 composer_ready
        val closed = observer.observe(
            listOf(assistant("assistant:turn-new", streaming = false)),
            "mobile:test",
            null,
        )
        assertTrue(closed.canStopFalse)
        assertFalse(closed.composerReady)

        // 4. 清理后旧 turn 的边沿不会再次污染后续 turn
        val later = observer.observe(
            listOf(assistant("assistant:turn-later", streaming = false)),
            "mobile:test",
            "turn-later",
        )
        assertFalse(later.composerReady)
    }

    @Test
    fun replacedActiveTurnThatStreamsStillFiresComposerReadyOnClose() {
        val observer = TurnProjectionObserver()
        active(observer, "mobile:test", "turn-old", streaming = true)

        // 1. 新 turn 替换旧 turn 后确实流式：streaming 边沿归属新 turn
        val streaming = observer.observe(
            listOf(assistant("assistant:turn-new", streaming = true)),
            "mobile:test",
            "turn-new",
        )
        assertFalse(streaming.composerReady)

        // 2. 新 turn 关闭：按新 turn 自己的 streaming 边沿触发 composer_ready
        val closed = observer.observe(
            listOf(assistant("assistant:turn-new", streaming = false)),
            "mobile:test",
            null,
        )
        assertTrue(closed.canStopFalse)
        assertTrue(closed.composerReady)
    }

    @Test
    fun sessionSwitchDoesNotLeakStreamingEdge() {
        val observer = TurnProjectionObserver()
        // 1. 会话 A 正在流式
        active(observer, "mobile:a", "turn-a1", streaming = true)

        // 2. 切到会话 B：B 无活动 turn，不得触发任何 A 的边沿
        val onB = observer.observe(emptyList(), "mobile:b", null)
        assertNull(onB.targetTurnId)
        assertFalse(onB.canStopFalse)
        assertFalse(onB.composerReady)

        // 3. 回到 A：A 已终态，只对 A 触发，B 不受影响
        val backOnA = observer.observe(
            listOf(assistant("assistant:turn-a1", streaming = false)),
            "mobile:a",
            null,
        )
        assertTrue(backOnA.canStopFalse)
        assertTrue(backOnA.composerReady)

        // 4. B 之后有新 turn 也不会因为 A 的 streaming 状态误触发
        val onB2 = observer.observe(
            listOf(assistant("assistant:turn-b1", streaming = true)),
            "mobile:b",
            "turn-b1",
        )
        assertFalse(onB2.composerReady)
    }

    @Test
    fun emptyThinkingBlockIsNotVisibleFirstChar() {
        val observer = TurnProjectionObserver()
        val result = observer.observe(
            listOf(assistant("assistant:turn-c", streaming = true, blocks = listOf(thinking("  ")))),
            "mobile:test",
            "turn-c",
        )
        assertEquals(true, result.streaming)
        assertFalse(result.hasThinking)

        val visible = observer.observe(
            listOf(assistant("assistant:turn-c", streaming = true, blocks = listOf(thinking("先分析")))),
            "mobile:test",
            "turn-c",
        )
        assertTrue(visible.hasThinking)
    }

    @Test
    fun lateClosedTurnOnlyObservedInItsSession() {
        val observer = TurnProjectionObserver()
        active(observer, "mobile:a", "turn-a1", streaming = true)
        // 关闭发生在用户停留在其他会话时，回到 A 后仍能完成观测
        val back = observer.observe(
            listOf(assistant("assistant:turn-a1", streaming = false)),
            "mobile:a",
            null,
        )
        assertTrue(back.composerReady)
    }

    @Test
    fun assistantLocatedFromTailNotFullHistoryScan() {
        val observer = TurnProjectionObserver()
        val messages = listOf(
            assistant("assistant:old", streaming = false, answer = "旧"),
            assistant("assistant:new", streaming = true, answer = "新"),
        )
        val result = observer.observe(messages, "mobile:test", "new")
        assertEquals("assistant:new", result.observed?.id)
        assertEquals(true, result.streaming)

        val fromTail = assistantTurnFromTail(messages, "old")
        assertEquals("assistant:old", fromTail?.id)
    }
}
