package com.akashic.mobile

internal data class CrashReportIdentity(
    val appVersion: String,
    val versionCode: Long,
    val device: String,
    val androidRelease: String,
    val sdk: Int,
)

/** 把未捕获异常完整格式化为固定上限、可离线导出的诊断文本。 */
internal fun formatCrashReport(
    timestampMillis: Long,
    threadName: String,
    identity: CrashReportIdentity,
    error: Throwable,
): String = formatIncidentReport(
    incident = "uncaught_exception",
    timestampMillis = timestampMillis,
    threadName = threadName,
    identity = identity,
    error = error,
)

/** 把已处理但会阻断功能的运行失败完整格式化为带上下文的有界诊断。 */
internal fun formatRuntimeErrorReport(
    timestampMillis: Long,
    threadName: String,
    identity: CrashReportIdentity,
    source: String,
    context: String,
    error: Throwable,
): String = formatIncidentReport(
    incident = "runtime_error",
    timestampMillis = timestampMillis,
    threadName = threadName,
    identity = identity,
    source = source,
    context = context,
    error = error,
)

/** 保留 Throwable 的完整 frame、cause 与 suppressed 链，并显式标记极端超限。 */
private fun formatIncidentReport(
    incident: String,
    timestampMillis: Long,
    threadName: String,
    identity: CrashReportIdentity,
    source: String? = null,
    context: String? = null,
    error: Throwable,
): String {
    // 1. 写入定位构建、设备和失败边界所需的元数据
    val report = StringBuilder()
        .appendLine("incident=$incident")
    if (source != null) report.appendLine("source=${source.toDiagnosticLine()}")
    if (context != null) report.appendLine("context=${context.toDiagnosticLine()}")
    report
        .appendLine("timestamp_ms=$timestampMillis")
        .appendLine("thread=$threadName")
        .appendLine("app_version=${identity.appVersion}")
        .appendLine("version_code=${identity.versionCode}")
        .appendLine("device=${identity.device}")
        .appendLine("android=${identity.androidRelease}")
        .appendLine("sdk=${identity.sdk}")
        .appendLine("exception=$error")
        .appendLine("stack_trace:")

    // 2. 使用平台格式保留完整异常树，只对异常大的单份报告设置硬上限
    report.append(error.stackTraceToString())
    if (!report.endsWith('\n')) report.appendLine()
    return report.toString().boundedDiagnosticReport()
}

private fun String.toDiagnosticLine(): String =
    replace('\r', ' ').replace('\n', ' ').take(MAX_CONTEXT_CHARS)

private fun String.boundedDiagnosticReport(): String {
    if (length <= MAX_REPORT_CHARS) return this
    val marker = "\n<diagnostic_truncated original_chars=$length limit=$MAX_REPORT_CHARS>\n"
    return take(MAX_REPORT_CHARS - marker.length) + marker
}

private const val MAX_REPORT_CHARS = 512 * 1024
private const val MAX_CONTEXT_CHARS = 2 * 1024
