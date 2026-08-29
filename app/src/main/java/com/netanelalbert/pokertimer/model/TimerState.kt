package com.netanelalbert.pokertimer.model

/**
 * Where the tournament clock currently is.
 *
 * Note there is deliberately no "auto advance" phase: when a level runs out the clock lands in
 * [LevelEnded], the alarm loops, and it stays there until somebody taps. The tap is what starts
 * the next level.
 */
sealed interface TimerPhase {

    /** Parked on a level, not counting down. Covers both "never started" and "paused". */
    data class Ready(val levelIndex: Int, val remainingMs: Long) : TimerPhase

    /** Counting down. [deadlineMs] is on the monotonic clock, so the countdown cannot drift. */
    data class Running(val levelIndex: Int, val deadlineMs: Long) : TimerPhase

    /**
     * A level just ran out. The alarm is looping and the display has already moved on to
     * [nextLevelIndex]'s blinds. [nextLevelIndex] is null when the last level just ended.
     */
    data class LevelEnded(val finishedLevelIndex: Int, val nextLevelIndex: Int?) : TimerPhase

    /** The last level ended and was acknowledged. */
    data object Finished : TimerPhase
}

/** The complete observable state of the clock. */
data class TimerState(
    val levels: List<BlindLevel>,
    val phase: TimerPhase,
    val remainingMs: Long,
) {
    /** The level whose blinds the screen should show. */
    val displayLevelIndex: Int
        get() = when (phase) {
            is TimerPhase.Ready -> phase.levelIndex
            is TimerPhase.Running -> phase.levelIndex
            // Once a level ends we show the NEW blinds, even though they are not running yet.
            is TimerPhase.LevelEnded -> phase.nextLevelIndex ?: phase.finishedLevelIndex
            TimerPhase.Finished -> levels.lastIndex.coerceAtLeast(0)
        }

    val currentLevel: BlindLevel? get() = levels.getOrNull(displayLevelIndex)

    val nextLevel: BlindLevel? get() = levels.getOrNull(displayLevelIndex + 1)

    val isRunning: Boolean get() = phase is TimerPhase.Running

    /** True while the blinds-up alarm should be sounding. */
    val isAlarming: Boolean get() = phase is TimerPhase.LevelEnded

    /** The foreground service only needs to exist while the clock is live. */
    val needsService: Boolean get() = isRunning || isAlarming

    /** Fraction of the current level already played, for the progress ring. */
    val progress: Float
        get() {
            val total = currentLevel?.durationMs ?: return 0f
            if (total <= 0L) return 0f
            return ((total - remainingMs).toFloat() / total).coerceIn(0f, 1f)
        }

    /** True when parked at the very start of a level, i.e. the button should say "Start". */
    val isAtLevelStart: Boolean
        get() = phase is TimerPhase.Ready && remainingMs >= (currentLevel?.durationMs ?: 0L)
}

/** One-shot sounds. The looping blinds-up alarm is driven by [TimerState.isAlarming] instead. */
enum class TimerEvent { WARNING, LEVEL_STARTED }
