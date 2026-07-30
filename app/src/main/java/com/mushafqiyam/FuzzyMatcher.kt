package com.mushafqiyam

import kotlin.math.max

object FuzzyMatcher {

    data class MatchResult(
        val verseIndex: Int,
        val verseText: String,
        val similarity: Double,
        val matchedSegment: String
    )

    /**
     * Comprehensive Arabic normalization:
     * Removes Tashkeel, normalizes Alef forms, Taa Marbouta, Yaa/Maqsoora, and punctuation.
     */
    fun normalizeArabic(text: String): String {
        if (text.isBlank()) return ""
        
        var normalized = text
            // Remove Tashkeel / Diacritics
            .replace(Regex("[\\u0617-\\u061A\\u064B-\\u0652]"), "")
            // Normalize Alef forms
            .replace(Regex("[إأآٱ]"), "ا")
            // Normalize Taa Marbouta to Haa
            .replace("ة", "ه")
            // Normalize Alef Maqsoora to Yaa
            .replace("ى", "ي")
            // Remove non-Arabic punctuation / symbols
            .replace(Regex("[^\\u0600-\\u06FF\\s]"), "")
            // Normalize multiple whitespaces
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized
    }

    /**
     * Compares recognized streaming text against candidate verses
     * in range [-1 to +3] relative to currentIndex using adaptive threshold rules:
     * - Current verse (0) and Next verse (+1): threshold = 0.45 (45%)
     * - Second next verse (+2): threshold = 0.55 (55%)
     * - Third next verse (+3): threshold = 0.60 (60%)
     * - Previous verse (-1): threshold = 0.60 (60%)
     */
    fun matchVerse(
        recognizedText: String,
        candidateVerses: List<String>,
        currentIndex: Int = -1
    ): MatchResult? {
        val cleanRec = normalizeArabic(recognizedText)
        if (cleanRec.length < 3) return null

        var bestMatch: MatchResult? = null
        var maxSim = 0.0

        // Search range: [-1, +3] relative to currentIndex
        val startIndex = if (currentIndex >= 0) maxOf(0, currentIndex - 1) else 0
        val endIndex = if (currentIndex >= 0) minOf(candidateVerses.size - 1, currentIndex + 3) else minOf(candidateVerses.size - 1, 3)

        val baseIndex = if (currentIndex >= 0) currentIndex else 0

        for (index in startIndex..endIndex) {
            val verse = candidateVerses[index]
            val cleanVerse = normalizeArabic(verse)
            if (cleanVerse.isNotBlank()) {
                val recWords = cleanRec.split(" ").filter { it.isNotBlank() }
                val verseWords = cleanVerse.split(" ").filter { it.isNotBlank() }

                // Check shared words count (Minimum 2 shared normalized words required, or 1 if verse has only 1 word)
                val sharedWordsCount = recWords.count { verseWords.contains(it) }
                val isWordCountValid = if (verseWords.size <= 1) {
                    sharedWordsCount >= 1
                } else {
                    sharedWordsCount >= 2
                }

                if (isWordCountValid) {
                    val sim = calculateSimilarity(cleanRec, cleanVerse)
                    
                    // Adaptive threshold determination based on distance relative to baseIndex
                    val requiredThreshold = when (index - baseIndex) {
                        -1 -> 0.60   // Previous verse: 60%
                        0 -> 0.45    // Current verse: 45%
                        1 -> 0.45    // Next verse: 45%
                        2 -> 0.55    // Second next verse: 55%
                        3 -> 0.60    // Third next verse: 60%
                        else -> 0.60
                    }

                    if (sim > maxSim && sim >= requiredThreshold) {
                        maxSim = sim
                        bestMatch = MatchResult(
                            verseIndex = index,
                            verseText = verse,
                            similarity = sim,
                            matchedSegment = cleanRec
                        )
                    }
                }
            }
        }

        return bestMatch
    }

    /**
     * Calculates normalized similarity with both full Levenshtein and sliding token window match.
     */
    private fun calculateSimilarity(recognized: String, verse: String): Double {
        // Direct Levenshtein similarity
        val fullSim = normalizedLevenshtein(recognized, verse)
        
        // Sliding window token similarity for partial streaming inputs
        val recTokens = recognized.split(" ")
        val verseTokens = verse.split(" ")

        if (recTokens.size <= verseTokens.size && recTokens.isNotEmpty()) {
            val windowSize = recTokens.size
            var maxWindowSim = 0.0

            for (i in 0..(verseTokens.size - windowSize)) {
                val subVerse = verseTokens.subList(i, i + windowSize).joinToString(" ")
                val windowSim = normalizedLevenshtein(recognized, subVerse)
                if (windowSim > maxWindowSim) {
                    maxWindowSim = windowSim
                }
            }

            return maxOf(fullSim, maxWindowSim)
        }

        return fullSim
    }

    /**
     * Calculates Levenshtein distance and normalizes it to a 0.0-1.0 similarity score.
     */
    private fun normalizedLevenshtein(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return 1.0 - (dist.toDouble() / maxLen.toDouble())
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val len0 = lhs.length + 1
        val len1 = rhs.length + 1
        var cost = IntArray(len0)
        var newCost = IntArray(len0)

        for (i in 0 until len0) cost[i] = i

        for (j in 1 until len1) {
            newCost[0] = j
            for (i in 1 until len0) {
                val match = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                val costReplace = cost[i - 1] + match
                val costInsert = cost[i] + 1
                val costDelete = newCost[i - 1] + 1
                newCost[i] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[len0 - 1]
    }
}

