package com.netanelalbert.pokertimer.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Plays the three tournament sounds.
 *
 * Uses [MediaPlayer] rather than `Ringtone` because the blinds-up alarm has to loop, and
 * `Ringtone.isLooping` only exists from API 28. Everything is tagged [AudioAttributes.USAGE_ALARM]
 * so it comes out of the alarm stream — a poker timer that goes unheard because someone's phone is
 * on vibrate is useless.
 */
class AlarmPlayer(private val context: Context) {

    private var alarmPlayer: MediaPlayer? = null
    private var oneShotPlayer: MediaPlayer? = null

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Start the looping blinds-up alarm. Idempotent: calling it while it rings does nothing. */
    fun startAlarm(uri: String?, volume: Float, vibrate: Boolean) {
        if (alarmPlayer != null) return
        alarmPlayer = createPlayer(uri, volume, looping = true)
        if (vibrate) startVibration()
    }

    fun stopAlarm() {
        alarmPlayer?.releaseQuietly()
        alarmPlayer = null
        vibrator?.cancel()
    }

    /** Play a warning or start chime. A new one-shot cuts off any previous one-shot still playing. */
    fun playOneShot(uri: String?, volume: Float) {
        oneShotPlayer?.releaseQuietly()
        oneShotPlayer = createPlayer(uri, volume, looping = false)?.also { player ->
            player.setOnCompletionListener {
                it.releaseQuietly()
                if (oneShotPlayer === it) oneShotPlayer = null
            }
        }
    }

    fun release() {
        stopAlarm()
        oneShotPlayer?.releaseQuietly()
        oneShotPlayer = null
    }

    private fun createPlayer(uri: String?, volume: Float, looping: Boolean): MediaPlayer? {
        // Fall back to the device alarm tone when the stored uri is unusable — the user may have
        // picked a track that has since been deleted, and silence would be the worst outcome here.
        val candidates = listOfNotNull(
            uri?.takeIf { it.isNotBlank() }?.let(Uri::parse),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (candidate in candidates) {
            val player = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(context, candidate)
                    isLooping = looping
                    setVolume(volume, volume)
                    prepare()
                    start()
                }
            }.getOrElse { error ->
                Log.w(TAG, "Could not play $candidate", error)
                null
            }
            if (player != null) return player
        }
        Log.w(TAG, "No playable sound found")
        return null
    }

    private fun startVibration() {
        val vibrator = vibrator ?: return
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 400, 300, 400, 900)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
    }

    private fun MediaPlayer.releaseQuietly() {
        runCatching {
            if (isPlaying) stop()
        }
        runCatching { release() }
    }

    private companion object {
        const val TAG = "AlarmPlayer"
    }
}
