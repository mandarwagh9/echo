package com.mandar.echo.summary

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Small, dependency-free text utilities for the offline summariser.
 *
 * Everything here is deliberately language-aware for English + Devanagari
 * (Hindi/Marathi), because a transcript of a real day in Pune is code-switched
 * and an English-only pipeline would throw away most of the signal.
 */
object TextTools {

    // \p{M} is essential, not decorative: Devanagari vowel signs and the virama
    // are combining marks, not letters. Without them "मीटिंग" tokenises as the
    // fragments "म", "ट", "ग" — which would silently destroy stopword removal and
    // topic extraction for every Hindi and Marathi transcript.
    private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}']+")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?।])\\s+|\\n+")

    /** Devanagari block, covering both Hindi and Marathi. */
    private val DEVANAGARI = 'ऀ'..'ॿ'

    private val EN_STOP = setOf(
        "the", "a", "an", "and", "or", "but", "if", "then", "than", "so", "because", "as",
        "of", "at", "by", "for", "with", "about", "against", "between", "into", "through",
        "during", "before", "after", "above", "below", "to", "from", "up", "down", "in",
        "out", "on", "off", "over", "under", "again", "further", "once", "here", "there",
        "when", "where", "why", "how", "all", "any", "both", "each", "few", "more", "most",
        "other", "some", "such", "no", "nor", "not", "only", "own", "same", "too", "very",
        "can", "will", "just", "should", "now", "i", "me", "my", "myself", "we", "our",
        "ours", "you", "your", "yours", "he", "him", "his", "she", "her", "it", "its",
        "they", "them", "their", "what", "which", "who", "whom", "this", "that", "these",
        "those", "am", "is", "are", "was", "were", "be", "been", "being", "have", "has",
        "had", "having", "do", "does", "did", "doing", "would", "could", "shall", "may",
        "might", "must", "yeah", "yes", "ok", "okay", "hmm", "uh", "um", "like", "know",
        "going", "get", "got", "one", "also", "really", "thing", "things", "actually",
        "right", "well", "want", "need", "make", "made", "see", "say", "said", "think",
    )

    private val HI_MR_STOP = setOf(
        // Hindi
        "है", "हैं", "था", "थी", "थे", "और", "को", "से", "के", "का", "की", "में", "यह",
        "वह", "हूँ", "हूं", "नहीं", "तो", "ही", "पर", "भी", "कि", "जो", "एक", "मैं", "हम",
        "आप", "तुम", "कर", "करना", "किया", "हो", "होगा", "गया", "लिए", "साथ", "बहुत",
        "अब", "क्या", "कोई", "कुछ", "सब", "जब", "तब", "अगर", "लेकिन", "या", "ने", "अपने",
        // Marathi
        "आहे", "आहेत", "होता", "होती", "होते", "आणि", "ला", "चा", "ची", "चे", "मध्ये",
        "तो", "ती", "ते", "मी", "तू", "आम्ही", "तुम्ही", "नाही", "पण", "किंवा", "हा", "ही",
        "हे", "का", "काय", "केले", "वर", "खूप", "आता", "मग", "तर", "असे", "करून", "एक",
        "साठी", "पण", "कधी", "जर", "सुद्धा", "अजून", "इथे", "तिथे", "बरं", "हो",
    )

    private val STOP = EN_STOP + HI_MR_STOP

    fun isDevanagari(text: String): Boolean = text.any { it in DEVANAGARI }

    fun tokenize(text: String): List<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.toList()

    fun contentWords(text: String): List<String> =
        tokenize(text).filter { it.length > 2 && it !in STOP && !it.all(Char::isDigit) }

    fun sentences(text: String): List<String> =
        SENTENCE_SPLIT.split(text).map { it.trim() }.filter { it.length > 12 }

    /**
     * Terms that are distinctive for *today* rather than merely frequent.
     *
     * Scored against a background corpus of previous days, so words the user says
     * constantly ("meeting", "chai") stop dominating every single summary.
     */
    fun distinctiveTerms(
        todayText: String,
        backgroundDocs: List<String>,
        limit: Int = 12,
    ): List<Pair<String, Double>> {
        val today = contentWords(todayText)
        if (today.isEmpty()) return emptyList()

        val tf = HashMap<String, Int>()
        today.forEach { tf[it] = (tf[it] ?: 0) + 1 }

        val docFreq = HashMap<String, Int>()
        backgroundDocs.forEach { doc ->
            contentWords(doc).toHashSet().forEach { w -> docFreq[w] = (docFreq[w] ?: 0) + 1 }
        }
        val n = backgroundDocs.size + 1

        // Requiring two occurrences suppresses noise on a full day, but on a short
        // one it drops everything. It also biases against Hindi and Marathi, which
        // inflect heavily — "कॅलिब्रेशन" and "कॅलिब्रेशनचे" are separate surface
        // forms, so an Indic term repeats less often than its English equivalent.
        val minCount = if (today.size >= 400) 2 else 1

        return tf.entries
            .filter { it.value >= minCount }
            .map { (term, count) ->
                val df = (docFreq[term] ?: 0) + 1
                val idf = ln(n.toDouble() / df) + 1.0
                term to (1.0 + ln(count.toDouble())) * idf
            }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * TextRank over sentences. Capped at [maxSentences] because similarity is
     * O(n²) and a full day can run to thousands of sentences on a phone.
     */
    fun keySentences(allSentences: List<String>, take: Int = 5, maxSentences: Int = 300): List<String> {
        if (allSentences.isEmpty()) return emptyList()
        if (allSentences.size <= take) return allSentences

        val pool = if (allSentences.size <= maxSentences) allSentences else {
            // Keep the most information-dense sentences rather than an arbitrary prefix.
            allSentences.sortedByDescending { contentWords(it).size }.take(maxSentences)
        }

        val tokens = pool.map { contentWords(it).toHashSet() }
        val n = pool.size
        val weights = Array(n) { DoubleArray(n) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val a = tokens[i]
                val b = tokens[j]
                if (a.isEmpty() || b.isEmpty()) continue
                val overlap = a.count { it in b }.toDouble()
                if (overlap == 0.0) continue
                val sim = overlap / (sqrt(a.size.toDouble()) * sqrt(b.size.toDouble()))
                weights[i][j] = sim
                weights[j][i] = sim
            }
        }

        var rank = DoubleArray(n) { 1.0 / n }
        val damping = 0.85
        repeat(25) {
            val next = DoubleArray(n) { (1 - damping) / n }
            for (i in 0 until n) {
                val outSum = weights[i].sum()
                if (outSum == 0.0) continue
                for (j in 0 until n) {
                    if (weights[i][j] > 0) next[j] += damping * rank[i] * weights[i][j] / outSum
                }
            }
            rank = next
        }

        return rank.indices
            .sortedByDescending { rank[it] }
            .take(take)
            .sorted()                      // restore chronological order
            .map { pool[it] }
    }

    /**
     * Best-effort name spotting.
     *
     * Latin script only: a capitalised token that is not sentence-initial and
     * recurs. Devanagari has no case distinction, so names there are genuinely not
     * recoverable without a trained NER model — the UI labels this section
     * approximate rather than pretending otherwise.
     */
    fun candidateNames(sentences: List<String>, minMentions: Int = 2): List<Pair<String, Int>> {
        val notNames = setOf("I", "I'm", "OK", "Okay", "Yeah", "And", "But", "The", "So", "Then", "Well")

        fun wordsOf(sentence: String) = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun clean(raw: String) = raw.trim('.', ',', '!', '?', '"', '\'', '(', ')', ':', ';', '।')
        fun looksLikeName(word: String) =
            word.length >= 3 &&
                word !in notNames &&
                word.first().isUpperCase() &&
                word.drop(1).all { it.isLowerCase() }

        // Two passes, because position alone is not enough. A word only qualifies
        // as a name if it appears somewhere that is *not* the start of a sentence
        // (otherwise every sentence-initial "Meeting" or "Later" qualifies) — but
        // once it has qualified, its sentence-initial uses count too. Without the
        // second pass, "Rohit said ..." would be invisible.
        val qualified = HashSet<String>()
        sentences.forEach { sentence ->
            wordsOf(sentence).forEachIndexed { index, raw ->
                if (index > 0) {
                    val word = clean(raw)
                    if (looksLikeName(word)) qualified += word
                }
            }
        }

        val counts = HashMap<String, Int>()
        sentences.forEach { sentence ->
            wordsOf(sentence).forEach { raw ->
                val word = clean(raw)
                if (word in qualified) counts[word] = (counts[word] ?: 0) + 1
            }
        }

        return counts.entries
            .filter { it.value >= minMentions }
            .sortedByDescending { it.value }
            .take(10)
            .map { it.key to it.value }
    }
}
