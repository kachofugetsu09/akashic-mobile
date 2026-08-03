package com.akashic.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNotificationNoticeTest {
    @Test
    fun noticeRequiresDeniedPermissionAndACompletedRequest() {
        assertFalse(
            notificationPermissionNoticeVisible(
                notificationsEnabled = true,
                permissionRequested = true,
                dismissed = false,
            ),
        )
        assertFalse(
            notificationPermissionNoticeVisible(
                notificationsEnabled = false,
                permissionRequested = false,
                dismissed = false,
            ),
        )
        assertTrue(
            notificationPermissionNoticeVisible(
                notificationsEnabled = false,
                permissionRequested = true,
                dismissed = false,
            ),
        )
    }

    @Test
    fun dismissalKeepsNoticeHiddenForTheCurrentSession() {
        assertFalse(
            notificationPermissionNoticeVisible(
                notificationsEnabled = false,
                permissionRequested = true,
                dismissed = true,
            ),
        )
    }

    @Test
    fun pairingRecoveryNoticeHasPriorityAndCanBeDismissedForOneState() {
        assertTrue(pairingRecoveryNoticeVisible(required = true, dismissed = false))
        assertFalse(pairingRecoveryNoticeVisible(required = true, dismissed = true))
        assertFalse(pairingRecoveryNoticeVisible(required = false, dismissed = false))
    }
}
