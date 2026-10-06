package com.mushafqiyam

import kotlin.math.abs

/**
 * QuranVocabularyFilter: Full-Mushaf (6,236 verses / ~77,430 words) Sequential Tracker,
 * Fast Inverted-Index Discovery Engine, and Rakah / Al-Fatihah State Machine.
 *
 * Key Capabilities:
 * 1. Monotonic Chain Matching: Enforces strictly increasing word order (idx_1 < idx_2 < ...)
 *    so noisy ASR outputs like "يقوم بعيد" or "وتكون السماء" never jump out of sequence.
 * 2. Universal Two-Ordered-Words Verse Transition Rule:
 *    - Moving to a new verse requires 2 ordered words in that verse (either in the same frame
 *      or across consecutive frames via pendingNextVerseWord), with an automatic exception for
 *      the 28 single-word verses in the Quran (such as "مدهامتان", "والضحى", "والعصر", "الرحمن").
 *    - Dynamic Overlap Priority: For the 133 consecutive verse pairs sharing 2+ similar words,
 *      if the shared words still lie ahead of activeWordPointer in the current verse, the current
 *      verse takes priority; once activeWordPointer passes them in the current verse, the next
 *      verse transitions immediately on its first 2 words without needing distinguishing end words.
 * 3. Al-Fatihah & Rakah Cycle Intelligence:
 *    - Detects Al-Fatihah at the start of any Rakah from any page -> switches to Page 1 (Surah 1).
 *    - Upon reaching the end of Al-Fatihah ("ولا الضالين" or saying "آمين"), does NOT advance to
 *      Page 2 (Al-Baqarah); instead switches to DISCOVERY mode to detect whatever Surah/Ayah is recited next.
 * 4. Two-Stage Skeleton Inverted Index Discovery Engine (< 2ms per frame):
 *    - Discovers any recited verse across all 6,236 verses using a rolling word buffer, IDF rarity
 *      weighting, and continuity bonus for the verse following the previous Rakah's stopping point.
 */
object QuranVocabularyFilter {

    private const val TAG = "QuranFilter"

    enum class RecitationMode {
        DISCOVERY,
        TRACKING
    }

    private val TRANSITION_WORDS = setOf(
        "اعوذ", "بالله", "الشيطان", "الرجيم", "بسم", "امين", "ءامين", "صدق", "العظيم"
    )

    private val AMEEN_WORDS = setOf(
        "امين", "ءامين", "اميين"
    )

    private val PRAYER_TAKBIR_WORDS = setOf(
        "اكبر", "سمع", "حمده", "سبحان", "الاعلي"
    )

    private val SHORT_PARTICLES = setOf(
        "من", "في", "ما", "لا", "ان", "هو", "هي", "له", "هم", "ثم", "او", "ام", "عن", "اذ", "بل", "قد", "لو", "لم", "لن", "به", "لي", "لك"
    )

    data class QuranWord(
        val globalIndex: Int,
        val verseIndex: Int,
        val wordIndexInVerse: Int,
        val originalText: String,
        val normalizedText: String,
        val skeletonText: String
    )

    data class TrackingResult(
        val filteredText: String,
        val matchedVerseIndex: Int?,
        val matchedVerse: MushafPageRepository.MushafVerse?,
        val matchedWordsCount: Int,
        val highestSimilarity: Double,
        val recitationMode: RecitationMode = RecitationMode.TRACKING,
        val fatihahCompletedNow: Boolean = false,
        val discoveredNewLocation: Boolean = false
    )

    private var allWords: List<QuranWord> = emptyList()
    private var verseFirstWordIndex: IntArray = IntArray(0)
    private var verseWordCount: IntArray = IntArray(0)
    private var cachedVersesHash: Int = 0
    private var isFullMushafLoaded: Boolean = false

    // Inverted index for < 2ms global discovery across 6,236 verses
    private val skeletonToGlobalIndices = HashMap<String, IntArray>(16384)
    private var skeletonsByLength: Array<MutableList<String>> = Array(25) { ArrayList() }

    // Current mode and word pointer in the recitation sequence
    var recitationMode: RecitationMode = RecitationMode.DISCOVERY
        private set

    var activeWordPointer: Int = 0
        private set

    private var confirmedVerseIndex: Int = 0
    private var consecutiveMismatches: Int = 0

    // Cross-frame 2-word confirmation state for transitioning to a new verse
    private var pendingNextVerseIndex: Int = -1
    private var pendingNextVerseFirstWordGlobalIdx: Int = -1
    private var pendingNextVerseSim: Double = 0.0
    private var pendingNextVerseText: String = ""

    // Rakah continuity memory: remembers the last non-Fatihah verse recited before starting a new Rakah
    var lastNonFatihahVerseIndex: Int = -1
        private set

    private var lastFatihahCompletedTimeMs: Long = 0L

    // Rolling word buffer for multi-frame discovery
    private data class RollingAsrWord(
        val normWord: String,
        val skeleton: String,
        val timestampMs: Long
    )
    private val discoveryRollingWords = ArrayList<RollingAsrWord>(12)

