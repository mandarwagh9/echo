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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.EchoButton
import com.mandar.echo.ui.components.EchoCard
import com.mandar.echo.ui.components.EmptyState
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.IconAction
import com.mandar.echo.ui.components.MarkdownText
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.components.SkeletonLines
import com.mandar.echo.ui.components.StatTile
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import java.time.LocalDate

/**
 * One day, whole.
 *
 * The summary and the transcript used to be two separate tabs, which made sense
 * to the person who built the pipeline and to nobody else: they are two views of
 * the same day, and reading one almost always means wanting the other. Here the
 * summary is the head of the page and the transcript is the body of it.
 */
@Composable
fun DayScreen(vm: EchoViewModel, onBack: () -> Unit) {
    val colors = EchoTheme.colors

    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val summary by vm.summaryForDay.collectAsStateWithLifecycle()
    val segments by vm.segmentsForDay.collectAsStateWithLifecycle()
    val chunks by vm.chunksForDay.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()

    val words = remember(segments) {
        segments.sumOf { seg -> seg.text.split(WHITESPACE).count { it.isNotBlank() } }
    }
    val capturedMs = remember(chunks) { chunks.sumOf { it.durationMs } }
    val byHour = remember(segments) { segments.groupBy { Format.hour(it.startMs) }.toSortedMap() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // ---- header -------------------------------------------------------
        Column(Modifier.padding(horizontal = Gutter)) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClick = onBack)
                Row {
                    IconAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") {
                        vm.selectDate(date.minusDays(1))
                    }
                    IconAction(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        "Next day",
                        enabled = date.isBefore(LocalDate.now()),
                    ) {
                        vm.selectDate(date.plusDays(1))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                Format.dayTitle(date),
                style = MaterialTheme.typography.displayMedium,
                color = colors.foreground,
            )
            // dayTitle already *is* the full date for anything older than
            // yesterday, so printing daySubtitle underneath rendered the same
            // string twice.
            if (Format.dayTitle(date) != Format.daySubtitle(date)) {
                Spacer(Modifier.height(4.dp))
                Figure(Format.daySubtitle(date))
            }
            Spacer(Modifier.height(22.dp))

            // Only when there is no summary. The summary states the day's own
            // figures, computed from settled chunks by SummaryEngine, and these
            // tiles count the segments and chunks that fall inside the day's
            // wall-clock bounds. The two do not agree and never will: on 8
            // August these read 24 h / 15,884 words directly above a summary
            // saying 22 h 59 min / 13,887. Both are defensible and having them
            // argue on one screen is not, so the summary wins when it exists.
            if (summary == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatTile(Format.duration(capturedMs), "captured")
                    StatTile(Format.count(words), "words")
                    StatTile(byHour.size.toString(), "active hours")
                }
                Spacer(Modifier.height(22.dp))
            }
        }

        Hairline()

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = Gutter)) {

            // ---- summary --------------------------------------------------
            item(key = "summary") {
                Spacer(Modifier.height(24.dp))
                val current = summary
                when {
                    current != null -> Column {
                        Text(
                            current.headline,
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.foreground,
                        )
                        if (current.provisional) {
                            Spacer(Modifier.height(12.dp))
                            EchoCard(accented = true) {
                                Text(
                                    "Written while recordings were still being transcribed, so " +
                                        "it is missing part of the day. Echo rewrites it once " +
                                        "the queue drains.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.muted,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        SelectionContainer { MarkdownText(current.bodyMarkdown) }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Figure("Written ${Format.clock(current.generatedAt)}")
                            Spacer(Modifier.width(14.dp))
                            EchoButton("Rewrite", onClick = vm::generateSummaryNow)
                        }
                    }

                    // A day with speech in it but no summary yet is the normal
                    // state before 11 PM, and is worth distinguishing from a day
                    // that has nothing in it at all.
                    segments.isNotEmpty() -> Column {
                        Text(
                            "No summary yet",
                            style = MaterialTheme.typography.headlineMedium,
                            color = colors.foreground,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (pending > 0) {
                                "Echo writes the day up at the end of it. There are still " +
                                    "recordings waiting to be transcribed."
                            } else {
                                "Echo writes the day up at the end of it, and you can ask for " +
                                    "it early."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.faint,
                        )
                        if (pending > 0) {
                            Spacer(Modifier.height(16.dp))
                            SkeletonLines(count = 3)
                        }
                        Spacer(Modifier.height(16.dp))
                        EchoButton("Write it now", onClick = vm::generateSummaryNow)
                    }

                    else -> Unit
                }
                Spacer(Modifier.height(30.dp))
            }

            // ---- transcript -----------------------------------------------
            if (segments.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "Nothing transcribed",
                        body = "No speech was written down for this day. Either nothing was " +
                            "recorded, or what was recorded was silence.",
                    )
                }
            } else {
                item(key = "transcript-label") {
                    SectionLabel("Transcript")
                    Spacer(Modifier.height(8.dp))
                }
                byHour.forEach { (hour, hourSegments) ->
                    item(key = "hour-$hour") {
                        Spacer(Modifier.height(20.dp))
                        Figure("%02d:00".format(hour), color = colors.accent)
                        Spacer(Modifier.height(12.dp))
                    }
                    items(hourSegments, key = { it.id }) { SegmentRow(it) }
                }
            }

            item(key = "tail") { Spacer(Modifier.height(64.dp)) }
        }
    }
}

private val WHITESPACE = Regex("\\s+")

@Composable
private fun SegmentRow(segment: SegmentEntity) {
    val colors = EchoTheme.colors
    // Per row, deliberately not around the LazyColumn. A SelectionContainer
    // measures its content so selection can span children, which is the one
    // thing a lazy list will not let it do; copying a single line is the actual
    // use here anyway.
    SelectionContainer {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 15.dp)
                .semantics(mergeDescendants = true) { }
        ) {
            Figure(Format.clock(segment.startMs), modifier = Modifier.width(56.dp))
            Text(
                segment.text,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
            )
        }
    }
}
