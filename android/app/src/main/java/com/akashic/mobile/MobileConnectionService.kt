package com.akashic.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.akashic.mobile.data.local.PendingMessageNotificationEntity
import com.akashic.mobile.data.realtime.FinalMessageEvent
import com.akashic.mobile.domain.model.ConnectionPhase
import com.akashic.mobile.domain.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MobileConnectionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: App
    private lateinit var notificationManager: NotificationManagerCompat
    private var stateJob: Job? = null
    private var messageJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        notificationManager = NotificationManagerCompat.from(this)
        createNotificationChannels()
        startForeground(CONNECTION_NOTIFICATION_ID, connectionNotification(app.container.realtimeSession.state.value.connection))
        observeConnection()
        observeFinalMessages()
        app.container.realtimeSession.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            app.container.realtimeSession.restartPairing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        messageJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeConnection() {
        stateJob = serviceScope.launch {
            app.container.realtimeSession.state.collectLatest { state ->
                if (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this@MobileConnectionService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(
                        CONNECTION_NOTIFICATION_ID,
                        connectionNotification(state.connection),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFinalMessages() {
        messageJob = serviceScope.launch {
            app.container.realtimeSession.state
                .map { it.serverId }
                .distinctUntilChanged()
                .flatMapLatest { serverId ->
                    serverId?.let {
                        app.container.database.pendingMessageNotifications().observeForServer(it)
                    } ?: flowOf(emptyList())
                }
                .collect { pending ->
                    pending.forEach { notification ->
                        consumePendingNotification(notification)
                    }
                }
        }
    }

    /** 发布或明确抑制一条持久通知，完成后消费待办。 */
    private suspend fun consumePendingNotification(notification: PendingMessageNotificationEntity) {
        // 1. 从 Room 边界恢复已校验的通知语义
        val event = FinalMessageEvent(
            sessionId = notification.sessionId,
            messageId = notification.messageId,
            content = notification.content,
            hasAttachments = notification.hasAttachments,
        )

        // 2. 区分产品抑制与暂时不可投递
        val shouldNotify = MessageNotificationPolicy.shouldNotify(
            appVisible = app.visibility.isVisible,
            currentSessionId = app.container.realtimeSession.state.value.currentSessionId,
            event = event,
        )
        val canPost = (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            ) && notificationManager.areNotificationsEnabled()
        // 3. 只有明确抑制或系统调用成功才消费；异常与权限缺失保留待办
        deliverPendingNotification(
            shouldNotify = shouldNotify,
            canPost = canPost,
            post = {
                notificationManager.notify(
                    event.messageId,
                    MESSAGE_NOTIFICATION_ID,
                    messageNotification(event),
                )
            },
            consume = {
                check(app.container.database.pendingMessageNotifications().delete(event.messageId) == 1) {
                    "Pending notification disappeared before consumption: ${event.messageId}"
                }
            },
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_connection_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.notification_channel_messages_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun connectionNotification(connection: ConnectionState): Notification {
        val status = when (connection.phase) {
            ConnectionPhase.READY -> "连接正常"
            ConnectionPhase.DEGRADED -> "网络不稳，正在重连"
            ConnectionPhase.CLOSED -> "连接已断开"
            ConnectionPhase.FAILED -> "连接启动失败"
            else -> "正在连接"
        }
        return NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_connection_title))
            .setContentText(status)
            .setContentIntent(openAppIntent(sessionId = null, messageId = null))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePublicNotification())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun messageNotification(event: FinalMessageEvent): Notification =
        NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_message_title))
            .setContentText(MessageNotificationPolicy.preview(event))
            .setContentIntent(openAppIntent(event.sessionId, event.messageId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(privatePublicNotification())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun privatePublicNotification(): Notification =
        NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_private_title))
            .setContentText(getString(R.string.notification_private_text))
            .build()

    private fun openAppIntent(sessionId: String?, messageId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse(notificationIntentData(messageId))
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.akashic.mobile.extra.SESSION_ID"
        private const val ACTION_START = "com.akashic.mobile.action.START_CONNECTION"
        private const val ACTION_DISCONNECT = "com.akashic.mobile.action.DISCONNECT"
        private const val CONNECTION_CHANNEL_ID = "mobile_connection"
        private const val MESSAGE_CHANNEL_ID = "mobile_messages"
        private const val CONNECTION_NOTIFICATION_ID = 1_001
        private const val MESSAGE_NOTIFICATION_ID = 0
        private const val NOTIFICATION_PENDING_INTENT_REQUEST_CODE = 1_002

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MobileConnectionService::class.java).setAction(ACTION_START),
            )
        }

        fun disconnect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MobileConnectionService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }
}

internal fun notificationIntentData(messageId: String?): String =
    if (messageId == null) {
        "akashic://notification/connection"
    } else {
        "akashic://notification/message/${
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(messageId.toByteArray(Charsets.UTF_8))
        }"
    }

/** 明确抑制时消费，暂不可投递时保留，系统投递成功后再消费。 */
internal suspend fun deliverPendingNotification(
    shouldNotify: Boolean,
    canPost: Boolean,
    post: () -> Unit,
    consume: suspend () -> Unit,
) {
    if (!shouldNotify) {
        consume()
        return
    }
    if (!canPost) return
    post()
    consume()
}
