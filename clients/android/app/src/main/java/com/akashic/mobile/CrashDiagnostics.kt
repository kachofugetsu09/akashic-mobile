package com.akashic.mobile

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

internal object CrashDiagnostics {
    private const val DIAGNOSTICS_DIRECTORY = "diagnostics"
    private const val LAST_CRASH_FILE = "last-crash.txt"
    private const val EXIT_HISTORY_FILE = "exit-history.txt"
    private const val MAX_EXIT_REASONS = 8
    private const val MAX_EXPORTED_SECTION_CHARS = 128 * 1024

    /** 安装轻量异常记录器，并保存系统最近的进程退出原因。 */
    fun install(application: Application) {
        // 1. 先接管未捕获异常，覆盖后续启动阶段
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

        // 2. Android 11+ 保存系统判定的退出类型，不读取可能很大的 trace
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) writeExitHistory(application)
    }

    /** 汇总当前构建和已有诊断记录，供用户通过系统分享页主动导出。 */
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

        // 2. 私有诊断文件不存在表示尚未观察到对应事件，不伪造崩溃
        listOf(LAST_CRASH_FILE, EXIT_HISTORY_FILE).forEach { name ->
            report.appendLine().appendLine("===== $name =====")
            val content = try {
                diagnosticsDirectory(application).resolve(name).takeIf(File::isFile)?.readText()
                    ?: "<not recorded>"
            } catch (_: IOException) {
                "<read failed>"
            } catch (_: SecurityException) {
                "<read denied>"
            }
            report.appendLine(content.take(MAX_EXPORTED_SECTION_CHARS))
        }
        return report.toString()
    }

    private fun writeLastCrash(application: Application, thread: Thread, error: Throwable) {
        val identity = CrashReportIdentity(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
        )
        diagnosticsDirectory(application).resolve(LAST_CRASH_FILE).writeText(
            formatCrashReport(System.currentTimeMillis(), thread.name, identity, error),
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeExitHistory(application: Application) {
        try {
            val activityManager = application.getSystemService(ActivityManager::class.java)
            val exits = activityManager.getHistoricalProcessExitReasons(
                application.packageName,
                0,
                MAX_EXIT_REASONS,
            )
            val report = buildString {
                appendLine("app_version=${BuildConfig.VERSION_NAME}")
                appendLine("version_code=${BuildConfig.VERSION_CODE}")
                exits.forEach { exit ->
                    appendLine("---")
                    appendLine("timestamp_ms=${exit.timestamp}")
                    appendLine("reason=${exitReasonName(exit.reason)}")
                    appendLine("status=${exit.status}")
                    appendLine("importance=${exit.importance}")
                    appendLine("description=${exit.description.orEmpty().take(512)}")
                }
            }
            diagnosticsDirectory(application).resolve(EXIT_HISTORY_FILE).writeText(report)
        } catch (_: IOException) {
            // 历史退出原因只是诊断投影，写入失败不影响应用正常启动。
        } catch (_: SecurityException) {
            // 私有目录不可写时由之后的真实崩溃继续暴露环境问题。
        }
    }

    private fun diagnosticsDirectory(application: Application): File =
        application.filesDir.resolve(DIAGNOSTICS_DIRECTORY).also { directory ->
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("无法创建诊断目录")
            }
            if (!directory.isDirectory) throw IOException("诊断路径不是目录")
        }

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
