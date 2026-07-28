package com.akashic.mobile

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.Reader
import kotlin.system.exitProcess

internal object CrashDiagnostics {
    private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
    private const val LAST_INCIDENT_FILE = "last-incident.txt"
    private val LEGACY_DIAGNOSTIC_FILES = listOf(
        "last-crash.txt",
        "last-runtime-error.txt",
        "exit-history.txt",
    )
    private const val MAX_EXPORTED_SECTION_CHARS = 512 * 1024
    private const val MAX_EXIT_TRACE_CHARS = 512 * 1024

    /** 安装轻量异常记录器，并把旧版分散诊断收敛为最近一次事故。 */
    fun install(application: Application) {
        // 1. 先迁移旧版诊断，后续所有失败只覆盖同一个原子文件
        migrateLegacyDiagnostics(application)

        // 2. 接管未捕获异常，覆盖后续启动阶段
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeLastCrash(application, thread, error)
            } catch (_: IOException) {
                // 崩溃边界无法恢复写盘，只能继续交给系统终止并保留原始失败。
            } catch (_: SecurityException) {
                // 私有目录不可写时不能阻止系统处理原始崩溃。
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    /** 导出最近一次应用事故和最近一次系统退出，不携带历史列表。 */
    fun exportReport(application: Application): String {
        // 1. 始终带上当前构建身份，空报告也能确认测试版本
        val report = StringBuilder()
            .appendLine("Akashic Mobile diagnostics")
            .appendLine("generated_at_ms=${System.currentTimeMillis()}")
            .appendLine("app_version=${BuildConfig.VERSION_NAME}")
            .appendLine("version_code=${BuildConfig.VERSION_CODE}")
            .appendLine("package=${application.packageName}")
            .appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            .appendLine("android=${Build.VERSION.RELEASE}")
            .appendLine("sdk=${Build.VERSION.SDK_INT}")

        // 2. 单份日志包含最新应用异常与最新系统退出，用户只需分享这一份
        report.appendLine().appendLine("===== $LAST_INCIDENT_FILE =====")
        report.appendLine(readLastIncident(application))
        report.appendLine("latest_system_exit:")
        report.appendLine(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                readLatestSystemExit(application)
            } else {
                "<unavailable before Android 11>"
            },
        )
        return report.toString()
    }

    private fun writeLastCrash(application: Application, thread: Thread, error: Throwable) {
        writeLastIncident(
            application,
            formatCrashReport(System.currentTimeMillis(), thread.name, reportIdentity(), error),
        )
    }

    /** 覆盖保存最近一次已处理的功能阻断错误，不把诊断失败带回业务链。 */
    fun recordRuntimeError(application: Application, source: String, context: String, error: Throwable) {
        try {
            writeLastIncident(
                application,
                formatRuntimeErrorReport(
                    timestampMillis = System.currentTimeMillis(),
                    threadName = Thread.currentThread().name,
                    identity = reportIdentity(),
                    source = source,
                    context = context,
                    error = error,
                ),
            )
        } catch (_: IOException) {
            // 诊断写盘失败不能替换已经由业务边界处理的原始错误。
        } catch (_: SecurityException) {
            // 私有目录不可写时保留业务层原有的显式失败语义。
        }
    }

    @Synchronized
    private fun writeLastIncident(application: Application, report: String) {
        val target = AtomicFile(diagnosticsDirectory(application).resolve(LAST_INCIDENT_FILE))
        val output = target.startWrite()
        try {
            output.write(report.toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (error: IOException) {
            target.failWrite(output)
            throw error
        }
    }

    private fun readLastIncident(application: Application): String = try {
        val incidentFile = diagnosticsDirectory(application).resolve(LAST_INCIDENT_FILE)
        if (!incidentFile.isFile) {
            "<not recorded>"
        } else {
            val target = AtomicFile(incidentFile)
            target.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
                .take(MAX_EXPORTED_SECTION_CHARS)
        }
    } catch (_: IOException) {
        "<read failed>"
    } catch (_: SecurityException) {
        "<read denied>"
    }

    /** 首次升级时保留较新的旧异常，并停止导出三个互相竞争的文件。 */
    private fun migrateLegacyDiagnostics(application: Application) {
        try {
            val directory = diagnosticsDirectory(application)
            val incidentFile = directory.resolve(LAST_INCIDENT_FILE)
            val legacyFiles = LEGACY_DIAGNOSTIC_FILES
                .map(directory::resolve)
                .filter(File::isFile)
            if (!incidentFile.isFile) {
                legacyFiles
                    .filterNot { it.name == "exit-history.txt" }
                    .maxByOrNull(::diagnosticTimestamp)
                    ?.let { latest ->
                        writeLastIncident(
                            application,
                            "incident=legacy_migrated\n" + latest.readText(),
                        )
                    }
            }
            legacyFiles.forEach(File::delete)
        } catch (_: IOException) {
            // 诊断迁移失败不能阻止应用启动，后续新事故仍会尝试覆盖统一文件。
        } catch (_: SecurityException) {
            // 私有目录不可写时保留原文件，并继续安装原始崩溃处理器。
        }
    }

    private fun diagnosticTimestamp(file: File): Long =
        file.useLines { lines ->
            lines.mapNotNull { line ->
                line.removePrefix("timestamp_ms=")
                    .takeIf { it.length != line.length }
                    ?.toLongOrNull()
            }.firstOrNull()
        } ?: file.lastModified()

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readLatestSystemExit(application: Application): String = try {
        val activityManager = application.getSystemService(ActivityManager::class.java)
        val exit = activityManager.getHistoricalProcessExitReasons(
            application.packageName,
            0,
            1,
        ).firstOrNull()
        if (exit == null) {
            "<not recorded>"
        } else {
            buildString {
                appendLine("timestamp_ms=${exit.timestamp}")
                appendLine("reason=${exitReasonName(exit.reason)}")
                appendLine("status=${exit.status}")
                appendLine("importance=${exit.importance}")
                appendLine("description=${exit.description.orEmpty().toDiagnosticLine()}")
                exit.traceInputStream?.bufferedReader(Charsets.UTF_8)?.use { trace ->
                    appendLine("trace:")
                    append(trace.readAtMost(MAX_EXIT_TRACE_CHARS))
                }
            }
        }
    } catch (_: IOException) {
        "<read failed>"
    } catch (_: SecurityException) {
        "<read denied>"
    }

    private fun Reader.readAtMost(limit: Int): String {
        val result = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (result.length < limit) {
            val count = read(buffer, 0, minOf(buffer.size, limit - result.length))
            if (count < 0) return result.toString()
            result.append(buffer, 0, count)
        }
        if (read() < 0) return result.toString()
        val marker = "\n<system_exit_trace_truncated limit=$limit>\n"
        return result.substring(0, limit - marker.length) + marker
    }

    private fun String.toDiagnosticLine(): String =
        replace('\r', ' ').replace('\n', ' ').take(2 * 1024)

    private fun diagnosticsDirectory(application: Application): File =
        application.filesDir.resolve(DIAGNOSTICS_DIRECTORY).also { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("无法创建诊断目录")
            }
            if (!directory.isDirectory) throw IOException("诊断路径不是目录")
        }

    private fun reportIdentity() = CrashReportIdentity(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidRelease = Build.VERSION.RELEASE,
        sdk = Build.VERSION.SDK_INT,
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "UNKNOWN_$reason"
    }
}
