package com.netanelalbert.pokertimer.model

import kotlinx.serialization.Serializable

/** A single blind level of the tournament structure. */
@Serializable
data class BlindLevel(
    val smallBlind: Int,
    val bigBlind: Int,
    val durationSeconds: Int,
) {
    val durationMs: Long get() = durationSeconds * 1000L
}
