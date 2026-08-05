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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandar.echo.ui.EchoViewModel
import com.mandar.echo.ui.Format
import com.mandar.echo.ui.components.Hairline
import com.mandar.echo.ui.components.MarkdownText
import com.mandar.echo.ui.components.PillButton
import com.mandar.echo.ui.components.SectionLabel
import com.mandar.echo.ui.theme.EchoTheme
import com.mandar.echo.ui.theme.Gutter
import java.time.LocalDate

@Composable
fun SummariesScreen(vm: EchoViewModel) {
    val colors = EchoTheme.colors

    val date by vm.selectedDate.collectAsStateWithLifecycle()
    val summary by vm.summaryForDay.collectAsStateWithLifecycle()
    val all by vm.summaries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

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
            Column {
                Text(
                    Format.dayTitle(date),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.foreground,
                )
                Spacer(Modifier.height(2.dp))
                SectionLabel(
                    "summary at %02d:%02d".format(settings.summaryHour, settings.summaryMinute)
                )
            }
            Row {
                PillButton("◀") { vm.selectDate(date.minusDays(1)) }
                Spacer(Modifier.width(8.dp))
                PillButton("▶", enabled = date.isBefore(LocalDate.now())) {
                    vm.selectDate(date.plusDays(1))
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        val current = summary
        if (current == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .padding(20.dp),
            ) {
                Column {
                    Text(
                        "No summary yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Echo writes this automatically at " +
                            "%02d:%02d".format(settings.summaryHour, settings.summaryMinute) +
                            ". You can also build it now from whatever has been transcribed so far.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton("Build it now", filled = true) { vm.generateSummaryNow() }
                }
            }
        } else {
            Text(
                current.headline,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.foreground,
            )
            Spacer(Modifier.height(6.dp))
            SectionLabel("written at ${Format.clock(current.generatedAt)}")
            Spacer(Modifier.height(10.dp))
            Hairline()
            MarkdownText(current.bodyMarkdown, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            PillButton("Rebuild") { vm.generateSummaryNow() }
        }

        if (all.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            Hairline()
            Spacer(Modifier.height(20.dp))
            SectionLabel("Earlier days")
            Spacer(Modifier.height(14.dp))

            all.forEach { item ->
                val itemDate = LocalDate.ofEpochDay(item.dayEpochDay)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.selectDate(itemDate) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        Format.dayShort(itemDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.faint,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.headline,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.foreground,
                    )
                }
                Hairline()
            }
        }

        Spacer(Modifier.height(56.dp))
    }
}
