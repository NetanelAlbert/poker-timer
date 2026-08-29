package com.netanelalbert.pokertimer.timer

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.netanelalbert.pokertimer.model.TimerState
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide handle on the clock.
 *
 * The engine has to be a singleton rather than live inside the service or a ViewModel: the UI and
 * the foreground service are two views onto one tournament, and neither owns it.
 *
 * Every user action goes through here so that starting the clock also starts the service that
 * keeps it alive with the screen off.
 */
object TimerController {

    val engine = TimerEngine(clock = { SystemClock.elapsedRealtime() })

    val state: StateFlow<TimerState> get() = engine.state

    fun primaryAction(context: Context) = act(context) { engine.primaryAction() }

    fun pause(context: Context) = act(context) { engine.pause() }

    fun snooze(context: Context) = act(context) { engine.snooze() }

    fun nextLevel(context: Context) = act(context) { engine.nextLevel() }

    fun previousLevel(context: Context) = act(context) { engine.previousLevel() }

    fun reset(context: Context) = act(context) { engine.reset() }

    private inline fun act(context: Context, action: () -> Unit) {
        action()
        syncService(context)
    }

    /**
     * Bring the service up when the clock goes live. Tearing it down is left to the service, which
     * watches the state itself — that way it also stops when the clock is stopped from elsewhere.
     */
    fun syncService(context: Context) {
        if (!state.value.needsService) return
        context.startForegroundService(Intent(context, TimerService::class.java))
    }
}
