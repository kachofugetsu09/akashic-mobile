package com.akashic.mobile.domain.model

data class ServerEndpoint(
    val url: String,
    val tlsSpkiPins: List<String>,
    val route: EndpointRoute,
)

enum class EndpointRoute {
    LAN,
    TUNNEL,
}

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    SERVER_CHALLENGE,
    DEVICE_PROOF,
    AUTHENTICATED,
    SYNCING,
    READY,
    DEGRADED,
    FAILED,
    CLOSED,
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val endpoint: ServerEndpoint? = null,
    val connectionEpoch: Long? = null,
    val retryCount: Int = 0,
    val lastErrorCode: String? = null,
)

/** 当前画面只展示一个连续窗口；null 上界表示跟随最新消息。 */
data class HistoryWindow(
    val afterSeq: Long = Long.MAX_VALUE,
    val throughSeq: Long? = null,
    val hasOlder: Boolean = true,
    val loading: Boolean = false,
)
