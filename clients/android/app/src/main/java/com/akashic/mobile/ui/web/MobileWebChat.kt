package com.akashic.mobile.ui.web

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.ConsoleMessage
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewClientCompat
import com.akashic.mobile.BuildConfig
import com.akashic.mobile.data.realtime.TurnTraceTracker
import com.akashic.mobile.ui.conversation.ConversationUiState
import com.akashic.mobile.ui.conversation.MessageUi
import com.akashic.mobile.data.realtime.MobileWebUiStore
import com.akashic.mobile.data.realtime.MobileWebUiCoordinator
import com.akashic.mobile.data.realtime.MobileWebUiAttemptLease
import com.akashic.mobile.data.realtime.MobileWebUiResetEvent
import com.akashic.mobile.data.realtime.pluginui.PluginUiAssetStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiWebBridge
import com.akashic.mobile.data.realtime.pluginui.PluginUiWebCatalog
import com.akashic.mobile.data.realtime.pluginui.PluginUiWebResult
import com.akashic.mobile.data.realtime.requireNoDuplicateJsonKeys
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import com.akashic.mobile.nativeActivityLaunchSucceeded
import java.util.IdentityHashMap

private const val MOBILE_WEB_URL = "https://appassets.androidplatform.net/assets/mobile.html"
private const val MOBILE_WEB_PRESENTATION_PREFIX = "https://appassets.androidplatform.net/mobile-webui/"
private const val MOBILE_WEB_EMBEDDED_GENERATION = "embedded"
private const val MOBILE_WEB_EMBEDDED_NONCE = "baseline"
private const val MOBILE_WEB_COMMITTED_NONCE = "committed"
private const val MOBILE_WEB_LOG_TAG = "AkashicMobileWeb"
private const val MOBILE_WEB_TRACE_PREFIX = "[akashic-trace]"
private const val MOBILE_WEB_RENDER_DEADLINE_MILLIS = 10_000L
private const val MOBILE_WEB_RENDERER_RETRY_WINDOW_MILLIS = 5 * 60 * 1_000L
private const val MOBILE_WEB_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
private val MOBILE_WEB_ORIGIN: Uri by lazy(LazyThreadSafetyMode.NONE) {
    Uri.parse("https://appassets.androidplatform.net")
}

private class MobileWebUiPresentationAssetHandler(
    private val store: MobileWebUiStore,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? = store.handlePresentation(path)
}

data class MobileSharedTextDraft(
    val id: String,
    val sessionId: String,
    val text: String,
    val revision: Int,
)

internal data class MobileWebUiAttempt(val serverId: String, val generationId: String, val nonce: String)

/** 仅 [akashic-trace] 前缀的 console 行走 info 级；其余保持原错误级行为。 */
internal fun isMobileWebTraceConsoleLine(message: String): Boolean =
    message.startsWith(MOBILE_WEB_TRACE_PREFIX)
private data class MobileWebUiRendererFailure(
    val serverId: String,
    val generationId: String,
    val atMillis: Long,
)

/** 在 lease 与 bridge admission 边界上调度发送，并保证每个请求只有一次回执。 */
internal fun <T> mobileWebUiDispatchSend(
    requestId: String,
    isLeaseCurrent: () -> Boolean,
    dispatch: (((T) -> Unit), () -> Unit) -> Unit,
    work: (T, (Boolean) -> Unit) -> Unit,
    report: (String, Boolean) -> Unit,
) {
    val reported = AtomicBoolean(false)
    fun reportOnce(accepted: Boolean) {
        // 1. 过期 lease 的异步成功不能越过新 lease；统一降为失败回执
        val result = accepted && isLeaseCurrent()
        if (reported.compareAndSet(false, true)) report(requestId, result)
    }

    // 2. 发送入口本身已过期时，直接显式回执，不执行任何副作用
    if (!isLeaseCurrent()) {
        reportOnce(false)
        return
    }

    // 3. 由 bridge admission 决定执行或拒绝；两条路径都必须收敛到一次回执
    dispatch(
        { context -> work(context, ::reportOnce) },
        { reportOnce(false) },
    )
}

/** 在 lease 与 bridge admission 边界上调度插件查询，并把拒绝转换为明确错误。 */
internal fun <T> mobileWebUiDispatchPluginQuery(
    requestId: String,
    isLeaseCurrent: () -> Boolean,
    dispatch: (((T) -> Unit), () -> Unit) -> Unit,
    work: (T) -> Unit,
    reportError: (String, String) -> Unit,
) {
    fun reject() = reportError(requestId, "插件界面当前不可用")

    // 1. 过期 lease 不得触发插件副作用
    if (!isLeaseCurrent()) {
        reject()
        return
    }

    // 2. admission 拒绝必须返回 WebUI 可消费的错误
    dispatch(
        { context -> work(context) },
        ::reject,
    )
}

internal fun mobileWebUiLeaseGenerationCurrent(
    candidate: Boolean,
    expectedGeneration: String,
    generationRef: String?,
): Boolean = if (!candidate && expectedGeneration == MOBILE_WEB_EMBEDDED_GENERATION) {
    generationRef == null
} else {
    generationRef == expectedGeneration
}

internal fun mobileWebUiLeaseCallbackAllowed(
    callbackView: Any,
    currentView: Any?,
    leaseStateCurrent: Boolean,
): Boolean = callbackView === currentView && leaseStateCurrent

internal fun mobileWebUiOwnerViewCurrent(currentView: Any?, expectedView: Any): Boolean =
    currentView === expectedView

internal fun shouldStartMobileWebUiCandidate(
    sessionStarted: Boolean,
    readyGeneration: String?,
    servingGeneration: String?,
    explicitApply: Boolean = false,
    canReplaceUi: Boolean = false,
): Boolean = readyGeneration != null && readyGeneration != servingGeneration &&
    (!sessionStarted || (explicitApply && canReplaceUi))

internal fun mobileWebUiApplyCanBeConsumed(
    explicitApply: Boolean,
    durableAttemptCreated: Boolean,
): Boolean = explicitApply && durableAttemptCreated

internal fun mobileWebUiCandidateLeaseAllowed(
    sessionStarted: Boolean,
    sessionEpoch: Long,
    presentationEpoch: Long,
): Boolean = sessionStarted && sessionEpoch == presentationEpoch

internal fun mobileWebUiEmbeddedAssetsEnabled(activeGeneration: String?): Boolean =
    activeGeneration == null

internal fun shouldConsumeMobileWebUiResetEvent(
    lastHandledEvent: MobileWebUiResetEvent?,
    event: MobileWebUiResetEvent?,
    currentServerId: String?,
): Boolean = event != null && event.serverId == currentServerId &&
    (lastHandledEvent?.serverId != event.serverId || lastHandledEvent.sequence < event.sequence)

internal fun mobileWebUiHealthReady(
    snapshotDelivered: Boolean,
    visualFrameReady: Boolean,
    rootRendered: Boolean,
    healthyReported: Boolean,
): Boolean = snapshotDelivered && visualFrameReady && rootRendered && healthyReported

internal fun mobileWebUiActivationResultOwnerMatches(
    currentServerId: String?,
    currentAttempt: MobileWebUiAttempt?,
    currentGeneration: String?,
    currentGenerationRef: String?,
    expectedAttempt: MobileWebUiAttempt,
    currentPresentationEpoch: Long,
    expectedPresentationEpoch: Long,
): Boolean = currentServerId == expectedAttempt.serverId &&
    currentAttempt == expectedAttempt &&
    currentGeneration == expectedAttempt.generationId &&
    currentGenerationRef == expectedAttempt.generationId &&
    currentPresentationEpoch == expectedPresentationEpoch

internal fun mobileWebUiCommittedResultOwnerMatches(
    currentServerId: String?,
    currentAttempt: MobileWebUiAttempt?,
    currentGeneration: String?,
    currentGenerationRef: String?,
    expectedServerId: String,
    expectedGeneration: String,
    currentPresentationEpoch: Long,
    expectedPresentationEpoch: Long,
): Boolean = currentServerId == expectedServerId &&
    currentAttempt == null &&
    currentGeneration == expectedGeneration &&
    currentGenerationRef == expectedGeneration &&
    currentPresentationEpoch == expectedPresentationEpoch

internal fun mobileWebUiCommittedFailureOwnerMatches(
    currentServerId: String?,
    currentServingGeneration: String?,
    currentAttempt: MobileWebUiAttempt?,
    currentGeneration: String?,
    currentGenerationRef: String?,
    expectedServerId: String,
    expectedGeneration: String,
    currentPresentationEpoch: Long,
    expectedPresentationEpoch: Long,
): Boolean = currentServingGeneration == expectedGeneration &&
    mobileWebUiCommittedResultOwnerMatches(
        currentServerId = currentServerId,
        currentAttempt = currentAttempt,
        currentGeneration = currentGeneration,
        currentGenerationRef = currentGenerationRef,
        expectedServerId = expectedServerId,
        expectedGeneration = expectedGeneration,
        currentPresentationEpoch = currentPresentationEpoch,
        expectedPresentationEpoch = expectedPresentationEpoch,
    )

internal fun mobileWebUiReadingPositionOffset(value: Long): Int? =
    value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

internal fun mobileWebUiReadThroughTimestamp(value: Long): Long? =
    value.takeIf { it in 0L..MOBILE_WEB_MAX_SAFE_INTEGER }

/** 在 Compose 调用 release 前按 WebView identity 保留对应 owner。 */
internal class MobileWebViewOwnerRegistry<T : Any, O : Any>(
    private val ownerView: (O) -> T,
    private val releaseOwner: (O) -> Unit,
) {
    private val owners = IdentityHashMap<T, O>()
    private var currentView: T? = null

    fun register(owner: O) {
        val view = ownerView(owner)
        check(owners.put(view, owner) == null) { "WebView owner was registered twice" }
        currentView = view
    }

    fun release(view: T) {
        val owner = checkNotNull(owners.remove(view)) { "Unknown WebView release owner" }
        releaseOwner(owner)
        if (currentView === view) currentView = null
    }

    fun currentView(): T? = currentView
}

