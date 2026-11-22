package com.mushafqiyam.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mushafqiyam.AppLogger

/**
 * BookmarksDatabaseHelper: Zero-dependency SQLite persistence for Mushaf Qiyam bookmarks.
 * Maintains zero APK bloat (no Room/KSP overhead) with high-speed indexed chronological queries.
 */
class BookmarksDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "mushaf_qiyam.db"
        const val DATABASE_VERSION = 1

        const val TABLE_BOOKMARKS = "bookmarks"
        const val COL_ID = "id"
        const val COL_SURAH = "surah"
        const val COL_AYAH = "ayah"
        const val COL_PAGE = "page"
        const val COL_AYAH_TEXT = "ayah_text"
        const val COL_SURAH_NAME = "surah_name"
        const val COL_CREATED_AT = "created_at"
        const val COL_NOTE = "note"

        private const val TAG = "BookmarksDb"
    }

    override fun onCreate(db: SQLiteDatabase) {
        AppLogger.i(TAG, "Creating bookmarks table and indexes...")
        val createTableSql = """
            CREATE TABLE $TABLE_BOOKMARKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SURAH INTEGER NOT NULL,
                $COL_AYAH INTEGER NOT NULL,
                $COL_PAGE INTEGER NOT NULL,
                $COL_AYAH_TEXT TEXT NOT NULL,
                $COL_SURAH_NAME TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_NOTE TEXT
            );
        """.trimIndent()
        db.execSQL(createTableSql)

        // Unique index on (surah, ayah) to enforce deduplication with UPSERT semantics
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_bookmarks_surah_ayah ON $TABLE_BOOKMARKS($COL_SURAH, $COL_AYAH);")

        // Chronological index (newest first)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_created_at ON $TABLE_BOOKMARKS($COL_CREATED_AT DESC);")
        AppLogger.i(TAG, "Bookmarks table created successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        AppLogger.i(TAG, "Upgrading database from $oldVersion to $newVersion")
    }
}
