package com.mandar.echo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mandar.echo.ui.theme.EchoTheme
import kotlin.math.pow

/** Small uppercase label with wide tracking — the app's structural voice. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
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

/** A number with its label beneath. Used in the stats row. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
) {
    Column(modifier) {
        Text(
            text = value,
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            color = if (emphasis) EchoTheme.colors.foreground else EchoTheme.colors.foreground,
        )
        Spacer(Modifier.height(4.dp))
        SectionLabel(label)
    }
}

/**
 * Rolling level meter. Bars scroll right-to-left, newest at the right, so the
 * last few seconds of room sound are always visible — the one moving element in
 * an otherwise still interface, which is what makes "it is listening" legible.
 */
@Composable
fun LevelMeter(
    levels: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 48,
) {
    val fg = EchoTheme.colors.foreground
    val faint = EchoTheme.colors.hairline
    val alpha by animateFloatAsState(if (active) 1f else 0.35f, label = "meter-alpha")

    Canvas(modifier) {
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
                color = if (level > 0.0005f) fg.copy(alpha = alpha) else faint,
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
 */
@Composable
fun RecordButton(
    recording: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = EchoTheme.colors
    val scale by animateFloatAsState(if (recording) 1f else 0.86f, label = "rec-scale")

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) colors.foreground else colors.hairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
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
            .clip(RoundedCornerShape(100))
            .background(bg)
            .border(1.dp, if (enabled) colors.foreground.copy(alpha = if (filled) 0f else 1f) else colors.hairline, RoundedCornerShape(100))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = fgColor,
            fontSize = 14.sp,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

/** Thin determinate bar used for downloads and transcription progress. */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "progress")
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.hairline)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(2.dp)
                .background(colors.foreground)
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
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            color = EchoTheme.colors.foreground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = EchoTheme.colors.muted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun RowScopeSpacer() = Spacer(Modifier.size(0.dp))
