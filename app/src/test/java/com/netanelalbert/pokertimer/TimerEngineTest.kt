package com.netanelalbert.pokertimer

import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerEvent
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerSettings
import com.netanelalbert.pokertimer.timer.TimerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock's whole contract, driven by a fake monotonic clock so the tests are exact and instant.
 *
 * The behaviour these are really guarding is the manual advance: a level running out must never
 * start the next one on its own.
 */
class TimerEngineTest {

    private class FakeClock(var now: Long = 10_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private val clock = FakeClock()

    private val levels = listOf(
        BlindLevel(smallBlind = 25, bigBlind = 50, durationSeconds = 600),
        BlindLevel(smallBlind = 50, bigBlind = 100, durationSeconds = 300),
        BlindLevel(smallBlind = 100, bigBlind = 200, durationSeconds = 120),
    )

    private fun engine(
        levels: List<BlindLevel> = this.levels,
        warningEnabled: Boolean = true,
        warningLeadSeconds: Int = 30,
        chimeEnabled: Boolean = true,
        snoozeSeconds: Int = 120,
    ) = TimerEngine { clock.now }.apply {
        applySettings(
            TimerSettings(
                levels = levels,
                warningEnabled = warningEnabled,
                warningLeadSeconds = warningLeadSeconds,
                chimeEnabled = chimeEnabled,
                snoozeSeconds = snoozeSeconds,
            )
        )
    }

    /** Runs the first level out so the clock is sitting on a sounding alarm. */
    private fun engineAtAlarm(): TimerEngine = engine().apply {
        primaryAction()
        clock.advance(600_000L)
        tick()
    }

    private val recorded = mutableListOf<TimerEvent>()

    /**
     * Every one-shot event the engine has emitted so far. Drains the engine's event channel
     * synchronously, so the tests need no dispatchers and stay exact.
     */
    private fun TimerEngine.events(): List<TimerEvent> {
        while (true) recorded += pollEvent() ?: break
        return recorded
    }

    private fun TimerEngine.warningCount() = events().count { it == TimerEvent.WARNING }

    @Test
    fun `starts parked at the top of the structure`() {
        val engine = engine()
        val state = engine.state.value

        assertEquals(TimerPhase.Ready(0, 600_000L), state.phase)
        assertEquals(600_000L, state.remainingMs)
        assertFalse(state.isRunning)
        assertFalse(state.needsService)
        assertTrue(state.isAtLevelStart)
    }

    @Test
    fun `primary action starts the first level`() {
        val engine = engine()
        engine.primaryAction()

        assertEquals(TimerPhase.Running(0, clock.now + 600_000L), engine.state.value.phase)
        assertTrue(engine.state.value.isRunning)
        assertTrue(engine.state.value.needsService)
    }

    @Test
    fun `countdown is derived from the deadline, not from tick count`() {
        val engine = engine()
        engine.primaryAction()

        // One single tick after a long gap must still land on the right remainder: the service
        // may be starved for seconds at a time while the device sleeps.
        clock.advance(123_456L)
        engine.tick()

        assertEquals(600_000L - 123_456L, engine.state.value.remainingMs)
    }

    @Test
    fun `expiry parks on the next level and does not start it`() {
        val engine = engine()
        engine.primaryAction()

        clock.advance(600_000L)
        engine.tick()

        val state = engine.state.value
        assertEquals(TimerPhase.LevelEnded(finishedLevelIndex = 0, nextLevelIndex = 1), state.phase)
        assertTrue("the alarm should be sounding", state.isAlarming)
        assertFalse("the next level must not run until tapped", state.isRunning)
        // The display has already moved on to the new blinds, showing their untouched duration.
        assertEquals(1, state.displayLevelIndex)
        assertEquals(50, state.currentLevel?.smallBlind)
        assertEquals(100, state.currentLevel?.bigBlind)
        assertEquals(300_000L, state.remainingMs)
    }

    @Test
    fun `waiting at the end of a level never auto-advances`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(600_000L)
        engine.tick()

        // Nobody taps for ten minutes. The clock must sit exactly where it is.
        repeat(100) {
            clock.advance(6_000L)
            engine.tick()
        }

        assertEquals(TimerPhase.LevelEnded(0, 1), engine.state.value.phase)
        assertTrue(engine.state.value.isAlarming)
    }

    @Test
    fun `tapping after expiry starts the next level`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(600_000L)
        engine.tick()

        engine.primaryAction()

