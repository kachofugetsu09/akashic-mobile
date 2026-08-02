package com.akashic.mobile.data.local

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest

class MessageContentStore(
    root: File,
    private val dao: MessageContentTransferDao,
) {
    private val root = root.canonicalFile

    init {
        check(this.root.isDirectory || this.root.mkdirs()) {
            "无法创建消息正文恢复目录: ${this.root}"
        }
    }

    fun partialFile(transfer: MessageContentTransferEntity): File =
        requireRegularPath(root.resolve("${cacheKey(transfer.messageId)}.utf8.part"))

    /** 启动时让临时文件只保留 DB 已确认的连续前缀。 */
    suspend fun reconcile() {
        val transfers = dao.all()
        transfers.forEach { transfer ->
            require(transfer.byteLength >= 0 && transfer.transferredBytes in 0..transfer.byteLength) {
                "消息正文恢复长度无效: ${transfer.messageId}"
            }
            require(SHA256.matches(transfer.sha256)) {
                "消息正文恢复摘要无效: ${transfer.messageId}"
            }
            val partial = partialFile(transfer)
            val actual = if (partial.exists()) partial.length() else 0L
            require(actual <= transfer.byteLength) {
                "消息正文临时文件超过声明长度: ${transfer.messageId}"
            }
            if (actual > transfer.transferredBytes) {
                RandomAccessFile(partial, "rw").use { file ->
                    file.setLength(transfer.transferredBytes)
                    file.fd.sync()
                }
            } else if (actual < transfer.transferredBytes) {
                check(
                    dao.updateProgress(
                        transfer.messageId,
                        actual,
                        if (actual == 0L) "pending" else "downloading",
                        System.currentTimeMillis(),
                    ) == 1,
                ) { "消息正文恢复记录已消失: ${transfer.messageId}" }
            }
        }
        val owned = transfers.mapTo(mutableSetOf()) { partialFile(it).name }
        requireNotNull(root.listFiles()) { "无法扫描消息正文恢复目录" }
            .filter { (it.isFile || Files.isSymbolicLink(it.toPath())) && it.name !in owned }
            .forEach(::deleteIfExists)
    }

    /** fsync 一个连续分片后才推进 Room 中的已确认 offset。 */
    suspend fun append(transfer: MessageContentTransferEntity, content: ByteArray): Long {
        require(content.isNotEmpty()) { "消息正文分片不能为空" }
        val nextOffset = Math.addExact(transfer.transferredBytes, content.size.toLong())
        require(nextOffset <= transfer.byteLength) { "消息正文分片超过声明长度" }
        require(root.usableSpace >= content.size.toLong()) { "消息正文恢复磁盘空间不足" }
        val partial = partialFile(transfer)
        RandomAccessFile(partial, "rw").use { file ->
            require(file.length() == transfer.transferredBytes) { "消息正文临时文件 offset 不连续" }
            file.seek(transfer.transferredBytes)
            file.write(content)
            file.fd.sync()
        }
        check(
            dao.updateProgress(
                transfer.messageId,
                nextOffset,
                "downloading",
                System.currentTimeMillis(),
            ) == 1,
        ) { "消息正文恢复记录已消失: ${transfer.messageId}" }
        return nextOffset
    }

    /** 完整校验摘要并严格解码 UTF-8，失败正文不得进入消息投影。 */
    fun readVerified(transfer: MessageContentTransferEntity): String {
        val partial = partialFile(transfer)
        require(partial.length() == transfer.byteLength) { "消息正文尚未下载完整" }
        require(sha256(partial) == transfer.sha256.lowercase()) { "消息正文 SHA-256 校验失败" }
        val bytes = partial.readBytes()
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    fun delete(transfer: MessageContentTransferEntity) {
        deleteIfExists(partialFile(transfer))
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireRegularPath(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.parentFile == root) { "消息正文恢复路径越界" }
        require(!Files.isSymbolicLink(file.toPath())) { "消息正文恢复路径不能是符号链接" }
        return canonical
    }

    private fun deleteIfExists(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.exists()) {
            check(file.delete()) { "无法删除消息正文临时文件: $file" }
        }
    }

    private fun cacheKey(messageId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(messageId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
