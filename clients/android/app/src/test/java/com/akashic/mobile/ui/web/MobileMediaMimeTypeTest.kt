package com.akashic.mobile.ui.web

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun resolvesExtensionBearingImageResourceToExistingCacheFile() {
        val file = Files.createTempFile("akashic-mobile-image", ".bin").toFile()
        try {
            file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            val key = mobileMediaResourceKey("01KXV4PAJ572EP2JZR285AJZ3H", "002.png")
            val index = MobileMediaResourceIndex()
            index.replace(mapOf(key to MobileMediaResource(file.absolutePath, "image/png")))

            assertEquals("01KXV4PAJ572EP2JZR285AJZ3H.png", key)
            assertEquals(
                "https://appassets.androidplatform.net/media/$key",
                mobileMediaResourceUrl("01KXV4PAJ572EP2JZR285AJZ3H", "002.png"),
            )
            assertEquals(file.absolutePath, index.resolve(key)?.path)
            assertNull(index.resolve("01KXV4PAJ572EP2JZR285AJZ3H"))
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }
}
