package com.akashic.mobile.data.realtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class MobileWebUiCoordinatorTest {
    @Test
    fun httpStatusTransitionsAreBoundedAndExplicit() {
        assertEquals(
            MobileWebUiHttpAction.REFRESH_TICKET,
            classifyMobileWebUiHttp("manifest", 401, "invalid_ticket"),
        )
        assertEquals(
            MobileWebUiHttpAction.RESOLVE_RELEASE,
            classifyMobileWebUiHttp("manifest", 409, "target_changed"),
        )
        assertEquals(
            MobileWebUiHttpAction.RESET_RANGE,
            classifyMobileWebUiHttp("blob", 412),
        )
        assertEquals(
            MobileWebUiHttpAction.RESET_RANGE,
            classifyMobileWebUiHttp("blob", 416, "invalid_range"),
        )
        assertEquals(
            MobileWebUiHttpAction.REJECT_TARGET,
            classifyMobileWebUiHttp("manifest", 404, "resource_not_found"),
        )
        assertEquals(
            MobileWebUiHttpAction.RETRY_AFTER,
            classifyMobileWebUiHttp("manifest", 500, "release_store_corrupt"),
        )
        assertEquals(
            MobileWebUiHttpAction.RETRY_AFTER,
            classifyMobileWebUiHttp("blob", 503),
        )
        assertEquals(
            MobileWebUiHttpAction.ACCEPT,
            classifyMobileWebUiHttp("manifest", 200),
        )
        assertEquals(
            MobileWebUiHttpAction.ACCEPT,
            classifyMobileWebUiHttp("blob", 206),
        )
    }

    @Test
    fun digestHeadersRequireStrongIdentity() {
        val digest = "00".repeat(32)
        val base64 = Base64.getEncoder().encodeToString(ByteArray(32))
        assertEquals("\"$digest\"", mobileWebUiStrongEtag(digest))
        assertEquals(true, mobileWebUiContentDigestMatches("sha-256=:$base64:", digest))
        assertEquals(false, mobileWebUiContentDigestMatches("sha-256=:AQ==:", digest))
        assertEquals(false, mobileWebUiContentDigestMatches("sha-256=$base64", digest))
    }

    @Test
    fun commandRaceErrorsReenterReleaseResolution() {
        assertEquals(
            MobileWebUiCommandAction.RESOLVE_RELEASE,
            classifyMobileWebUiCommandError("target_changed"),
        )
        assertEquals(
            MobileWebUiCommandAction.RESOLVE_RELEASE,
            classifyMobileWebUiCommandError("target_not_found"),
        )
        assertEquals(
            MobileWebUiCommandAction.WAIT_FOR_AUTH,
            classifyMobileWebUiCommandError("capability_required"),
        )
        assertEquals(
            MobileWebUiCommandAction.RETRY_AFTER,
            classifyMobileWebUiCommandError("release_store_corrupt"),
        )
        assertEquals(
            MobileWebUiCommandAction.WAIT_FOR_AUTH,
            classifyMobileWebUiCommandError("capability_required"),
        )
    }

    @Test
    fun pairingRecoveryOnlyStartsForCapabilityErrorsAndOtherErrorsPreserveState() {
        assertEquals(
            true,
            pairingRecoveryRequiredAfterCommandError(false, "capability_required"),
        )
        assertEquals(
            false,
            pairingRecoveryRequiredAfterCommandError(false, "unauthorized"),
        )
        assertEquals(
            true,
            pairingRecoveryRequiredAfterCommandError(true, "unauthorized"),
        )
        assertEquals(
            true,
            pairingRecoveryRequiredAfterCommandError(true, "temporarily_unavailable"),
        )
        assertEquals(
            false,
            pairingRecoveryRequiredAfterCommandError(false, null),
        )
    }
}
