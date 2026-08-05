package com.mandar.echo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mandar.echo.data.ChunkEntity
import com.mandar.echo.data.ChunkStatus
import com.mandar.echo.data.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Exercises the daily summary against a realistic code-switched day (English,
 * Hindi and Marathi in the same transcript), on the real Room database.
 *
 * This is deliberately the test that carries the Devanagari load: a unit-test
 * regression here previously hid a tokenizer bug where combining marks were
 * excluded, shredding "मीटिंग" into "म"/"ट"/"ग" and silently destroying topic
 * extraction for every non-English transcript.
 *
 * WARNING — DESTRUCTIVE. [seed] clears chunks, segments and summaries from the
 * real application database so the assertions have a known corpus. Never run this
 * suite against a phone whose recordings you want to keep; `verify-on-device.ps1`
 * therefore skips instrumented tests unless `-RunTests` is passed explicitly.
 */
@RunWith(AndroidJUnit4::class)
class SummaryEngineInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as EchoApp
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun todayAt(hour: Int, minute: Int = 0): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() +
            hour * 3_600_000L + minute * 60_000L

    @Before
    fun seed() = runBlocking {
        app.db.chunkDao().deleteAll()
        app.db.summaryDao().deleteAll()

        data class Line(val hour: Int, val minute: Int, val lang: String, val text: String)

        val lines = listOf(
            Line(9, 5, "en", "Morning standup with Rohit about the drone flight controller."),
            Line(9, 12, "en", "Rohit said the sensor calibration is still drifting badly."),
            Line(9, 30, "en", "We agreed to rerun the calibration before the next flight test."),
            Line(14, 2, "hi", "आज दोपहर को मीटिंग थी और हमने प्रोजेक्ट के बारे में बात की।"),
            Line(14, 20, "hi", "सेंसर की कैलिब्रेशन अभी ठीक नहीं है, कल फिर देखेंगे।"),
            Line(19, 40, "mr", "संध्याकाळी आम्ही ऑफिसमधून निघालो आणि जेवायला बाहेर गेलो।"),
            Line(19, 55, "mr", "उद्या सकाळी परत कॅलिब्रेशनचे काम करायचे आहे।"),
        )

        // One chunk per hour bucket that has speech.
        lines.groupBy { it.hour }.forEach { (hour, group) ->
            val startedAt = todayAt(hour)
            val chunkId = app.db.chunkDao().insert(
                ChunkEntity(
                    startedAt = startedAt,
                    endedAt = startedAt + 600_000,
                    filePath = null,
                    sampleCount = 16_000L * 600,
                    status = ChunkStatus.DONE,
                    audioDeleted = true,
                    speechRatio = 0.4f,
                    wordCount = group.sumOf { it.text.split(" ").size },
                )
            )
            app.db.segmentDao().insertAll(
                group.map { line ->
                    val at = todayAt(line.hour, line.minute)
                    SegmentEntity(
                        chunkId = chunkId,
                        startMs = at,
                        endMs = at + 6_000,
                        text = line.text,
                        language = line.lang,
                    )
                }
            )
        }
    }

    @Test
    fun generatesASummaryForACodeSwitchedDay() = runBlocking {
        val summary = app.summaryEngine.generate(LocalDate.now(zone))
        assertTrue("no summary produced", summary != null)
        val s = summary!!

        android.util.Log.i("EchoTest", "headline: ${s.headline}")
        android.util.Log.i("EchoTest", "body:\n${s.bodyMarkdown}")

        assertTrue(s.headline.isNotBlank())
        assertTrue("headline should report words: ${s.headline}", s.headline.contains("words"))

        val body = s.bodyMarkdown
        assertTrue(body.contains("## The shape of your day"))
        assertTrue(body.contains("## When you talked"))
        assertTrue(body.contains("## Languages"))

        // All three languages must be represented, and named in English.
        assertTrue(body.contains("English"))
        assertTrue(body.contains("Hindi"))
        assertTrue(body.contains("Marathi"))

        val stats = app.summaryEngine.parseStats(s.statsJson)
        assertEquals(3, stats.chunks)
        assertEquals(setOf("en", "hi", "mr"), stats.languages.keys)
        assertTrue("no words counted", stats.words > 30)

        // The busiest-hour timeline must reflect the hours we actually seeded.
        val activeHours = stats.hourlyWords.withIndex().filter { it.value > 0 }.map { it.index }
        assertEquals(listOf(9, 14, 19), activeHours)

        // Persisted and re-readable.
        val stored = app.db.summaryDao().forDay(LocalDate.now(zone).toEpochDay())
        assertTrue(stored != null)
        assertEquals(s.headline, stored!!.headline)
    }

    /** The regression guard for the combining-mark tokenizer bug. */
    @Test
    fun devanagariTermsSurviveIntoTopics() = runBlocking {
        val s = app.summaryEngine.generate(LocalDate.now(zone))!!
        val stats = app.summaryEngine.parseStats(s.statsJson)
        android.util.Log.i("EchoTest", "topics: ${stats.topTerms}")

        assertTrue("no topics extracted", stats.topTerms.isNotEmpty())

        // At least one topic must be a full Devanagari word, not a shredded
        // consonant fragment. Real words here are 3+ characters.
        val devanagari = stats.topTerms.filter { term -> term.any { it in 'ऀ'..'ॿ' } }
        assertTrue("no Devanagari topics: ${stats.topTerms}", devanagari.isNotEmpty())
        assertTrue(
            "Devanagari topics look shredded: $devanagari",
            devanagari.any { it.length >= 3 },
        )
    }

    @Test
    fun namesAreDetectedFromEnglishSpeech() = runBlocking {
        val s = app.summaryEngine.generate(LocalDate.now(zone))!!
        val stats = app.summaryEngine.parseStats(s.statsJson)
        android.util.Log.i("EchoTest", "names: ${stats.names}")
        assertTrue("Rohit should be detected (2 mentions)", stats.names.contains("Rohit"))
    }
}
