package com.zerofriction.localcast.ui.home

/**
 * Connection state toward the desktop. Phase 1 has exactly one state; pairing
 * (Phase3) and casting (Phase 5) extend this enum — the UI renders [label].
 */
enum class ConnectionStatus {
    NOT_CONNECTED;

    val label: String
        get() = when (this) {
            NOT_CONNECTED -> "Not connected"
        }
}

data class HomeUiState(
    val status: ConnectionStatus = ConnectionStatus.NOT_CONNECTED,
)
