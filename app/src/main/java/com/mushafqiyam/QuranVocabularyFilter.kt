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
        val displayVerseIndex: Int,
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
        val discoveredNewLocation: Boolean = false,
        val currentWordInAyah: Int = 0,
        val totalWordsInAyah: Int = 1,
        val charProgress: Float = 0f,
        val isPrayerTransition: Boolean = false,
        val isTasleem: Boolean = false
    ) {
        val mode: RecitationMode get() = recitationMode
        val confidence: Double get() = highestSimilarity
    }

    private var allWords: List<QuranWord> = emptyList()
    private var verseFirstWordIndex: IntArray = IntArray(0)
    private var verseWordCount: IntArray = IntArray(0)
    private var cachedVersesHash: Int = 0
    private var isFullMushafLoaded: Boolean = false

    // Inverted index for < 2ms global discovery across 6,236 verses
    private val skeletonToGlobalIndices = HashMap<String, IntArray>(16384)
    private var skeletonsByLength: Array<ArrayList<String>> = Array(25) { ArrayList() }

    // Character-Level representation for streaming continuous phoneme alignment
    private var verseCanonicalChars: Array<String> = emptyArray()
    private var verseWordCharOffsets: Array<IntArray> = emptyArray()
    private val fourGramToVerseIndices = HashMap<String, IntArray>(65536)
    private val discoveryRollingChars = java.lang.StringBuilder(64)
    private var utteranceAnchorVerseIndex: Int = 0
    private var lastSeenStreamLength: Int = 0
    private var lastDiscoveryStreamLength: Int = 0

    // Reusable DP arrays for compact alignment to eliminate garbage collection pressure
    private var dpPrevScore = IntArray(2048)
    private var dpCurrScore = IntArray(2048)
    private var dpPrevStart = IntArray(2048)
    private var dpCurrStart = IntArray(2048)
    private var dpPrevGap = IntArray(2048)
    private var dpCurrGap = IntArray(2048)

    private fun ensureDpCapacity(needed: Int) {
        if (dpPrevScore.size < needed) {
            val newCap = maxOf(needed + 256, dpPrevScore.size * 2)
            dpPrevScore = IntArray(newCap)
            dpCurrScore = IntArray(newCap)
            dpPrevStart = IntArray(newCap)
            dpCurrStart = IntArray(newCap)
            dpPrevGap = IntArray(newCap)
            dpCurrGap = IntArray(newCap)
        }
    }

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
    private var lastMatchedSpeechTimeMs: Long = System.currentTimeMillis()

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
        val canonList = Array(mushafVerses.size) { "" }
        val offsetsList = Array(mushafVerses.size) { IntArray(0) }
        val tempFourGrams = HashMap<String, IntArrayList>(65536)

        var globalIdx = 0

        val logicalVerseMap = IntArray(mushafVerses.size) { it }
        for (vIdx in mushafVerses.indices) {
            val cleanVerse = FuzzyMatcher.normalizeArabic(mushafVerses[vIdx].cleanText)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            
            if (cleanVerse.contains("فباي الاء ربكما تكذبان") || cleanVerse.contains("ويل يومئذ للمكذبين") || cleanVerse.contains("فبأي آلاء ربكما تكذبان")) {
                if (vIdx > 0) {
                    logicalVerseMap[vIdx] = logicalVerseMap[vIdx - 1]
                }
            } else if (mushafVerses[vIdx].surah == 1 && mushafVerses[vIdx].ayah == 3) {
                if (vIdx > 0) {
                    logicalVerseMap[vIdx] = logicalVerseMap[vIdx - 1]
                }
            } else if (verseTokens.size == 1) {
                var nextIdx = vIdx + 1
                while (nextIdx < mushafVerses.size) {
                    val nextClean = FuzzyMatcher.normalizeArabic(mushafVerses[nextIdx].cleanText)
                    val nextTokens = nextClean.split(" ").filter { it.isNotBlank() }
                    if (nextTokens.size > 1 && !nextClean.contains("فباي الاء") && !nextClean.contains("ويل يومئذ")) {
                        break
                    }
                    nextIdx++
                }
                logicalVerseMap[vIdx] = minOf(nextIdx, mushafVerses.size - 1)
            }
        }

        firstWordMap.fill(-1)
        countMap.fill(0)

        for ((vIdx, mv) in mushafVerses.withIndex()) {
            val logicalVIdx = logicalVerseMap[vIdx]
            if (firstWordMap[logicalVIdx] == -1) {
                firstWordMap[logicalVIdx] = globalIdx
            }
            
            val cleanVerse = FuzzyMatcher.normalizeArabic(mv.cleanText)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = mv.text.split(" ").filter {
                it.isNotBlank() && it.any { ch -> ch in '\u0621'..'\u064A' || ch == '\u0671' }
            }

            countMap[logicalVIdx] += verseTokens.size

            val canonBuilder = java.lang.StringBuilder()
            val offsets = IntArray(verseTokens.size)

            for (wIdx in verseTokens.indices) {
                val orig = if (wIdx < rawTokens.size) rawTokens[wIdx] else verseTokens[wIdx]
                val norm = verseTokens[wIdx]
                val skel = FuzzyMatcher.skeletonWord(norm)
                val cIdx = globalIdx++

                // 1. Phonetic transformation of word for canonical continuous tracking:
                // a. Strip silent Jama'ah Alif (e.g. "قالوا" -> "قالو", "امنوا" -> "امنو")
                var phonWord = if (norm.endsWith("وا") && norm.length >= 4) {
                    norm.substring(0, norm.length - 1)
                } else {
                    norm
                }

                // b. Remove Shamsi & Qamari Wasl assimilation in connected speech
                // Shamsi consonants: ت ث د ذ ر ز س ش ص ض ط ظ ل ن
                val shamsiChars = "تثدذرزسشصضطظلن"
                if (phonWord.startsWith("ال") && phonWord.length >= 3) {
                    val isShamsi = phonWord[2] in shamsiChars
                    // If previous word ended with vowel 'ي' or 'ا' in prepositions/particles (like "في العقد" -> "فلعقد", "ذا الاوتاد" -> "ذلاوتاد"), drop trailing vowel before connected lam
                    if (canonBuilder.length >= 2 && (canonBuilder.last() == 'ي' || canonBuilder.last() == 'ا')) {
                        val prevPrev = canonBuilder[canonBuilder.length - 2]
                        if (prevPrev in "فعلذ") {
                            canonBuilder.deleteCharAt(canonBuilder.length - 1)
                            if (wIdx > 0 && offsets[wIdx - 1] > 0) {
                                offsets[wIdx - 1]--
                            }
                        }
                    }
                    phonWord = if (canonBuilder.isNotEmpty()) {
                        if (isShamsi) phonWord.substring(2) else phonWord.substring(1) // Shamsi: drop "ال", Qamari: drop wasl "ا" and keep "ل"
                    } else {
                        if (isShamsi) "ا" + phonWord.substring(2) else phonWord
                    }
                } else if (phonWord.length >= 4 && (phonWord.startsWith("بال") || phonWord.startsWith("فال") || phonWord.startsWith("وال") || phonWord.startsWith("كال")) && (orig.contains('\u0671') || orig.contains("ٱ"))) {
                    val prefixChar = phonWord[0]
                    val isShamsi = phonWord[3] in shamsiChars
                    phonWord = if (isShamsi) {
                        "$prefixChar" + phonWord.substring(3) // e.g. "والشمس" -> "وشمس", "بالناس" -> "بناس"
                    } else {
                        "${prefixChar}ل" + phonWord.substring(3) // e.g. "بالواد" -> "بلواد", "والفجر" -> "ولفجر"
                    }
                }

                // c. Idgham / Iqlab with previous word's trailing noon
                if (canonBuilder.isNotEmpty() && canonBuilder[canonBuilder.length - 1] == 'ن') {
                    val firstChar = if (phonWord.isNotEmpty()) phonWord[0] else ' '
                    if (firstChar == 'ب') {
                        // Iqlab: ن followed by ب becomes م (e.g. "من بعد" -> "مبعد")
                        canonBuilder.setCharAt(canonBuilder.length - 1, 'م')
                    } else if (firstChar in "رل") {
                        // Idgham without ghunnah: drop trailing noon (e.g. "من ربهم" -> "مربهم")
                        canonBuilder.deleteCharAt(canonBuilder.length - 1)
                        if (wIdx > 0 && offsets[wIdx - 1] > 0) {
                            offsets[wIdx - 1]--
                        }
                    }
                }

                // d. Append to canonBuilder with run-length deduplication across word boundaries
                for (ch in phonWord) {
                    if (canonBuilder.isNotEmpty() && canonBuilder[canonBuilder.length - 1] == ch) {
                        val isDoubleLamAllah = (ch == 'ل' && (
                            (canonBuilder.length >= 2 && canonBuilder[canonBuilder.length - 2] == 'ا') ||
                            norm.contains("الله") || norm.contains("لله")
                        ))
                        if (!isDoubleLamAllah) {
                            continue
                        }
                    }
                    canonBuilder.append(ch)
                }
                offsets[wIdx] = canonBuilder.length

                words.add(
                    QuranWord(
                        globalIndex = cIdx,
                        verseIndex = logicalVIdx,
                        displayVerseIndex = vIdx,
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

            val canon = canonBuilder.toString()
            canonList[vIdx] = canon
            offsetsList[vIdx] = offsets

            val seenInVerse = HashSet<String>()
            for (j in 0 until canon.length - 3) {
                val tetra = canon.substring(j, j + 4)
                if (seenInVerse.add(tetra)) {
                    var list = tempFourGrams[tetra]
                    if (list == null) {
                        list = IntArrayList(8)
                        tempFourGrams[tetra] = list
                    }
                    list.add(vIdx)
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

        fourGramToVerseIndices.clear()
        for ((tetra, intList) in tempFourGrams) {
            fourGramToVerseIndices[tetra] = intList.toIntArray()
        }

        verseCanonicalChars = canonList
        verseWordCharOffsets = offsetsList

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
        discoveryRollingChars.setLength(0)

        val elapsed = System.currentTimeMillis() - startTime
        AppLogger.i(
            TAG,
            "Full Mushaf indexed in ${elapsed}ms: ${mushafVerses.size} verses, ${words.size} words, ${skeletonToGlobalIndices.size} unique skeletons, ${fourGramToVerseIndices.size} 4-grams."
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
        discoveryRollingChars.setLength(0)
        utteranceAnchorVerseIndex = confirmedVerseIndex
        lastSeenStreamLength = 0
        lastDiscoveryStreamLength = 0
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
        discoveryRollingChars.setLength(0)
        utteranceAnchorVerseIndex = confirmedVerseIndex
        lastSeenStreamLength = 0
        lastDiscoveryStreamLength = 0

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

    @Synchronized
    fun setLastNonFatihahVerseIndex(verseIndex: Int) {
        if (verseIndex in 0 until 6236) {
            lastNonFatihahVerseIndex = verseIndex
            AppLogger.i(TAG, "Explicitly set lastNonFatihahVerseIndex = $verseIndex")
        }
    }

    /**
     * Retrieves the current narrow window of allowed words around activeWordPointer.
     */
    @Synchronized
    fun getActiveContextWindow(): List<QuranWord> {
        if (allWords.isEmpty()) return emptyList()

        val lookbehind = 2
        val baseLookahead = 20
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
        val canonicalStream = cleanRaw.replace(" ", "")
        if (canonicalStream.isBlank()) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }
        val rawWords = cleanRaw.split(" ").filter { it.isNotBlank() }

        // Detect if stream was reset by Sherpa-ONNX endpoint
        if (canonicalStream.length < lastSeenStreamLength - 3) {
            utteranceAnchorVerseIndex = confirmedVerseIndex
            discoveryRollingChars.setLength(0)
            lastDiscoveryStreamLength = 0
        }
        lastSeenStreamLength = canonicalStream.length

        // 1. Check if the user is currently in Surah Al-Fatihah (Surah 1) and said "آمين"
        val currentVerseObj = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)
        if (recitationMode == RecitationMode.TRACKING && currentVerseObj?.surah == 1 && confirmedVerseIndex >= 5) {
            val saidAmeen = canonicalStream.contains("امين") || canonicalStream.contains("ءامين") ||
                rawWords.any { rw -> AMEEN_WORDS.any { aw -> FuzzyMatcher.wordSimilarity(rw, aw) >= 0.80 } }
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

        // 2. If in DISCOVERY mode, run character-based and rolling discovery
        if (recitationMode == RecitationMode.DISCOVERY) {
            val charDiscovered = discoverVerseFromCharacterStream(canonicalStream)
            if (charDiscovered != null) {
                return charDiscovered
            }
            if (rawWords.isNotEmpty()) {
                val wordDiscovered = discoverVerseFromRawWords(rawWords)
                if (wordDiscovered != null) {
                    return wordDiscovered
                }
            }
            return TrackingResult("", null, null, 0, 0.0, RecitationMode.DISCOVERY)
        }

        // 3. While in TRACKING mode on a non-Fatihah Surah, check if reciter stood up for a new Rakah and started Al-Fatihah
        if (currentVerseObj != null && currentVerseObj.surah != 1) {
            val isFatihahJump = ((canonicalStream.contains("الحمدلله") || canonicalStream.contains("الحمد")) &&
                    (canonicalStream.contains("رب") || canonicalStream.contains("عالمين") || canonicalStream.contains("رحمن"))) ||
                canonicalStream.contains("اياكنعبد") ||
                canonicalStream.contains("اهدنالصراط") ||
                canonicalStream.contains("اهدينصراط") ||
                canonicalStream.contains("صراطلذين")
            if (isFatihahJump) {
                val fatihahTarget = if (canonicalStream.contains("اهد") || canonicalStream.contains("صراط")) 5 else 1
                val fv = MushafPageRepository.getVerseByGlobalIndex(fatihahTarget)
                if (fv != null) {
                    resetPointerToVerse(fatihahTarget)
                    AppLogger.i(TAG, "New Rakah detected via character stream: switching to Surah Al-Fatihah [1:${fv.ayah}]")
                    return TrackingResult(
                        filteredText = fv.cleanText,
                        matchedVerseIndex = fatihahTarget,
                        matchedVerse = fv,
                        matchedWordsCount = 1,
                        highestSimilarity = 0.95,
                        recitationMode = RecitationMode.TRACKING,
                        discoveredNewLocation = true
                    )
                }
            }
            val fatihahJump = detectAlFatihahStart(rawWords)
            if (fatihahJump != null) {
                AppLogger.i(TAG, "New Rakah detected: switching to Surah Al-Fatihah [${fatihahJump.matchedVerse?.surah}:${fatihahJump.matchedVerse?.ayah}]")
                return fatihahJump
            }
        }

        // 3.5. Prayer Dhikr & Tasleem Isolation Gate:
        // Exclude explicit non-Quranic prayer transitions, tasmee', takbeer, tasbeeh, and tasleem from sequential tracking
        val streamTail20 = if (canonicalStream.length > 20) canonicalStream.substring(canonicalStream.length - 20) else canonicalStream

        // 1. Tasleem: strictly requires pairing of Salam with Rahmah (e.g. سلام عليكم ورحمة الله / السلام عليك ورحمة الله)
        // because "سلام عليكم" alone is genuine Quran (e.g. 13:24, 6:54), but "ورحمة الله" paired with it is NOT in the Quran.
        val isExplicitTasleem = (canonicalStream.contains("سليكمورحم") ||
                ((canonicalStream.contains("سلامعلي") || canonicalStream.contains("لسلامعلي") || canonicalStream.contains("سلامعليك") || canonicalStream.contains("لسلامعليك")) &&
                 (canonicalStream.contains("رحمت") || canonicalStream.contains("رحمه")))) ||
                (streamTail20.contains("سليكمورحم") ||
                ((streamTail20.contains("سلامعلي") || streamTail20.contains("لسلامعلي") || streamTail20.contains("سلامعليك") || streamTail20.contains("لسلامعليك")) &&
                 (streamTail20.contains("رحمت") || streamTail20.contains("رحمه"))))

        // 2. Tasmee' & Tahmeed: "سمع الله لمن حمده" / "ربنا ولك الحمد"
        // strictly requires the non-Quranic compound containing "لمن حمده" or "ولك الحمد"
        // Never isolate "سمع الله" alone because it is genuine Quran (Al-Mujadila 58:1, Al-Imran 3:181).
        val isExplicitTasmeeOrTahmeed = canonicalStream.contains("سمعاللهلمن") || canonicalStream.contains("لمنحمده") || canonicalStream.contains("لمنحمد") ||
                canonicalStream.contains("ربنولكلحمد") || canonicalStream.contains("ربنالكلحمد") || canonicalStream.contains("ولكلحمد") ||
                streamTail20.contains("سمعاللهلمن") || streamTail20.contains("لمنحمده") || streamTail20.contains("لمنحمد") ||
                streamTail20.contains("ربنولكلحمد") || streamTail20.contains("ربنالكلحمد") || streamTail20.contains("ولكلحمد")

        // 3. Takbeer: "الله أكبر" (non-Quranic compound, guarding against 29:45 "ولذكر الله أكبر")
        val isExplicitTakbeer = (canonicalStream.contains("اللهكبر") || canonicalStream.contains("اللهواكبر") ||
                streamTail20.contains("اللهكبر") || streamTail20.contains("اللهواكبر")) &&
                !canonicalStream.contains("ولذكراللهكبر")

        // 4. Tasbeeh: "سبحان ربي العظيم" / "سبحان ربي الأعلى"
        val isExplicitTasbeeh = canonicalStream.contains("سبحانربيلعظيم") || canonicalStream.contains("سبحانربيلعلى") ||
                canonicalStream.contains("سبحانربيلعظم") ||
                streamTail20.contains("سبحانربيلعظيم") || streamTail20.contains("سبحانربيلعلى") ||
                streamTail20.contains("سبحانربيلعظم")

        val isExplicitPrayerDhikr = isExplicitTasmeeOrTahmeed || isExplicitTakbeer || isExplicitTasbeeh

        if (isExplicitTasleem || isExplicitPrayerDhikr) {
            val currCanonical = if (confirmedVerseIndex in verseCanonicalChars.indices) verseCanonicalChars[confirmedVerseIndex] else ""
            val nextCanonical = if (confirmedVerseIndex + 1 in verseCanonicalChars.indices) verseCanonicalChars[confirmedVerseIndex + 1] else ""
            val next2Canonical = if (confirmedVerseIndex + 2 in verseCanonicalChars.indices) verseCanonicalChars[confirmedVerseIndex + 2] else ""
            val isGenuineQuranicVerse = (isExplicitTasleem && (currCanonical.contains("سلامعليك") || nextCanonical.contains("سلامعليك") || next2Canonical.contains("سلامعليك"))) ||
                    (isExplicitTakbeer && (currCanonical.contains("اللهكبر") || nextCanonical.contains("اللهكبر") || next2Canonical.contains("اللهكبر"))) ||
                    (isExplicitTasbeeh && (currCanonical.contains("سبحانربي") || nextCanonical.contains("سبحانربي") || next2Canonical.contains("سبحانربي")))

            if (!isGenuineQuranicVerse) {
                AppLogger.d(TAG, "Prayer Dhikr/Tasleem isolated: stream='$canonicalStream'")
                return TrackingResult(
                    filteredText = "",
                    matchedVerseIndex = null,
                    matchedVerse = null,
                    matchedWordsCount = 0,
                    highestSimilarity = 0.0,
                    recitationMode = recitationMode,
                    isPrayerTransition = isExplicitPrayerDhikr,
                    isTasleem = isExplicitTasleem
                )
            }
        }

        // 4. Primary: Character-LCS Sequential Window Tracking
        val lcsResult = trackWithinSequentialWindowCharacterLCS(canonicalStream)
        val now = System.currentTimeMillis()
        if (lcsResult.filteredText.isNotBlank()) {
            lastMatchedSpeechTimeMs = now
            discoveryRollingWords.clear()
            discoveryRollingChars.setLength(0)
            return lcsResult
        }

        // Secondary fallback: Word-based window tracking (if space-separated words exist)
        if (rawWords.size >= 2) {
            val windowResult = trackWithinSequentialWindow(rawWords)
            if (windowResult.filteredText.isNotBlank()) {
                lastMatchedSpeechTimeMs = now
                discoveryRollingWords.clear()
                discoveryRollingChars.setLength(0)
                return windowResult
            }
        }

        // 5. Smart Auto-Recovery
        val hasTakbirOrTransition = (canonicalStream.contains("اللهكبر") && !canonicalStream.contains("ولذكر")) ||
            canonicalStream.contains("سمعاللهلمن") || canonicalStream.contains("لمنحمده") || canonicalStream.contains("ربنولكلحمد") ||
            rawWords.any { rw ->
                TRANSITION_WORDS.any { tw -> FuzzyMatcher.wordSimilarity(rw, tw) >= 0.78 } ||
                    PRAYER_TAKBIR_WORDS.any { pw -> FuzzyMatcher.wordSimilarity(rw, pw) >= 0.80 }
            }
        val silenceDurationMs = if (lastMatchedSpeechTimeMs > 0L) now - lastMatchedSpeechTimeMs else 0L
        val canTriggerRecovery = consecutiveMismatches >= 3 && (silenceDurationMs >= 2500L || hasTakbirOrTransition || consecutiveMismatches >= 5)

        if (canTriggerRecovery) {
            val recoveryStream = if (canonicalStream.length > 35) {
                canonicalStream.substring(canonicalStream.length - 35)
            } else {
                canonicalStream
            }
            val recoveredChar = discoverVerseFromCharacterStream(recoveryStream)
            if (recoveredChar != null) {
                val targetVIdx = recoveredChar.matchedVerseIndex
                val isSevereBackwardJump = currentVerseObj != null &&
                    targetVIdx != null &&
                    recoveredChar.matchedVerse?.surah == currentVerseObj.surah &&
                    targetVIdx < confirmedVerseIndex - 2 &&
                    silenceDurationMs < 6000L
                if (!isSevereBackwardJump) {
                    lastMatchedSpeechTimeMs = now
                    AppLogger.i(TAG, "Auto-Recovery (Character) discovered [${recoveredChar.matchedVerse?.surah}:${recoveredChar.matchedVerse?.ayah}]")
                    return recoveredChar
                } else {
                    AppLogger.w(TAG, "Suppressed false backward Auto-Recovery jump from $confirmedVerseIndex to $targetVIdx in Surah ${currentVerseObj?.surah}")
                }
            }
            if (rawWords.isNotEmpty()) {
                val recovered = discoverVerseFromRawWords(rawWords)
                if (recovered != null) {
                    lastMatchedSpeechTimeMs = now
                    AppLogger.i(TAG, "Auto-Recovery (Word) discovered [${recovered.matchedVerse?.surah}:${recovered.matchedVerse?.ayah}]")
                    return recovered
                }
            }
        }

        return lcsResult
    }

    data class CompactAlignmentResult(
        val score: Int,
        val startJ: Int,
        val endJ: Int,
        val density: Double
    )

    fun toPhoneticCanonicalStream(stream: String): String {
        if (stream.isBlank()) return ""
        val s = stream.replace("نب", "مب").replace("نپ", "مب")
        val sb = java.lang.StringBuilder(s.length)
        var lastCh = ' '
        for (i in s.indices) {
            val ch = s[i]
            if (ch == lastCh) {
                val isDoubleLamAllah = (ch == 'ل' && (sb.length >= 2 && sb[sb.length - 2] == 'ا' || (i + 1 < s.length && s[i + 1] == 'ه')))
                if (!isDoubleLamAllah) continue
            }
            sb.append(ch)
            lastCh = ch
        }
        return sb.toString()
    }

    /**
     * Compact semi-global dynamic programming alignment:
     * Computes the longest common subsequence of stream inside ref while tracking the start
     * position in ref to enforce dense, contiguous clustering (preventing runaway scattered gaps).
     * Reuses pre-allocated DP buffers to achieve 0 memory allocation per frame.
     */
    private fun alignStreamToRefCompact(stream: String, ref: String): CompactAlignmentResult {
        val m = stream.length
        val n = ref.length
        if (m == 0 || n == 0) return CompactAlignmentResult(0, 0, 0, 0.0)

        ensureDpCapacity(n + 2)
        var prevScore = dpPrevScore
        var currScore = dpCurrScore
        var prevStart = dpPrevStart
        var currStart = dpCurrStart
        var prevGap = dpPrevGap
        var currGap = dpCurrGap

        java.util.Arrays.fill(prevScore, 0, n + 2, 0)
        java.util.Arrays.fill(currScore, 0, n + 2, 0)
        java.util.Arrays.fill(prevStart, 0, n + 2, 0)
        java.util.Arrays.fill(currStart, 0, n + 2, 0)
        java.util.Arrays.fill(prevGap, 0, n + 2, 0)
        java.util.Arrays.fill(currGap, 0, n + 2, 0)

        val maxWeakGap = 3

        for (i in 1..m) {
            val sc = stream[i - 1]
            for (j in 1..n) {
                if (sc == ref[j - 1]) {
                    // Can extend previous match if established (>= 4) or gap is within limit
                    if (prevScore[j - 1] > 0 && (prevScore[j - 1] >= 4 || prevGap[j - 1] <= maxWeakGap)) {
                        currScore[j] = prevScore[j - 1] + 1
                        currStart[j] = if (prevStart[j - 1] > 0) prevStart[j - 1] else j
                        currGap[j] = 0
                    } else {
                        // Fresh Start!
                        currScore[j] = 1
                        currStart[j] = j
                        currGap[j] = 0
                    }
                } else {
                    if (prevScore[j] >= currScore[j - 1]) {
                        currScore[j] = prevScore[j]
                        currStart[j] = prevStart[j]
                        currGap[j] = prevGap[j]
                    } else {
                        val prevS = currScore[j - 1]
                        val st = currStart[j - 1]
                        val span = j - st + 1
                        val localDensity = if (span > 0) prevS.toDouble() / span.toDouble() else 0.0
                        val gap = currGap[j - 1] + 1
                        // Guard against runaway horizontal gaps across verses:
                        // Discard weak matches (<= 3) when gap exceeds maxWeakGap to allow clean local restarts.
                        // For deeper matches (>= 4), reset if span exceeds twice the score or density drops below 0.55.
                        if ((prevS <= 3 && gap > maxWeakGap) || (prevS >= 4 && span >= 8 && (span > 2 * prevS || localDensity < 0.55))) {
                            currScore[j] = 0
                            currStart[j] = j
                            currGap[j] = 0
                        } else {
                            currScore[j] = prevS
                            currStart[j] = st
                            currGap[j] = gap
                        }
                    }
                }
            }
            val tmpS = prevScore
            prevScore = currScore
            currScore = tmpS
            java.util.Arrays.fill(currScore, 0, n + 2, 0)

            val tmpSt = prevStart
            prevStart = currStart
            currStart = tmpSt
            java.util.Arrays.fill(currStart, 0, n + 2, 0)

            val tmpG = prevGap
            prevGap = currGap
            currGap = tmpG
            java.util.Arrays.fill(currGap, 0, n + 2, 0)
        }

        // Keep internal references updated
        dpPrevScore = prevScore
        dpCurrScore = currScore
        dpPrevStart = prevStart
        dpCurrStart = currStart
        dpPrevGap = prevGap
        dpCurrGap = currGap

        var bestScore = 0
        var bestStartJ = 0
        var bestEndJ = 0
        var bestDensity = 0.0

        for (j in 1..n) {
            val s = prevScore[j]
            if (s >= 3) {
                val st = prevStart[j]
                val span = maxOf(1, j - st + 1)
                val dens = s.toDouble() / span.toDouble()
                if (dens >= 0.60 && (s > bestScore || (s == bestScore && dens > bestDensity))) {
                    bestScore = s
                    bestStartJ = st
                    bestEndJ = j
                    bestDensity = dens
                }
            }
        }

        return CompactAlignmentResult(bestScore, bestStartJ, bestEndJ, bestDensity)
    }

    /**
     * Continuous Character-LCS Sequential Window Tracking for streaming phoneme output.
     * Uses Compact Local Alignment and Strict Sequential Advance Rules to prevent the Runaway Train effect.
     */
    private fun trackWithinSequentialWindowCharacterLCS(stream: String): TrackingResult {
        if (stream.length < 2 || verseCanonicalChars.isEmpty() || confirmedVerseIndex !in verseCanonicalChars.indices) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        // Window spans from utteranceAnchorVerseIndex up to confirmedVerseIndex + 2
        // Effective start looks back up to 7 verses to prevent erosion during short-verse recitations
        val vStart = maxOf(0, minOf(utteranceAnchorVerseIndex, confirmedVerseIndex))
        val effectiveStart = maxOf(vStart, confirmedVerseIndex - 7)
        val vEnd = minOf(verseCanonicalChars.size - 1, confirmedVerseIndex + 2)

        val windowVerseIndices = ArrayList<Int>(vEnd - effectiveStart + 1)
        val verseStartInRef = ArrayList<Int>(vEnd - effectiveStart + 1)
        val refWindowBuilder = java.lang.StringBuilder(256)

        for (v in effectiveStart..vEnd) {
            windowVerseIndices.add(v)
            verseStartInRef.add(refWindowBuilder.length)
            refWindowBuilder.append(verseCanonicalChars[v])
        }

        val refWindow = refWindowBuilder.toString()
        if (refWindow.isEmpty()) {
            return TrackingResult("", null, null, 0, 0.0, recitationMode)
        }

        // Align the tail of stream against refWindow
        val maxTailLen = (refWindow.length * 4 / 3).coerceAtLeast(20)
        val streamToAlign = if (stream.length > maxTailLen) stream.substring(stream.length - maxTailLen) else stream
        val phonStreamToAlign = toPhoneticCanonicalStream(streamToAlign)

        val alignResult = alignStreamToRefCompact(phonStreamToAlign, refWindow)
        val score = alignResult.score
        val matchRatio = if (phonStreamToAlign.isNotEmpty()) score.toDouble() / phonStreamToAlign.length.toDouble() else 0.0

        val hasUltraShortInWindow = windowVerseIndices.any { vIdx ->
            vIdx in verseCanonicalChars.indices && verseCanonicalChars[vIdx].length <= 8
        }
        val targetVerseLen = if (confirmedVerseIndex in verseCanonicalChars.indices) verseCanonicalChars[confirmedVerseIndex].length else refWindow.length
        val isLongVerse = (targetVerseLen > 50)

        val isUltraShortValid = (score >= 3 && matchRatio >= 0.75 && alignResult.density >= 0.75 && hasUltraShortInWindow)
        val isStandardValid = if (isLongVerse && phonStreamToAlign.length <= 6) {
            (score >= 5 && matchRatio >= 0.80 && alignResult.density >= 0.70)
        } else {
            (score >= 4 && matchRatio >= 0.65 && alignResult.density >= 0.60)
        }

        var effectiveAlignResult = alignResult
        var effectiveScore = score
        var effectiveRatio = matchRatio

        if (!isUltraShortValid && !isStandardValid) {
            // Tail Sub-Stream Fallback: If initial breath/words diluted ratio, test sub-stream tail
            var recoveredByTail = false
            if (phonStreamToAlign.length >= 8) {
                for (tailSkip in listOf(2, 4, phonStreamToAlign.length / 2)) {
                    if (phonStreamToAlign.length - tailSkip >= 4) {
                        val tailStream = phonStreamToAlign.substring(tailSkip)
                        val tailRes = alignStreamToRefCompact(tailStream, refWindow)
                        val tailRatio = if (tailStream.isNotEmpty()) tailRes.score.toDouble() / tailStream.length.toDouble() else 0.0
                        val isTailValid = if (isLongVerse) {
                            (tailRes.score >= 5 && tailRatio >= 0.75 && tailRes.density >= 0.75)
                        } else {
                            (tailRes.score >= 4 && tailRatio >= 0.70 && tailRes.density >= 0.65) ||
                            (tailRes.score >= 3 && tailRatio >= 0.75 && tailRes.density >= 0.75 && hasUltraShortInWindow)
                        }
                        if (isTailValid) {
                            effectiveAlignResult = tailRes
                            effectiveScore = tailRes.score
                            effectiveRatio = tailRatio
                            recoveredByTail = true
                            break
                        }
                    }
                }
            }

            if (!recoveredByTail) {
                consecutiveMismatches++
                AppLogger.d(
                    TAG,
                    "TrackReject: confirmedIdx=$confirmedVerseIndex | score=$score | ratio=${"%.2f".format(matchRatio)} | dens=${"%.2f".format(alignResult.density)} | mismatches=$consecutiveMismatches"
                )
                return TrackingResult("", null, null, 0, matchRatio, recitationMode)
            }
        }

        consecutiveMismatches = 0
        lastMatchedSpeechTimeMs = System.currentTimeMillis()

        val bestEndJ = effectiveAlignResult.endJ
        val bestStartJ = effectiveAlignResult.startJ

        // Map bestEndJ to the matching verse in the window
        var targetVerseIdx = confirmedVerseIndex
        var offsetInVerse = 0
        var totalVerseChars = if (confirmedVerseIndex in verseCanonicalChars.indices) verseCanonicalChars[confirmedVerseIndex].length else 1

        val currentConfirmedPage = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)?.page ?: 1

        var isPendingAdvance = false
        for (i in windowVerseIndices.indices.reversed()) {
            val vIdx = windowVerseIndices[i]
            val vStartPos = verseStartInRef[i]
            val vLen = verseCanonicalChars[vIdx].length
            val candidatePage = MushafPageRepository.getVerseByGlobalIndex(vIdx)?.page ?: currentConfirmedPage
            val minCharsToEnter = if (candidatePage != currentConfirmedPage) {
                minOf(7, maxOf(3, vLen / 2))
            } else if (vIdx == confirmedVerseIndex + 1) {
                2 // Fast seamless transition to immediate next verse on same page
            } else {
                4
            }

            if (bestEndJ >= vStartPos + minCharsToEnter) {
                targetVerseIdx = vIdx
                offsetInVerse = minOf(vLen, maxOf(0, bestEndJ - vStartPos))
                totalVerseChars = vLen
                break
            } else if (vIdx > confirmedVerseIndex && bestEndJ > vStartPos) {
                // Hold On Pending: The reciter has entered the next verse, but hasn't reached minCharsToEnter yet.
                // Do NOT fall back to i == 0 to falsely claim a match on the old verse!
                isPendingAdvance = true
                break
            } else if (i == 0) {
                targetVerseIdx = vIdx
                offsetInVerse = minOf(vLen, maxOf(0, bestEndJ - vStartPos))
                totalVerseChars = vLen
                break
            }
        }

        if (isPendingAdvance) {
            // Stay in HOLD state waiting for next characters
            return TrackingResult("", null, null, 0, effectiveRatio, recitationMode)
        }

        // Strict advance guards: never skip more than 1 verse in a single step unless confirmed
        if (targetVerseIdx > confirmedVerseIndex + 1) {
            val v2Pos = windowVerseIndices.indexOf(confirmedVerseIndex + 2)
            val v2Start = if (v2Pos >= 0) verseStartInRef[v2Pos] else -1
            if (v2Start >= 0 && bestEndJ >= v2Start + 6) {
                confirmedVerseIndex = targetVerseIdx
            } else {
                targetVerseIdx = confirmedVerseIndex + 1
                confirmedVerseIndex = targetVerseIdx
                val v1Len = if (targetVerseIdx in verseCanonicalChars.indices) verseCanonicalChars[targetVerseIdx].length else 1
                // Safe offset clamping: clamp to start of intermediate verse (never leap to its end)
                offsetInVerse = minOf(v1Len / 3, 4)
                totalVerseChars = v1Len
            }
        } else if (targetVerseIdx > confirmedVerseIndex) {
            confirmedVerseIndex = targetVerseIdx
        }

        val offsets = if (targetVerseIdx in verseWordCharOffsets.indices) verseWordCharOffsets[targetVerseIdx] else IntArray(0)
        var wordInAyah = 0
        for (wIdx in offsets.indices) {
            if (offsetInVerse >= offsets[wIdx]) {
                wordInAyah = minOf(offsets.size - 1, wIdx + 1)
            } else {
                break
            }
        }

        val totalWords = maxOf(1, offsets.size)
        val firstWordGlobal = if (targetVerseIdx in verseFirstWordIndex.indices) verseFirstWordIndex[targetVerseIdx] else 0
        activeWordPointer = minOf(allWords.size - 1, firstWordGlobal + wordInAyah)

        val mv = MushafPageRepository.getVerseByGlobalIndex(targetVerseIdx)
        if (mv != null && mv.surah > 1) {
            lastNonFatihahVerseIndex = targetVerseIdx
        }

        val charProgress = (offsetInVerse.toFloat() / maxOf(1, totalVerseChars).toFloat()).coerceIn(0f, 1f)

        if (mv != null && mv.surah == 1 && mv.ayah == 7 && (offsetInVerse >= totalVerseChars - 4 || wordInAyah >= totalWords - 1)) {
            enterDiscoveryMode("Completed Al-Fatihah 1:7 (ولا الضالين)")
            return TrackingResult(
                filteredText = mv.cleanText,
                matchedVerseIndex = targetVerseIdx,
                matchedVerse = mv,
                matchedWordsCount = wordInAyah + 1,
                highestSimilarity = effectiveRatio,
                recitationMode = RecitationMode.DISCOVERY,
                fatihahCompletedNow = true,
                currentWordInAyah = wordInAyah,
                totalWordsInAyah = maxOf(1, totalWords),
                charProgress = 1.0f
            )
        }

        return TrackingResult(
            filteredText = mv?.cleanText ?: "",
            matchedVerseIndex = targetVerseIdx,
            matchedVerse = mv,
            matchedWordsCount = wordInAyah + 1,
            highestSimilarity = effectiveRatio,
            recitationMode = recitationMode,
            currentWordInAyah = wordInAyah,
            totalWordsInAyah = maxOf(1, totalWords),
            charProgress = charProgress
        )
    }

    /**
     * Two-Stage Discovery Engine:
     * Stage 1: Ultra-fast Candidate Retrieval via 4-grams Inverted Index (< 0.3ms).
     * Stage 2: Precision Verification via 5-gram check and Compact Substring Alignment (< 0.2ms).
     */
    private fun discoverVerseFromCharacterStream(stream: String): TrackingResult? {
        if (stream.length < 3 || fourGramToVerseIndices.isEmpty()) return null

        val now = System.currentTimeMillis()
        if (discoveryRollingChars.isNotEmpty() && now - lastMatchedSpeechTimeMs > 8000L) {
            discoveryRollingChars.setLength(0)
            lastDiscoveryStreamLength = 0
        }

        if (stream.length < lastDiscoveryStreamLength) {
            lastDiscoveryStreamLength = 0
        }

        val delta = if (stream.length > lastDiscoveryStreamLength) {
            stream.substring(lastDiscoveryStreamLength)
        } else {
            ""
        }
        lastDiscoveryStreamLength = stream.length

        if (delta.isNotEmpty()) {
            discoveryRollingChars.append(delta)
            if (discoveryRollingChars.length > 50) {
                discoveryRollingChars.delete(0, discoveryRollingChars.length - 40)
            }
        }

        val rolling = discoveryRollingChars.toString()
        if (rolling.length < 8) return null

        // Check if reciter started Al-Fatihah (require رب or عالمين or رحمن to avoid hijacking Al-Kahf and other surahs)
        val isFatihahStream = ((rolling.contains("الحمد") || rolling.contains("لله")) &&
                (rolling.contains("رب") || rolling.contains("عالمين") || rolling.contains("رحمن"))) ||
            rolling.contains("اهدنالصراط") ||
            rolling.contains("اهدينصراط") || rolling.contains("اياكنعبد") ||
            rolling.contains("صراطلذين")
        if (isFatihahStream) {
            val fatihahTarget = if (rolling.contains("اهد") || rolling.contains("صراط")) 5 else 1
            val fv = MushafPageRepository.getVerseByGlobalIndex(fatihahTarget)
            if (fv != null) {
                resetPointerToVerse(fatihahTarget)
                discoveryRollingChars.setLength(0)
                lastDiscoveryStreamLength = 0
                AppLogger.i(TAG, "Character Discovery locked onto Al-Fatihah [1:${fv.ayah}]")
                return TrackingResult(
                    filteredText = fv.cleanText,
                    matchedVerseIndex = fatihahTarget,
                    matchedVerse = fv,
                    matchedWordsCount = 1,
                    highestSimilarity = 0.95,
                    recitationMode = RecitationMode.TRACKING,
                    discoveredNewLocation = true
                )
            }
        }

        // Strip initial Basmalah prefix completely (including connected "بسمللهلرحمنلرحيم")
        // Only strip when Basmalah starts near the beginning (index 0..4) so Surah An-Naml ("انه من سليمان وانه بسم...") is never stripped!
        var cleanRolling = rolling
        val bsmIdx = cleanRolling.indexOf("بسم")
        val rahimIdx = cleanRolling.indexOf("رحيم")
        if (bsmIdx in 0..4 && rahimIdx in 6..24) {
            val after = cleanRolling.substring(rahimIdx + 4)
            if (after.length >= 6) {
                cleanRolling = after
            } else {
                return null // Still reciting initial Basmalah or fewer than 6 chars after it
            }
        } else if (bsmIdx == -1 && rahimIdx in 0..14 && (cleanRolling.startsWith("رحمن") || cleanRolling.startsWith("لرحمن") || cleanRolling.startsWith("ارحمن"))) {
            // Handle case where endpoint split right after "بسم الله" leaving only "الرحمن الرحيم" at the start of rolling buffer
            val after = cleanRolling.substring(rahimIdx + 4)
            if (after.length >= 6) {
                cleanRolling = after
            } else {
                return null
            }
        }
        if (cleanRolling.length < 8) return null

        // --- Continuity Priority: Check lastNonFatihahVerseIndex and its next verse first! ---
        if (lastNonFatihahVerseIndex in verseCanonicalChars.indices) {
            val candidateIndices = listOf(lastNonFatihahVerseIndex, lastNonFatihahVerseIndex + 1)
            for (cIdx in candidateIndices) {
                if (cIdx in verseCanonicalChars.indices) {
                    val ref = verseCanonicalChars[cIdx]
                    val align = alignStreamToRefCompact(cleanRolling, ref)
                    val ratio = align.score.toDouble() / cleanRolling.length
                    if (align.score >= 5 && ratio >= 0.70 && align.density >= 0.65) {
                        val mv = MushafPageRepository.getVerseByGlobalIndex(cIdx)
                        if (mv != null) {
                            resetPointerToVerse(cIdx)
                            discoveryRollingChars.setLength(0)
                            lastDiscoveryStreamLength = 0
                            AppLogger.i(TAG, "Continuity Discovery locked onto [${mv.surah}:${mv.ayah}] (Page ${mv.page})")
                            return TrackingResult(
                                filteredText = mv.cleanText,
                                matchedVerseIndex = cIdx,
                                matchedVerse = mv,
                                matchedWordsCount = 1,
                                highestSimilarity = ratio,
                                recitationMode = RecitationMode.TRACKING,
                                discoveredNewLocation = true
                            )
                        }
                    }
                }
            }
        }

        // --- STAGE 1: 4-gram Candidate Retrieval ---
        val candidateHits = HashMap<Int, Int>(64)
        for (j in 0 until cleanRolling.length - 3) {
            val tetra = cleanRolling.substring(j, j + 4)
            val matches = fourGramToVerseIndices[tetra] ?: continue
            if (matches.size <= 40) {
                for (vIdx in matches) {
                    candidateHits[vIdx] = (candidateHits[vIdx] ?: 0) + 1
                }
            }
        }

        if (lastNonFatihahVerseIndex in verseCanonicalChars.indices) {
            candidateHits[lastNonFatihahVerseIndex] = (candidateHits[lastNonFatihahVerseIndex] ?: 0) + 3
            if (lastNonFatihahVerseIndex + 1 in verseCanonicalChars.indices) {
                candidateHits[lastNonFatihahVerseIndex + 1] = (candidateHits[lastNonFatihahVerseIndex + 1] ?: 0) + 3
            }
        }

        if (candidateHits.isEmpty()) return null

        // Require at least two 4-gram hits or a high density ratio, with prefix-start bonus
        val prefix4 = cleanRolling.take(4)
        val topCandidates = candidateHits.entries
            .filter { it.value >= 2 || cleanRolling.length <= 9 }
            .sortedByDescending { e ->
                val canon = if (e.key in verseCanonicalChars.indices) verseCanonicalChars[e.key] else ""
                val vLen = canon.length.coerceAtLeast(10)
                val startsWithBonus = if (canon.startsWith(prefix4)) 2.5 else 0.0
                (e.value.toDouble() + startsWithBonus) / vLen.toDouble()
            }
            .take(15)

        if (topCandidates.isEmpty()) return null

        // --- STAGE 2: Precision Verification via 5-gram check & Compact Alignment ---
        data class ScoredCandidate(
            val vIdx: Int,
            val score: Int,
            val ratio: Double,
            val density: Double,
            val endJ: Int
        )

        val scoredCandidates = ArrayList<ScoredCandidate>(15)

        for (cand in topCandidates) {
            val vIdx = cand.key
            val canon = verseCanonicalChars[vIdx]

            // If cleanRolling is significantly longer than canon (e.g. reading 2 short verses in a breath),
            // match against extended reference (canon + nextVerse) or tail of stream
            val nextCanon = if (vIdx + 1 in verseCanonicalChars.indices &&
                MushafPageRepository.getVerseByGlobalIndex(vIdx + 1)?.surah == MushafPageRepository.getVerseByGlobalIndex(vIdx)?.surah) {
                verseCanonicalChars[vIdx + 1]
            } else ""

            val refToMatch = if (cleanRolling.length > canon.length + 4 && nextCanon.isNotEmpty()) {
                canon + nextCanon
            } else {
                canon
            }

            // 1. Check for at least one contiguous 5-gram match between cleanRolling and refToMatch
            var has5Gram = false
            for (j in 0 until cleanRolling.length - 4) {
                val five = cleanRolling.substring(j, j + 5)
                if (refToMatch.contains(five)) {
                    has5Gram = true
                    break
                }
            }

            // If cleanRolling is 10+ chars, strictly require at least one 5-gram match
            if (cleanRolling.length >= 10 && !has5Gram) {
                continue
            }

            val streamForCandidate = if (cleanRolling.length > refToMatch.length + 4) {
                cleanRolling.substring(cleanRolling.length - (refToMatch.length + 3))
            } else {
                cleanRolling
            }

            // 2. Compact Alignment
            val phonRolling = toPhoneticCanonicalStream(streamForCandidate)
            val alignResult = alignStreamToRefCompact(phonRolling, refToMatch)
            val score = alignResult.score
            val denom = minOf(phonRolling.length, refToMatch.length).coerceAtLeast(8)
            val ratio = if (denom > 0) score.toDouble() / denom.toDouble() else 0.0

            if (score >= 8 && alignResult.density >= 0.65 && ratio >= 0.65) {
                scoredCandidates.add(ScoredCandidate(vIdx, score, ratio, alignResult.density, alignResult.endJ))
            }
        }

        if (scoredCandidates.isEmpty()) return null

        scoredCandidates.sortWith(compareByDescending<ScoredCandidate> { it.score }.thenByDescending { it.ratio })
        val best = scoredCandidates[0]
        val secondBest = scoredCandidates.getOrNull(1)

        // Generic Ambiguity Gate: If top two candidates are closely contested (score difference < 2)
        // on a short prefix (< 15 chars), defer lock-on until distinguishing characters arrive!
        if (secondBest != null && (best.score - secondBest.score) < 2 && secondBest.ratio >= 0.65 && cleanRolling.length < 15) {
            AppLogger.i(TAG, "Ambiguity Gate: Candidate [${best.vIdx}] (score=${best.score}) is closely contested by [${secondBest.vIdx}] (score=${secondBest.score}) on short stream ($cleanRolling). Deferring lock-on...")
            return null
        }

        val bestVIdx = best.vIdx
        val bestRatio = best.ratio

        if (bestVIdx != -1) {
            val baseCanon = if (bestVIdx in verseCanonicalChars.indices) verseCanonicalChars[bestVIdx] else ""
            val nextCanon = if (bestVIdx + 1 in verseCanonicalChars.indices &&
                MushafPageRepository.getVerseByGlobalIndex(bestVIdx + 1)?.surah == MushafPageRepository.getVerseByGlobalIndex(bestVIdx)?.surah) {
                verseCanonicalChars[bestVIdx + 1]
            } else ""

            var lockVIdx = bestVIdx
            var offsetInVerse = 0
            var targetCanonLen = baseCanon.length

            // If the reciter finished the short first verse and entered the next verse in the same breath:
            if (baseCanon.isNotEmpty() && best.endJ > baseCanon.length + 3 && nextCanon.isNotEmpty()) {
                lockVIdx = bestVIdx + 1
                offsetInVerse = best.endJ - baseCanon.length
                targetCanonLen = nextCanon.length
            } else {
                offsetInVerse = minOf(baseCanon.length, best.endJ)
            }

            val mv = MushafPageRepository.getVerseByGlobalIndex(lockVIdx)
            if (mv != null) {
                if (mv.surah == 1 && (mv.ayah == 1 || mv.ayah == 3)) {
                    return null
                }
                resetPointerToVerse(lockVIdx)
                discoveryRollingChars.setLength(0)
                lastDiscoveryStreamLength = 0
                val discoveredCharProgress = (offsetInVerse.toFloat() / maxOf(1, targetCanonLen).toFloat()).coerceIn(0f, 1f)
                AppLogger.i(TAG, "Two-Stage Discovery locked onto verse [${mv.surah}:${mv.ayah}] (progress=${(discoveredCharProgress * 100).toInt()}%) with ratio ${"%.2f".format(bestRatio)}")
                return TrackingResult(
                    filteredText = mv.cleanText,
                    matchedVerseIndex = lockVIdx,
                    matchedVerse = mv,
                    matchedWordsCount = 1,
                    highestSimilarity = bestRatio,
                    recitationMode = RecitationMode.TRACKING,
                    discoveredNewLocation = true,
                    charProgress = discoveredCharProgress
                )
            }
        }
        return null
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
        val displayIdx = matchedWords.lastOrNull()?.displayVerseIndex ?: verseIdx
        val mv = MushafPageRepository.getVerseByGlobalIndex(displayIdx)
        val filteredStr = matchedWords.joinToString(" ") { it.originalText }

        // Check if the reciter just reached the end of Surah Al-Fatihah (1:7 "ولا الضالين")
        // Note: we still check the logical verse (verseIdx == 6) internally, but let's just use mv's logic.
        val lastWord = matchedWords.lastOrNull()
        val wordInAyah = lastWord?.wordIndexInVerse ?: 0
        val totalWords = if (verseIdx in verseWordCount.indices) verseWordCount[verseIdx] else 1
        val computedCharProgress = ((wordInAyah + 1).toFloat() / maxOf(1, totalWords).toFloat()).coerceIn(0f, 1f)

        if (mv != null && mv.surah == 1 && mv.ayah == 7) {
            val fatihahEndWordIdx = verseFirstWordIndex[6] + verseWordCount[6] - 1 // "الضالين"
            val reachedEndOfFatihah = matchedWords.any { w ->
                w.globalIndex >= fatihahEndWordIdx || w.skeletonText == "الضلين" || w.skeletonText == "المغضوب"
            }
            if (reachedEndOfFatihah) {
                enterDiscoveryMode("Completed Al-Fatihah 1:7 (ولا الضالين)")
                return TrackingResult(
                    filteredText = filteredStr,
                    matchedVerseIndex = displayIdx,
                    matchedVerse = mv,
                    matchedWordsCount = matchedWords.size,
                    highestSimilarity = highestSim,
                    recitationMode = RecitationMode.DISCOVERY,
                    fatihahCompletedNow = true,
                    currentWordInAyah = wordInAyah,
                    totalWordsInAyah = maxOf(1, totalWords),
                    charProgress = 1.0f
                )
            }
        }

        return TrackingResult(
            filteredText = filteredStr,
            matchedVerseIndex = displayIdx,
            matchedVerse = mv,
            matchedWordsCount = matchedWords.size,
            highestSimilarity = highestSim,
            recitationMode = recitationMode,
            currentWordInAyah = wordInAyah,
            totalWordsInAyah = maxOf(1, totalWords),
            charProgress = computedCharProgress
        )
    }

    private fun handleMismatchFrame(rawWords: List<String>) {
        val isOnlyTransitionWords = rawWords.all { rw ->
            TRANSITION_WORDS.any { tw -> FuzzyMatcher.wordSimilarity(rw, tw) >= 0.78 } ||
                PRAYER_TAKBIR_WORDS.any { pw -> FuzzyMatcher.wordSimilarity(rw, pw) >= 0.80 }
        }
        if (!isOnlyTransitionWords) {
            val hasValidQuranWord = rawWords.any { rw ->
                val skel = FuzzyMatcher.skeletonWord(rw)
                skeletonToGlobalIndices.containsKey(skel)
            }
            if (hasValidQuranWord) {
                consecutiveMismatches++
            }
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
        // AND at least one exclusive Fatihah word. For 1:2 ("الحمد لله رب العالمين"), require either "العلمين" or "رب" or 3+ words to prevent matching "الحمد لله الذي" in Al-Kahf
        val hasExclusiveFatihahWord = matched.any {
            it.skeletonText in setOf("العلمين", "ملك", "الدين", "اياك", "نعبد", "نستعين") ||
            (it.skeletonText == "الحمد" && (matched.any { m -> m.skeletonText in setOf("العلمين", "رب") } || matched.size >= 3))
        }
        if (matched.size >= 2 && (matched.last().globalIndex - matched.first().globalIndex) <= 4 && hasExclusiveFatihahWord) {
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
        // With Local Locality Bias (2.5x multiplier for verses on current or adjacent page to prevent false jumps)
        val currentAyahPage = MushafPageRepository.getVerseByGlobalIndex(confirmedVerseIndex)?.page ?: -1
        var bestIdx = -1
        var bestScore = 0.0
        var bestVerseIdx = -1
        var secondBestScore = 0.0

        for (i in 0 until m) {
            val vIdx = allWords[allHits[i].globalWordIdx].verseIndex
            val candPage = MushafPageRepository.getVerseByGlobalIndex(vIdx)?.page ?: -1
            val isLocalNeighborhood = currentAyahPage > 0 && kotlin.math.abs(candPage - currentAyahPage) <= 1
            val localityMultiplier = if (isLocalNeighborhood) 2.50 else 1.00
            val s = chainScore[i] * localityMultiplier

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
        
        // ADD THIS CHECK:
        val prospectiveVerse = MushafPageRepository.getVerseByGlobalIndex(discoveredVIdx)
        if (prospectiveVerse?.surah == 1 && prospectiveVerse?.ayah == 1) {
            // Ignore Bismillah as Ayah 1:1 match to prevent jumping to Fatihah between Surahs
            return null
        }

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
        val firstWordMap = IntArray(verses.size) { -1 }
        val countMap = IntArray(verses.size) { 0 }

        val logicalVerseMap = IntArray(verses.size) { it }
        for (vIdx in verses.indices) {
            val verse = verses[vIdx]
            val cleanVerse = FuzzyMatcher.normalizeArabic(verse)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            
            if (cleanVerse.contains("فباي الاء ربكما تكذبان") || cleanVerse.contains("ويل يومئذ للمكذبين") || cleanVerse.contains("فبأي آلاء ربكما تكذبان")) {
                if (vIdx > 0) {
                    logicalVerseMap[vIdx] = logicalVerseMap[vIdx - 1]
                }
            } else if (cleanVerse == "الرحمن الرحيم" && vIdx > 0 && FuzzyMatcher.normalizeArabic(verses[vIdx - 1]).contains("الحمد لله رب العالمين")) {
                logicalVerseMap[vIdx] = logicalVerseMap[vIdx - 1]
            } else if (verseTokens.size == 1) {
                var nextIdx = vIdx + 1
                while (nextIdx < verses.size) {
                    val nextClean = FuzzyMatcher.normalizeArabic(verses[nextIdx])
                    val nextTokens = nextClean.split(" ").filter { it.isNotBlank() }
                    if (nextTokens.size > 1 && !nextClean.contains("فباي الاء") && !nextClean.contains("ويل يومئذ")) {
                        break
                    }
                    nextIdx++
                }
                logicalVerseMap[vIdx] = minOf(nextIdx, verses.size - 1)
            }
        }
        
        var globalIdx = 0
        for ((vIdx, verse) in verses.withIndex()) {
            val logicalVIdx = logicalVerseMap[vIdx]
            
            if (firstWordMap[logicalVIdx] == -1) {
                firstWordMap[logicalVIdx] = globalIdx
            }
            val cleanVerse = FuzzyMatcher.normalizeArabic(verse)
            val verseTokens = cleanVerse.split(" ").filter { it.isNotBlank() }
            val rawTokens = verse.split(" ").filter { it.isNotBlank() }
            
            countMap[logicalVIdx] += verseTokens.size
            for (wIdx in verseTokens.indices) {
                val orig = if (wIdx < rawTokens.size) rawTokens[wIdx] else verseTokens[wIdx]
                val norm = verseTokens[wIdx]
                words.add(
                    QuranWord(
                        globalIndex = globalIdx++,
                        verseIndex = logicalVIdx,
                        displayVerseIndex = vIdx,
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
