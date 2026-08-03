package com.akashic.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.BadParcelableException
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.akashic.mobile.ui.design.AkashicTheme
import com.akashic.mobile.ui.conversation.MessageAttachmentUi
import com.akashic.mobile.ui.pairing.PairingScreen
import com.akashic.mobile.ui.settings.SettingsScreen
import com.akashic.mobile.ui.web.MobileWebChat
import com.akashic.mobile.ui.web.MobileSharedTextDraft
import com.akashic.mobile.ui.web.openCachedAttachment
import com.akashic.mobile.ui.web.shareCachedAttachment
import com.akashic.mobile.ui.web.saveCachedAttachment
import com.akashic.mobile.ui.web.withCachedAttachment
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.launch

private val MOBILE_WEB_UI_PROCESS_TOKEN = UUID.randomUUID().toString()

internal fun mobileWebUiSessionStartedForProcess(
    savedProcessToken: String?,
    currentProcessToken: String,
    savedServerId: String?,
    currentServerId: String?,
    savedSessionStarted: Boolean,
): Boolean = savedSessionStarted && savedProcessToken == currentProcessToken &&
    savedServerId != null && savedServerId == currentServerId

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<MainViewModel>()
    private val notificationsEnabled = mutableStateOf(true)
    private val settingsOpen = mutableStateOf(false)
    private var leftForeground = false
    private val mobileWebUiPresentationEpoch = mutableStateOf(0L)
    private var nativeResultPending = false
    private var nativeResultReturned = false
    private var nativeResultResumeObserved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mobileWebUiPresentationEpoch.value = savedInstanceState?.getLong(KEY_MOBILE_WEB_UI_EPOCH) ?: 0L
        settingsOpen.value = savedInstanceState?.getBoolean(KEY_SETTINGS_OPEN) ?: false
        consumeIncomingShare(intent)
        takeNotificationTarget(intent)?.let(viewModel::acceptNotificationTarget)
        notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
        MobileConnectionService.start(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            AkashicTheme {
                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    markNativeActivityResultReturned()
                    notificationsEnabled.value = granted
                }
                val attachmentPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    markNativeActivityResultReturned()
                    if (uris.isNotEmpty()) viewModel.addAttachments(uris)
                }
                val pendingSave = remember { mutableStateOf<MessageAttachmentUi?>(null) }
                val notificationNoticeDismissed = rememberSaveable { mutableStateOf(false) }
                val attachmentSaver = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream"),
                ) { destination ->
                    markNativeActivityResultReturned()
                    val attachment = pendingSave.value
                    pendingSave.value = null
                    if (destination != null && attachment != null) {
                        lifecycleScope.launch {
                            try {
                                saveCachedAttachment(this@MainActivity, attachment, destination)
                                Toast.makeText(
                                    this@MainActivity,
                                    "已保存 ${attachment.filename}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (error: IOException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "保存失败：${error.message}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (error: SecurityException) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "没有写入所选位置的权限",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                val session by viewModel.sessionState.collectAsStateWithLifecycle()
                val conversation by viewModel.conversationState.collectAsStateWithLifecycle()
                val incomingShare by viewModel.incomingShare.collectAsStateWithLifecycle()
                val pluginUiCatalog by viewModel.pluginUiCatalog.collectAsStateWithLifecycle()
                val mobileWebUiServing by viewModel.mobileWebUiServing.collectAsStateWithLifecycle()
                val mobileWebUiAttempt by viewModel.mobileWebUiAttempt.collectAsStateWithLifecycle()
                val mobileWebUiReadyGeneration by viewModel.mobileWebUiReadyGeneration.collectAsStateWithLifecycle()
                val mobileWebUiResetEvent by viewModel.mobileWebUiResetEvent.collectAsStateWithLifecycle()
                val mobileWebUiWaitForSpace by viewModel.mobileWebUiWaitForSpace.collectAsStateWithLifecycle()
                val mobileWebUiRetryAvailable by viewModel.mobileWebUiRetryAvailable.collectAsStateWithLifecycle()
                val pairingRecoveryRequired by viewModel.pairingRecoveryRequired.collectAsStateWithLifecycle()
                val mobileWebUiApplyGeneration = remember { mutableStateOf<String?>(null) }
                val mobileWebUiSessionStarted = rememberSaveable { mutableStateOf(false) }
                val mobileWebUiSessionServerId = rememberSaveable { mutableStateOf<String?>(null) }
                val mobileWebUiSessionProcessToken = rememberSaveable { mutableStateOf<String?>(null) }
                val pairingRecoveryNoticeDismissed = rememberSaveable(pairingRecoveryRequired) {
                    mutableStateOf(false)
                }
                val processSessionStarted = mobileWebUiSessionStartedForProcess(
                    savedProcessToken = mobileWebUiSessionProcessToken.value,
                    currentProcessToken = MOBILE_WEB_UI_PROCESS_TOKEN,
                    savedServerId = mobileWebUiSessionServerId.value,
                    currentServerId = session.serverId,
                    savedSessionStarted = mobileWebUiSessionStarted.value,
                )
                LaunchedEffect(Unit) {
                    if (mobileWebUiSessionProcessToken.value != MOBILE_WEB_UI_PROCESS_TOKEN) {
                        mobileWebUiSessionProcessToken.value = MOBILE_WEB_UI_PROCESS_TOKEN
                        mobileWebUiSessionStarted.value = false
                    }
                }
                LaunchedEffect(session.serverId) {
                    if (mobileWebUiSessionServerId.value != session.serverId) {
                        mobileWebUiSessionServerId.value = session.serverId
                        mobileWebUiSessionStarted.value = false
                    }
                }
                LaunchedEffect(Unit) {
                    if (shouldRequestNotificationPermission()) {
                        markNotificationPermissionRequested()
                        if (!nativeActivityLaunchSucceeded(
                                launch = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                onSuccess = ::markNativeActivityResultLaunched,
                            )
                        ) {
                            Toast.makeText(this@MainActivity, "无法打开通知权限请求", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                LaunchedEffect(session.hasProfile) {
                    if (session.hasProfile) MobileConnectionService.start(this@MainActivity)
                }
                LaunchedEffect(
                    session.initialized,
                    session.hasProfile,
                    session.currentSessionId,
                    incomingShare?.id,
                    incomingShare?.targetSessionId,
                ) {
                    val share = incomingShare
                    if (
                        session.initialized && session.hasProfile &&
                        session.currentSessionId != null && share != null &&
                        share.targetSessionId == null
                    ) {
                        viewModel.claimIncomingShareTarget(share.id)
                    }
                }
                LaunchedEffect(
                    incomingShare?.id,
                    incomingShare?.targetSessionId,
                    incomingShare?.hasPreparedText,
                    incomingShare?.errorMessage,
                ) {
                    val share = incomingShare
                    if (
                        share?.targetSessionId != null && share.hasPreparedText &&
                        share.errorMessage == null
                    ) {
                        viewModel.resumePreparedIncomingShareText(share.id)
                    }
                }
                LaunchedEffect(
                    incomingShare?.id,
                    incomingShare?.targetSessionId,
                    incomingShare?.hasPendingAttachments,
                    incomingShare?.text,
                    incomingShare?.revision,
                    incomingShare?.errorMessage,
                ) {
                    val share = incomingShare
                    if (
                        share?.targetSessionId != null && share.hasPendingAttachments &&
                        share.text == null && share.errorMessage == null
                    ) {
                        viewModel.dispatchIncomingShareAttachments(share.id)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (!session.initialized) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    } else if (session.hasProfile) {
                        MobileWebChat(
                            state = conversation,
                            sharedTextDraft = incomingShare?.text?.let { text ->
                                val share = requireNotNull(incomingShare)
                                share.targetSessionId?.let { sessionId ->
                                    MobileSharedTextDraft(share.id, sessionId, text, share.revision)
                                }
                            },
                            onCommitSharedText = viewModel::commitIncomingShareText,
                            onSharedTextRejected = viewModel::reportIncomingShareError,
                            pluginUiCatalog = pluginUiCatalog,
                            pluginUiResults = viewModel.pluginUiResults,
                            pluginUiAssetStore = viewModel.pluginUiAssetStore,
                            mobileWebUiStore = viewModel.mobileWebUiStore,
                            mobileWebUiServerId = session.serverId,
                            mobileWebUiServingGeneration = mobileWebUiServing
                                ?.takeIf { it.serverId == session.serverId }
                                ?.generationId,
                            mobileWebUiAttemptLease = mobileWebUiAttempt
                                ?.takeIf { it.serverId == session.serverId },
                            mobileWebUiReadyGeneration = mobileWebUiReadyGeneration,
                            mobileWebUiResetEvent = mobileWebUiResetEvent,
                            mobileWebUiPresentationEpoch = mobileWebUiPresentationEpoch.value,
                            mobileWebUiSessionStarted = processSessionStarted,
                            onMobileWebUiSessionStarted = { mobileWebUiSessionStarted.value = true },
                            onNativeActivityResultLaunched = ::markNativeActivityResultLaunched,
                            mobileWebUiApplyGeneration = mobileWebUiApplyGeneration.value,
                            mobileWebUiCanReplaceUi = settingsOpen.value,
                            onMobileWebUiApplyHandled = { mobileWebUiApplyGeneration.value = null },
                            mobileWebUiCoordinator = viewModel.mobileWebUiCoordinator,
                            onSelectSession = viewModel::selectSession,
                            onRemoveUnavailableSession = viewModel::removeUnavailableSession,
                            onNewSession = viewModel::createSession,
                            onRestartPairing = viewModel::restartPairing,
                            onReloadFromServer = viewModel::reloadFromServer,
                            onExportDiagnostics = ::shareDiagnostics,
                            onOpenSettings = { settingsOpen.value = true },
                            onAttach = {
                                if (!nativeActivityLaunchSucceeded(
                                        launch = { attachmentPicker.launch(arrayOf("*/*")) },
                                        onSuccess = ::markNativeActivityResultLaunched,
                                    )
                                ) {
                                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onRemoveAttachment = viewModel::removeAttachment,
                            onRetryAttachment = viewModel::retryAttachment,
                            onContinueMeteredTransfer = viewModel::continueLargeTransfersOnMeteredNetwork,
                            onRetryFailedMessage = viewModel::retryFailedMessage,
                            onSaveComposerDraft = viewModel::saveComposerDraft,
                            onSaveReadingPosition = viewModel::saveReadingPosition,
                            onMarkSessionReadThrough = viewModel::markSessionReadThrough,
                            onNavigationTargetHandled = viewModel::acknowledgeNavigationTarget,
                            onRetryDownloadedAttachment = viewModel::retryDownloadedAttachment,
                            onTouchDownloadedAttachment = viewModel::touchDownloadedAttachment,
                            onOpenDownloadedAttachment = { attachmentId ->
                                withCachedAttachment(this@MainActivity, conversation, attachmentId) {
                                    viewModel.touchDownloadedAttachment(attachmentId)
                                    openCachedAttachment(
                                        this@MainActivity,
                                        it,
                                        ::markNativeActivityResultLaunched,
                                    )
                                }
                            },
                            onShareDownloadedAttachment = { attachmentId ->
                                withCachedAttachment(this@MainActivity, conversation, attachmentId) {
                                    viewModel.touchDownloadedAttachment(attachmentId)
                                    shareCachedAttachment(
                                        this@MainActivity,
                                        it,
                                        ::markNativeActivityResultLaunched,
                                    )
                                }
                            },
                            onSaveDownloadedAttachment = { attachmentId ->
                                withCachedAttachment(this@MainActivity, conversation, attachmentId) {
                                    viewModel.touchDownloadedAttachment(attachmentId)
                                    pendingSave.value = it
                                    if (!nativeActivityLaunchSucceeded(
                                            launch = { attachmentSaver.launch(it.filename) },
                                            onSuccess = ::markNativeActivityResultLaunched,
                                        )
                                    ) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "无法打开保存位置选择器",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onDismissError = viewModel::dismissError,
                            onSend = viewModel::sendMessage,
                            onSendCommand = viewModel::sendCommand,
                            onRefreshRuntimeInspection = viewModel::refreshRuntimeInspection,
                            onOpenRuntimeDocument = viewModel::openRuntimeDocument,
                            onOpenRuntimeMcp = viewModel::openRuntimeMcp,
                            onOpenRuntimeJob = viewModel::openRuntimeJob,
                            onClearRuntimeInspectionDetail =
                                viewModel::clearRuntimeInspectionDetail,
                            onPluginUiQuery = viewModel::queryPluginUi,
                            onPluginUiOwnerCancelled = viewModel::cancelPluginUiOwner,
                            onPluginUiWebViewDisposed = viewModel::disposePluginUiWebView,
                            onStop = viewModel::stopCurrentTurn,
                            onBackAtRoot = ::finish,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PairingScreen(
                            scanGeneration = session.scanGeneration,
                            confirmationCode = session.pairingConfirmationCode,
                            errorMessage = session.errorMessage,
                            onQrCode = viewModel::onQrCode,
                        )
                    }
                    val shareError = incomingShare?.errorMessage
                    if (shareError != null) {
                        IncomingShareNotice(
                            message = shareError,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            onRetry = { viewModel.retryIncomingShare(requireNotNull(incomingShare).id) },
                            onDiscard = { viewModel.discardIncomingShare(requireNotNull(incomingShare).id) },
                        )
                    } else if (pairingRecoveryNoticeVisible(
                            required = pairingRecoveryRequired,
                            dismissed = pairingRecoveryNoticeDismissed.value,
                            hasProfile = session.hasProfile,
                        )
                    ) {
                        PairingRecoveryNotice(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            onRestartPairing = viewModel::restartPairing,
                            onDismiss = { pairingRecoveryNoticeDismissed.value = true },
                        )
                    } else if (notificationPermissionNoticeVisible(
                            notificationsEnabled = notificationsEnabled.value,
                            permissionRequested = notificationPermissionWasRequested(),
                            dismissed = notificationNoticeDismissed.value,
                        )
                    ) {
                        NotificationPermissionNotice(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 20.dp),
                            onOpenSettings = {
                                notificationNoticeDismissed.value = true
                                openNotificationSettings()
                            },
                            onDismiss = { notificationNoticeDismissed.value = true },
                        )
                    }
                    if (settingsOpen.value) {
                        SettingsScreen(
                            onBack = { settingsOpen.value = false },
                            mobileWebUiStore = viewModel.mobileWebUiStore,
                            mobileWebUiCoordinator = viewModel.mobileWebUiCoordinator,
                            mobileWebUiServerId = session.serverId,
                            mobileWebUiReadyGeneration = mobileWebUiReadyGeneration,
                            mobileWebUiWaitForSpace = mobileWebUiWaitForSpace,
                            mobileWebUiRetryAvailable = mobileWebUiRetryAvailable,
                            mobileWebUiCanReplaceUi = settingsOpen.value,
                            onCollectMobileWebUi = viewModel::collectMobileWebUiGarbage,
                            onResetMobileWebUi = viewModel::resetMobileWebUi,
                            onRetryMobileWebUi = viewModel::retryMobileWebUi,
                            onApplyMobileWebUi = {
                                val generation = mobileWebUiReadyGeneration
                                if (settingsOpen.value && generation != null) {
                                    mobileWebUiApplyGeneration.value = generation
                                    true
                                } else {
                                    false
                                }
                            },
                            onNativeActivityResultLaunched = ::markNativeActivityResultLaunched,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingShare(intent)
        takeNotificationTarget(intent)?.let(viewModel::acceptNotificationTarget)
    }

    override fun onResume() {
        super.onResume()
        if (nativeResultPending || nativeResultReturned) {
            nativeResultResumeObserved = true
            nativeResultPending = false
            nativeResultReturned = false
            leftForeground = false
        } else if (shouldAdvanceMobileWebUiPresentationEpoch(leftForeground, isChangingConfigurations)) {
            mobileWebUiPresentationEpoch.value += 1L
            leftForeground = false
        }
        viewModel.onForeground()
        notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
        MobileConnectionService.dismissMessageNotifications(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_SETTINGS_OPEN, settingsOpen.value)
        outState.putLong(KEY_MOBILE_WEB_UI_EPOCH, mobileWebUiPresentationEpoch.value)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        leftForeground = !isChangingConfigurations
        super.onPause()
    }

    private fun consumeIncomingShare(intent: Intent) {
        val incoming = try {
            parseIncomingShare(intent)
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message, Toast.LENGTH_SHORT).show()
            intent.action = null
            return
        } catch (_: BadParcelableException) {
            Toast.makeText(this, "分享内容无法读取", Toast.LENGTH_SHORT).show()
            intent.action = null
            return
        }
        if (incoming == null) return
        lifecycleScope.launch {
            try {
                viewModel.acceptIncomingShare(incoming)
                intent.action = null
            } catch (error: IllegalArgumentException) {
                Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_SHORT).show()
            } catch (_: IOException) {
                Toast.makeText(this@MainActivity, "共享内容暂存失败", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(this@MainActivity, "没有读取共享内容的权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shouldRequestNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionWasRequested()

    private fun notificationPermissionWasRequested(): Boolean =
        getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

    private fun markNotificationPermissionRequested() {
        getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
            .edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
    }

    private fun openNotificationSettings() {
        val launched = nativeActivityLaunchWithFallback(
            launches = listOf(
                {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            data = "package:$packageName".toUri()
                            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        },
                    )
                },
                {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$packageName".toUri()
                        },
                    )
                },
            ),
            onSuccess = ::markNativeActivityResultLaunched,
        )
        if (!launched) {
            Toast.makeText(this, "系统通知设置页面不可用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDiagnostics() {
        val report = CrashDiagnostics.exportReport(application)
        if (!nativeActivityLaunchSucceeded(
                launch = {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Akashic Mobile ${BuildConfig.VERSION_NAME} 诊断报告")
                                putExtra(Intent.EXTRA_TEXT, report)
                            },
                            "导出诊断报告",
                        ),
                    )
                },
                onSuccess = ::markNativeActivityResultLaunched,
            )
        ) {
            Toast.makeText(this, "没有可导出诊断报告的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun markNativeActivityResultLaunched() {
        nativeResultPending = true
        nativeResultReturned = false
        nativeResultResumeObserved = false
    }

    private fun markNativeActivityResultReturned() {
        if (nativeResultResumeObserved) {
            nativeResultResumeObserved = false
            nativeResultReturned = false
        } else {
            nativeResultReturned = true
        }
    }

    private companion object {
        const val PERMISSION_PREFERENCES = "notification_permission"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "requested"
        const val KEY_SETTINGS_OPEN = "settings_open"
        const val KEY_MOBILE_WEB_UI_EPOCH = "mobile_web_ui_presentation_epoch"
    }
}

internal fun shouldAdvanceMobileWebUiPresentationEpoch(
    leftForeground: Boolean,
    changingConfigurations: Boolean,
    nativeActivityContinuation: Boolean = false,
): Boolean = leftForeground && !changingConfigurations && !nativeActivityContinuation

internal data class NotificationTargetRequest(
    val sessionId: String?,
    val messageId: String?,
) : java.io.Serializable

/** 从 Activity Intent 一次性交接通知目标，避免重建时重复读取 extras。 */
internal fun takeNotificationTarget(intent: Intent): NotificationTargetRequest? {
    val hasSession = intent.hasExtra(MobileConnectionService.EXTRA_SESSION_ID)
    val hasMessage = intent.hasExtra(MobileConnectionService.EXTRA_MESSAGE_ID)
    if (!hasSession && !hasMessage) return null

    val request = NotificationTargetRequest(
        intent.getStringExtra(MobileConnectionService.EXTRA_SESSION_ID),
        intent.getStringExtra(MobileConnectionService.EXTRA_MESSAGE_ID),
    )
    intent.removeExtra(MobileConnectionService.EXTRA_SESSION_ID)
    intent.removeExtra(MobileConnectionService.EXTRA_MESSAGE_ID)
    return request
}

@Composable
private fun IncomingShareNotice(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    Snackbar(
        modifier = modifier,
        action = { TextButton(onClick = onRetry) { Text("重试") } },
        dismissAction = { TextButton(onClick = onDiscard) { Text("放弃") } },
    ) {
        Text(message)
    }
}

@Composable
private fun NotificationPermissionNotice(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Snackbar(
        modifier = modifier,
        action = {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.notification_permission_action))
            }
        },
        dismissAction = { TextButton(onClick = onDismiss) { Text("关闭") } },
    ) {
        Text(stringResource(R.string.notification_permission_notice))
    }
}

@Composable
private fun PairingRecoveryNotice(
    modifier: Modifier = Modifier,
    onRestartPairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    Snackbar(
        modifier = modifier,
        action = { TextButton(onClick = onRestartPairing) { Text("重新配对") } },
        dismissAction = { TextButton(onClick = onDismiss) { Text("关闭") } },
    ) {
        Text("当前界面需要更新连接能力，请重新配对后继续")
    }
}

internal fun notificationPermissionNoticeVisible(
    notificationsEnabled: Boolean,
    permissionRequested: Boolean,
    dismissed: Boolean,
): Boolean = !notificationsEnabled && permissionRequested && !dismissed

internal fun pairingRecoveryNoticeVisible(
    required: Boolean,
    dismissed: Boolean,
    hasProfile: Boolean,
): Boolean = required && hasProfile && !dismissed
