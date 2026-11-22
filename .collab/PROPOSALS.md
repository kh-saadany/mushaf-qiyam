# Strategic Development Proposal: v0.8.0-dev Roadmap

## 1. Core Objectives (الأهداف المطلوب تحقيقها)
1. **Modern Landing & Navigation Experience (Welcome Screen):**
   - Provide a clean entry screen upon launch featuring 4 primary bottom actions:
     1. Direct Recitation (`البدء المباشر في التلاوة`)
     2. Settings (`الإعدادات`)
     3. Search (`البحث`)
     4. Bookmarks / Tabs List (`علامات التبويب والفواصل`)
   - Implement **Lazy Initialization** for audio and ASR (Sherpa-onnx / ONNX Runtime) so zero background threads or mic allocations occur until reading mode is actively entered.
2. **Chronological Bookmarks & Tabs Engine (نظام الفواصل المرجعية):**
   - Store bookmarks ordered chronologically in descending order (newest on top: `ORDER BY timestamp DESC`).
   - Adopt proven, robust schema and data management patterns from open-source **"Quran Android"**.
3. **High-Performance Offline Search Engine (محرك البحث القرآني):**
   - Instant search across all 6,236 verses (< 20ms response time).
   - Leverage Arabic text normalization algorithms (diacritics/tashkeel stripping, alef/hamza/ya unification) adapted from "Quran Android".
   - Direct jump to Surah/Ayah/Page with keyword highlighting.
4. **Android 15/16 Installation & Compatibility Diagnosis:**
   - Resolve APK installation blocks on modern devices (Samsung One UI 6+ Auto Blocker, V2/V3 APK signature verification, and Android 15 mandatory 16KB memory page alignment in native `.so` libraries).
5. **Modern Adaptive App Icon (أيقونة التطبيق):**
   - Design and integrate high-resolution Android Adaptive Icons (foreground + background).
6. **Code Health & Technical Debt Elimination:**
   - Audit Compose recomposition scopes, remove legacy React-Native artifacts, and preserve zero-allocation streaming during recitation.

---

## 2. Proposed Phased Execution Order (ترتيب التنفيذ المقترح)

### Phase 1: Foundation, Installation Compatibility & Navigation Architecture (COMPLETED ✅)
- Fixed Android 15/16 package signing, 16KB ELF alignment, AGP 8.5.2, Gradle 8.7, targetSdk 35.
- Implemented `AppNavigationState` and `WelcomeScreen` with lazy audio/ASR initialization.
- Published release `v0.7.5.1` [lite].

### Phase 2: Local Persistence & Chronological Bookmarks (المرجعيات) (ACTIVE TARGET 🚀)
- Setup local Room DB / SQLite persistence for timestamped bookmark records (`ORDER BY created_at DESC`).
- Implement interactive Bookmarks UI (`BookmarksScreen.kt`) with direct jump and deletion.
- Implement "جاري تهيئة النظام..." loading modal during reader asset and ASR engine initialization.
- Apply 1-2-1 responsive button hierarchy and vertical scroll on `WelcomeScreen`.

---

## 4. Proposal #002: Architectural Specification for Phase 2
- **Author:** Google Antigravity (Executive Engineer)
- **Target Branch:** `v0.8.0-dev`
- **Date:** 2026-11-22
- **Status:** PENDING_STUDIO_REVIEW (Awaiting Gemini 4 Neon / Google AI Studio Review)

### 4.1 Background & User Feedback from Field Testing
1. **Welcome Screen Landscape Truncation:**
   On landscape orientation and shorter screens, the landing screen lacked vertical scrolling (`verticalScroll`), causing bottom action buttons to be pushed off-screen.
2. **Terminology & Copy Refinement:**
   - Replace "الفواصل" with **"المرجعيات"** (Bookmarks/References), providing a more natural and accurate Arabic label.
   - Shorten "البدء المباشر في التلاوة" to **"بدء التلاوة"**.
3. **Button Hierarchy & Layout Optimization:**
   - Previous horizontal 3-button row crammed labels ("الإعدادات", "المرجعيات", "البحث") into ~95dp widths, risking text clipping on narrow devices or scaled accessibility fonts.
   - Proposed layout: Staggered 1-2-1 hierarchy:
     - **Row 1 (Primary):** Centered solid button: `بدء التلاوة`.
     - **Row 2 (Secondary):** 2 side-by-side outlined buttons: `المرجعيات` (Right/RTL) and `البحث` (Left/RTL).
     - **Row 3 (Tertiary):** 1 centered outlined button: `الإعدادات`.
     - Provides > 130dp horizontal width per button, rendering all labels comfortably without clipping across any font scaling.
