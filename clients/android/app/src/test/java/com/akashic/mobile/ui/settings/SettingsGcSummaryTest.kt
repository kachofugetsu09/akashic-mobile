package com.akashic.mobile.ui.settings

import com.akashic.mobile.data.realtime.MobileWebUiGcReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGcSummaryTest {
    @Test
    fun degradedCleanupSummaryNamesRetainedOwnersAndCounts() {
        val summary = mobileWebUiGcSummary(
            MobileWebUiGcReport(
                removedGenerations = 1,
                removedBlobs = 2,
                removedBytes = 64,
                blockedGenerations = 1,
                unownedFiles = 2,
            ),
        )

        assertTrue(summary.contains("部分未清理"))
        assertTrue(summary.contains("引用已保留"))
        assertTrue(summary.contains("未假报释放空间"))
        assertTrue(summary.contains("1 个版本"))
        assertTrue(summary.contains("2 个引用"))
    }

    @Test
    fun completeCleanupSummaryDoesNotClaimDegradedState() {
        val summary = mobileWebUiGcSummary(
            MobileWebUiGcReport(
                removedGenerations = 1,
                removedBlobs = 2,
                removedBytes = 64,
            ),
        )

        assertFalse(summary.contains("部分未清理"))
        assertFalse(summary.contains("引用已保留"))
    }
}
