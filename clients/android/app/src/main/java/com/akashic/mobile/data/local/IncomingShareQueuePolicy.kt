package com.akashic.mobile.data.local

internal const val MAX_PENDING_INCOMING_SHARES = 32
internal const val MAX_PENDING_INCOMING_SHARE_BYTES = 200L * 1024 * 1024

internal data class IncomingShareQuotaUsage(
    val pendingCount: Int,
    val pendingBytes: Long,
)

/** 在复制新附件前及复制过程中检查待处理分享的全局容量。 */
internal fun requireIncomingShareCapacity(
    usage: IncomingShareQuotaUsage,
    incomingBytes: Long,
    hasIncomingFiles: Boolean,
) {
    // 1. 先验证容量统计自身仍满足队列不变量
    require(usage.pendingCount >= 0 && usage.pendingBytes >= 0 && incomingBytes >= 0) {
        "系统分享容量统计无效"
    }
    require(hasIncomingFiles || incomingBytes == 0L) { "无附件分享不能增加附件字节" }

    // 2. 新记录不得让全局条数或附件总量越界
    require(usage.pendingCount < MAX_PENDING_INCOMING_SHARES) {
        "待处理系统分享不能超过 $MAX_PENDING_INCOMING_SHARES 条"
    }
    require(usage.pendingBytes <= MAX_PENDING_INCOMING_SHARE_BYTES) {
        "待处理系统分享附件已超过全局容量"
    }
    if (hasIncomingFiles) {
        require(usage.pendingBytes < MAX_PENDING_INCOMING_SHARE_BYTES) {
            "待处理系统分享附件总量不能超过 200 MiB"
        }
    }
    require(incomingBytes <= MAX_PENDING_INCOMING_SHARE_BYTES - usage.pendingBytes) {
        "待处理系统分享附件总量不能超过 200 MiB"
    }
}
