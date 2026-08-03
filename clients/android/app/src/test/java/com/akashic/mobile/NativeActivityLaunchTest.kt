package com.akashic.mobile

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeActivityLaunchTest {
    @Test
    fun fallbackStopsAfterTheFirstSuccessfulLaunch() {
        val launches = mutableListOf<String>()
        var callbackCount = 0

        val launched = nativeActivityLaunchWithFallback(
            launches = listOf(
                {
                    launches += "primary"
                    throw ActivityNotFoundException()
                },
                { launches += "fallback" },
                { launches += "unreachable" },
            ),
            onSuccess = { callbackCount += 1 },
        )

        assertTrue(launched)
        assertEquals(listOf("primary", "fallback"), launches)
        assertEquals(1, callbackCount)
    }

    @Test
    fun fallbackReportsFailureWhenAllLaunchesAreUnavailable() {
        var callbackCount = 0

        val launched = nativeActivityLaunchWithFallback(
            launches = listOf(
                { throw ActivityNotFoundException() },
                { throw SecurityException("blocked") },
            ),
            onSuccess = { callbackCount += 1 },
        )

        assertFalse(launched)
        assertEquals(0, callbackCount)
    }

    @Test
    fun fallbackRequiresAtLeastOneLaunch() {
        var callbackCount = 0

        try {
            nativeActivityLaunchWithFallback(emptyList()) { callbackCount += 1 }
            throw AssertionError("expected empty fallback list to fail")
        } catch (error: IllegalArgumentException) {
            assertEquals("至少需要一个外部 Activity 启动方式", error.message)
        }

        assertEquals(0, callbackCount)
    }
}
