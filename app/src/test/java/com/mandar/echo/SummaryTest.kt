package com.mandar.echo

import com.mandar.echo.summary.SummaryEngine
import com.mandar.echo.summary.SummaryScheduler
import com.mandar.echo.summary.TextTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TextToolsTest {

    @Test
    fun `tokenizer handles english and devanagari`() {
        val tokens = TextTools.tokenize("Hello there, आज मीटिंग होती.")
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("आज"))
        assertTrue(tokens.contains("मीटिंग"))
    }

    @Test
    fun `stopwords are stripped in both scripts`() {
        val content = TextTools.contentWords("the meeting was about आणि the प्रोजेक्ट")
        assertFalse(content.contains("the"))
        assertFalse(content.contains("आणि"))
        assertTrue(content.contains("meeting"))
        assertTrue(content.contains("प्रोजेक्ट"))
    }

    @Test
    fun `devanagari is detected`() {
        assertTrue(TextTools.isDevanagari("काय झालं"))
        assertFalse(TextTools.isDevanagari("what happened"))
    }

    @Test
    fun `sentence splitting respects the devanagari danda`() {
        val sentences = TextTools.sentences("आज मी ऑफिसला गेलो होतो। नंतर आम्ही जेवायला बाहेर गेलो।")
        assertEquals(2, sentences.size)
    }

    @Test
    fun `distinctive terms favour today over the background corpus`() {
        val today = "sensor calibration sensor calibration drone flight drone flight meeting meeting"
        val background = List(6) { "meeting meeting meeting standup standup" }

        val terms = TextTools.distinctiveTerms(today, background, limit = 5).map { it.first }

        assertTrue("today-specific terms should surface", terms.contains("sensor") || terms.contains("drone"))
        // "meeting" is frequent today but also in every background day, so it must
        // rank below the terms that make today distinctive.
        val meetingRank = terms.indexOf("meeting")
        assertTrue("generic term should not lead", meetingRank != 0)
    }

    @Test
    fun `key sentences returns everything when input is small`() {
        val sentences = listOf("This is the first sentence here.", "And a second one follows.")
        assertEquals(2, TextTools.keySentences(sentences, take = 5).size)
    }

    @Test
    fun `key sentences are chronological and bounded`() {
        val sentences = (1..40).map { "Sentence number $it about drones and sensors and calibration work." }
        val picked = TextTools.keySentences(sentences, take = 5)
        assertEquals(5, picked.size)
        val indices = picked.map { sentences.indexOf(it) }
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun `names need repeat mentions and are not sentence-initial artefacts`() {
        val sentences = listOf(
            "I spoke to Rohit about the launch.",
            "Later Rohit sent the deck over.",
            "Meeting was fine.",
        )
        val names = TextTools.candidateNames(sentences).map { it.first }
        assertTrue(names.contains("Rohit"))
        assertFalse("sentence-initial words are not names", names.contains("Meeting"))
        assertFalse(names.contains("Later"))
    }

    @Test
    fun `a name is still counted when it starts a sentence`() {
        // Regression: the first pass only qualified non-initial words, so a name
        // that opened a sentence was invisible and fell below the mention floor.
        val sentences = listOf(
            "I spoke to Rohit about the launch.",
            "Rohit said he would send the deck.",
        )
        val names = TextTools.candidateNames(sentences)
        assertTrue("Rohit should be found twice: $names", names.any { it.first == "Rohit" && it.second == 2 })
    }

    @Test
    fun `a word that only ever starts sentences is not a name`() {
        val sentences = listOf(
            "Meeting was fine today.",
            "Meeting ran over by an hour.",
        )
        assertTrue(TextTools.candidateNames(sentences).none { it.first == "Meeting" })
    }

    @Test
    fun `short transcripts still yield topics`() {
        // With the old fixed >=2 floor a short day produced no topics at all.
        val terms = TextTools.distinctiveTerms(
            "कॅलिब्रेशन सेंसर ड्रोन उड्डाण चाचणी", emptyList(),
        )
        assertTrue("no topics from a short Devanagari transcript", terms.isNotEmpty())
        assertTrue(terms.all { it.first.length >= 3 })
    }

    @Test
    fun `empty input does not crash`() {
        assertTrue(TextTools.distinctiveTerms("", emptyList()).isEmpty())
        assertTrue(TextTools.keySentences(emptyList()).isEmpty())
        assertTrue(TextTools.candidateNames(emptyList()).isEmpty())
    }

    // ---- repetition collapse ------------------------------------------------
    //
    // A stuck decoder emitted "the security is over" seven times in a row on the
    // real device, and 150 of one chunk's 168 segments were repeats. Without this
    // filter the loop wins every topic and the whole word count.

    @Test
    fun `a runaway loop is collapsed to a couple of mentions`() {
        val loop = List(200) { "the security is over" }
        val kept = TextTools.dropRepeats(loop) { it }
        assertEquals(2, kept.size)
    }

    @Test
    fun `genuine repetition of a short phrase is still allowed twice`() {
        val day = listOf("okay", "let us start the meeting", "okay", "okay", "okay")
        val kept = TextTools.dropRepeats(day) { it }
        assertEquals(listOf("okay", "let us start the meeting", "okay"), kept)
    }

    @Test
    fun `collapse ignores case and punctuation`() {
        val kept = TextTools.dropRepeats(listOf("Yes.", "yes", "YES!", "yes,")) { it }
        assertEquals(2, kept.size)
    }

    @Test
    fun `segments with no words at all are dropped`() {
        // What a hallucination over silence decays to.
        val kept = TextTools.dropRepeats(listOf(".", " ", "...", "real words here")) { it }
        assertEquals(listOf("real words here"), kept)
    }

    @Test
    fun `Devanagari repeats collapse too`() {
        val kept = TextTools.dropRepeats(List(50) { "ठीक आहे" } + "उद्या सकाळी भेटू") { it }
        assertEquals(3, kept.size)
        assertTrue(kept.contains("उद्या सकाळी भेटू"))
    }

    @Test
    fun `distinct speech is left completely alone`() {
        val day = listOf(
            "morning standup with Rohit",
            "the sensor calibration is drifting",
            "we should rerun it before the flight test",
        )
        assertEquals(day, TextTools.dropRepeats(day) { it })
    }
}

class SummarySchedulerTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `before the target time schedules today`() {
        val now = at(2026, 8, 5, 14, 0)
        val next = SummaryScheduler.nextOccurrence(23, 0, now)
        assertEquals(at(2026, 8, 5, 23, 0), next)
    }

    @Test
    fun `after the target time rolls to tomorrow`() {
        val now = at(2026, 8, 5, 23, 30)
        val next = SummaryScheduler.nextOccurrence(23, 0, now)
        assertEquals(at(2026, 8, 6, 23, 0), next)
    }

    @Test
    fun `exactly at the target time rolls forward rather than firing twice`() {
        val now = at(2026, 8, 5, 23, 0)
        val next = SummaryScheduler.nextOccurrence(23, 0, now)
        assertEquals(at(2026, 8, 6, 23, 0), next)
    }

    @Test
    fun `next occurrence is always in the future`() {
        val now = System.currentTimeMillis()
        for (hour in 0..23) {
            assertTrue(SummaryScheduler.nextOccurrence(hour, 0, now) > now)
        }
    }
}

class SummaryFormatTest {

    @Test
    fun `minutes render as hours and minutes`() {
        assertEquals("45 min", SummaryEngine.formatMinutes(45))
        assertEquals("2 h", SummaryEngine.formatMinutes(120))
        assertEquals("2 h 5 min", SummaryEngine.formatMinutes(125))
    }

    @Test
    fun `sub-minute durations do not render as zero`() {
        assertEquals("under a minute", SummaryEngine.formatMinutes(0))
    }

    @Test
    fun `language codes map to names`() {
        assertEquals("English", SummaryEngine.languageName("en"))
        assertEquals("Hindi", SummaryEngine.languageName("hi"))
        assertEquals("Marathi", SummaryEngine.languageName("mr"))
        assertEquals("Unknown", SummaryEngine.languageName(""))
    }

    @Test
    fun `percent guards against divide by zero`() {
        assertEquals("0%", SummaryEngine.percent(5, 0))
        assertEquals("50%", SummaryEngine.percent(30, 60))
    }

    @Test
    fun `sub-minute speech no longer rounds away to zero`() {
        // 42 s of speech in a 30 min capture used to render as a flat "0%",
        // because the ratio was taken from truncated whole minutes (0 / 30).
        assertEquals("2%", SummaryEngine.percent(42L, 1800L))
        // Only a genuinely negligible share collapses, and even then not to "0%".
        assertEquals("<1%", SummaryEngine.percent(5L, 1800L))
        assertEquals("0%", SummaryEngine.percent(0L, 1800L))
    }
}
