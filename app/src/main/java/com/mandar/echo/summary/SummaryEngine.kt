package com.mandar.echo.summary

import android.content.Context
import android.util.Log
import com.mandar.echo.audio.Notifications
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.EchoDatabase
import com.mandar.echo.data.SegmentEntity
import com.mandar.echo.data.SummaryEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private const val TAG = "SummaryEngine"

@Serializable
data class DayStats(
    val capturedMinutes: Int = 0,
    val speechMinutes: Int = 0,
    /** Kept alongside the minute figures so percentages do not inherit their truncation. */
    val capturedSeconds: Long = 0,
    val speechSeconds: Long = 0,
    val words: Int = 0,
    val chunks: Int = 0,
    val transcribed: Int = 0,
    val silent: Int = 0,
    val failed: Int = 0,
    val languages: Map<String, Int> = emptyMap(),
    val hourlyWords: List<Int> = List(24) { 0 },
    val topTerms: List<String> = emptyList(),
    val names: List<String> = emptyList(),
)

/**
 * Builds the end-of-day summary entirely on-device.
 *
 * Nothing here calls a network. The transcript never leaves the phone, which is
 * the whole point of the app; a cloud LLM would produce a nicer narrative but
 * would also mean uploading a recording of everyone the user spoke to that day.
 */
