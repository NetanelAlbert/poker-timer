package com.netanelalbert.pokertimer.timer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.netanelalbert.pokertimer.data.SettingsRepository
import com.netanelalbert.pokertimer.model.TimerEvent
import com.netanelalbert.pokertimer.model.TimerSettings
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerState
import com.netanelalbert.pokertimer.sound.AlarmPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the tournament clock alive while the app is backgrounded or the screen is off — which is
 * the normal case for a timer sitting on a poker table.
 *
 * The service does not own the clock (see [TimerController]); it drives it. Its jobs are: tick the
 * engine, hold a wake lock while the clock is live so the alarm actually fires with the screen off,
 * play sounds, mirror the state into the ongoing notification, and shut itself down once the clock
 * is no longer live.
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engine get() = TimerController.engine

    private lateinit var repository: SettingsRepository
    private lateinit var player: AlarmPlayer

    private val settings = MutableStateFlow(TimerSettings())
    private var wakeLock: PowerManager.WakeLock? = null
    private var startedForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository.get(this)
        player = AlarmPlayer(this)
        TimerNotifications.ensureChannel(this)

        scope.launch {
            repository.settings.collect { latest ->
                settings.value = latest
                engine.applySettings(latest)
            }
        }
        scope.launch { runClock() }
        scope.launch { observeState() }
        scope.launch { observeEvents() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground has to happen promptly on every start, including the ones triggered by
        // notification action buttons, or the system kills us for taking too long.
        promoteToForeground(engine.state.value)

        when (intent?.action) {
            ACTION_PRIMARY -> engine.primaryAction()
            ACTION_SNOOZE -> engine.snooze()
            ACTION_NEXT -> engine.nextLevel()
            ACTION_RESET -> engine.reset()
        }
        stopIfIdle(engine.state.value)
        // Not sticky: a restarted service would come back with an empty engine and no way to know
        // where the tournament had got to, which is worse than simply not coming back.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        player.release()
        cancelAlarmNotification()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Ticks often enough for a smooth readout. The engine derives everything from a monotonic
     * deadline, so an irregular or delayed tick costs nothing but a late repaint.
     */
    private suspend fun runClock() {
        var lastSessionSave = 0L
        while (scope.isActive) {
            engine.tick()
            val state = engine.state.value
            val now = System.currentTimeMillis()
            if (state.isRunning && now - lastSessionSave > SESSION_SAVE_INTERVAL_MS) {
                lastSessionSave = now
                repository.saveSession(state.displayLevelIndex, state.remainingMs)
            }
            delay(TICK_INTERVAL_MS)
        }
    }

    private suspend fun observeState() {
        // The notification carries a self-ticking chronometer, so it only has to be rebuilt when
        // the phase or the level changes — not on every tick.
        engine.state
            .distinctUntilChangedBy { NotificationKey(it.phase, it.displayLevelIndex) }
            .collect { state ->
                syncAlarm(state)
                syncWakeLock(state)
                if (startedForeground) {
                    postNotification(state)
                    syncAlarmNotification(state)
                    stopIfIdle(state)
                }
            }
    }

    private suspend fun observeEvents() {
        engine.events.collect { event ->
            val current = settings.value
            when (event) {
                TimerEvent.WARNING ->
                    player.playOneShot(current.warningSoundUri, current.alarmVolume * WARNING_VOLUME_SCALE)
                TimerEvent.LEVEL_STARTED ->
                    player.playOneShot(current.chimeSoundUri, current.alarmVolume * CHIME_VOLUME_SCALE)
            }
        }
    }

    /**
     * Put the blinds-up alert on screen for as long as the alarm is sounding, and take it away the
     * moment it is acknowledged or snoozed. This is what makes the alarm visible: the ongoing clock
     * notification is deliberately silent and low priority, so on its own it left the sound with no
     * apparent source.
     */
    private fun syncAlarmNotification(state: TimerState) {
        runCatching {
            val manager = NotificationManagerCompat.from(this)
            if (state.isAlarming) {
                manager.notify(
                    TimerNotifications.ALARM_NOTIFICATION_ID,
                    TimerNotifications.buildAlarm(this, state),
                )
            } else {
                manager.cancel(TimerNotifications.ALARM_NOTIFICATION_ID)
            }
        }
    }

    private fun cancelAlarmNotification() {
        runCatching { NotificationManagerCompat.from(this).cancel(TimerNotifications.ALARM_NOTIFICATION_ID) }
    }

    private fun syncAlarm(state: TimerState) {
        if (state.isAlarming) {
            val current = settings.value
            player.startAlarm(current.alarmSoundUri, current.alarmVolume, current.vibrateEnabled)
        } else {
            player.stopAlarm()
        }
    }

    /**
     * A foreground service does not by itself keep the CPU awake, so without this the countdown
     * would stall the moment the screen went off and the blinds would go up late.
     */
    private fun syncWakeLock(state: TimerState) {
        if (state.needsService) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun promoteToForeground(state: TimerState) {
        // specialUse only exists from API 34; on older platforms an untyped foreground service is
        // both correct and what the platform expects.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            TimerNotifications.NOTIFICATION_ID,
            TimerNotifications.build(this, state),
            type,
        )
        startedForeground = true
    }

    private fun postNotification(state: TimerState) {
        // Posting without POST_NOTIFICATIONS throws on API 33+; the clock itself must survive that.
        runCatching {
            NotificationManagerCompat.from(this).notify(
                TimerNotifications.NOTIFICATION_ID,
                TimerNotifications.build(this, state),
            )
        }
    }

    private fun stopIfIdle(state: TimerState) {
        if (state.needsService) return
        scope.launch { repository.saveSession(state.displayLevelIndex, state.remainingMs) }
        player.stopAlarm()
        cancelAlarmNotification()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        startedForeground = false
        stopSelf()
    }

    private data class NotificationKey(val phase: TimerPhase, val levelIndex: Int)

    companion object {
        const val ACTION_PRIMARY = "com.netanelalbert.pokertimer.PRIMARY"
        const val ACTION_SNOOZE = "com.netanelalbert.pokertimer.SNOOZE"
        const val ACTION_NEXT = "com.netanelalbert.pokertimer.NEXT"
        const val ACTION_RESET = "com.netanelalbert.pokertimer.RESET"

        private const val TICK_INTERVAL_MS = 200L
        private const val SESSION_SAVE_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TAG = "PokerTimer:clock"

        /** A safety net, not a budget: no single blind level should ever run this long. */
        private const val WAKE_LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000

        private const val WARNING_VOLUME_SCALE = 0.7f
        private const val CHIME_VOLUME_SCALE = 0.6f
    }
}
