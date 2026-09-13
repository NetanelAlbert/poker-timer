package com.netanelalbert.pokertimer.timer

import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerEvent
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerSettings
import com.netanelalbert.pokertimer.model.TimerState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The tournament clock's state machine.
 *
 * Deliberately free of Android imports so the whole thing is unit-testable on the JVM: the only
 * outside dependency is [clock], which must be a monotonic millisecond source (in the app,
 * `SystemClock.elapsedRealtime`). Everything is derived from a deadline on that clock rather than
 * from accumulated ticks, so the countdown cannot drift no matter how irregular [tick] calls are.
 *
 * The defining behaviour: a level running out does NOT start the next one. It parks in
 * [TimerPhase.LevelEnded] with the alarm looping until [primaryAction] is called.
 */
class TimerEngine(private val clock: () -> Long) {

    private val _state = MutableStateFlow(
        TimerState(
            levels = TimerSettings.DEFAULT_LEVELS,
            phase = TimerPhase.Ready(0, TimerSettings.DEFAULT_LEVELS.first().durationMs),
            remainingMs = TimerSettings.DEFAULT_LEVELS.first().durationMs,
        )
    )
    val state: StateFlow<TimerState> = _state.asStateFlow()

    // A channel rather than a SharedFlow: these are one-shot commands for the single consumer that
    // owns the speaker (the service), and they must not be replayed to it after a restart — a
    // replayed chime would go off minutes after the level it belonged to.
    private val _events = Channel<TimerEvent>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: Flow<TimerEvent> = _events.receiveAsFlow()

    /** Test seam: drains one pending event without needing a coroutine. */
    internal fun pollEvent(): TimerEvent? = _events.tryReceive().getOrNull()

    private var warningEnabled = true
    private var warningLeadMs = 30_000L
    private var chimeEnabled = true
    private var snoozeMs = 120_000L

    /** Guards the pre-end warning so it fires exactly once per level, even across pause/resume. */
    private var warningFired = false

    // region configuration

    fun applySettings(settings: TimerSettings) {
        warningEnabled = settings.warningEnabled
        warningLeadMs = settings.warningLeadSeconds * 1000L
        chimeEnabled = settings.chimeEnabled
        snoozeMs = settings.snoozeSeconds * 1000L
        setLevels(settings.levels)
    }

    /**
     * Swap the structure underneath the clock. Editing levels mid-tournament is legitimate (someone
     * always wants to shorten the blinds at 1am), so we keep the current position rather than
     * resetting: indices are clamped and a running deadline is left alone unless it no longer makes
     * sense for the level it now points at.
     *
     * Levels are identified by array index, not by any stable id — there isn't one. So this can only
     * detect a clamp or a now-too-long remainder; reordering or inserting a level ahead of the
     * running one silently reattaches the countdown to different blinds even though nothing was
     * clamped. A real fix needs a per-level id and is deliberately out of scope here.
     */
    fun setLevels(levels: List<BlindLevel>) {
        if (levels.isEmpty()) return
        val current = _state.value
        if (current.levels == levels) return

        val lastIndex = levels.lastIndex
        val phase = when (val p = current.phase) {
            is TimerPhase.Ready -> {
                val index = p.levelIndex.coerceIn(0, lastIndex)
                val remaining = if (index != p.levelIndex) {
                    // The structure shrank out from under us; carrying the old remainder onto a
                    // different level would show a time that belongs to nothing.
                    levels[index].durationMs
                } else {
                    // Same level, but it may have just been shortened.
                    p.remainingMs.coerceIn(0L, levels[index].durationMs)
                }
                TimerPhase.Ready(index, remaining)
            }
            is TimerPhase.Running -> {
                val index = p.levelIndex.coerceIn(0, lastIndex)
                val remaining = p.deadlineMs - clock()
                if (index != p.levelIndex || remaining > levels[index].durationMs) {
                    // Either the clamp moved us onto a different level, or the level now at this
                    // index is shorter than what's left on the clock — either way the deadline
                    // belongs to a level that no longer exists here, so rebase it.
                    TimerPhase.Running(index, clock() + levels[index].durationMs)
                } else {
                    TimerPhase.Running(index, p.deadlineMs)
                }
            }
            is TimerPhase.LevelEnded -> p.copy(
                finishedLevelIndex = p.finishedLevelIndex.coerceIn(0, lastIndex),
                nextLevelIndex = p.nextLevelIndex?.takeIf { it <= lastIndex },
            )
            TimerPhase.Finished -> TimerPhase.Finished
        }
        _state.value = current.copy(levels = levels, phase = phase).withRecomputedRemaining()
    }

