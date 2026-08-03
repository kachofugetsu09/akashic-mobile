package com.akashic.mobile.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileWebChatSendTest {
    @Test
    fun candidatePluginQueryReportsErrorWithoutRunningWork() {
        val errors = mutableListOf<Pair<String, String>>()
        var dispatchCount = 0
        var workCount = 0

        mobileWebUiDispatchPluginQuery<Unit>(
            requestId = "plugin-candidate",
            isLeaseCurrent = { false },
            dispatch = { _, _ -> dispatchCount += 1 },
            work = { workCount += 1 },
            reportError = { requestId, message -> errors += requestId to message },
        )

        assertEquals(0, dispatchCount)
        assertEquals(0, workCount)
        assertEquals(listOf("plugin-candidate" to "插件界面当前不可用"), errors)
    }

    @Test
    fun pluginQueryAdmissionRejectionReportsErrorWithoutRunningWork() {
        val errors = mutableListOf<Pair<String, String>>()
        var workCount = 0

        mobileWebUiDispatchPluginQuery<Unit>(
            requestId = "plugin-admission",
            isLeaseCurrent = { true },
            dispatch = { _, reject -> reject() },
            work = { workCount += 1 },
            reportError = { requestId, message -> errors += requestId to message },
        )

        assertEquals(0, workCount)
        assertEquals(listOf("plugin-admission" to "插件界面当前不可用"), errors)
    }

    @Test
    fun admissionRejectionReportsFalseWithoutRunningSendWork() {
        val results = mutableListOf<Boolean>()
        var workCount = 0

        mobileWebUiDispatchSend<Unit>(
            requestId = "send-admission",
            isLeaseCurrent = { true },
            dispatch = { _, reject -> reject() },
            work = { _, _ -> workCount += 1 },
            report = { _, accepted -> results += accepted },
        )

        assertEquals(0, workCount)
        assertEquals(listOf(false), results)
    }

    @Test
    fun staleLeaseReportsFalseAndCannotAcceptAsyncPersistence() {
        val results = mutableListOf<Boolean>()
        var leaseCurrent = true
        var persisted: ((Boolean) -> Unit)? = null

        mobileWebUiDispatchSend<Unit>(
            requestId = "send-stale",
            isLeaseCurrent = { leaseCurrent },
            dispatch = { work, _ -> work(Unit) },
            work = { _, onPersisted -> persisted = onPersisted },
            report = { _, accepted -> results += accepted },
        )

        leaseCurrent = false
        requireNotNull(persisted).invoke(true)

        assertEquals(listOf(false), results)
        assertFalse(results.single())
    }

    @Test
    fun asyncPersistedSendReportsAcceptedExactlyOnce() {
        val results = mutableListOf<Boolean>()
        var persisted: ((Boolean) -> Unit)? = null

        mobileWebUiDispatchSend<Unit>(
            requestId = "send-persisted",
            isLeaseCurrent = { true },
            dispatch = { work, _ -> work(Unit) },
            work = { _, onPersisted -> persisted = onPersisted },
            report = { _, accepted -> results += accepted },
        )

        assertTrue(results.isEmpty())
        requireNotNull(persisted).invoke(true)
        requireNotNull(persisted).invoke(false)

        assertEquals(listOf(true), results)
    }
}
