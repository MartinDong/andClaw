package com.coderred.andclaw.ui.screen.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.data.SetupState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SetupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndClawApp
    private val setupManager = app.setupManager
    private val prefs = app.preferencesManager
    private val prootManager = app.prootManager

    val state: StateFlow<SetupState> = setupManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SetupState())

    val availableStorageMb: Long
        get() = prootManager.getAvailableStorageMb()

    val hasEnoughStorage: Boolean
        get() = prootManager.hasEnoughStorage()

    val isProotAvailable: Boolean
        get() = prootManager.isProotAvailable

    val isAlreadySetup: Boolean
        get() = prootManager.isFullySetup

    fun startSetup() {
        viewModelScope.launch {
            val success = setupManager.runFullSetup()
            if (success) {
                prefs.setSetupComplete(true)
            }
        }
    }
}
