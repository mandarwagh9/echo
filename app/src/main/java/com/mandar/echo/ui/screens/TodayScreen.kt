package com.mandar.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.LevelMeter
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.RecordButton
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.StatTile
import com.mandar.echo.ui.components.ThinProgress
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun TodayScreen(vm: EchoViewModel, onOpenSettings: () -> Unit) {
    val colors = EchoTheme.colors
    val context = LocalContext.current

    val settings by vm.settings.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val paused by vm.pausedReason.collectAsStateWithLifecycle()
    val levels by vm.levels.collectAsStateWithLifecycle()
    val sessionStart by vm.sessionStartedAt.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val pipeline by vm.pipelineState.collectAsStateWithLifecycle()
    val segments by vm.segmentsForDay.collectAsStateWithLifecycle()
    val chunks by vm.chunksForDay.collectAsStateWithLifecycle()
    val dropped by vm.droppedSamples.collectAsStateWithLifecycle()
    val download by vm.downloadState.collectAsStateWithLifecycle()

    // Keyed on download state too: modelFile does not change when a download
    // finishes, so without it the "install a model" notice would never clear.
    // A configured server is a transcriber too. Gating recording on a local model
    // regardless of backend is what left 11 chunks queued and 0 words written:
    // capture was blocked here while the pipeline was perfectly able to upload.
    // Deliberately the same predicate the pipeline uses, including the language
    // check: with English selected the server is not a transcriber at all (it
    // coerces unmapped codes to Malayalam, so the client never sends them), and
    // claiming otherwise here would let recording start with nothing able to run.
    val usingServer = settings.sttBackend == com.mandar.echo.data.SttBackend.CLOUD &&
        settings.sttServerUrl.startsWith("http") &&
        com.mandar.echo.stt.CloudTranscriber.supports(settings.language.code)
    val hasModel = remember(settings.modelFile, download, usingServer) {
        usingServer || vm.modelsInstalled().isNotEmpty()
    }
    val words = remember(segments) {
        segments.sumOf { seg -> seg.text.split(Regex("\\s+")).count { it.isNotBlank() } }
    }
    val capturedMs = remember(chunks) { chunks.sumOf { it.durationMs } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ECHO",
                style = MaterialTheme.typography.labelSmall,
                color = colors.foreground,
            )
            Text(
                Format.daySubtitle(LocalDate.now()),
                style = MaterialTheme.typography.labelSmall,
                color = colors.faint,
            )
        }

        Spacer(Modifier.height(52.dp))

        // ---- live state ---------------------------------------------------

        Text(
            text = when {
                recording -> "Listening"
                paused != null -> "Paused"
                else -> "Not listening"
            },
            style = MaterialTheme.typography.displayMedium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(6.dp))
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
                .height(72.dp),
        )

        Spacer(Modifier.height(30.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            RecordButton(recording = recording) { vm.toggleRecording(context) }
        }

        Spacer(Modifier.height(36.dp))
        Hairline()
        Spacer(Modifier.height(22.dp))

        // ---- today's numbers ----------------------------------------------

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatTile(Format.duration(capturedMs), "captured")
            StatTile(Format.count(words), "words")
            StatTile(if (pending == 0) "—" else pending.toString(), "in queue")
        }

        Spacer(Modifier.height(22.dp))
        Hairline()

        // ---- transcription state ------------------------------------------

        if (pipeline.busy) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel("Transcribing")
                Text(
                    "${pipeline.progress}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
            Spacer(Modifier.height(10.dp))
            ThinProgress(pipeline.progress / 100f)
            if (pipeline.realtimeFactor > 0f) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${"%.1f".format(pipeline.realtimeFactor)}× realtime" +
                        if (pipeline.realtimeFactor < 1f) " — falling behind" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pipeline.realtimeFactor < 1f) colors.foreground else colors.faint,
                )
            }
        }

        // ---- warnings ------------------------------------------------------

        if (!hasModel) {
            Notice(
                title = "Install a speech model",
                body = "Echo transcribes on your phone, so it needs to download a model " +
                    "once. Nothing is sent anywhere after that. Alternatively, point it " +
                    "at your own transcription server in Settings.",
                actionLabel = "Open settings",
                onAction = onOpenSettings,
            )
        }

        // Cloud selected with nothing local to fall back on. Recording is allowed
        // -- audio is kept until it transcribes -- but a tunnel means a backlog,
        // and that is worth saying before it happens rather than after.
        if (usingServer && vm.modelsInstalled().isEmpty()) {
            Notice(
                title = "No offline fallback",
                body = "Transcription needs your server. Without a speech model installed, " +
                    "chunks recorded with no connection queue up until it can be reached.",
                actionLabel = "Open settings",
                onAction = onOpenSettings,
            )
        }

        if (dropped > 0) {
            Notice(
                title = "${Format.duration(dropped * 1000 / 16000)} of audio dropped",
                body = "Storage could not keep up with the microphone. This is recorded " +
                    "honestly rather than hidden — the gap is real.",
            )
        }

        pipeline.lastError?.let { error ->
            Notice(title = "Transcription problem", body = error)
        }

        // A park is a normal state of a recorder that lives on hotel wifi, but an
        // invisible one is how eighteen chunks queued in silence with nothing on
        // screen to say so. Shown only while there is actually a backlog waiting.
        if (!pipeline.busy && pending > 0) {
            pipeline.waiting?.let { reason ->
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SectionLabel("Waiting")
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
            }
        }

        // ---- latest speech --------------------------------------------------

        if (segments.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionLabel("Latest")
            Spacer(Modifier.height(14.dp))
            SelectionContainer {
                Column {
                    segments.takeLast(6).reversed().forEach { seg ->
                        // Merged: unmerged, a screen reader reads the timestamp and
                        // the sentence as two unrelated items and you lose which is
                        // which.
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                                .semantics(mergeDescendants = true) { }
                        ) {
                            Text(
                                Format.clock(seg.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.faint,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                seg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.foreground,
                            )
                        }
                    }
                }
            }
        } else if (recording) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Nothing transcribed yet. The first transcript appears once the " +
                    "current ${settings.chunkMinutes}-minute chunk closes.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

/**
 * The line under "Listening" — and the only thing on this screen that changes
 * once a second.
 *
 * The tick lives in here rather than in [TodayScreen] so the invalidation stops
 * at this Text. Read at the top level it restarted the whole screen's scope
 * every second, level meter and transcript list included, for the sake of one
 * changing digit.
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

@Composable
private fun Notice(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = EchoTheme.colors
    Spacer(Modifier.height(20.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(18.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.foreground)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.muted)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                PillButton(actionLabel, onClick = onAction)
            }
        }
    }
}
