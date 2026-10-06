package com.mushafqiyam

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * MushafPageRepository: Manages the 604 Mushaf pages, 6,236 verses,
 * 13,766 normalized ayah highlight bounding boxes, and local device storage
 * of page images (extracted on [full] install, reused on [lite] updates).
 */
object MushafPageRepository {

    private const val TAG = "MushafRepo"
    private const val REF_WIDTH = 1200f
    private const val REF_HEIGHT = 1941f
    private const val PAGES_DIR_NAME = "mushaf_pages"
    private const val EXTRACTION_MARKER = ".extracted_v1"

    private const val CLEAN_BASMALAH_PREFIX = "بسم الله الرحمن الرحيم "
    private const val UTHMANI_BASMALAH_PREFIX = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ "

    data class MushafVerse(
        val page: Int,
        val surah: Int,
        val surahName: String,
        val ayah: Int,
        val text: String,
        val cleanText: String,
        val globalIndex: Int
    )

    data class AyahBoundingBox(
        val surah: Int,
        val ayah: Int,
        val normLeft: Float,
        val normTop: Float,
        val normRight: Float,
        val normBottom: Float
    )

    data class SurahInfo(
        val number: Int,
        val name: String,
        val startPage: Int,
        val verseCount: Int
    )

    @Volatile
    var isInitialized: Boolean = false
        private set

    private val pagesVerses = HashMap<Int, List<MushafVerse>>(604)
    private val pagesBoxes = HashMap<Int, List<AyahBoundingBox>>(604)
    private val allVersesList = ArrayList<MushafVerse>(6236)
    private val verseKeyToGlobalIndex = HashMap<Int, Int>(6236)
    private val surahInfoList = ArrayList<SurahInfo>(114)

    // Keep at most 3 decoded page bitmaps in memory to guarantee low RAM usage
    private val bitmapCache = LruCache<Int, ImageBitmap>(3)

    @Synchronized
    fun initRepository(context: Context, onProgress: ((String) -> Unit)? = null): Boolean {
        try {
            if (!isInitialized) {
                onProgress?.invoke("⏳ جاري تحميل بيانات صفحات المصحف وإحداثيات التظليل...")
                loadJsonMetadata(context)
                isInitialized = true
            }
            extractBundledPagesIfNeeded(context, onProgress)
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize MushafPageRepository: ${e.message}")
            return false
        }
    }

