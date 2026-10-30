package com.termprompter

import java.text.Normalizer

/** Supported speech/script languages. [modelKey] names the folder of the offline Vosk model. */
enum class Lang(val code: String, val label: String, val modelKey: String) {
    ENGLISH("en", "English", "en"),
    FILIPINO("fil", "Filipino", "tl"),
    BISAYA("ceb", "Bisaya (Cebuano)", "ceb")
}

/** One displayed line/phrase of the script and its normalized spoken tokens. */
class Phrase(val text: String, val tokens: List<String>)

object TextUtil {
    private val diacritics = Regex("\\p{M}+")
    private val cues = Regex("\\[[^\\]]*]")             // [stage directions] are shown but never spoken
    private val wordSplit = Regex("[^\\p{L}\\p{N}'’]+")
    private val repeated = Regex("(.)\\1+")
    private val sentenceSplit = Regex("(?<=[.!?;:…])\\s+")
    private val commaSplit = Regex("(?<=,)\\s+")

    /**
     * Normalizes one word so that script text and recognizer output compare equal.
     * For Filipino/Bisaya, common spelling variants are folded together
     * (o/u, e/i, c/k, qu/k, ph/f, doubled letters) because recognizer output and
     * script spelling often differ in exactly these places.
     */
    fun normalize(raw: String, lang: Lang): String {
        var w = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(diacritics, "")
        w = w.filter { it.isLetterOrDigit() }
        if (lang != Lang.ENGLISH) {
            w = w.replace("ph", "f").replace("qu", "k")
                .replace('c', 'k').replace('o', 'u').replace('e', 'i')
            w = w.replace(repeated, "$1")
        }
        return w
    }

    fun tokenize(text: String, lang: Lang): List<String> =
        text.replace(cues, " ")
            .split(wordSplit)
            .map { normalize(it, lang) }
            .filter { it.isNotEmpty() }

    /** Splits a script into display phrases: per line, then per sentence, long sentences per comma. */
    fun splitPhrases(script: String, lang: Lang): List<Phrase> {
        val out = ArrayList<Phrase>()
        fun add(s: String) {
            val t = s.trim()
            if (t.isNotEmpty()) out.add(Phrase(t, tokenize(t, lang)))
        }
        for (line in script.replace("\r", "").split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) continue
            for (sentence in t.split(sentenceSplit)) {
                val s = sentence.trim()
                if (s.isEmpty()) continue
                if (s.length > 100) s.split(commaSplit).forEach { add(it) } else add(s)
            }
        }
        return out
    }

    fun close(a: String, b: String, maxEdit: Int): Boolean {
        if (a == b) return true
        if (maxEdit <= 0 || a.length < 2 || b.length < 2) return false
        if (kotlin.math.abs(a.length - b.length) > maxEdit) return false
        return levenshtein(a, b) <= maxEdit
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
