package com.zerofriction.localcast.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the home screen state. Phase 1: static status + a notice when the user
 * taps Start Cast. Real session wiring (pairing, WebRTC) starts in Phase 3.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onStartCastClicked() {
        _uiState.update { it.copy(notice = CAST_NOT_IMPLEMENTED_NOTICE) }
    }

    fun onNoticeShown() {
        _uiState.update { it.copy(notice = null) }
    }

    private companion object {
        const val CAST_NOT_IMPLEMENTED_NOTICE =
            "Casting isn't wired up yet — it arrives in Phase 5."
    }
}