private data class MobileWebUiOwner(
    val webView: WebView,
    val healthGate: MobileWebUiHealthGate,
    val snapshotPump: MobileSnapshotPump,
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MobileWebChat(
    state: ConversationUiState,
    themeId: String,
    onThemeChange: (String) -> Unit,
    onModelChange: (String, String) -> Unit,
    sharedTextDraft: MobileSharedTextDraft?,
    onCommitSharedText: (String, String, String, String?) -> Unit,
    onSharedTextRejected: (String, String) -> Unit,
    pluginUiCatalog: PluginUiWebCatalog,
    pluginUiResults: Flow<PluginUiWebResult>,
    pluginUiAssetStore: PluginUiAssetStore,
    mobileWebUiStore: MobileWebUiStore? = null,
    mobileWebUiServerId: String? = null,
    mobileWebUiServingGeneration: String? = null,
    mobileWebUiAttemptLease: MobileWebUiAttemptLease? = null,
    mobileWebUiReadyGeneration: String? = null,
    mobileWebUiResetEvent: MobileWebUiResetEvent? = null,
    mobileWebUiPresentationEpoch: Long = 0L,
    mobileWebUiSessionStarted: Boolean = false,
    onMobileWebUiSessionStarted: () -> Unit = {},
    mobileWebUiApplyGeneration: String? = null,
    mobileWebUiCanReplaceUi: Boolean = false,
    onMobileWebUiApplyHandled: (Boolean) -> Unit = {},
    onNativeActivityResultLaunched: () -> Unit = {},
    mobileWebUiCoordinator: MobileWebUiCoordinator? = null,
    onSelectSession: (String) -> Unit,
    onRemoveUnavailableSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onRestartPairing: () -> Unit,
    onReloadFromServer: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onContinueMeteredTransfer: () -> Unit,
    onRetryFailedMessage: (String) -> Unit,
    onSaveComposerDraft: (String, String, String?, Long) -> Unit,
    onSaveReadingPosition: (String, String, Int) -> Unit,
    onMarkSessionReadThrough: (String, Long) -> Unit,
    onNavigationTargetHandled: (String) -> Unit,
    onRetryDownloadedAttachment: (String) -> Unit,
    onTouchDownloadedAttachment: (String) -> Unit,
    onOpenDownloadedAttachment: (String) -> Unit,
    onShareDownloadedAttachment: (String) -> Unit,
    onSaveDownloadedAttachment: (String) -> Unit,
    onDismissError: () -> Unit,
    onSend: (String, String, String?, List<String>, Long, (Boolean) -> Unit) -> Unit,
    onSendCommand: (String) -> Unit,
    onRefreshRuntimeInspection: () -> Unit,
    onOpenRuntimeDocument: (String) -> Unit,
    onOpenRuntimeMcp: (String, String) -> Unit,
    onOpenRuntimeJob: (String) -> Unit,
    onClearRuntimeInspectionDetail: () -> Unit,
    onPluginUiQuery: (
        String,
        String,
        String,
        String?,
        String?,
        String,
        String,
        String,
        String,
        String,
    ) -> Unit,
    onPluginUiOwnerCancelled: (String) -> Unit,
    onPluginUiWebViewDisposed: () -> Unit,
    onStop: () -> Unit,
    onBackAtRoot: () -> Unit,
    modifier: Modifier = Modifier,
    turnTrace: TurnTraceTracker? = null,
) {
    val latestState by rememberUpdatedState(state)
    val latestThemeId by rememberUpdatedState(themeId)
    val latestSharedTextDraft by rememberUpdatedState(sharedTextDraft)
    val latestPluginUiCatalog by rememberUpdatedState(pluginUiCatalog)
    val mediaRegistry = remember { MobileMediaRegistry() }
    val shareScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var snapshotPump by remember { mutableStateOf<MobileSnapshotPump?>(null) }
    var pluginUiBridge by remember { mutableStateOf<PluginUiWebBridge?>(null) }
    var webLoadError by remember { mutableStateOf<String?>(null) }
    var webHistoryActive by remember { mutableStateOf(false) }
    var webReady by remember { mutableStateOf(false) }
    var activeHealthGate by remember { mutableStateOf<MobileWebUiHealthGate?>(null) }
    val webViewOwners = remember {
        MobileWebViewOwnerRegistry<WebView, MobileWebUiOwner>(
            ownerView = MobileWebUiOwner::webView,
            releaseOwner = { owner ->
                // 1. 先停掉与该 view 绑定的异步任务和回调
                owner.healthGate.cancel()
                WebViewCompat.removeWebMessageListener(owner.webView, MOBILE_WEB_TRANSPORT_NAME)
                owner.webView.stopLoading()
                owner.snapshotPump.cancel()

                // 2. 最后销毁已从 Compose 层 release 的 view
                owner.webView.destroy()
            },
        )
    }
    var sessionStarted by remember(mobileWebUiServerId) {
        mutableStateOf(mobileWebUiSessionStarted)
    }
    var sessionEpoch by remember(mobileWebUiServerId) {
        mutableStateOf(mobileWebUiPresentationEpoch)
    }
    val sessionBoundaryAtComposition = !mobileWebUiCandidateLeaseAllowed(
        sessionStarted,
        sessionEpoch,
        mobileWebUiPresentationEpoch,
    )
    val initialAttempt = if (!sessionBoundaryAtComposition) mobileWebUiAttemptLease
        ?.takeIf { it.serverId == mobileWebUiServerId }
        ?.let { MobileWebUiAttempt(it.serverId, it.generationId, it.nonce) }
    else null
    var activeAttempt by remember(mobileWebUiServerId, sessionBoundaryAtComposition) { mutableStateOf(initialAttempt) }
    var activeGeneration by remember(mobileWebUiServerId, sessionBoundaryAtComposition) {
        mutableStateOf(activeAttempt?.generationId ?: mobileWebUiServingGeneration)
    }
    var rendererRebuildToken by remember { mutableStateOf(0) }
    var rendererFailure by remember { mutableStateOf<MobileWebUiRendererFailure?>(null) }
    val generationRef = remember(mobileWebUiServerId, sessionBoundaryAtComposition) {
        AtomicReference<String?>(activeAttempt?.generationId ?: mobileWebUiServingGeneration)
    }
    val admissionRef = remember(mobileWebUiServerId, sessionBoundaryAtComposition) {
        AtomicBoolean(activeAttempt == null)
    }
    val healthCommitStarted = remember { AtomicBoolean(false) }
    val latestMobileWebUiServerId by rememberUpdatedState(mobileWebUiServerId)
    val latestMobileWebUiServingGeneration by rememberUpdatedState(mobileWebUiServingGeneration)
    val latestMobileWebUiPresentationEpoch by rememberUpdatedState(mobileWebUiPresentationEpoch)
    val latestActiveAttempt by rememberUpdatedState(activeAttempt)
    val latestActiveGeneration by rememberUpdatedState(activeGeneration)
    val latestGenerationRef by rememberUpdatedState(generationRef)
    val webViewKey = when {
        activeAttempt != null -> "candidate:${activeAttempt!!.serverId}:${activeAttempt!!.generationId}:${activeAttempt!!.nonce}"
        activeGeneration != null -> "serving:${mobileWebUiServerId}:${activeGeneration}:$rendererRebuildToken"
        else -> "baseline"
    }

    LaunchedEffect(
        mobileWebUiServerId,
        mobileWebUiAttemptLease,
        mobileWebUiServingGeneration,
        sessionStarted,
        sessionEpoch,
        mobileWebUiPresentationEpoch,
    ) {
        val lease = if (!sessionBoundaryAtComposition) mobileWebUiAttemptLease
            ?.takeIf { it.serverId == mobileWebUiServerId }
            ?.let { MobileWebUiAttempt(it.serverId, it.generationId, it.nonce) }
        else null
        if (lease != null) {
            if (activeAttempt != lease) {
                activeHealthGate?.cancel()
                activeAttempt = lease
                activeGeneration = lease.generationId
                generationRef.set(lease.generationId)
                admissionRef.set(false)
                webReady = false
            }
        } else if (activeAttempt != null) {
            activeHealthGate?.cancel()
            activeAttempt = null
            activeGeneration = mobileWebUiServingGeneration
            generationRef.set(mobileWebUiServingGeneration)
            admissionRef.set(true)
            webReady = false
        }
    }

    val callbacks by rememberUpdatedState(
        MobileWebCallbacks(
            onThemeChange = onThemeChange,
            onModelChange = onModelChange,
            onCommitSharedText = onCommitSharedText,
            onSharedTextRejected = onSharedTextRejected,
            onSelectSession = onSelectSession,
            onRemoveUnavailableSession = onRemoveUnavailableSession,
            onNewSession = onNewSession,
            onRestartPairing = onRestartPairing,
            onReloadFromServer = onReloadFromServer,
            onExportDiagnostics = onExportDiagnostics,
            onOpenSettings = onOpenSettings,
            onAttach = onAttach,
            onRemoveAttachment = onRemoveAttachment,
            onRetryAttachment = onRetryAttachment,
            onContinueMeteredTransfer = onContinueMeteredTransfer,
            onRetryFailedMessage = onRetryFailedMessage,
            onSaveComposerDraft = onSaveComposerDraft,
            onSaveReadingPosition = onSaveReadingPosition,
            onMarkSessionReadThrough = onMarkSessionReadThrough,
            onNavigationTargetHandled = onNavigationTargetHandled,
            onRetryDownloadedAttachment = onRetryDownloadedAttachment,
            onTouchDownloadedAttachment = onTouchDownloadedAttachment,
            onOpenDownloadedAttachment = onOpenDownloadedAttachment,
            onShareDownloadedAttachment = onShareDownloadedAttachment,
            onSaveDownloadedAttachment = onSaveDownloadedAttachment,
            onDismissError = onDismissError,
            onSend = onSend,
            onSendCommand = onSendCommand,
            onRefreshRuntimeInspection = onRefreshRuntimeInspection,
            onOpenRuntimeDocument = onOpenRuntimeDocument,
            onOpenRuntimeMcp = onOpenRuntimeMcp,
            onOpenRuntimeJob = onOpenRuntimeJob,
            onClearRuntimeInspectionDetail = onClearRuntimeInspectionDetail,
            onPluginUiQuery = onPluginUiQuery,
            onPluginUiOwnerCancelled = onPluginUiOwnerCancelled,
            onStop = onStop,
        ),
    )

    val activationFinished = remember { AtomicBoolean(false) }
    var lastHandledResetEvent by remember {
        mutableStateOf<MobileWebUiResetEvent?>(null)
    }

    LaunchedEffect(
        mobileWebUiServerId,
        mobileWebUiPresentationEpoch,
        mobileWebUiApplyGeneration,
        mobileWebUiAttemptLease,
        mobileWebUiCanReplaceUi,
    ) {
        val sessionBoundary = !sessionStarted || sessionEpoch != mobileWebUiPresentationEpoch
        if (sessionEpoch != mobileWebUiPresentationEpoch) {
            sessionEpoch = mobileWebUiPresentationEpoch
            sessionStarted = false
        }
        val coordinator = mobileWebUiCoordinator
        val serverId = mobileWebUiServerId
        var readyGeneration = mobileWebUiReadyGeneration
        var servingGeneration = mobileWebUiServingGeneration
        if (coordinator == null || serverId == null) {
            activeAttempt = null
            activeGeneration = null
            generationRef.set(null)
            admissionRef.set(true)
            return@LaunchedEffect
        }
        if (activeAttempt?.serverId != null && activeAttempt?.serverId != serverId) {
            activeHealthGate?.cancel()
            activeAttempt = null
            activeGeneration = servingGeneration
            generationRef.set(servingGeneration)
            admissionRef.set(true)
            webReady = false
        }
        val restoredLease = activeAttempt
        if (restoredLease != null) {
            val attemptCurrent = withContext(Dispatchers.IO) {
                coordinator.isAttemptCurrent(
                    restoredLease.serverId,
                    restoredLease.generationId,
                    restoredLease.nonce,
                )
            }
            if (!attemptCurrent) {
                activeHealthGate?.cancel()
                activeAttempt = null
                activeGeneration = servingGeneration
                generationRef.set(servingGeneration)
                admissionRef.set(true)
                webReady = false
            }
        }
        if (sessionBoundary) {
            val baselineApplied = withContext(Dispatchers.IO) {
                coordinator.applyDesiredBaselineForSession(serverId)
            }
            if (baselineApplied) {
                activeHealthGate?.cancel()
                activeAttempt = null
                activeGeneration = null
                generationRef.set(null)
                admissionRef.set(true)
                webReady = false
                readyGeneration = null
                servingGeneration = null
            }
        }
        val explicitApply = mobileWebUiApplyGeneration != null
        val requestedGeneration = mobileWebUiApplyGeneration ?: readyGeneration
        val canStart = shouldStartMobileWebUiCandidate(
            sessionStarted = sessionStarted,
            readyGeneration = requestedGeneration,
            servingGeneration = servingGeneration,
            explicitApply = explicitApply,
            canReplaceUi = mobileWebUiCanReplaceUi,
        )
        if (!sessionStarted) {
            sessionStarted = true
            onMobileWebUiSessionStarted()
        }
        if (!canStart) {
            if (explicitApply && requestedGeneration == null) onMobileWebUiApplyHandled(false)
            return@LaunchedEffect
        }
        if (activeAttempt != null) {
            if (mobileWebUiApplyCanBeConsumed(explicitApply, durableAttemptCreated = true)) {
                onMobileWebUiApplyHandled(true)
            }
            return@LaunchedEffect
        }
        if (
            requestedGeneration != null &&
            requestedGeneration != servingGeneration
        ) {
            val nonce = withContext(Dispatchers.IO) {
                coordinator.openAttempt(serverId, requestedGeneration)
            }
            if (nonce == null) {
                coordinator.invalidateReady(requestedGeneration)
                if (explicitApply) onMobileWebUiApplyHandled(false)
                return@LaunchedEffect
            }
            activeAttempt = MobileWebUiAttempt(serverId, requestedGeneration, nonce)
            healthCommitStarted.set(false)
            activationFinished.set(false)
            activeGeneration = requestedGeneration
            generationRef.set(requestedGeneration)
            admissionRef.set(false)
            webReady = false
            webLoadError = null
            if (mobileWebUiApplyCanBeConsumed(explicitApply, durableAttemptCreated = true)) {
                onMobileWebUiApplyHandled(true)
            }
        }
    }

    // Reset/revoke 是原生拥有的边界；普通发布变化继续沿用当前界面。
    LaunchedEffect(mobileWebUiServerId, mobileWebUiServingGeneration, mobileWebUiResetEvent) {
        if (!shouldConsumeMobileWebUiResetEvent(
                lastHandledEvent = lastHandledResetEvent,
                event = mobileWebUiResetEvent,
                currentServerId = mobileWebUiServerId,
            )
        ) return@LaunchedEffect
        val event = requireNotNull(mobileWebUiResetEvent)
        if (event.serverId == mobileWebUiServerId) {
            activeHealthGate?.cancel()
            activeAttempt = null
            activationFinished.set(true)
            activeGeneration = null
            generationRef.set(null)
            admissionRef.set(true)
            webReady = false
        }
        lastHandledResetEvent = event
    }

    fun failMobileWebUiActivation(reason: String, ownerView: WebView) {
        val attempt = latestActiveAttempt ?: return
        if (!activationFinished.compareAndSet(false, true)) return
        val expectedPresentationEpoch = latestMobileWebUiPresentationEpoch
        shareScope.launch {
            var fallbackGeneration: String? = null
            var rollbackError: String? = null
            try {
                fallbackGeneration = mobileWebUiCoordinator?.rejectActivation(
                    attempt.serverId,
                    attempt.generationId,
                    attempt.nonce,
                    reason,
                )
            } catch (error: IOException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI activation rollback storage failed: ${error.message}")
                rollbackError = "WebUI 候选界面已隔离，回滚暂不可用：${error.message.orEmpty()}"
            } catch (error: android.database.sqlite.SQLiteException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI activation rollback database failed: ${error.message}")
                rollbackError = "WebUI 候选界面已隔离，回滚暂不可用：本地数据库暂不可用"
            } catch (error: SecurityException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI activation rollback permission failed: ${error.message}")
                rollbackError = "WebUI 候选界面已隔离，回滚暂不可用：缓存无权访问"
            }
            withContext(Dispatchers.Main) {
                if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), ownerView)) {
                    return@withContext
                }
                if (!mobileWebUiActivationResultOwnerMatches(
                        currentServerId = latestMobileWebUiServerId,
                        currentAttempt = latestActiveAttempt,
                        currentGeneration = latestActiveGeneration,
                        currentGenerationRef = latestGenerationRef.get(),
                        expectedAttempt = attempt,
                        currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                        expectedPresentationEpoch = expectedPresentationEpoch,
                    )
                ) return@withContext
                if (rollbackError == null) {
                    admissionRef.set(true)
                    activeAttempt = null
                    activeGeneration = fallbackGeneration
                    generationRef.set(activeGeneration)
                    healthCommitStarted.set(false)
                    webReady = false
                } else {
                    admissionRef.set(false)
                    activeGeneration = attempt.generationId
                    generationRef.set(attempt.generationId)
                    webReady = false
                    webLoadError = rollbackError
                }
            }
        }
    }

    fun recoverCommittedMobileWebUi(
        reason: String,
        expectedServerId: String? = null,
        expectedGeneration: String? = null,
        ownerView: WebView,
    ) {
        if (expectedServerId != null && latestMobileWebUiServerId != expectedServerId) return
        val generation = expectedGeneration ?: latestActiveGeneration ?: return
        val serverId = expectedServerId ?: latestMobileWebUiServerId ?: return
        if (expectedGeneration != null && latestActiveGeneration != expectedGeneration) return
        val expectedPresentationEpoch = latestMobileWebUiPresentationEpoch
        val now = System.currentTimeMillis()
        val previous = rendererFailure?.takeIf {
            it.serverId == serverId && it.generationId == generation
        }
        if (previous == null || now - previous.atMillis > MOBILE_WEB_RENDERER_RETRY_WINDOW_MILLIS) {
            rendererFailure = MobileWebUiRendererFailure(serverId, generation, now)
            rendererRebuildToken += 1
            webReady = false
            return
        }
        rendererFailure = null
        shareScope.launch {
            try {
                val fallback = mobileWebUiCoordinator?.rejectServing(
                    serverId,
                    generation,
                    reason,
                    forceBaseline = false,
                )
                withContext(Dispatchers.Main) {
                    if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), ownerView)) {
                        return@withContext
                    }
                    if (!mobileWebUiCommittedResultOwnerMatches(
                        currentServerId = latestMobileWebUiServerId,
                        currentAttempt = latestActiveAttempt,
                        currentGeneration = latestActiveGeneration,
                        currentGenerationRef = latestGenerationRef.get(),
                        expectedServerId = serverId,
                        expectedGeneration = generation,
                        currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                        expectedPresentationEpoch = expectedPresentationEpoch,
                    )
                    ) return@withContext
                    activeGeneration = fallback
                    generationRef.set(fallback)
                    admissionRef.set(true)
                    webReady = false
                    webLoadError = null
                }
            } catch (error: IOException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI serving recovery storage failed: ${error.message}")
                withContext(Dispatchers.Main) {
                    if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), ownerView)) {
                        return@withContext
                    }
                    if (!mobileWebUiCommittedFailureOwnerMatches(
                        currentServerId = latestMobileWebUiServerId,
                        currentServingGeneration = latestMobileWebUiServingGeneration,
                        currentAttempt = latestActiveAttempt,
                        currentGeneration = latestActiveGeneration,
                        currentGenerationRef = latestGenerationRef.get(),
                        expectedServerId = serverId,
                        expectedGeneration = generation,
                        currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                        expectedPresentationEpoch = expectedPresentationEpoch,
                    )
                    ) return@withContext
                    webReady = false
                    webLoadError = "WebUI 回滚暂不可用：${error.message.orEmpty()}"
                }
            } catch (error: android.database.sqlite.SQLiteException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI serving recovery database failed: ${error.message}")
                withContext(Dispatchers.Main) {
                    if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), ownerView)) {
                        return@withContext
                    }
                    if (!mobileWebUiCommittedFailureOwnerMatches(
                        currentServerId = latestMobileWebUiServerId,
                        currentServingGeneration = latestMobileWebUiServingGeneration,
                        currentAttempt = latestActiveAttempt,
                        currentGeneration = latestActiveGeneration,
                        currentGenerationRef = latestGenerationRef.get(),
                        expectedServerId = serverId,
                        expectedGeneration = generation,
                        currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                        expectedPresentationEpoch = expectedPresentationEpoch,
                    )
                    ) return@withContext
                    webReady = false
                    webLoadError = "WebUI 回滚暂不可用：本地数据库暂不可用"
                }
            } catch (error: SecurityException) {
                Log.e(MOBILE_WEB_LOG_TAG, "WebUI serving recovery permission failed: ${error.message}")
                withContext(Dispatchers.Main) {
                    if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), ownerView)) {
                        return@withContext
                    }
                    if (!mobileWebUiCommittedFailureOwnerMatches(
                        currentServerId = latestMobileWebUiServerId,
                        currentServingGeneration = latestMobileWebUiServingGeneration,
                        currentAttempt = latestActiveAttempt,
                        currentGeneration = latestActiveGeneration,
                        currentGenerationRef = latestGenerationRef.get(),
                        expectedServerId = serverId,
                        expectedGeneration = generation,
                        currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                        expectedPresentationEpoch = expectedPresentationEpoch,
                    )
                    ) return@withContext
                    webReady = false
                    webLoadError = "WebUI 回滚暂不可用：缓存无权访问"
                }
            }
        }
    }

    Box(modifier = modifier) {
        key(webViewKey) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    shareScope.launch { pruneMobileTextShareCache(mobileTextShareDirectory(context)) }
                    configureMobileWebUiServiceWorker()
                    val assetLoaderBuilder = WebViewAssetLoader.Builder()
                    if (mobileWebUiEmbeddedAssetsEnabled(activeGeneration)) {
                        assetLoaderBuilder.addPathHandler(
                            "/assets/",
                            WebViewAssetLoader.AssetsPathHandler(context),
                        )
                    }
                    assetLoaderBuilder
                        .addPathHandler("/media/", mediaRegistry)
                        .addPathHandler("/plugin-ui/", pluginUiAssetStore)
                    if (mobileWebUiStore != null) {
                        assetLoaderBuilder.addPathHandler(
                            "/mobile-webui/",
                            MobileWebUiPresentationAssetHandler(mobileWebUiStore),
                        )
                    }
                    val assetLoader = assetLoaderBuilder.build()
                    val leaseAttempt = activeAttempt
                    val leaseGeneration = activeGeneration ?: MOBILE_WEB_EMBEDDED_GENERATION
                    val leaseNonce = leaseAttempt?.nonce ?: if (activeGeneration == null) {
                        MOBILE_WEB_EMBEDDED_NONCE
                    } else {
                        MOBILE_WEB_COMMITTED_NONCE
                    }
                    val candidate = leaseAttempt != null
                    lateinit var currentWebView: WebView
                    lateinit var healthGate: MobileWebUiHealthGate
                    val newWebView = WebView(context)
                    val newPluginUiBridge = PluginUiWebBridge(newWebView)
                    currentWebView = newWebView
                    fun leaseStillCurrent(callbackView: WebView = currentWebView): Boolean {
                        val leaseStateCurrent = if (candidate) {
                            activeAttempt?.serverId == mobileWebUiServerId &&
                                activeAttempt?.generationId == leaseGeneration &&
                                activeAttempt?.nonce == leaseNonce &&
                                mobileWebUiLeaseGenerationCurrent(
                                    candidate = true,
                                    expectedGeneration = leaseGeneration,
                                    generationRef = generationRef.get(),
                                )
                        } else {
                            activeAttempt == null &&
                                mobileWebUiLeaseGenerationCurrent(
                                    candidate = false,
                                    expectedGeneration = leaseGeneration,
                                    generationRef = generationRef.get(),
                                )
                        }
                        return mobileWebUiLeaseCallbackAllowed(
                            callbackView,
                            currentWebView.takeIf { webView === currentWebView },
                            leaseStateCurrent,
                        )
                    }
                    val bridge = MobileWebBridge(
                        callbackView = newWebView,
                        isLeaseCurrent = { leaseStillCurrent() },
                        dispatchInternal = { work ->
                            if (leaseStillCurrent() &&
                                (admissionRef.get() || generationRef.get() == null)
                            ) {
                                webView?.post {
                                    if (leaseStillCurrent() &&
                                        (admissionRef.get() || generationRef.get() == null)
                                    ) {
                                        work(callbacks)
                                    }
                                }
                            }
                        },
                        dispatchSend = { work, onRejected ->
                            if (leaseStillCurrent() &&
                                (admissionRef.get() || generationRef.get() == null)
                            ) {
                                if (!currentWebView.post {
                                        if (leaseStillCurrent() &&
                                            (admissionRef.get() || generationRef.get() == null)
                                        ) {
                                            work(callbacks)
                                        } else {
                                            onRejected()
                                        }
                                    }
                                ) {
                                    onRejected()
                                }
                            } else {
                                onRejected()
                            }
                        },
                        dispatchPluginUi = { work, onRejected ->
                            if (leaseStillCurrent() &&
                                (admissionRef.get() || generationRef.get() == null)
                            ) {
                                if (!currentWebView.post {
                                        if (leaseStillCurrent() &&
                                            (admissionRef.get() || generationRef.get() == null)
                                        ) {
                                            work(callbacks)
                                        } else {
                                            onRejected()
                                        }
                                    }
                                ) {
                                    onRejected()
                                }
                            } else {
                                onRejected()
                            }
                        },
                        requestSnapshot = {
                            if (leaseStillCurrent()) {
                                currentWebView.post {
                                    if (!leaseStillCurrent()) return@post
                                    val pump = snapshotPump
                                    Log.i(MOBILE_WEB_LOG_TAG, "transport requestSnapshot: pumpReady=${pump != null}")
                                    pump?.request(latestState)
                                    pluginUiBridge?.publishCatalog(latestPluginUiCatalog)
                                }
                            }
                        },
                        reportHealthy = {
                            if (leaseStillCurrent()) {
                                currentWebView.post {
                                    if (!leaseStillCurrent()) return@post
                                    webLoadError = null
                                    webReady = true
                                    healthGate.markHealthy()
                                    currentWebView.postMobileMessage(
                                        "mobile.theme",
                                        JSONObject.quote(latestThemeId),
                                    )
                                }
                            }
                        },
                        copyText = { text ->
                            if (admissionRef.get() || generationRef.get() == null) {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("Akashic message", text),
                                )
                            }
                        },
                        shareText = { requestId, text ->
                            if (admissionRef.get() || generationRef.get() == null) {
                                shareScope.launch {
                                    val prepared = try {
                                        preparePlainTextShare(context, text)
                                    } catch (_: IOException) {
                                        null
                                    }
                                    val launched = withContext(Dispatchers.Main) {
                                        if (prepared === null) {
                                            Toast.makeText(context, "分享文件准备失败，请重试", Toast.LENGTH_SHORT).show()
                                            false
                                        } else {
                                            val launched = launchPlainTextShare(context, prepared)
                                            if (launched) onNativeActivityResultLaunched()
                                            launched
                                        }
                                    }
                                    currentWebView.post {
                                        if (!leaseStillCurrent()) return@post
                                        currentWebView.evaluateJavascript(
                                            "window.AkashicMobile?.receiveShareResult(" +
                                                "${JSONObject.quote(requestId)},$launched)",
                                            null,
                                        )
                                    }
                                }
                            }
                        },
                        performActionHaptic = {
                            if (admissionRef.get() || generationRef.get() == null) {
                                currentWebView.post {
                                    currentWebView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                }
                            }
                        },
                        setWebHistoryActive = { active -> currentWebView.post { webHistoryActive = active } },
                        reportSendResult = { callbackView, requestId, accepted ->
                            val deliver = Runnable {
                                val result = accepted &&
                                    leaseStillCurrent(callbackView) &&
                                    (admissionRef.get() || generationRef.get() == null)
                                callbackView.evaluateJavascript(
                                    "window.AkashicMobile?.receiveSendResult(" +
                                        "${JSONObject.quote(requestId)},$result)",
                                    null,
                                )
                            }
                            if (!callbackView.post(deliver) &&
                                webViewOwners.currentView() === callbackView
                            ) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post(deliver)
                            }
                        },
                        reportPluginUiError = { requestId, message ->
                            newPluginUiBridge.publishResult(
                                PluginUiWebResult(requestId, error = message),
                            )
                        },
                    )
                    healthGate = MobileWebUiHealthGate(
                        webView = newWebView,
                        enabled = candidate,
                        isLeaseCurrent = { leaseStillCurrent() },
                        onHealthy = healthy@{
                            val attempt = leaseAttempt ?: return@healthy
                            if (latestMobileWebUiServerId != attempt.serverId ||
                                latestGenerationRef.get() != attempt.generationId ||
                                latestActiveAttempt?.nonce != attempt.nonce
                            ) return@healthy
                            if (!healthCommitStarted.compareAndSet(false, true)) return@healthy
                            val expectedPresentationEpoch = latestMobileWebUiPresentationEpoch
                            shareScope.launch {
                                try {
                                    val committed = mobileWebUiCoordinator?.commitHealthy(
                                        attempt.serverId,
                                        attempt.generationId,
                                        attempt.nonce,
                                    ) ?: false
                                    withContext(Dispatchers.Main) {
                                        if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), currentWebView)) {
                                            return@withContext
                                        }
                                        if (!mobileWebUiActivationResultOwnerMatches(
                                                currentServerId = latestMobileWebUiServerId,
                                                currentAttempt = latestActiveAttempt,
                                                currentGeneration = latestActiveGeneration,
                                                currentGenerationRef = latestGenerationRef.get(),
                                                expectedAttempt = attempt,
                                                currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                                                expectedPresentationEpoch = expectedPresentationEpoch,
                                            )
                                        ) return@withContext
                                        activationFinished.set(true)
                                        admissionRef.set(true)
                                        activeAttempt = null
                                        if (!committed) {
                                            activeGeneration = latestMobileWebUiServingGeneration
                                            generationRef.set(activeGeneration)
                                            healthCommitStarted.set(false)
                                        }
                                        webLoadError = null
                                    }
                                } catch (_: IOException) {
                                    withContext(Dispatchers.Main) {
                                        if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), currentWebView)) {
                                            return@withContext
                                        }
                                        if (mobileWebUiActivationResultOwnerMatches(
                                                currentServerId = latestMobileWebUiServerId,
                                                currentAttempt = latestActiveAttempt,
                                                currentGeneration = latestActiveGeneration,
                                                currentGenerationRef = latestGenerationRef.get(),
                                                expectedAttempt = attempt,
                                                currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                                                expectedPresentationEpoch = expectedPresentationEpoch,
                                            )
                                        ) {
                                            healthCommitStarted.set(false)
                                            failMobileWebUiActivation("healthy_storage_retry", currentWebView)
                                        }
                                    }
                                } catch (_: android.database.sqlite.SQLiteException) {
                                    withContext(Dispatchers.Main) {
                                        if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), currentWebView)) {
                                            return@withContext
                                        }
                                        if (mobileWebUiActivationResultOwnerMatches(
                                                currentServerId = latestMobileWebUiServerId,
                                                currentAttempt = latestActiveAttempt,
                                                currentGeneration = latestActiveGeneration,
                                                currentGenerationRef = latestGenerationRef.get(),
                                                expectedAttempt = attempt,
                                                currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                                                expectedPresentationEpoch = expectedPresentationEpoch,
                                            )
                                        ) {
                                            healthCommitStarted.set(false)
                                            failMobileWebUiActivation("healthy_database_retry", currentWebView)
                                        }
                                    }
                                } catch (_: SecurityException) {
                                    withContext(Dispatchers.Main) {
                                        if (!mobileWebUiOwnerViewCurrent(webViewOwners.currentView(), currentWebView)) {
                                            return@withContext
                                        }
                                        if (mobileWebUiActivationResultOwnerMatches(
                                                currentServerId = latestMobileWebUiServerId,
                                                currentAttempt = latestActiveAttempt,
                                                currentGeneration = latestActiveGeneration,
                                                currentGenerationRef = latestGenerationRef.get(),
                                                expectedAttempt = attempt,
                                                currentPresentationEpoch = latestMobileWebUiPresentationEpoch,
                                                expectedPresentationEpoch = expectedPresentationEpoch,
                                            )
                                        ) {
                                            healthCommitStarted.set(false)
                                            failMobileWebUiActivation("healthy_permission_retry", currentWebView)
                                        }
                                    }
                                }
                            }
                        },
                        onTimeout = { failMobileWebUiActivation("health_timeout", currentWebView) },
                    )
                    newWebView.apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = true
                        // appassets 使用受控 HTTPS origin；外部请求由 MobileWebClient 拒绝。
                        settings.blockNetworkLoads = false
                        setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                        setBackgroundColor(android.graphics.Color.rgb(243, 247, 252))
                        webViewClient = MobileWebClient(
                            context,
                            assetLoader,
                            onExternalActivityLaunched = onNativeActivityResultLaunched,
                            allowExternalNavigation = mobileExternalNavigationAllowed(candidate),
                            isLeaseCurrent = { callbackView -> leaseStillCurrent(callbackView) },
                            onMainFrameStarted = {
                                val callbackView = this
                                if (leaseStillCurrent(callbackView)) {
                                    callbackView.post {
                                        if (!leaseStillCurrent(callbackView)) return@post
                                        webLoadError = null
                                        webReady = false
                                    }
                                }
                            },
                            onMainFrameError = { message ->
                                val callbackView = this
                                if (leaseStillCurrent(callbackView)) {
                                    callbackView.post {
                                        if (!leaseStillCurrent(callbackView)) return@post
                                        webLoadError = message
                                    }
                                    if (candidate) failMobileWebUiActivation("render_failed", callbackView)
                                    else recoverCommittedMobileWebUi(
                                        "render_failed",
                                        mobileWebUiServerId,
                                        leaseGeneration,
                                        callbackView,
                                    )
                                }
                            },
                            onRendererGone = {
                                if (leaseStillCurrent()) {
                                    if (candidate) {
                                        failMobileWebUiActivation("renderer_terminated", currentWebView)
                                    } else {
                                        recoverCommittedMobileWebUi(
                                            "renderer_repeated",
                                            mobileWebUiServerId,
                                            leaseGeneration,
                                            currentWebView,
                                        )
                                    }
                                }
                            },
                        )
                        WebViewCompat.addWebMessageListener(
                            this,
                            MOBILE_WEB_TRANSPORT_NAME,
                            setOf(MOBILE_WEB_ORIGIN.toString()),
                            MobileWebTransportListener(
                                expectedGeneration = leaseGeneration,
                                expectedNonce = leaseNonce,
                                candidate = candidate,
                                bridge = bridge,
                                isLeaseCurrent = { callbackView -> leaseStillCurrent(callbackView) },
                            ),
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                val text = message.message()
                                if (isMobileWebTraceConsoleLine(text)) {
                                    // 1. 只有 [akashic-trace] 前缀结构行走 info；不解析内容，也不扩大桥权限
                                    Log.i(MOBILE_WEB_LOG_TAG, text)
                                } else {
                                    Log.e(
                                        MOBILE_WEB_LOG_TAG,
                                        "console ${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} $text",
                                    )
                                }
                                return true
                            }
                        }
                        webView = this
                        val newSnapshotPump = MobileSnapshotPump(
                            this,
                            mediaRegistry,
                            onFirstSnapshot = { healthGate.markSnapshot() },
                            turnTrace = turnTrace,
                        )
                        snapshotPump = newSnapshotPump
                        pluginUiBridge = newPluginUiBridge
                        activeHealthGate = healthGate
                        webViewOwners.register(
                            MobileWebUiOwner(
                                webView = this,
                                healthGate = healthGate,
                                snapshotPump = newSnapshotPump,
                            ),
                        )
                        loadUrl(
                            if (activeGeneration == null) {
                                mobileWebUrl(BuildConfig.VERSION_CODE)
                            } else {
                                mobileWebUrl(
                                    BuildConfig.VERSION_CODE,
                                    activeGeneration,
                                    mobileWebUiServerId,
                                    leaseNonce,
                                )
                            },
                        )
                    }
                    newWebView
                },
                onRelease = { releasedView ->
                    webViewOwners.release(releasedView)
                    if (webView === releasedView) {
                        webView = null
                        snapshotPump = null
                        pluginUiBridge = null
                        activeHealthGate = null
                        webHistoryActive = false
                        webReady = false
                    }
                },
            )
        }
        if (webLoadError != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "会话界面加载失败",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 96.dp),
                )
                Text(
                    text = requireNotNull(webLoadError),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(onClick = { webView?.reload() }) { Text("重新加载") }
            }
        }
    }

    LaunchedEffect(webView, pluginUiCatalog) {
        pluginUiBridge?.publishCatalog(pluginUiCatalog)
    }
    LaunchedEffect(webView, pluginUiResults) {
        val bridge = pluginUiBridge ?: return@LaunchedEffect
        pluginUiResults.collect(bridge::publishResult)
    }
    BackHandler {
        val current = webView
        if (current == null) {
            onBackAtRoot()
        } else {
            check(webViewOwners.currentView() === current) { "WebView owner was released" }
            current.evaluateJavascript(
                "window.AkashicMobile?.navigateBack?.() ?? false",
            ) { result ->
                if (!mobileWebBackHandled(result)) current.post(onBackAtRoot)
            }
        }
    }

    SideEffect { snapshotPump?.submit(state) }
    LaunchedEffect(webReady, sharedTextDraft?.id, sharedTextDraft?.revision, webView) {
        val current = webView
        val draft = latestSharedTextDraft
        if (webReady && current != null && draft != null) current.pushSharedTextDraft(draft)
    }
    LaunchedEffect(themeId, webReady, webView) {
        val current = webView
        if (webReady && current != null) {
            current.postMobileMessage("mobile.theme", JSONObject.quote(themeId))
        }
    }
    BackHandler(enabled = webHistoryActive) {
        val current = requireNotNull(webView) { "Web history owner is unavailable" }
        check(webViewOwners.currentView() === current) { "Web history owner was released" }
        check(current.canGoBack()) { "Web history active without a back entry" }
        webHistoryActive = false
        current.goBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            shareScope.cancel()
            onPluginUiWebViewDisposed()
        }
    }
}

