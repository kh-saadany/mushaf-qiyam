package com.mushafqiyam

/**
 * QuranVocabularyFilter: Dynamically filters raw ASR model output text
 * against the allowed vocabulary of verses in current search scope [currentIndex - 1, currentIndex + 3].
 * Features Zero-GC Caching: allowedWords HashSet is only built when currentIndex changes!
 */
object QuranVocabularyFilter {

    private var cachedCurrentIndex: Int = -999
    private var cachedAllowedWords: HashSet<String> = HashSet()

    /**
     * Retrieves cached allowedWords set, rebuilding only when currentIndex changes.
     */
    @Synchronized
    fun getOrBuildAllowedWords(verses: List<String>, currentIndex: Int): Set<String> {
        if (currentIndex == cachedCurrentIndex && cachedAllowedWords.isNotEmpty()) {
            return cachedAllowedWords
        }

        val newSet = HashSet<String>()
        if (verses.isNotEmpty()) {
            val startIndex = maxOf(0, currentIndex - 1)
            val endIndex = minOf(verses.size - 1, currentIndex + 3)

            for (i in startIndex..endIndex) {
                val cleanVerse = FuzzyMatcher.normalizeArabic(verses[i])
                cleanVerse.split(" ").forEach { word ->
                    val cleanWord = word.trim()
                    if (cleanWord.isNotBlank()) {
                        newSet.add(cleanWord)
                    }
                }
            }
        }

        cachedCurrentIndex = currentIndex
        cachedAllowedWords = newSet
        return cachedAllowedWords
    }

    /**
     * Filters raw ASR text, keeping only words that exist in allowedWords.
     * Returns empty string if no valid Quranic words remain.
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
