package com.example.vcompress

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class CompressionProgressState {
    object Idle : CompressionProgressState()
    data class Running(val progress: Int, val message: String) : CompressionProgressState()
    data class Done(val resultSizeMb: Double, val savedPercent: Int, val message: String) : CompressionProgressState()
    data class Failed(val error: String) : CompressionProgressState()
}

object CompressionStateHolder {
    private val _state = MutableStateFlow<CompressionProgressState>(CompressionProgressState.Idle)
    val state: StateFlow<CompressionProgressState> = _state.asStateFlow()

    fun update(newState: CompressionProgressState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = CompressionProgressState.Idle
    }
}
