package com.akashic.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {
    @Test
    fun newerStableReleaseReturnsVerifiedApkMetadata() {
        val result = AppUpdateRepository.parseLatestRelease(releaseJson("v0.8.15"), "0.8.14")

        assertTrue(result is UpdateCheckResult.Available)
        val release = (result as UpdateCheckResult.Available).release
        assertEquals("0.8.15", release.versionName)
        assertEquals("a".repeat(64), release.apkSha256)
        assertEquals(12_345L, release.apkSizeBytes)
    }

    @Test
    fun matchingDebugBuildIsAlreadyUpToDate() {
        val result = AppUpdateRepository.parseLatestRelease(releaseJson("v0.8.14"), "0.8.14-debug")

        assertEquals(UpdateCheckResult.UpToDate("0.8.14"), result)
    }

    @Test
    fun releaseWithoutGithubDigestFailsLoud() {
        val body = releaseJson("v0.8.15").replace("\"digest\":\"sha256:${"a".repeat(64)}\",", "")

        assertThrows(ReleaseContractException::class.java) {
            AppUpdateRepository.parseLatestRelease(body, "0.8.14")
        }
    }

    private fun releaseJson(tag: String): String =
        """
        {
          "tag_name":"$tag",
          "name":"Akashic Mobile $tag",
          "body":"更新说明",
          "draft":false,
          "prerelease":false,
          "assets":[{
            "name":"Akashic-Mobile-$tag.apk",
            "size":12345,
            "digest":"sha256:${"a".repeat(64)}",
            "browser_download_url":"https://github.com/kachofugetsu09/akashic-mobile/releases/download/$tag/Akashic-Mobile-$tag.apk"
          }]
        }
        """.trimIndent()
}
