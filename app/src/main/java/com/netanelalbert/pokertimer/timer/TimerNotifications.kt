package com.netanelalbert.pokertimer.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.netanelalbert.pokertimer.MainActivity
import com.netanelalbert.pokertimer.R
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerState

/** Builds the ongoing notification that mirrors the clock while the app is in the background. */
object TimerNotifications {

    const val CHANNEL_ID = "poker_timer_clock"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.timer_channel_name),
            // Low importance on purpose: this notification is a readout, not an alert. The
            // blinds-up alarm is played on the alarm stream by AlarmPlayer, so the channel must
            // never make a sound of its own or the two would overlap.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.timer_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, state: TimerState): Notification {
        val level = state.currentLevel
        val levelNumber = state.displayLevelIndex + 1
        val blinds = level?.let {
            context.getString(R.string.blinds_format, it.smallBlind, it.bigBlind)
        }.orEmpty()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentIntent(contentIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        when (val phase = state.phase) {
            is TimerPhase.Running -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_running, levelNumber, blinds))
                    .setContentText(context.getString(R.string.notification_time_left))
                    // Let the system tick the countdown for us, so the notification stays live
                    // without us posting an update every second.
                    .setWhen(System.currentTimeMillis() + state.remainingMs)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .addAction(0, context.getString(R.string.pause), serviceIntent(context, TimerService.ACTION_PRIMARY, 1))
                    .addAction(0, context.getString(R.string.next_level), serviceIntent(context, TimerService.ACTION_NEXT, 2))
            }

            is TimerPhase.LevelEnded -> {
                val action = if (phase.nextLevelIndex != null) {
                    context.getString(R.string.start_level, levelNumber)
                } else {
                    context.getString(R.string.tournament_complete)
                }
                builder
                    .setContentTitle(
                        if (state.isSnoozed) {
                            context.getString(R.string.notification_snoozed, blinds)
                        } else {
                            context.getString(R.string.blinds_up)
                        }
                    )
                    .setContentText(
                        if (phase.nextLevelIndex != null) {
                            context.getString(R.string.notification_tap_to_start, blinds, levelNumber)
                        } else {
                            context.getString(R.string.tournament_complete)
                        }
                    )
                    .addAction(0, action, serviceIntent(context, TimerService.ACTION_PRIMARY, 1))

                if (state.isSnoozed) {
                    // Count the snooze down in the notification so it is obvious the alarm is
                    // coming back, and roughly when.
                    state.snoozeRemainingMs?.let { remaining ->
                        builder
                            .setWhen(System.currentTimeMillis() + remaining)
                            .setUsesChronometer(true)
                            .setChronometerCountDown(true)
                    }
                } else if (phase.nextLevelIndex != null) {
                    builder.addAction(
                        0,
                        context.getString(R.string.snooze),
                        serviceIntent(context, TimerService.ACTION_SNOOZE, 4),
                    )
                }
            }

            is TimerPhase.Ready -> {
                builder
                    .setContentTitle(context.getString(R.string.notification_paused, levelNumber, blinds))
                    .setContentText(formatRemaining(state.remainingMs))
                    .addAction(0, context.getString(R.string.resume), serviceIntent(context, TimerService.ACTION_PRIMARY, 1))
                    .addAction(0, context.getString(R.string.reset), serviceIntent(context, TimerService.ACTION_RESET, 3))
            }

            TimerPhase.Finished -> {
                builder
                    .setContentTitle(context.getString(R.string.tournament_complete))
                    .setOngoing(false)
            }
        }
        return builder.build()
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TimerService::class.java).setAction(action)
        return PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
