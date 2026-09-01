package com.mandar.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.data.SttBackend
import com.mandar.echo.stt.CloudTranscriber
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.LevelMeter
import com.mandar.echo.ui.components.LiveChip
import com.mandar.echo.ui.components.Notice
import com.mandar.echo.ui.components.RecordControl
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.StatTile
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import kotlinx.coroutines.delay
import java.time.LocalDate

/**
 * Home. Always today, never a browsed date.
 *
 * The day browser owns [EchoViewModel.selectedDate]; this screen reads the
 * separate today flows. Sharing one date between the two meant opening yesterday
 * silently blanked this screen, which reads as data loss rather than navigation.
 */
@Composable
fun HomeScreen(
    vm: EchoViewModel,
    onOpenSettings: () -> Unit,
    onOpenToday: () -> Unit,
) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val paused by vm.pausedReason.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val sessionStart by vm.sessionStartedAt.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val awaiting by vm.awaitingRemote.collectAsStateWithLifecycle()
    val pipeline by vm.pipelineState.collectAsStateWithLifecycle()
    val segments by vm.segmentsToday.collectAsStateWithLifecycle()
    val chunks by vm.chunksToday.collectAsStateWithLifecycle()
    val dropped by vm.droppedSamples.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()

    // A configured server is a transcriber too, so gating capture on a local
    // model regardless of backend is what once left chunks queued and zero words
    // written. Deliberately the same predicate the pipeline uses, language check
    // included: with English selected the server is not a transcriber at all, as
    // it coerces unmapped codes to Malayalam and the client never sends them.
    val usingServer = settings.sttBackend == SttBackend.CLOUD &&
        settings.sttServerUrl.startsWith("http") &&
        CloudTranscriber.supports(settings.language.code)

    // Keyed on the download state as well: the model file name does not change
    // when a download finishes, so without it this notice would never clear.
    val hasModel = remember(settings.modelFile, download, usingServer) {
        usingServer || vm.modelsInstalled().isNotEmpty()
    }
    val words = remember(segments) {
        segments.sumOf { seg -> seg.text.split(WHITESPACE).count { it.isNotBlank() } }
    }
    val capturedMs = remember(chunks) { chunks.sumOf { it.durationMs } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(26.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Echo",
                style = MaterialTheme.typography.titleMedium,
                color = colors.foreground,
            )
            if (recording) LiveChip("Listening") else Figure(Format.daySubtitle(LocalDate.now()))
        }

        Spacer(Modifier.height(44.dp))

        Text(
            text = when {
                recording -> "Listening"
                paused != null -> "Paused"
                else -> "Not listening"
            },
            style = MaterialTheme.typography.displayLarge,
            color = colors.foreground,
        )
        Spacer(Modifier.height(8.dp))
        StatusLine(
            recording = recording,
            sessionStart = sessionStart,
            paused = paused,
            hasModel = hasModel,
        )

        Spacer(Modifier.height(30.dp))

        LevelMeter(
            levels = levels,
            active = recording,
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
        )

        Spacer(Modifier.height(30.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            RecordControl(recording = recording) { vm.toggleRecording(context) }
        }

        Spacer(Modifier.height(38.dp))
        Hairline()
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(Format.duration(capturedMs), "captured")
            StatTile(Format.count(words), "words")
            StatTile(if (pending == 0) "0" else pending.toString(), "in queue", emphasised = pending > 0)
        }

        Spacer(Modifier.height(24.dp))
        Hairline()

        if (pipeline.busy) {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Transcribing",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.foreground,
                )
                Figure("${pipeline.progress}%", color = colors.accent)
            }
            Spacer(Modifier.height(11.dp))
            ThinProgress(pipeline.progress / 100f)
            if (pipeline.realtimeFactor > 0f) {
                Spacer(Modifier.height(9.dp))
                val behind = pipeline.realtimeFactor < 1f
                Text(
                    "%.1f times realtime".format(pipeline.realtimeFactor) +
                        if (behind) ", falling behind" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (behind) colors.accent else colors.faint,
                )
            }
        }

        // ---- things worth interrupting for -------------------------------

        if (!hasModel) {
            Spacer(Modifier.height(22.dp))
            Notice(
                title = "No speech model installed",
                body = "Echo transcribes on this phone, so it needs to download a model once. " +
                    "Until then it records but writes nothing down.",
                accented = true,
                actionLabel = "Open settings",
                onAction = onOpenSettings,
            )
        }

        if (usingServer && vm.modelsInstalled().isEmpty()) {
            Spacer(Modifier.height(22.dp))
            Notice(
                title = "No offline fallback",
                body = "Transcription needs your server. With no model installed, anything " +
                    "recorded without a connection queues up until it can be reached.",
                actionLabel = "Open settings",
                onAction = onOpenSettings,
            )
        }

        if (dropped > 0) {
            Spacer(Modifier.height(22.dp))
            Notice(
                title = "${Format.duration(dropped * 1000 / 16000)} of audio dropped",
                body = "Storage could not keep up with the microphone. The gap is real, and " +
                    "is recorded rather than hidden.",
                accented = true,
            )
        }

        pipeline.lastError?.let { error ->
            Spacer(Modifier.height(22.dp))
            Notice(title = "Transcription problem", body = error, accented = true)
        }

        // Uploaded and waiting. Without a line for it, a day on the batch backend
        // reads as zero words and an empty queue while everything is in flight.
        if (awaiting > 0) {
            Spacer(Modifier.height(22.dp))
            Notice(
                title = if (awaiting == 1) "1 recording being transcribed elsewhere" else
                    "$awaiting recordings being transcribed elsewhere",
                body = "Uploaded and waiting on the batch pipeline. The audio stays on this " +
                    "phone until the words come back.",
            )
        }

        // A park is normal for a recorder living on hotel wifi. An invisible one
        // is how eighteen chunks queued in silence with nothing on screen saying so.
        if (!pipeline.busy && pending > 0) {
            pipeline.waiting?.let { reason ->
                Spacer(Modifier.height(22.dp))
                Notice(title = "Waiting", body = reason)
            }
        }

        // ---- the last few things you said --------------------------------

        Spacer(Modifier.height(30.dp))
        if (segments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Latest")
                EchoButton("Open today", onClick = onOpenToday)
            }
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Column {
                    segments.takeLast(5).reversed().forEach { seg ->
                        // Merged: unmerged, a screen reader reads the timestamp
                        // and the sentence as two unrelated items and you lose
                        // which is which.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 15.dp)
                                .semantics(mergeDescendants = true) { }
                        ) {
                            Figure(Format.clock(seg.startMs), modifier = Modifier.width(54.dp))
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.foreground,
                            )
                        }
                    }
                }
            }
        } else {
            Text(
                if (recording) {
                    "Nothing transcribed yet. The first transcript appears when the current " +
                        "${settings.chunkMinutes} minute chunk closes."
                } else {
                    "Nothing recorded today. Tap the circle above to start."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.faint,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

private val WHITESPACE = Regex("\\s+")

/**
 * The line under the status word, and the only thing on this screen that changes
 * once a second.
 *
 * The tick lives in here rather than in [HomeScreen] so the invalidation stops
 * at this one Text. Read at the top level it restarted the whole screen's scope
 * every second, level meter and transcript list included, for one changing digit.
 */
@Composable
private fun StatusLine(
    recording: Boolean,
    sessionStart: Long?,
    paused: String?,
    hasModel: Boolean,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Text(
        text = when {
            recording && sessionStart != null -> "for ${Format.elapsed(sessionStart, now)}"
            paused != null -> paused
            !hasModel -> "No speech model installed yet"
            else -> "Tap to start capturing your day"
        },
        style = MaterialTheme.typography.bodyLarge,
        color = EchoTheme.colors.muted,
    )
}
