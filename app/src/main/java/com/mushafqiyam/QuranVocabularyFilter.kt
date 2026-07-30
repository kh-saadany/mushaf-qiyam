package com.mushafqiyam

/**
 * QuranVocabularyFilter: Dynamically filters raw ASR model output text
 * against the allowed vocabulary of verses in current search scope [currentIndex - 1, currentIndex + 3].
 * Removes any out-of-domain words (e.g. "درس", "استعد", "المصدر") in O(1) time.
 */
object QuranVocabularyFilter {

    /**
     * Builds a normalized HashSet of allowed words from candidate verses range [currentIndex - 1, currentIndex + 3].
     */
    fun buildAllowedWordsSet(verses: List<String>, currentIndex: Int): HashSet<String> {
        val allowedWords = HashSet<String>()
        if (verses.isEmpty()) return allowedWords

        val startIndex = if (currentIndex >= 0) maxOf(0, currentIndex - 1) else 0
        val endIndex = if (currentIndex >= 0) minOf(verses.size - 1, currentIndex + 3) else minOf(verses.size - 1, 3)

        for (i in startIndex..endIndex) {
            val cleanVerse = FuzzyMatcher.normalizeArabic(verses[i])
            cleanVerse.split(" ").forEach { word ->
                val cleanWord = word.trim()
                if (cleanWord.isNotBlank()) {
                    allowedWords.add(cleanWord)
                }
            }
        }
        return allowedWords
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
