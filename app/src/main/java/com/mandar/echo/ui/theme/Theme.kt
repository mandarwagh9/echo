package com.mandar.echo.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Strictly monochrome. No hue anywhere — hierarchy comes from weight, scale and
 * the opacity of hairline rules, which is what makes the app read as designed
 * rather than merely "dark mode".
 *
 * Every value here that carries *text* clears WCAG AA (4.5:1) against the surface
 * it sits on. [faint] used to be #A3A3A3 on white (2.35:1) and #5E5E5E on black
 * (3.34:1) — below the bar in both themes, and it is the colour of every section
 * label, timestamp and stat caption in the app. #757575 happens to clear AA in
 * both directions (4.61:1 on white, 4.56:1 on black), so one grey serves both.
 *
 * [hairline] is deliberately *not* held to that bar: it draws rules and inactive
 * borders, never type. Anything that reaches for it to colour text is a bug.
 */
@Immutable
data class EchoColors(
    val background: Color,
    val surface: Color,
    val elevated: Color,
    val foreground: Color,
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val inverse: Color,
    val onInverse: Color,
)

private val LightColors = EchoColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F7),
    elevated = Color(0xFFFFFFFF),
    foreground = Color(0xFF000000),
    muted = Color(0xFF6B6B6B),
    faint = Color(0xFF757575),
    hairline = Color(0xFFE3E3E3),
    inverse = Color(0xFF000000),
    onInverse = Color(0xFFFFFFFF),
)

private val DarkColors = EchoColors(
    background = Color(0xFF000000),
    surface = Color(0xFF0C0C0C),
    elevated = Color(0xFF141414),
    foreground = Color(0xFFFFFFFF),
    muted = Color(0xFF9C9C9C),
    faint = Color(0xFF757575),
    hairline = Color(0xFF212121),
    inverse = Color(0xFFFFFFFF),
    onInverse = Color(0xFF000000),
)

val LocalEchoColors = staticCompositionLocalOf { LightColors }

object EchoTheme {
    val colors: EchoColors
        @Composable get() = LocalEchoColors.current
}

private val echoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-2.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.8).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    // Settings reaches for bodySmall in two places. Undefined, it fell through to
    // Material's stock 14 sp / 0.25 tracking -- the one place in the app where
    // type was not coming from this file.
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,       // wide tracking for the small caps labels
    ),
)

@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val material = if (darkTheme) {
        darkColorScheme(
            background = colors.background,
            surface = colors.surface,
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            primary = colors.foreground,
            onPrimary = colors.background,
            surfaceVariant = colors.elevated,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.foreground,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.surface,
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            primary = colors.foreground,
            onPrimary = colors.background,
            surfaceVariant = colors.elevated,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.foreground,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val context = LocalContext.current
        SideEffect {
            (context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalEchoColors provides colors) {
        MaterialTheme(colorScheme = material, typography = echoTypography, content = content)
    }
}

/** Standard page gutter, used everywhere so screens align to one grid. */
val Gutter = 22.dp
