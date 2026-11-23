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
- **Status:** COMPLETED (Verified & Published in Releases v0.7.5.3 and v0.7.5.4 [lite])
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

## Directive #003: Phase 3 Architectural Specifications — Offline Text & Voice Quran Search + 4-Gate Version-First Update Checker
- **Architect:** Google AI Studio
- **Status:** ACTIVE_DIRECTIVE (PM Approved — Option A + 4-Gate Update Pipeline + Versioned APK Naming)
- **Target Branch:** `v0.8.0-dev` (execute on `review/phase-3` for Architect audit before merge/release)
- **Target Version:** `0.7.6.0` (Code `94`) `[lite]`
- **Date:** 2026-11-23
- **In Response To:** Executive Report (`v0.7.5.4`), PM Phase 3 Scope Approval, and Update Verification Alignment

---

### 1. Architectural Decisions & Technical Rationale

#### Decision 3.1: Offline Text Search Engine — Dedicated Dual-Form Normalizer (DO NOT Reuse `QuranVocabularyFilter` / `FuzzyMatcher`)
- **Critical Audit Finding on `quran-pages.json`:**
  `cleanText` omits Uthmani dagger alefs (`\u0670` / `ٰ`), resulting in Uthmani orthography (`العلمين`, `الصرط`, `الكتب`, `الصلوه`, `السموت`) instead of keyboard Imla'i spelling (`العالمين`, `الصراط`, `الكتاب`, `الصلاة`, `السماوات`).
  Furthermore, `FuzzyMatcher.normalizeArabic()` expands Muqattaat (`الم` -> `الف لام ميم`) and `QuranVocabularyFilter` applies phonetic Tajweed rules (`نب` -> `مب`), which would break literal typed queries like `"الم"`, `"يس"`, `"طه"`, or `"من بعد"`.
- **Verdict:** Build a dedicated, zero-dependency in-memory engine **`com.mushafqiyam.search.QuranSearchEngine`** indexing all 6,236 verses from `MushafPageRepository.getAllVerses()` using **Dual-Form Normalization**:
  1. **`normalizedImlai`** (from `verse.text` with Uthmani dagger-alef expansion):
     - `و\u0670` (`وٰ`) -> `ا` | `ى\u0670` (`ىٰ`) -> `ي` | standalone `\u0670` -> `ا`
     - Strip Tashkeel (`\u0610-\u061A`, `\u064B-\u065F`, `\u06D6-\u06ED`), Tatweel (`\u0640`), BOM (`\uFEFF`), punctuation.
     - Unify: `أ إ آ ٱ` -> `ا` | `ة` -> `ه` | `ى` -> `ي` | `ؤ` -> `و` | `ئ` -> `ي`.
  2. **`normalizedUthmani`** (from `verse.cleanText` with the same stripping & unification, **without** dagger-alef expansion):
     - Preserves standard spellings where users don't type dagger alefs (`الرحمن`, `ذلك`, `هذا`, `لكن`, `اله`, `الله`, `طه`, `يس`, `الم`, `العلمين`).
- **Performance:** Lazy single-pass `Array<SearchVerseEntry>` built once on `Dispatchers.IO` (`< 35ms`, `@Volatile var isIndexed`), query execution `< 10ms`, word-boundary Tier 0 before substring Tier 1, Mushaf order (`globalIndex ASC`) within each tier, UI cap `limit = 150` with exact `totalMatches`.

#### Decision 3.2: Offline Voice Search (`البحث الصوتي في المصحف`) — Stateless Hybrid Phonetic Multi-Match Engine
- **Why Raw ASR Output MUST NOT Be Fed Into `QuranSearchEngine`:**
  `AudioRecognizer.projectPhonemesToCanonical()` emits a Tajweed-projected phonetic stream (`نب -> مب`, cross-word Idgham `ن ر -> ر`, collapsed Shaddah/Madd, unreliable spaces). Feeding it to typed text search fails on Tajweed-merged recitations.
- **Why `discoverVerseFromCharacterStream()` MUST NOT Be Called Directly:**
  Live tracking mutates recitation state (`confirmedVerseIndex`, `activeWordPointer`, `discoveryRollingChars`), returns only 1 winner, applies page/Fatihah bias, and skips common 4-grams (`matches.size <= 40`).
