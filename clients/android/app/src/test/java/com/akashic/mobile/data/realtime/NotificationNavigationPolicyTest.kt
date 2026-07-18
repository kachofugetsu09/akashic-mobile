package com.akashic.mobile.data.realtime

import com.akashic.mobile.data.local.NotificationTargetProjection
import com.akashic.mobile.domain.model.ConnectionPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationNavigationPolicyTest {
    @Test
    fun availableLocalTargetOpensWithoutWaitingForReady() {
        assertEquals(
            NotificationTargetOpenResult.OPENED,
            notificationTargetOpenResult(
                NotificationTargetProjection.AVAILABLE,
                ConnectionPhase.CONNECTING,
            ),
        )
    }

    @Test
    fun missingProjectionWaitsForSyncBeforeBecomingStale() {
        assertEquals(
            NotificationTargetOpenResult.WAITING_FOR_SYNC,
            notificationTargetOpenResult(
                NotificationTargetProjection.MISSING,
                ConnectionPhase.SYNCING,
            ),
        )
        assertEquals(
            NotificationTargetOpenResult.STALE,
            notificationTargetOpenResult(
                NotificationTargetProjection.MISSING,
                ConnectionPhase.READY,
            ),
        )
    }

    @Test
    fun explicitOwnerOrSessionMismatchIsImmediatelyStale() {
        assertEquals(
            NotificationTargetOpenResult.STALE,
            notificationTargetOpenResult(
                NotificationTargetProjection.WRONG_SERVER,
                ConnectionPhase.CONNECTING,
            ),
        )
        assertEquals(
            NotificationTargetOpenResult.STALE,
            notificationTargetOpenResult(
                NotificationTargetProjection.WRONG_SESSION,
                ConnectionPhase.SYNCING,
            ),
        )
    }
}
