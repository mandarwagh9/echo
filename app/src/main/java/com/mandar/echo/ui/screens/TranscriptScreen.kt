package com.mandar.echo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.EmptyState
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MinTouchTarget
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import java.time.LocalDate

@Composable
fun TranscriptScreen(vm: EchoViewModel) {
    val colors = EchoTheme.colors

    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val daySegments by vm.segmentsForDay.collectAsStateWithLifecycle()
    val date by vm.selectedDate.collectAsStateWithLifecycle()

    val searching = query.isNotBlank()
    val shown = if (searching) results else daySegments

    val grouped = remember(shown) { shown.groupBy { Format.hour(it.startMs) }.toSortedMap() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Gutter),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                Format.dayTitle(date),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.foreground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton("◀", contentDescription = "Previous day") {
                    vm.selectDate(date.minusDays(1))
                }
                Spacer(Modifier.width(8.dp))
                PillButton(
                    "▶",
                    enabled = date.isBefore(LocalDate.now()),
                    contentDescription = "Next day",
                ) {
                    vm.selectDate(date.plusDays(1))
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        SearchField(value = query, onValueChange = vm::setQuery)

        Spacer(Modifier.height(18.dp))
        Hairline()

        if (shown.isEmpty()) {
            EmptyState(
                title = if (searching) "No matches" else "Nothing transcribed",
                body = if (searching) {
                    "No speech containing “$query” has been transcribed yet."
                } else {
                    "Transcripts appear here once a chunk finishes processing."
                },
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (searching) {
                item {
                    Spacer(Modifier.height(16.dp))
                    SectionLabel("${results.size} matches")
                    Spacer(Modifier.height(12.dp))
                }
                items(results, key = { it.id }) { SegmentRow(it, showDate = true) }
            } else {
                grouped.forEach { (hour, segments) ->
                    item(key = "hdr-$hour") {
                        Spacer(Modifier.height(22.dp))
                        SectionLabel("%02d:00".format(hour))
                        Spacer(Modifier.height(12.dp))
                    }
                    items(segments, key = { it.id }) { SegmentRow(it) }
                }
            }
            item { Spacer(Modifier.height(56.dp)) }
        }
    }
}

@Composable
private fun SegmentRow(segment: SegmentEntity, showDate: Boolean = false) {
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
            Column(Modifier.width(56.dp)) {
                Text(
                    Format.clock(segment.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.faint,
                )
                if (showDate) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        Format.dayShort(
                            java.time.Instant.ofEpochMilli(segment.startMs)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        // Was `hairline` — the rule colour, 1.2:1 against the page.
                        // In search results this is the only thing telling you
                        // which day a hit came from, and it was invisible.
                        color = colors.faint,
                    )
                }
            }
            Text(
                segment.text,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = EchoTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(100))
            .background(colors.surface)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
            cursorBrush = SolidColor(colors.foreground),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search
            ),
            modifier = Modifier.weight(1f).padding(vertical = 13.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        "Search everything you have said",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.faint,
                    )
                }
                inner()
            },
        )
        // An active search replaces the whole day view, and the only way back out
        // of one was to backspace it away a character at a time.
        if (value.isNotEmpty()) {
            Box(
                Modifier
                    .size(MinTouchTarget)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Clear search") {
                        onValueChange("")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.muted,
                )
            }
        } else {
            Spacer(Modifier.width(14.dp))
        }
    }
}
