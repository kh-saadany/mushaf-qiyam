package com.mushafqiyam

import kotlin.math.max

object FuzzyMatcher {

    data class MatchResult(val verse: String, val similarity: Double)

    /**
     * Compares recognized text with a list of verses and returns the best match above a threshold.
     */
    fun matchVerse(recognizedText: String, currentPageVerses: List<String>, threshold: Double = 0.6): MatchResult? {
        val cleanRecognized = removeDiacritics(recognizedText)
        if (cleanRecognized.isBlank()) return null

        return currentPageVerses
            .map { verse ->
                MatchResult(
                    verse = verse,
                    similarity = normalizedLevenshtein(cleanRecognized, removeDiacritics(verse))
                )
            }
            .filter { it.similarity >= threshold }
            .maxByOrNull { it.similarity }
    }

    /**
     * Removes Arabic diacritics (Tashkeel) for clean matching.
     */
    private fun removeDiacritics(text: String): String {
        val regex = Regex("[\\u0617-\\u061A\\u064B-\\u0652]")
        return regex.replace(text, "")
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
