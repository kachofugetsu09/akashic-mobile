package com.akashic.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNotificationDismissalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        manager.createNotificationChannel(
            NotificationChannel(
                MobileConnectionService.CONNECTION_CHANNEL_ID,
                "连接测试",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MobileConnectionService.MESSAGE_CHANNEL_ID,
                "消息测试",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(CONNECTION_ID, notification(MobileConnectionService.CONNECTION_CHANNEL_ID))
        manager.notify(MESSAGE_ID, notification(MobileConnectionService.MESSAGE_CHANNEL_ID))
    }

    @After
    fun tearDown() {
        manager.cancel(CONNECTION_ID)
        manager.cancel(MESSAGE_ID)
    }

    @Test
    fun launcherEntryConsumesMessagesButKeepsConnectionNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val activity = requireNotNull(monitor.waitForActivityWithTimeout(5_000)) {
            "MainActivity 未在 5 秒内启动"
        }
        instrumentation.waitForIdleSync()

        val active = manager.activeNotifications.associateBy { it.id }
        assertTrue(active.containsKey(CONNECTION_ID))
        assertFalse(active.containsKey(MESSAGE_ID))

        activity.finish()
        instrumentation.removeMonitor(monitor)
    }

    private fun notification(channelId: String): Notification = Notification.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("测试通知")
        .build()

    private companion object {
        const val CONNECTION_ID = 61_001
        const val MESSAGE_ID = 61_002
    }
}
