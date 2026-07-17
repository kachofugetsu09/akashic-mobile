package com.akashic.mobile.data.realtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentOperationOwnerTest {
    @Test
    fun `send and removal execute under one owner`() = runBlocking {
        val owner = AttachmentOperationOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val send = async {
            owner.perform {
                order += "send-start"
                entered.complete(Unit)
                release.await()
                order += "send-end"
            }
        }
        entered.await()
        val remove = async { owner.perform { order += "remove" } }

        release.complete(Unit)
        send.await()
        remove.await()

        assertEquals(listOf("send-start", "send-end", "remove"), order)
    }

    @Test
    fun `new attachment after click rejects stale visible set`() {
        assertTrue(attachmentDraftMatchesExpected(listOf("a", "b"), listOf("b", "a")))
        assertFalse(attachmentDraftMatchesExpected(listOf("a", "b"), listOf("a")))
        assertFalse(attachmentDraftMatchesExpected(listOf("a"), listOf("a", "a")))
    }

    @Test
    fun `send result reports failure before propagating internal error`() = runBlocking {
        val results = mutableListOf<Boolean>()
        val failure = runCatching {
            withSendResult(results::add) { error("persistence failed") }
        }.exceptionOrNull()

        assertEquals(listOf(false), results)
        assertEquals("persistence failed", failure?.message)
    }
}
