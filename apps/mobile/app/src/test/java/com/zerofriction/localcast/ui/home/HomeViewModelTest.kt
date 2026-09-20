package com.zerofriction.localcast.ui.home

import com.zerofriction.localcast.service.CastState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeViewModelTest {

    @org.junit.Test
    fun `renders the cast service state exactly`() {
        val castState = MutableStateFlow<CastState>(CastState.Idle)
        val viewModel = HomeViewModel(castState)

        assertEquals(CastState.Idle, viewModel.castState.value)

        castState.value = CastState.Starting("test-desktop")
        assertEquals(CastState.Starting("test-desktop"), viewModel.castState.value)

        castState.value = CastState.Casting("test-desktop")
        assertEquals(CastState.Casting("test-desktop"), viewModel.castState.value)

        castState.value = CastState.Failed("The screen cast was stopped.")
        assertEquals(CastState.Failed("The screen cast was stopped."), viewModel.castState.value)
    }
}
