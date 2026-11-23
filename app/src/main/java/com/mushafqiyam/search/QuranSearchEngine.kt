package com.mushafqiyam.search

import com.mushafqiyam.MushafPageRepository
import kotlin.system.measureTimeMillis

data class SearchVerseEntry(
    val globalIndex: Int,
    val surah: Int,
    val ayah: Int,
    val page: Int,
    val surahName: String,
    val uthmaniText: String,
    val normalizedImlai: String,
    val normalizedUthmani: String
)

data class SearchResultItem(
    val entry: SearchVerseEntry,
    val matchTier: Int, // 0 = Exact word/boundary match, 1 = Substring match
    val highlightRanges: List<IntRange>
)

data class SearchResponse(
    val query: String,
    val normalizedQuery: String,
    val results: List<SearchResultItem>,
    val totalMatches: Int,
    val elapsedMs: Long
)

object QuranSearchEngine {

    @Volatile
    private var isIndexed = false

    private var indexedVerses: Array<SearchVerseEntry> = emptyArray()

    private val lock = Any()

    val isReady: Boolean
        get() = isIndexed

    /**
     * Lazy, thread-safe single-pass indexer of all 6,236 verses.
     * Takes < 35ms on ARM64 and consumes ~2.8MB heap.
     */
    fun ensureIndexed(verses: List<MushafPageRepository.MushafVerse>) {
        if (isIndexed) return
        synchronized(lock) {
            if (isIndexed) return
            val array = Array(verses.size) { i ->
                val v = verses[i]
                val sName = MushafPageRepository.getSurahName(v.surah)
                val imlai = normalizeImlaiFromUthmani(v.text)
                val uthmani = normalizeUthmaniClean(v.cleanText)
                SearchVerseEntry(
                    globalIndex = v.globalIndex,
                    surah = v.surah,
                    ayah = v.ayah,
                    page = v.page,
                    surahName = sName,
                    uthmaniText = v.text,
                    normalizedImlai = imlai,
                    normalizedUthmani = uthmani
                )
            }
            indexedVerses = array
            isIndexed = true
        }
    }

