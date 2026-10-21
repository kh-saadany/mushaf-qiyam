package com.mushafqiyam

import kotlin.math.max

object FuzzyMatcher {

    private val DIACRITICS_REGEX = Regex("[\\u0617-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private val ALEF_VARIANTS_REGEX = Regex("[إأآٱ]")
    private val NON_ARABIC_REGEX = Regex("[^\\u0621-\\u064A\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    private val MUQATTAAT_MAP = mapOf(
        "الم" to "الف لام ميم",
        "المص" to "الف لام ميم صاد",
        "الر" to "الف لام را",
        "المر" to "الف لام ميم را",
        "كهيعص" to "كاف ها يا عين صاد",
        "طه" to "طا ها",
        "طسم" to "طا سين ميم",
        "طس" to "طا سين",
        "يس" to "يا سين",
        "ص" to "صاد",
        "حم" to "حا ميم",
        "عسق" to "عين سين قاف",
        "ق" to "قاف",
        "ن" to "نون"
    )

    /**
     * Expands Quranic disjointed letters (الحروف المقطعة) into their spoken phonetic word forms.
     * e.g., "الم" -> "الف لام ميم", "الر" -> "الف لام را", "حم" -> "حا ميم"
     * Zero-allocation fast path when text does not contain any Muqattaat.
     */
    fun expandMuqattaat(text: String): String {
        if (text.isBlank()) return text
        var hasMuqattaat = false
        for (k in MUQATTAAT_MAP.keys) {
            if (text.contains(k)) {
                hasMuqattaat = true
                break
            }
        }
        if (!hasMuqattaat) return text

        val words = text.split(" ")
        var modified = false
        val expandedWords = words.map { w ->
            val exp = MUQATTAAT_MAP[w.trim()]
            if (exp != null) {
                modified = true
                exp
            } else {
                w
            }
        }
        return if (modified) expandedWords.joinToString(" ") else text
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
            .replace(DIACRITICS_REGEX, "")
        val expanded = expandMuqattaat(withoutDiacritics)
        return expanded
            .replace(ALEF_VARIANTS_REGEX, "ا")
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace('ء', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace(NON_ARABIC_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
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