private data class MobileWebCallbacks(
    val onThemeChange: (String) -> Unit,
    val onModelChange: (String, String) -> Unit,
    val onCommitSharedText: (String, String, String, String?) -> Unit,
    val onSharedTextRejected: (String, String) -> Unit,
    val onSelectSession: (String) -> Unit,
    val onRemoveUnavailableSession: (String) -> Unit,
    val onNewSession: () -> Unit,
    val onRestartPairing: () -> Unit,
    val onReloadFromServer: () -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAttach: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    val onRetryAttachment: (String) -> Unit,
    val onContinueMeteredTransfer: () -> Unit,
    val onRetryFailedMessage: (String) -> Unit,
    val onSaveComposerDraft: (String, String, String?, Long) -> Unit,
    val onSaveReadingPosition: (String, String, Int) -> Unit,
    val onMarkSessionReadThrough: (String, Long) -> Unit,
    val onNavigationTargetHandled: (String) -> Unit,
    val onRetryDownloadedAttachment: (String) -> Unit,
    val onTouchDownloadedAttachment: (String) -> Unit,
    val onOpenDownloadedAttachment: (String) -> Unit,
    val onShareDownloadedAttachment: (String) -> Unit,
    val onSaveDownloadedAttachment: (String) -> Unit,
    val onDismissError: () -> Unit,
    val onSend: (String, String, String?, List<String>, Long, (Boolean) -> Unit) -> Unit,
    val onSendCommand: (String) -> Unit,
    val onRefreshRuntimeInspection: () -> Unit,
    val onOpenRuntimeDocument: (String) -> Unit,
    val onOpenRuntimeMcp: (String, String) -> Unit,
    val onOpenRuntimeJob: (String) -> Unit,
    val onClearRuntimeInspectionDetail: () -> Unit,
    val onPluginUiQuery: (
        String,
        String,
        String,
        String?,
        String?,
        String,
        String,
        String,
        String,
        String,
    ) -> Unit,
    val onPluginUiOwnerCancelled: (String) -> Unit,
    val onStop: () -> Unit,
)