- **Verdict — Pure Read-Only Multi-Match Method in `QuranVocabularyFilter`:**
  Add `fun searchAllMatchingVerses(projectedText: String, limit: Int = 150): VoiceSearchResponse` reusing warmed-up `AudioRecognizer` + `verseCanonicalChars` + `fourGramToVerseIndices` in a **Two-Stage Hybrid Match**:
  1. **Stage A — Exact Phonetic Substring Scan (`< 3ms` across all 6,236 verses):**
     Compute `phoneticStream = toPhoneticCanonicalStream(projectedText.replace(" ", ""))` (min length `>= 5`). Scan all `verseCanonicalChars[i].contains(phoneticStream)` so **100% of repeated Quranic phrases** (e.g., `"فبأي آلاء ربكما تكذبان"` 31 hits) are found without 4-gram cap truncation.
  2. **Stage B — Isolated-DP Fuzzy Phonetic Fallback:**
     Query `fourGramToVerseIndices` with relaxed cap (`matches.size <= 300`, top 80 candidates by hit count) and score with a **local/isolated DP buffer** (NEVER touch live-tracking `dpPrevScore`/`dpCurrScore` arrays!). Accept candidates passing `score.toDouble() / stream.length >= 0.72 && density >= 0.70`, deduplicate against Stage A, sort by `(matchTier ASC, score DESC, globalIndex ASC)`.
- **Mandatory UX Invariants (PM Requirements):**
  1. **Strict No Auto-Jump Invariant:** NEVER navigate automatically to any verse — **even if `results.size == 1`**. Navigation happens **strictly** on explicit user tap.
  2. **Endpoint Result Freeze:** When `AudioRecognizer` hits `isEndpoint` (resetting stream), preserve the last non-empty voice results on screen; auto-stop mic after `3500ms` of post-speech silence (or Stop tap) while keeping results frozen for manual selection.
  3. **Strict Audio Routing & Lifecycle Isolation:** `MainActivity` routes `onPartialResult` exclusively to `SearchScreen` while `currentScreen == AppScreen.Search` (zero mutation of `PrayerSessionManager`, `activeSurah`, `activeAyah`, `currentPage`). Stop mic on result tap, Back press, manual text edit, mic toggle, or screen exit.

#### Decision 3.3: In-App Update Checker — 4-Gate Version-First Pipeline + Zero-Dependency `HttpURLConnection` (NO `REQUEST_INSTALL_PACKAGES`)
- **Offline-First & Zero-Bloat:** Built-in `HttpURLConnection` + `org.json.JSONObject` on `Dispatchers.IO`. Add `<uses-permission android:name="android.permission.INTERNET" />` ONLY to `AndroidManifest.xml` (strictly NO `REQUEST_INSTALL_PACKAGES`). Zero automatic network calls on launch; check runs **only** on user tap.
- **Installed Version Source of Truth:** Read actual installed APK version from `PackageManager.getPackageInfo(context.packageName, 0).versionName` (fallback `MainActivity.APP_VERSION`).
- **4-Gate Version-First Pipeline (`GET https://api.github.com/repos/kh-saadany/mushaf-qiyam/releases?per_page=10`):**
  1. **Gate 1 — Validity Filter:** Discard `draft == true`, `prerelease == true`, or releases with **zero** `.apk` assets.
  2. **Gate 2 — Highest Semantic Version Selection:** Parse `tag_name` of all remaining valid releases numerically and pick the release with the **strictly highest semantic version**.
  3. **Gate 3 — Strict Upgrade Gate (`remoteVersion > installedVersion`):**
     - If `compareSemanticVersions(remoteTag, installedVersion) <= 0` -> STOP immediately and return `UpdateCheckResult.UpToDate` (no download button/link shown).
     - ONLY if `compareSemanticVersions(remoteTag, installedVersion) > 0` -> proceed to Gate 4.
  4. **Gate 4 — Asset Selection Inside the Proven-Newer Release:**
     - Prefer the first asset ending with `-lite.apk` (case-insensitive); otherwise pick the first `.apk` asset (handles future `[full]`-only model/asset upgrades).
     - Launch `Intent(Intent.ACTION_VIEW, Uri.parse(url))` wrapped in `try/catch (ActivityNotFoundException)` for primary **"تحميل التحديث"** and secondary **"صفحة الإصدار"**.
- **Versioned APK CI Naming:** Update `.github/workflows/android-build.yml` to name release artifacts `mushaf-qiyam-v<VERSION>-lite.apk` / `mushaf-qiyam-v<VERSION>-full.apk` (e.g., `mushaf-qiyam-v0.7.6.0-lite.apk`).

