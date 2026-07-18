package com.akashic.mobile.data.realtime.pluginui

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

class PluginUiAssetStore(
    private val root: File,
    private val maxCacheBytes: Long = MAX_CACHE_BYTES,
    private val maxCacheEntries: Int = MAX_CACHE_ENTRIES,
) : WebViewAssetLoader.PathHandler {
    init {
        require(maxCacheBytes > 0) { "插件 UI 缓存预算必须为正数" }
        require(maxCacheEntries > 0) { "插件 UI 缓存条目预算必须为正数" }
        check(root.exists() || root.mkdirs()) { "无法创建插件 UI 缓存目录: $root" }
        require(root.isDirectory) { "插件 UI 缓存根目录不是目录: $root" }
    }

    fun contains(sha256: String, kind: String, expectedBytes: Int): Boolean {
        validateIdentity(sha256, kind)
        val file = target(sha256, kind)
        if (!file.isFile) return false
        val valid = file.length() == expectedBytes.toLong() && file.readBytes().sha256() == sha256
        if (!valid) check(file.delete()) { "无法删除损坏插件资源: $sha256" }
        if (valid) touch(file)
        return valid
    }

    /** 校验摘要并原子提交一个不可变插件资源。 */
    fun store(
        sha256: String,
        kind: String,
        content: String,
        expectedBytes: Int,
        retainedSha256: Set<String> = emptySet(),
    ) {

        // 1. 在网络边界验证长度和内容身份
        validateIdentity(sha256, kind)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size == expectedBytes) { "插件 UI 资源长度不一致: $sha256" }
        require(bytes.sha256() == sha256) { "插件 UI 资源摘要不一致: $sha256" }
        if (contains(sha256, kind, expectedBytes)) {
            trimToBudget(retainedSha256 + sha256)
            return
        }

        // 2. 同一文件系统内写临时文件并原子改名
        val directory = root.resolve(sha256)
        check(directory.exists() || directory.mkdirs()) { "无法创建插件资源目录: $directory" }
        val destination = target(sha256, kind)
        val temporary = directory.resolve(".${destination.name}.${System.nanoTime()}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (destination.exists()) check(destination.delete()) { "无法删除损坏插件资源: $sha256" }
        check(temporary.renameTo(destination)) { "插件 UI 资源原子提交失败: $sha256" }
        trimToBudget(retainedSha256 + sha256)
    }

    /** 保留活动目录，并按最近使用顺序把内容寻址缓存裁到固定预算。 */
    fun trimToBudget(retainedSha256: Set<String>) {
        retainedSha256.forEach { require(SHA256.matches(it)) { "插件 UI 保留资源身份无效" } }

        // 1. 清理进程退出留下的临时文件并统计真实缓存占用
        val directories = requireNotNull(root.listFiles()) { "无法扫描插件 UI 缓存目录" }
            .filter(File::isDirectory)
        directories.forEach { directory ->
            requireNotNull(directory.listFiles()) { "无法扫描插件 UI 资源目录: $directory" }
                .filter { it.isFile && it.name.startsWith('.') && it.name.endsWith(".tmp") }
                .forEach { check(it.delete()) { "无法删除插件 UI 临时文件: $it" } }
        }
        val entries = directories.map { directory ->
            val files = requireNotNull(directory.listFiles()) { "无法扫描插件 UI 资源目录: $directory" }
                .filter(File::isFile)
            CacheEntry(
                directory = directory,
                bytes = files.sumOf(File::length),
                lastUsedAt = maxOf(directory.lastModified(), files.maxOfOrNull(File::lastModified) ?: 0L),
            )
        }
        var totalBytes = entries.sumOf(CacheEntry::bytes)
        val retainedEntries = entries.filter { it.directory.name in retainedSha256 }
        val retainedBytes = retainedEntries.sumOf(CacheEntry::bytes)
        check(retainedBytes <= maxCacheBytes) { "活动插件 UI 资源超过缓存预算" }
        check(retainedEntries.size <= maxCacheEntries) { "活动插件 UI 资源条目超过缓存预算" }

        // 2. 只淘汰非活动目录，直到重新满足全局磁盘与条目上限
        var totalEntries = entries.size
        for (entry in entries.asSequence()
            .filter { it.directory.name !in retainedSha256 }
            .sortedWith(compareBy(CacheEntry::lastUsedAt, { it.directory.name }))) {
            if (totalBytes <= maxCacheBytes && totalEntries <= maxCacheEntries) break
            check(entry.directory.deleteRecursively()) {
                "无法裁剪插件 UI 资源: ${entry.directory.name}"
            }
            totalBytes -= entry.bytes
            totalEntries -= 1
        }
        check(totalBytes <= maxCacheBytes && totalEntries <= maxCacheEntries) {
            "插件 UI 缓存无法裁到预算内"
        }
    }

    fun contentUrl(sha256: String, kind: String): String {
        validateIdentity(sha256, kind)
        return "https://appassets.androidplatform.net/plugin-ui/$sha256/${filename(kind)}"
    }

    override fun handle(path: String): WebResourceResponse? {
        val parts = path.split('/')
        if (parts.size != 2) return null
        val sha256 = parts[0]
        val kind = when (parts[1]) {
            "module.js" -> "module"
            "style.css" -> "stylesheet"
            else -> return null
        }
        if (!SHA256.matches(sha256)) return null
        val file = target(sha256, kind)
        if (!file.isFile) return null
        val stream = try {
            file.inputStream()
        } catch (_: FileNotFoundException) {
            return null
        }
        val mime = if (kind == "module") "text/javascript" else "text/css"
        return WebResourceResponse(mime, "utf-8", stream)
    }

    private fun target(sha256: String, kind: String): File =
        root.resolve(sha256).resolve(filename(kind))

    private fun validateIdentity(sha256: String, kind: String) {
        require(SHA256.matches(sha256)) { "插件 UI sha256 无效" }
        require(kind in setOf("module", "stylesheet")) { "插件 UI 资源类型无效" }
    }

    private fun filename(kind: String): String =
        if (kind == "module") "module.js" else "style.css"

    private fun touch(file: File) {
        val now = System.currentTimeMillis()
        check(file.setLastModified(now)) { "无法更新插件 UI 资源访问时间: ${file.name}" }
        check(requireNotNull(file.parentFile).setLastModified(now)) {
            "无法更新插件 UI 目录访问时间: ${file.parentFile?.name}"
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_ENTRIES = 512
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }

    private data class CacheEntry(
        val directory: File,
        val bytes: Long,
        val lastUsedAt: Long,
    )
}
