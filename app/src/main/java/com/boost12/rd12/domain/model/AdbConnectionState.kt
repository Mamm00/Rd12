package com.boost12.rd12.domain.model

enum class AdbErrorKind {
    UNAUTHORIZED_REVOKED,
    PAIRING_TIMEOUT,
    PAIRING_CODE_REJECTED,
    SOCKET_DROPPED_DOZE,
    CONNECTION_REFUSED,
    WIFI_UNAVAILABLE,
    INVALID_ENDPOINT,
    UNKNOWN
}

sealed interface AdbConnectionState {
    data object Disconnected : AdbConnectionState

    data class Pairing(
        val host: String,
        val port: Int,
        val stage: String = "Negotiating TLSv1.3 + SPAKE2+…"
    ) : AdbConnectionState

    data class Connecting(
        val host: String,
        val port: Int,
        val attempt: Int = 1
    ) : AdbConnectionState

    data class Connected(
        val host: String,
        val port: Int,
        val isTls: Boolean = true,
        val connectedAtEpochMs: Long = System.currentTimeMillis()
    ) : AdbConnectionState

    data class Error(
        val kind: AdbErrorKind,
        val message: String,
        val attempt: Int = 0,
        val nextRetryInMs: Long? = null
    ) : AdbConnectionState
}

enum class EndpointType {
    TLS_PAIRING,
    TLS_CONNECT
}

data class MdnsDiscoveredEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val type: EndpointType
) {
    val addressLabel: String get() = "$host:$port"
}

data class SavedConnectionConfig(
    val isPaired: Boolean,
    val lastHost: String,
    val lastConnectPort: Int,
    val lastPairingPort: Int,
    val autoReconnect: Boolean,
    val certificateFingerprint: String?
)
