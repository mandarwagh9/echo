package com.mandar.echo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mandar.echo.ui.theme.EchoMotion
import com.mandar.echo.ui.theme.EchoShapes
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.NumericLarge
import com.mandar.echo.ui.theme.NumericSmall
import java.util.Locale
import kotlin.math.pow

/**
 * Echo's component set.
 *
 * Icons come from `material-icons-core`, which Compose Material 3 already brings
 * in. `material-icons-extended` is not a dependency and must not become one: it
 * generates roughly 1,500 vector assets as Kotlin source and cost this app about
 * 30 MB of dex, two thirds of the APK, the last time it was tried. The core set
 * has no microphone glyph, which is fine, because the one mark this app really
 * needs is [RecordControl] and that is drawn as geometry rather than borrowed.
 */

/**
 * Minimum touch target. Several controls here are painted smaller than this on
 * purpose, so the target is grown independently of the paint.
 */
val MinTouchTarget = 48.dp

// ---------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------

/**
 * Small caps label with wide tracking.
 *
 * **Rationed.** At most one of these per three sections on a screen. Put one
 * above every heading and the interface stops looking composed and starts
 * looking like a form with fieldset legends.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        // ROOT rather than the device locale: a Turkish locale maps "i" to "İ",
        // which would quietly mangle every structural label in the app.
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = color ?: EchoTheme.colors.faint,
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

/** The app's one container. Anything grouped sits in one of these or in nothing. */
@Composable
fun EchoCard(
    modifier: Modifier = Modifier,
    accented: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = EchoTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .clip(EchoShapes.card)
            .background(if (accented) colors.accentWash else colors.surface)
            .then(
                if (accented) Modifier.border(1.dp, colors.accent.copy(alpha = 0.35f), EchoShapes.card)
                else Modifier
            )
            .padding(18.dp),
    ) { content() }
}

// ---------------------------------------------------------------------------
// Numbers
// ---------------------------------------------------------------------------

/**
 * A measurement with its name beneath it.
 *
 * Read out as one phrase: unmerged, a screen reader announces "1,240" and
 * "WORDS" as two unrelated nodes, and a bare number is not information.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasised: Boolean = false,
) {
    val colors = EchoTheme.colors
    Column(modifier.clearAndSetSemantics { contentDescription = "$value $label" }) {
        Text(
            text = value,
            style = NumericLarge,
            color = if (emphasised) colors.accent else colors.foreground,
        )
        Spacer(Modifier.height(6.dp))
        SectionLabel(label)
    }
}

/** Inline monospace figure, for use inside a row of prose. */
@Composable
fun Figure(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        style = NumericSmall,
        color = color ?: EchoTheme.colors.faint,
        modifier = modifier,
    )
}

/**
 * The live badge.
 *
 * A filled accent chip, and the only place in the app where the accent appears
 * as a background. It means one thing: the microphone is open right now.
 */
@Composable
fun LiveChip(text: String, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    Row(
        modifier
            .clip(EchoShapes.chip)
            .background(colors.accentWash)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot carries real state (the mic is open) rather than decorating a
        // label, which is the only reason it earns its place.
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accent)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
        )
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * @param filled the accent-filled primary. One per screen, at most.
 */
@Composable
fun EchoButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Tactile feedback: the control gives under the finger and springs back.
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, EchoMotion.quick(), label = "press")

    val background = when {
        !enabled -> Color.Transparent
        filled -> colors.accent
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> colors.faint
        filled -> colors.onAccent
        else -> colors.foreground
    }
    val outline = when {
        !enabled -> colors.hairline
        filled -> Color.Transparent
        else -> colors.hairline
    }

    Row(
        modifier
            .scale(scale)
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(EchoShapes.pill)
            .background(background)
            .border(1.dp, outline, EchoShapes.pill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            // The description has to replace the label on the *child*: merged
            // onto the clickable node it would announce both.
            modifier = if (contentDescription != null) {
                Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        )
    }
}

/** A borderless icon-only control, for chrome: back, close, clear, step a day. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    Box(
        modifier
            .size(MinTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) colors.muted else colors.hairline,
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * The record control.
 *
 * A ring holding a circle that becomes a square while capture is running, so the
 * state is unmistakable at a glance. It is the only control in the app with no
 * text on it, which is why the spoken label and the haptic are not decoration:
 * for anyone not looking directly at the screen they are the whole affordance.
 *
 * The ring takes the accent while live. That is the accent's entire job, and
 * this is the largest instance of it in the app.
 */
@Composable
fun RecordControl(
    recording: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val haptics = LocalHapticFeedback.current

    val ring by animateColorAsState(
        when {
            !enabled -> colors.hairline
            recording -> colors.accent
            else -> colors.foreground
        },
        EchoMotion.deliberate(),
        label = "ring",
    )
    val innerSize by animateFloatAsState(
        if (recording) 0.34f else 0.62f,
        EchoMotion.deliberate(),
        label = "inner",
    )
    val corner by animateFloatAsState(
        if (recording) 7f else 50f,
        EchoMotion.deliberate(),
        label = "corner",
    )

    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .border(1.5.dp, ring, CircleShape)
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
                .size(84.dp * innerSize)
                .clip(RoundedCornerShape(percent = corner.toInt()))
                .background(ring)
        )
    }
}

// ---------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------

/**
 * Rolling level meter. Newest sample at the right, so the last minute of room
 * sound scrolls leftward out of view.
 *
 * This is the one element in Echo that moves continuously, and it does so only
 * because the microphone is genuinely producing new values. The recording
 * service stops computing and publishing them the moment no UI is on screen, so
 * an idle phone in a pocket animates nothing.
 *
 * A screen reader cannot follow 56 bars redrawn every frame, so it collapses to
 * one honest sentence instead.
 */
