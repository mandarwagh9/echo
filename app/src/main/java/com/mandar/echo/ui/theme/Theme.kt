package com.mandar.echo.ui.theme

import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Echo's design system.
 *
 * The identity is a **nocturnal instrument**: a cool ink ground, one warm signal
 * colour, and numerals set in monospace so durations and timestamps line up in a
 * column and read as measurements rather than prose. It replaces the strictly
 * monochrome scheme this app used while it was one person's prototype, which was
 * legible but had no way to say "this is live" other than making something bold.
 *
 * ## The rules this file locks
 *
 * **One accent.** [EchoColors.accent] is the only hue in the app, and it means
 * one thing: *look here*. In practice that is live capture, the primary action,
 * and the small number of states that genuinely need the user (a stalled queue,
 * dropped audio, a recorder that will die overnight). There is deliberately no
 * separate warning red: a second hue would not add a distinction the user needs,
 * it would only make the first one stop meaning anything. Severity is carried by
 * the words, which is where a person reads it anyway.
 *
 * **One shape scale.** Pills are fully round, cards are [CardRadius], fields are
 * [FieldRadius]. See [EchoShapes]. Mixing radii is the fastest way to make a
 * careful interface look assembled from parts.
 *
 * **One theme per launch.** Light and dark are both designed, and the app follows
 * the system. No screen inverts against the rest.
 *
 * **Motion is caused, never idle.** Transitions run in response to a real event:
 * a tap, a state change, data arriving. Nothing loops on a timer. The single
 * continuously-moving element in the app is the level meter, and it moves only
 * because the microphone is genuinely producing new values, which the recording
 * service stops sending entirely once no UI is on screen. This is a recorder
 * that runs for days; an animation that idles is a battery bug wearing a
 * costume.
 *
 * ## Contrast
 *
 * Every value below that carries text was measured against each surface it can
 * sit on, and the ratios are written down beside it rather than eyeballed. The
 * bar is WCAG AA, 4.5:1 for body text. Two candidate greys and two candidate
 * ambers were rejected for missing it on [surface] specifically, which is the
 * background most easily forgotten: a colour checked only against [background]
 * passes the test it was given and fails the one that matters.
 *
 * [hairline] is deliberately exempt. It draws rules and inactive borders and
 * never type. Anything reaching for it to colour text is a bug.
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
    /** The one hue. Live state, the primary action, and anything needing the user. */
    val accent: Color,
    /** Drawn on top of a solid [accent] fill. */
    val onAccent: Color,
    /** [accent] at low alpha, for the fill behind a live badge. Never behind text. */
    val accentWash: Color,
    val inverse: Color,
    val onInverse: Color,
    val isDark: Boolean,
)

/*
 * Dark. The primary identity: this app is opened at night, to read back a day.
 *
 *   foreground #ECEFF3   16.77 : 1  on background   15.41 on surface   13.99 on elevated
 *   muted      #A3ADBB    8.52      on background    7.83              7.11
 *   faint      #8A94A1    6.29      on background    5.78              5.25
 *   accent     #E09A4F    8.20      on background    7.54              6.84
 *   onAccent   #0B0E12    8.20 : 1  on the accent fill
 *   hairline   #232A35    1.34      non-text, deliberately quiet
 *
 * #78828F was the first candidate for `faint` and is rejected: 4.14 on elevated.
 */
private val DarkColors = EchoColors(
    background = Color(0xFF0B0E12),
    surface = Color(0xFF141820),
    elevated = Color(0xFF1C212B),
    foreground = Color(0xFFECEFF3),
    muted = Color(0xFFA3ADBB),
    faint = Color(0xFF8A94A1),
    hairline = Color(0xFF232A35),
    accent = Color(0xFFE09A4F),
    onAccent = Color(0xFF0B0E12),
    accentWash = Color(0x1FE09A4F),
    inverse = Color(0xFFECEFF3),
    onInverse = Color(0xFF0B0E12),
    isDark = true,
)