@Keep
private class MobileWebBridge(
    private val callbackView: WebView,
    private val isLeaseCurrent: () -> Boolean,
    private val dispatchInternal: ((MobileWebCallbacks) -> Unit) -> Unit,
    private val dispatchSend: (((MobileWebCallbacks) -> Unit), () -> Unit) -> Unit,
    private val dispatchPluginUi: (((MobileWebCallbacks) -> Unit), () -> Unit) -> Unit,
    private val requestSnapshot: () -> Unit,
    private val reportHealthy: () -> Unit,
    private val copyText: (String) -> Unit,
    private val shareText: (String, String) -> Unit,
    private val performActionHaptic: () -> Unit,
    private val setWebHistoryActive: (Boolean) -> Unit,
    private val reportSendResult: (WebView, String, Boolean) -> Unit,
    private val reportPluginUiError: (String, String) -> Unit,
) {
    private fun runIfLeaseCurrent(action: () -> Unit) {
        if (isLeaseCurrent()) action()
    }

    private fun dispatch(work: (MobileWebCallbacks) -> Unit) {
        if (isLeaseCurrent()) dispatchInternal(work)
    }

    fun requestSnapshot() = runIfLeaseCurrent(requestSnapshot)

    fun reportHealthy() = runIfLeaseCurrent(reportHealthy)

    fun setTheme(themeId: String) = dispatch { it.onThemeChange(themeId) }

    fun setModelSelection(runtimeId: String, reasoningEffort: String) = dispatch {
        it.onModelChange(runtimeId, reasoningEffort)
    }

    fun commitSharedText(
        draftId: String,
        sessionId: String,
        text: String,
        replyToMessageId: String,
    ) = dispatch {
        it.onCommitSharedText(draftId, sessionId, text, replyToMessageId.ifBlank { null })
    }

    fun rejectSharedText(draftId: String, message: String) = dispatch {
        it.onSharedTextRejected(draftId, message)
    }

    fun selectSession(sessionId: String) = dispatch { it.onSelectSession(sessionId) }

    fun removeUnavailableSession(sessionId: String) = dispatch {
        it.onRemoveUnavailableSession(sessionId)
    }

    fun createSession() = dispatch { it.onNewSession() }

    fun restartPairing() = dispatch { it.onRestartPairing() }

    fun reloadFromServer() = dispatch { it.onReloadFromServer() }

    fun exportDiagnostics() = dispatch { it.onExportDiagnostics() }

    fun openSettings() = dispatch { it.onOpenSettings() }

    fun chooseAttachments() = dispatch { it.onAttach() }

    fun removeAttachment(attachmentId: String) = dispatch { it.onRemoveAttachment(attachmentId) }

    fun retryAttachment(attachmentId: String) = dispatch { it.onRetryAttachment(attachmentId) }

    fun continueMeteredTransfer() = dispatch { it.onContinueMeteredTransfer() }

    fun retryFailedMessage(messageId: String) = dispatch { it.onRetryFailedMessage(messageId) }

    fun saveComposerDraft(
        sessionId: String,
        text: String,
        replyToMessageId: String,
        updatedAt: String,
    ) = dispatch {
        val revision = requireNotNull(updatedAt.toLongOrNull()) { "会话草稿 revision 无效" }
        require(revision in 1..MOBILE_WEB_MAX_SAFE_INTEGER) { "会话草稿 revision 无效" }
        it.onSaveComposerDraft(sessionId, text, replyToMessageId.ifBlank { null }, revision)
    }

    fun saveReadingPosition(sessionId: String, messageId: String, offsetPx: Long) {
        val boundedOffset = mobileWebUiReadingPositionOffset(offsetPx) ?: return
        dispatch { it.onSaveReadingPosition(sessionId, messageId, boundedOffset) }
    }

    fun markSessionReadThrough(sessionId: String, readAtMillis: Long) {
        val boundedTimestamp = mobileWebUiReadThroughTimestamp(readAtMillis) ?: return
        dispatch { it.onMarkSessionReadThrough(sessionId, boundedTimestamp) }
    }

    fun navigationTargetHandled(messageId: String) = dispatch {
        it.onNavigationTargetHandled(messageId)
    }

    fun retryDownloadedAttachment(attachmentId: String) = dispatch {
        it.onRetryDownloadedAttachment(attachmentId)
    }

    fun touchDownloadedAttachment(attachmentId: String) = dispatch {
        it.onTouchDownloadedAttachment(attachmentId)
    }

    fun openDownloadedAttachment(attachmentId: String) = dispatch {
        it.onOpenDownloadedAttachment(attachmentId)
    }

    fun shareDownloadedAttachment(attachmentId: String) = dispatch {
        it.onShareDownloadedAttachment(attachmentId)
    }

    fun saveDownloadedAttachment(attachmentId: String) = dispatch {
        it.onSaveDownloadedAttachment(attachmentId)
    }

    fun setWebHistoryActive(active: Boolean) =
        runIfLeaseCurrent { setWebHistoryActive.invoke(active) }

    fun dismissError() = dispatch { it.onDismissError() }

    fun rejectSendMessage(requestId: String) {
        reportSendResult(callbackView, requestId, false)
    }

    fun sendMessage(
        requestId: String,
        sessionId: String,
        text: String,
        replyToMessageId: String,
        attachmentIdsJson: String,
        sentDraftRevision: String,
    ) {
        mobileWebUiDispatchSend(
            requestId = requestId,
            isLeaseCurrent = isLeaseCurrent,
            dispatch = dispatchSend,
            work = { callbacks, report ->
                val attachmentIds = try {
                    Json.decodeFromString<List<String>>(attachmentIdsJson)
                } catch (_: SerializationException) {
                    report(false)
                    return@mobileWebUiDispatchSend
                }
                if (attachmentIds.distinct().size != attachmentIds.size) {
                    report(false)
                    return@mobileWebUiDispatchSend
                }
                val revision = sentDraftRevision.toLongOrNull()
                if (
                    sessionId.isBlank() ||
                    revision == null ||
                    revision !in 1..MOBILE_WEB_MAX_SAFE_INTEGER
                ) {
                    report(false)
                    return@mobileWebUiDispatchSend
                }
                callbacks.onSend(
                    sessionId,
                    text,
                    replyToMessageId.ifBlank { null },
                    attachmentIds,
                    revision,
                    report,
                )
            },
            report = { id, accepted -> reportSendResult(callbackView, id, accepted) },
        )
    }

    fun copyText(text: String) = runIfLeaseCurrent { copyText.invoke(text) }

    fun shareText(requestId: String, text: String) =
        runIfLeaseCurrent { shareText.invoke(requestId, text) }

    fun performActionHaptic() = runIfLeaseCurrent { performActionHaptic.invoke() }

    fun sendCommand(command: String) = dispatch { it.onSendCommand(command) }

    fun refreshRuntimeInspection() = dispatch { it.onRefreshRuntimeInspection() }

    fun openRuntimeDocument(documentId: String) = dispatch {
        it.onOpenRuntimeDocument(documentId)
    }

    fun openRuntimeMcp(ownerId: String, serverName: String) = dispatch {
        it.onOpenRuntimeMcp(ownerId, serverName)
    }

    fun openRuntimeJob(jobId: String) = dispatch {
        it.onOpenRuntimeJob(jobId)
    }

    fun clearRuntimeInspectionDetail() = dispatch {
        it.onClearRuntimeInspectionDetail()
    }

    fun rejectPluginUiQuery(requestId: String) {
        reportPluginUiError(requestId, "插件界面当前不可用")
    }

    fun queryPluginUi(
        requestId: String,
        ownerId: String,
        slot: String,
        sessionId: String?,
        turnId: String?,
        pluginId: String,
        method: String,
        payloadJson: String,
        cacheMode: String,
        transportMode: String,
    ) {
        mobileWebUiDispatchPluginQuery(
            requestId = requestId,
            isLeaseCurrent = isLeaseCurrent,
            dispatch = dispatchPluginUi,
            work = { callbacks ->
                callbacks.onPluginUiQuery(
                    requestId,
                    ownerId,
                    slot,
                    sessionId,
                    turnId,
                    pluginId,
                    method,
                    payloadJson,
                    cacheMode,
                    transportMode,
                )
            },
            reportError = { id, message -> reportPluginUiError(id, message) },
        )
    }

    fun cancelPluginUiOwner(ownerId: String) = dispatch { it.onPluginUiOwnerCancelled(ownerId) }

    fun stopTurn() = dispatch { it.onStop() }
}

