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
    ) = TimerEngine { clock.now }.apply {
        applySettings(
            TimerSettings(
                levels = levels,
                warningEnabled = warningEnabled,
                warningLeadSeconds = warningLeadSeconds,
                chimeEnabled = chimeEnabled,
            )
        )
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
    fun `a restored session comes back parked where it left off`() {
        val engine = engine()
        engine.restore(levelIndex = 1, remainingMs = 42_000L)

        assertEquals(TimerPhase.Ready(1, 42_000L), engine.state.value.phase)
        assertFalse("restoring must never start the clock on its own", engine.state.value.isRunning)
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
}
