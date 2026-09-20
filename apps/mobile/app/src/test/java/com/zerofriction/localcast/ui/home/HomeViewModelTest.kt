package com.zerofriction.localcast.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {

    @Test
    fun `initial state is not connected`() {
        val viewModel = HomeViewModel()

        assertEquals(ConnectionStatus.NOT_CONNECTED, viewModel.uiState.value.status)
    }
}