private const val MOBILE_WEB_TRANSPORT_NAME = "AkashicNativeTransport"
private const val MOBILE_WEB_TRANSPORT_MAX_BYTES = 256 * 1024
private val MOBILE_WEB_TRANSPORT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

@Serializable
private data class MobileWebTransportEnvelope(
    val v: Int,
    @kotlinx.serialization.SerialName("generation_id") val generationId: String,
    val nonce: String,
    val method: String,
    val args: JsonArray,
)

private enum class MobileWebTransportArgType {
    STRING,
    NULLABLE_STRING,
    NUMBER,
    BOOLEAN,
}

private val MOBILE_WEB_TRANSPORT_METHODS = mapOf(
    "requestSnapshot" to emptyList(),
    "reportHealthy" to emptyList(),
    "setTheme" to listOf(MobileWebTransportArgType.STRING),
    "setModelSelection" to listOf(
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
    ),
    "commitSharedText" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING),
    "rejectSharedText" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING),
    "selectSession" to listOf(MobileWebTransportArgType.STRING),
    "removeUnavailableSession" to listOf(MobileWebTransportArgType.STRING),
    "createSession" to emptyList(),
    "restartPairing" to emptyList(),
    "reloadFromServer" to emptyList(),
    "exportDiagnostics" to emptyList(),
    "openSettings" to emptyList(),
    "chooseAttachments" to emptyList(),
    "removeAttachment" to listOf(MobileWebTransportArgType.STRING),
    "retryAttachment" to listOf(MobileWebTransportArgType.STRING),
    "continueMeteredTransfer" to emptyList(),
    "retryFailedMessage" to listOf(MobileWebTransportArgType.STRING),
    "saveComposerDraft" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING),
    "saveReadingPosition" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING, MobileWebTransportArgType.NUMBER),
    "markSessionReadThrough" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.NUMBER),
    "navigationTargetHandled" to listOf(MobileWebTransportArgType.STRING),
    "retryDownloadedAttachment" to listOf(MobileWebTransportArgType.STRING),
    "touchDownloadedAttachment" to listOf(MobileWebTransportArgType.STRING),
    "openDownloadedAttachment" to listOf(MobileWebTransportArgType.STRING),
    "shareDownloadedAttachment" to listOf(MobileWebTransportArgType.STRING),
    "saveDownloadedAttachment" to listOf(MobileWebTransportArgType.STRING),
    "setWebHistoryActive" to listOf(MobileWebTransportArgType.BOOLEAN),
    "dismissError" to emptyList(),
    "sendMessage" to listOf(
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
    ),
    "copyText" to listOf(MobileWebTransportArgType.STRING),
    "shareText" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING),
    "performActionHaptic" to emptyList(),
    "sendCommand" to listOf(MobileWebTransportArgType.STRING),
    "refreshRuntimeInspection" to emptyList(),
    "openRuntimeDocument" to listOf(MobileWebTransportArgType.STRING),
    "openRuntimeMcp" to listOf(MobileWebTransportArgType.STRING, MobileWebTransportArgType.STRING),
    "openRuntimeJob" to listOf(MobileWebTransportArgType.STRING),
    "clearRuntimeInspectionDetail" to emptyList(),
    "queryPluginUi" to listOf(
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.NULLABLE_STRING,
        MobileWebTransportArgType.NULLABLE_STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
        MobileWebTransportArgType.STRING,
    ),
    "cancelPluginUiOwner" to listOf(MobileWebTransportArgType.STRING),
    "stopTurn" to emptyList(),
)

