package com.termprompter

/**
 * Tolerance profile. [tol] is 1 (strict) .. 5 (very forgiving).
 * Higher tolerance = more skipped words allowed, fewer words needed to call a phrase "read",
 * fuzzier word comparison, and more unmatched words before an ad-lib is declared.
 */
class Profile(val tol: Int, val lang: Lang) {
    val skip = tol + 1                                         // words that may be skipped
    val ratio = floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f)[(tol - 1).coerceIn(0, 4)]
    val adlibMisses = tol + 2                                  // consecutive unmatched words => ad-lib
    val resync = if (tol >= 4) 2 else 3                        // matched words in a row to rejoin script
    val tailSlack = if (tol >= 3) 2 else if (tol == 2) 1 else 0 // unread words allowed at phrase end

    fun maxEdit(len: Int): Int {
        var e = when {
            len <= 2 -> 0
            len <= 4 -> 1
            len <= 7 -> 2
            else -> 3
        }
        if (tol <= 2) e = maxOf(0, e - 1)
        // Bisaya is usually decoded with a Filipino (or other) acoustic model, so allow more spelling drift.
        if (lang == Lang.BISAYA && len >= 4) e += 1
        return e
    }
}

/**
 * Follows the speaker through the script.
 *
 * - Advances to the next phrase only when the current phrase has been (nearly) fully read.
 * - Pace-independent: it reacts to words heard, not to a timer.
 * - If [adlibMisses] words in a row do not match the script, it enters ad-lib mode
 *   (no advancing) and keeps listening until the speaker re-reads [Profile.resync] script words in a row
 *   near the expected position.
 */
class Matcher {
    var profile = Profile(3, Lang.ENGLISH)

    var cur = 0; private set                 // phrase currently being read
    var adlib = false; private set
    var finished = false; private set

    private var g: List<String> = emptyList()      // all tokens, flattened
    private var pStart = IntArray(0)
    private var pEnd = IntArray(0)
    private var tokPhrase = IntArray(0)
    private var count = 0

    private var pos = 0          // next expected global token
    private var hits = 0         // tokens matched in current phrase
    private var miss = 0         // consecutive misses
    private var consumed = 0     // words of the current utterance already processed
    private var run = 0          // resync run length
    private var runLast = -1

    private val fillers = setOf("uh", "um", "ah", "er", "hm", "mm", "ahm", "eh")

    fun load(phrases: List<Phrase>) {
        count = phrases.size
        pStart = IntArray(count); pEnd = IntArray(count)
        val all = ArrayList<String>()
        val tp = ArrayList<Int>()
        for ((i, p) in phrases.withIndex()) {
            pStart[i] = all.size
            for (t in p.tokens) { all.add(t); tp.add(i) }
            pEnd[i] = all.size
        }
        g = all
        tokPhrase = tp.toIntArray()
        reset()
    }

    fun reset() {
        cur = nextNonEmpty(0)
        finished = cur >= count
        pos = if (finished) 0 else pStart[cur]
        hits = 0; miss = 0; adlib = false; consumed = 0; run = 0; runLast = -1
    }

    /** Call when the recognizer finalizes an utterance (the next partial starts from zero words). */
    fun endUtterance() { consumed = 0 }

    /** Feed the full word list of the current utterance. Returns true if cur/adlib changed. */
    fun feed(words: List<String>): Boolean {
        if (finished) return false
        if (words.size < consumed) consumed = words.size   // hypothesis was revised/shortened
        var changed = false
        for (i in consumed until words.size) {
            if (finished) break
            if (step(words[i])) changed = true
        }
        consumed = words.size
        return changed
    }

    private fun nextNonEmpty(from: Int): Int {
        var i = from
        while (i < count && pStart[i] == pEnd[i]) i++
        return i
    }

    private fun step(w: String): Boolean {
        if (w in fillers || g.isEmpty()) return false
        return if (adlib) stepAdlib(w) else stepTracking(w)
    }

    private fun stepTracking(w: String): Boolean {
        val limit = minOf(g.size, pos + profile.skip + 1)
        var k = -1
        for (j in pos until limit) {
            // Skipping ahead is only trusted for content words (4+ letters). Short common words
            // ("the", "ang", "sa") appear everywhere and would let ad-lib speech jump the script.
            if (j > pos + 1 && g[j].length < 4) continue
            if (TextUtil.close(w, g[j], profile.maxEdit(g[j].length))) { k = j; break }
        }
        if (k < 0) {
            miss++
            if (miss >= profile.adlibMisses) {
                adlib = true; run = 0; runLast = -1
                return true
            }
            return false
        }
        miss = 0
        return apply(k)
    }

    /** Heard the script token at global index [k]. */
    private fun apply(k: Int): Boolean {
        var changed = false
        val pk = tokPhrase[k]
        if (pk > cur) { cur = pk; hits = 0; changed = true }   // speaker moved into a later phrase
        hits++
        pos = k + 1
        if (isComplete()) {
            cur = nextNonEmpty(cur + 1)
            hits = 0
            changed = true
            if (cur >= count) { finished = true } else if (pos < pStart[cur]) pos = pStart[cur]
        }
        return changed
    }

    private fun isComplete(): Boolean {
        val n = pEnd[cur] - pStart[cur]
        if (n == 0) return true
        val need = maxOf(1, Math.ceil((n * profile.ratio).toDouble()).toInt())
        val slack = if (n <= 4) 0 else profile.tailSlack
        return pos >= pEnd[cur] - slack && hits >= need
    }

    private fun stepAdlib(w: String): Boolean {
        // Rejoin window: the whole current phrase (speaker may restart it) plus the next phrase.
        val from = pStart[cur]
        val last = minOf(count - 1, nextNonEmpty(cur + 1).let { if (it >= count) cur else it })
        val to = pEnd[last]
        var k = -1
        for (j in from until to) {
            if (TextUtil.close(w, g[j], profile.maxEdit(g[j].length))) {
                // prefer continuing the current run
                if (run > 0 && j in (runLast + 1)..(runLast + 1 + profile.skip)) { k = j; break }
                if (k < 0) k = j
            }
        }
        if (k < 0) { run = 0; runLast = -1; return false }
        run = if (run > 0 && k in (runLast + 1)..(runLast + 1 + profile.skip)) run + 1 else 1
        runLast = k
        if (run < profile.resync) return false

        // Speaker is back on script.
        adlib = false; miss = 0
        val pk = tokPhrase[k]
        if (pk != cur) { cur = pk; hits = 0 }
        hits = maxOf(hits, run)
        pos = k + 1
        if (isComplete()) {
            cur = nextNonEmpty(cur + 1); hits = 0
            if (cur >= count) finished = true else if (pos < pStart[cur]) pos = pStart[cur]
        }
        return true
    }
}
