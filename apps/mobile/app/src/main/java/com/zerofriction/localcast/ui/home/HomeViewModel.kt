package com.zerofriction.localcast.ui.home

import androidx.lifecycle.ViewModel
import com.zerofriction.localcast.service.CastState
import com.zerofriction.localcast.service.CastService
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the home screen state. Phase6: the cast service owns the session, so
 * the home screen renders [CastService.state] 1:1 — the home screen shows a
 * live cast ("Casting to …" + stop) even though pairing happened on the scan
 * screen, because the session no longer dies with any ViewModel.
 */
class HomeViewModel(
    castStateFlow: StateFlow<CastState> = CastService.state,
) : ViewModel() {

    val castState: StateFlow<CastState> = castStateFlow
}
