package com.akashic.mobile.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.akashic.mobile.data.realtime.MobileWebUiResetEvent
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive

class MobileNavigationPolicyTest {
    @Test
    fun androidBackOnlyStaysInActivityWhenWebSurfaceConsumedIt() {
        assertEquals(true, mobileWebBackHandled("true"))
        assertEquals(false, mobileWebBackHandled("false"))
        assertEquals(false, mobileWebBackHandled(null))
    }

    @Test
    fun onlyAllowsBundledEntryPageInsideWebView() {
        assertEquals(
            MobileNavigationAction.ALLOW_INTERNAL,
            mobileNavigationAction(
                mobileWebUrl(20),
                isMainFrame = true,
            ),
        )
        assertEquals(
            "https://appassets.androidplatform.net/assets/mobile.html?appVersion=20&generation_id=embedded&nonce=baseline",
            mobileWebUrl(20),
        )
        assertEquals(
            MobileNavigationAction.BLOCK,
            mobileNavigationAction(
                "https://appassets.androidplatform.net/media/untrusted.html",
                isMainFrame = true,
            ),
        )
        assertEquals(
            MobileNavigationAction.BLOCK,
            mobileNavigationAction("https://example.com/frame", isMainFrame = false),
        )
    }

    @Test
    fun onlyHandsHttpLinksToExternalApps() {
        assertEquals(
            MobileNavigationAction.OPEN_EXTERNAL,
            mobileNavigationAction("https://example.com/page", isMainFrame = true),
        )
        assertEquals(
            MobileNavigationAction.BLOCK,
            mobileNavigationAction("intent://unsafe", isMainFrame = true),
        )
        assertEquals(
            MobileNavigationAction.BLOCK,
            mobileNavigationAction("file:///tmp/unsafe", isMainFrame = true),
        )
    }

    @Test
    fun candidateWebUiCannotLaunchExternalActivityBeforeCommit() {
        assertFalse(mobileExternalNavigationAllowed(candidate = true))
        assertTrue(mobileExternalNavigationAllowed(candidate = false))
    }

    @Test
    fun remoteLeaseUrlAndTransportAdmissionAreGenerationBound() {
        val serverId = "server-a"
        val generation = "a".repeat(64)
        val nonce = "candidate-1"
        val url = mobileWebUrl(20, generation, serverId, nonce)
        assertTrue(url.startsWith("https://appassets.androidplatform.net/mobile-webui/"))
        assertTrue(url.contains("/$generation/mobile.html?"))
        assertTrue(url.contains("generation_id=$generation"))
        assertTrue(url.contains("nonce=$nonce"))
        assertTrue(mobileWebUiTransportMethodAllowed("reportHealthy", candidate = true))
        assertTrue(mobileWebUiTransportMethodAllowed("requestSnapshot", candidate = true))
        assertFalse(mobileWebUiTransportMethodAllowed("reportReady", candidate = true))
        assertFalse(mobileWebUiTransportMethodAllowed("sendMessage", candidate = true))
        assertTrue(mobileWebUiTransportMethodAllowed("sendMessage", candidate = false))
        assertTrue(validateMobileWebTransportArgs("reportHealthy", buildJsonArray {}))
        assertFalse(
            validateMobileWebTransportArgs(
                "setWebHistoryActive",
                buildJsonArray { add(JsonPrimitive("true")) },
            ),
        )
    }

    @Test
    fun oversizedTransportEnvelopeIsDiscardedBeforeJsonDecode() {
        assertFalse(mobileWebUiTransportEnvelopeWithinLimit("x".repeat(256 * 1024 + 1)))
        assertTrue(mobileWebUiTransportEnvelopeWithinLimit("{}"))
    }

    @Test
    fun embeddedAssetsAreAvailableOnlyToBaselineWebView() {
        assertTrue(mobileWebUiEmbeddedAssetsEnabled(null))
        assertFalse(mobileWebUiEmbeddedAssetsEnabled("a".repeat(64)))
    }

    @Test
    fun queuedCallbacksFromReplacedWebViewCannotMutateNativeState() {
        val oldView = Any()
        val replacementView = Any()
        var actionCount = 0
        var healthyCount = 0
        fun dispatch(callbackView: Any, action: () -> Unit) {
            if (mobileWebUiLeaseCallbackAllowed(callbackView, replacementView, callbackView === replacementView)) {
                action()
            }
        }

        dispatch(oldView) { actionCount += 1 }
        dispatch(oldView) { healthyCount += 1 }
        dispatch(replacementView) { actionCount += 1 }
        dispatch(replacementView) { healthyCount += 1 }

        assertEquals(1, actionCount)
        assertEquals(1, healthyCount)
    }

    @Test
    fun resetEventIsConsumedOnceAndDoesNotClearLaterHealthyRetry() {
        val resetA = MobileWebUiResetEvent("server-a", 1L)
        assertTrue(
            shouldConsumeMobileWebUiResetEvent(
                lastHandledEvent = null,
                event = resetA,
                currentServerId = "server-a",
            ),
        )
        assertFalse(
            shouldConsumeMobileWebUiResetEvent(
                lastHandledEvent = resetA,
                event = resetA,
                currentServerId = "server-a",
            ),
        )
        assertTrue(
            shouldConsumeMobileWebUiResetEvent(
                lastHandledEvent = resetA,
                event = MobileWebUiResetEvent("server-a", 2L),
                currentServerId = "server-a",
            ),
        )
        assertFalse(
            shouldConsumeMobileWebUiResetEvent(
                lastHandledEvent = resetA,
                event = resetA,
                currentServerId = "server-b",
            ),
        )
    }

    @Test
    fun emptyRootCannotCommitHealthyCandidate() {
        assertFalse(mobileWebUiHealthReady(true, true, false, true))
        assertTrue(mobileWebUiHealthReady(true, true, true, true))
        assertFalse(mobileWebUiHealthReady(true, false, true, true))
    }
}
