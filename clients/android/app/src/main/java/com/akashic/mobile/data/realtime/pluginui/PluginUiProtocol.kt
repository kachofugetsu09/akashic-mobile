package com.akashic.mobile.data.realtime.pluginui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PluginUiWebCatalog(
    val catalogRevision: String,
    val updating: Boolean,
    val error: String? = null,
    val plugins: List<PluginUiWebPlugin>,
)

@Serializable
data class PluginUiWebPlugin(
    val id: String,
    val revision: String,
    val moduleUrl: String,
    val stylesheetUrl: String? = null,
    val navigation: PluginUiWebNavigation? = null,
    val slots: List<String>,
)

@Serializable
data class PluginUiWebNavigation(
    val label: String,
    val description: String,
)

@Serializable
data class PluginUiWebResult(
    val requestId: String,
    val resultJson: String? = null,
    val error: String? = null,
)

@Serializable
data class PluginUiHttpGrantPayload(
    val path: String,
    val ticket: String,
    @SerialName("expires_at")
    val expiresAt: String,
)

data class PluginUiHttpRequest(
    val commandId: String,
    val path: String,
    val ticket: String,
    val body: JsonObject,
)

data class PluginUiHttpResponse(
    val statusCode: Int,
    val body: String,
)