    private fun loadJsonMetadata(context: Context) {
        pagesVerses.clear()
        pagesBoxes.clear()
        allVersesList.clear()
        verseKeyToGlobalIndex.clear()
        surahInfoList.clear()

        // 1. Load quran-pages.json
        val pagesJsonStr = context.assets.open("quran-pages.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val pagesArray = JSONArray(pagesJsonStr)

        val surahStartPage = HashMap<Int, Int>()
        val surahNames = HashMap<Int, String>()
        val surahCounts = HashMap<Int, Int>()

        var globalIdx = 0
        for (i in 0 until pagesArray.length()) {
            val pageObj = pagesArray.getJSONObject(i)
            val pageNum = pageObj.getInt("page")
            val versesArr = pageObj.getJSONArray("verses")
            val verseList = ArrayList<MushafVerse>(versesArr.length())

            for (j in 0 until versesArr.length()) {
                val vObj = versesArr.getJSONObject(j)
                val surah = vObj.getInt("surah")
                val surahName = vObj.getString("surahName").replace("\uFEFF", "").trim()
                val ayah = vObj.getInt("ayah")
                var text = vObj.getString("text").replace("\uFEFF", "").trim()
                var cleanText = vObj.getString("cleanText").replace("\uFEFF", "").trim()

                // Strip prepended Basmalah on surahs 2..114 ayah 1 so verse tokens match the Mushaf highlight boxes
                if (surah > 1 && ayah == 1) {
                    if (cleanText.startsWith(CLEAN_BASMALAH_PREFIX)) {
                        cleanText = cleanText.substring(CLEAN_BASMALAH_PREFIX.length).trim()
                    }
                    if (text.startsWith(UTHMANI_BASMALAH_PREFIX)) {
                        text = text.substring(UTHMANI_BASMALAH_PREFIX.length).trim()
                    }
                }

                val currentGlobalIdx = globalIdx++
                val mv = MushafVerse(
                    page = pageNum,
                    surah = surah,
                    surahName = surahName,
                    ayah = ayah,
                    text = text,
                    cleanText = cleanText,
                    globalIndex = currentGlobalIdx
                )
                verseList.add(mv)
                allVersesList.add(mv)
                verseKeyToGlobalIndex[surah * 1000 + ayah] = currentGlobalIdx

                if (!surahStartPage.containsKey(surah)) {
                    surahStartPage[surah] = pageNum
                    surahNames[surah] = surahName
                }
                val currentMax = surahCounts[surah] ?: 0
                if (ayah > currentMax) {
                    surahCounts[surah] = ayah
                }
            }
            pagesVerses[pageNum] = verseList
        }

        for (s in 1..114) {
            surahInfoList.add(
                SurahInfo(
                    number = s,
                    name = surahNames[s] ?: "سورة $s",
                    startPage = surahStartPage[s] ?: 1,
                    verseCount = surahCounts[s] ?: 0
                )
            )
        }

        // 2. Load ayah-boxes.json
        val boxesJsonStr = context.assets.open("ayah-boxes.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val boxesRoot = JSONObject(boxesJsonStr)
        val keys = boxesRoot.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val pageNum = key.toIntOrNull() ?: continue
            val arr = boxesRoot.getJSONArray(key)
            val boxList = ArrayList<AyahBoundingBox>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.getJSONArray(i)
                val surah = item.getInt(0)
                val ayah = item.getInt(1)
                val minX = item.getInt(2)
                val minY = item.getInt(3)
                val maxX = item.getInt(4)
                val maxY = item.getInt(5)

                boxList.add(
                    AyahBoundingBox(
                        surah = surah,
                        ayah = ayah,
                        normLeft = (minX / REF_WIDTH).coerceIn(0f, 1f),
                        normTop = (minY / REF_HEIGHT).coerceIn(0f, 1f),
                        normRight = (maxX / REF_WIDTH).coerceIn(0f, 1f),
                        normBottom = (maxY / REF_HEIGHT).coerceIn(0f, 1f)
                    )
                )
            }
            pagesBoxes[pageNum] = boxList
        }

        AppLogger.i(TAG, "Loaded ${pagesVerses.size} pages, ${allVersesList.size} verses, and ${pagesBoxes.values.sumOf { it.size }} highlight boxes.")
    }

