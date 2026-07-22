package com.akashic.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportFormatterTest {
    @Test
    fun includesBuildThreadAndCauseChain() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))

        val report = formatCrashReport(
            timestampMillis = 1234,
            threadName = "main",
            identity = CrashReportIdentity("0.8.5-debug", 26, "test device", "16", 36),
            error = error,
        )

        assertTrue(report.contains("timestamp_ms=1234"))
        assertTrue(report.contains("thread=main"))
        assertTrue(report.contains("app_version=0.8.5-debug"))
        assertTrue(report.contains("exception=java.lang.IllegalStateException: outer"))
        assertTrue(report.contains("caused_by=java.lang.IllegalArgumentException: inner"))
    }

    @Test
    fun truncatesDeepCauseChains() {
        var error: Throwable = IllegalStateException("root")
        repeat(12) { index -> error = IllegalStateException("cause-$index", error) }

        val report = formatCrashReport(
            timestampMillis = 1234,
            threadName = "worker",
            identity = CrashReportIdentity("debug", 1, "test", "16", 36),
            error = error,
        )

        assertTrue(report.contains("caused_by=<truncated>"))
        assertFalse(report.contains("cause-0"))
    }

    @Test
    fun runtimeErrorIncludesSafeBoundaryContextAndStack() {
        val report = formatRuntimeErrorReport(
            timestampMillis = 1234,
            threadName = "realtime",
            identity = CrashReportIdentity("0.8.7", 28, "test device", "16", 36),
            source = "RealtimeSession",
            context = "operation=envelope\ntype=turn.started event_seq=42",
            error = IllegalArgumentException("同一会话出现重叠 turn"),
        )

        assertTrue(report.contains("source=RealtimeSession"))
        assertTrue(report.contains("context=operation=envelope type=turn.started event_seq=42"))
        assertTrue(report.contains("exception=java.lang.IllegalArgumentException: 同一会话出现重叠 turn"))
        assertFalse(report.contains("\ncontext=operation=envelope\ntype="))
    }
}
