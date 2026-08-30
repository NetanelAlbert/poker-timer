package com.netanelalbert.pokertimer.ui

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.netanelalbert.pokertimer.R
import com.netanelalbert.pokertimer.sound.SoundSlot
import com.netanelalbert.pokertimer.model.TimerSettings
import com.netanelalbert.pokertimer.timer.formatRemaining
import kotlin.math.roundToInt

/**
 * Lets the user configure sounds, alerts, playback behavior, and reset to defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: PokerTimerViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionsNonce by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionsNonce++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(text = stringResource(R.string.settings_section_sounds))

            SoundRow(
                label = stringResource(R.string.sound_alarm),
                currentUri = settings.alarmSoundUri,
                onPicked = { uri -> viewModel.updateSettings { it.copy(alarmSoundUri = uri) } },
                onPreview = { viewModel.previewSound(settings.alarmSoundUri, SoundSlot.ALARM) },
                defaultLabel = stringResource(R.string.sound_default),
            )
            SoundRow(
                label = stringResource(R.string.sound_warning),
                currentUri = settings.warningSoundUri,
                onPicked = { uri -> viewModel.updateSettings { it.copy(warningSoundUri = uri) } },
                onPreview = { viewModel.previewSound(settings.warningSoundUri, SoundSlot.WARNING) },
                defaultLabel = stringResource(R.string.sound_built_in),
            )
            SoundRow(
                label = stringResource(R.string.sound_chime),
                currentUri = settings.chimeSoundUri,
                onPicked = { uri -> viewModel.updateSettings { it.copy(chimeSoundUri = uri) } },
                onPreview = { viewModel.previewSound(settings.chimeSoundUri, SoundSlot.CHIME) },
                defaultLabel = stringResource(R.string.sound_built_in),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(text = stringResource(R.string.settings_section_alerts))

            SettingsSwitchRow(
                title = stringResource(R.string.warning_enabled),
                checked = settings.warningEnabled,
                onCheckedChange = { checked ->
                    viewModel.updateSettings { it.copy(warningEnabled = checked) }
                },
            )
            if (settings.warningEnabled) {
                var sliderValue by remember(settings.warningLeadSeconds) {
                    mutableStateOf(settings.warningLeadSeconds.toFloat())
                }
                Text(
                    text = stringResource(R.string.warning_lead_seconds),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.warning_lead_seconds_value, sliderValue.roundToInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val seconds = sliderValue.roundToInt()
                        viewModel.updateSettings { it.copy(warningLeadSeconds = seconds) }
                    },
                    valueRange = 5f..120f,
                    steps = 22,
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.chime_enabled),
                checked = settings.chimeEnabled,
                onCheckedChange = { checked ->
                    viewModel.updateSettings { it.copy(chimeEnabled = checked) }
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.vibrate_enabled),
                checked = settings.vibrateEnabled,
                onCheckedChange = { checked ->
                    viewModel.updateSettings { it.copy(vibrateEnabled = checked) }
                },
            )

            var snoozeValue by remember(settings.snoozeSeconds) {
                mutableStateOf(settings.snoozeSeconds.toFloat())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.snooze_length),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = formatRemaining(snoozeValue.roundToInt() * 1000L),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Slider(
                value = snoozeValue,
                onValueChange = { snoozeValue = it },
                onValueChangeFinished = {
                    viewModel.updateSettings { it.copy(snoozeSeconds = snoozeValue.roundToInt()) }
                },
                valueRange = TimerSettings.MIN_SNOOZE_SECONDS.toFloat()..TimerSettings.MAX_SNOOZE_SECONDS.toFloat(),
                steps = 19,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(text = stringResource(R.string.settings_section_playback))

            var volumeValue by remember(settings.alarmVolume) { mutableStateOf(settings.alarmVolume) }
            Text(
                text = stringResource(R.string.alarm_volume),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.alarm_volume_value, (volumeValue * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = volumeValue,
                onValueChange = { volumeValue = it },
                onValueChangeFinished = {
                    viewModel.updateSettings { it.copy(alarmVolume = volumeValue) }
                },
                valueRange = 0f..1f,
            )

            SettingsSwitchRow(
                title = stringResource(R.string.keep_screen_on),
                checked = settings.keepScreenOn,
                onCheckedChange = { checked ->
                    viewModel.updateSettings { it.copy(keepScreenOn = checked) }
                },
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SectionHeader(text = stringResource(R.string.settings_section_reset))

            OutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.reset_to_defaults))
            }

            val showNotificationsRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val showFullScreenRow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

            if (showNotificationsRow || showFullScreenRow) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SectionHeader(text = stringResource(R.string.settings_section_permissions))

                if (showNotificationsRow) {
                    val notificationsGranted = remember(permissionsNonce) {
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                    }
                    PermissionRow(
                        title = stringResource(R.string.permission_notifications),
                        granted = notificationsGranted,
                        summaryWhenMissing = stringResource(R.string.permission_notifications_summary),
                        onRequest = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }

                if (showFullScreenRow) {
                    val fullScreenGranted = remember(permissionsNonce) {
                        context.getSystemService(NotificationManager::class.java)
                            ?.canUseFullScreenIntent() == true
                    }
                    PermissionRow(
                        title = stringResource(R.string.permission_full_screen),
                        granted = fullScreenGranted,
                        summaryWhenMissing = stringResource(R.string.permission_full_screen_summary),
                        onRequest = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:${context.packageName}"),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_to_defaults)) },
            text = { Text(stringResource(R.string.reset_to_defaults_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetSettingsToDefaults()
                    },
                ) {
                    Text(stringResource(R.string.reset_to_defaults))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    summaryWhenMissing: String,
    onRequest: () -> Unit,
) {
    val rowModifier = if (granted) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clickable { onRequest() }
    }
    Column(modifier = rowModifier.padding(vertical = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        if (granted) {
            Text(
                text = stringResource(R.string.permission_granted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = summaryWhenMissing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SoundRow(
    label: String,
    currentUri: String?,
    onPicked: (String?) -> Unit,
    onPreview: () -> Unit,
    // What "nothing picked" means differs per sound: the alarm falls back to the device alarm
    // tone, the chime and warning to short bundled tones.
    defaultLabel: String,
) {
    val context = LocalContext.current
    val soundName = remember(currentUri, defaultLabel) {
        if (currentUri == null) {
            defaultLabel
        } else {
            runCatching {
                RingtoneManager.getRingtone(context, Uri.parse(currentUri))?.getTitle(context)
            }.getOrNull() ?: defaultLabel
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onPicked(uri?.toString())
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(text = soundName, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, label)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        // No "Silent" entry: a null uri already means "device default" here, so a
                        // silent pick would come back indistinguishable from it. The warning and
                        // chime have their own switches, and a silent blinds-up alarm would defeat
                        // the point of the app.
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        currentUri?.let {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it))
                        }
                    }
                    launcher.launch(intent)
                },
            ) {
                Text(stringResource(R.string.pick_sound))
            }
            IconButton(onClick = onPreview) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.preview))
            }
        }
    }
}
