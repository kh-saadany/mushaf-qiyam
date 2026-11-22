package com.mushafqiyam.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.mushafqiyam.AppLogger
import com.mushafqiyam.MushafPageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * BookmarkRecord: Data model representing a saved user bookmark.
 */
data class BookmarkRecord(
    val id: Long = 0L,
    val surah: Int,
    val ayah: Int,
    val page: Int,
    val ayahText: String,
    val surahName: String,
    val createdAt: Long,
    val note: String? = null
)

/**
 * BookmarkRepository: Singleton repository providing reactive StateFlow access
 * to saved bookmarks with IO-dispatched asynchronous SQLite CRUD operations.
 */
object BookmarkRepository {

    private const val TAG = "BookmarkRepo"
    private const val PREFS_NAME = "mushaf_prefs"
    private const val KEY_MIGRATED_LEGACY = "migrated_legacy_bookmark_v1"

    private val _bookmarks = MutableStateFlow<List<BookmarkRecord>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkRecord>> = _bookmarks.asStateFlow()

    @Volatile
    private var dbHelper: BookmarksDatabaseHelper? = null

    private fun getDbHelper(context: Context): BookmarksDatabaseHelper {
        return dbHelper ?: synchronized(this) {
            dbHelper ?: BookmarksDatabaseHelper(context.applicationContext).also { dbHelper = it }
        }
    }