class SummaryEngine(
    context: Context,
    private val db: EchoDatabase,
) {
    private val appContext = context.applicationContext
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun generateAndNotifyForToday() {
        val today = LocalDate.now(zone)
        val summary = generate(today) ?: return
        Notifications.notify(
            appContext,
            Notifications.ID_SUMMARY,
            Notifications.summaryReady(appContext, summary.headline, firstParagraph(summary.bodyMarkdown)),
        )
    }

    /** Builds and persists the summary for [date]. Returns null if there is nothing to summarise. */
    suspend fun generate(date: LocalDate): SummaryEntity? {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val chunks = db.chunkDao().chunksBetween(from, to)
        val segments = db.segmentDao().between(from, to)

        if (chunks.isEmpty()) {
            Log.i(TAG, "no chunks for $date; skipping summary")
            return null
        }

        val capturedMs = chunks.sumOf { it.durationMs }
        val speechMs = segments.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
        val fullText = segments.joinToString(" ") { it.text }
        val sentenceList = TextTools.sentences(fullText)

        val background = backgroundCorpus(date)
        val terms = TextTools.distinctiveTerms(fullText, background)
        val names = TextTools.candidateNames(sentenceList)
        val highlights = TextTools.keySentences(sentenceList, take = 5)

        val hourly = IntArray(24)
        segments.forEach { seg ->
            val hour = Instant.ofEpochMilli(seg.startMs).atZone(zone).hour
            hourly[hour] += seg.text.split(Regex("\\s+")).count { it.isNotBlank() }
        }

        val languages = segments.groupingBy { it.language }.eachCount()
        val words = fullText.split(Regex("\\s+")).count { it.isNotBlank() }

        val stats = DayStats(
            capturedMinutes = (capturedMs / 60_000).toInt(),
            speechMinutes = (speechMs / 60_000).toInt(),
            capturedSeconds = capturedMs / 1000,
            speechSeconds = speechMs / 1000,
            words = words,
            chunks = chunks.size,
            transcribed = chunks.count { it.status == ChunkStatus.DONE },
            silent = chunks.count { it.status == ChunkStatus.SILENT },
            failed = chunks.count { it.status == ChunkStatus.FAILED },
            languages = languages,
            hourlyWords = hourly.toList(),
            topTerms = terms.map { it.first },
            names = names.map { it.first },
        )

        val headline = buildHeadline(stats)
        val body = buildBody(stats, highlights, names, hourly, segments)

        val entity = SummaryEntity(
            dayEpochDay = date.toEpochDay(),
            generatedAt = System.currentTimeMillis(),
            headline = headline,
            bodyMarkdown = body,
            statsJson = json.encodeToString(DayStats.serializer(), stats),
        )
        db.summaryDao().upsert(entity)
        Log.i(TAG, "summary for $date: $headline")
        return entity
    }

    fun parseStats(raw: String): DayStats =
        runCatching { json.decodeFromString(DayStats.serializer(), raw) }.getOrDefault(DayStats())

    // ---- composition ------------------------------------------------------

    private fun buildHeadline(s: DayStats): String {
        if (s.words == 0) {
            return "${formatMinutes(s.capturedMinutes)} captured · no speech detected"
        }
        val lang = s.languages.maxByOrNull { it.value }?.key?.let { languageName(it) }
        val langPart = if (lang != null) " · $lang" else ""
        return "${formatMinutes(s.speechMinutes)} of talk · ${"%,d".format(s.words)} words$langPart"
    }

    private fun buildBody(
        s: DayStats,
        highlights: List<String>,
        names: List<Pair<String, Int>>,
        hourly: IntArray,
        segments: List<SegmentEntity>,
    ): String = buildString {

        appendLine("## The shape of your day")
        appendLine()
        appendLine("- Captured **${formatMinutes(s.capturedMinutes)}** across ${s.chunks} chunks")
        appendLine("- **${formatMinutes(s.speechMinutes)}** contained speech (${percent(s.speechSeconds, s.capturedSeconds)} of the day)")
        appendLine("- **${"%,d".format(s.words)}** words transcribed")
        if (s.silent > 0) appendLine("- ${s.silent} chunks were silent and skipped")
        if (s.failed > 0) appendLine("- ⚠ ${s.failed} chunks failed to transcribe (audio kept for retry)")
        appendLine()

        val busiest = busiestWindows(hourly)
        if (busiest.isNotEmpty()) {
            appendLine("## When you talked")
            appendLine()
            busiest.forEach { (hour, words) ->
                appendLine("- **${"%02d:00".format(hour)}–${"%02d:00".format((hour + 1) % 24)}** · ${"%,d".format(words)} words")
            }
            appendLine()
        }

        if (s.languages.isNotEmpty()) {
            appendLine("## Languages")
            appendLine()
            val total = s.languages.values.sum().coerceAtLeast(1)
            s.languages.entries.sortedByDescending { it.value }.forEach { (code, count) ->
                appendLine("- ${languageName(code)} — ${(count * 100 / total)}%")
            }
            appendLine()
        }

        if (s.topTerms.isNotEmpty()) {
            appendLine("## What came up")
            appendLine()
            appendLine(s.topTerms.joinToString(" · "))
            appendLine()
        }

        if (names.isNotEmpty()) {
            appendLine("## Names mentioned")
            appendLine()
            appendLine(names.joinToString(" · ") { "${it.first} (${it.second})" })
            appendLine()
            appendLine("_Approximate. Detected from capitalisation, so this misses Hindi and Marathi names entirely._")
            appendLine()
        }

        if (highlights.isNotEmpty()) {
            appendLine("## Moments")
            appendLine()
            highlights.forEach { line ->
                val at = segments.firstOrNull { it.text.contains(line.take(24)) }?.startMs
                val stamp = at?.let {
                    Instant.ofEpochMilli(it).atZone(zone).let { z -> "%02d:%02d".format(z.hour, z.minute) }
                }
                if (stamp != null) appendLine("- **$stamp** — $line") else appendLine("- $line")
            }
            appendLine()
        }

        if (s.words == 0) {
            appendLine("Nothing was transcribed today. If that is unexpected, check that Echo had")
            appendLine("microphone access and was not paused.")
        }
    }

    private fun busiestWindows(hourly: IntArray): List<Pair<Int, Int>> =
        hourly.withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.index to it.value }
            .sortedBy { it.first }

    private suspend fun backgroundCorpus(date: LocalDate): List<String> =
        (1..7).mapNotNull { back ->
            val d = date.minusDays(back.toLong())
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            val segs = db.segmentDao().between(from, to)
            if (segs.isEmpty()) null else segs.joinToString(" ") { it.text }
        }

    private fun firstParagraph(markdown: String): String =
        markdown.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(3)
            .joinToString(" ")
            .replace(Regex("[*_`]"), "")
            .take(240)

    companion object {
        fun languageName(code: String): String = when (code) {
            "en" -> "English"
            "hi" -> "Hindi"
            "mr" -> "Marathi"
            "" -> "Unknown"
            else -> code.uppercase()
        }

        fun formatMinutes(minutes: Int): String = when {
            // Never render "0 min of talk" next to a real word count — integer
            // minutes truncate, so a genuinely short day looked like a bug.
            minutes <= 0 -> "under a minute"
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0 -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }

        fun percent(part: Long, whole: Long): String = when {
            whole <= 0L -> "0%"
            // A real but tiny share should read as "<1%", never as a flat 0%.
            part > 0L && part * 100.0 / whole < 0.5 -> "<1%"
            else -> "${(part * 100.0 / whole).roundToInt()}%"
        }

        fun percent(part: Int, whole: Int): String = percent(part.toLong(), whole.toLong())
    }
}
