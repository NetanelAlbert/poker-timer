package com.netanelalbert.pokertimer.ui

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netanelalbert.pokertimer.R
import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerPhase
import com.netanelalbert.pokertimer.model.TimerState
import com.netanelalbert.pokertimer.timer.TimerController
import com.netanelalbert.pokertimer.timer.formatRemaining
import kotlinx.coroutines.launch

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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.undo)

    fun showUndoSnackbar(message: String) {
        scope.launch {
            // A second destructive tap while the first snackbar is still up should offer undo for
            // the new action, not queue behind the old one.
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                TimerController.undo(context)
            }
        }
    }

    val onReset: () -> Unit = {
        TimerController.reset(context)
        showUndoSnackbar(context.getString(R.string.reset_undone_message))
    }
    val onNext: () -> Unit = {
        TimerController.nextLevel(context)
        showUndoSnackbar(
            context.getString(
                R.string.skip_undone_message,
                TimerController.state.value.displayLevelIndex + 1,
            )
        )
    }
    val onPrevious: () -> Unit = {
        TimerController.previousLevel(context)
        showUndoSnackbar(
            context.getString(
                R.string.skip_undone_message,
                TimerController.state.value.displayLevelIndex + 1,
            )
        )
    }

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

    // The manifest handles orientation changes itself (no activity recreation), so this has to
    // reflow on every rotation rather than relying on separate layout resources.
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // The Scaffold only exists to host the undo snackbar. Its default container would paint over
    // the alarm's pulsing background below, so it must stay transparent, and its default inset
    // handling is left off (0-padding) since the Column already applies systemBarsPadding itself.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
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

            if (isLandscape) {
                // A phone propped on the table in landscape has very little height to give: put
                // the clock and the controls side by side instead of stacking them, so the clock
                // — the one thing that matters — gets the full height of the screen to work with
                // instead of whatever the buttons below it leave over.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClockFace(state = state, alarming = alarming)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Controls(
                            state = state,
                            onPrimary = { TimerController.primaryAction(context) },
                            onSnooze = { TimerController.snooze(context) },
                            onPrevious = onPrevious,
                            onNext = onNext,
                            onReset = onReset,
                        )
                    }
                }
            } else {
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
                    onSnooze = { TimerController.snooze(context) },
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onReset = onReset,
                )
            }
        }
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
    if (state.phase is TimerPhase.Finished) {
        Text(
            text = stringResource(R.string.tournament_complete),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        return
    }

    // A paused clock parked mid-level and a running one differ only in whether the digits are
    // moving — invisible at a glance from across a table — so the ring color itself has to carry
    // "is this actually counting down". A snoozed clock gets the error color instead: it looks
    // parked at 0% just like a level that hasn't started, but a tap is still owed.
    val ringColor = when {
        alarming -> MaterialTheme.colorScheme.onError
        state.isSnoozed -> MaterialTheme.colorScheme.error
        state.isRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val paused = !state.isRunning && !state.isAtLevelStart && !alarming && !state.isSnoozed

    // The ring's hard-coded 300dp only ever fit a tall portrait screen. Deriving it from the
    // smaller of the two available dimensions lets it fill a short landscape pane instead of
    // clipping against it, and the countdown font follows the same measurement so it never
    // outgrows the ring that surrounds it.
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val ringSize = (minOf(maxWidth, maxHeight) * 0.8f).coerceIn(160.dp, 320.dp)
        val countdownFontSize = (ringSize.value * 0.25f).sp
        val blindsFontSize = (ringSize.value * 0.13f).sp

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (alarming) {
                Text(
                    text = stringResource(R.string.blinds_up),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onError,
                )
                Spacer(Modifier.height(12.dp))
            } else if (state.isSnoozed) {
                // The blinds have already gone up; the alarm is only quiet for the moment. Say so,
                // and show when it comes back, so a snooze never looks like the level simply ended.
                Text(
                    text = stringResource(R.string.snoozed).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(
                        R.string.snooze_ringing_in,
                        formatRemaining(state.snoozeRemainingMs ?: 0L),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            } else if (paused) {
                Text(
                    text = stringResource(R.string.paused).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(ringSize),
                    strokeWidth = 10.dp,
                    color = ringColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatRemaining(state.remainingMs),
                        style = MaterialTheme.typography.displayLarge.copy(
                            fontSize = countdownFontSize,
                            lineHeight = TextUnit.Unspecified,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BlindsText(
                        level = state.currentLevel,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = blindsFontSize,
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
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Controls(
    state: TimerState,
    onPrimary: () -> Unit,
    onSnooze: () -> Unit,
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
            Text(
                text = primaryLabel,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.isAlarming) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(text = stringResource(R.string.snooze), fontSize = 18.sp)
            }
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
