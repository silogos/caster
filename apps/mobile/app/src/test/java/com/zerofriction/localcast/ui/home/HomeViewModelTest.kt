package com.zerofriction.localcast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun `initial state is not connected with no notice`() {
        val viewModel = HomeViewModel()

        assertEquals(ConnectionStatus.NOT_CONNECTED, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `start cast shows a notice without changing connection status`() {
        val viewModel = HomeViewModel()

        viewModel.onStartCastClicked()

        assertNotNull(viewModel.uiState.value.notice)
        assertEquals(ConnectionStatus.NOT_CONNECTED, viewModel.uiState.value.status)
    }

    @Test
    fun `notice clears after it was shown`() {
        val viewModel = HomeViewModel()
        viewModel.onStartCastClicked()

        viewModel.onNoticeShown()

        assertNull(viewModel.uiState.value.notice)
    }
}
