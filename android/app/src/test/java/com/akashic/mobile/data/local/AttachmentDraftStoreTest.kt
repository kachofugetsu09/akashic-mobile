package com.akashic.mobile.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AttachmentDraftStoreTest {
    @Test
    fun `rejects filenames with surrounding whitespace`() {
        listOf(" report.pdf", "report.pdf ").forEach { filename ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                validateAttachmentFilename(filename)
            }
            assertEquals("文件名不能包含首尾空白", error.message)
        }
    }
}
