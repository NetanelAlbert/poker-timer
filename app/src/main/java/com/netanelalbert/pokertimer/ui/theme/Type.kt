package com.netanelalbert.pokertimer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material3 defaults, with displayLarge overridden for a big, clock-style
// countdown readout (the main thing this app's UI needs to get right).
val Typography = Typography(
    displayLarge = Typography().displayLarge.copy(
        fontSize = 96.sp,
        lineHeight = 104.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
    ),
)
