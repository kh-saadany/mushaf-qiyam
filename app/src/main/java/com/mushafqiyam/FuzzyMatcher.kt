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
     * constrained by current active verse index for forward sequential Quran tracking.
     */
    fun matchVerse(
        recognizedText: String,
        candidateVerses: List<String>,
        currentIndex: Int = -1,
        threshold: Double = 0.50
    ): MatchResult? {
        val cleanRec = normalizeArabic(recognizedText)
        // Ignore single/two letter noise outputs like "ش", "ت", "ص"
        if (cleanRec.length < 3) return null

        var bestMatch: MatchResult? = null
        var maxSim = 0.0

        // Determine search range: If we have an active verse, look at current + next 3 verses.
        // Otherwise (start of recitation), look across the first 4 verses.
        val startIndex = if (currentIndex >= 0) maxOf(0, currentIndex) else 0
        val endIndex = if (currentIndex >= 0) minOf(candidateVerses.size - 1, currentIndex + 3) else minOf(candidateVerses.size - 1, 3)

        for (index in startIndex..endIndex) {
            val verse = candidateVerses[index]
            val cleanVerse = normalizeArabic(verse)
            if (cleanVerse.isNotBlank()) {
                val sim = calculateSimilarity(cleanRec, cleanVerse)
                if (sim > maxSim && sim >= threshold) {
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

