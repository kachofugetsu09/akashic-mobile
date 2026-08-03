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
