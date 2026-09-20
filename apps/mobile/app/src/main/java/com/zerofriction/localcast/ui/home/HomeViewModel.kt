package com.zerofriction.localcast.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the home screen state. Phase3: the home screen launches the pairing
 * scan; a session-aware status (connected desktop name) arrives when the cast
 * service owns the session (Phase6) — the pairing session currently lives in
 * the scan screen's ViewModel.
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
}