    /**
     * Loads all bookmarks ordered newest-first (ORDER BY created_at DESC) into the StateFlow.
     * Also executes the one-time legacy migration from SharedPreferences if not already done.
     */
    suspend fun loadAll(context: Context): List<BookmarkRecord> = withContext(Dispatchers.IO) {
        val helper = getDbHelper(context)
        migrateLegacyBookmarkIfNeeded(context, helper)

        val list = ArrayList<BookmarkRecord>()
        val db = helper.readableDatabase
        val query = """
            SELECT ${BookmarksDatabaseHelper.COL_ID},
                   ${BookmarksDatabaseHelper.COL_SURAH},
                   ${BookmarksDatabaseHelper.COL_AYAH},
                   ${BookmarksDatabaseHelper.COL_PAGE},
                   ${BookmarksDatabaseHelper.COL_AYAH_TEXT},
                   ${BookmarksDatabaseHelper.COL_SURAH_NAME},
                   ${BookmarksDatabaseHelper.COL_CREATED_AT},
                   ${BookmarksDatabaseHelper.COL_NOTE}
            FROM ${BookmarksDatabaseHelper.TABLE_BOOKMARKS}
            ORDER BY ${BookmarksDatabaseHelper.COL_CREATED_AT} DESC
        """.trimIndent()

        try {
            db.rawQuery(query, null).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_ID)
                val surahIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_SURAH)
                val ayahIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_AYAH)
                val pageIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_PAGE)
                val textIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_AYAH_TEXT)
                val nameIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_SURAH_NAME)
                val createdIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_CREATED_AT)
                val noteIdx = cursor.getColumnIndexOrThrow(BookmarksDatabaseHelper.COL_NOTE)

                while (cursor.moveToNext()) {
                    list.add(
                        BookmarkRecord(
                            id = cursor.getLong(idIdx),
                            surah = cursor.getInt(surahIdx),
                            ayah = cursor.getInt(ayahIdx),
                            page = cursor.getInt(pageIdx),
                            ayahText = cursor.getString(textIdx) ?: "",
                            surahName = cursor.getString(nameIdx) ?: "",
                            createdAt = cursor.getLong(createdIdx),
                            note = if (cursor.isNull(noteIdx)) null else cursor.getString(noteIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error querying bookmarks: ${e.message}")
        }

        _bookmarks.value = list
        AppLogger.i(TAG, "Loaded ${list.size} bookmarks from database.")
        list
    }

    /**
     * Adds or updates a bookmark for (surah, ayah) with UPSERT semantics (promotes to top via created_at).
     */
    suspend fun addOrUpdateBookmark(
        context: Context,
        page: Int,
        surah: Int,
        ayah: Int,
        note: String? = null
    ): BookmarkRecord = withContext(Dispatchers.IO) {
        val helper = getDbHelper(context)
        val now = System.currentTimeMillis()

        // Resolve Surah name and Ayah text
        val surahName = MushafPageRepository.getSurahName(surah)
        val ayahText = MushafPageRepository.getVerse(surah, ayah)?.text
            ?: MushafPageRepository.getPageVerses(page).firstOrNull { it.surah == surah && it.ayah == ayah }?.text
            ?: MushafPageRepository.getPageVerses(page).firstOrNull()?.text
            ?: ""

        val values = ContentValues().apply {
            put(BookmarksDatabaseHelper.COL_SURAH, surah)
            put(BookmarksDatabaseHelper.COL_AYAH, ayah)
            put(BookmarksDatabaseHelper.COL_PAGE, page)
            put(BookmarksDatabaseHelper.COL_AYAH_TEXT, ayahText)
            put(BookmarksDatabaseHelper.COL_SURAH_NAME, surahName)
            put(BookmarksDatabaseHelper.COL_CREATED_AT, now)
            if (note != null) {
                put(BookmarksDatabaseHelper.COL_NOTE, note)
            } else {
                putNull(BookmarksDatabaseHelper.COL_NOTE)
            }
        }

        val db = helper.writableDatabase
        val id = db.insertWithOnConflict(
            BookmarksDatabaseHelper.TABLE_BOOKMARKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )

        AppLogger.i(TAG, "Saved bookmark id=$id for $surahName ayah=$ayah page=$page")
        loadAll(context)

        BookmarkRecord(
            id = id,
            surah = surah,
            ayah = ayah,
            page = page,
            ayahText = ayahText,
            surahName = surahName,
            createdAt = now,
            note = note
        )
    }

    /**
     * Deletes a bookmark by its primary key ID.
     */
    suspend fun deleteBookmark(context: Context, id: Long): Boolean = withContext(Dispatchers.IO) {
        val helper = getDbHelper(context)
        val db = helper.writableDatabase
        val deletedRows = db.delete(
            BookmarksDatabaseHelper.TABLE_BOOKMARKS,
            "${BookmarksDatabaseHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
        AppLogger.i(TAG, "Deleted bookmark id=$id (rows affected: $deletedRows)")
        loadAll(context)
        deletedRows > 0
    }

    /**
     * Restores a deleted bookmark (used by Snackbar Undo action).
     */
    suspend fun restoreBookmark(context: Context, record: BookmarkRecord): Long = withContext(Dispatchers.IO) {
        val helper = getDbHelper(context)
        val values = ContentValues().apply {
            if (record.id > 0) {
                put(BookmarksDatabaseHelper.COL_ID, record.id)
            }
            put(BookmarksDatabaseHelper.COL_SURAH, record.surah)
            put(BookmarksDatabaseHelper.COL_AYAH, record.ayah)
            put(BookmarksDatabaseHelper.COL_PAGE, record.page)
            put(BookmarksDatabaseHelper.COL_AYAH_TEXT, record.ayahText)
            put(BookmarksDatabaseHelper.COL_SURAH_NAME, record.surahName)
            put(BookmarksDatabaseHelper.COL_CREATED_AT, record.createdAt)
            if (record.note != null) {
                put(BookmarksDatabaseHelper.COL_NOTE, record.note)
            } else {
                putNull(BookmarksDatabaseHelper.COL_NOTE)
            }
        }

        val db = helper.writableDatabase
        val newId = db.insertWithOnConflict(
            BookmarksDatabaseHelper.TABLE_BOOKMARKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        AppLogger.i(TAG, "Restored bookmark id=$newId for ${record.surahName} ayah=${record.ayah}")
        loadAll(context)
        newId
    }

    /**
     * One-time legacy migration from SharedPreferences (last_saved_*) to SQLite database.
     */
    private fun migrateLegacyBookmarkIfNeeded(context: Context, helper: BookmarksDatabaseHelper) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isMigrated = prefs.getBoolean(KEY_MIGRATED_LEGACY, false)
        if (isMigrated) return

        try {
            val lastPage = prefs.getInt("last_saved_page", 1)
            val lastSurah = prefs.getInt("last_saved_surah", 1)
            val lastAyah = prefs.getInt("last_saved_ayah", 1)

            if (lastSurah > 1 || lastPage > 1 || lastAyah > 1) {
                // Seed initial bookmark
                val surahName = MushafPageRepository.getSurahName(lastSurah)
                val ayahText = MushafPageRepository.getVerse(lastSurah, lastAyah)?.text
                    ?: MushafPageRepository.getPageVerses(lastPage).firstOrNull()?.text
                    ?: ""

                val values = ContentValues().apply {
                    put(BookmarksDatabaseHelper.COL_SURAH, lastSurah)
                    put(BookmarksDatabaseHelper.COL_AYAH, lastAyah)
                    put(BookmarksDatabaseHelper.COL_PAGE, lastPage)
                    put(BookmarksDatabaseHelper.COL_AYAH_TEXT, ayahText)
                    put(BookmarksDatabaseHelper.COL_SURAH_NAME, surahName)
                    put(BookmarksDatabaseHelper.COL_CREATED_AT, System.currentTimeMillis())
                    put(BookmarksDatabaseHelper.COL_NOTE, "موضع القراءة السابق")
                }

                val db = helper.writableDatabase
                db.insertWithOnConflict(
                    BookmarksDatabaseHelper.TABLE_BOOKMARKS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                AppLogger.i(TAG, "Migrated legacy bookmark: Surah $lastSurah Ayah $lastAyah Page $lastPage")
            }

            prefs.edit().putBoolean(KEY_MIGRATED_LEGACY, true).apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to migrate legacy bookmark: ${e.message}")
        }
    }
}
