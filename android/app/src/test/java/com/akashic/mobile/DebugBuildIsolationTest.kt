package com.akashic.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuildIsolationTest {
    @Test
    fun debugBuildCannotReplaceTheReleaseApplication() {
        assertEquals("com.akashic.mobile.debug", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-debug"))
    }
}
