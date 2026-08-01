package com.akashic.mobile

import android.app.Application
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.akashic.mobile.data.local.AppDatabase
import com.akashic.mobile.data.local.AppPreferences
import com.akashic.mobile.data.local.AttachmentDraftStore
import com.akashic.mobile.data.local.LocalDeliveryStore
import com.akashic.mobile.data.local.MediaCacheStore
import com.akashic.mobile.data.local.IncomingShareStore
import com.akashic.mobile.data.realtime.DeviceKeyStore
import com.akashic.mobile.data.realtime.RealtimeSession
import com.akashic.mobile.data.realtime.TransferNetworkMonitor
import com.akashic.mobile.data.realtime.pluginui.PluginUiAssetStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiCatalogStore
import com.akashic.mobile.data.realtime.pluginui.PluginUiResultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.Executors

class App : Application() {
    lateinit var container: AppContainer
        private set
    val visibility = AppVisibilityTracker()

    override fun onCreate() {
        super.onCreate()
        CrashDiagnostics.install(this)
        startWebViewInBackground()
        registerActivityLifecycleCallbacks(visibility)
        container = AppContainer(this)
    }

    /** Move WebView engine startup off the first interactive UI frame. */
    private fun startWebViewInBackground() {
        val executor = Executors.newSingleThreadExecutor { work ->
            Thread(work, "Akashic-WebView-Startup").apply { isDaemon = true }
        }
        val config = WebViewStartUpConfig.Builder(executor).build()
        WebViewCompat.startUpWebView(
            this,
            config,
            object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                override fun onResult(result: WebViewStartUpResult) {
                    Log.i(
                        WEBVIEW_STARTUP_LOG_TAG,
                        "ready uiTotalMs=${result.totalTimeInUiThreadMillis} " +
                            "uiMaxTaskMs=${result.maxTimePerTaskInUiThreadMillis} " +
                            "blockingSites=${result.uiThreadBlockingStartUpLocations?.size ?: 0}",
                    )
                    executor.shutdown()
                }

                override fun onError(error: WebViewStartupException) {
                    Log.e(WEBVIEW_STARTUP_LOG_TAG, "background startup failed", error)
                    executor.shutdown()
                }
            },
        )
    }

    private companion object {
        const val WEBVIEW_STARTUP_LOG_TAG = "AkashicWebViewStartup"
    }
}

class AppContainer(application: Application) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = AppDatabase.create(application)
    private val mediaCacheRoot = application.filesDir.resolve("received-attachments")
    val mediaCacheStore = MediaCacheStore(mediaCacheRoot, database.mediaAttachments())
    val deliveryStore = LocalDeliveryStore(database, mediaCacheStore)
    val preferences = AppPreferences(application)
    val attachmentDraftStore = AttachmentDraftStore(
        contentResolver = application.contentResolver,
        root = application.filesDir.resolve("pending-attachments"),
        dao = database.attachmentTransfers(),
    )
    val incomingShareStore = IncomingShareStore(
        context = application,
        root = application.filesDir.resolve("incoming-shares"),
    )
    val deviceKeyStore = DeviceKeyStore()
    val transferNetwork = TransferNetworkMonitor(application)
    val pluginUiAssetStore = PluginUiAssetStore(
        application.filesDir.resolve("plugin-ui-cache/v2"),
    )
    private val pluginUiResultStore = PluginUiResultStore(
        application.filesDir.resolve("plugin-ui-cache/v3-results"),
    )
    private val pluginUiCatalogStore = PluginUiCatalogStore(
        application.filesDir.resolve("plugin-ui-cache/v3-catalogs"),
    )
    val realtimeSession = RealtimeSession(
        database = database,
        deliveryStore = deliveryStore,
        attachmentDrafts = attachmentDraftStore,
        mediaCache = mediaCacheStore,
        preferences = preferences,
        deviceKeys = deviceKeyStore,
        transferNetwork = transferNetwork.state,
        pluginUiAssetStore = pluginUiAssetStore,
        pluginUiCatalogStore = pluginUiCatalogStore,
        pluginUiResultStore = pluginUiResultStore,
        scope = applicationScope,
        allowInsecureTransport = BuildConfig.ALLOW_INSECURE_WS,
        onRuntimeError = { context, error ->
            CrashDiagnostics.recordRuntimeError(application, "RealtimeSession", context, error)
        },
    )
}
