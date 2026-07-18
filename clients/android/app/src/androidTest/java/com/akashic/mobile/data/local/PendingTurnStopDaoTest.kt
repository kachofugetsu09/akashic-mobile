package com.akashic.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingTurnStopDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: PendingTurnStopDao

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.pendingTurnStops()
        database.serverProfiles().upsert(
            ServerProfileEntity("server", "电脑", "device", "alias", "pin", "[]", "[]", "[]", 1),
        )
        database.conversations().upsert(
            ConversationEntity("mobile:test", "server", "会话", 2, remoteKnown = true),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stopIntentSurvivesConversationProjectionCleanupUntilExplicitlyConsumed() = runBlocking {
        val stop = PendingTurnStopEntity("stop-1", "server", "mobile:test", "turn-1", 3)
        database.messages().upsert(
            MessageEntity(
                "assistant:turn-1",
                null,
                "mobile:test",
                "assistant",
                "生成中",
                "streaming",
                3,
                3,
            ),
        )
        dao.insert(stop)

        assertEquals(listOf(stop), dao.listForServer("server"))
        assertEquals(
            listOf("assistant:turn-1"),
            database.messages().activeAssistantTurns("server").map(MessageEntity::messageId),
        )
        assertEquals(1, database.conversations().delete("server", "mobile:test"))
        assertEquals(listOf(stop), dao.listForServer("server"))

        assertEquals(1, dao.delete("stop-1"))
        assertTrue(dao.listForServer("server").isEmpty())
    }
}
