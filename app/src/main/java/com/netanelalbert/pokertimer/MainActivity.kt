package com.netanelalbert.pokertimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netanelalbert.pokertimer.ui.PokerTimerNavHost
import com.netanelalbert.pokertimer.ui.PokerTimerViewModel
import com.netanelalbert.pokertimer.ui.theme.PokerTimerTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* advisory only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            PokerTimerTheme {
                val viewModel: PokerTimerViewModel = viewModel()
                val settings by viewModel.settings.collectAsState()

                // The clock is meant to be readable from across the table for a whole level, so
                // honour the setting by holding the screen awake while the app is in front.
                val view = LocalView.current
                view.keepScreenOn = settings.keepScreenOn

                PokerTimerNavHost(viewModel = viewModel)
            }
        }
    }

    /**
     * Without this the ongoing clock notification is silently dropped on API 33+. It is not
     * required for the alarm itself to sound, so a refusal is not treated as fatal.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