    // endregion

    // region actions

    /**
     * The single big button. What it means depends on where the clock is:
     * parked -> start, running -> pause, alarm sounding -> silence it and start the next level.
     */
    fun primaryAction() {
        when (val phase = _state.value.phase) {
            is TimerPhase.Ready -> resumeFrom(phase.levelIndex, phase.remainingMs)
            is TimerPhase.Running -> pause()
            is TimerPhase.LevelEnded ->
                if (phase.nextLevelIndex != null) beginLevel(phase.nextLevelIndex) else finish()
            TimerPhase.Finished -> reset()
        }
    }

    /**
     * Silence the blinds-up alarm without moving on. The clock stays exactly where it is — parked
     * on the new blinds, not started — and the alarm comes back once the snooze runs out.
     */
    fun snooze() {
        val current = _state.value
        val phase = current.phase as? TimerPhase.LevelEnded ?: return
        _state.value = current.copy(
            phase = phase.copy(snoozeUntilMs = clock() + snoozeMs),
            snoozeRemainingMs = snoozeMs,
        )
    }

    fun pause() {
        val current = _state.value
        val phase = current.phase as? TimerPhase.Running ?: return
        val remaining = (phase.deadlineMs - clock()).coerceAtLeast(0L)
        _state.value = current.copy(
            phase = TimerPhase.Ready(phase.levelIndex, remaining),
            remainingMs = remaining,
            snoozeRemainingMs = null,
        )
    }

    fun resume() {
        val phase = _state.value.phase as? TimerPhase.Ready ?: return
        resumeFrom(phase.levelIndex, phase.remainingMs)
    }

    /** Jump forward a level. Keeps whether the clock is running, and never sounds the alarm. */
    fun nextLevel() = goToLevel(_state.value.displayLevelIndex + 1)

    /** Jump back a level. Restarts that level's full duration. */
    fun previousLevel() = goToLevel(_state.value.displayLevelIndex - 1)

    fun goToLevel(index: Int) {
        val current = _state.value
        // Stepping off either end is a no-op rather than a clamp: silently restarting the level
        // you are already on is not what "next"/"previous" is asking for.
        val target = index.takeIf { it in current.levels.indices } ?: return
        // A live clock (running, or an alarm waiting to be acknowledged — snoozed or not) stays
        // live across a jump; a parked clock stays parked, so nudging through the structure never
        // starts a level.
        if (current.isRunning || current.phase is TimerPhase.LevelEnded) {
            beginLevel(target)
        } else {
            val duration = current.levels[target].durationMs
            warningFired = false
            _state.value = current.copy(
                phase = TimerPhase.Ready(target, duration),
                remainingMs = duration,
                snoozeRemainingMs = null,
            )
        }
    }

    /** Back to the top of the structure, parked. */
    fun reset() {
        val current = _state.value
        val duration = current.levels.firstOrNull()?.durationMs ?: return
        warningFired = false
        _state.value = current.copy(
            phase = TimerPhase.Ready(0, duration),
            remainingMs = duration,
            snoozeRemainingMs = null,
        )
    }

    /**
     * Advance the clock. Called on a short interval by the service; safe to call at any rate,
     * including not at all for a while (a suspended CPU just means one late, still-correct tick).
     */
    fun tick() {
        val current = _state.value
        when (val phase = current.phase) {
            is TimerPhase.Running -> tickRunning(current, phase)
            is TimerPhase.LevelEnded -> tickSnooze(current, phase)
            else -> Unit
        }
    }

