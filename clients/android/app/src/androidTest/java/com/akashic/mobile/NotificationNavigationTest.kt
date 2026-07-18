package com.akashic.mobile

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.ConversationEntity
import com.akashic.mobile.data.local.LocalDeliveryStore
import com.akashic.mobile.data.local.MediaCacheStore
import com.akashic.mobile.data.local.MessageEntity
import com.akashic.mobile.data.local.NotificationTargetProjection
import com.akashic.mobile.data.local.ServerProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationNavigationTest {
    private lateinit var database: AppDatabase
    private lateinit var store: LocalDeliveryStore

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        store = LocalDeliveryStore(
            database,
            MediaCacheStore(
                context.cacheDir.resolve("notification-navigation-${System.nanoTime()}"),
                database.mediaAttachments(),
            ),
        )
        database.serverProfiles().upsert(profile("server-a"))
        database.serverProfiles().upsert(profile("server-b"))
        database.conversations().upsert(
            ConversationEntity(SESSION_A, "server-a", "A", 1),
        )
        database.conversations().upsert(
            ConversationEntity(SESSION_B, "server-a", "B", 1),
        )
        database.conversations().upsert(
            ConversationEntity(SESSION_OTHER_SERVER, "server-b", "Other", 1),
        )
        database.messages().upsert(message(MESSAGE_A, SESSION_A))
        database.messages().upsert(message(MESSAGE_B, SESSION_B))
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun intentTargetIsHandedOffOnlyOnce() {
        val intent = Intent().apply {
            putExtra(MobileConnectionService.EXTRA_SESSION_ID, SESSION_A)
            putExtra(MobileConnectionService.EXTRA_MESSAGE_ID, MESSAGE_A)
        }

        assertEquals(
            NotificationTargetRequest(SESSION_A, MESSAGE_A),
            takeNotificationTarget(intent),
        )
        assertFalse(intent.hasExtra(MobileConnectionService.EXTRA_SESSION_ID))
        assertFalse(intent.hasExtra(MobileConnectionService.EXTRA_MESSAGE_ID))
        assertNull(takeNotificationTarget(intent))
    }

    @Test
    fun targetRequiresCurrentServerAndExactMessageSession() = runBlocking {
        assertEquals(
            NotificationTargetProjection.AVAILABLE,
            store.notificationTargetProjection("server-a", SESSION_A, MESSAGE_A),
        )
        assertEquals(
            NotificationTargetProjection.AVAILABLE,
            store.notificationTargetProjection("server-a", SESSION_A, null),
        )
        assertEquals(
            NotificationTargetProjection.MISSING,
            store.notificationTargetProjection("server-a", SESSION_A, "message-missing"),
        )
        assertEquals(
            NotificationTargetProjection.WRONG_SESSION,
            store.notificationTargetProjection("server-a", SESSION_A, MESSAGE_B),
        )
        assertEquals(
            NotificationTargetProjection.WRONG_SERVER,
            store.notificationTargetProjection("server-a", SESSION_OTHER_SERVER, null),
        )
    }

    private fun profile(serverId: String) = ServerProfileEntity(
        serverId,
        serverId,
        "device-$serverId",
        "key-$serverId",
        "fingerprint-$serverId",
        "[]",
        "[]",
        "[]",
        1,
    )

    private fun message(messageId: String, sessionId: String) = MessageEntity(
        messageId,
        null,
        sessionId,
        "assistant",
        "消息",
        "complete",
        1,
        1,
    )

    private companion object {
        const val SESSION_A = "mobile:00000000-0000-0000-0000-000000000001"
        const val SESSION_B = "mobile:00000000-0000-0000-0000-000000000002"
        const val SESSION_OTHER_SERVER = "mobile:00000000-0000-0000-0000-000000000003"
        const val MESSAGE_A = "message-a"
        const val MESSAGE_B = "message-b"
    }
}
