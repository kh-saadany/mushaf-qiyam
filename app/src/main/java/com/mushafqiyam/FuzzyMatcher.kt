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
     * Expands Quranic disjointed letters (الحروف المقطعة) into their spoken phonetic word forms.
     * e.g., "الم" -> "الف لام ميم", "الر" -> "الف لام را", "حم" -> "حا ميم"
     * Uses regex-free string splitting for 100% compatibility with Android ICU engine.
     */
    fun expandMuqattaat(text: String): String {
        if (text.isBlank()) return text
        val words = text.split(" ")
        val expandedWords = words.map { w ->
            when (w.trim()) {
                "الم" -> "الف لام ميم"
                "المص" -> "الف لام ميم صاد"
                "الر" -> "الف لام را"
                "المر" -> "الف لام ميم را"
                "كهيعص" -> "كاف ها يا عين صاد"
                "طه" -> "طا ها"
                "طسم" -> "طا سين ميم"
                "طس" -> "طا سين"
                "يس" -> "يا سين"
                "ص" -> "صاد"
                "حم" -> "حا ميم"
                "عسق" -> "عين سين قاف"
                "ق" -> "قاف"
                "ن" -> "نون"
                else -> w
            }
        }
        return expandedWords.joinToString(" ")
    }

    /**
     * Normalizes Arabic text for flexible matching across both Uthmani and Imla'i scripts:
     * - Removes BOM, Tashkeel (diacritics), dagger alef, and Quranic tajweed/pause marks
     * - Expands Quranic disjointed letters (الم -> الف لام ميم)
     * - Normalizes Alef/Hamza forms (أ, إ, آ, ٱ -> ا, ؤ -> و, ئ -> ي)
     * - Normalizes Taa Marbouta (ة -> ه) and Alef Maqsura (ى -> ي)
     */
    fun normalizeArabic(text: String): String {
        if (text.isBlank()) return ""
        val withoutDiacritics = text
            .replace("\uFEFF", "")
            .replace(Regex("[\\u0617-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]"), "")
        val expanded = expandMuqattaat(withoutDiacritics)
        return expanded
            .replace(Regex("[إأآٱ]"), "ا")
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace('ء', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(Regex("[^\\u0621-\\u064A\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Produces an orthography-agnostic skeleton of a normalized Arabic word to bridge
     * Uthmani (dagger-alif omitted in cleanText, e.g. العلمين, الصرط, الكتب, الصلوه)
     * and Imla'i ASR output (العالمين, الصراط, الكتاب, الصلاه).
     */
    fun skeletonWord(word: String): String {
        if (word.length <= 2) return word
        var w = word
        // Handle Uthmani Waw-Alef before Taa Marbouta (الصلوه -> الصلاه, الزكوه -> الزكاه, الحيوه -> الحياه)
        if (w.endsWith("وه") && w.length >= 4) {
            w = w.substring(0, w.length - 2) + "اه"
        }
        // Strip medial Alef 'ا' after the first character (or after 'ال' / 'وال' / 'فال' / 'بال' / 'لل' prefix)
        val prefixLen = when {
            w.startsWith("وال") || w.startsWith("فال") || w.startsWith("بال") || w.startsWith("كال") -> 3
            w.startsWith("ال") || w.startsWith("لل") -> 2
            else -> 1
        }
        if (w.length <= prefixLen + 1) return w
        val sb = StringBuilder(w.length)
        sb.append(w, 0, prefixLen)
        for (i in prefixLen until w.length) {
            val ch = w[i]
            // Keep final 'ا' if it is the very last letter of a short word, otherwise strip medial 'ا'
            if (ch != 'ا') {
                sb.append(ch)
            }
        }
        return if (sb.length >= 2) sb.toString() else w
    }

    fun matchVerse(
        recognizedText: String,
        candidateVerses: List<String>,
        currentIndex: Int = -1
    ): MatchResult? {
        val cleanRec = normalizeArabic(recognizedText)
        if (cleanRec.length < 3) return null

        var bestMatch: MatchResult? = null
        var maxSim = 0.0

        val startIndex = if (currentIndex >= 0) maxOf(0, currentIndex - 1) else 0
        val endIndex = if (currentIndex >= 0) minOf(candidateVerses.size - 1, currentIndex + 3) else minOf(candidateVerses.size - 1, 3)
        val baseIndex = if (currentIndex >= 0) currentIndex else 0

        for (index in startIndex..endIndex) {
            val verse = candidateVerses[index]
            val cleanVerse = normalizeArabic(verse)
            if (cleanVerse.isNotBlank()) {
                val recWords = cleanRec.split(" ").filter { it.isNotBlank() }
                val verseWords = cleanVerse.split(" ").filter { it.isNotBlank() }

                val sharedWordsCount = recWords.count { rw ->
                    verseWords.any { vw -> wordSimilarity(rw, vw) >= 0.70 }
                }
                val isWordCountValid = if (verseWords.size <= 1) {
                    sharedWordsCount >= 1
                } else {
                    sharedWordsCount >= 2 || (recWords.size >= 2 && sharedWordsCount >= 1)
                }

                if (isWordCountValid) {
                    val baseSim = calculateSimilarity(cleanRec, cleanVerse)
                    val effectiveSim = if (index > baseIndex) baseSim + 0.12 else baseSim

                    val requiredThreshold = when (index - baseIndex) {
                        -1 -> 0.60
                        0 -> 0.45
                        1 -> 0.45
                        2 -> 0.55
                        3 -> 0.60
                        else -> 0.60
                    }

                    if (effectiveSim > maxSim && baseSim >= (requiredThreshold - 0.05)) {
                        maxSim = effectiveSim
                        bestMatch = MatchResult(
                            verseIndex = index,
                            verseText = verse,
                            similarity = baseSim,
                            matchedSegment = cleanRec
                        )
                    }
                }
            }
        }

        return bestMatch
    }

    private fun calculateSimilarity(recognized: String, verse: String): Double {
        val fullSim = normalizedLevenshtein(recognized, verse)
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
     * Compares two normalized Arabic words, accounting for both exact Levenshtein similarity
     * and Uthmani/Imla'i skeleton equivalence (e.g. العالمين == العلمين, الصراط == الصرط).
     */
    fun wordSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        val directSim = normalizedLevenshtein(s1, s2)
        if (directSim >= 0.92) return directSim
        val sk1 = skeletonWord(s1)
        val sk2 = skeletonWord(s2)
        if (sk1 == sk2 && sk1.length >= 2) return 0.98
        val skelSim = normalizedLevenshtein(sk1, sk2) * 0.96
        return max(directSim, skelSim)
    }

    fun normalizedLevenshtein(s1: String, s2: String): Double {
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
