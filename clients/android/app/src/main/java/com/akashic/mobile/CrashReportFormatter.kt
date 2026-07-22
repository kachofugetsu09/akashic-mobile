package com.akashic.mobile

internal data class CrashReportIdentity(
    val appVersion: String,
    val versionCode: Long,
    val device: String,
    val androidRelease: String,
    val sdk: Int,
)

/** 把未捕获异常压缩为固定上限、可离线导出的诊断文本。 */
internal fun formatCrashReport(
    timestampMillis: Long,
    threadName: String,
    identity: CrashReportIdentity,
    error: Throwable,
): String {
    // 1. 写入定位构建与设备环境所需的最小元数据
    val report = StringBuilder()
        .appendLine("timestamp_ms=$timestampMillis")
        .appendLine("thread=$threadName")
        .appendLine("app_version=${identity.appVersion}")
        .appendLine("version_code=${identity.versionCode}")
        .appendLine("device=${identity.device}")
        .appendLine("android=${identity.androidRelease}")
        .appendLine("sdk=${identity.sdk}")

    // 2. 限制 cause 深度与每层栈帧，避免诊断文件无界增长
    var current: Throwable? = error
    var causeDepth = 0
    while (current != null && causeDepth < MAX_CAUSE_DEPTH) {
        report.appendLine(if (causeDepth == 0) "exception=$current" else "caused_by=$current")
        current.stackTrace.take(MAX_FRAMES_PER_CAUSE).forEach { frame ->
            report.appendLine("    at $frame")
        }
        current = current.cause?.takeUnless { it === current }
        causeDepth += 1
    }
    if (current != null) report.appendLine("caused_by=<truncated>")
    return report.toString().take(MAX_REPORT_CHARS)
}

/** 把已处理但会阻断功能的运行失败保存为带上下文的有界诊断。 */
internal fun formatRuntimeErrorReport(
    timestampMillis: Long,
    threadName: String,
    identity: CrashReportIdentity,
    source: String,
    context: String,
    error: Throwable,
): String = buildString {
    appendLine("source=${source.toDiagnosticLine()}")
    appendLine("context=${context.toDiagnosticLine()}")
    append(formatCrashReport(timestampMillis, threadName, identity, error))
}.take(MAX_REPORT_CHARS)

private fun String.toDiagnosticLine(): String =
    replace('\r', ' ').replace('\n', ' ').take(MAX_CONTEXT_CHARS)

private const val MAX_CAUSE_DEPTH = 8
private const val MAX_FRAMES_PER_CAUSE = 64
private const val MAX_REPORT_CHARS = 128 * 1024
private const val MAX_CONTEXT_CHARS = 2 * 1024
