package com.mandar.echo.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mandar.echo.ui.components.ChoiceRow
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.EchoCard
import com.mandar.echo.ui.components.EchoSwitch
import com.mandar.echo.ui.components.EchoTextField
import com.mandar.echo.ui.components.EmptyState
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.LevelMeter
import com.mandar.echo.ui.components.LiveChip
import com.mandar.echo.ui.components.Notice
import com.mandar.echo.ui.components.RecordControl
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.SettingRow
import com.mandar.echo.ui.components.SkeletonLines
import com.mandar.echo.ui.components.StatTile
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoShapes
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import kotlin.math.sin

/**
 * The design system, rendered.
 *
 * Open this file in Android Studio and use the split or design view; every
 * component in the app appears below in both themes, without a device and
 * without an install. The screens themselves cannot be previewed because they
 * take an AndroidViewModel and therefore an Application, so this is the closest
 * thing to seeing the interface before it is on hardware.
 *
 * It lives in the main source set rather than `src/debug/` deliberately. The
 * debug variant triggers a second full NDK compile of ggml, which is a long
 * build to sit through to look at a button, and the preview composables cost a
 * few tens of kilobytes of dex against a 25 MB APK.
 */

@Preview(name = "Design system, dark", showBackground = true, heightDp = 1600)
@Composable
private fun DesignSystemDark() = EchoTheme(darkTheme = true) { Gallery() }

@Preview(name = "Design system, light", showBackground = true, heightDp = 1600)
@Composable
private fun DesignSystemLight() = EchoTheme(darkTheme = false) { Gallery() }

@Composable
private fun Gallery() {
    val colors = EchoTheme.colors
    Column(
        Modifier
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter, vertical = 24.dp),
    ) {
        SectionLabel("Palette")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(colors.background, "bg")
            Swatch(colors.surface, "surface")
            Swatch(colors.elevated, "elev")
            Swatch(colors.foreground, "fg")
            Swatch(colors.muted, "muted")
            Swatch(colors.faint, "faint")
            Swatch(colors.accent, "accent")
        }

        Divider()

        Text("Listening", style = MaterialTheme.typography.displayLarge, color = colors.foreground)
        Text(
            "for 4:21:07",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveChip("Listening")
            Spacer(Modifier.width(10.dp))
            Figure("Tuesday 26 August")
        }

        Divider()

        // A plausible-looking sample, not real data: shaped so the meter's
        // accent tail at the leading edge is actually visible in a still image.
        val levels = List(56) { i ->
            (0.5f + 0.45f * sin(i * 0.42f)).coerceIn(0f, 1f) * if (i % 7 == 0) 0.3f else 1f
        }
        LevelMeter(levels = levels, active = true, modifier = Modifier.fillMaxWidth().height(76.dp))

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RecordControl(recording = false) {}
            RecordControl(recording = true) {}
        }

        Divider()

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile("6h 12m", "captured")
            StatTile("4,182", "words")
            StatTile("3", "in queue", emphasised = true)
        }

        Divider()

        SectionLabel("Buttons")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EchoButton("Start listening", filled = true) {}
            EchoButton("Not now") {}
            EchoButton("Disabled", enabled = false) {}
        }
        Spacer(Modifier.height(12.dp))
        EchoButton("Search", icon = Icons.Default.Search) {}

        Divider()

        SectionLabel("Progress")
        Spacer(Modifier.height(12.dp))
        ThinProgress(0.62f)
        Spacer(Modifier.height(16.dp))
        SkeletonLines(count = 3)

        Divider()

        SectionLabel("Input")
        Spacer(Modifier.height(12.dp))
        EchoTextField(
            value = "",
            onValueChange = {},
            placeholder = "Search everything you have said",
            leading = Icons.Default.Search,
        )
        Spacer(Modifier.height(10.dp))
        EchoTextField(value = "https://stt.example.run.app", onValueChange = {}, placeholder = "")
        Spacer(Modifier.height(14.dp))
        ChoiceRow(label = "On device", body = "Private, works offline", selected = true) {}
        Spacer(Modifier.height(10.dp))
        ChoiceRow(label = "Your server", body = "Audio leaves the phone", selected = false) {}
        Spacer(Modifier.height(6.dp))
        SettingRow(
            title = "Keep audio after transcribing",
            body = "Off by default. Recordings are deleted once transcribed.",
            trailing = { EchoSwitch(checked = false, onCheckedChange = {}) },
        )

        Divider()

        SectionLabel("Notices")
        Spacer(Modifier.height(12.dp))
        Notice(
            title = "Recording will stop overnight",
            body = "Android suspends Echo once the screen has been off for a while.",
            accented = true,
            actionLabel = "Fix this",
            onAction = {},
        )
        Spacer(Modifier.height(12.dp))
        Notice(
            title = "Waiting",
            body = "No connection. Recordings queue until the server can be reached.",
        )
        Spacer(Modifier.height(12.dp))
        EchoCard {
            Text(
                "An ordinary card, holding whatever it is given.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }

        Divider()

        EmptyState(
            title = "Nothing recorded yet",
            body = "Once Echo has listened for a while, every day it captured shows up here.",
            actionLabel = "Start listening",
            onAction = {},
        )
    }
}

@Composable
private fun Swatch(color: Color, name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(38.dp)
                .clip(EchoShapes.chip)
                .background(color)
        )
        Spacer(Modifier.height(6.dp))
        Figure(name)
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(28.dp))
    Hairline()
    Spacer(Modifier.height(28.dp))
}
