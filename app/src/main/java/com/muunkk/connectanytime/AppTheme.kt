package com.muunkk.connectanytime

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun ConnectTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(primary = Color(0xFFA0CBE5), background = Color(0xFF101418))
    else lightColorScheme(primary = Color(0xFF284B63), background = Color(0xFFF9F8FF))
    MaterialTheme(colorScheme = colors, content = content)
}