    private fun extractBundledPagesIfNeeded(context: Context, onProgress: ((String) -> Unit)?) {
        val bundledList = try {
            context.assets.list(PAGES_DIR_NAME)?.filter { it.endsWith(".png") } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val targetDir = File(context.filesDir, PAGES_DIR_NAME)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val markerFile = File(targetDir, EXTRACTION_MARKER)
        val lastPageFile = File(targetDir, "604.png")

        if (bundledList.size >= 600) {
            val alreadyExtracted = markerFile.exists() && lastPageFile.exists() && lastPageFile.length() > 1000L
            if (!alreadyExtracted) {
                AppLogger.i(TAG, "Detected bundled Mushaf pages (${bundledList.size} files) in APK. Extracting to device storage...")
                val buffer = ByteArray(32 * 1024)
                var count = 0
                for (fileName in bundledList.sorted()) {
                    val outFile = File(targetDir, fileName)
                    context.assets.open("$PAGES_DIR_NAME/$fileName").use { input ->
                        FileOutputStream(outFile).use { output ->
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    count++
                    if (count % 50 == 0 || count == bundledList.size) {
                        onProgress?.invoke("⏳ جاري حفظ صفحات المصحف على الجهاز ($count / ${bundledList.size})...")
                    }
                }
                markerFile.writeText("${bundledList.size}")
                bitmapCache.evictAll()
                AppLogger.i(TAG, "Successfully extracted $count Mushaf pages to ${targetDir.absolutePath}")
            } else {
                AppLogger.i(TAG, "Mushaf pages already extracted on device (${targetDir.absolutePath}).")
            }
        } else {
            val localCount = targetDir.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0
            AppLogger.i(TAG, "Lite build mode: Found $localCount locally stored Mushaf pages in ${targetDir.absolutePath}")
        }
    }

    fun getAvailableLocalPageCount(context: Context): Int {
        val targetDir = File(context.filesDir, PAGES_DIR_NAME)
        return targetDir.listFiles { f -> f.name.endsWith(".png") }?.size ?: 0
    }

    fun loadPageBitmap(context: Context, pageNumber: Int): ImageBitmap? {
        val p = pageNumber.coerceIn(1, 604)
        synchronized(bitmapCache) {
            bitmapCache.get(p)?.let { return it }
        }

        val fileName = String.format(Locale.US, "%03d.png", p)
        val localFile = File(File(context.filesDir, PAGES_DIR_NAME), fileName)

        val bitmap = try {
            if (localFile.exists() && localFile.length() > 0L) {
                FileInputStream(localFile).use { BitmapFactory.decodeStream(it) }
            } else {
                context.assets.open("$PAGES_DIR_NAME/$fileName").use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        }

        return bitmap?.asImageBitmap()?.also { imgBmp ->
            synchronized(bitmapCache) {
                bitmapCache.put(p, imgBmp)
            }
        }
    }

    fun getAllVerses(): List<MushafVerse> = allVersesList

    fun getVerseByGlobalIndex(globalIndex: Int): MushafVerse? {
        return allVersesList.getOrNull(globalIndex)
    }

    fun getGlobalVerseIndex(surah: Int, ayah: Int): Int {
        return verseKeyToGlobalIndex[surah * 1000 + ayah] ?: 0
    }

    fun getVerse(surah: Int, ayah: Int): MushafVerse? {
        val idx = verseKeyToGlobalIndex[surah * 1000 + ayah] ?: return null
        return allVersesList.getOrNull(idx)
    }

    fun getPageVerses(pageNumber: Int): List<MushafVerse> {
        return pagesVerses[pageNumber.coerceIn(1, 604)] ?: emptyList()
    }

    fun getPageBoxes(pageNumber: Int): List<AyahBoundingBox> {
        return pagesBoxes[pageNumber.coerceIn(1, 604)] ?: emptyList()
    }

    fun getAyahBoxesOnPage(pageNumber: Int, surah: Int, ayah: Int): List<AyahBoundingBox> {
        return getPageBoxes(pageNumber).filter { it.surah == surah && it.ayah == ayah }
    }

    fun getSurahList(): List<SurahInfo> = surahInfoList

    fun getSurahName(surahNumber: Int): String {
        return surahInfoList.firstOrNull { it.number == surahNumber }?.name ?: "سورة $surahNumber"
    }

    fun getPageHeaderTitle(pageNumber: Int): String {
        val verses = getPageVerses(pageNumber)
        if (verses.isEmpty()) return "صفحة $pageNumber"
        val distinctSurahs = verses.map { it.surahName }.distinct()
        return distinctSurahs.joinToString(" • ")
    }

    fun findTappedAyah(pageNumber: Int, normX: Float, normY: Float): Pair<Int, Int>? {
        val boxes = getPageBoxes(pageNumber)
        if (boxes.isEmpty()) return null

        val directHit = boxes.firstOrNull { b ->
            normX in b.normLeft..b.normRight && normY in b.normTop..b.normBottom
        }
        if (directHit != null) {
            return Pair(directHit.surah, directHit.ayah)
        }

        val sameLineBoxes = boxes.filter { b ->
            normY in (b.normTop - 0.015f)..(b.normBottom + 0.015f)
        }
        if (sameLineBoxes.isNotEmpty()) {
            val closestHoriz = sameLineBoxes.minByOrNull { b ->
                when {
                    normX < b.normLeft -> b.normLeft - normX
                    normX > b.normRight -> normX - b.normRight
                    else -> 0f
                }
            }
            if (closestHoriz != null) {
                return Pair(closestHoriz.surah, closestHoriz.ayah)
            }
        }

        return null
    }
}