/*
 * Light. Not a tint of the dark scheme: the accent has to be a different value
 * entirely, because the amber that reads warm on ink is 2.11 : 1 on paper.
 *
 *   foreground #14171C   17.22 : 1  on background   16.04 on surface   17.96 on elevated
 *   muted      #4E5661    7.12      on background    6.63              7.43
 *   faint      #666F7A    4.89      on background    4.55              5.10
 *   accent     #9C5C14    5.09      on background    4.74              5.31
 *   onAccent   #FFFFFF    5.31 : 1  on the accent fill
 *   hairline   #E2E5E9    1.21      non-text, deliberately quiet
 *
 * #6B7480 (faint) and #A6631A (accent) were both rejected for landing at 4.23
 * and 4.24 on `surface`, which is the background of every card in the app.
 */
private val LightColors = EchoColors(
    background = Color(0xFFFAFAFB),
    surface = Color(0xFFF1F2F4),
    elevated = Color(0xFFFFFFFF),
    foreground = Color(0xFF14171C),
    muted = Color(0xFF4E5661),
    faint = Color(0xFF666F7A),
    hairline = Color(0xFFE2E5E9),
    accent = Color(0xFF9C5C14),
    onAccent = Color(0xFFFFFFFF),
    accentWash = Color(0x1A9C5C14),
    inverse = Color(0xFF14171C),
    onInverse = Color(0xFFFAFAFB),
    isDark = false,
)

val LocalEchoColors = staticCompositionLocalOf { DarkColors }

object EchoTheme {
    val colors: EchoColors
        @Composable get() = LocalEchoColors.current
}

// ---------------------------------------------------------------------------
// Shape
// ---------------------------------------------------------------------------

/** Cards, sheets, notices. */
val CardRadius = 18.dp

/** Text fields and other things you type into. */
val FieldRadius = 14.dp

/** Small inline chips and badges. */
val ChipRadius = 10.dp

object EchoShapes {
    val card = RoundedCornerShape(CardRadius)
    val field = RoundedCornerShape(FieldRadius)
    val chip = RoundedCornerShape(ChipRadius)

    /** Buttons are fully round. The one exception to the radius scale, applied everywhere. */
    val pill = RoundedCornerShape(percent = 50)
}

// ---------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------

/**
 * One easing curve and three durations, so nothing in the app moves at a speed
 * that was not chosen. The curve decelerates hard: things arrive rather than
 * drift in.
 */
object EchoMotion {
    val easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** State flips: a toggle, a chip, a colour change. */
    fun <T> quick() = tween<T>(durationMillis = 180, easing = easing)

    /** Something entering or leaving the screen. */
    fun <T> standard() = tween<T>(durationMillis = 320, easing = easing)

    /** Reserved for the record control, which is the app's one deliberate gesture. */
    fun <T> deliberate() = tween<T>(durationMillis = 460, easing = easing)
}

// ---------------------------------------------------------------------------
// Type
// ---------------------------------------------------------------------------

/**
 * The platform sans for language, the platform monospace for measurement.
 *
 * No font is bundled. On Android the system face is a real typeface rather than
 * a fallback, and an offline-first recorder cannot depend on the downloadable
 * fonts provider, which needs Play Services and a network to render a heading.
 * The character comes from the scale, the weights and the tracking instead, and
 * from the split below: every number Echo shows you it measured, and monospace
 * is how a column of measurements stays a column.
 */
val Numeric = FontFamily.Monospace

private val echoTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 39.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.1).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    // Small caps, wide tracking. Rationed: at most one of these above any three
    // sections, or the whole app reads as a form with fieldset legends.
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.3.sp,
    ),
)

/** A measurement, not a word. Timestamps, durations, counts, percentages. */
val NumericLarge = TextStyle(
    fontFamily = Numeric,
    fontWeight = FontWeight.Medium,
    fontSize = 30.sp,
    lineHeight = 34.sp,
    letterSpacing = (-1).sp,
)

val NumericSmall = TextStyle(
    fontFamily = Numeric,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp,
)

@Composable
fun EchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    // Material's scheme is still populated, because Material 3 components drawn
    // by the platform (ripples, the text selection handles, the IME) read from
    // it and would otherwise arrive in Material's own purple.
    val material = if (darkTheme) {
        darkColorScheme(
            background = colors.background,
            surface = colors.surface,
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            surfaceVariant = colors.elevated,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.accent,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.surface,
            onBackground = colors.foreground,
            onSurface = colors.foreground,
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            surfaceVariant = colors.elevated,
            onSurfaceVariant = colors.muted,
            outline = colors.hairline,
            error = colors.accent,
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

/** Standard page gutter. Every screen aligns to this one grid. */
val Gutter = 22.dp
