@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.muunkk.connectanytime

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fallback brand scheme for Android 10 and 11, built from the launcher icon's slate
// blue (#284B63). Android 12 and later use the wallpaper's dynamic colors instead.
private val BrandLight = lightColorScheme(
    primary = Color(0xFF2B5F7F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9E6FF), onPrimaryContainer = Color(0xFF0E4A66),
    secondary = Color(0xFF50606E), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E4F5), onSecondaryContainer = Color(0xFF384956),
    tertiary = Color(0xFF3F6375), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC3E8FD), onTertiaryContainer = Color(0xFF264B5C),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF7F9FD), onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FD), onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDDE3EA), onSurfaceVariant = Color(0xFF41484D),
    outline = Color(0xFF71787E), outlineVariant = Color(0xFFC1C7CE),
    inverseSurface = Color(0xFF2D3135), inverseOnSurface = Color(0xFFEEF1F5),
    inversePrimary = Color(0xFF98CCF0), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9FD), surfaceDim = Color(0xFFD7DADF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEBEEF3), surfaceContainerHigh = Color(0xFFE5E8ED),
    surfaceContainerHighest = Color(0xFFE0E3E7),
)
private val BrandDark = darkColorScheme(
    primary = Color(0xFF98CCF0), onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0E4A66), onPrimaryContainer = Color(0xFFC9E6FF),
    secondary = Color(0xFFB7C9D9), onSecondary = Color(0xFF22323F),
    secondaryContainer = Color(0xFF384956), onSecondaryContainer = Color(0xFFD3E4F5),
    tertiary = Color(0xFFA7CCE0), onTertiary = Color(0xFF0C3445),
    tertiaryContainer = Color(0xFF264B5C), onTertiaryContainer = Color(0xFFC3E8FD),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101417), onBackground = Color(0xFFE0E3E7),
    surface = Color(0xFF101417), onSurface = Color(0xFFE0E3E7),
    surfaceVariant = Color(0xFF41484D), onSurfaceVariant = Color(0xFFC1C7CE),
    outline = Color(0xFF8B9198), outlineVariant = Color(0xFF41484D),
    inverseSurface = Color(0xFFE0E3E7), inverseOnSurface = Color(0xFF2D3135),
    inversePrimary = Color(0xFF2B5F7F), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A3E), surfaceDim = Color(0xFF101417),
    surfaceContainerLowest = Color(0xFF0B0F12), surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024), surfaceContainerHigh = Color(0xFF272A2E),
    surfaceContainerHighest = Color(0xFF313539),
)

// The status headline is the one loud element; everything else stays at the defaults.
private val AppTypography = Typography().run {
    copy(
        displaySmallEmphasized = displaySmallEmphasized.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmallEmphasized = headlineSmallEmphasized.copy(fontWeight = FontWeight.SemiBold),
        titleMediumEmphasized = titleMediumEmphasized.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * True when the user removed animations (Settings › Accessibility › Remove animations,
 * or an animator duration scale of 0). Finite Compose animations already follow the
 * scale; this also replaces the endless loading indicator and shape morphs with
 * static states.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
private fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val read = { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

@Composable
fun ConnectTheme(dark: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDark
        else -> BrandLight
    }
    val reduced = reducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = if (reduced) MotionScheme.standard() else MotionScheme.expressive(),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
