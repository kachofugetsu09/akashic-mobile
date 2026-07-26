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
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWebSnapshotTest {
    @Test
    fun createsPatchForAppendOnlyStreamingAnswer() {
        val beforeMessage = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "mobile:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
            updatedAtMillis = 1_100,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "mobile:test",
            projectionGeneration = 7,
            messages = listOf(beforeMessage),
            isStreaming = true,
        )
        val after = before.copy(
            sessions = listOf(
                SessionUi("mobile:test", "会话", "正在分析", 1_200, 0, true, true),
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

        assertEquals(1, patch?.protocolVersion)
        assertEquals(7L, patch?.projectionGeneration)
        assertEquals(0, patch?.messageIndex)
        assertEquals("正在分析", patch?.message?.content)
    }

    @Test
    fun createsPatchForAppendOnlyThinkingAndRejectsStructuralChange() {
        val thinking = ProcessBlockUi(
            id = "thinking-1",
            kind = ProcessBlockKind.THINKING,
            title = "思考",
            detail = "先",
            state = ProcessBlockState.RUNNING,
        )
        val beforeMessage = MessageUi.AssistantTurn(
            id = "assistant:turn-1",
            sessionId = "mobile:test",
            intro = null,
            blocks = listOf(thinking),
            answer = "",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = null,
            createdAtMillis = 1_000,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "mobile:test",
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
            "先检查调用链",
            appended.toMobileWebStreamPatch(before)?.message?.blocks?.single()?.detail,
        )
        assertEquals(null, completed.toMobileWebStreamPatch(appended))
    }

    @Test
    fun streamingPatchDoesNotSerializeConversationHistory() {
        val history = List(400) { index ->
            MessageUi.User(
                id = "user:$index",
                sessionId = "mobile:test",
                text = "历史消息-$index-${"内容".repeat(150)}",
                deliveryLabel = "已发送",
                replyable = true,
                createdAtMillis = index.toLong(),
                reply = null,
            )
        }
        val streaming = MessageUi.AssistantTurn(
            id = "assistant:streaming",
            sessionId = "mobile:test",
            intro = null,
            blocks = emptyList(),
            answer = "正在",
            status = AssistantTurnStatus.STREAMING,
            durationSeconds = 1,
            createdAtMillis = 1_000,
        )
        val before = EmptyConversationState.copy(
            selectedSessionId = "mobile:test",
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
                    sessionId = "mobile:test",
                    title = "正在执行",
                    lastMessagePreview = "后台任务仍在处理",
                    lastMessageAtMillis = 1_752_681_601_000,
                    unreadCount = 1,
                    isRunning = true,
                    isAvailable = false,
                ),
            ),
            selectedSessionId = "mobile:test",
            readingPosition = ReadingPositionUi("message-1", -18),
            navigationTarget = NavigationTargetUi("mobile:test", "message-2"),
            projectionGeneration = 7,
            messages = listOf(
                MessageUi.User(
                    id = "message-1",
                    sessionId = "mobile:test",
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
                    sessionId = "mobile:test",
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

        assertEquals(6, snapshot.protocolVersion)
        assertEquals(7, snapshot.projectionGeneration)
        assertEquals(MobileWebConnectionStatus.RECONNECTING, snapshot.connection.status)
        assertTrue(snapshot.sessions.single().isRunning)
        assertTrue(!snapshot.sessions.single().isAvailable)
        assertEquals(listOf("message-1", "message-2"), snapshot.messages.map { it.id })
        assertEquals(
            listOf(1_752_681_600_100, 1_752_681_601_200),
            snapshot.messages.map { it.searchRevision },
        )
        assertEquals(listOf("mobile:test", "mobile:test"), snapshot.messages.map { it.sessionId })
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
        assertEquals(6, Json.parseToJsonElement(encoded).jsonObject
            .getValue("protocolVersion").jsonPrimitive.content.toInt())
        assertTrue(encoded.contains("\"status\":\"reconnecting\""))
        assertTrue(encoded.contains("\"deliveryAction\":\"retry\""))
    }
}