private class MobileWebTransportListener(
    private val expectedGeneration: String,
    private val expectedNonce: String,
    private val candidate: Boolean,
    private val bridge: MobileWebBridge,
    private val isLeaseCurrent: (WebView) -> Boolean,
) : WebViewCompat.WebMessageListener {
    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || sourceOrigin != MOBILE_WEB_ORIGIN || message.type != WebMessageCompat.TYPE_STRING) {
            return
        }
        val data = message.data ?: return
        if (!mobileWebUiTransportEnvelopeWithinLimit(data)) {
            Log.w(MOBILE_WEB_LOG_TAG, "discard oversized native transport envelope")
            return
        }
        val envelope = try {
            requireNoDuplicateJsonKeys(data)
            MOBILE_WEB_TRANSPORT_JSON.decodeFromString<MobileWebTransportEnvelope>(data)
        } catch (_: SerializationException) {
            Log.w(MOBILE_WEB_LOG_TAG, "discard malformed native transport envelope")
            return
        } catch (_: IllegalArgumentException) {
            Log.w(MOBILE_WEB_LOG_TAG, "discard invalid native transport envelope")
            return
        }
        if (envelope.v != 1 || envelope.generationId != expectedGeneration || envelope.nonce != expectedNonce) {
            return
        }
        if (!mobileWebUiTransportMethodAllowed(envelope.method, candidate) &&
            envelope.method !in setOf("sendMessage", "queryPluginUi")
        ) {
            return
        }
        if (!validateMobileWebTransportArgs(envelope.method, envelope.args)) {
            if (envelope.method == "sendMessage" || envelope.method == "queryPluginUi") {
                val requestId = (envelope.args.firstOrNull() as? JsonPrimitive)?.contentOrNull
                if (requestId != null) {
                    if (envelope.method == "sendMessage") bridge.rejectSendMessage(requestId)
                    else bridge.rejectPluginUiQuery(requestId)
                }
            }
            return
        }
        if (!isLeaseCurrent(view) && envelope.method !in setOf("sendMessage", "queryPluginUi")) return
        try {
            dispatchMobileWebTransport(view, bridge, envelope)
        } catch (_: IllegalArgumentException) {
            Log.w(MOBILE_WEB_LOG_TAG, "discard invalid native transport arguments: ${envelope.method}")
        } catch (_: SerializationException) {
            Log.w(MOBILE_WEB_LOG_TAG, "discard invalid native transport payload: ${envelope.method}")
        }
    }
}