    /** Bring the alarm back when a snooze runs out. */
    private fun tickSnooze(current: TimerState, phase: TimerPhase.LevelEnded) {
        val until = phase.snoozeUntilMs ?: return
        val remaining = until - clock()
        _state.value = if (remaining <= 0L) {
            current.copy(phase = phase.copy(snoozeUntilMs = null), snoozeRemainingMs = null)
        } else {
            current.copy(snoozeRemainingMs = remaining)
        }
    }

    private fun tickRunning(current: TimerState, phase: TimerPhase.Running) {
        val remaining = phase.deadlineMs - clock()

        if (remaining <= 0L) {
            val next = (phase.levelIndex + 1).takeIf { it <= current.levels.lastIndex }
            _state.value = current.copy(
                phase = TimerPhase.LevelEnded(phase.levelIndex, next),
                // Show the incoming level's full duration: the numbers have moved on, the clock
                // for them has not started.
                remainingMs = next?.let { current.levels[it].durationMs } ?: 0L,
            )
            return
        }

        if (warningEnabled && !warningFired && remaining <= warningLeadMs) {
            // Suppress it on levels shorter than the lead time, where it would fire immediately
            // and just double up with the alarm.
            if (current.levels[phase.levelIndex].durationMs > warningLeadMs) {
                _events.trySend(TimerEvent.WARNING)
            }
            warningFired = true
        }
        _state.value = current.copy(remainingMs = remaining)
    }

    // endregion

    // region internals

    private fun beginLevel(index: Int) {
        val current = _state.value
        val target = index.coerceIn(0, current.levels.lastIndex)
        val duration = current.levels[target].durationMs
        warningFired = false
        _state.value = current.copy(
            phase = TimerPhase.Running(target, clock() + duration),
            remainingMs = duration,
            snoozeRemainingMs = null,
        )
        if (chimeEnabled) _events.trySend(TimerEvent.LEVEL_STARTED)
    }

    /** Start counting again from an exact remainder — no chime, this is not a new level. */
    private fun resumeFrom(levelIndex: Int, remainingMs: Long) {
        val current = _state.value
        if (remainingMs <= 0L) {
            beginLevel(levelIndex)
            return
        }
        val atLevelStart = remainingMs >= current.levels[levelIndex].durationMs
        if (atLevelStart) {
            beginLevel(levelIndex)
        } else {
            _state.value = current.copy(
                phase = TimerPhase.Running(levelIndex, clock() + remainingMs),
                remainingMs = remainingMs,
            )
        }
    }

    private fun finish() {
        _state.value = _state.value.copy(
            phase = TimerPhase.Finished,
            remainingMs = 0L,
            snoozeRemainingMs = null,
        )
    }

    private fun TimerState.withRecomputedRemaining(): TimerState = when (val p = phase) {
        is TimerPhase.Ready -> copy(remainingMs = p.remainingMs)
        is TimerPhase.Running -> copy(remainingMs = (p.deadlineMs - clock()).coerceAtLeast(0L))
        is TimerPhase.LevelEnded ->
            copy(remainingMs = p.nextLevelIndex?.let { levels[it].durationMs } ?: 0L)
        TimerPhase.Finished -> copy(remainingMs = 0L)
    }

    // endregion

    /**
     * Restore a position saved before the process was killed. Always comes back parked.
     *
     * [wasLevelEnded] marks a save taken while a level's alarm was ringing (or snoozed): the alarm
     * itself is never resurrected — coming back to a blaring alarm hours later is worse than the bug
     * this fixes — so instead we land parked on [levelIndex] at its full duration, ignoring whatever
     * [remainingMs] was recorded as, rather than trying to reconstruct how much of the wait-for-tap
     * had elapsed.
     */
    fun restore(levelIndex: Int, remainingMs: Long, wasLevelEnded: Boolean = false) {
        val current = _state.value
        if (current.phase !is TimerPhase.Ready || current.levels.isEmpty()) return
        val index = levelIndex.coerceIn(0, current.levels.lastIndex)
        val remaining = if (wasLevelEnded) {
            current.levels[index].durationMs
        } else {
            remainingMs.coerceIn(0L, current.levels[index].durationMs)
        }
        if (remaining <= 0L) return
        _state.value = current.copy(
            phase = TimerPhase.Ready(index, remaining),
            remainingMs = remaining,
        )
    }
}
