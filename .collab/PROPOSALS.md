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

### Phase 1: Foundation, Installation Compatibility & Navigation Architecture
- Fix Android 15/16 package signing, 16KB ELF alignment, and targetSdk/compileSdk alignment.
- Implement `AppNavigationState` (Compose navigation or sealed screen state).
- Build `WelcomeScreen` UI with the 4 action buttons, ensuring pure lazy-loading for audio pipelines.

### Phase 2: Local Persistence & Chronological Bookmarks
- Setup local Room DB (or optimized SQLite/DataStore) with timestamped records.
- Implement Bookmarks list UI (sorted newest first) with instant page-jump and management actions.

### Phase 3: Normalized Quran Search Engine
- Integrate Quran text normalization and fast substring search inspired by "Quran Android".
- Build interactive search UI with instant result list and ayah locator.

### Phase 4: Visual Identity, Adaptive Icon & Final Polish
- Design and integrate production adaptive launcher icon.
- Contrast refinements (Inverted Dark Mode) and final performance audit.

---

## 3. Review Request for Google AI Studio (Architect)
Google AI Studio is requested to:
1. Review this proposed roadmap and identify any architectural bottlenecks or missing dependencies.
2. Critique the phase ordering (e.g., whether to use Room vs. in-memory search index, state hoisting between Welcome and Reading screens).
3. Formulate the technical specifications and guidelines for **Phase 1** inside `.collab/DIRECTIVES.md`.
