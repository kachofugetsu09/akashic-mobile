package com.akashic.mobile.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.akashic.mobile.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

internal data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
)

internal sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
}

internal class ReleaseContractException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** 通过公开 GitHub Release 检查、下载并交给系统安装 Akashic Mobile 更新。 */
internal class AppUpdateRepository(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        // 1. 读取公开仓库的最新稳定 Release
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext UpdateCheckResult.NoRelease
            if (!response.isSuccessful) {
                throw IOException("检查更新失败：GitHub 返回 HTTP ${response.code}")
            }
            val body = response.body.string()

            // 2. 在外部 JSON 边界校验发布资产、版本和摘要
            parseLatestRelease(body, BuildConfig.VERSION_NAME)
        }
    }

    /** 下载并验证 APK，成功后保留 24 小时供授权返回继续安装。 */
    suspend fun download(
        context: Context,
        release: AppRelease,
        onProgress: suspend (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // 1. 为目标版本创建唯一临时文件
        val directory = updateDirectory(context).apply { mkdirs() }
        if (!directory.isDirectory) throw IOException("无法创建更新目录：$directory")
        val finalFile = directory.resolve("Akashic-Mobile-${release.tagName}.apk")
        val partialFile = directory.resolve("${finalFile.name}.part")
        partialFile.deleteIfExistsOrThrow()

        // 2. 流式下载并计算摘要
        val request = Request.Builder().url(release.apkUrl).build()
        val digest = MessageDigest.getInstance("SHA-256")
        var downloaded = 0L
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载更新失败：GitHub 返回 HTTP ${response.code}")
            }
            val body = response.body
            body.byteStream().use { input ->
                partialFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgress = -1
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        val progress = (downloaded * 100 / release.apkSizeBytes).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            withContext(Dispatchers.Main.immediate) { onProgress(progress) }
                        }
                    }
                }
            }
        }

        // 3. 严格验证 GitHub 元数据后原子发布文件
        val actualSha256 = digest.digest().toHexString()
        if (downloaded != release.apkSizeBytes || actualSha256 != release.apkSha256) {
            partialFile.deleteIfExistsOrThrow()
            throw IOException("下载的 APK 与 GitHub Release 摘要不一致")
        }
        finalFile.deleteIfExistsOrThrow()
        if (!partialFile.renameTo(finalFile)) {
            partialFile.deleteIfExistsOrThrow()
            throw IOException("无法保存已验证的更新 APK")
        }
        PendingUpdateStore.save(context, release, finalFile)
        finalFile
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Throws(ActivityNotFoundException::class, SecurityException::class, IOException::class)
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    suspend fun resumableUpdate(context: Context): File? = withContext(Dispatchers.IO) {
        PendingUpdateStore.loadVerified(context)
    }

    @Throws(ActivityNotFoundException::class, SecurityException::class)
    fun launchInstaller(context: Context, apk: File) {
        // Android 系统安装器负责最终签名连续性与用户确认。
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        PendingUpdateStore.clear(context)
    }

    internal companion object {
        const val RELEASES_URL = "https://github.com/kachofugetsu09/akashic-mobile/releases"
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/kachofugetsu09/akashic-mobile/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val json = Json { ignoreUnknownKeys = true }

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        /** 解析并校验 GitHub 最新稳定 Release 的单一 APK 合同。 */
        internal fun parseLatestRelease(body: String, installedVersion: String): UpdateCheckResult {
            val release = try {
                json.decodeFromString<GitHubRelease>(body)
            } catch (error: SerializationException) {
                throw ReleaseContractException("GitHub Release 响应格式无效", error)
            }
            if (release.draft || release.prerelease) {
                throw ReleaseContractException("最新 Release 不是稳定发布")
            }
            val remoteVersion = SemanticVersion.parseReleaseTag(release.tagName)
            val localVersion = SemanticVersion.parseInstalled(installedVersion)
            val expectedName = "Akashic-Mobile-${release.tagName}.apk"
            val apk = release.assets.singleOrNull { it.name == expectedName }
                ?: throw ReleaseContractException("Release 必须且只能包含名为 $expectedName 的 APK")
            if (apk.size <= 0L) throw ReleaseContractException("Release APK 大小无效")
            val sha256 = requireSha256(apk.digest)
            val expectedPrefix = "$RELEASES_URL/download/${release.tagName}/"
            if (!apk.browserDownloadUrl.startsWith(expectedPrefix)) {
                throw ReleaseContractException("Release APK 下载地址不属于目标仓库")
            }
            val appRelease = AppRelease(
                tagName = release.tagName,
                versionName = remoteVersion.toString(),
                title = release.name.ifBlank { "Akashic Mobile ${release.tagName}" },
                notes = release.body,
                apkUrl = apk.browserDownloadUrl,
                apkSizeBytes = apk.size,
                apkSha256 = sha256,
            )
            return if (remoteVersion > localVersion) {
                UpdateCheckResult.Available(appRelease)
            } else {
                UpdateCheckResult.UpToDate(remoteVersion.toString())
            }
        }

        private fun requireSha256(value: String?): String {
            val match = value?.let(SHA256_PATTERN::matchEntire)
                ?: throw ReleaseContractException("Release APK 缺少有效的 SHA-256 摘要")
            return match.groupValues[1].lowercase()
        }

        private val SHA256_PATTERN = Regex("^sha256:([0-9a-fA-F]{64})$")
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String = "",
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubAsset>,
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long,
    val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

private data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parseReleaseTag(value: String): SemanticVersion = parse(value, RELEASE_PATTERN, "Release tag")

        fun parseInstalled(value: String): SemanticVersion = parse(value, INSTALLED_PATTERN, "本机版本")

        private fun parse(value: String, pattern: Regex, label: String): SemanticVersion {
            val match = pattern.matchEntire(value)
                ?: throw ReleaseContractException("$label 不符合 vMAJOR.MINOR.PATCH 版本约定：$value")
            return try {
                SemanticVersion(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            } catch (error: NumberFormatException) {
                throw ReleaseContractException("$label 数值超出范围：$value", error)
            }
        }

        private val RELEASE_PATTERN = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
        private val INSTALLED_PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].+)?$")
    }
}

