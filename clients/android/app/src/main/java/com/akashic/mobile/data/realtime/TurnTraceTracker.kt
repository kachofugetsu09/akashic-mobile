package com.akashic.mobile.data.realtime

import android.os.SystemClock
import android.util.Log
import java.time.Instant

/** 单调时间与可读墙钟抽象；生产用 SystemClock，JVM 测试注入 fake。 */
interface TurnTraceClock {
    fun elapsedMillis(): Long
    fun wallMillis(): Long
}

object SystemTurnTraceClock : TurnTraceClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()
    override fun wallMillis(): Long = System.currentTimeMillis()
}

/** 发送入口时刻的单调与墙钟快照，cmid 生成后绑定为 send origin。 */
data class SendOrigin(val elapsedMillis: Long, val wallMillis: Long)

/** turn 生命周期各阶段；received/committed 分离，webview 阶段只描述跨桥投递。 */
enum class TurnTraceStage {
    SEND_REQUESTED,
    LOCAL_OUTBOX_COMMITTED,
    SOCKET_DISPATCHED,
    SERVER_ACK_RECEIVED,
    SERVER_ACK_COMMITTED,
    SEND_ERROR,
    TURN_STARTED_RECEIVED,
    TURN_STARTED_COMMITTED,
    FIRST_THINKING_RECEIVED,
    FIRST_THINKING_COMMITTED,
    FIRST_ANSWER_RECEIVED,
    FIRST_ANSWER_COMMITTED,
    TERMINAL_RECEIVED,
    TERMINAL_COMMITTED,
    UI_FIRST_THINKING,
    UI_FIRST_ANSWER,
    UI_TERMINAL,
    CAN_STOP_FALSE,
    COMPOSER_READY,
    WEBVIEW_FIRST_THINKING_DISPATCHED,
    WEBVIEW_FIRST_ANSWER_DISPATCHED,
    WEBVIEW_TERMINAL_DISPATCHED,
    TURN_CLEARED,
}

/**
 * 进程内 turn 阶段观测：LRU 有界、只写 logcat、不落库。
 * send_requested 的 origin 在发送入口捕获、cmid 生成时绑定；
 * 缺 entry 的路径记录 origin=missing，不伪造 0ms，也不新建 entry 污染旧 turn。
 */
