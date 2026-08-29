package com.netanelalbert.pokertimer.model

import kotlinx.serialization.Serializable

/**
 * Everything the user can configure. Persisted as a single JSON blob in DataStore.
 *
 * A null sound uri means "use the device default alarm sound".
 */
@Serializable
data class TimerSettings(
    val levels: List<BlindLevel> = DEFAULT_LEVELS,
    val alarmSoundUri: String? = null,
    val warningSoundUri: String? = null,
    val chimeSoundUri: String? = null,
    val warningEnabled: Boolean = true,
    val warningLeadSeconds: Int = 30,
    val chimeEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val alarmVolume: Float = 1f,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        const val MIN_LEVEL_SECONDS = 5
        const val MAX_LEVEL_SECONDS = 6 * 60 * 60

        /** A conventional home-game structure: blinds roughly 1.5x per level, 20 minutes each. */
        val DEFAULT_LEVELS: List<BlindLevel> = listOf(
            25 to 50,
            50 to 100,
            75 to 150,
            100 to 200,
            150 to 300,
            200 to 400,
            300 to 600,
            400 to 800,
            500 to 1000,
            700 to 1400,
            1000 to 2000,
            1500 to 3000,
        ).map { (small, big) -> BlindLevel(small, big, durationSeconds = 20 * 60) }
    }
}
