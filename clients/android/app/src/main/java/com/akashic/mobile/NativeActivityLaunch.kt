package com.akashic.mobile

import android.content.ActivityNotFoundException

/** Run an external activity launch and notify the presentation owner only after it succeeds. */
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
