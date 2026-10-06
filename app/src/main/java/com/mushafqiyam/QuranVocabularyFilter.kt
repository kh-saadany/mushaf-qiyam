package com.mushafqiyam

/**
 * QuranVocabularyFilter: Full-Mushaf (6,236 verses / ~77,400 words) Word-Level Sequential
 * Context Window Filter and Tracker.
 *
 * Features:
 * - Full-Mushaf Flattened Word Array: Initialized once on startup with O(1) verse pointer lookup.
 * - Narrow Sequential Window: Lookahead = 7 words, Lookbehind = 2 words (max 10 words active).
 * - Immediate Hallucination Shield: Any ASR output not matching the active window is rejected.
 * - Adaptive Window Expansion: If 3 consecutive non-empty frames have no match, the window expands to +15 words
 *   to allow graceful recovery from reciter skips.
 * - Smart Short-Particle Guard: Prevents 2-letter particles (من, في, لا, ما...) far ahead in the window
 *   from prematurely jumping to the next verse before the current verse finishes.
 */
object QuranVocabularyFilter {

    private const val TAG = "QuranFilter"

    private val TRANSITION_WORDS = setOf(
        "اعوذ", "بالله", "الشيطان", "الرجيم", "بسم", "امين", "صدق", "العظيم"
    )

    private val SHORT_PARTICLES = setOf(
        "من", "في", "ما", "لا", "ان", "هو", "هي", "له", "هم", "ثم", "او", "ام", "عن", "اذ", "بل", "قد", "لو", "لم", "لن"
    )

    data class QuranWord(
        val globalIndex: Int,
        val verseIndex: Int,
        val originalText: String,
        val normalizedText: String
    )

    data class TrackingResult(
        val filteredText: String,
        val matchedVerseIndex: Int?,
        val matchedVerse: MushafPageRepository.MushafVerse?,
        val matchedWordsCount: Int,
        val highestSimilarity: Double
    )

    private var allWords: List<QuranWord> = emptyList()
    private var verseFirstWordIndex: IntArray = IntArray(0)
    private var cachedVersesHash: Int = 0
    private var isFullMushafLoaded: Boolean = false

    // Current word pointer in the recitation sequence
    var activeWordPointer: Int = 0
        private set

    // Non-matching frames counter for adaptive recovery
    private var consecutiveMismatches: Int = 0

