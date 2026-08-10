package com.mandar.echo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandar.echo.ui.theme.EchoTheme
import java.util.Locale
import kotlin.math.pow

/**
 * Minimum touch target. Several controls here are visually smaller than this by
 * design -- the interface is built out of hairlines and small caps -- so the
 * target is grown independently of the paint.
 */
val MinTouchTarget = 48.dp

/** Small uppercase label with wide tracking — the app's structural voice. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        // ROOT, not the device locale: a Turkish locale maps "i" to "İ", which
        // would quietly mangle every structural label in the app.
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = EchoTheme.colors.faint,
        modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(EchoTheme.colors.hairline)
    )
}

/**
 * A number with its label beneath. Used in the stats row.
 *
 * Read out as one phrase: unmerged, TalkBack announces "1,240" and "WORDS" as
 * two unrelated nodes, and a lone number is not information.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clearAndSetSemantics { contentDescription = "$value $label" }) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = EchoTheme.colors.foreground,
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel(label)
    }
}

/**
 * Rolling level meter. Bars scroll right-to-left, newest at the right, so the
 * last few seconds of room sound are always visible — the one moving element in
 * an otherwise still interface, which is what makes "it is listening" legible.
 *
 * Purely visual, and a screen reader cannot follow 48 bars redrawn every frame,
 * so it collapses to a single honest sentence instead.
 */
@Composable
fun LevelMeter(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
) {
    val fg = EchoTheme.colors.foreground
    val idle = EchoTheme.colors.hairline
    val alpha by animateFloatAsState(if (active) 1f else 0.35f, label = "meter-alpha")

    Canvas(
        modifier.clearAndSetSemantics {
            contentDescription = if (active) {
                "Microphone level meter, listening"
            } else {
                "Microphone level meter, idle"
            }
        }
    ) {
        val gap = size.width / barCount
        val barWidth = (gap * 0.34f).coerceAtLeast(1.4f)
        val mid = size.height / 2f

        for (i in 0 until barCount) {
            val level = levels.getOrElse(levels.size - barCount + i) { 0f }
            // Perceptual curve: raw RMS is tiny for speech, so compress it.
            val shaped = level.coerceIn(0f, 1f).pow(0.42f)
            val h = (shaped * size.height * 0.92f).coerceAtLeast(2f)
            val x = i * gap + gap / 2f
            drawLine(
                color = if (level > 0.0005f) fg.copy(alpha = alpha) else idle,
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The record control: a ring that fills solid while listening. Deliberately a
 * square-in-circle (stop) versus circle (start) so the state is unmistakable at
 * a glance, which matters for an app that records people.
 *
 * It is also the only control in the app with no text anywhere on it, so the
 * label and the haptic are not decoration: they are the whole affordance for
 * anyone not looking directly at the screen.
 */
@Composable
fun RecordButton(
    recording: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (recording) 1f else 0.86f, label = "rec-scale")

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) colors.foreground else colors.hairline, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (recording) "Stop recording" else "Start recording",
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics {
                contentDescription = if (recording) "Recording" else "Not recording"
                stateDescription = if (recording) "Listening" else "Stopped"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((if (recording) 26.dp else 52.dp) * scale)
                .clip(if (recording) RoundedCornerShape(5.dp) else CircleShape)
                .background(
                    if (enabled) colors.foreground else colors.hairline
                )
        )
    }
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    /**
     * Spoken label, for pills whose visible text is a glyph. "◀" is announced as
     * nothing useful at all, so the date steppers would be two unnamed buttons.
     */
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val bg = when {
        !enabled -> Color.Transparent
        filled -> colors.inverse
        else -> Color.Transparent
    }
    val fgColor = when {
        !enabled -> colors.faint
        filled -> colors.onInverse
        else -> colors.foreground
    }
    Box(
        modifier
            // Grown, not repainted: the pill keeps its 46 dp look and gains a
            // reachable target.
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(100))
            .background(bg)
            .border(
                1.dp,
                if (enabled) colors.foreground.copy(alpha = if (filled) 0f else 1f) else colors.hairline,
                RoundedCornerShape(100),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fgColor,
            fontSize = 14.sp,
            style = MaterialTheme.typography.titleMedium,
            // The description has to replace the glyph on the *child*, not sit
            // beside it on the clickable node -- merged, the node would still
            // carry "◀" and announce both.
            modifier = if (contentDescription != null) {
                Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        )
    }
}

/** Thin determinate bar used for downloads and transcription progress. */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.hairline)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(2.dp)
                .background(colors.foreground)
        )
    }
}

/**
 * Single-line text input in the app's own language — a filled surface pill, no
 * Material outline, no floating label.
 *
 * [masked] renders dots rather than characters. It is a shoulder-surfing guard,
 * not storage security: the value is already in the settings flow and in
 * `BuildConfig`, so hiding it here changes who can read it over your shoulder
 * and nothing else.
 */
@Composable
fun EchoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = EchoTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.foreground),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.faint,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = EchoTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = EchoTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}
