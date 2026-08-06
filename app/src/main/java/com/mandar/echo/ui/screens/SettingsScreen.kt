package com.mandar.echo.ui.screens

import android.media.MediaRecorder
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.BuildConfig
import com.mandar.echo.data.SttBackend
import com.mandar.echo.data.SttLanguage
import com.mandar.echo.stt.DownloadState
import com.mandar.echo.stt.WhisperModel
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter

@Composable
fun SettingsScreen(vm: EchoViewModel) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val free by vm.freeBytes.collectAsStateWithLifecycle()
    val failed by vm.failedChunks.collectAsStateWithLifecycle()
    val redoable by vm.redoableChunks.collectAsStateWithLifecycle()

    var confirmWipe by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = colors.foreground)

        // ---- models --------------------------------------------------------

        Group("Speech model")
        Text(
            "Transcription runs entirely on this phone. A model is downloaded once, " +
                "then never needs the network again.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(16.dp))

        WhisperModel.entries.forEach { model ->
            val installed = vm.isInstalled(model)
            val selected = settings.modelFile == model.fileName
            val busy = (download as? DownloadState.Running)?.model == model

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.surface else colors.background)
                    .border(
                        1.dp,
                        if (selected) colors.foreground else colors.hairline,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = installed) { vm.setModel(model) }
                    .padding(16.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioDot(selected)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                model.label,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.foreground,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${model.note} · ${Format.bytes(model.bytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.muted,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    when {
                        busy -> PillButton("Cancel") { vm.cancelDownload() }
                        installed -> PillButton("Remove") { vm.deleteModel(model) }
                        else -> PillButton("Download", filled = true) { vm.downloadModel(model) }
                    }
                }

                if (busy) {
                    val running = download as DownloadState.Running
                    Spacer(Modifier.height(14.dp))
                    ThinProgress(running.fraction)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${Format.bytes(running.bytes)} of ${Format.bytes(running.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                    )
                }
            }
        }

        (download as? DownloadState.Failed)?.let {
            Text(
                "Download failed: ${it.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.foreground,
            )
        }

        // ---- transcription backend -----------------------------------------

        Group("Transcribed by")
        Text(
            "On-device Whisper is private and works offline, but it is weak on " +
                "Devanagari — measured against known references it recovers about half " +
                "of Hindi and almost none of Marathi. Your own IndicConformer server " +
                "returns both word for word, at the cost of sending audio to it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(14.dp))
        ChoiceRow(
            options = SttBackend.entries.map { it.label },
            selectedIndex = SttBackend.entries.indexOf(settings.sttBackend),
            onSelect = { vm.setSttBackend(SttBackend.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            settings.sttBackend.note,
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )

        if (settings.sttBackend == SttBackend.CLOUD) {
            Spacer(Modifier.height(16.dp))
            SettingRow(
                "Server",
                settings.sttServerUrl.ifBlank { "not set" },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (settings.sttServerUrl.isBlank()) {
                    "No server configured — Echo will keep using the on-device model."
                } else {
                    "Chunks are uploaded in 4-minute pieces. If the server cannot be " +
                        "reached, Echo falls back to on-device rather than losing the audio."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }

        // ---- language ------------------------------------------------------

        Group("Language")
        Text(
            "Auto-detect handles code-switching between English, Hindi and Marathi. " +
                "Pin a language if you mostly speak one.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
        Spacer(Modifier.height(14.dp))
        ChoiceRow(
            options = SttLanguage.entries.map { it.label },
            selectedIndex = SttLanguage.entries.indexOf(settings.language),
            onSelect = { vm.setLanguage(SttLanguage.entries[it]) },
        )

        // ---- capture -------------------------------------------------------

        Group("Capture")
        SettingRow("Chunk length", "${settings.chunkMinutes} minutes")
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            options = listOf("1", "5", "10", "15"),
            selectedIndex = listOf(1, 5, 10, 15).indexOf(settings.chunkMinutes).coerceAtLeast(0),
            onSelect = { vm.setChunkMinutes(listOf(1, 5, 10, 15)[it]) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Shorter chunks make transcripts appear sooner and are useful for testing. " +
                "10 minutes is the intended setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )

        Spacer(Modifier.height(20.dp))
        SettingRow("Microphone source", if (settings.audioSource == MediaRecorder.AudioSource.MIC) "Ambient (MIC)" else "Voice recognition")
        Spacer(Modifier.height(10.dp))
        ChoiceRow(
            options = listOf("Ambient", "Voice"),
            selectedIndex = if (settings.audioSource == MediaRecorder.AudioSource.MIC) 0 else 1,
            onSelect = {
                vm.setAudioSource(
                    if (it == 0) MediaRecorder.AudioSource.MIC
                    else MediaRecorder.AudioSource.VOICE_RECOGNITION
                )
            },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Ambient is correct for room conversation. Voice recognition applies " +
                "near-field noise suppression that hurts far-field capture.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )

        Spacer(Modifier.height(20.dp))
        ToggleRow(
            title = "Skip silent chunks",
            body = "Saves battery and stops whisper inventing text from room tone.",
            checked = settings.skipSilentChunks,
            onChange = vm::setSkipSilent,
        )
        Spacer(Modifier.height(16.dp))
        ToggleRow(
            title = "Keep audio after transcription",
            body = "Off by default: audio is deleted as soon as its transcript is saved.",
            checked = settings.keepAudioAfterTranscription,
            onChange = vm::setKeepAudio,
        )

        // ---- summary -------------------------------------------------------

        Group("Daily summary")
        SettingRow("Runs at", "%02d:%02d".format(settings.summaryHour, settings.summaryMinute))
        Spacer(Modifier.height(12.dp))
        ChoiceRow(
            options = listOf("21:00", "22:00", "23:00", "23:30"),
            selectedIndex = listOf(21 to 0, 22 to 0, 23 to 0, 23 to 30)
                .indexOfFirst { it.first == settings.summaryHour && it.second == settings.summaryMinute }
                .coerceAtLeast(0),
            onSelect = {
                val (h, m) = listOf(21 to 0, 22 to 0, 23 to 0, 23 to 30)[it]
                vm.setSummaryTime(context, h, m)
            },
        )

        // ---- storage / health ----------------------------------------------

        Group("Storage & health")
        SettingRow("Free space", Format.bytes(free))
        Spacer(Modifier.height(12.dp))
        SettingRow("Failed chunks", failed.size.toString())
        if (failed.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Their audio was kept so nothing is lost. Retrying puts them back in the queue.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            PillButton("Retry ${failed.size} failed") { vm.retryFailedChunks() }
        }

        Spacer(Modifier.height(12.dp))
        SettingRow("Chunks worth redoing", redoable.toString())
        if (redoable > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Their audio is still here because a better transcript is possible — the " +
                    "server was unreachable, or part of the chunk was never transcribed. " +
                    "Redo them once it is back.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            PillButton("Redo $redoable chunks") { vm.redoHeldChunks() }
        }

        Group("About")
        Text(
            remember { vm.whisperSystemInfo() },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Echo ${BuildConfig.VERSION_NAME} · recordings and transcripts never leave this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )

        Group("Danger zone")
        if (!confirmWipe) {
            PillButton("Delete everything") { confirmWipe = true }
        } else {
            Text(
                "This permanently deletes all audio, transcripts and summaries.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.foreground,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                PillButton("Yes, delete", filled = true) {
                    vm.deleteEverything(context)
                    confirmWipe = false
                }
                Spacer(Modifier.width(10.dp))
                PillButton("Cancel") { confirmWipe = false }
            }
        }

        Spacer(Modifier.height(64.dp))
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(34.dp))
    SectionLabel(title)
    Spacer(Modifier.height(10.dp))
    Hairline()
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SettingRow(title: String, value: String) {
    val colors = EchoTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.foreground)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = colors.muted)
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = EchoTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.foreground)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
        }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier
                .width(46.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(100))
                .background(if (checked) colors.foreground else colors.hairline),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) colors.background else colors.background)
            )
        }
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = EchoTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(100))
                    .background(if (selected) colors.inverse else colors.background)
                    .border(
                        1.dp,
                        if (selected) colors.inverse else colors.hairline,
                        RoundedCornerShape(100),
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onInverse else colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val colors = EchoTheme.colors
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (selected) colors.foreground else colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.foreground))
        }
    }
}
