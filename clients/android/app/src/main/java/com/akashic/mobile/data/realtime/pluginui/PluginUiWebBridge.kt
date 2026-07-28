package com.akashic.mobile.data.realtime.pluginui

import android.net.Uri
import android.webkit.WebMessage
import android.webkit.WebView
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class PluginUiWebBridge(private val webView: WebView) {
    private val json = Json { explicitNulls = false }

    fun publishCatalog(catalog: PluginUiWebCatalog) {
        post(
            "plugin.catalog",
            json.encodeToJsonElement(catalog),
        )
    }

    fun publishResult(result: PluginUiWebResult) {
        post(
            "plugin.result",
            json.encodeToJsonElement(result),
        )
    }

    private fun post(type: String, payload: JsonElement) {
        val encoded = json.encodeToString(
            buildJsonObject {
                put("type", type)
                put("payload", payload)
            },
        )
        webView.postWebMessage(WebMessage(encoded), TARGET_ORIGIN)
    }

    private companion object {
        val TARGET_ORIGIN: Uri = Uri.parse("https://appassets.androidplatform.net")
    }
}
