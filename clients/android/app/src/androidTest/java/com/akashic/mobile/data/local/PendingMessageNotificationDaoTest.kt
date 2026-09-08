package com.akashic.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingMessageNotificationDaoTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var databaseName: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "pending-notification-${UUID.randomUUID()}"
        database = openDatabase()
        database.serverProfiles().upsert(
            ServerProfileEntity("server", "电脑", "device", "alias", "pin", "[]", "[]", "[]", 1),
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun hintSurvivesRestartAndBecomesObservableOnlyAfterMessageIsReady() = runBlocking {
        val hint = PendingMessageNotificationEntity(
            messageId = "message-42",
            serverId = "server",
            sessionId = "akashic:test",
            content = "",
            hasAttachments = false,
            attention = "complete",
            ready = false,
            headSeq = 42,
            createdAt = 1,
        )
        assertTrue(database.pendingMessageNotifications().insertHint(hint) > 0)
        assertTrue(database.pendingMessageNotifications().observeForServer("server").first().isEmpty())

        database.close()
        database = openDatabase()
        assertEquals(
            listOf(hint),
            database.pendingMessageNotifications().pendingHints("server"),
        )

        assertEquals(
            1,
            database.pendingMessageNotifications().markReady(
                messageId = hint.messageId,
                content = "权威回答",
                hasAttachments = true,
                attention = "confirmation",
                createdAt = 2,
            ),
        )
        val ready = database.pendingMessageNotifications().observeForServer("server").first().single()
        assertEquals("权威回答", ready.content)
        assertEquals(true, ready.hasAttachments)
        assertEquals("confirmation", ready.attention)
        assertEquals(true, ready.ready)
        assertEquals(42L, ready.headSeq)
    }

    private fun openDatabase(): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        databaseName,
    ).addMigrations(
        AppDatabase.MIGRATION_16_17,
    ).build()
}
