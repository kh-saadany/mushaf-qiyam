package com.mushafqiyam.search

import com.mushafqiyam.MushafPageRepository
import org.junit.Assert.*
import org.junit.Test

class QuranSearchEngineTest {

    @Test
    fun testNormalizeQuery_stripsTashkeelAndUnifiesLetters() {
        assertEquals("الصراط المستقيم", QuranSearchEngine.normalizeQuery("الصِّرَاطَ المُسْتَقِيمَ"))
        assertEquals("الم", QuranSearchEngine.normalizeQuery("الٓمٓ"))
        assertEquals("طه", QuranSearchEngine.normalizeQuery("طٰهٰ"))
        assertEquals("يس", QuranSearchEngine.normalizeQuery("يـٰسٓ"))
        assertEquals("قل هو الله احد", QuranSearchEngine.normalizeQuery("قُلْ هُوَ ٱللَّهُ أَحَدٌ"))
    }

    @Test
    fun testNormalizeImlaiFromUthmani_expandsDaggerAlefsAndDigraphs() {
        // Dagger alef expansion (ٱلصِّرَٰطَ -> الصراط, ٱلْعَٰلَمِينَ -> العالمين)
        val fatihah = "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ"
        val normalizedFatihah = QuranSearchEngine.normalizeImlaiFromUthmani(fatihah)
        assertEquals("اهدنا الصراط المستقيم", normalizedFatihah)

        val alemeen = "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ"
        val normalizedAlemeen = QuranSearchEngine.normalizeImlaiFromUthmani(alemeen)
        assertEquals("الحمد لله رب العالمين", normalizedAlemeen)

        // وٰ -> ا (الصلاة, الزكاة, الحياة)
        val salah = "يُقِيمُونَ ٱلصَّلَوٰةَ وَيُؤْتُونَ ٱلزَّكَوٰةَ"
        val normalizedSalah = QuranSearchEngine.normalizeImlaiFromUthmani(salah)
        assertEquals("يقيمون الصلاة ويوتون الزكاة", normalizedSalah)

        // ىٰ -> ي (موسىٰ, هدىٰ)
        val musa = "وَإِذْ ءَاتَيْنَا مُوسَى ٱلْكِتَٰبَ"
        val normalizedMusa = QuranSearchEngine.normalizeImlaiFromUthmani(musa)
        assertEquals("واذ اتينا موسي الكتاب", normalizedMusa)
    }

    @Test
    fun testNormalizeUthmaniClean_preservesStandardUthmaniWords() {
        assertEquals("الرحمن", QuranSearchEngine.normalizeUthmaniClean("الرحمن"))
        assertEquals("ذلك", QuranSearchEngine.normalizeUthmaniClean("ذلك"))
        assertEquals("هذا", QuranSearchEngine.normalizeUthmaniClean("هذا"))
        assertEquals("الم", QuranSearchEngine.normalizeUthmaniClean("الم"))
    }

    @Test
    fun testFindUthmaniHighlightRanges_accuratelyLocatesSpans() {
        val uthmani = "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ"
        val query = "الصراط"
        val ranges = QuranSearchEngine.findUthmaniHighlightRanges(uthmani, query)

        assertEquals(1, ranges.size)
        val range = ranges[0]
        val highlighted = uthmani.substring(range)
        // Should contain ٱلصِّرَٰطَ with all diacritics intact
        assertTrue(highlighted.startsWith("ٱلصِّ"))
        assertTrue(highlighted.endsWith("طَ"))
    }

    @Test
    fun testSearch_indexesAndRetrievesVersesWithDualForm() {
        val testVerses = listOf(
            MushafPageRepository.MushafVerse(
                globalIndex = 0,
                surah = 1,
                ayah = 1,
                page = 1,
                text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
                cleanText = "بسم الله الرحمن الرحيم",
                surahName = "الفاتحة"
            ),
            MushafPageRepository.MushafVerse(
                globalIndex = 5,
                surah = 1,
                ayah = 6,
                page = 1,
                text = "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ",
                cleanText = "اهدنا الصرط المستقيم",
                surahName = "الفاتحة"
            ),
            MushafPageRepository.MushafVerse(
                globalIndex = 7,
                surah = 2,
                ayah = 1,
                page = 2,
                text = "الٓمٓ",
                cleanText = "الم",
                surahName = "البقرة"
            ),
            MushafPageRepository.MushafVerse(
                globalIndex = 9,
                surah = 2,
                ayah = 3,
                page = 2,
                text = "ٱلَّذِينَ يُؤْمِنُونَ بِٱلْغَيْبِ وَيُقِيمُونَ ٱلصَّلَوٰةَ",
                cleanText = "الذين يؤمنون بالغيب ويقيمون الصلوة",
                surahName = "البقرة"
            )
        )

        QuranSearchEngine.ensureIndexed(testVerses)

        // Query 1: "الصراط" (Standard typed Imla'i spelling should match "الصرط" in cleanText via normalizedImlai)
        val res1 = QuranSearchEngine.search("الصراط")
        assertEquals(1, res1.totalMatches)
        assertEquals(6, res1.results[0].entry.ayah)
        assertEquals(0, res1.results[0].matchTier) // Word boundary

        // Query 2: "الصلاة" (Standard typed Imla'i spelling should match "الصلوة")
        val res2 = QuranSearchEngine.search("الصلاة")
        assertEquals(1, res2.totalMatches)
        assertEquals(3, res2.results[0].entry.ayah)

        // Query 3: "الم" (Literal Muqattaat search)
        val res3 = QuranSearchEngine.search("الم")
        assertEquals(1, res3.totalMatches)
        assertEquals(1, res3.results[0].entry.ayah)

        // Query 4: "الرحمن" (Standard Uthmani word without dagger alef)
        val res4 = QuranSearchEngine.search("الرحمن")
        assertEquals(1, res4.totalMatches)
        assertEquals(1, res4.results[0].entry.ayah)
    }
}