internal fun mobileWebUiTransportEnvelopeWithinLimit(data: String): Boolean =
    data.toByteArray(Charsets.UTF_8).size <= MOBILE_WEB_TRANSPORT_MAX_BYTES

internal fun mobileWebUiTransportMethodAllowed(method: String, candidate: Boolean): Boolean =
    method in MOBILE_WEB_TRANSPORT_METHODS &&
        (!candidate || method in setOf("requestSnapshot", "reportHealthy"))

internal fun validateMobileWebTransportArgs(method: String, args: JsonArray): Boolean {
    val schema = MOBILE_WEB_TRANSPORT_METHODS[method] ?: return false
    if (schema.size != args.size) return false
    return schema.indices.all { index ->
        val value = args[index]
        when (schema[index]) {
            MobileWebTransportArgType.STRING -> value is JsonPrimitive && value.isString
            MobileWebTransportArgType.NULLABLE_STRING ->
                value is kotlinx.serialization.json.JsonNull || value is JsonPrimitive && value.isString
            MobileWebTransportArgType.NUMBER ->
                value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
            MobileWebTransportArgType.BOOLEAN ->
                value is JsonPrimitive && !value.isString && value.content in setOf("true", "false")
        }
    }
}

private fun dispatchMobileWebTransport(
    view: WebView,
    bridge: MobileWebBridge,
    envelope: MobileWebTransportEnvelope,
) {
    val args = envelope.args
    fun string(index: Int): String = (args[index] as JsonPrimitive).content
    fun nullableString(index: Int): String? = (args[index] as? JsonPrimitive)?.contentOrNull
    fun number(index: Int): Long = (args[index] as JsonPrimitive).content.toLong()
    fun boolean(index: Int): Boolean = (args[index] as JsonPrimitive).content == "true"
    when (envelope.method) {
        "requestSnapshot" -> bridge.requestSnapshot()
        "reportHealthy" -> bridge.reportHealthy()
        "setTheme" -> bridge.setTheme(string(0))
        "setModelSelection" -> bridge.setModelSelection(string(0), string(1))
        "commitSharedText" -> bridge.commitSharedText(string(0), string(1), string(2), string(3))
        "rejectSharedText" -> bridge.rejectSharedText(string(0), string(1))
        "selectSession" -> bridge.selectSession(string(0))
        "removeUnavailableSession" -> bridge.removeUnavailableSession(string(0))
        "createSession" -> bridge.createSession()
        "restartPairing" -> bridge.restartPairing()
        "reloadFromServer" -> bridge.reloadFromServer()
        "exportDiagnostics" -> bridge.exportDiagnostics()
        "openSettings" -> bridge.openSettings()
        "chooseAttachments" -> bridge.chooseAttachments()
        "removeAttachment" -> bridge.removeAttachment(string(0))
        "retryAttachment" -> bridge.retryAttachment(string(0))
        "continueMeteredTransfer" -> bridge.continueMeteredTransfer()
        "retryFailedMessage" -> bridge.retryFailedMessage(string(0))
        "saveComposerDraft" -> bridge.saveComposerDraft(string(0), string(1), string(2), string(3))
        "saveReadingPosition" -> bridge.saveReadingPosition(string(0), string(1), number(2))
        "markSessionReadThrough" -> bridge.markSessionReadThrough(string(0), number(1))
        "navigationTargetHandled" -> bridge.navigationTargetHandled(string(0))
        "retryDownloadedAttachment" -> bridge.retryDownloadedAttachment(string(0))
        "touchDownloadedAttachment" -> bridge.touchDownloadedAttachment(string(0))
        "openDownloadedAttachment" -> bridge.openDownloadedAttachment(string(0))
        "shareDownloadedAttachment" -> bridge.shareDownloadedAttachment(string(0))
        "saveDownloadedAttachment" -> bridge.saveDownloadedAttachment(string(0))
        "setWebHistoryActive" -> bridge.setWebHistoryActive(boolean(0))
        "dismissError" -> bridge.dismissError()
        "sendMessage" -> bridge.sendMessage(string(0), string(1), string(2), string(3), string(4), string(5))
        "copyText" -> bridge.copyText(string(0))
        "shareText" -> bridge.shareText(string(0), string(1))
        "performActionHaptic" -> bridge.performActionHaptic()
        "sendCommand" -> bridge.sendCommand(string(0))
        "refreshRuntimeInspection" -> bridge.refreshRuntimeInspection()
        "openRuntimeDocument" -> bridge.openRuntimeDocument(string(0))
        "openRuntimeMcp" -> bridge.openRuntimeMcp(string(0), string(1))
        "openRuntimeJob" -> bridge.openRuntimeJob(string(0))
        "clearRuntimeInspectionDetail" -> bridge.clearRuntimeInspectionDetail()
        "queryPluginUi" -> bridge.queryPluginUi(
            string(0), string(1), string(2), nullableString(3), nullableString(4),
            string(5), string(6), string(7), string(8), string(9),
        )
        "cancelPluginUiOwner" -> bridge.cancelPluginUiOwner(string(0))
        "stopTurn" -> bridge.stopTurn()
    }
}

private class MobileWebUiHealthGate(
    private val webView: WebView,
    private val enabled: Boolean,
    private val isLeaseCurrent: () -> Boolean,
    private val onHealthy: () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private val snapshotDelivered = AtomicBoolean(false)
    private val visualFrameReady = AtomicBoolean(false)
    private val rootRendered = AtomicBoolean(false)
    private val rootCheckStarted = AtomicBoolean(false)
    private val healthyReported = AtomicBoolean(false)
    private val callbackPosted = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    private val timeout = Runnable {
        if (enabled && completed.compareAndSet(false, true)) onTimeout()
    }

    init {
        if (enabled) webView.postDelayed(timeout, MOBILE_WEB_RENDER_DEADLINE_MILLIS)
    }

    fun markSnapshot() {
        if (!enabled || completed.get() || !isLeaseCurrent() ||
            !snapshotDelivered.compareAndSet(false, true)
        ) return
        if (callbackPosted.compareAndSet(false, true)) {
            WebViewCompat.postVisualStateCallback(
                webView,
                System.nanoTime(),
                object : WebViewCompat.VisualStateCallback {
                    override fun onComplete(requestId: Long) {
                        visualFrameReady.set(true)
                        maybeCommit()
                    }
                },
            )
        }
        maybeCommit()
    }

    fun markHealthy() {
        if (!enabled || completed.get() || !isLeaseCurrent()) return
        healthyReported.set(true)
        if (rootCheckStarted.compareAndSet(false, true)) {
            webView.evaluateJavascript(
                "document.getElementById('root')?.childElementCount > 0",
            ) { rendered ->
                if (!isLeaseCurrent() || completed.get()) return@evaluateJavascript
                rootRendered.set(rendered == "true")
                maybeCommit()
            }
        }
        maybeCommit()
    }

    private fun maybeCommit() {
        if (
            isLeaseCurrent() &&
            mobileWebUiHealthReady(
                snapshotDelivered = snapshotDelivered.get(),
                visualFrameReady = visualFrameReady.get(),
                rootRendered = rootRendered.get(),
                healthyReported = healthyReported.get(),
            ) &&
                completed.compareAndSet(false, true)
        ) {
            webView.removeCallbacks(timeout)
            onHealthy()
        }
    }

    fun cancel() {
        completed.set(true)
        webView.removeCallbacks(timeout)
    }
}

private fun configureMobileWebUiServiceWorker() {
    val controller = ServiceWorkerControllerCompat.getInstance()
    controller.serviceWorkerWebSettings.apply {
        setBlockNetworkLoads(true)
        setAllowContentAccess(false)
        setAllowFileAccess(false)
        setCacheMode(WebSettings.LOAD_NO_CACHE)
    }
    controller.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
            WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Service Worker blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
    })
}

