package com.mupa.agent.argos.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SyncStateHolder {
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    fun begin() { _isSyncing.value = true }
    fun end()   { _isSyncing.value = false }
}