/** 保存一次用户已主动下载但尚未交给系统安装器的 APK。 */
private object PendingUpdateStore {
    private const val PREFERENCES = "pending_app_update"
    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1_000L

    fun save(context: Context, release: AppRelease, file: File) {
        // 同步提交保证跳转系统授权页前恢复信息已经落盘。
        val committed = preferences(context).edit()
            .putString("version", release.versionName)
            .putString("path", file.absolutePath)
            .putLong("size", release.apkSizeBytes)
            .putString("sha256", release.apkSha256)
            .putLong("downloaded_at", System.currentTimeMillis())
            .commit()
        if (!committed) throw IOException("无法保存待安装更新状态")
    }

    /** 只返回仍新于本机、未过期且摘要匹配的受控更新文件。 */
    fun loadVerified(context: Context): File? {
        // 1. 恢复并校验持久化字段
        val prefs = preferences(context)
        val version = prefs.getString("version", null) ?: return null
        val path = prefs.getString("path", null) ?: return clearInvalid(context, null)
        val size = prefs.getLong("size", -1L)
        val sha256 = prefs.getString("sha256", null) ?: return clearInvalid(context, File(path))
        val downloadedAt = prefs.getLong("downloaded_at", 0L)
        val file = File(path)
        val expectedDirectory = updateDirectory(context).canonicalFile
        if (file.parentFile?.canonicalFile != expectedDirectory) return clearInvalid(context, null)

        // 2. 淘汰过期、已安装或损坏的更新缓存
        val age = System.currentTimeMillis() - downloadedAt
        val stillNewer = SemanticVersion.parseInstalled(version) >
            SemanticVersion.parseInstalled(BuildConfig.VERSION_NAME)
        if (age !in 0..MAX_AGE_MILLIS || !stillNewer || size <= 0L || !file.isFile) {
            return clearInvalid(context, file)
        }
        if (file.length() != size || file.sha256() != sha256) return clearInvalid(context, file)
        return file
    }

    fun clear(context: Context) {
        val prefs = preferences(context)
        if (!prefs.edit().clear().commit()) throw IOException("无法清除待安装更新状态")
    }

    private fun clearInvalid(context: Context, file: File?): File? {
        file?.takeIf(File::exists)?.deleteIfExistsOrThrow()
        clear(context)
        return null
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

private fun updateDirectory(context: Context): File = context.filesDir.resolve("updates")

private fun File.deleteIfExistsOrThrow() {
    if (exists() && !delete()) throw IOException("无法删除更新缓存：$absolutePath")
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }
