package com.akashic.mobile.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MobileMediaMimeTypeTest {
    @Test
    fun separatesMimeTypeFromContentTypeParameters() {
        assertEquals("image/png", mobileMediaMimeType("image/png; charset=binary"))
        assertEquals("image/jpeg", mobileMediaMimeType(" Image/JPEG "))
    }

    @Test
    fun rejectsMalformedMimeType() {
        assertThrows(IllegalArgumentException::class.java) {
            mobileMediaMimeType("not-a-mime")
        }
    }
}
