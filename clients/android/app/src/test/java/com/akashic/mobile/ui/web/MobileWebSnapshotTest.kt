package com.akashic.mobile.ui.web

import com.akashic.mobile.ui.conversation.ConnectionStatusUi
import com.akashic.mobile.ui.conversation.ConversationUiState
import com.akashic.mobile.ui.conversation.EmptyConversationState
import com.akashic.mobile.ui.conversation.AssistantTurnStatus
import com.akashic.mobile.ui.conversation.CommandUi
import com.akashic.mobile.ui.conversation.ComposerDraftUi
import com.akashic.mobile.ui.conversation.MessageUi
import com.akashic.mobile.ui.conversation.MessageDeliveryActionUi
import com.akashic.mobile.ui.conversation.MessageReplyUi
import com.akashic.mobile.ui.conversation.ProcessBlockKind
import com.akashic.mobile.ui.conversation.ProcessBlockState
import com.akashic.mobile.ui.conversation.ProcessBlockUi
import com.akashic.mobile.ui.conversation.PendingMessageUi
import com.akashic.mobile.ui.conversation.ReadingPositionUi
import com.akashic.mobile.ui.conversation.NavigationTargetUi
import com.akashic.mobile.ui.conversation.TransferStatusUi
import com.akashic.mobile.ui.conversation.SessionUi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWebSnapshotTest {
    @Test
    fun defersHistoryGrowthUntilResyncBecomesReady() {
        val delivered = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 7,
            messages = listOf(userMessage("message-1")),
            isResyncing = true,
        )
        val nextPage = delivered.copy(
            messages = listOf(userMessage("message-1"), userMessage("message-2")),
        )

        assertTrue(shouldDeferResyncSnapshot(delivered, nextPage))
        assertEquals(false, shouldDeferResyncSnapshot(delivered, nextPage.copy(isResyncing = false)))
        assertEquals(false, shouldDeferResyncSnapshot(null, nextPage))
    }

    @Test
    fun createsPatchForAppendOnlyStreamingAnswer() {
        val beforeMessage = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
            updatedAtMillis = 1_100,
            clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 7,
            messages = listOf(beforeMessage),
            isStreaming = true,
        )
        val after = before.copy(
            sessions = listOf(
                SessionUi("akashic:test", "会话", "正在分析", 1_200, 0, true, true),
            ),
            messages = listOf(
                beforeMessage.copy(
                    answer = "正在分析",
                    durationSeconds = 2,
                    updatedAtMillis = 1_200,
                ),
            ),
        )

        val patch = after.toMobileWebStreamPatch(before)

        assertEquals(3, patch?.protocolVersion)
        assertEquals(7L, patch?.projectionGeneration)
        assertEquals(0, patch?.messageIndex)
        assertEquals("分析", patch?.contentAppend)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", patch?.clientMessageId)
        assertEquals(null, patch?.message)
    }

    @Test
    fun createsPatchForThinkingAndCommitsCompletedTurn() {
        val thinking = ProcessBlockUi(
            id = "thinking-1",
            kind = ProcessBlockKind.THINKING,
            title = "思考",
            detail = "先",
            state = ProcessBlockState.RUNNING,
        )
        val beforeMessage = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = listOf(thinking),
            answer = "",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = null,
            createdAtMillis = 1_000,
            clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV",
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            messages = listOf(beforeMessage),
            isStreaming = true,
        )
        val appended = before.copy(
            messages = listOf(
                beforeMessage.copy(blocks = listOf(thinking.copy(detail = "先检查调用链"))),
            ),
        )
        val completed = appended.copy(
            messages = listOf(
                beforeMessage.copy(
                    blocks = listOf(thinking.copy(detail = "先检查调用链")),
                    status = AssistantTurnStatus.COMPLETE,
                ),
            ),
            isStreaming = false,
        )

        assertEquals(
            "检查调用链",
            appended.toMobileWebStreamPatch(before)?.thinkingAppend?.delta,
        )
        val terminal = completed.toMobileWebStreamPatch(appended)
        assertEquals(false, terminal?.message?.streaming)
        assertEquals(false, terminal?.state?.composer?.isStreaming)
        assertEquals("assistant:turn-1", terminal?.messageId)
        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", terminal?.clientMessageId)
    }

    @Test
    fun rejectsStreamPatchWithInvalidClientMessageId() {
        val beforeMessage = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
            clientMessageId = "not-a-frame-id",
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            messages = listOf(beforeMessage),
            isStreaming = true,
        )
        val after = before.copy(
            messages = listOf(beforeMessage.copy(answer = "正在分析", updatedAtMillis = 1_200)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            after.toMobileWebStreamPatch(before)
        }
    }

    @Test
    fun createsPatchForToolInsertionAndToolStateChange() {
        val thinking = ProcessBlockUi(
            id = "thinking-1",
            kind = ProcessBlockKind.THINKING,
            title = "思考",
            detail = "检查完成",
            state = ProcessBlockState.COMPLETED,
        )
        val tool = ProcessBlockUi(
            id = "tool-1",
            kind = ProcessBlockKind.TOOL,
            title = "读取文件",
            detail = "读取配置",
            state = ProcessBlockState.RUNNING,
        )
        val message = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = listOf(thinking),
            answer = "",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = null,
            createdAtMillis = 1_000,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            messages = listOf(message),
            isStreaming = true,
        )
        val running = before.copy(messages = listOf(message.copy(blocks = listOf(thinking, tool))))
        val finished = running.copy(
            messages = listOf(
                message.copy(
                    blocks = listOf(
                        thinking,
                        tool.copy(state = ProcessBlockState.COMPLETED, resultPreview = "完成"),
                    ),
                ),
            ),
        )

        assertEquals(
            MobileWebProcessState.RUNNING,
            running.toMobileWebStreamPatch(before)?.message?.blocks?.last()?.state,
        )
        assertEquals(
            MobileWebProcessState.COMPLETED,
            finished.toMobileWebStreamPatch(running)?.message?.blocks?.last()?.state,
        )
    }

    @Test
    fun streamingPatchDoesNotSerializeConversationHistory() {
        val history = List(400) { index ->
            MessageUi.User(
                id = "user:$index",
                sessionId = "akashic:test",
                text = "历史消息-$index-${"内容".repeat(150)}",
                deliveryLabel = "已发送",
                replyable = true,
                createdAtMillis = index.toLong(),
                reply = null,
            )
        }
        val streaming = MessageUi.AssistantTurn(
            id = "assistant:streaming",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 9,
            messages = history + streaming,
            isStreaming = true,
        )
        val after = before.copy(
            messages = history + streaming.copy(answer = "正在检查调用链"),
        )

        val patchJson = Json.encodeToString(after.toMobileWebStreamPatch(before))
        val snapshotJson = Json.encodeToString(after.toMobileWebSnapshot())

        assertTrue(snapshotJson.length > 100_000)
        assertTrue(patchJson.length * 100 < snapshotJson.length)
        assertTrue(!patchJson.contains("正在检查调用链"))
        assertTrue(patchJson.contains("检查调用链"))
    }

    @Test
    fun terminalPatchDoesNotSerializeConversationHistoryOrRequireStableMessageId() {
        val history = List(400) { index ->
            MessageUi.User(
                id = "user:$index",
                sessionId = "akashic:test",
                text = "历史消息-$index-${"内容".repeat(150)}",
                deliveryLabel = "已发送",
                replyable = true,
                createdAtMillis = index.toLong(),
                reply = null,
            )
        }
        val streaming = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在检查调用链",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 2,
            createdAtMillis = 1_000,
            updatedAtMillis = 1_200,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 9,
            messages = history + streaming,
            isStreaming = true,
        )
        val after = before.copy(
            sessions = listOf(
                SessionUi("akashic:test", "会话", "检查完成", 1_300, 0, false, true),
            ),
            messages = history + streaming.copy(
                id = "message:canonical",
                answer = "检查调用链完成",
                status = AssistantTurnStatus.COMPLETE,
                durationSeconds = 3,
                updatedAtMillis = 1_300,
            ),
            isStreaming = false,
        )

        val patch = after.toMobileWebStreamPatch(before)
        val patchJson = Json.encodeToString(patch)
        val snapshotJson = Json.encodeToString(after.toMobileWebSnapshot())

        assertEquals("assistant:turn-1", patch?.messageId)
        assertEquals("message:canonical", patch?.message?.id)
        assertEquals(false, patch?.state?.composer?.isStreaming)
        assertTrue(snapshotJson.length > 100_000)
        assertTrue(patchJson.length * 100 < snapshotJson.length)
        assertTrue(!patchJson.contains("历史消息-399"))
    }

    @Test
    fun fullSnapshotStillIdentifiesCanonicalTerminalTransition() {
        val clientMessageId = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        val user = MessageUi.User(
            id = "user:$clientMessageId",
            sessionId = "akashic:test",
            text = "问题",
            deliveryLabel = "已发送",
            replyable = true,
            createdAtMillis = 900,
            reply = null,
            clientMessageId = clientMessageId,
        )
        val streaming = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "回答",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
            clientMessageId = clientMessageId,
            controlTurnId = "turn-1",
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 9,
            messages = listOf(user, streaming),
            isStreaming = true,
        )
        val after = before.copy(
            messages = listOf(
                user.copy(id = "message:user:canonical", deliveryLabel = "已完成"),
                streaming.copy(
                    id = "message:assistant:canonical",
                    status = AssistantTurnStatus.COMPLETE,
                ),
            ),
            isStreaming = false,
        )

        assertEquals(null, after.toMobileWebStreamPatch(before))
        assertEquals(
            MobileWebTerminalTransition("akashic:test", "turn-1", clientMessageId),
            after.terminalTransitionFrom(before),
        )
    }

    @Test
    fun controlTurnIdIdentifiesLegacyTerminalWithoutClientIdentity() {
        val streaming = MessageUi.AssistantTurn(
            id = "assistant:turn-legacy",
            sessionId = "akashic:test",
            intro = null,
            blocks = emptyList(),
            answer = "回答",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            messages = listOf(streaming),
            isStreaming = true,
        )
        val after = before.copy(
            messages = listOf(
                streaming.copy(
                    id = "message:canonical",
                    status = AssistantTurnStatus.COMPLETE,
                    controlTurnId = "turn-legacy",
                ),
            ),
            isStreaming = false,
        )

        assertEquals(
            MobileWebTerminalTransition("akashic:test", "turn-legacy", null),
            after.terminalTransitionFrom(before),
        )
    }

    @Test
    fun controlStatePatchDoesNotSerializeUnchangedConversationHistory() {
        val history = List(400) { index ->
            MessageUi.User(
                id = "user:$index",
                sessionId = "akashic:test",
                text = "历史消息-$index-${"内容".repeat(150)}",
                deliveryLabel = "已发送",
                replyable = true,
                createdAtMillis = index.toLong(),
                reply = null,
            )
        }
        val before = EmptyConversationState.copy(
            selectedSessionId = "akashic:test",
            projectionGeneration = 9,
            messages = history,
        )
        val after = before.copy(connectionNotice = "消息已缓存")

        val patchJson = Json.encodeToString(after.toMobileWebStatePatch(before))
        val snapshotJson = Json.encodeToString(after.toMobileWebSnapshot())

        assertTrue(snapshotJson.length > 100_000)
        assertTrue(patchJson.length * 100 < snapshotJson.length)
        assertEquals(null, after.copy(projectionGeneration = 10).toMobileWebStatePatch(before))
    }

    @Test
    fun serializesVersionedConversationSnapshot() {
        val snapshot = ConversationUiState(
            connectionLabel = "正在重连",
            connectionStatus = ConnectionStatusUi.RECONNECTING,
            connectionNotice = "消息已缓存",
            errorNotice = null,
            sessions = listOf(
                SessionUi(
                    sessionId = "akashic:test",
                    title = "正在执行",
                    lastMessagePreview = "后台任务仍在处理",
                    lastMessageAtMillis = 1_752_681_601_000,
                    unreadCount = 1,
                    isRunning = true,
                    isAvailable = false,
                ),
            ),
            selectedSessionId = "akashic:test",
            readingPosition = ReadingPositionUi("message-1", -18),
            navigationTarget = NavigationTargetUi("akashic:test", "message-2"),
            projectionGeneration = 7,
            messages = listOf(
                MessageUi.User(
                    id = "message-1",
                    sessionId = "akashic:test",
                    text = "你好",
                    deliveryLabel = "发送失败",
                    replyable = false,
                    deliveryAction = MessageDeliveryActionUi.RETRY,
                    createdAtMillis = 1_752_681_600_000,
                    updatedAtMillis = 1_752_681_600_100,
                    reply = MessageReplyUi("message-0", "assistant", "之前的回答"),
                ),
                MessageUi.AssistantTurn(
                    id = "message-2",
                    sessionId = "akashic:test",
                    intro = null,
                    blocks = listOf(
                        ProcessBlockUi(
                            id = "tool-1",
                            kind = ProcessBlockKind.TOOL,
                            title = "read_file",
                            detail = "读取上下文",
                            state = ProcessBlockState.COMPLETED,
                            arguments = buildJsonObject {
                                put("path", "/sandbox/context.md")
                            },
                            resultPreview = "读取完成",
                            durationMillis = 840,
                        ),
                    ),
                    answer = "正在处理",
                    status = AssistantTurnStatus.STREAMING,
                    durationSeconds = null,
                    createdAtMillis = 1_752_681_601_000,
                    updatedAtMillis = 1_752_681_601_200,
                ),
            ),
            attachments = emptyList(),
            composerDraft = ComposerDraftUi("继续检查草稿", "message-1", 1_752_681_602_000),
            pendingMessages = listOf(PendingMessageUi("message-1", "你好", 1_752_681_600_000)),
            transferStatus = TransferStatusUi(
                title = "大文件上传已暂停",
                detail = "当前为移动网络，确认后会从 42% 继续",
                progressPercent = 42,
                requiresMeteredApproval = true,
            ),
            commands = listOf(CommandUi("memorystatus", "查看记忆整理状态")),
            isStreaming = true,
            isResyncing = false,
            canResync = false,
            isStopping = false,
            canStop = false,
            canSend = true,
        ).toMobileWebSnapshot()

        val encoded = Json.encodeToString(snapshot)

        assertEquals(8, snapshot.protocolVersion)
        assertTrue(snapshot.runtimeInspection.documents.isEmpty())
        assertEquals(7, snapshot.projectionGeneration)
        assertEquals(MobileWebConnectionStatus.RECONNECTING, snapshot.connection.status)
        assertTrue(snapshot.sessions.single().isRunning)
        assertTrue(!snapshot.sessions.single().isAvailable)
        assertEquals(listOf("message-1", "message-2"), snapshot.messages.map { it.id })
        assertEquals(
            listOf(1_752_681_600_100, 1_752_681_601_200),
            snapshot.messages.map { it.searchRevision },
        )
        assertEquals(listOf("akashic:test", "akashic:test"), snapshot.messages.map { it.sessionId })
        assertTrue(!snapshot.messages.first().replyable)
        assertEquals(MobileWebDeliveryAction.RETRY, snapshot.messages.first().deliveryAction)
        assertTrue(!snapshot.messages.last().replyable)
        assertTrue(snapshot.messages.last().streaming)
        val tool = snapshot.messages.last().blocks.single()
        assertEquals(MobileWebProcessState.COMPLETED, tool.state)
        assertEquals("/sandbox/context.md", tool.arguments?.get("path")?.jsonPrimitive?.content)
        assertEquals("读取完成", tool.resultPreview)
        assertEquals(840L, tool.durationMillis)
        assertEquals("memorystatus", snapshot.composer.commands.single().command)
        assertEquals(42, snapshot.composer.transferStatus?.progressPercent)
        assertTrue(snapshot.composer.transferStatus?.requiresMeteredApproval == true)
        assertEquals("之前的回答", snapshot.messages.first().reply?.preview)
        assertEquals(-18, snapshot.readingPosition?.offsetPx)
        assertEquals("message-2", snapshot.navigationTarget?.messageId)
        assertEquals("message-1", snapshot.composer.pendingMessages.single().messageId)
        assertEquals("继续检查草稿", snapshot.composer.draft.text)
        assertEquals("message-1", snapshot.composer.draft.replyToMessageId)
        assertEquals(1_752_681_602_000, snapshot.composer.draft.updatedAt)
        assertEquals(8, Json.parseToJsonElement(encoded).jsonObject
            .getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertTrue(encoded.contains("\"status\":\"reconnecting\""))
        assertTrue(encoded.contains("\"deliveryAction\":\"retry\""))
    }
}

private fun userMessage(id: String) = MessageUi.User(
    id = id,
    sessionId = "akashic:test",
    text = id,
    deliveryLabel = "已发送",
    replyable = true,
    createdAtMillis = 1_000,
    reply = null,
)
