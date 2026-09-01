package com.mandar.echo.ui.screens

import android.media.MediaRecorder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.BuildConfig
import com.mandar.echo.data.SttBackend
import com.mandar.echo.data.SttLanguage
import com.mandar.echo.stt.DownloadState
import com.mandar.echo.stt.WhisperModel
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.ChoiceRow
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.EchoCard
import com.mandar.echo.ui.components.EchoSwitch
import com.mandar.echo.ui.components.EchoTextField
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MinTouchTarget
import com.mandar.echo.ui.components.Notice
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.SettingRow
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoMotion
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter

/**
 * Settings, in two tiers.
 *
 * Everything a person needs to run Echo is on the surface. Everything that only
 * makes sense if you built the pipeline (which backend, which server, how long a
 * chunk is, which audio source) sits behind one disclosure, because a stranger
 * who opens this screen and finds a field asking for an API key concludes,
 * correctly, that the app was not meant for them.
 */
@Composable
fun SettingsScreen(vm: EchoViewModel) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()
    val free by vm.freeBytes.collectAsStateWithLifecycle()
    val failed by vm.failedChunks.collectAsStateWithLifecycle()
    val redoable by vm.redoableChunks.collectAsStateWithLifecycle()

    var showAdvanced by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    var batteryExempt by remember { mutableStateOf(vm.batteryExemptionGranted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryExempt = vm.batteryExemptionGranted(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(26.dp))
        Text(
            "Settings",
            style = MaterialTheme.typography.displayMedium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(28.dp))

        // ---- the one that breaks the product if it is wrong ---------------
        if (!batteryExempt) {
            Notice(
                title = "Recording will stop overnight",
                body = "Android suspends Echo once the screen has been off for a while. " +
                    "Allowing it to run in the background is what keeps capture alive.",
                accented = true,
                actionLabel = "Fix this",
                onAction = {
                    runCatching { context.startActivity(vm.batteryExemptionIntent(context)) }
                },
            )
            Spacer(Modifier.height(28.dp))
        }

        // ---- speech model -------------------------------------------------
        Group("Speech model")
        WhisperModel.entries.forEach { model ->
            val installed = vm.isInstalled(model)
            val running = (download as? DownloadState.Running)?.takeIf { it.model == model }
            val failedFor = (download as? DownloadState.Failed)?.takeIf { it.model == model }

            // 22 dp between models, 8 dp from a row to its own button. They were
            // 10 and 8, near enough equal that each Download read as belonging to
            // the model underneath it.
            Column(Modifier.padding(bottom = 22.dp)) {
                // Never rendered disabled. A greyed-out row sitting directly
                // above a Download button offering that very model reads as
                // "unavailable" next to a control that says otherwise. The row
                // does the obvious thing instead: select it if it is here,
                // fetch it if it is not.
                ChoiceRow(
                    label = model.label,
                    body = when {
                        running != null -> "Downloading"
                        installed -> "Installed, ${Format.bytes(model.bytes)}. ${model.note}"
                        else -> "${Format.bytes(model.bytes)} download. ${model.note}"
                    },
                    selected = installed && settings.modelFile == model.fileName,
                    enabled = running == null,
                    onClick = {
                        if (installed) vm.setModel(model) else vm.downloadModel(model)
                    },
                )
                if (running != null) {
                    Spacer(Modifier.height(10.dp))
                    ThinProgress(running.fraction)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Figure("${Format.bytes(running.bytes)} / ${Format.bytes(running.total)}")
                        EchoButton("Cancel", onClick = vm::cancelDownload)
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (installed) {
                            EchoButton("Remove") { vm.deleteModel(model) }
                        } else {
                            // Not filled. Three accent-filled pills stacked down
                            // the screen is three competing primary actions, which
                            // is exactly what the one-primary-per-screen rule in
                            // EchoButton's own docs forbids.
                            EchoButton("Download") { vm.downloadModel(model) }
                        }
                    }
                }
                if (failedFor != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Download failed: ${failedFor.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accent,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ---- language -----------------------------------------------------
        Group("Language")
        Text(
            "Whisper on this phone is strong on English and weak on Devanagari: measured " +
                "against known references it recovers about one Hindi word in four and " +
                "almost no Marathi. Those two need a transcription server, which you can " +
                "point Echo at under Advanced.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(14.dp))
        SttLanguage.entries.forEach { language ->
            ChoiceRow(
                label = language.label,
                selected = settings.language == language,
                onClick = { vm.setLanguage(language) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(18.dp))

        // ---- the daily summary --------------------------------------------
        Group("Daily summary")
        SummaryTimeRow(
            hour = settings.summaryHour,
            minute = settings.summaryMinute,
            onChange = { h, m -> vm.setSummaryTime(context, h, m) },
        )

        Spacer(Modifier.height(18.dp))

        // ---- storage ------------------------------------------------------
        Group("Storage")
        SettingRow(
            title = "Free space",
            body = "Echo pauses recording when the phone is nearly full.",
            trailing = { Figure(Format.bytes(free)) },
        )
        Hairline()
        SettingRow(
            title = "Keep audio after transcribing",
            body = "Off by default. Recordings are deleted the moment their transcript is " +
                "saved, which is what keeps a day of listening down to a few kilobytes.",
            trailing = {
                EchoSwitch(
                    checked = settings.keepAudioAfterTranscription,
                    onCheckedChange = vm::setKeepAudio,
                )
            },
        )

        if (failed.isNotEmpty() || redoable > 0) {
            Spacer(Modifier.height(18.dp))
            Group("Needs attention")
            if (failed.isNotEmpty()) {
                SettingRow(
                    title = if (failed.size == 1) "1 recording failed" else
                        "${failed.size} recordings failed",
                    body = "Transcription gave up on these. Retrying uses whichever backend " +
                        "is configured now.",
                    trailing = {
                        EchoButton("Retry", onClick = vm::retryFailedChunks)
                    },
                )
            }
            if (redoable > 0) {
                if (failed.isNotEmpty()) Hairline()
                SettingRow(
                    title = "$redoable recordings held for a better transcript",
                    body = "Their audio is still here because a better backend could do more " +
                        "with it than the one that ran.",
                    trailing = {
                        EchoButton("Redo", onClick = vm::redoHeldChunks)
                    },
                )
            }
        }

        // ---- advanced -----------------------------------------------------
        Spacer(Modifier.height(28.dp))
        DisclosureHeader(
            title = "Advanced",
            expanded = showAdvanced,
            onToggle = { showAdvanced = !showAdvanced },
        )
        AnimatedVisibility(
            visible = showAdvanced,
            enter = expandVertically(EchoMotion.standard()) + fadeIn(EchoMotion.standard()),
            exit = shrinkVertically(EchoMotion.quick()) + fadeOut(EchoMotion.quick()),
        ) {
            AdvancedSection(vm = vm, settings = settings)
        }

        // ---- destructive --------------------------------------------------
        Spacer(Modifier.height(34.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))
        Group("Delete everything")
        Text(
            "Removes every transcript, every summary and any audio still on the phone. " +
                "There is no copy anywhere else, and this cannot be undone.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(16.dp))
        if (confirmingDelete) {
            EchoCard(accented = true) {
                Column {
                    Text(
                        "Delete everything Echo has recorded?",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        EchoButton("Delete", filled = true) {
                            vm.deleteEverything(context)
                            confirmingDelete = false
                        }
                        Spacer(Modifier.width(10.dp))
                        EchoButton("Keep it") { confirmingDelete = false }
                    }
                }
            }
        } else {
            EchoButton("Delete everything") { confirmingDelete = true }
        }

        Spacer(Modifier.height(34.dp))
        Hairline()
        Spacer(Modifier.height(20.dp))
        Figure("Echo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        Spacer(Modifier.height(56.dp))
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun AdvancedSection(vm: EchoViewModel, settings: com.mandar.echo.data.Settings) {
    val colors = EchoTheme.colors

    var urlDraft by remember(settings.sttServerUrl) { mutableStateOf(settings.sttServerUrl) }
    var keyDraft by remember(settings.sttApiKey) { mutableStateOf(settings.sttApiKey) }
    var upUrlDraft by remember(settings.uploadUrl) { mutableStateOf(settings.uploadUrl) }
    var upKeyDraft by remember(settings.uploadKey) { mutableStateOf(settings.uploadKey) }

    Column {
        Spacer(Modifier.height(20.dp))

        // ---- where transcription happens ----------------------------------
        Group("Where transcription happens")
        if (BuildConfig.PUBLIC_BUILD) {
            // The built-in endpoints are compiled out of a public build, so there
            // is nothing to fall back to and the copy must not pretend otherwise.
            Text(
                "This build ships with no server of its own. On device is the only backend " +
                    "that works out of the box; the other two need a server you run, and " +
                    "both send audio off this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.faint,
            )
            Spacer(Modifier.height(14.dp))
        }
        SttBackend.entries.forEach { backend ->
            val needsServer = backend != SttBackend.ON_DEVICE
            val configured = when (backend) {
                SttBackend.ON_DEVICE -> true
                SttBackend.CLOUD -> settings.sttServerUrl.startsWith("http")
                SttBackend.BATCH -> settings.uploadUrl.startsWith("http")
            }
            ChoiceRow(
                label = backend.label,
                body = if (needsServer && !configured) {
                    "${backend.note}. Not configured yet"
                } else {
                    backend.note
                },
                selected = settings.sttBackend == backend,
                onClick = { vm.setSttBackend(backend) },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(18.dp))

        // ---- your own server ----------------------------------------------
        Group("Your transcription server")
        Text(
            "A vexyl-stt server, for Hindi and Marathi. Audio is uploaded to it, so only " +
                "point this at something you control.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel("Server URL")
        Spacer(Modifier.height(8.dp))
        EchoTextField(
            value = urlDraft,
            onValueChange = { urlDraft = it },
            placeholder = "https://",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(10.dp))
        EchoTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            placeholder = "API key",
            masked = true,
        )
        Spacer(Modifier.height(12.dp))
        Row {
            // Saving is the point of the screen: a bad key halts the cloud path
            // until the settings that could fix it change, and this is that change.
            EchoButton("Save server", filled = true) { vm.setSttServer(urlDraft, keyDraft) }
            if (!BuildConfig.PUBLIC_BUILD) {
                Spacer(Modifier.width(10.dp))
                EchoButton("Use built-in") { vm.setSttServer("", "") }
            }
        }

        Spacer(Modifier.height(22.dp))

        // ---- batch upload ---------------------------------------------------
        Group("Batch upload service")
        Text(
            "The echo-upload service that mints signed upload URLs for the batch backend. " +
                "Audio is uploaded to your own bucket and transcribed there later.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(14.dp))
        EchoTextField(
            value = upUrlDraft,
            onValueChange = { upUrlDraft = it },
            placeholder = "https://",
            keyboardType = KeyboardType.Uri,
        )
        Spacer(Modifier.height(10.dp))
        EchoTextField(
            value = upKeyDraft,
            onValueChange = { upKeyDraft = it },
            placeholder = "Upload key",
            masked = true,
        )
        Spacer(Modifier.height(12.dp))
        EchoButton("Save upload service", filled = true) {
            vm.setUploadService(upUrlDraft, upKeyDraft)
        }

        Spacer(Modifier.height(22.dp))

        // ---- capture --------------------------------------------------------
        Group("Capture")
        SettingRow(
            title = "Skip silent recordings",
            body = "Most of a day is room tone. Transcribing it costs battery and makes " +
                "Whisper invent sentences that were never said.",
            trailing = {
                EchoSwitch(
                    checked = settings.skipSilentChunks,
                    onCheckedChange = vm::setSkipSilent,
                )
            },
        )
        Hairline()
        SettingRow(
            title = "Far-field microphone",
            body = "The right source for a room. The alternative applies near-field noise " +
                "suppression built for a phone held to your face.",
            trailing = {
                EchoSwitch(
                    checked = settings.audioSource == MediaRecorder.AudioSource.MIC,
                    onCheckedChange = { far ->
                        vm.setAudioSource(
                            if (far) MediaRecorder.AudioSource.MIC
                            else MediaRecorder.AudioSource.VOICE_RECOGNITION
                        )
                    },
                )
            },
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("Recording length")
        Spacer(Modifier.height(6.dp))
        Text(
            "How much audio Echo gathers before transcribing it. Shorter means transcripts " +
                "appear sooner and the pipeline runs more often.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.faint,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 15).forEach { minutes ->
                val selected = settings.chunkMinutes == minutes
                EchoButton(
                    "$minutes min",
                    filled = selected,
                    onClick = { vm.setChunkMinutes(minutes) },
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Group("Build")
        Text(
            remember { vm.whisperSystemInfo() },
            style = MaterialTheme.typography.bodySmall,
            color = colors.faint,
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun Group(title: String) {
    Column {
        SectionLabel(title)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DisclosureHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val colors = EchoTheme.colors
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        EchoMotion.standard(),
        label = "chevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation),
        )
    }
}

/**
 * The hour the day gets written up.
 *
 * Steppers rather than a time picker dialog: the value is only ever nudged by an
 * hour or two, and a full picker is a modal for a decision nobody agonises over.
 */
@Composable
private fun SummaryTimeRow(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    val colors = EchoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Written at",
                style = MaterialTheme.typography.titleSmall,
                color = colors.foreground,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Echo gathers the day and writes it up at this time.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.faint,
            )
        }
        Spacer(Modifier.width(12.dp))
        EchoButton(
            "%02d:%02d".format(hour, minute),
            contentDescription = "Summary time, %02d:%02d. Tap to move it an hour later"
                .format(hour, minute),
            onClick = { onChange((hour + 1) % 24, minute) },
        )
    }
}
