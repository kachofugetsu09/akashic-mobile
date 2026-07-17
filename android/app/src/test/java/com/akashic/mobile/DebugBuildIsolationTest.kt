package com.akashic.mobile

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugBuildIsolationTest {
    @Test
    fun debugBuildCannotReplaceTheReleaseApplication() {
        assertNotEquals("com.akashic.mobile", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("com.akashic.mobile."))
        assertTrue(BuildConfig.VERSION_NAME.endsWith("-debug"))
    }
}
