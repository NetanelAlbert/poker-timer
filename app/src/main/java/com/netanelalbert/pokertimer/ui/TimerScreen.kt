package com.netanelalbert.pokertimer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netanelalbert.pokertimer.R
import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerState
import com.netanelalbert.pokertimer.timer.TimerController
import com.netanelalbert.pokertimer.timer.formatRemaining

/**
 * The tournament clock itself — designed to be read from the far side of a table: one enormous
 * countdown, the blinds under it, and one button that does the obvious next thing.
 */
@Composable
fun TimerScreen(
    viewModel: PokerTimerViewModel,
    onOpenLevels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.timerState.collectAsState()

    // When a level runs out the whole screen goes red and pulses, so it is impossible to miss
    // across a noisy room even before anyone registers the alarm.
    val alarming = state.isAlarming
    val pulse = rememberInfiniteTransition(label = "alarm-pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "alarm-pulse-alpha",
    )
    val background by animateColorAsState(
        targetValue = if (alarming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.background,
        animationSpec = tween(250),
        label = "background",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (alarming) background.copy(alpha = pulseAlpha) else background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeaderRow(state = state, onOpenLevels = onOpenLevels, onOpenSettings = onOpenSettings)

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                ClockFace(state = state, alarming = alarming)
            }

            Controls(
                state = state,
                onPrimary = { TimerController.primaryAction(context) },
                onPrevious = { TimerController.previousLevel(context) },
                onNext = { TimerController.nextLevel(context) },
                onReset = { TimerController.reset(context) },
            )
        }
    }
}

@Composable
private fun HeaderRow(state: TimerState, onOpenLevels: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.level_of, state.displayLevelIndex + 1, state.levels.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenLevels) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.levels_title))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }
}

@Composable
private fun ClockFace(state: TimerState, alarming: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.phase is TimerPhase.Finished) {
            Text(
                text = stringResource(R.string.tournament_complete),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        if (alarming) {
            Text(
                text = stringResource(R.string.blinds_up),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.height(12.dp))
        }

        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.size(300.dp),
                strokeWidth = 10.dp,
                color = if (alarming) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatRemaining(state.remainingMs),
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                )
                BlindsText(
                    level = state.currentLevel,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 40.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val next = state.nextLevel
        if (next != null) {
            Text(
                text = stringResource(R.string.next_blinds).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BlindsText(
                level = next,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 22.sp,
            )
        }
    }
}

@Composable
private fun BlindsText(level: BlindLevel?, color: Color, fontSize: TextUnit) {
    if (level == null) return
    Text(
        text = stringResource(R.string.blinds_format, level.smallBlind, level.bigBlind),
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun Controls(
    state: TimerState,
    onPrimary: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReset: () -> Unit,
) {
    val phase = state.phase
    val primaryLabel = when {
        phase is TimerPhase.LevelEnded && phase.nextLevelIndex != null ->
            stringResource(R.string.start_level, phase.nextLevelIndex + 1)
        phase is TimerPhase.LevelEnded -> stringResource(R.string.tournament_complete)
        phase is TimerPhase.Running -> stringResource(R.string.pause)
        phase is TimerPhase.Finished -> stringResource(R.string.new_tournament)
        state.isAtLevelStart -> stringResource(R.string.start)
        else -> stringResource(R.string.resume)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(text = primaryLabel, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = state.displayLevelIndex > 0) {
                Icon(
                    Icons.Default.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.previous_level),
                )
            }
            TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
            IconButton(
                onClick = onNext,
                enabled = state.displayLevelIndex < state.levels.lastIndex,
            ) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.next_level),
                )
            }
        }
    }
}
