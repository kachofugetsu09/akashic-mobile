package com.akashic.mobile.data.realtime.pluginui

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUiAssetStoreTest {
    @Test
    fun storeCommitsVerifiedContentAndRejectsLaterCorruption() {
        val root = Files.createTempDirectory("plugin-ui-store").toFile()
        try {
            val store = PluginUiAssetStore(root)
            val content = "export const value = 1;"
            val bytes = content.toByteArray(Charsets.UTF_8)
            val sha256 = bytes.sha256()

            store.store(sha256, "module", content, bytes.size)
            assertTrue(store.contains(sha256, "module", bytes.size))

            root.resolve("$sha256/module.js").writeText("corrupt")
            assertFalse(store.contains(sha256, "module", bytes.size))
            assertFalse(root.resolve("$sha256/module.js").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storeRejectsContentWhoseDigestDoesNotMatchCatalog() {
        val root = Files.createTempDirectory("plugin-ui-store").toFile()
        try {
            val content = "export const value = 1;"
            assertThrows(IllegalArgumentException::class.java) {
                PluginUiAssetStore(root).store("0".repeat(64), "module", content, content.length)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheEvictsOldUnownedAssetsButRetainsTheActiveCatalog() {
        val root = Files.createTempDirectory("plugin-ui-bounded-store").toFile()
        try {
            val store = PluginUiAssetStore(root, maxCacheBytes = 20)
            val first = "aaaaaaaaaa"
            val second = "bbbbbbbbbb"
            val third = "cccccccccc"
            val firstSha = first.toByteArray().sha256()
            val secondSha = second.toByteArray().sha256()
            val thirdSha = third.toByteArray().sha256()
            store.store(firstSha, "module", first, first.length)
            store.store(secondSha, "module", second, second.length)
            root.resolve(firstSha).setLastModified(1)
            root.resolve(secondSha).setLastModified(2)

            store.store(
                thirdSha,
                "module",
                third,
                third.length,
                retainedSha256 = setOf(firstSha),
            )

            assertTrue(root.resolve("$firstSha/module.js").isFile)
            assertFalse(root.resolve(secondSha).exists())
            assertTrue(root.resolve("$thirdSha/module.js").isFile)
            assertTrue(root.walkTopDown().filter(File::isFile).sumOf(File::length) <= 20)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cacheAlsoBoundsTinyAssetDirectoryCount() {
        val root = Files.createTempDirectory("plugin-ui-entry-bounded-store").toFile()
        try {
            val store = PluginUiAssetStore(root, maxCacheBytes = 100, maxCacheEntries = 2)
            listOf("a", "b", "c").forEach { content ->
                val sha256 = content.toByteArray().sha256()
                store.store(sha256, "module", content, content.length)
            }

            assertEquals(2, root.listFiles()?.count(File::isDirectory))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }
}