        val state = engine.state.value
        assertEquals(TimerPhase.Running(1, clock.now + 300_000L), state.phase)
        assertFalse("the alarm must stop once acknowledged", state.isAlarming)
        assertEquals(300_000L, state.remainingMs)
    }

    @Test
    fun `the last level ends the tournament rather than wrapping around`() {
        val engine = engine()
        engine.goToLevel(2)
        engine.primaryAction()

        clock.advance(120_000L)
        engine.tick()

        val ended = engine.state.value.phase as TimerPhase.LevelEnded
        assertEquals(2, ended.finishedLevelIndex)
        assertNull("there is no level after the last one", ended.nextLevelIndex)
        assertTrue(engine.state.value.isAlarming)

        engine.primaryAction()
        assertEquals(TimerPhase.Finished, engine.state.value.phase)
        assertFalse(engine.state.value.needsService)
    }

    @Test
    fun `pause preserves the exact remainder and resume continues from it`() {
        val engine = engine()
        engine.primaryAction()

        clock.advance(90_000L)
        engine.tick()
        engine.pause()

        assertEquals(TimerPhase.Ready(0, 510_000L), engine.state.value.phase)
        assertFalse(engine.state.value.isAtLevelStart)

        // Time passing while paused must not eat into the level.
        clock.advance(60_000L)
        engine.resume()

        assertEquals(TimerPhase.Running(0, clock.now + 510_000L), engine.state.value.phase)
    }

    @Test
    fun `next level while running starts that level immediately`() {
        val engine = engine()
        engine.primaryAction()
        engine.nextLevel()

        assertEquals(TimerPhase.Running(1, clock.now + 300_000L), engine.state.value.phase)
    }

    @Test
    fun `next level while parked stays parked`() {
        val engine = engine()
        engine.nextLevel()

        assertEquals(TimerPhase.Ready(1, 300_000L), engine.state.value.phase)
        assertFalse("browsing the structure must not start the clock", engine.state.value.isRunning)
    }

    @Test
    fun `stepping past either end of the structure does nothing`() {
        val engine = engine()
        engine.previousLevel()
        assertEquals(TimerPhase.Ready(0, 600_000L), engine.state.value.phase)

        engine.goToLevel(2)
        engine.nextLevel()
        assertEquals(TimerPhase.Ready(2, 120_000L), engine.state.value.phase)
    }

    @Test
    fun `reset returns to the top of the structure, parked`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(120_000L)
        engine.tick()
        engine.nextLevel()

        engine.reset()

        assertEquals(TimerPhase.Ready(0, 600_000L), engine.state.value.phase)
        assertFalse(engine.state.value.needsService)
    }

    @Test
    fun `reset from a sounding alarm returns to the top of the structure, parked`() {
        val engine = engineAtAlarm()
        engine.snooze()

        engine.reset()

        val state = engine.state.value
        assertEquals(TimerPhase.Ready(0, 600_000L), state.phase)
        assertFalse(state.needsService)
        assertNull(state.snoozeRemainingMs)
    }

    @Test
    fun `editing the structure mid-level keeps the clock where it is`() {
        val engine = engine()
        engine.goToLevel(1)
        engine.primaryAction()
        val deadline = (engine.state.value.phase as TimerPhase.Running).deadlineMs

        engine.setLevels(levels.map { it.copy(smallBlind = it.smallBlind * 2) })

        val phase = engine.state.value.phase as TimerPhase.Running
        assertEquals("the running level must not move", 1, phase.levelIndex)
        assertEquals("the deadline must not be disturbed", deadline, phase.deadlineMs)
        assertEquals(100, engine.state.value.currentLevel?.smallBlind)
    }

    @Test
    fun `a shortened structure clamps the current level instead of going out of bounds`() {
        val engine = engine()
        engine.goToLevel(2)

        engine.setLevels(listOf(levels[0]))

        assertEquals(0, engine.state.value.displayLevelIndex)
        assertEquals(
            "the surviving level should show its own full duration, not the old remainder",
            600_000L,
            engine.state.value.remainingMs,
        )
    }

    @Test
    fun `shrinking the structure while running rebases the deadline to the new level's duration`() {
        val engine = engine()
        engine.goToLevel(2)
        engine.primaryAction()
        clock.advance(90_000L)
        engine.tick()

        // Level 2 (120s) is dropped entirely; the running index clamps onto level 1 (300s), which
        // is shorter than the ~30s actually left, so the old deadline can't simply be kept.
        engine.setLevels(listOf(levels[0], levels[1]))

        val phase = engine.state.value.phase as TimerPhase.Running
        assertEquals(1, phase.levelIndex)
        assertEquals(TimerPhase.Running(1, clock.now + 300_000L), phase)
        assertEquals(300_000L, engine.state.value.remainingMs)
    }

    @Test
    fun `reordering the structure while running rebases when the new level no longer fits`() {
        val engine = engine()
        engine.goToLevel(1)
        engine.primaryAction()
        clock.advance(60_000L)
        engine.tick()

        // Same length and same index, but the level now sitting at index 1 is the old level 2
        // (120s) — far shorter than the ~240s actually remaining.
        engine.setLevels(listOf(levels[0], levels[2], levels[1]))

        val phase = engine.state.value.phase as TimerPhase.Running
        assertEquals(1, phase.levelIndex)
        assertEquals(TimerPhase.Running(1, clock.now + 120_000L), phase)
        assertEquals(120_000L, engine.state.value.remainingMs)
    }

    @Test
    fun `reordering the structure while running leaves the deadline alone when it still fits`() {
        val engine = engine()
        engine.goToLevel(1)
        engine.primaryAction()
        clock.advance(60_000L)
        engine.tick()
        val deadline = (engine.state.value.phase as TimerPhase.Running).deadlineMs

        // Swap the first two levels; index 1 now holds the old level 0 (600s), which comfortably
        // covers the ~240s remaining, so the deadline should be left exactly where it was.
        engine.setLevels(listOf(levels[1], levels[0], levels[2]))

        val phase = engine.state.value.phase as TimerPhase.Running
        assertEquals(1, phase.levelIndex)
        assertEquals("the deadline must not be disturbed", deadline, phase.deadlineMs)
    }

    @Test
    fun `warning fires once, at the configured lead time`() {
        val engine = engine()
        engine.primaryAction()
        assertEquals(listOf(TimerEvent.LEVEL_STARTED), engine.events())

        clock.advance(600_000L - 31_000L)
        engine.tick()
        assertEquals("too early for the warning", 0, engine.warningCount())

        clock.advance(1_500L)
        engine.tick()
        assertEquals(1, engine.warningCount())

        // Every subsequent tick is still inside the warning window; it must not fire again.
        repeat(10) {
            clock.advance(1_000L)
            engine.tick()
        }
        assertEquals(1, engine.warningCount())
    }

    @Test
    fun `warning is not re-armed by pausing and resuming inside the window`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(600_000L - 20_000L)
        engine.tick()
        assertEquals(1, engine.warningCount())

        engine.pause()
        engine.resume()
        clock.advance(5_000L)
        engine.tick()

        assertEquals(1, engine.warningCount())
    }

    @Test
    fun `warning is re-armed for the next level`() {
        val engine = engine()
        engine.primaryAction()

        clock.advance(600_000L - 10_000L)
        engine.tick()
        assertEquals(1, engine.warningCount())

        clock.advance(10_000L)
        engine.tick()
        engine.primaryAction()

        clock.advance(300_000L - 10_000L)
        engine.tick()

        assertEquals(2, engine.warningCount())
    }

    @Test
    fun `warning is suppressed on a level shorter than the lead time`() {
        val engine = engine()
        // A 20-second level with a 30-second warning would otherwise fire the warning the instant
        // the level starts, with the alarm right behind it.
        engine.setLevels(listOf(BlindLevel(25, 50, durationSeconds = 20)))
        engine.primaryAction()

        clock.advance(19_000L)
        engine.tick()

        assertEquals(0, engine.warningCount())
    }

    @Test
    fun `resuming mid-level does not chime, but starting a level does`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(1_000L)
        engine.tick()
        engine.pause()
        engine.resume()

        assertEquals(
            "a resume is not a new level",
            1,
            engine.events().count { it == TimerEvent.LEVEL_STARTED },
        )
    }

    @Test
    fun `snooze silences the alarm without advancing anything`() {
        val engine = engineAtAlarm()

        engine.snooze()

        val state = engine.state.value
        assertFalse("the alarm must go quiet", state.isAlarming)
        assertTrue(state.isSnoozed)
        assertFalse("a snooze is not a start", state.isRunning)
        // Still parked on the new blinds, showing their untouched duration.
        assertEquals(1, state.displayLevelIndex)
        assertEquals(300_000L, state.remainingMs)
        assertEquals(120_000L, state.snoozeRemainingMs)
        val phase = state.phase as TimerPhase.LevelEnded
        assertEquals(0, phase.finishedLevelIndex)
        assertEquals(1, phase.nextLevelIndex)
    }

    @Test
    fun `the service must stay alive through a snooze to bring the alarm back`() {
        val engine = engineAtAlarm()
        engine.snooze()

        assertTrue(engine.state.value.needsService)
    }

    @Test
    fun `the alarm comes back when the snooze runs out`() {
        val engine = engineAtAlarm()
        engine.snooze()

        clock.advance(119_000L)
        engine.tick()
        assertFalse("still inside the snooze", engine.state.value.isAlarming)
        assertEquals(1_000L, engine.state.value.snoozeRemainingMs)

        clock.advance(1_000L)
        engine.tick()

        val state = engine.state.value
        assertTrue("the alarm must return", state.isAlarming)
        assertFalse(state.isSnoozed)
        assertNull(state.snoozeRemainingMs)
        // And it comes back to the same place, still not started.
        assertEquals(TimerPhase.LevelEnded(0, 1), state.phase)
        assertFalse(state.isRunning)
    }

    @Test
    fun `a snooze can be repeated indefinitely`() {
        val engine = engineAtAlarm()

        repeat(5) {
            engine.snooze()
            assertFalse(engine.state.value.isAlarming)
            clock.advance(120_000L)
            engine.tick()
            assertTrue(engine.state.value.isAlarming)
        }
        // Five snoozes later the clock has still not moved off the level that ended.
        assertEquals(TimerPhase.LevelEnded(0, 1), engine.state.value.phase)
    }

    @Test
    fun `starting the next level clears a snooze`() {
        val engine = engineAtAlarm()
        engine.snooze()

        engine.primaryAction()

        val state = engine.state.value
        assertEquals(TimerPhase.Running(1, clock.now + 300_000L), state.phase)
        assertFalse(state.isSnoozed)
        assertFalse(state.isAlarming)
    }

    @Test
    fun `the snooze length comes from settings`() {
        val engine = engine(snoozeSeconds = 45)
        engine.primaryAction()
        clock.advance(600_000L)
        engine.tick()

        engine.snooze()
        assertEquals(45_000L, engine.state.value.snoozeRemainingMs)

        clock.advance(45_000L)
        engine.tick()
        assertTrue(engine.state.value.isAlarming)
    }

    @Test
    fun `snooze does nothing unless the alarm is up`() {
        val engine = engine()
        val parked = engine.state.value.phase

        engine.snooze()
        assertEquals(parked, engine.state.value.phase)

        engine.primaryAction()
        val running = engine.state.value.phase
        engine.snooze()
        assertEquals("a running level cannot be snoozed", running, engine.state.value.phase)
    }

    @Test
    fun `the last level can be snoozed too`() {
        val engine = engine()
        engine.goToLevel(2)
        engine.primaryAction()
        clock.advance(120_000L)
        engine.tick()

        engine.snooze()
        assertFalse(engine.state.value.isAlarming)

        clock.advance(120_000L)
        engine.tick()
        assertTrue(engine.state.value.isAlarming)
        assertNull((engine.state.value.phase as TimerPhase.LevelEnded).nextLevelIndex)
    }

    @Test
    fun `editing the structure during a snooze does not lose it`() {
        val engine = engineAtAlarm()
        engine.snooze()

        engine.setLevels(levels.map { it.copy(smallBlind = it.smallBlind * 2) })

        assertTrue(engine.state.value.isSnoozed)
        assertFalse(engine.state.value.isAlarming)
    }

    @Test
    fun `leaving the alarm clears the snooze countdown with it`() {
        val engine = engineAtAlarm()
        engine.snooze()
        engine.reset()
        assertNull(engine.state.value.snoozeRemainingMs)

        val second = engineAtAlarm()
        second.snooze()
        second.primaryAction()
        assertNull(second.state.value.snoozeRemainingMs)
    }

    @Test
    fun `skipping a level during a snooze starts that level, same as skipping mid-alarm`() {
        val engine = engineAtAlarm()
        engine.snooze()

        engine.nextLevel()

        val state = engine.state.value
        assertTrue("a snoozed alarm is still a live clock", state.isRunning)
        assertFalse(state.isSnoozed)
        assertEquals(2, state.displayLevelIndex)
    }

    @Test
    fun `a restored session comes back parked where it left off`() {
        val engine = engine()
        engine.restore(levelIndex = 1, remainingMs = 42_000L)

        assertEquals(TimerPhase.Ready(1, 42_000L), engine.state.value.phase)
        assertFalse("restoring must never start the clock on its own", engine.state.value.isRunning)
    }

    @Test
    fun `a session saved while a level had ended restores parked on the next level, not the finished one`() {
        val engine = engine()

        // Simulates what TimerService persists for a LevelEnded save: the target is the level after
        // the one that just ended, and the recorded remainder is deliberately stale — a couple of
        // seconds left on the old, already-finished level — to prove it is never trusted.
        engine.restore(levelIndex = 1, remainingMs = 2_000L, wasLevelEnded = true)

        val state = engine.state.value
        assertEquals(
            "must park on the next level at its own full duration, not the stale remainder",
            TimerPhase.Ready(1, 300_000L),
            state.phase,
        )
        assertFalse("the alarm must never be resurrected", state.isRunning)
        assertFalse(state.isAlarming)
    }

    @Test
    fun `a restore is ignored while the clock is live`() {
        val engine = engine()
        engine.primaryAction()
        val phase = engine.state.value.phase

        engine.restore(levelIndex = 2, remainingMs = 1_000L)

        assertEquals(phase, engine.state.value.phase)
    }

    @Test
    fun `progress runs from zero to one across a level`() {
        val engine = engine()
        engine.primaryAction()
        assertEquals(0f, engine.state.value.progress, 0.001f)

        clock.advance(300_000L)
        engine.tick()
        assertEquals(0.5f, engine.state.value.progress, 0.001f)
    }

    @Test
    fun `undo after reset restores the exact prior running phase`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(90_000L)
        engine.tick()
        val before = engine.state.value.phase
        val remainingBefore = engine.state.value.remainingMs

        engine.reset()
        assertEquals(TimerPhase.Ready(0, 600_000L), engine.state.value.phase)

        engine.undo()

        assertEquals("the exact prior phase, deadline included, must come back", before, engine.state.value.phase)
        assertEquals(remainingBefore, engine.state.value.remainingMs)
    }

    @Test
    fun `undo after a skip restores the previous level and its remaining time`() {
        val engine = engine()
        engine.goToLevel(1)
        engine.primaryAction()
        clock.advance(60_000L)
        engine.tick()
        val before = engine.state.value.phase
        val remainingBefore = engine.state.value.remainingMs

        engine.nextLevel()
        assertEquals(2, engine.state.value.displayLevelIndex)

        engine.undo()

        assertEquals(before, engine.state.value.phase)
        assertEquals(remainingBefore, engine.state.value.remainingMs)
        assertEquals(1, engine.state.value.displayLevelIndex)
    }

    @Test
    fun `undo does nothing when nothing is snapshotted`() {
        val engine = engine()
        engine.primaryAction()
        val phase = engine.state.value.phase

        engine.undo()

        assertEquals(phase, engine.state.value.phase)
    }

    @Test
    fun `undo is ignored once its window has passed`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(60_000L)
        engine.tick()

        engine.reset()
        val afterReset = engine.state.value.phase

        // Comfortably past any reasonable undo window (which is only meant to outlive a snackbar).
        clock.advance(60_000L)
        engine.undo()

        assertEquals("a stale undo must be a no-op", afterReset, engine.state.value.phase)
    }

    @Test
    fun `a second destructive action replaces the snapshot so undo only reverts the most recent one`() {
        val engine = engine()
        engine.primaryAction()
        clock.advance(60_000L)
        engine.tick()

        engine.nextLevel()
        val afterFirstSkip = engine.state.value.phase

        engine.previousLevel()

        engine.undo()

        assertEquals(
            "undo must reverse only the second skip, not resurrect the state from before the first",
            afterFirstSkip,
            engine.state.value.phase,
        )
    }

    @Test
    fun `a no-op action after a destructive one does not clobber its snapshot`() {
        val engine = engine()
        engine.goToLevel(2)
        engine.primaryAction()
        clock.advance(30_000L)
        engine.tick()
        val before = engine.state.value.phase
        val remainingBefore = engine.state.value.remainingMs

        engine.reset()
        // Already at level 0: stepping back is a no-op and must not overwrite the reset's snapshot.
        engine.previousLevel()

        engine.undo()

        assertEquals(before, engine.state.value.phase)
        assertEquals(remainingBefore, engine.state.value.remainingMs)
    }
}
