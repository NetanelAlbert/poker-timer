package com.netanelalbert.pokertimer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netanelalbert.pokertimer.data.SettingsRepository
import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerSettings
import com.netanelalbert.pokertimer.model.TimerState
import com.netanelalbert.pokertimer.sound.AlarmPlayer
import com.netanelalbert.pokertimer.timer.TimerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridges the persisted settings to the UI and to the clock.
 *
 * Note the clock itself lives in [TimerController], not here: it has to outlive any screen, and the
 * foreground service drives the same instance.
 */
class PokerTimerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.get(application)
    private val previewPlayer = AlarmPlayer(application)

    val settings: StateFlow<TimerSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, TimerSettings())

    val timerState: StateFlow<TimerState> = TimerController.state

    /** True once the saved session has been consulted, so the UI does not flash the default. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    init {
        viewModelScope.launch {
            // Feed settings into the engine from here as well as from the service, so edits made
            // while the clock is parked (with no service running) still take effect.
            repository.settings.collect { TimerController.engine.applySettings(it) }
        }
        viewModelScope.launch {
            repository.settings.first()
            // Come back to wherever the tournament was if the process was killed. restore() is a
            // no-op unless the clock is parked, so this can never stomp a live countdown.
            repository.savedSession.first()?.let { saved ->
                TimerController.engine.restore(saved.levelIndex, saved.remainingMs)
            }
            _ready.value = true
        }
    }

    fun updateSettings(transform: (TimerSettings) -> TimerSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setLevels(levels: List<BlindLevel>) = updateSettings { it.copy(levels = levels) }

    fun resetSettingsToDefaults() = updateSettings { TimerSettings() }

    /** Plays a sound once so the user can hear what they just picked. */
    fun previewSound(uri: String?) {
        previewPlayer.playOneShot(uri, settings.value.alarmVolume)
    }

    override fun onCleared() {
        previewPlayer.release()
        super.onCleared()
    }
}
