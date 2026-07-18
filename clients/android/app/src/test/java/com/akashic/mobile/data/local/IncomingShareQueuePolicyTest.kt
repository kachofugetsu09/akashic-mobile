package com.akashic.mobile.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IncomingShareQueuePolicyTest {
    @Test
    fun acceptsTheLastAvailableRecordAndRejectsTheNextOne() {
        requireIncomingShareCapacity(
            IncomingShareQuotaUsage(MAX_PENDING_INCOMING_SHARES - 1, 0),
            incomingBytes = 0,
            hasIncomingFiles = false,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            requireIncomingShareCapacity(
                IncomingShareQuotaUsage(MAX_PENDING_INCOMING_SHARES, 0),
                incomingBytes = 0,
                hasIncomingFiles = false,
            )
        }

        assertEquals("待处理系统分享不能超过 32 条", error.message)
    }

    @Test
    fun enforcesGlobalBytesWithoutBlockingTextAtTheExactByteLimit() {
        val usage = IncomingShareQuotaUsage(
            pendingCount = 1,
            pendingBytes = MAX_PENDING_INCOMING_SHARE_BYTES,
        )
        requireIncomingShareCapacity(usage, incomingBytes = 0, hasIncomingFiles = false)

        val error = assertThrows(IllegalArgumentException::class.java) {
            requireIncomingShareCapacity(usage, incomingBytes = 0, hasIncomingFiles = true)
        }

        assertEquals("待处理系统分享附件总量不能超过 200 MiB", error.message)
    }

    @Test
    fun rejectsTheChunkThatWouldCrossTheGlobalByteLimit() {
        val usage = IncomingShareQuotaUsage(
            pendingCount = 3,
            pendingBytes = MAX_PENDING_INCOMING_SHARE_BYTES - 1024,
        )
        requireIncomingShareCapacity(usage, incomingBytes = 1024, hasIncomingFiles = true)

        assertThrows(IllegalArgumentException::class.java) {
            requireIncomingShareCapacity(usage, incomingBytes = 1025, hasIncomingFiles = true)
        }
    }
}