    /**
     * Initializes the full 6,236-verse Mushaf word sequence once on background thread.
     */
    @Synchronized
    fun initializeFullMushaf(mushafVerses: List<MushafPageRepository.MushafVerse>) {
        if (isFullMushafLoaded && allWords.isNotEmpty() && verseFirstWordIndex.size == mushafVerses.size) {
            return
        }

        val words = ArrayList<QuranWord>(78000)
        val firstWordMap = IntArray(mushafVerses.size)
        var globalIdx = 0

        for ((vIdx, mv) in mushafVerses.withIndex()) {
            firstWordMap[vIdx] = globalIdx
            val cleanVerse = FuzzyMatcher.normalizeArabic(mv.cleanText)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = mv.text.split(" ").filter { it.isNotBlank() && it.any { ch -> ch in '\u0621'..'\u064A' || ch == '\u0671' } }

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
        verseFirstWordIndex = firstWordMap
        isFullMushafLoaded = true
        activeWordPointer = 0
        consecutiveMismatches = 0
        AppLogger.i(TAG, "Full Mushaf vocabulary initialized: ${mushafVerses.size} verses, ${words.size} words.")
    }

    /**
     * Legacy initializer for string lists (kept for backward compatibility).
     */
    @Synchronized
    fun initializeVerses(verses: List<String>) {
        if (isFullMushafLoaded) return
        val hash = verses.hashCode()
        if (hash == cachedVersesHash && allWords.isNotEmpty()) return

        val words = ArrayList<QuranWord>()
        val firstWordMap = IntArray(verses.size)
        var globalIdx = 0
        for ((vIdx, verse) in verses.withIndex()) {
            firstWordMap[vIdx] = globalIdx
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
        verseFirstWordIndex = firstWordMap
        cachedVersesHash = hash
        activeWordPointer = 0
        consecutiveMismatches = 0
    }

    /**
     * Resets the word pointer to the start of a specific global verse index (0..6235) in O(1) time.
     */
    @Synchronized
    fun resetPointerToVerse(verseIndex: Int) {
        if (verseIndex in verseFirstWordIndex.indices) {
            activeWordPointer = verseFirstWordIndex[verseIndex]
        } else {
            val targetWord = allWords.firstOrNull { it.verseIndex == verseIndex }
            activeWordPointer = targetWord?.globalIndex ?: 0
        }
        consecutiveMismatches = 0
    }

    /**
     * Resets the word pointer to the start of a specific (surah, ayah) in the full Mushaf.
     */
    @Synchronized
    fun resetPointerToSurahAyah(surah: Int, ayah: Int) {
        val globalVerseIdx = MushafPageRepository.getGlobalVerseIndex(surah, ayah)
        resetPointerToVerse(globalVerseIdx)
        AppLogger.i(TAG, "Tracking pointer reset to Surah $surah Ayah $ayah (GlobalVerse=$globalVerseIdx, WordPtr=$activeWordPointer)")
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
     * Filters raw ASR text and tracks progression across the entire 6,236-verse Mushaf.
     */
    @Synchronized
    fun filterAndTrackFullMushaf(rawText: String): TrackingResult {
        if (!isFullMushafLoaded && MushafPageRepository.isInitialized) {
            initializeFullMushaf(MushafPageRepository.getAllVerses())
        }

        if (rawText.isBlank() || allWords.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0)
        }

        val cleanRaw = FuzzyMatcher.normalizeArabic(rawText)
        val rawWords = cleanRaw.split(" ").filter { it.isNotBlank() }
        if (rawWords.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0)
        }

        val currentVerseIdx = allWords.getOrNull(activeWordPointer)?.verseIndex ?: 0
        val window = getActiveContextWindow()
        val matchedObjects = ArrayList<QuranWord>(rawWords.size)
        var highestSim = 0.0

        for (rawWord in rawWords) {
            var bestWordMatch: QuranWord? = null
            var bestWordSim = 0.0
            var bestDistance = Int.MAX_VALUE

            for (expectedWord in window) {
                val sim = FuzzyMatcher.wordSimilarity(rawWord, expectedWord.normalizedText)
                val minRequiredSim = if (expectedWord.normalizedText.length <= 2) 0.85 else 0.72

                if (sim >= minRequiredSim) {
                    val dist = kotlin.math.abs(expectedWord.globalIndex - activeWordPointer)
                    // Prefer higher similarity, or closer word in the window on tie
                    if (sim > bestWordSim + 0.04 || (sim >= bestWordSim - 0.04 && dist < bestDistance)) {
                        bestWordSim = sim
                        bestWordMatch = expectedWord
                        bestDistance = dist
                    }
                }
            }

            if (bestWordMatch != null) {
                matchedObjects.add(bestWordMatch)
                if (bestWordSim > highestSim) highestSim = bestWordSim
            }
        }

        // Filter out isolated short particles that jump far ahead into a future verse on their own
        val substantiveMatches = matchedObjects.filter { w ->
            val isFutureVerse = w.verseIndex > currentVerseIdx
            val isFarAhead = w.globalIndex > activeWordPointer + 2
            val isShortParticle = w.normalizedText.length <= 2 || SHORT_PARTICLES.contains(w.normalizedText)
            if (isFutureVerse && isFarAhead && isShortParticle) {
                // Only keep if another non-particle word in that same future verse was also matched
                matchedObjects.any { other -> other !== w && other.verseIndex == w.verseIndex && other.normalizedText.length > 2 }
            } else {
                true
            }
        }

        if (substantiveMatches.isNotEmpty()) {
            consecutiveMismatches = 0
            val latestMatchedWord = substantiveMatches.maxByOrNull { it.globalIndex }!!

            // Advance pointer forward if matched word is at or ahead of current pointer
            if (latestMatchedWord.globalIndex >= activeWordPointer) {
                activeWordPointer = minOf(allWords.size - 1, latestMatchedWord.globalIndex + 1)
            }

            val matchedVerseObj = MushafPageRepository.getVerseByGlobalIndex(latestMatchedWord.verseIndex)
            return TrackingResult(
                filteredText = substantiveMatches.joinToString(" ") { it.originalText },
                matchedVerseIndex = latestMatchedWord.verseIndex,
                matchedVerse = matchedVerseObj,
                matchedWordsCount = substantiveMatches.size,
                highestSimilarity = highestSim
            )
        } else {
            // Do not penalize consecutiveMismatches if the user only recited Isti'adhah / Basmalah / Ameen
            val isOnlyTransitionWords = rawWords.all { rw ->
                TRANSITION_WORDS.any { tw -> FuzzyMatcher.wordSimilarity(rw, tw) >= 0.78 }
            }
            if (!isOnlyTransitionWords) {
                consecutiveMismatches++
            }
            return TrackingResult("", null, null, 0, 0.0)
        }
    }

    /**
     * Backward-compatible wrapper for string lists.
     */
    @Synchronized
    fun filterAndTrack(rawText: String, verses: List<String>): TrackingResult {
        if (isFullMushafLoaded) {
            return filterAndTrackFullMushaf(rawText)
        }
        if (allWords.isEmpty() || verses.hashCode() != cachedVersesHash) {
            initializeVerses(verses)
        }
        return filterAndTrackFullMushaf(rawText)
    }
}
