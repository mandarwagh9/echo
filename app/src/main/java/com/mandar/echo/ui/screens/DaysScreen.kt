package com.mandar.echo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.SummaryEntity
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.EchoTextField
import com.mandar.echo.ui.components.EmptyState
import com.mandar.echo.ui.components.Figure
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MinTouchTarget
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The archive: every day that holds a recording, and one search box across all
 * of them.
 *
 * Search is the reason this screen is a destination rather than a list buried
 * inside a day. What a life recorder is actually for is "when did I say that",
 * and answering it should not require knowing which day to open first.
 */
@Composable
fun DaysScreen(vm: EchoViewModel, onOpenDay: (LocalDate) -> Unit) {
    val colors = EchoTheme.colors

    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val days by vm.recordedDays.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()

    val searching = query.isNotBlank()
    val summaryByDay = remember(summaries) { summaries.associateBy { it.dayEpochDay } }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(26.dp))
        Text(
            "Days",
            style = MaterialTheme.typography.displayMedium,
            color = colors.foreground,
        )
        Spacer(Modifier.height(20.dp))

        EchoTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = "Search everything you have said",
            leading = Icons.Default.Search,
            imeAction = ImeAction.Search,
            trailing = {
                // An active search replaces the whole list, and without this the
                // only way back out was to backspace it a character at a time.
                if (query.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(MinTouchTarget)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Clear search") {
                                vm.setQuery("")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = colors.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            },
        )

        Spacer(Modifier.height(20.dp))

        when {
            searching && results.isEmpty() -> EmptyState(
                title = "No matches",
                body = "Nothing transcribed so far contains “$query”.",
            )

            searching -> {
                Hairline()
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Spacer(Modifier.height(18.dp))
                        SectionLabel(
                            if (results.size == 1) "1 match" else "${results.size} matches"
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                    items(results, key = { it.id }) { segment ->
                        SearchResultRow(segment, onOpenDay = onOpenDay)
                    }
                    item { Spacer(Modifier.height(56.dp)) }
                }
            }

            days.isEmpty() -> EmptyState(
                title = "Nothing recorded yet",
                body = "Once Echo has listened for a while, every day it captured shows up " +
                    "here with its transcript and its summary.",
            )

            else -> {
                Hairline()
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(days, key = { it.toEpochDay() }) { day ->
                        DayRow(
                            day = day,
                            summary = summaryByDay[day.toEpochDay()],
                            onClick = { onOpenDay(day) },
                        )
                    }
                    item { Spacer(Modifier.height(56.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DayRow(day: LocalDate, summary: SummaryEntity?, onClick: () -> Unit) {
    val colors = EchoTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MinTouchTarget)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Open ${Format.dayTitle(day)}",
                    onClick = onClick,
                )
                .padding(vertical = 16.dp)
                .semantics(mergeDescendants = true) { },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Format.dayTitle(day),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.foreground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    summary?.headline?.takeIf { it.isNotBlank() }
                        ?: "No summary written for this day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(20.dp),
            )
        }
        Hairline()
    }
}

@Composable
private fun SearchResultRow(segment: SegmentEntity, onOpenDay: (LocalDate) -> Unit) {
    val colors = EchoTheme.colors
    val day = remember(segment.startMs) {
        Instant.ofEpochMilli(segment.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Open ${Format.dayTitle(day)}",
            ) { onOpenDay(day) }
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(Modifier.width(58.dp)) {
            Figure(Format.clock(segment.startMs))
            Spacer(Modifier.height(3.dp))
            // In search results this is the only thing saying which day a hit
            // came from, so it has to be readable rather than merely present.
            Figure(Format.dayShort(day), color = colors.faint)
        }
        Text(
            segment.text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.foreground,
        )
    }
}