---

### 2. Target Files & Concrete Deliverables

1. **`app/src/main/java/com/mushafqiyam/search/QuranSearchEngine.kt`:**
   - `SearchVerseEntry`, `SearchResultItem`, `SearchResponse`, `normalizeQuery`, `ensureIndexed`, `search`, and diacritic-safe `findUthmaniHighlightRanges`.
2. **`app/src/main/java/com/mushafqiyam/QuranVocabularyFilter.kt`:**
   - Add pure read-only `VoiceVerseMatch`, `VoiceSearchResponse`, `searchAllMatchingVerses(projectedText: String, limit: Int = 150)`, and stateless local-buffer `alignStreamToRefCompactStateless`.
3. **`app/src/main/java/com/mushafqiyam/ui/SearchScreen.kt` + `MainActivity.kt`:**
   - Unified Material 3 RTL Text + Voice Search UI: `OutlinedTextField` with `Search` leading icon, `Close` + `Mic/Stop` trailing icons, debounced text search (`120ms`), voice listening banner with live preview + Stop button + `3500ms` post-speech silence auto-stop with result freeze, `LazyColumn` results with Uthmani highlighting (text mode) and **strict NO auto-jump**.
   - `MainActivity.kt`: screen-aware `onPartialResult` routing, `permissionLauncher` screen-awareness (never start mic on `WelcomeScreen`), and `BackHandler` / `onSelectVerse` mic stop cleanup.
4. **`app/src/main/java/com/mushafqiyam/update/AppUpdateChecker.kt` + `ui/UpdateCheckerDialog.kt` + `PrayerSetupDialog.kt`:**
   - Implement `getInstalledVersionName`, `compareSemanticVersions`, pure testable `evaluateReleasesJson(jsonArrayBody, installedVersion)`, and `checkForUpdate(context)`.
   - Material 3 RTL `UpdateCheckerDialog` showing `Checking`, `UpToDate`, `UpdateAvailable` (`v$installedVersion ← ${release.tagName}`, release notes, size/type pill, Download & Release Page buttons), and `Error` (Retry + Releases fallback).
   - Integrate compact version + `"التحقق من وجود تحديث"` row into `PrayerSetupDialog.kt`.
5. **Manifest, Version Bump & CI:**
   - `AndroidManifest.xml`: add `INTERNET` only (NO `REQUEST_INSTALL_PACKAGES`).
   - Bump to `0.7.6.0` (Code `94`) across `app/build.gradle.kts`, `MainActivity.kt`, and `.github/workflows/android-build.yml`.
   - Update `.github/workflows/android-build.yml` packaging step to output `mushaf-qiyam-v0.7.6.0-lite.apk` / `mushaf-qiyam-v0.7.6.0-full.apk` (dynamically derived from `versionName`).

---

### 3. Verification & Acceptance Criteria
- `./gradlew compileDebugKotlin`, `./gradlew testDebugUnitTest`, and `./gradlew assembleRelease` pass with zero new dependencies.
- **Text Search Matrix:** `"الصراط المستقيم"` (`1:6` with clean highlight), `"الصلاة"` (`2:3`), `"السماوات"` (`2:255`), `"العالمين"` (`1:2`), `"الرحمن"` & `"ذلك الكتاب"`, `"الم"` (Muqattaat literal match).
- **Voice Search Matrix:** Repeated phrases return multiple verses; single-result voice search **never auto-navigates**; endpoint/3.5s silence stops mic while freezing results; zero mutation of tracking state/DP buffers; Back/typing/selection stops mic cleanly.
- **4-Gate Update Checker Unit Tests (`evaluateReleasesJson`):**
  1. Multi-release JSON (`v0.7.5.0` full listed first, `v0.7.6.0` lite listed second) -> picks `v0.7.6.0`.
  2. Installed `0.7.6.0` vs Remote `v0.7.6.0` -> `UpToDate`.
  3. Installed `0.7.6.0` vs Remote `v0.7.5.4` -> `UpToDate` (no downgrade).
  4. Remote `v0.7.7.0` with empty `assets: []` alongside `v0.7.6.0` with APK -> Gate 1 skips `v0.7.7.0`.
  5. Remote `v0.7.10.0` vs Installed `0.7.6.0` -> numeric `10 > 6`, selects `mushaf-qiyam-v0.7.10.0-lite.apk`.
