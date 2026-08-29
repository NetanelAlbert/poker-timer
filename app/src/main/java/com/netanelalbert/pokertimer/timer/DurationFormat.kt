package com.netanelalbert.pokertimer.timer

import java.util.Locale

/** Formats a remaining duration as `m:ss`, or `h:mm:ss` once a level is an hour or longer. */
fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
