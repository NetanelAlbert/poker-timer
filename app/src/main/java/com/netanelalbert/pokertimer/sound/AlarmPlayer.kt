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
import com.netanelalbert.pokertimer.R

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
        alarmPlayer = createPlayer(uri, SoundSlot.ALARM, volume, looping = true)
        if (vibrate) startVibration()
    }

    fun stopAlarm() {
        alarmPlayer?.releaseQuietly()
        alarmPlayer = null
        vibrator?.cancel()
    }

    /** Play a warning or start chime. A new one-shot cuts off any previous one-shot still playing. */
    fun playOneShot(uri: String?, slot: SoundSlot, volume: Float) {
        oneShotPlayer?.releaseQuietly()
        oneShotPlayer = createPlayer(uri, slot, volume, looping = false)?.also { player ->
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

    private fun createPlayer(
        uri: String?,
        slot: SoundSlot,
        volume: Float,
        looping: Boolean,
    ): MediaPlayer? {
        // Tried in order: what the user picked, then this slot's own default, then the device
        // tones as a last resort. A stored uri can stop working (the track was deleted, or the
        // permission to read it went away) and silence would be the worst outcome here.
        val candidates = listOfNotNull(
            uri?.takeIf { it.isNotBlank() }?.let(Uri::parse),
            defaultFor(slot),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (candidate in candidates) {
            // Constructed outside runCatching so a failure between here and `start()` still has a
            // handle to release — leaving a failed setDataSource/prepare unreleased leaks a native
            // MediaPlayer for every alarm, warning, chime and snooze re-ring for the rest of the
            // tournament.
            val player = MediaPlayer()
            val prepared = runCatching {
                player.apply {
                    setAudioAttributes(audioAttributes)
                    setDataSource(context, candidate)
                    isLooping = looping
                    setVolume(volume, volume)
                    prepare()
                    start()
                }
            }.getOrElse { error ->
                Log.w(TAG, "Could not play $candidate", error)
                player.releaseQuietly()
                null
            }
            if (prepared != null) return prepared
        }
        Log.w(TAG, "No playable sound found")
        return null
    }

    /**
     * The chime and the warning default to short bundled tones rather than a device ringtone.
     * Ringtones and alarm tones run for seconds and are built to be loop-ready, which is right for
     * the blinds-up alarm and wrong for a blip that only has to say "started" or "nearly up".
     */
    private fun defaultFor(slot: SoundSlot): Uri? = when (slot) {
        SoundSlot.ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        SoundSlot.WARNING -> rawUri(R.raw.warning)
        SoundSlot.CHIME -> rawUri(R.raw.chime)
    }

    private fun rawUri(resId: Int): Uri =
        Uri.parse("android.resource://${'$'}{context.packageName}/${'$'}resId")

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