    /**
     * Initializes the full 6,236-verse Mushaf word sequence and inverted skeleton index once on startup.
     */
    @Synchronized
    fun initializeFullMushaf(mushafVerses: List<MushafPageRepository.MushafVerse>) {
        if (isFullMushafLoaded && allWords.isNotEmpty() && verseFirstWordIndex.size == mushafVerses.size) {
            return
        }

        val startTime = System.currentTimeMillis()
        val words = ArrayList<QuranWord>(78000)
        val firstWordMap = IntArray(mushafVerses.size)
        val countMap = IntArray(mushafVerses.size)
        val tempInverted = HashMap<String, IntArrayList>(16384)

        var globalIdx = 0

        for ((vIdx, mv) in mushafVerses.withIndex()) {
            firstWordMap[vIdx] = globalIdx
            val cleanVerse = FuzzyMatcher.normalizeArabic(mv.cleanText)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = mv.text.split(" ").filter {
                it.isNotBlank() && it.any { ch -> ch in '\u0621'..'\u064A' || ch == '\u0671' }
            }

            countMap[vIdx] = verseTokens.size

            for (wIdx in verseTokens.indices) {
                val orig = if (wIdx < rawTokens.size) rawTokens[wIdx] else verseTokens[wIdx]
                val norm = verseTokens[wIdx]
                val skel = FuzzyMatcher.skeletonWord(norm)
                val cIdx = globalIdx++

                words.add(
                    QuranWord(
                        globalIndex = cIdx,
                        verseIndex = vIdx,
                        wordIndexInVerse = wIdx,
                        originalText = orig,
                        normalizedText = norm,
                        skeletonText = skel
                    )
                )

                if (skel.length >= 2) {
                    var list = tempInverted[skel]
                    if (list == null) {
                        list = IntArrayList(8)
                        tempInverted[skel] = list
                    }
                    list.add(cIdx)
                }
            }
        }

        skeletonToGlobalIndices.clear()
        val byLen = Array(25) { ArrayList<String>() }
        for ((skel, intList) in tempInverted) {
            skeletonToGlobalIndices[skel] = intList.toIntArray()
            val lenBucket = skel.length.coerceIn(0, 24)
            byLen[lenBucket].add(skel)
        }

        allWords = words
        verseFirstWordIndex = firstWordMap
        verseWordCount = countMap
        skeletonsByLength = byLen
        isFullMushafLoaded = true
        activeWordPointer = 0
        confirmedVerseIndex = 0
        consecutiveMismatches = 0
        clearPendingVerseTransition()
        discoveryRollingWords.clear()

        val elapsed = System.currentTimeMillis() - startTime
        AppLogger.i(
            TAG,
            "Full Mushaf indexed in ${elapsed}ms: ${mushafVerses.size} verses, ${words.size} words, ${skeletonToGlobalIndices.size} unique skeletons."
        )
    }

    /**
     * Compact primitive int list helper to avoid boxing during index construction.
     */
    private class IntArrayList(capacity: Int) {
        var data = IntArray(capacity)
        var size = 0
        fun add(element: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }
        fun toIntArray(): IntArray = data.copyOf(size)
    }

    @Synchronized
    private fun clearPendingVerseTransition() {
        pendingNextVerseIndex = -1
        pendingNextVerseFirstWordGlobalIdx = -1
        pendingNextVerseSim = 0.0
        pendingNextVerseText = ""
    }

    /**
     * Switches the engine into global 6,236-verse DISCOVERY mode.
     */
    @Synchronized
    fun enterDiscoveryMode(reason: String = "Manual") {
        recitationMode = RecitationMode.DISCOVERY
        consecutiveMismatches = 0
        clearPendingVerseTransition()
        discoveryRollingWords.clear()
        if (reason.contains("Fatihah") || reason.contains("Ameen")) {
            lastFatihahCompletedTimeMs = System.currentTimeMillis()
        }
        AppLogger.i(TAG, "Entered DISCOVERY mode ($reason). Last non-Fatihah verse index: $lastNonFatihahVerseIndex")
    }