class TurnTraceTracker(
    private val clock: TurnTraceClock = SystemTurnTraceClock,
    maxEntries: Int = MAX_TRACE_ENTRIES,
    private val logSink: (String) -> Unit = { Log.i(TAG, it) },
) {
    private data class TraceEntry(
        val sessionId: String,
        var turnId: String?,
        var clientMessageId: String?,
        var sendElapsed: Long? = null,
        val seen: MutableSet<TurnTraceStage> = mutableSetOf(),
    )
    // 1. 访问序 LRU；淘汰时必须同步清掉 cmid->turn 反向映射，不留陈旧映射
    private val entries = object : LinkedHashMap<String, TraceEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TraceEntry>): Boolean {
            if (size <= maxEntries) return false
            eldest.value.clientMessageId?.let(clientMessageIdToTurnKey::remove)
            return true
        }
    }
    private val clientMessageIdToTurnKey = mutableMapOf<String, String>()

    // 2. 缺失来源日志去重，避免淘汰后每个 delta 都刷一条
    private val missingOrigins = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean =
            size > MAX_MISSING_ORIGINS
    }

    /** 捕获发送入口时刻的 origin；调用方在生成 cmid 后用同一 origin 绑定 send_requested。 */
    fun captureSendOrigin(): SendOrigin = SendOrigin(clock.elapsedMillis(), clock.wallMillis())

    @Synchronized
    fun recordSend(sessionId: String, clientMessageId: String, origin: SendOrigin) {
        // 1. send_requested 只记录第一次，且时间戳绑定入口 origin，不后移到 cmid 生成时刻
        val key = sendKey(clientMessageId)
        val entry = entries[key] ?: TraceEntry(sessionId, null, clientMessageId)
        if (!entry.seen.add(TurnTraceStage.SEND_REQUESTED)) return
        entry.sendElapsed = origin.elapsedMillis
        entries[key] = entry
        logSink(
            "trace session=${entry.sessionId} turn=missing cmid=$clientMessageId " +
                "stage=send_requested mono=${origin.elapsedMillis}" +
                " wall=${Instant.ofEpochMilli(origin.wallMillis)} since_send=0",
        )
    }

    @Synchronized
    fun onLocalOutboxCommitted(sessionId: String, clientMessageId: String) {
        val entry = entries[sendKey(clientMessageId)]
        if (entry == null) {
            logMissingOrigin(sendKey(clientMessageId), TurnTraceStage.LOCAL_OUTBOX_COMMITTED)
            return
        }
        require(entry.sessionId == sessionId) { "Local outbox commit session mismatch" }
        record(entry, sendKey(clientMessageId), TurnTraceStage.LOCAL_OUTBOX_COMMITTED)
    }

    /** socket 真正写入后的投递阶段；entry 可能已迁移到 turn 键，按 cmid 反查。 */
    @Synchronized
    fun onSocketDispatched(sessionId: String, clientMessageId: String) {
        val entry = entryForClientMessageId(sessionId, clientMessageId)
        if (entry == null) {
            logMissingOrigin(sendKey(clientMessageId), TurnTraceStage.SOCKET_DISPATCHED)
            return
        }
        record(entry, entryKey(entry, clientMessageId), TurnTraceStage.SOCKET_DISPATCHED)
    }

    /** 服务端 message.send.ok 回复到达；entry 可能已在 turn 键下，按 cmid 反查，session 以 entry 为准。 */
    @Synchronized
    fun onServerAckReceived(clientMessageId: String) {
        val entry = entryForClientMessageId(clientMessageId)
        if (entry == null) {
            logMissingOrigin(sendKey(clientMessageId), TurnTraceStage.SERVER_ACK_RECEIVED)
            return
        }
        record(entry, entryKey(entry, clientMessageId), TurnTraceStage.SERVER_ACK_RECEIVED)
    }

    /** 服务端 ACK 在 Room 事务提交完成后记录。 */
    @Synchronized
    fun onServerAckCommitted(clientMessageId: String) {
        val entry = entryForClientMessageId(clientMessageId)
        if (entry == null) {
            logMissingOrigin(sendKey(clientMessageId), TurnTraceStage.SERVER_ACK_COMMITTED)
            return
        }
        record(entry, entryKey(entry, clientMessageId), TurnTraceStage.SERVER_ACK_COMMITTED)
    }

    /**
     * 服务端 message.send.error：总是记录明确 outcome。
     * command_outcome_unknown / command_in_progress 都不是确定失败：原命令仍在执行
     * 或仍留在持久队列，后续 turn.started/delta/final 可到达，保留 entry 与反向映射；
     * 其余确定失败不会再有 turn，直接移除 entry，避免残留在 LRU 伪装活动 turn。
     */
    @Synchronized
    fun onSendError(clientMessageId: String, code: String) {
        val entry = entryForClientMessageId(clientMessageId)
        if (entry == null) {
            logMissingOrigin(sendKey(clientMessageId), TurnTraceStage.SEND_ERROR, code)
            return
        }
        val key = entryKey(entry, clientMessageId)
        record(entry, key, TurnTraceStage.SEND_ERROR, code)
        if (code == "command_outcome_unknown" || code == "command_in_progress") return
        entries.remove(key)
        entry.clientMessageId?.let(clientMessageIdToTurnKey::remove)
    }

    /** 服务端 turn.started 到达：迁移 send 记录并绑定三元组，记录 received。 */
    @Synchronized
    fun onTurnStartedReceived(sessionId: String, turnId: String, clientMessageId: String?) {
        val turnKey = turnKey(sessionId, turnId)
        val existing = entries[turnKey]
        if (existing != null) {
            require(existing.sessionId == sessionId) { "Turn trace session mismatch" }
            // 1. 已有 entry 时补写缺失的 cmid，非空则校验一致
            if (clientMessageId != null) {
                if (existing.clientMessageId == null) {
                    existing.clientMessageId = clientMessageId
                    clientMessageIdToTurnKey[clientMessageId] = turnKey
                } else {
                    require(existing.clientMessageId == clientMessageId) { "Turn trace client_message_id mismatch" }
                }
            }
            record(existing, turnKey, TurnTraceStage.TURN_STARTED_RECEIVED)
            return
        }
        // 2. 从 send 记录迁移，保留 send 时延 origin
        val migrated = clientMessageId?.let { cmid ->
            entries.remove(sendKey(cmid))?.also {
                it.turnId = turnId
                it.clientMessageId = cmid
                clientMessageIdToTurnKey[cmid] = turnKey
            }
        }
        val entry = migrated ?: TraceEntry(sessionId, turnId, clientMessageId)
        entries[turnKey] = entry
        record(entry, turnKey, TurnTraceStage.TURN_STARTED_RECEIVED)
    }

    @Synchronized
    fun onThinkingReceived(sessionId: String, turnId: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.FIRST_THINKING_RECEIVED)

    @Synchronized
    fun onAnswerReceived(sessionId: String, turnId: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.FIRST_ANSWER_RECEIVED)

    @Synchronized
    fun onTerminalReceived(sessionId: String, turnId: String, terminalStatus: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.TERMINAL_RECEIVED, detail = terminalStatus)

    @Synchronized
    fun onRoomCommitted(sessionId: String, turnId: String, stage: TurnTraceStage) =
        record(turnKey(sessionId, turnId), stage)

    @Synchronized
    fun onTurnCleared(sessionId: String, turnId: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.TURN_CLEARED)

    @Synchronized
    fun onUiProjected(
        sessionId: String,
        turnId: String,
        clientMessageId: String?,
        hasThinking: Boolean,
        hasAnswer: Boolean,
        streaming: Boolean,
    ) {
        val key = turnKey(sessionId, turnId)
        val entry = entryFor(key, sessionId, turnId, clientMessageId)
        if (entry == null) {
            logMissingOrigin(key, when {
                hasThinking -> TurnTraceStage.UI_FIRST_THINKING
                hasAnswer -> TurnTraceStage.UI_FIRST_ANSWER
                else -> TurnTraceStage.UI_TERMINAL
            })
            return
        }
        if (hasThinking) record(entry, key, TurnTraceStage.UI_FIRST_THINKING)
        if (hasAnswer) record(entry, key, TurnTraceStage.UI_FIRST_ANSWER)
        if (!streaming) record(entry, key, TurnTraceStage.UI_TERMINAL)
    }

    @Synchronized
    fun onCanStopFalse(sessionId: String, turnId: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.CAN_STOP_FALSE)

    @Synchronized
    fun onComposerReady(sessionId: String, turnId: String) =
        record(turnKey(sessionId, turnId), TurnTraceStage.COMPOSER_READY)

    @Synchronized
    fun onWebViewPatch(
        sessionId: String,
        turnId: String,
        clientMessageId: String?,
        thinkingDelta: Boolean,
        answerDelta: Boolean,
        terminal: Boolean,
    ) {
        val key = turnKey(sessionId, turnId)
        val entry = entryFor(key, sessionId, turnId, clientMessageId)
        if (entry == null) {
            logMissingOrigin(key, when {
                thinkingDelta -> TurnTraceStage.WEBVIEW_FIRST_THINKING_DISPATCHED
                answerDelta -> TurnTraceStage.WEBVIEW_FIRST_ANSWER_DISPATCHED
                else -> TurnTraceStage.WEBVIEW_TERMINAL_DISPATCHED
            })
            return
        }
        if (thinkingDelta) record(entry, key, TurnTraceStage.WEBVIEW_FIRST_THINKING_DISPATCHED)
        if (answerDelta) record(entry, key, TurnTraceStage.WEBVIEW_FIRST_ANSWER_DISPATCHED)
        if (terminal) record(entry, key, TurnTraceStage.WEBVIEW_TERMINAL_DISPATCHED)
    }

    /** 新配对或服务端身份重建时清空全部观测；断线重连不清，保留正在追踪的 turn。 */
    @Synchronized
    fun reset() {
        entries.clear()
        clientMessageIdToTurnKey.clear()
        missingOrigins.clear()
    }

    /** 供 JVM 测试观察有界容量，生产路径不调用。 */
    @Synchronized
    internal fun entryCount(): Int = entries.size

    /** 供 JVM 测试观察指定 turn 条目是否仍在，生产路径不调用。 */
    @Synchronized
    internal fun hasEntryForTest(sessionId: String, turnId: String): Boolean =
        entries.containsKey(turnKey(sessionId, turnId))

    private fun record(key: String, stage: TurnTraceStage, detail: String? = null) {
        val entry = entries[key]
        if (entry == null) {
            logMissingOrigin(key, stage, detail)
            return
        }
        record(entry, key, stage, detail)
    }

    private fun record(entry: TraceEntry, key: String, stage: TurnTraceStage, detail: String? = null) {
        // 1. 同一阶段只记录第一次，物理重放不重复
        if (!entry.seen.add(stage)) return
        val nowElapsed = clock.elapsedMillis()
        val nowWall = clock.wallMillis()
        val sendElapsed = entry.sendElapsed
        val sinceSend = if (sendElapsed != null) nowElapsed - sendElapsed else null
        logSink(
            "trace session=${entry.sessionId} turn=${entry.turnId ?: "missing"} " +
                "cmid=${entry.clientMessageId ?: "missing"} stage=${stage.name.lowercase()}" +
                (detail?.let { " status=$it" } ?: "") +
                " mono=$nowElapsed wall=${Instant.ofEpochMilli(nowWall)}" +
                " since_send=${sinceSend ?: "missing_origin"}",
        )
        // 2. 四条 post-terminal 里程碑齐了才移除，中间态继续可观测
        val closed = TurnTraceStage.TURN_CLEARED in entry.seen &&
            TurnTraceStage.CAN_STOP_FALSE in entry.seen &&
            TurnTraceStage.COMPOSER_READY in entry.seen &&
            TurnTraceStage.WEBVIEW_TERMINAL_DISPATCHED in entry.seen
        if (closed) {
            entries.remove(key)
            entry.clientMessageId?.let(clientMessageIdToTurnKey::remove)
        }
    }

    private fun logMissingOrigin(key: String, stage: TurnTraceStage, detail: String? = null) {
        if (missingOrigins.put("$key|${stage.name}", true) != null) return
        val nowElapsed = clock.elapsedMillis()
        val nowWall = clock.wallMillis()
        logSink(
            "trace origin=missing key=$key stage=${stage.name.lowercase()}" +
                (detail?.let { " status=$it" } ?: "") +
                " mono=$nowElapsed wall=${Instant.ofEpochMilli(nowWall)}",
        )
    }

    private fun entryFor(key: String, sessionId: String, turnId: String, clientMessageId: String?): TraceEntry? {
        val entry = entries[key]
        if (entry != null) return entry
        if (clientMessageId == null) return null
        // 反查 cmid 命中的 entry 必须与当前 session/turn 一致，跨会话或跨 turn 复用即 fail-loud
        val resolved = entries[clientMessageIdToTurnKey[clientMessageId]] ?: return null
        require(resolved.sessionId == sessionId) { "Turn trace session mismatch" }
        require(resolved.turnId == turnId) { "Turn trace turn mismatch" }
        return resolved
    }

    /** 按 cmid 同时反查 send 键与 turn 键，覆盖 turn.started 先于 ACK 到达的顺序。 */
    private fun entryForClientMessageId(sessionId: String, clientMessageId: String): TraceEntry? {
        val entry = entries[sendKey(clientMessageId)]
            ?: entries[clientMessageIdToTurnKey[clientMessageId]]
            ?: return null
        require(entry.sessionId == sessionId) { "Turn trace session mismatch" }
        return entry
    }

    /** ACK/error 只有 cmid：entry 的 sessionId 即观测身份，无其他权威来源可核对。 */
    private fun entryForClientMessageId(clientMessageId: String): TraceEntry? =
        entries[sendKey(clientMessageId)] ?: entries[clientMessageIdToTurnKey[clientMessageId]]

    /** 返回 entry 当前所在键：send 键未迁移时用于写入，turn 键迁移后用于写入。 */
    private fun entryKey(entry: TraceEntry, clientMessageId: String): String {
        if (entries[sendKey(clientMessageId)] === entry) return sendKey(clientMessageId)
        val turnKey = clientMessageIdToTurnKey[clientMessageId]
        require(turnKey != null && entries[turnKey] === entry) { "Turn trace entry key mismatch" }
        return turnKey
    }

    private fun sendKey(clientMessageId: String): String = "send:$clientMessageId"

    private fun turnKey(sessionId: String, turnId: String): String = "turn:$sessionId:$turnId"

    private companion object {
        const val TAG = "TurnTrace"
        const val MAX_TRACE_ENTRIES = 32
        const val MAX_MISSING_ORIGINS = 64
    }
}
