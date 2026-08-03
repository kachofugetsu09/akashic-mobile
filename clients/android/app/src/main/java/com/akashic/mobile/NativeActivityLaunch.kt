package com.akashic.mobile

import android.content.ActivityNotFoundException

/** 执行一次外部 Activity 启动；仅在成功后通知展示 owner。 */
internal fun nativeActivityLaunchSucceeded(
    launch: () -> Unit,
    onSuccess: () -> Unit,
): Boolean {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
    onSuccess()
    return true
}

/** 按兼容性顺序尝试外部 Activity，首个成功启动后只通知一次展示 owner。 */
internal fun nativeActivityLaunchWithFallback(
    launches: List<() -> Unit>,
    onSuccess: () -> Unit,
): Boolean {
    require(launches.isNotEmpty()) { "至少需要一个外部 Activity 启动方式" }
    for (launch in launches) {
        if (nativeActivityLaunchSucceeded(launch, onSuccess)) return true
    }
    return false
}
