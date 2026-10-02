package com.mushafqiyam

/**
 * QuranVocabularyFilter: Word-Level Sequential Context Window Filter and Tracker.
 *
 * Instead of exposing a wide 5-verse vocabulary (~60 words) that allows ASR noise/hallucinations,
 * this filter tracks recitation at the WORD level with a narrow lookahead window (6-8 words).
 *
 * Features:
 * - Narrow Sequential Window: Lookahead = 7 words, Lookbehind = 2 words (max 10 words active).
 * - Immediate Hallucination Shield: Any ASR output not matching the active 10-word window is instantly rejected.
 * - Adaptive Window Expansion: If 3 consecutive non-empty frames have no match, the window expands to +15 words
 *   to allow graceful recovery from reciter skips.
 * - Zero-GC Word Mapping: Verses are flattened into QuranWord tokens once upon initialization.
 */
object QuranVocabularyFilter {

    data class QuranWord(
        val globalIndex: Int,
        val verseIndex: Int,
        val originalText: String,
        val normalizedText: String
    )

    data class TrackingResult(
        val filteredText: String,
        val matchedVerseIndex: Int?,
        val matchedWordsCount: Int,
        val highestSimilarity: Double
    )

    private var allWords: List<QuranWord> = emptyList()
    private var cachedVersesHash: Int = 0

    // Current word pointer in the recitation sequence
    var activeWordPointer: Int = 0
        private set

    // Non-matching frames counter for adaptive recovery
    private var consecutiveMismatches: Int = 0

    /**
     * Initializes or updates the flattened word list from sample verses.
     */
    @Synchronized
    fun initializeVerses(verses: List<String>) {
        val hash = verses.hashCode()
        if (hash == cachedVersesHash && allWords.isNotEmpty()) return

        val words = ArrayList<QuranWord>()
        var globalIdx = 0
        for ((vIdx, verse) in verses.withIndex()) {
            val cleanVerse = FuzzyMatcher.normalizeArabic(verse)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = verse.split(" ").filter { it.isNotBlank() }
            for (wIdx in verseTokens.indices) {
                val orig = if (wIdx < rawTokens.size) rawTokens[wIdx] else verseTokens[wIdx]
                words.add(
                    QuranWord(
                        globalIndex = globalIdx++,
                        verseIndex = vIdx,
                        originalText = orig,
                        normalizedText = verseTokens[wIdx]
                    )
                )
            }
        }
        allWords = words
        cachedVersesHash = hash
        activeWordPointer = 0
        consecutiveMismatches = 0
    }

    /**
     * Resets the word pointer to the start of a specific verse.
     */
    @Synchronized
    fun resetPointerToVerse(verseIndex: Int) {
        val targetWord = allWords.firstOrNull { it.verseIndex == verseIndex }
        activeWordPointer = targetWord?.globalIndex ?: 0
        consecutiveMismatches = 0
    }

    /**
     * Retrieves the current narrow window of allowed words.
     */
    @Synchronized
    fun getActiveContextWindow(): List<QuranWord> {
        if (allWords.isEmpty()) return emptyList()

        val lookbehind = 2
        val lookahead = if (consecutiveMismatches >= 3) 15 else 7

        val start = maxOf(0, activeWordPointer - lookbehind)
        val end = minOf(allWords.size - 1, activeWordPointer + lookahead)

        return allWords.subList(start, end + 1)
    }

    /**
     * Backward-compatible helper returning normalized word strings for the current window.
     */
    @Synchronized
    fun getOrBuildAllowedWords(verses: List<String>, currentIndex: Int): Set<String> {
        if (allWords.isEmpty() || verses.hashCode() != cachedVersesHash) {
            initializeVerses(verses)
        }
        return getActiveContextWindow().map { it.normalizedText }.toSet()
    }

    /**
     * Filters raw ASR text and advances word pointer upon positive matches.
     */
    @Synchronized
    fun filterAndTrack(rawText: String, verses: List<String>): TrackingResult {
        if (allWords.isEmpty() || verses.hashCode() != cachedVersesHash) {
            initializeVerses(verses)
        }

        if (rawText.isBlank() || allWords.isEmpty()) {
            return TrackingResult(
                filteredText = "",
                matchedVerseIndex = null,
                matchedWordsCount = 0,
                highestSimilarity = 0.0
            )
        }

        val cleanRaw = FuzzyMatcher.normalizeArabic(rawText)
        val rawWords = cleanRaw.split(" ").filter { it.isNotBlank() }
        if (rawWords.isEmpty()) {
            return TrackingResult(
                filteredText = "",
                matchedVerseIndex = null,
                matchedWordsCount = 0,
                highestSimilarity = 0.0
            )
        }

        val window = getActiveContextWindow()
        val matchedAcceptedWords = ArrayList<String>()
        var highestSim = 0.0
        var latestMatchedWord: QuranWord? = null

        for (rawWord in rawWords) {
            var bestWordMatch: QuranWord? = null
            var bestWordSim = 0.0

            for (expectedWord in window) {
                val sim = if (rawWord == expectedWord.normalizedText) {
                    1.0
                } else {
                    FuzzyMatcher.wordSimilarity(rawWord, expectedWord.normalizedText)
                }

                if (sim >= 0.72 && sim > bestWordSim) {
                    bestWordSim = sim
                    bestWordMatch = expectedWord
                }
            }

            if (bestWordMatch != null && bestWordSim >= 0.72) {
                matchedAcceptedWords.add(bestWordMatch.originalText)
                if (bestWordSim > highestSim) highestSim = bestWordSim
                if (latestMatchedWord == null || bestWordMatch.globalIndex > latestMatchedWord.globalIndex) {
                    latestMatchedWord = bestWordMatch
                }
            }
        }

        return if (matchedAcceptedWords.isNotEmpty() && latestMatchedWord != null) {
            consecutiveMismatches = 0
            // Advance pointer forward if matched word is at or ahead of current pointer
            if (latestMatchedWord.globalIndex >= activeWordPointer) {
                activeWordPointer = minOf(allWords.size - 1, latestMatchedWord.globalIndex + 1)
            }

            TrackingResult(
                filteredText = matchedAcceptedWords.joinToString(" "),
                matchedVerseIndex = latestMatchedWord.verseIndex,
                matchedWordsCount = matchedAcceptedWords.size,
                highestSimilarity = highestSim
            )
        } else {
            consecutiveMismatches++
            TrackingResult(
                filteredText = "",
                matchedVerseIndex = null,
                matchedWordsCount = 0,
                highestSimilarity = 0.0
            )
        }
    }

    /**
     * Backward-compatible filterText delegating to window matching.
     */
    fun filterText(rawText: String, allowedWords: Set<String>): String {
        if (rawText.isBlank() || allowedWords.isEmpty()) return ""
        val cleanRaw = FuzzyMatcher.normalizeArabic(rawText)
        val rawWords = cleanRaw.split(" ").filter { it.isNotBlank() }

        val validWords = rawWords.filter { word ->
            allowedWords.contains(word) || allowedWords.any { aw -> FuzzyMatcher.wordSimilarity(word, aw) >= 0.75 }
        }
        return validWords.joinToString(" ")
    }
}