    /**
     * Resets the word pointer to the start of a specific global verse index (0..6235) in O(1) time
     * and locks into TRACKING mode.
     */
    @Synchronized
    fun resetPointerToVerse(verseIndex: Int) {
        if (verseIndex in verseFirstWordIndex.indices) {
            activeWordPointer = verseFirstWordIndex[verseIndex]
            confirmedVerseIndex = verseIndex
        } else {
            val targetWord = allWords.firstOrNull { it.verseIndex == verseIndex }
            activeWordPointer = targetWord?.globalIndex ?: 0
            confirmedVerseIndex = targetWord?.verseIndex ?: 0
        }
        recitationMode = RecitationMode.TRACKING
        consecutiveMismatches = 0
        clearPendingVerseTransition()
        discoveryRollingWords.clear()

        val mv = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)
        if (mv != null && mv.surah > 1) {
            lastNonFatihahVerseIndex = confirmedVerseIndex
        }
    }

    /**
     * Resets the word pointer to the start of a specific (surah, ayah) in the full Mushaf
     * and locks into TRACKING mode.
     */
    @Synchronized
    fun resetPointerToSurahAyah(surah: Int, ayah: Int) {
        val globalVerseIdx = MushafPageRepository.getGlobalVerseIndex(surah, ayah)
        resetPointerToVerse(globalVerseIdx)
        AppLogger.i(TAG, "Tracking pointer locked to Surah $surah Ayah $ayah (GlobalVerse=$globalVerseIdx, WordPtr=$activeWordPointer)")
    }

    /**
     * Retrieves the current narrow window of allowed words around activeWordPointer.
     */
    @Synchronized
    fun getActiveContextWindow(): List<QuranWord> {
        if (allWords.isEmpty()) return emptyList()

        val lookbehind = 2
        val baseLookahead = if (consecutiveMismatches >= 3) 15 else 8
        val targetEnd = if (pendingNextVerseFirstWordGlobalIdx > activeWordPointer) {
            maxOf(activeWordPointer + baseLookahead, pendingNextVerseFirstWordGlobalIdx + 4)
        } else {
            activeWordPointer + baseLookahead
        }

        val start = maxOf(0, activeWordPointer - lookbehind)
        val end = minOf(allWords.size - 1, targetEnd)

        return allWords.subList(start, end + 1)
    }

    /**
     * Main unified entry point: handles DISCOVERY mode, TRACKING mode, Al-Fatihah Rakah cycle,
     * and automatic background recovery when the reciter jumps to a new Surah/Ayah.
     */
    @Synchronized
    fun filterAndTrackFullMushaf(rawText: String): TrackingResult {
        if (!isFullMushafLoaded && MushafPageRepository.isInitialized) {
            initializeFullMushaf(MushafPageRepository.getAllVerses())
        }

        if (rawText.isBlank() || allWords.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        val cleanRaw = FuzzyMatcher.normalizeArabic(rawText)
        val rawWords = cleanRaw.split(" ").filter { it.isNotBlank() }
        if (rawWords.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        // 1. Check if the user is currently in Surah Al-Fatihah (Surah 1) and said "آمين"
        val currentVerseObj = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)
        if (recitationMode == RecitationMode.TRACKING && currentVerseObj?.surah == 1 && confirmedVerseIndex >= 5) {
            val saidAmeen = rawWords.any { rw ->
                AMEEN_WORDS.any { aw -> FuzzyMatcher.wordSimilarity(rw, aw) >= 0.80 }
            }
            if (saidAmeen) {
                val fatihah7 = MushafPageRepository.getVerseByGlobalIndex(6) ?: currentVerseObj
                enterDiscoveryMode("Reciter said Ameen after Al-Fatihah")
                return TrackingResult(
                    filteredText = "آمين",
                    matchedVerseIndex = 6,
                    matchedVerse = fatihah7,
                    matchedWordsCount = 1,
                    highestSimilarity = 1.0,
                    recitationMode = RecitationMode.DISCOVERY,
                    fatihahCompletedNow = true
                )
            }
        }

        // 2. If in DISCOVERY mode, run global 6,236-verse discovery engine
        if (recitationMode == RecitationMode.DISCOVERY) {
            val discovered = discoverVerseFromRawWords(rawWords)
            if (discovered != null) {
                return discovered
            }
            return TrackingResult("", null, null, 0, 0.0, RecitationMode.DISCOVERY)
        }

        // 3. While in TRACKING mode on a non-Fatihah Surah, check if reciter stood up for a new Rakah and started Al-Fatihah
        if (currentVerseObj != null && currentVerseObj.surah != 1) {
            val fatihahJump = detectAlFatihahStart(rawWords)
            if (fatihahJump != null) {
                AppLogger.i(TAG, "New Rakah detected: switching to Surah Al-Fatihah [${fatihahJump.matchedVerse?.surah}:${fatihahJump.matchedVerse?.ayah}]")
                return fatihahJump
            }
        }

        // 4. Normal Sequential Window Tracking with Monotonic Chain & Two-Ordered-Words Rule
        val windowResult = trackWithinSequentialWindow(rawWords)
        if (windowResult.filteredText.isNotBlank()) {
            discoveryRollingWords.clear()
            return windowResult
        }

        // 5. Auto-Recovery: if tracking has >= 3 consecutive mismatches, feed words to global discovery
        //    so if the reciter jumped to another Surah or page, we find it seamlessly!
        if (consecutiveMismatches >= 3) {
            val recovered = discoverVerseFromRawWords(rawWords)
            if (recovered != null) {
                AppLogger.i(
                    TAG,
                    "Auto-Recovery discovered new verse [${recovered.matchedVerse?.surah}:${recovered.matchedVerse?.ayah}] after $consecutiveMismatches mismatches"
                )
                return recovered
            }
        }

        return windowResult
    }

    /**
     * Tracks recitation within the active sequential window using:
     * - Dynamic Programming Monotonic Chain matching (strictly increasing globalIndex).
     * - Two-Ordered-Words Verse Transition Rule (same-frame or cross-frame via pendingNextVerseIndex),
     *   with single-word verse exemption (28 verses) and dynamic overlap protection (133 verse pairs).
     */
    private fun trackWithinSequentialWindow(rawWords: List<String>): TrackingResult {
        val window = getActiveContextWindow()
        if (window.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        // Step A: Find all candidate matches in the window for each rawWord
        // Candidate(rawIdx, quranWord, similarity)
        data class WordCandidate(
            val rawIdx: Int,
            val word: QuranWord,
            val sim: Double
        )

        val candidatesPerRaw = ArrayList<List<WordCandidate>>(rawWords.size)
        for ((rIdx, rawWord) in rawWords.withIndex()) {
            val list = ArrayList<WordCandidate>(4)
            for (expectedWord in window) {
                val sim = FuzzyMatcher.wordSimilarity(rawWord, expectedWord.normalizedText)
                val minRequiredSim = if (expectedWord.normalizedText.length <= 2) 0.85 else 0.72
                if (sim >= minRequiredSim) {
                    list.add(WordCandidate(rIdx, expectedWord, sim))
                }
            }
            if (list.isNotEmpty()) {
                candidatesPerRaw.add(list)
            }
        }

        if (candidatesPerRaw.isEmpty()) {
            handleMismatchFrame(rawWords)
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        // Flatten all candidates in order of rawIdx to build the best monotonic increasing chain
        val flatCandidates = ArrayList<WordCandidate>()
        for (group in candidatesPerRaw) {
            for (c in group) flatCandidates.add(c)
        }

        val n = flatCandidates.size
        val dpScore = DoubleArray(n)
        val dpPrev = IntArray(n) { -1 }
        val dpLen = IntArray(n) { 1 }

        var bestEndIdx = 0
        var bestTotalScore = -1.0

        for (i in 0 until n) {
            val ci = flatCandidates[i]
            // Proximity bonus: slightly prefer words closer to activeWordPointer (and in current confirmedVerseIndex)
            // This naturally resolves the 133 overlapping consecutive verses when shared words are still ahead in currentVerse!
            val distFromPtr = abs(ci.word.globalIndex - activeWordPointer)
            val proximityBonus = (0.12 - distFromPtr * 0.01).coerceAtLeast(0.0)
            val currentVerseBonus = if (ci.word.verseIndex == confirmedVerseIndex && ci.word.globalIndex >= activeWordPointer - 1) 0.08 else 0.0

            var maxScore = ci.sim + proximityBonus + currentVerseBonus
            var bestPrev = -1
            var maxLen = 1

            for (j in 0 until i) {
                val cj = flatCandidates[j]
                // Must come from an earlier rawWord AND a strictly earlier globalIndex in the Quran
                if (cj.rawIdx < ci.rawIdx && cj.word.globalIndex < ci.word.globalIndex) {
                    val indexStep = ci.word.globalIndex - cj.word.globalIndex
                    if (indexStep <= 5) {
                        val adjacencyBonus = when (indexStep) {
                            1 -> 0.45 // Directly consecutive words in the Quran!
                            2 -> 0.25 // 1 word skipped between them
                            else -> 0.10
                        }
                        val sameVerseBonus = if (ci.word.verseIndex == cj.word.verseIndex) 0.10 else 0.0
                        val candidateScore = dpScore[j] + ci.sim + proximityBonus + currentVerseBonus + adjacencyBonus + sameVerseBonus
                        if (candidateScore > maxScore) {
                            maxScore = candidateScore
                            bestPrev = j
                            maxLen = dpLen[j] + 1
                        }
                    }
                }
            }

            dpScore[i] = maxScore
            dpPrev[i] = bestPrev
            dpLen[i] = maxLen

            if (maxScore > bestTotalScore) {
                bestTotalScore = maxScore
                bestEndIdx = i
            }
        }

        // Reconstruct the best monotonic chain of QuranWords
        val chain = ArrayList<WordCandidate>()
        var curr = bestEndIdx
        while (curr != -1) {
            chain.add(flatCandidates[curr])
            curr = dpPrev[curr]
        }
        chain.reverse()

        // Filter out isolated short particles that jump into a future verse on their own
        val substantiveChain = chain.filter { c ->
            val w = c.word
            val isFutureVerse = w.verseIndex > confirmedVerseIndex
            val isFarAhead = w.globalIndex > activeWordPointer + 2
            val isShortParticle = w.normalizedText.length <= 2 || SHORT_PARTICLES.contains(w.normalizedText)
            if (isFutureVerse && isFarAhead && isShortParticle) {
                chain.any { other -> other !== c && other.word.verseIndex == w.verseIndex && other.word.normalizedText.length > 2 }
            } else {
                true
            }
        }

        if (substantiveChain.isEmpty()) {
            handleMismatchFrame(rawWords)
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        consecutiveMismatches = 0
        val highestSim = substantiveChain.maxOf { it.sim }

        // Group matched words in the monotonic chain by their verseIndex
        val wordsInCurrentVerse = substantiveChain.filter { it.word.verseIndex == confirmedVerseIndex }
        val latestWordOverall = substantiveChain.last().word
        val targetVerseIdx = latestWordOverall.verseIndex

        // Case 1: The chain stays within the currently confirmed verse (or earlier)
        if (targetVerseIdx <= confirmedVerseIndex) {
            val latestInCurrent = (wordsInCurrentVerse.lastOrNull() ?: substantiveChain.last()).word
            if (latestInCurrent.globalIndex >= activeWordPointer) {
                activeWordPointer = minOf(allWords.size - 1, latestInCurrent.globalIndex + 1)
            }
            // Clear any false pending jump since reciter is still in confirmedVerseIndex
            clearPendingVerseTransition()

            return buildTrackingResponse(
                matchedWords = substantiveChain.map { it.word },
                verseIdx = confirmedVerseIndex,
                highestSim = highestSim
            )
        }

        // Case 2: The chain reaches into a new verse (targetVerseIdx > confirmedVerseIndex)
        val wordsInTargetVerse = substantiveChain.filter { it.word.verseIndex == targetVerseIdx }
        val targetVerseTotalWords = if (targetVerseIdx in verseWordCount.indices) verseWordCount[targetVerseIdx] else 2

        // Advance pointer for any words matched in confirmedVerseIndex first
        if (wordsInCurrentVerse.isNotEmpty()) {
            val lastCurr = wordsInCurrentVerse.last().word
            if (lastCurr.globalIndex >= activeWordPointer) {
                activeWordPointer = minOf(allWords.size - 1, lastCurr.globalIndex + 1)
            }
        }

        // Check Universal Verse Transition Rule for targetVerseIdx:
        // Condition A: 1-word verse exception (e.g. "مدهامتان", "والضحى", "والعصر", "الرحمن")
        val isSingleWordVerseConfirmed = (targetVerseTotalWords == 1 && wordsInTargetVerse.isNotEmpty() && wordsInTargetVerse.first().sim >= 0.78)

        // Condition B: 2+ ordered words in targetVerseIdx within this same audio frame
        val hasTwoOrderedWordsInSameFrame = (wordsInTargetVerse.size >= 2 &&
                wordsInTargetVerse.last().word.globalIndex > wordsInTargetVerse.first().word.globalIndex)

        // Condition C: 2 ordered words across consecutive frames (pending first word + current second word in targetVerseIdx)
        val hasTwoOrderedWordsAcrossFrames = (pendingNextVerseIndex == targetVerseIdx &&
                wordsInTargetVerse.isNotEmpty() &&
                wordsInTargetVerse.last().word.globalIndex > pendingNextVerseFirstWordGlobalIdx &&
                (wordsInTargetVerse.last().word.globalIndex - pendingNextVerseFirstWordGlobalIdx) <= 4)

        // Dynamic Overlap Guard for the 133 consecutive verses sharing 2+ similar words:
        // If confirmedVerseIndex STILL has unrecited words at/ahead of activeWordPointer that match these exact target words,
        // do not jump prematurely over confirmedVerseIndex!
        val isBlockedByUnrecitedCurrentVerseOverlap = if (wordsInCurrentVerse.isEmpty() && wordsInTargetVerse.size >= 2) {
            hasSamePhraseRemainingInCurrentVerse(wordsInTargetVerse.map { it.word })
        } else {
            false
        }

        if (!isBlockedByUnrecitedCurrentVerseOverlap &&
            (isSingleWordVerseConfirmed || hasTwoOrderedWordsInSameFrame || hasTwoOrderedWordsAcrossFrames)
        ) {
            // Confirmed transition to targetVerseIdx!
            confirmedVerseIndex = targetVerseIdx
            val lastTargetWord = wordsInTargetVerse.last().word
            activeWordPointer = minOf(allWords.size - 1, lastTargetWord.globalIndex + 1)
            clearPendingVerseTransition()

            val mv = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)
            if (mv != null && mv.surah > 1) {
                lastNonFatihahVerseIndex = confirmedVerseIndex
            }

            return buildTrackingResponse(
                matchedWords = substantiveChain.map { it.word },
                verseIdx = confirmedVerseIndex,
                highestSim = maxOf(highestSim, pendingNextVerseSim)
            )
        } else {
            // Only 1 word of targetVerseIdx was heard so far: store it as pending first word and wait for the 2nd word!
            if (wordsInTargetVerse.isNotEmpty()) {
                val firstTargetCandidate = wordsInTargetVerse.first()
                if (pendingNextVerseIndex != targetVerseIdx ||
                    firstTargetCandidate.word.globalIndex < pendingNextVerseFirstWordGlobalIdx
                ) {
                    pendingNextVerseIndex = targetVerseIdx
                    pendingNextVerseFirstWordGlobalIdx = firstTargetCandidate.word.globalIndex
                    pendingNextVerseSim = firstTargetCandidate.sim
                    pendingNextVerseText = firstTargetCandidate.word.originalText
                }
            }

            // If we also matched words in the current verse in this frame, emit those on current verse
            if (wordsInCurrentVerse.isNotEmpty()) {
                return buildTrackingResponse(
                    matchedWords = wordsInCurrentVerse.map { it.word },
                    verseIdx = confirmedVerseIndex,
                    highestSim = wordsInCurrentVerse.maxOf { it.sim }
                )
            }

            // Otherwise hold verse highlight steady on confirmedVerseIndex while waiting for 2nd word of targetVerseIdx
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }
    }

    /**
     * Checks if the remaining unrecited portion of confirmedVerseIndex (from activeWordPointer onwards)
     * contains the same consecutive words as the candidate phrase in targetVerseIdx.
     */
    private fun hasSamePhraseRemainingInCurrentVerse(targetWords: List<QuranWord>): Boolean {
        if (targetWords.size < 2) return false
        val currentVerseStart = if (confirmedVerseIndex in verseFirstWordIndex.indices) verseFirstWordIndex[confirmedVerseIndex] else return false
        val currentVerseLen = if (confirmedVerseIndex in verseWordCount.indices) verseWordCount[confirmedVerseIndex] else return false
        val currentVerseEnd = currentVerseStart + currentVerseLen - 1

        val searchStart = maxOf(currentVerseStart, activeWordPointer)
        if (currentVerseEnd - searchStart + 1 < targetWords.size) return false

        val w0 = targetWords[0].normalizedText
        val w1 = targetWords[1].normalizedText
        for (idx in searchStart until currentVerseEnd) {
            val cw0 = allWords[idx].normalizedText
            val cw1 = allWords[idx + 1].normalizedText
            if (FuzzyMatcher.wordSimilarity(w0, cw0) >= 0.78 &&
                FuzzyMatcher.wordSimilarity(w1, cw1) >= 0.78
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Builds the TrackingResult and checks if Surah Al-Fatihah (1:7 "ولا الضالين") just finished.
     */
    private fun buildTrackingResponse(
        matchedWords: List<QuranWord>,
        verseIdx: Int,
        highestSim: Double
    ): TrackingResult {
        val mv = MushafPageRepository.getVerseByGlobalIndex(verseIdx)
        val filteredStr = matchedWords.joinToString(" ") { it.originalText }

        // Check if the reciter just reached the end of Surah Al-Fatihah (1:7 "ولا الضالين")
        if (mv != null && mv.surah == 1 && mv.ayah == 7) {
            val fatihahEndWordIdx = verseFirstWordIndex[6] + verseWordCount[6] - 1 // "الضالين"
            val reachedEndOfFatihah = matchedWords.any { w ->
                w.globalIndex >= fatihahEndWordIdx || w.skeletonText == "الضلين"
            }
            if (reachedEndOfFatihah) {
                enterDiscoveryMode("Completed Al-Fatihah 1:7 (ولا الضالين)")
                return TrackingResult(
                    filteredText = filteredStr,
                    matchedVerseIndex = verseIdx,
                    matchedVerse = mv,
                    matchedWordsCount = matchedWords.size,
                    highestSimilarity = highestSim,
                    recitationMode = RecitationMode.DISCOVERY,
                    fatihahCompletedNow = true
                )
            }
        }

        return TrackingResult(
            filteredText = filteredStr,
            matchedVerseIndex = verseIdx,
            matchedVerse = mv,
            matchedWordsCount = matchedWords.size,
            highestSimilarity = highestSim,
            recitationMode = recitationMode
        )
    }

    private fun handleMismatchFrame(rawWords: List<String>) {
        val isOnlyTransitionWords = rawWords.all { rw ->
            TRANSITION_WORDS.any { tw -> FuzzyMatcher.wordSimilarity(rw, tw) >= 0.78 } ||
                PRAYER_TAKBIR_WORDS.any { pw -> FuzzyMatcher.wordSimilarity(rw, pw) >= 0.80 }
        }
        if (!isOnlyTransitionWords) {
            consecutiveMismatches++
        }
    }

    /**
     * Detects if the reciter has started reciting Surah Al-Fatihah (1:2..1:5) at the beginning of a new Rakah
     * while the app was previously tracking another Surah.
     */
    private fun detectAlFatihahStart(rawWords: List<String>): TrackingResult? {
        if (verseFirstWordIndex.size < 7) return null
        val fatihahStartWord = verseFirstWordIndex[1] // start of 1:2 (الحمد لله رب العالمين)
        val fatihahEndWord = verseFirstWordIndex[5] - 1 // end of 1:5 (إياك نعبد وإياك نستعين)
        if (fatihahEndWord <= fatihahStartWord) return null

        val fatihahWords = allWords.subList(fatihahStartWord, fatihahEndWord + 1)
        val matched = ArrayList<QuranWord>()
        var bestSim = 0.0

        for (rw in rawWords) {
            if (rw.length <= 2) continue
            var bestW: QuranWord? = null
            var maxS = 0.0
            for (fw in fatihahWords) {
                if (matched.isNotEmpty() && fw.globalIndex <= matched.last().globalIndex) continue
                val s = FuzzyMatcher.wordSimilarity(rw, fw.normalizedText)
                if (s >= 0.82 && s > maxS) {
                    maxS = s
                    bestW = fw
                }
            }
            if (bestW != null) {
                matched.add(bestW)
                if (maxS > bestSim) bestSim = maxS
            }
        }

        // Require at least 2 ordered words of Al-Fatihah (1:2..1:5) with consecutive proximity
        if (matched.size >= 2 && (matched.last().globalIndex - matched.first().globalIndex) <= 4) {
            val targetVIdx = matched.last().verseIndex
            confirmedVerseIndex = targetVIdx
            activeWordPointer = minOf(allWords.size - 1, matched.last().globalIndex + 1)
            recitationMode = RecitationMode.TRACKING
            consecutiveMismatches = 0
            clearPendingVerseTransition()
            discoveryRollingWords.clear()

            val mv = MushafPageRepository.getVerseByGlobalIndex(targetVIdx)
            return TrackingResult(
                filteredText = matched.joinToString(" ") { it.originalText },
                matchedVerseIndex = targetVIdx,
                matchedVerse = mv,
                matchedWordsCount = matched.size,
                highestSimilarity = bestSim,
                recitationMode = RecitationMode.TRACKING,
                discoveredNewLocation = true
            )
        }
        return null
    }

    /**
     * Global 6,236-Verse Discovery Engine using the Two-Stage Inverted Skeleton Index.
     * Accumulates rolling words across consecutive frames and resolves the unique Quranic verse.
     */
    private fun discoverVerseFromRawWords(rawWords: List<String>): TrackingResult? {
        val now = System.currentTimeMillis()
        // Expire old rolling words if there was a pause > 8 seconds
        if (discoveryRollingWords.isNotEmpty() && now - discoveryRollingWords.last().timestampMs > 8000L) {
            discoveryRollingWords.clear()
        }

        // Append new substantive words (avoiding duplicate overlap from sliding 1.8s window)
        for (rw in rawWords) {
            val isTransition = TRANSITION_WORDS.any { tw -> FuzzyMatcher.wordSimilarity(rw, tw) >= 0.80 } ||
                PRAYER_TAKBIR_WORDS.any { pw -> FuzzyMatcher.wordSimilarity(rw, pw) >= 0.80 }
            if (isTransition) continue

            val skel = FuzzyMatcher.skeletonWord(rw)
            if (skel.isBlank()) continue

            // Deduplicate against the last 3 rolling words in buffer
            val recentTail = discoveryRollingWords.takeLast(3)
            val isDuplicate = recentTail.any { FuzzyMatcher.wordSimilarity(it.normWord, rw) >= 0.82 }
            if (!isDuplicate) {
                discoveryRollingWords.add(RollingAsrWord(rw, skel, now))
                if (discoveryRollingWords.size > 10) {
                    discoveryRollingWords.removeAt(0)
                }
            }
        }

        if (discoveryRollingWords.isEmpty()) return null

        // 0. Check if the reciter is starting Surah Al-Fatihah (1:2..1:5)
        val fatihahFromRolling = detectAlFatihahStart(discoveryRollingWords.map { it.normWord })
        if (fatihahFromRolling != null) {
            AppLogger.i(TAG, "Discovery locked onto Surah Al-Fatihah [${fatihahFromRolling.matchedVerse?.surah}:${fatihahFromRolling.matchedVerse?.ayah}]")
            return fatihahFromRolling
        }

        // 1. Check quick continuity right after lastNonFatihahVerseIndex (e.g. starting 2nd Rakah where 1st Rakah stopped!)
        if (lastNonFatihahVerseIndex in 0 until verseFirstWordIndex.size - 1) {
            val nextVIdx = lastNonFatihahVerseIndex + 1
            val contStart = verseFirstWordIndex[nextVIdx]
            val contEnd = minOf(allWords.size - 1, contStart + 12)
            val contWindow = allWords.subList(contStart, contEnd + 1)

            val contMatched = ArrayList<QuranWord>()
            var contMaxSim = 0.0
            for (rw in discoveryRollingWords) {
                for (cw in contWindow) {
                    if (contMatched.isNotEmpty() && cw.globalIndex <= contMatched.last().globalIndex) continue
                    val s = FuzzyMatcher.wordSimilarity(rw.normWord, cw.normalizedText)
                    if (s >= 0.78) {
                        contMatched.add(cw)
                        if (s > contMaxSim) contMaxSim = s
                        break
                    }
                }
            }
            if (contMatched.size >= 2 && (contMatched.last().globalIndex - contMatched.first().globalIndex) <= 4) {
                val foundVIdx = contMatched.last().verseIndex
                confirmedVerseIndex = foundVIdx
                activeWordPointer = minOf(allWords.size - 1, contMatched.last().globalIndex + 1)
                recitationMode = RecitationMode.TRACKING
                consecutiveMismatches = 0
                clearPendingVerseTransition()
                discoveryRollingWords.clear()

                val mv = MushafPageRepository.getVerseByGlobalIndex(foundVIdx)
                if (mv != null && mv.surah > 1) {
                    lastNonFatihahVerseIndex = foundVIdx
                }
                AppLogger.i(TAG, "Rakah Continuity Discovery matched [${mv?.surah}:${mv?.ayah}] (Page ${mv?.page})")
                return TrackingResult(
                    filteredText = contMatched.joinToString(" ") { it.originalText },
                    matchedVerseIndex = foundVIdx,
                    matchedVerse = mv,
                    matchedWordsCount = contMatched.size,
                    highestSimilarity = contMaxSim,
                    recitationMode = RecitationMode.TRACKING,
                    discoveredNewLocation = true
                )
            }
        }

        // 2. Full 6,236-Verse Inverted Index Search
        // For each rolling word, collect matching global word positions with similarity & rarity weight
        data class GlobalHit(
            val rollIdx: Int,
            val globalWordIdx: Int,
            val sim: Double,
            val idfWeight: Double
        )

        val allHits = ArrayList<GlobalHit>(256)
        val inPostFatihahCooldown = (now - lastFatihahCompletedTimeMs) < 4500L

        for ((rIdx, rw) in discoveryRollingWords.withIndex()) {
            if (rw.normWord.length <= 2 && SHORT_PARTICLES.contains(rw.normWord)) continue

            val matchedSkeletons = ArrayList<Pair<String, Double>>(6)
            // Stage 1A: Exact skeleton lookup in O(1)
            if (skeletonToGlobalIndices.containsKey(rw.skeleton)) {
                matchedSkeletons.add(rw.skeleton to 0.98)
            }

            // Stage 1B: Fast length-bucketed fuzzy skeleton lookup
            val minL = (rw.skeleton.length - 1).coerceAtLeast(2)
            val maxL = (rw.skeleton.length + 1).coerceAtMost(24)
            for (len in minL..maxL) {
                for (candSkel in skeletonsByLength[len]) {
                    if (candSkel == rw.skeleton) continue
                    // Fast character filter: must share first char or last char or second char
                    if (candSkel[0] != rw.skeleton[0] &&
                        candSkel[candSkel.length - 1] != rw.skeleton[rw.skeleton.length - 1]
                    ) {
                        continue
                    }
                    val skSim = FuzzyMatcher.normalizedLevenshtein(rw.skeleton, candSkel)
                    if (skSim >= 0.78) {
                        matchedSkeletons.add(candSkel to (skSim * 0.94))
                    }
                }
            }

            matchedSkeletons.sortByDescending { it.second }
            val topSkeletons = matchedSkeletons.take(6)

            for ((skel, baseSim) in topSkeletons) {
                val positions = skeletonToGlobalIndices[skel] ?: continue
                // Skip ultra-frequent words (> 400 occurrences across Quran) unless exact match
                if (positions.size > 400 && baseSim < 0.95) continue
                val idf = when {
                    positions.size <= 5 -> 1.65
                    positions.size <= 20 -> 1.40
                    positions.size <= 80 -> 1.20
                    positions.size <= 200 -> 1.05
                    else -> 0.85
                }
                for (gPos in positions) {
                    if (inPostFatihahCooldown && allWords[gPos].verseIndex == 6) continue
                    val exactSim = FuzzyMatcher.wordSimilarity(rw.normWord, allWords[gPos].normalizedText)
                    val finalSim = maxOf(baseSim, exactSim)
                    if (finalSim >= 0.76) {
                        allHits.add(GlobalHit(rIdx, gPos, finalSim, idf))
                    }
                }
            }
        }

        if (allHits.isEmpty()) return null

        // Sort hits by globalWordIdx to chain adjacent hits within a 10-word span
        allHits.sortWith(compareBy<GlobalHit> { it.globalWordIdx }.thenBy { it.rollIdx })

        val m = allHits.size
        val chainScore = DoubleArray(m)
        val chainCount = IntArray(m) { 1 }
        val chainPrev = IntArray(m) { -1 }

        for (i in 0 until m) {
            val hi = allHits[i]
            var bestS = hi.sim * hi.idfWeight
            var bestC = 1
            var bestP = -1

            // Look back at recent hits within 10 words in the Quran
            var j = i - 1
            while (j >= 0) {
                val hj = allHits[j]
                val wordSpan = hi.globalWordIdx - hj.globalWordIdx
                if (wordSpan > 10) break
                if (wordSpan > 0 && hj.rollIdx < hi.rollIdx) {
                    val stepBonus = when (wordSpan) {
                        1 -> 0.65
                        2 -> 0.40
                        3 -> 0.25
                        else -> 0.10
                    }
                    val candS = chainScore[j] + (hi.sim * hi.idfWeight) + stepBonus
                    if (candS > bestS) {
                        bestS = candS
                        bestC = chainCount[j] + 1
                        bestP = j
                    }
                }
                j--
            }
            chainScore[i] = bestS
            chainCount[i] = bestC
            chainPrev[i] = bestP
        }

        // Rank candidate verses by their best chain score
        var bestIdx = -1
        var bestScore = 0.0
        var bestVerseIdx = -1
        var secondBestScore = 0.0

        for (i in 0 until m) {
            val vIdx = allWords[allHits[i].globalWordIdx].verseIndex
            val s = chainScore[i]
            if (s > bestScore) {
                if (vIdx != bestVerseIdx) {
                    secondBestScore = bestScore
                }
                bestScore = s
                bestIdx = i
                bestVerseIdx = vIdx
            } else if (vIdx != bestVerseIdx && s > secondBestScore) {
                secondBestScore = s
            }
        }

        if (bestIdx == -1) return null

        val matchedWordCount = chainCount[bestIdx]
        val uniquenessRatio = if (secondBestScore > 0.0) bestScore / secondBestScore else 10.0

        // Unambiguous Discovery Confirmation Criteria:
        // 1. >= 3 ordered substantive words with clear margin over 2nd best verse (ratio >= 1.22), OR
        // 2. 2 consecutive rare/distinctive words (bestScore >= 2.85) with strong uniqueness margin (ratio >= 1.38)
        val isConfirmedDiscovery = (matchedWordCount >= 3 && bestScore >= 3.10 && uniquenessRatio >= 1.22) ||
            (matchedWordCount >= 2 && bestScore >= 2.85 && uniquenessRatio >= 1.38)

        if (!isConfirmedDiscovery) {
            return null
        }

        // Reconstruct discovered chain
        val matchedQuranWords = ArrayList<QuranWord>(matchedWordCount)
        var maxSim = 0.0
        var curr = bestIdx
        while (curr != -1) {
            val h = allHits[curr]
            matchedQuranWords.add(allWords[h.globalWordIdx])
            if (h.sim > maxSim) maxSim = h.sim
            curr = chainPrev[curr]
        }
        matchedQuranWords.reverse()

        val finalWord = matchedQuranWords.last()
        val discoveredVIdx = finalWord.verseIndex
        confirmedVerseIndex = discoveredVIdx
        activeWordPointer = minOf(allWords.size - 1, finalWord.globalIndex + 1)
        recitationMode = RecitationMode.TRACKING
        consecutiveMismatches = 0
        clearPendingVerseTransition()
        discoveryRollingWords.clear()

        val mv = MushafPageRepository.getVerseByGlobalIndex(discoveredVIdx)
        if (mv != null && mv.surah > 1) {
            lastNonFatihahVerseIndex = discoveredVIdx
        }

        AppLogger.i(
            TAG,
            "Global Discovery locked onto [${mv?.surah}:${mv?.ayah}] (Page ${mv?.page}) | Words=$matchedWordCount, Score=${"%.2f".format(bestScore)}, Margin=${"%.2f".format(uniquenessRatio)}"
        )

        return buildTrackingResponse(
            matchedWords = matchedQuranWords,
            verseIdx = discoveredVIdx,
            highestSim = maxSim
        ).copy(discoveredNewLocation = true)
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
        val countMap = IntArray(verses.size)
        var globalIdx = 0
        for ((vIdx, verse) in verses.withIndex()) {
            firstWordMap[vIdx] = globalIdx
            val cleanVerse = FuzzyMatcher.normalizeArabic(verse)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = verse.split(" ").filter { it.isNotBlank() }
            countMap[vIdx] = verseTokens.size
            for (wIdx in verseTokens.indices) {
                val orig = if (wIdx < rawTokens.size) rawTokens[wIdx] else verseTokens[wIdx]
                val norm = verseTokens[wIdx]
                words.add(
                    QuranWord(
                        globalIndex = globalIdx++,
                        verseIndex = vIdx,
                        wordIndexInVerse = wIdx,
                        originalText = orig,
                        normalizedText = norm,
                        skeletonText = FuzzyMatcher.skeletonWord(norm)
                    )
                )
            }
        }
        allWords = words
        verseFirstWordIndex = firstWordMap
        verseWordCount = countMap
        cachedVersesHash = hash
        activeWordPointer = 0
        confirmedVerseIndex = 0
        consecutiveMismatches = 0
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