@Composable
fun LevelMeter(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 56,
) {
    val colors = EchoTheme.colors
    val accent = colors.accent
    val idle = colors.hairline
    val fade by animateFloatAsState(if (active) 1f else 0.4f, EchoMotion.standard(), label = "meter")

    Canvas(
        modifier.clearAndSetSemantics {
            contentDescription =
                if (active) "Microphone level, listening" else "Microphone level, idle"
        }
    ) {
        val gap = size.width / barCount
        val barWidth = (gap * 0.3f).coerceAtLeast(1.5f)
        val mid = size.height / 2f

        for (i in 0 until barCount) {
            val level = levels.getOrElse(levels.size - barCount + i) { 0f }
            // Raw RMS is tiny for speech, so compress it perceptually.
            val shaped = level.coerceIn(0f, 1f).pow(0.42f)
            val h = (shaped * size.height * 0.94f).coerceAtLeast(2f)
            val x = i * gap + gap / 2f

            // The leading quarter is the accent: it reads as "now", and the tail
            // recedes into the rule colour as it ages out of the window.
            val recency = i.toFloat() / barCount
            val colour = if (level > 0.0005f) {
                val warmth = ((recency - 0.75f) / 0.25f).coerceIn(0f, 1f)
                lerp(colors.muted, accent, warmth).copy(alpha = fade)
            } else {
                idle
            }

            drawLine(
                color = colour,
                start = Offset(x, mid - h / 2f),
                end = Offset(x, mid + h / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t,
)

/** Thin determinate bar, for downloads and transcription progress. */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, EchoMotion.standard(), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(EchoShapes.pill)
            .background(colors.hairline)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(3.dp)
                .clip(EchoShapes.pill)
                .background(colors.accent)
        )
    }
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

/**
 * @param masked renders dots rather than characters. A shoulder-surfing guard,
 *   not storage security: the value is already in the settings flow, so hiding
 *   it here changes who can read it over your shoulder and nothing else.
 */
@Composable
fun EchoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    textStyle: TextStyle? = null,
) {
    val colors = EchoTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(EchoShapes.field)
            .background(colors.surface)
            .padding(start = if (leading != null) 14.dp else 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(11.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = (textStyle ?: MaterialTheme.typography.bodyLarge)
                .copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (masked) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 14.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = textStyle ?: MaterialTheme.typography.bodyLarge,
                        // faint clears AA on `surface`, which is this field's
                        // own background. Placeholder text is text.
                        color = colors.faint,
                        maxLines = 1,
                    )
                }
                inner()
            },
        )
        if (trailing != null) trailing() else Spacer(Modifier.width(10.dp))
    }
}

/**
 * A labelled row with a control on the right. The spine of Settings.
 *
 * Merged for accessibility: the title, the explanation and the control are one
 * node, because "Keep audio" and "on" announced separately loses which is which.
 */
@Composable
fun SettingRow(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) colors.foreground else colors.faint,
            )
            if (body != null) {
                Spacer(Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.faint)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/** Compose Material 3's Switch in Echo's palette, with no track outline. */
@Composable
fun EchoSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val colors = EchoTheme.colors
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.faint,
            uncheckedTrackColor = colors.surface,
            uncheckedBorderColor = colors.hairline,
        ),
    )
}

/** A row of mutually exclusive choices. Used for backend, language and model. */
@Composable
fun ChoiceRow(
    label: String,
    selected: Boolean,
    body: String? = null,
    enabled: Boolean = true,
    /**
     * Defaults to a radio button because most uses here are one-of-many. A row
     * that can be turned back off is a checkbox, and announcing it as a radio
     * button tells a screen reader user the opposite of what the control does.
     */
    role: Role = Role.RadioButton,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val border by animateColorAsState(
        if (selected) colors.accent else colors.hairline,
        EchoMotion.quick(),
        label = "choice",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(EchoShapes.field)
            .border(1.dp, border, EchoShapes.field)
            .clickable(
                enabled = enabled,
                role = role,
                onClickLabel = if (role == Role.Checkbox && selected) {
                    "Clear $label"
                } else {
                    "Select $label"
                },
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (selected) "Selected" else "Not selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) colors.foreground else colors.faint,
            )
            if (body != null) {
                Spacer(Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.faint)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------

/**
 * The message shown when a screen has nothing to show.
 *
 * Always says what will make it fill up, because on a fresh install every screen
 * in this app is empty and "no data" tells a new user nothing they can act on.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.foreground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            EchoButton(actionLabel, onClick = onAction)
        }
    }
}

/**
 * Something the user should know about, with the thing they can do about it.
 *
 * [accented] is for a state that is costing them something right now: a stalled
 * queue, audio being dropped. Ordinary notices stay quiet.
 */
@Composable
fun Notice(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    accented: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    EchoCard(modifier, accented = accented) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (accented) colors.accent else colors.foreground,
            )
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                EchoButton(actionLabel, onClick = onAction)
            }
        }
    }
}

/**
 * Placeholder rows shaped like the content that will replace them.
 *
 * A spinner says "wait"; this says "text is coming, about this much of it",
 * which is the difference between a screen that feels stuck and one that feels
 * busy. Static by design: a shimmer that sweeps forever is exactly the idle
 * animation this app has decided not to run.
 */
@Composable
fun SkeletonLines(count: Int = 3, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    val widths = listOf(0.92f, 0.74f, 0.85f, 0.6f, 0.8f)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading" }) {
        repeat(count) { i ->
            Box(
                Modifier
                    .fillMaxWidth(widths[i % widths.size])
                    .height(13.dp)
                    .clip(EchoShapes.chip)
                    .background(colors.surface)
            )
            if (i < count - 1) Spacer(Modifier.height(11.dp))
        }
    }
}