private class MobileWebClient(
    private val context: Context,
    private val assetLoader: WebViewAssetLoader,
    private val onExternalActivityLaunched: () -> Unit,
    private val allowExternalNavigation: Boolean,
    private val isLeaseCurrent: (WebView) -> Boolean,
    private val onMainFrameStarted: WebView.() -> Unit,
    private val onMainFrameError: WebView.(String) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClientCompat() {
    private var pageGeneration = 0L

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        Log.i(MOBILE_WEB_LOG_TAG, "page started: $url")
        if (!isLeaseCurrent(view)) return
        pageGeneration += 1
        view.onMainFrameStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        Log.i(MOBILE_WEB_LOG_TAG, "page finished: $url")
        if (!isMobileWebUrl(url) || !isLeaseCurrent(view)) return
        val generation = pageGeneration
        view.postDelayed({
            if (generation != pageGeneration || !isLeaseCurrent(view)) return@postDelayed
            view.evaluateJavascript(
                """document.getElementById('root')?.childElementCount > 0""",
            ) { rendered ->
                Log.i(MOBILE_WEB_LOG_TAG, "page deadline rendered: $rendered")
                if (rendered != "true") view.onMainFrameError("会话脚本没有生成界面")
            }
        }, MOBILE_WEB_RENDER_DEADLINE_MILLIS)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        Log.e(MOBILE_WEB_LOG_TAG, "resource error: ${request.url} ${error.errorCode} ${error.description}")
        if (isLeaseCurrent(view) && (request.isForMainFrame || request.isCriticalAppAsset())) {
            view.onMainFrameError("资源加载失败: ${request.url.path} (${error.description})")
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (isLeaseCurrent(view) && (request.isForMainFrame || request.isCriticalAppAsset())) {
            Log.e(
                MOBILE_WEB_LOG_TAG,
                "critical HTTP error: ${request.url} ${errorResponse.statusCode} ${errorResponse.reasonPhrase}",
            )
            view.onMainFrameError(
                "资源加载失败: ${request.url.path} (HTTP ${errorResponse.statusCode})",
            )
        }
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        Log.e(MOBILE_WEB_LOG_TAG, "renderer terminated didCrash=${detail.didCrash()}")
        if (isLeaseCurrent(view)) onRendererGone()
        return true
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.url.host == "appassets.androidplatform.net") {
            return assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse(404, "Not Found")
        }
        return blockedResponse(403, "Blocked")
    }

    private fun blockedResponse(statusCode: Int, reason: String) =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            statusCode,
            reason,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun WebResourceRequest.isCriticalAppAsset(): Boolean =
        url.host == "appassets.androidplatform.net" &&
            (url.path == "/assets/mobile.html" || url.path?.startsWith("/assets/mobile-") == true ||
                url.path?.startsWith("/mobile-webui/") == true)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!isLeaseCurrent(view)) return true
        return when (mobileNavigationAction(request.url.toString(), request.isForMainFrame)) {
            MobileNavigationAction.ALLOW_INTERNAL -> false
            MobileNavigationAction.BLOCK -> true
            MobileNavigationAction.OPEN_EXTERNAL -> {
                if (!allowExternalNavigation) return true
                val launched = nativeActivityLaunchSucceeded(
                    launch = { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) },
                    onSuccess = onExternalActivityLaunched,
                )
                if (!launched) {
                    Toast.makeText(context, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
    }
}

internal enum class MobileNavigationAction { ALLOW_INTERNAL, OPEN_EXTERNAL, BLOCK }

internal fun mobileExternalNavigationAllowed(candidate: Boolean): Boolean = !candidate

internal fun mobileWebBackHandled(javascriptResult: String?): Boolean = javascriptResult == "true"

internal fun mobileWebUrl(
    versionCode: Int,
    generationId: String? = null,
    serverId: String? = null,
    leaseNonce: String? = null,
): String {
    require(versionCode > 0) { "应用版本号必须为正数" }
    if (generationId == null) {
        require(serverId == null && leaseNonce == null) { "baseline WebUI 不允许远端 lease" }
        return "$MOBILE_WEB_URL?appVersion=$versionCode&generation_id=$MOBILE_WEB_EMBEDDED_GENERATION&nonce=$MOBILE_WEB_EMBEDDED_NONCE"
    }
    require(serverId != null && serverId.isNotBlank()) { "远端 WebUI 缺少 server_id" }
    val generation = generationId.let {
        require(it.matches(Regex("^[0-9a-f]{64}$"))) { "WebUI generation_id 无效" }
        it
    }
    val serverHash = serverId.sha256Hex()
    val nonce = leaseNonce ?: MOBILE_WEB_COMMITTED_NONCE
    require(nonce.matches(Regex("^[A-Za-z0-9._:-]{1,128}$"))) { "WebUI lease nonce 无效" }
    return "$MOBILE_WEB_PRESENTATION_PREFIX$serverHash/$generation/mobile.html?" +
        "appVersion=$versionCode&generation_id=$generation&nonce=$nonce"
}

private fun isMobileWebUrl(url: String): Boolean {
    val prefix = "https://appassets.androidplatform.net"
    if (!url.startsWith(prefix)) return false
    val path = url.removePrefix(prefix).substringBefore('?').substringBefore('#')
    if (path == "/assets/mobile.html") return true
    return path.matches(
        Regex("^/mobile-webui/[0-9a-f]{64}/[0-9a-f]{64}/mobile\\.html$"),
    )
}

private fun String.sha256Hex(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 只允许应用主页面留在 WebView，普通网页交给系统浏览器。 */
internal fun mobileNavigationAction(url: String, isMainFrame: Boolean): MobileNavigationAction {
    if (!isMainFrame) return MobileNavigationAction.BLOCK
    if (isMobileWebUrl(url)) return MobileNavigationAction.ALLOW_INTERNAL
    if (url.startsWith("https://appassets.androidplatform.net/")) {
        return MobileNavigationAction.BLOCK
    }
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    return if (scheme in setOf("https", "http")) {
        MobileNavigationAction.OPEN_EXTERNAL
    } else {
        MobileNavigationAction.BLOCK
    }
}

internal data class MobileMediaResource(val path: String, val contentType: String)

internal class MobileMediaResourceIndex {
    private val resources = AtomicReference<Map<String, MobileMediaResource>>(emptyMap())

    fun replace(next: Map<String, MobileMediaResource>) {
        resources.set(next.toMap())
    }

    fun resolve(path: String): MobileMediaResource? =
        resources.get()[path]?.takeIf { File(it.path).isFile }
}

internal fun mobileMediaResourceKey(attachmentId: String, filename: String): String {
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (MEDIA_EXTENSION_PATTERN.matches(extension)) "$attachmentId.$extension" else attachmentId
}

internal fun mobileMediaResourceUrl(attachmentId: String, filename: String): String =
    "https://appassets.androidplatform.net/media/${mobileMediaResourceKey(attachmentId, filename)}"

/** 把协议 Content-Type 规范为 WebResourceResponse 要求的不带参数 MIME。 */
internal fun mobileMediaMimeType(contentType: String): String {
    val mimeType = contentType.substringBefore(';').trim().lowercase()
    require(MIME_TYPE_PATTERN.matches(mimeType)) { "附件 MIME type 无效: $contentType" }
    return mimeType
}

private class MobileMediaRegistry : WebViewAssetLoader.PathHandler {
    private val index = MobileMediaResourceIndex()

    fun replace(next: Map<String, MobileMediaResource>) {
        index.replace(next)
    }

    override fun handle(path: String): WebResourceResponse? {
        val resource = index.resolve(path) ?: return null
        val file = File(resource.path)
        val stream = try {
            file.inputStream()
        } catch (_: FileNotFoundException) {
            return null
        }
        return WebResourceResponse(
            resource.contentType,
            null,
            200,
            "OK",
            mapOf(
                "Cache-Control" to "no-store",
                "Content-Length" to file.length().toString(),
                "X-Content-Type-Options" to "nosniff",
            ),
            stream,
        )
    }
}

private fun ConversationUiState.mediaResources(): Map<String, MobileMediaResource> =
    messages.asSequence()
        .flatMap { message ->
            when (message) {
                is MessageUi.User -> message.attachments.asSequence()
                is MessageUi.AssistantTurn -> message.attachments.asSequence()
            }
        }
        .filter { it.cachePath.isNotBlank() }
        .associate {
            mobileMediaResourceKey(it.id, it.filename) to
                MobileMediaResource(it.cachePath, mobileMediaMimeType(it.contentType))
        }

private val MIME_TYPE_PATTERN = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$")
private val MEDIA_EXTENSION_PATTERN = Regex("^[a-z0-9]{1,16}$")

private fun WebView.pushSnapshot(snapshotJson: String) {
    postMobileMessage("mobile.snapshot", snapshotJson)
}

private fun WebView.pushStreamPatch(patchJson: String) {
    postMobileMessage("mobile.stream-patch", patchJson)
}

private fun WebView.pushStatePatch(patchJson: String) {
    postMobileMessage("mobile.state-patch", patchJson)
}

/** Send native state through the asynchronous origin-bound message channel. */
private fun WebView.postMobileMessage(type: String, payloadJson: String) {
    val envelope = """{"type":${JSONObject.quote(type)},"payload":$payloadJson}"""
    postWebMessage(WebMessage(envelope), MOBILE_WEB_ORIGIN)
}

private fun WebView.pushSharedTextDraft(draft: MobileSharedTextDraft) {
    evaluateJavascript(
        "window.AkashicMobile?.receiveSharedText(" +
            "${JSONObject.quote(draft.id)},${JSONObject.quote(draft.sessionId)}," +
            "${JSONObject.quote(draft.text)})",
        null,
    )
}

internal const val ASSISTANT_TURN_PREFIX = "assistant:"

private class MobileSnapshotPump(
    private val webView: WebView,
    private val mediaRegistry: MobileMediaRegistry,
    private val onFirstSnapshot: () -> Unit,
    private val turnTrace: TurnTraceTracker? = null,
) {
    private val json = Json { explicitNulls = false }
    private val states = Channel<ConversationUiState>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val forceFullSnapshot = AtomicBoolean()
    private var deliveredState: ConversationUiState? = null

    init {
        scope.launch {
            for (first in states) {
                var latest = first
                while (true) {
                    latest = states.tryReceive().getOrNull() ?: break
                }
                val forceSnapshot = forceFullSnapshot.getAndSet(false)
                if (!forceSnapshot && deliveredState == latest) continue
                val streamPatch = deliveredState
                    ?.takeUnless { forceSnapshot }
                    ?.let(latest::toMobileWebStreamPatch)
                val statePatch = deliveredState
                    ?.takeUnless { forceSnapshot || streamPatch != null }
                    ?.let(latest::toMobileWebStatePatch)
                val terminalTransition = deliveredState
                    ?.takeIf { streamPatch == null }
                    ?.let(latest::terminalTransitionFrom)
                val payload = when {
                    streamPatch != null -> json.encodeToString(streamPatch)
                    statePatch != null -> json.encodeToString(statePatch)
                    else -> {
                        mediaRegistry.replace(latest.mediaResources())
                        json.encodeToString(latest.toMobileWebSnapshot())
                    }
                }
                withContext(Dispatchers.Main.immediate) {
                    when {
                        streamPatch != null -> {
                            webView.pushStreamPatch(payload)
                            traceStreamPatch(streamPatch)
                        }
                        statePatch != null -> webView.pushStatePatch(payload)
                        else -> {
                            webView.pushSnapshot(payload)
                            if (deliveredState == null) onFirstSnapshot()
                        }
                    }
                    terminalTransition?.let(::traceTerminalTransition)
                }
                deliveredState = latest
            }
        }
    }

    fun submit(state: ConversationUiState) {
        states.trySend(state).getOrThrow()
    }

    /** 记录已跨桥投递 patch 的 stage；只描述投递，不虚报 visible/paint。 */
    private fun traceStreamPatch(patch: MobileWebStreamPatch) {
        val trace = turnTrace ?: return
        val turnId = patch.messageId.removePrefix(ASSISTANT_TURN_PREFIX)
        if (turnId == patch.messageId) return
        trace.onWebViewPatch(
            sessionId = patch.selectedSessionId,
            turnId = turnId,
            clientMessageId = patch.clientMessageId,
            thinkingDelta = patch.thinkingAppend != null,
            answerDelta = patch.contentAppend != null,
            terminal = patch.message?.streaming == false,
        )
    }

    private fun traceTerminalTransition(transition: MobileWebTerminalTransition) {
        turnTrace?.onWebViewPatch(
            sessionId = transition.sessionId,
            turnId = transition.turnId,
            clientMessageId = transition.clientMessageId,
            thinkingDelta = false,
            answerDelta = false,
            terminal = true,
        )
    }

    fun request(state: ConversationUiState) {
        forceFullSnapshot.set(true)
        submit(state)
    }

    fun cancel() {
        states.close()
        scope.cancel()
    }
}