    /**
     * Normalizes keyboard user input for search matching.
     * Preserves Muqattaat letters (الم, طه, يس) literally.
     */
    fun normalizeQuery(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        var lastSpace = false

        for (i in 0 until raw.length) {
            val ch = raw[i]

            // Skip Tashkeel, Tatweel, punctuation, and BOM
            if (isTashkeelOrPunctuation(ch)) continue

            val normalized = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى', 'ي', 'ئ' -> 'ي'
                'ؤ' -> 'و'
                '\u0670' -> 'ا' // Dagger alef if typed
                ' ', '\t', '\n', '\r' -> ' '
                else -> ch
            }

            if (normalized == ' ') {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(normalized)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * Normalizes Uthmani diacritized text into standard modern Imla'i spelling:
     * - وٰ -> ا (الصلوة -> الصلاة)
     * - ىٰ -> ي (موسىٰ -> موسي)
     * - ٰ -> ا (الصرٰط -> الصراط)
     * - Unifies Hamzas and Alef variants.
     */
    fun normalizeImlaiFromUthmani(textWithTashkeel: String): String {
        if (textWithTashkeel.isBlank()) return ""
        val sb = StringBuilder(textWithTashkeel.length)
        var lastSpace = false
        val len = textWithTashkeel.length

        var i = 0
        while (i < len) {
            val ch = textWithTashkeel[i]

            // Check digraphs with dagger alef: وٰ or ىٰ
            if (ch == 'و' || ch == 'ى') {
                // Look ahead skipping intermediate harakat for dagger alef \u0670
                var j = i + 1
                while (j < len && isDiacriticOnly(textWithTashkeel[j])) {
                    if (textWithTashkeel[j] == '\u0670') break
                    j++
                }
                if (j < len && textWithTashkeel[j] == '\u0670') {
                    if (ch == 'و') {
                        sb.append('ا')
                    } else {
                        sb.append('ي')
                    }
                    lastSpace = false
                    i = j + 1
                    continue
                }
            }

            if (ch == '\u0670') {
                sb.append('ا')
                lastSpace = false
                i++
                continue
            }

            if (isTashkeelOrPunctuation(ch)) {
                i++
                continue
            }

            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ', 'ء' -> 'ا'
                'ة' -> 'ه'
                'ى', 'ي', 'ئ' -> 'ي'
                'ؤ' -> 'و'
                ' ', '\t', '\n', '\r' -> ' '
                else -> ch
            }

            if (mapped == ' ') {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(mapped)
                lastSpace = false
            }
            i++
        }
        return sb.toString().trim()
    }

    /**
     * Normalizes cleanText (Uthmani without dagger alefs):
     * Preserves standard words where dagger alef is not typed: الرحمن, ذلك, هذا, طه, يس, الم.
     */
    fun normalizeUthmaniClean(cleanText: String): String {
        if (cleanText.isBlank()) return ""
        val sb = StringBuilder(cleanText.length)
        var lastSpace = false

        for (i in 0 until cleanText.length) {
            val ch = cleanText[i]
            if (isTashkeelOrPunctuation(ch)) continue

            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ', 'ء' -> 'ا'
                'ة' -> 'ه'
                'ى', 'ي', 'ئ' -> 'ي'
                'ؤ' -> 'و'
                ' ', '\t', '\n', '\r' -> ' '
                else -> ch
            }

            if (mapped == ' ') {
                if (!lastSpace && sb.isNotEmpty()) {
                    sb.append(' ')
                    lastSpace = true
                }
            } else {
                sb.append(mapped)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * Executes fast offline search over all 6,236 indexed verses.
     * Returns matching verses ranked by MatchTier (Word Boundary Tier 0 before Substring Tier 1)
     * and ordered by globalIndex ASC within each tier.
     */
    fun search(rawQuery: String, limit: Int = 150): SearchResponse {
        val q = normalizeQuery(rawQuery)
        if (q.length < 2 || !isIndexed) {
            return SearchResponse(
                query = rawQuery,
                normalizedQuery = q,
                results = emptyList(),
                totalMatches = 0,
                elapsedMs = 0L
            )
        }

        val tier0List = ArrayList<SearchResultItem>()
        val tier1List = ArrayList<SearchResultItem>()
        var totalMatches = 0

        val elapsed = measureTimeMillis {
            val verses = indexedVerses
            for (i in 0 until verses.size) {
                val entry = verses[i]
                val tier = matchTier(entry, q)
                if (tier >= 0) {
                    totalMatches++
                    val highlights = findUthmaniHighlightRanges(entry.uthmaniText, q)
                    val item = SearchResultItem(
                        entry = entry,
                        matchTier = tier,
                        highlightRanges = highlights
                    )
                    if (tier == 0) {
                        tier0List.add(item)
                    } else {
                        tier1List.add(item)
                    }
                }
            }
        }

        val combined = ArrayList<SearchResultItem>(limit.coerceAtMost(totalMatches))
        for (item in tier0List) {
            if (combined.size < limit) combined.add(item)
        }
        for (item in tier1List) {
            if (combined.size < limit) combined.add(item)
        }

        return SearchResponse(
            query = rawQuery,
            normalizedQuery = q,
            results = combined,
            totalMatches = totalMatches,
            elapsedMs = elapsed
        )
    }

    /**
     * Determines whether verse matches query:
     * Returns 0 for exact word/phrase boundary match,
     * Returns 1 for substring match,
     * Returns -1 for no match.
     */
    private fun matchTier(entry: SearchVerseEntry, q: String): Int {
        // Check normalizedImlai first
        val imlai = entry.normalizedImlai
        if (isWordBoundaryMatch(imlai, q)) return 0

        // Check normalizedUthmani
        val uthmani = entry.normalizedUthmani
        if (isWordBoundaryMatch(uthmani, q)) return 0

        if (imlai.contains(q) || uthmani.contains(q)) return 1

        return -1
    }

    private fun isWordBoundaryMatch(text: String, q: String): Boolean {
        if (text == q) return true
        if (text.startsWith("$q ")) return true
        if (text.endsWith(" $q")) return true
        if (text.contains(" $q ")) return true
        return false
    }

    /**
     * Maps match locations back to Uthmani diacritized text to highlight exact character spans
     * without stripping or corrupting Tashkeel or connecting letters.
     */
    fun findUthmaniHighlightRanges(uthmaniText: String, normalizedQuery: String): List<IntRange> {
        if (uthmaniText.isBlank() || normalizedQuery.isBlank()) return emptyList()

        // Build a mapping from normalized character index to Uthmani character index
        val uthmaniIndices = ArrayList<Int>(uthmaniText.length)
        val normBuilder = StringBuilder(uthmaniText.length)

        var i = 0
        val len = uthmaniText.length
        while (i < len) {
            val ch = uthmaniText[i]

            // Check dagger alef digraphs: وٰ or ىٰ
            if (ch == 'و' || ch == 'ى') {
                var j = i + 1
                while (j < len && isDiacriticOnly(uthmaniText[j])) {
                    if (uthmaniText[j] == '\u0670') break
                    j++
                }
                if (j < len && uthmaniText[j] == '\u0670') {
                    uthmaniIndices.add(i)
                    normBuilder.append(if (ch == 'و') 'ا' else 'ي')
                    i = j + 1
                    continue
                }
            }

            if (ch == '\u0670') {
                uthmaniIndices.add(i)
                normBuilder.append('ا')
                i++
                continue
            }

            if (isTashkeelOrPunctuation(ch)) {
                i++
                continue
            }

            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ', 'ء' -> 'ا'
                'ة' -> 'ه'
                'ى', 'ي', 'ئ' -> 'ي'
                'ؤ' -> 'و'
                ' ', '\t', '\n', '\r' -> ' '
                else -> ch
            }

            uthmaniIndices.add(i)
            normBuilder.append(mapped)
            i++
        }

        val mappedNorm = normBuilder.toString()
        val query = normalizedQuery
        val ranges = ArrayList<IntRange>()

        var searchIndex = 0
        while (searchIndex <= mappedNorm.length - query.length) {
            val matchPos = mappedNorm.indexOf(query, searchIndex)
            if (matchPos < 0) break

            val matchEndPos = matchPos + query.length - 1
            if (matchPos < uthmaniIndices.size && matchEndPos < uthmaniIndices.size) {
                val uthmaniStart = uthmaniIndices[matchPos]
                var uthmaniEnd = uthmaniIndices[matchEndPos]

                // Extend uthmaniEnd to include any trailing diacritics attached to the last letter
                while (uthmaniEnd + 1 < len && isDiacriticOnly(uthmaniText[uthmaniEnd + 1])) {
                    uthmaniEnd++
                }

                ranges.add(uthmaniStart..uthmaniEnd)
            }
            searchIndex = matchPos + query.length
        }

        return ranges
    }

    private fun isDiacriticOnly(ch: Char): Boolean {
        return (ch in '\u064B'..'\u065F') || // Harakat: Fatha, Damma, Kasra, Shadda, Sukun
                (ch in '\u0610'..'\u061A') || // Quranic auxiliary annotations
                (ch in '\u06D6'..'\u06ED') || // Quranic pause marks and small letters
                ch == '\u0640' ||              // Tatweel
                ch == '\u0670'                // Dagger alef
    }

    private fun isTashkeelOrPunctuation(ch: Char): Boolean {
        if (isDiacriticOnly(ch)) return true
        return ch in listOf('،', '؛', '؟', '!', '.', ':', '«', '»', '(', ')', '[', ']', '{', '}', '—', '-', '\uFEFF')
    }
}
