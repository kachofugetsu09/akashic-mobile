package com.akashic.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerDraftDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ComposerDraftDao

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.composerDrafts()
        database.serverProfiles().upsert(profile("server-a"))
        database.serverProfiles().upsert(profile("server-b"))
        database.conversations().upsert(
            ConversationEntity("akashic:a", "server-a", "会话 A", 1),
        )
        database.conversations().upsert(
            ConversationEntity("akashic:b", "server-b", "会话 B", 1),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun observeGetUpsertAndDeleteKeepSessionIsolation() = runBlocking {
        assertNull(dao.observe("server-a", "akashic:a").first())
        val original = ComposerDraftEntity(
            sessionId = "akashic:a",
            serverId = "server-a",
            text = "第一版",
            replyToMessageId = "missing-message",
            updatedAt = 2,
        )

        dao.upsert(original)

        assertEquals(original, dao.observe("server-a", "akashic:a").first())
        assertNull(dao.get("server-b", "akashic:a"))
        assertEquals(0, dao.delete("server-b", "akashic:a"))
        assertEquals(original, dao.get("server-a", "akashic:a"))

        val updated = original.copy(text = "第二版", replyToMessageId = null, updatedAt = 3)
        dao.upsert(updated)

        assertEquals(updated, dao.get("server-a", "akashic:a"))
        assertEquals(1, dao.delete("server-a", "akashic:a"))
        assertNull(dao.get("server-a", "akashic:a"))
    }

    @Test
    fun conversationDeletionCascadesOnlyToTargetDraft() = runBlocking {
        dao.upsert(ComposerDraftEntity("akashic:a", "server-a", "A", null, 2))
        dao.upsert(ComposerDraftEntity("akashic:b", "server-b", "B", null, 2))

        assertEquals(1, database.conversations().delete("server-a", "akashic:a"))
        assertNull(dao.get("server-a", "akashic:a"))
        assertEquals("B", dao.get("server-b", "akashic:b")?.text)
    }

    @Test
    fun canonicalMergeMovesOnlyMatchingReplyTarget() = runBlocking {
        dao.upsert(
            ComposerDraftEntity("akashic:a", "server-a", "继续回复", "optimistic", 2),
        )

        assertEquals(0, dao.moveReplyTarget("akashic:a", "other", "canonical"))
        assertEquals("optimistic", dao.get("server-a", "akashic:a")?.replyToMessageId)

        assertEquals(1, dao.moveReplyTarget("akashic:a", "optimistic", "canonical"))
        val migrated = requireNotNull(dao.get("server-a", "akashic:a"))
        assertEquals("canonical", migrated.replyToMessageId)
        assertEquals(2L, migrated.updatedAt)
    }

    @Test
    fun composerDraftCountsAsLocalWorkAndProtectsEmptyProjection() = runBlocking {
        dao.upsert(ComposerDraftEntity("akashic:a", "server-a", "未发送", null, 2))

        val summary = database.conversations().observeSummaries("server-a").first().single()
        assertEquals(true, summary.hasLocalWork)
        assertEquals(0, database.conversations().deleteEmptyProjection("server-a", null))
        assertEquals("会话 A", database.conversations().get("akashic:a")?.title)

        assertEquals(1, dao.delete("server-a", "akashic:a"))
        assertEquals(1, database.conversations().deleteEmptyProjection("server-a", null))
        assertNull(database.conversations().get("akashic:a"))
    }

    private fun profile(serverId: String) = ServerProfileEntity(
        serverId,
        serverId,
        "device-$serverId",
        "alias-$serverId",
        "pin-$serverId",
        "[]",
        "[]",
        "[]",
        1,
    )
}