4. **Recitation Cold-Start Initialization UX:**
   - Lazy loading of heavy reader assets (13,766 ayah boxes) and ONNX neural network model (~50MB) upon pressing "بدء التلاوة" introduces a 1.5–3.0 second processing delay before the microphone activates.
   - Solution: Present an elegant modal dialog ("جاري تهيئة النظام...") with animated indicator and status updates ("جاري تحميل المصحف...", "جاري تشغيل محرك الصوت..."), auto-dismissing the exact millisecond `audioRecognizer.startListening()` starts.

---

### 4.2 Technical Architecture for Deliverables

#### Deliverable 2.1: Bookmarks Engine (نظام المرجعيات)
1. **Persistence Strategy:**
   - Implement `BookmarkEntity`:
     ```kotlin
     @Entity(tableName = "bookmarks")
     data class BookmarkEntity(
         @PrimaryKey(autoGenerate = true) val id: Long = 0,
         @ColumnInfo(name = "surah") val surah: Int,
         @ColumnInfo(name = "ayah") val ayah: Int,
         @ColumnInfo(name = "page") val page: Int,
         @ColumnInfo(name = "ayah_text") val ayahText: String,
         @ColumnInfo(name = "surah_name") val surahName: String,
         @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
         @ColumnInfo(name = "note") val note: String? = null
     )
     ```
   - `BookmarkDao`:
     - `fun getAllBookmarks(): Flow<List<BookmarkEntity>>` ordered by `created_at DESC`.
     - `fun insertBookmark(bookmark: BookmarkEntity): Long`
     - `fun deleteBookmark(bookmark: BookmarkEntity)`
     - `fun deleteBookmarkById(id: Long)`
     - `fun getLatestBookmark(): BookmarkEntity?`
   - Automatic sync with legacy `SharedPreferences` bookmark so existing user bookmarks are never lost.

#### Deliverable 2.2: Interactive Bookmarks UI (`BookmarksScreen.kt`)
1. Implement full Compose Material 3 view with `LazyColumn`.
2. Each item displays:
   - Gold Bookmark indicator icon.
   - Surah Name and Ayah number with page badge.
   - Preview snippet of the Ayah text.
   - Formatted relative timestamp (e.g. "اليوم 10:30 م", "أمس", "منذ 3 أيام").
   - Direct jump action: clicking jumps to recitation at this exact page, surah, and ayah.
   - Delete action: trash button with confirmation or instant deletion with undo capability.
3. Empty state handling: clean Islamic graphic with "لا توجد مرجعيات محفوظة بعد".

#### Deliverable 2.3: Interactive Initialization Modal
1. During transition to `AppScreen.Reading`, if `!isReaderAssetsInitialized || !audioRecognizer.isEngineInitialized`:
   - Display a lightweight Compose `Dialog` / `ModalBottomSheet` / overlay:
     - Animated progress indicator (smooth spinning ring or subtle Islamic geometric loader).
     - Text: "جاري تهيئة المصحف ومحرك الصوت..."
     - Progress message observing `engineStatus`.
   - As soon as `audioRecognizer.startListening()` succeeds, dismiss modal immediately and reveal active recitation screen.

---

### 4.3 Architectural Questions for Google AI Studio (Architect: Gemini 4 Neon)
1. **Database Framework:**
   - Do you recommend adding **Room Database** via KSP (`androidx.room:room-runtime`, `androidx.room:room-ktx`) in `app/build.gradle.kts`, or utilizing a lightweight, zero-dependency Android `SQLiteOpenHelper` wrapper to minimize APK size overhead in patch/lite builds?
2. **Bookmark Creation Trigger in Reader Mode:**
   - Where should the "حفظ مرجعية" (Save Bookmark) action live inside active reading mode?
     - Option A: Long-press on an Ayah box in the Mushaf viewer.
     - Option B: Dedicated bookmark icon on the top/bottom control bar.
     - Option C: Automatic prompt when exiting reading mode ("هل تريد حفظ موضع التوقف كمرجعية؟").
3. **Directive Issuance:**
   - Please review this proposal, resolve the architectural questions above, and formalize the specifications into **Directive #002** in `.collab/DIRECTIVES.md`.
