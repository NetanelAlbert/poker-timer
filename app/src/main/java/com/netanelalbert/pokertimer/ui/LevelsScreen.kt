package com.netanelalbert.pokertimer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.netanelalbert.pokertimer.R
import com.netanelalbert.pokertimer.model.BlindLevel
import com.netanelalbert.pokertimer.model.TimerSettings

/**
 * Lets the user add, remove, reorder, and edit the tournament's blind levels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelsScreen(viewModel: PokerTimerViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val ready by viewModel.ready.collectAsState()
    val levels = settings.levels

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.levels_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val newLevel = if (levels.isEmpty()) {
                        BlindLevel(smallBlind = 25, bigBlind = 50, durationSeconds = 20 * 60)
                    } else {
                        val last = levels.last()
                        BlindLevel(
                            smallBlind = last.bigBlind,
                            bigBlind = last.bigBlind * 2,
                            durationSeconds = last.durationSeconds,
                        )
                    }
                    viewModel.setLevels(levels + newLevel)
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_level))
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxWidth().padding(paddingValues)) {
            OutlinedButton(
                onClick = {
                    val first = levels.firstOrNull() ?: return@OutlinedButton
                    viewModel.setLevels(levels.map { it.copy(durationSeconds = first.durationSeconds) })
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = levels.isNotEmpty(),
            ) {
                Text(stringResource(R.string.apply_duration_to_all))
            }

            if (!ready) return@Column
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(levels) { index, level ->
                    LevelRow(
                        index = index,
                        level = level,
                        isFirst = index == 0,
                        isLast = index == levels.lastIndex,
                        canDelete = levels.size > 1,
                        onCommit = { updated ->
                            viewModel.setLevels(levels.toMutableList().also { it[index] = updated })
                        },
                        onMoveUp = {
                            if (index > 0) {
                                viewModel.setLevels(
                                    levels.toMutableList().also {
                                        val tmp = it[index - 1]
                                        it[index - 1] = it[index]
                                        it[index] = tmp
                                    },
                                )
                            }
                        },
                        onMoveDown = {
                            if (index < levels.lastIndex) {
                                viewModel.setLevels(
                                    levels.toMutableList().also {
                                        val tmp = it[index + 1]
                                        it[index + 1] = it[index]
                                        it[index] = tmp
                                    },
                                )
                            }
                        },
                        onDelete = {
                            viewModel.setLevels(levels.toMutableList().also { it.removeAt(index) })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelRow(
    index: Int,
    level: BlindLevel,
    isFirst: Boolean,
    isLast: Boolean,
    canDelete: Boolean,
    onCommit: (BlindLevel) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    // Seeded per row, not re-keyed on `level`: an edit round-trips through DataStore and comes
    // back as a new level object, and re-keying on it would reset the field under the user's
    // fingers mid-number. Instead each field syncs down only when the stored value genuinely
    // diverges from what is typed — an external change such as "reset to defaults".
    var smallBlindText by remember(index) { mutableStateOf(level.smallBlind.toString()) }
    var bigBlindText by remember(index) { mutableStateOf(level.bigBlind.toString()) }
    var durationText by remember(index) { mutableStateOf((level.durationSeconds / 60).toString()) }

    LaunchedEffect(level.smallBlind) {
        if (smallBlindText.toIntOrNull() != level.smallBlind) {
            smallBlindText = level.smallBlind.toString()
        }
    }
    LaunchedEffect(level.bigBlind) {
        if (bigBlindText.toIntOrNull() != level.bigBlind) bigBlindText = level.bigBlind.toString()
    }
    LaunchedEffect(level.durationSeconds) {
        val minutes = level.durationSeconds / 60
        if (durationText.toIntOrNull() != minutes) durationText = minutes.toString()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.level_number, index + 1),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = smallBlindText,
                    onValueChange = { text ->
                        smallBlindText = text
                        text.toIntOrNull()?.let { parsed ->
                            onCommit(level.copy(smallBlind = parsed))
                        }
                    },
                    label = { Text(stringResource(R.string.small_blind)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = bigBlindText,
                    onValueChange = { text ->
                        bigBlindText = text
                        text.toIntOrNull()?.let { parsed ->
                            onCommit(level.copy(bigBlind = parsed))
                        }
                    },
                    label = { Text(stringResource(R.string.big_blind)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { text ->
                        durationText = text
                        text.toIntOrNull()?.let { minutes ->
                            val clampedSeconds = (minutes * 60).coerceIn(
                                TimerSettings.MIN_LEVEL_SECONDS,
                                TimerSettings.MAX_LEVEL_SECONDS,
                            )
                            onCommit(level.copy(durationSeconds = clampedSeconds))
                        }
                    },
                    label = { Text(stringResource(R.string.duration)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onMoveUp, enabled = !isFirst) {
                    Icon(
                        Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.move_up),
                    )
                }
                IconButton(onClick = onMoveDown, enabled = !isLast) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.move_down),
                    )
                }
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_level),
                    )
                }
            }
        }
    }
}
