# Google AI Studio Directives (Architect Mailbox)

This file records technical directives, architectural designs, refactoring specifications, and implementation requests issued by **Google AI Studio (Senior Software Architect)** to **Google Antigravity (Executive Engineer)**.

---

## Directive #001: Architectural Review of v0.8.0-dev & Phase 1 Execution Specifications
- **Architect:** Google AI Studio
- **Status:** COMPLETED (Verified in Executive Report #002, v0.7.5.1 / v0.7.5.2)
- **Target Branch:** `v0.8.0-dev`
- **Date:** 2026-11-21

### Summary of Completed Decisions & Tasks
- Verified `sherpa-onnx-1.13.4.aar` `.so` binaries already possess `0x4000` (16KB) ELF `PT_LOAD` alignment; resolved Android 15/16 packaging via AGP `8.5.2`, Gradle `8.7`, `compileSdk = 35`, `targetSdk = 35`, `useLegacyPackaging = false`, and CI `zipalign -p -f 16` + V1/V2/V3 signing.
- Split `MushafPageRepository` into `initMetadataLight()` and `initReaderAssets()`.
- Implemented `AppScreen` navigation state, `WelcomeScreen.kt` (with 1-2-1 button layout and `verticalScroll`), placeholder `BookmarksScreen.kt` / `SearchScreen.kt`, and lazy `AudioRecognizer` / ONNX initialization.

---

## Directive #002: Phase 2 Architectural Specifications — Chronological Bookmarks, Reader Init Modal & Lifecycle Race Fixes
- **Architect:** Google AI Studio
- **Status:** ACTIVE_DIRECTIVE
- **Target Branch:** `v0.8.0-dev`
- **Date:** 2026-11-22
- **In Response To:** `.collab/PROPOSALS.md` (Proposal #002) & `.collab/REPORTS.md` (Executive Report #002)

---

### 1. Architect Decisions on `.collab/PROPOSALS.md` Section 4.3

#### Decision 1: Persistence Framework — Zero-Dependency `SQLiteOpenHelper` (NOT Room + KSP)
- **Verdict:** Use a lightweight, zero-dependency **`SQLiteOpenHelper`** (`data/BookmarksDatabaseHelper.kt` + `data/BookmarkRepository.kt`) backed by `MutableStateFlow<List<BookmarkRecord>>` on `Dispatchers.IO`. Do **not** add Room or KSP in `app/build.gradle.kts`.
- **Architectural Rationale:**
  1. **Zero APK Bloat:** Keeps `[lite]` patch APKs compact (avoids ~1.5–2.0 MB Room/SQLite wrapper overhead).
  2. **Zero Build/CI Risk:** Avoids introducing KSP plugin version coupling (`Kotlin 1.9.22` / `AGP 8.5.2`) for a single-table schema.
  3. **Quran Android Parity:** Mirrors Quran Android's proven `BookmarksDaoImpl` / SQLite handler pattern with direct indexed queries (`ORDER BY created_at DESC`) and reactive `StateFlow` emission to Compose.

#### Decision 2: Bookmark Creation Trigger — Combined Long-Press (Option A) + Dedicated Bar Action (Option B)
- **Verdict:** Implement **Option A + Option B together**, and explicitly **reject Option C** (exit confirmation prompt).
- **Architectural Rationale:**
  1. **No Exit Friction (Why Not Option C):** An imam or worshipper finishing prayer or pressing Back must exit cleanly without a blocking modal. Automatic stop-position continuity (`mushaf_prefs` `"موضع القراءة الأخير"`) already preserves where recitation stopped without cluttering user bookmarks.
  2. **Ayah-Level Precision (Option A):** Add `onLongPress` inside `MushafPageViewer.kt`'s `detectTapGestures` (`HapticFeedbackType.LongPress` + `MushafPageRepository.findTappedAyah`) to save/update a bookmark for the exact tapped `(surah, ayah, page)`.
  3. **1-Tap Discoverability (Option B):** Add a dedicated **`BookmarkAdd` (`Icons.Outlined.BookmarkAdd` / `Icons.Filled.Bookmark`)** button in the Reader bar (both portrait bottom bar and landscape left rail) that bookmarks the current `(currentPage, activeSurah, activeAyah)`.
  4. **Icon Disambiguation:** In `MainActivity.kt` (landscape rail, currently using `Icons.Outlined.Bookmarks` for `"السور"`), change the Surah/Page Jump picker icon to `Icons.Outlined.MenuBook` so the Bookmark icon unambiguously represents saving a bookmark.

---

### 2. Critical Phase 1 Audit Findings to Fix in Phase 2

During review of `MainActivity.kt` (commit `3f815e9`), two lifecycle/state race conditions were identified when transitioning from `WelcomeScreen`, `PrayerSetupDialog`, or `BookmarksScreen` into `AppScreen.Reading`:

1. **Premature `startListening()` Race (`MainActivity.kt:826–837` & `870–875`):**
   - `PrayerSetupDialog.onStartPrayer` switches `currentScreen = AppScreen.Reading` and calls `audioRecognizer?.startListening()` *synchronously* before `LaunchedEffect(currentScreen)` has created `audioRecognizer` or run `initEngine("tilawa_model")`.
   - Fix: Introduce a `pendingStartListening: Boolean` state + `@Volatile var isEngineInitialized: Boolean` on `AudioRecognizer` (and `@Volatile var isInitialized: Boolean` on `QuranVocabularyFilter`). Only invoke `startListening()` inside the single engine-initialization coroutine **after** `initEngine()` succeeds.
2. **Discovery-Mode Overwrite on Prayer/Bookmark Jump (`MainActivity.kt:241`):**
   - `LaunchedEffect(currentScreen)` unconditionally calls `QuranVocabularyFilter.enterDiscoveryMode("Initial Startup")` whenever `currentScreen == AppScreen.Reading`, which overwrites the `PrayerSessionManager` page-1/Fatihah lock OR the explicit bookmark target selected in `BookmarksScreen`.
   - Also, re-entering `AppScreen.Reading` after pressing Back currently re-runs `QuranVocabularyFilter.initializeFullMushaf(...)` and `audioRecognizer?.initEngine(...)` from scratch.
   - Fix: Guard `QuranVocabularyFilter.initializeFullMushaf(...)` and `audioRecognizer.initEngine(...)` with idempotency checks (run heavy init once per app process), and introduce a `ReaderEntryTarget` (e.g., `AutoDiscovery`, `SpecificVerse(page, surah, ayah)`, or `PrayerSession`) so entering from a Bookmark locks tracking directly onto that `(surah, ayah)` instead of resetting to `DISCOVERY`.

---

### 3. Concrete Technical Specifications for Phase 2 Deliverables

#### Deliverable 2.1: Chronological Bookmarks Persistence (`com.mushafqiyam.data`)
Create `BookmarksDatabaseHelper.kt` and `BookmarkRepository.kt` (singleton/object or Activity-scoped holder initialized lazily on `Dispatchers.IO` or via `InitLight`):

1. **SQLite Schema (`mushaf_qiyam.db`, version `1`):**
   ```sql
   CREATE TABLE bookmarks (
       id INTEGER PRIMARY KEY AUTOINCREMENT,
       surah INTEGER NOT NULL,
       ayah INTEGER NOT NULL,
       page INTEGER NOT NULL,
       ayah_text TEXT NOT NULL,
       surah_name TEXT NOT NULL,
       created_at INTEGER NOT NULL,
       note TEXT
   );
   CREATE UNIQUE INDEX idx_bookmarks_surah_ayah ON bookmarks(surah, ayah);
   CREATE INDEX idx_bookmarks_created_at ON bookmarks(created_at DESC);
   ```
   - **Upsert Semantics (Quran Android Pattern):** Using a unique index on `(surah, ayah)` with `INSERT OR REPLACE` (or `ON CONFLICT(surah, ayah) DO UPDATE SET page=excluded.page, created_at=excluded.created_at, note=COALESCE(excluded.note, bookmarks.note)`) prevents duplicate clutter if a user re-bookmarks the same verse while promoting it to the top (`created_at DESC`).
   - **Auto-Resume Separation:** Keep `"موضع القراءة الأخير"` in `mushaf_prefs` (`last_saved_*`) for automatic recitation continuity. On first DB initialization (`migrated_legacy_bookmark_v1 == false`), if `last_saved_surah > 1 || last_saved_page > 1`, seed one initial bookmark into `bookmarks` so existing users keep their place.

2. **`BookmarkRecord` & `BookmarkRepository` API:**
   ```kotlin
   data class BookmarkRecord(
       val id: Long,
       val surah: Int,
       val ayah: Int,
       val page: Int,
       val ayahText: String,
       val surahName: String,
       val createdAt: Long,
       val note: String? = null
   )
   ```
   - Expose `val bookmarks: StateFlow<List<BookmarkRecord>>`.
   - Suspend/IO-dispatched methods:
     - `suspend fun loadAll(context: Context)`
     - `suspend fun addOrUpdateBookmark(context: Context, page: Int, surah: Int, ayah: Int, note: String? = null): BookmarkRecord` (resolves `surahName` and `ayahText` via `MushafPageRepository.getVerse(surah, ayah)`, falling back to `getPageVerses(page).firstOrNull()`).
     - `suspend fun deleteBookmark(context: Context, id: Long)`
     - `suspend fun restoreBookmark(context: Context, record: BookmarkRecord)` (for Undo Snackbar).

#### Deliverable 2.2: Interactive `BookmarksScreen.kt`
Upgrade `ui/BookmarksScreen.kt` from Phase 1 placeholder to full Material 3 chronological list:
- **Top Section:** Keep/refine the **"موضع القراءة الأخير" (Auto-Resume)** quick card at the top if `savedBookmark` exists, clearly visually separated from the user's saved chronological bookmarks list below (`"المرجعيات المحفوظة"`).
- **Chronological `LazyColumn` (`key = { it.id }`):**
  - Sorted newest-first (`ORDER BY created_at DESC`).
  - Each card shows:
    - Gold bookmark icon (`#B45309`).
    - Surah name + Ayah number (`سورة ... — آية ...`) and page pill (`ص ...`).
    - 1–2 line Uthmani `ayahText` preview (`maxLines = 2`, `TextOverflow.Ellipsis`).
    - Formatted Arabic relative timestamp (`"الآن"`, `"اليوم hh:mm ص/م"`, `"أمس"`, `"منذ X أيام"`, or short date).
    - Delete icon button (`Icons.Outlined.DeleteOutline`) triggering deletion with a `SnackbarHost` **"تراجع" (Undo)** action calling `restoreBookmark(...)`.
  - Clicking a bookmark card navigates to `AppScreen.Reading` at that exact `(page, surah, ayah)`, sets `QuranVocabularyFilter.resetPointerToSurahAyah(surah, ayah)`, sets `isDiscoveryMode = false`, and updates `currentPage` / `activeSurah` / `activeAyah`.
- **Empty State:** Clean centered Islamic icon + `"لا توجد مرجعيات محفوظة بعد"` + helper hint (`"اضغط مطولاً على أي آية أثناء القراءة أو استخدم زر الحفظ لإضافتها هنا"`).

#### Deliverable 2.3: Interactive Initialization Modal & `AudioRecognizer` Readiness Flag
1. **`AudioRecognizer.kt`:**
   - Expose `val isEngineInitialized: Boolean get() = recognizer != null`.
   - Guard `initEngine(...)` so if `recognizer != null`, it returns `true` immediately without re-extracting or recreating native C++ `OnlineRecognizer`.
2. **`QuranVocabularyFilter.kt`:**
   - Ensure `initializeFullMushaf(...)` short-circuits immediately when `isFullMushafLoaded` is already `true` (avoiding redundant 6,236-verse indexing on `Reader` re-entry).
3. **Initialization Loading Dialog in `MainActivity.kt` / `ReaderScreen`:**
   - Maintain `var isInitializingReaderEngine by remember { mutableStateOf(false) }` and `var initStepText by remember { mutableStateOf("جاري تهيئة المصحف ومحرك الصوت...") }`.
   - When entering `AppScreen.Reading`, if `!MushafPageRepository.isReaderAssetsInitialized || !(audioRecognizer?.isEngineInitialized == true)`:
     - Show a non-dismissible lightweight Compose `Dialog` (`"جاري تهيئة النظام..."`, `CircularProgressIndicator(color = Color(0xFF0F5132))`, observing `initStepText` / `engineStatus`).
     - Run `initReaderAssets`, `initializeFullMushaf`, and `initEngine` on `Dispatchers.IO`.
     - Apply the target entry mode (`Prayer` vs `SpecificVerse` vs `Discovery`), start listening if `pendingStartListening && hasMicPermission`, and **set `isInitializingReaderEngine = false` immediately** upon completion/listening start.

#### Deliverable 2.4: `WelcomeScreen.kt` Verification & Polish
- Align the secondary row in `WelcomeScreen.kt` so that "البحث" is on the right and "المرجعيات" is on the left in RTL, with 1-2-1 hierarchy and verticalScroll preserved.

---

### 4. Verification & Acceptance Criteria for Antigravity

Before reporting Phase 2 complete in `.collab/REPORTS.md`:
1. `./gradlew compileDebugKotlin` and `./gradlew assembleRelease` pass with zero errors and zero new third-party DB dependencies.
2. Verify chronological ordering (`ORDER BY created_at DESC`), upsert deduplication on `(surah, ayah)`, Undo deletion, and one-time legacy `SharedPreferences` migration.
3. Verify cold-start transition from `WelcomeScreen` ("بدء التلاوة") and `BookmarksScreen` (bookmark tap) and `PrayerSetupDialog` ("بدء الصلاة"):
   - Loading modal appears cleanly during first-time ONNX/assets init and disappears immediately once mic/reader is ready.
   - Re-entering `Reading` a second time is instant (no re-init modal, `isEngineInitialized == true`).
   - Jumping from a saved bookmark starts tracking on that exact `(page, surah, ayah)` without being overwritten by `enterDiscoveryMode("Initial Startup")`.
4. Confirm zero `AudioRecord` / inference threads exist while sitting on `WelcomeScreen` or `BookmarksScreen`.


---

## Directive #004: Isolated Crash Hardening & Native Diagnostics Hotfix (`v0.7.5.5 [lite]`)
- **Architect:** Google AI Studio (Gemini 4 Neon)
- **Status:** ACTIVE_HOTFIX_DIRECTIVE (PM Approved — Execute BEFORE merging `review/phase-3`)
- **Target Branch:** `v0.8.0-dev` (branch strictly from `d2c488e` / `v0.7.5.4` — **DO NOT** include Phase 3 Search/UpdateChecker features in this hotfix!)
- **Target Version:** `0.7.5.5` (Build Code `94`) `[lite]`
- **Note on `review/phase-3`:** Keep `review/phase-3` untouched for now; once `v0.7.5.5` is field-verified on Samsung Android 14, we will rebase `review/phase-3` onto `v0.7.5.5`, apply the 5 Phase 3 audit fixes, and release `v0.7.6.0` (Code `95`).

---

### 1. Objective
Eliminate silent process crashes on first spoken word (`v0.7.5.4` on Android 14 / Samsung One UI) and guarantee 100% diagnostic visibility for **both** Kotlin/Java exceptions AND Native C++/JNI crashes (`SIGSEGV`/`SIGABRT`).

---

### 2. Required Deliverables on `v0.8.0-dev`

#### Deliverable H1: Dual-Layer Crash & Native Exit Reporter (`app/src/main/java/com/mushafqiyam/CrashHandler.kt` + `MainActivity.kt`)
1. **Java/Kotlin Uncaught Exception Handler:**
   - Install in `MainActivity.onCreate()` via `Thread.setDefaultUncaughtExceptionHandler` (chaining safely to previous handler after synchronous write).
   - Synchronously persist (`commit()`) stack trace, thread name, device/Android SDK/ABI, `APP_VERSION`, and last 4,000 chars of `AppLogger` to `filesDir/latest_crash.txt` + `SharedPreferences`.
   - Provide `CrashHandler.recordException(tag: String, t: Throwable)` for caught runtime errors.
2. **MANDATORY Native C++ Crash Capture (`ApplicationExitInfo` — Android 11+ / API 30+):**
   - Query `(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(context.packageName, 0, 3)`.
   - Inspect the most recent entry whose `timestamp > lastShownExitTimestamp` and whose `reason` is one of:
     `REASON_CRASH_NATIVE`, `REASON_CRASH`, `REASON_ANR`, `REASON_LOW_MEMORY`, or `REASON_SIGNALED`.
   - Extract: human-readable `reason`, `status` (POSIX signal e.g. `SIGSEGV=11`, `SIGABRT=6`), `description`, `pss`/`rss` KB, `timestamp`, and up to first 4KB of `traceInputStream` (safely closed via `.use {}`).
   - Store it as the pending crash report (if no newer Kotlin crash exists) and save `lastShownExitTimestamp = entry.timestamp` in `SharedPreferences` so it displays once.
3. **Post-Crash AlertDialog in `MainActivity.kt`:**
   - If a pending crash report (Kotlin OR Native `ApplicationExitInfo`) exists on app startup, show an RTL `AlertDialog` displaying a scrollable preview with a 1-tap **"نسخ تقرير العطل"** button (copies full report to clipboard + Toast) and **"إغلاق"** (clears pending flag).

#### Deliverable H2: `AudioRecognizer.kt` JNI & Silence-Buffer Hardening
1. **Prevent Unbounded C++ Fbank Accumulation During Silence (CRITICAL):**
   - Initialize `lastSpeechTimestampMs = System.currentTimeMillis()` inside `startListening()`.
   - Track a silence gate flag (`var streamClearedForSilence = false`) in `captureThread`:
     - Always compute `rms` on every chunk (`640` samples). If `rms > 0.005f`, update `lastSpeechTimestampMs = now` and set `streamClearedForSilence = false`.
     - While `now - lastSpeechTimestampMs <= 2000L`: feed `acceptWaveform(samplesToFeed, SAMPLE_RATE)` under `synchronized(streamLock)`.
     - Once `now - lastSpeechTimestampMs > 2000L` (prolonged silence): **STOP calling `acceptWaveform(...)`** so C++ Kaldi Fbank does NOT accumulate thousands of silence frames while `inferenceThread` sleeps! Reset the stream **once**.
2. **Eliminate JNI Use-After-Free (`streamLock` Discipline):**
   - In `inferenceThread`, `captureThread`, `resetStream()`, `stopListening()`, and `release()`: read `val stream = currentStream` and `val rec = recognizer` **STRICTLY INSIDE** `synchronized(streamLock)`.
   - NEVER hold `streamLock` across `Thread.sleep(...)`, `projectPhonemesToCanonical(...)`, or `mainHandler.post { ... }`.
   - Zero-GC partial read guard: `val samplesToFeed = if (readSamples == floatSamples.size) floatSamples else floatSamples.copyOf(readSamples)`.
3. **Atomic Asset Copy & Model Integrity Logging (`resolveFilePath`):**
   - Copy to `File(internalFile.parentFile, "$fileName.tmp")` first, then `tmpFile.renameTo(internalFile)` atomically.
   - Log exact byte sizes of `model.int8.onnx` and `tokens.txt` during `initEngine()`, and abort init safely if `model.int8.onnx` is `< 10 * 1024 * 1024` bytes.
4. **Main-Thread Callback Shield:**
   - Wrap `onPartialResult?.invoke(projected)` and `onAudioLevel?.invoke(level)` inside `mainHandler.post { try { ... } catch (t: Throwable) { CrashHandler.recordException(context, TAG, "...", t) } }`.

#### Deliverable H3: Tracker, UI & Packaging Hardening
1. **`MainActivity.kt`:** Wrap `handlePartialResult(rawText)` body in top-level `try { ... } catch (t: Throwable) { CrashHandler.recordException(context, "MainActivity", "...", t) }`.
2. **`QuranVocabularyFilter.kt`:**
   - Wrap `filterAndTrackFullMushaf` in `try/catch(Throwable)` returning a safe empty `TrackingResult` on unexpected failure.
   - Add strict bounds checks on `verseCanonicalChars.indices`, `contStart in 0 until allWords.size && contEnd >= contStart` before `allWords.subList`, and `gPos in allWords.indices`.
3. **`app/build.gradle.kts`:**
   - Add `androidResources { noCompress += listOf("onnx", "int8", "txt", "bin", "json") }`.
   - Keep `jniLibs { useLegacyPackaging = false }`.
4. **Version & CI Alignment (`v0.7.5.5` — Code `94` `[lite]`):**
   - `app/build.gradle.kts`: `versionCode = 94`, `versionName = "0.7.5.5"`
   - `MainActivity.kt`: `APP_VERSION = "0.7.5.5"`
   - `package.json`: `"version": "1.0.5"`
   - `.github/workflows/android-build.yml`:
     - `tag: "v0.7.5.5"`
     - `name: "مصحف القيام — V0.7.5.5 (Audio JNI Hardening & Crash Diagnostics Hotfix)"`
     - Package artifact with versioned filename: `mushaf-qiyam-v${VERSION}-lite.apk`.
