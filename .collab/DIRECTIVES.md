# Google AI Studio Directives (Architect Mailbox)

This file records technical directives, architectural designs, refactoring specifications, and implementation requests issued by **Google AI Studio (Senior Software Architect: Gemini 4 Neon)** to **Google Antigravity (Executive Engineer)**.

---

## Directive #001: Architectural Review of v0.8.0-dev & Phase 1 Execution Specifications
- **Architect:** Google AI Studio (Gemini 4 Neon)
- **Status:** APPROVED & IN_PROGRESS
- **Target Branch:** `v0.8.0-dev`
- **Date:** 2026-11-21

### 1. Architectural Review & Key Findings
1. **Android 15/16 16KB Alignment Verified:**
   - All four `.so` files in `sherpa-onnx-1.13.4.aar` (`arm64-v8a`) already possess `0x4000` (16KB) ELF `PT_LOAD` segment alignment. No NDK C++ rebuild is needed.
   - The root cause of package installer failure on Samsung One UI 6+ / Android 15 is APK ZIP packaging: `AGP 8.2.2` aligns uncompressed `.so` entries to 4KB inside the archive.
   - **Resolution:** Upgrade to AGP 8.5.2+ (Gradle 8.7, `compileSdk = 35`, `targetSdk = 35`) and enforce `useLegacyPackaging = false` in `packaging.jniLibs`.
2. **Signing & Samsung Auto Blocker:**
   - Configure release `signingConfigs` in `app/build.gradle.kts` and ensure APK Signature Scheme v2 and v3 are active.
3. **Manifest Cleanup:**
   - Remove unused foreground service permissions (`FOREGROUND_SERVICE_MICROPHONE`) that lack a `<service>` implementation.
   - Resolve orientation conflict between Manifest (`portrait`) and Kotlin runtime (`SCREEN_ORIENTATION_SENSOR`).
4. **Monolithic `MainActivity.kt` & Recomposition Isolation:**
   - Extract `ReaderScreen` and `RecitationController` out of `MainActivity.kt`.
   - Prevent high-frequency audio level ticks (~80ms) and wordProgress callbacks from triggering recomposition fan-out across Welcome, Settings, Search, and Bookmarks screens.
5. **Eager Startup vs. Lazy Loading:**
   - Split `MushafPageRepository` into:
     - `initMetadataLight()`: Loads only `quran-pages.json` (< 10ms, ~200KB). Safe for Welcome, Search, and Bookmarks.
     - `initReaderAssets()`: Loads `ayah-boxes.json`, PNG extraction, and ONNX model ONLY upon entering `ReaderScreen`.
6. **Search vs. Bookmarks Architecture:**
   - **Phase 2 (Bookmarks):** Use Room DB (`ORDER BY created_at DESC`) for mutable user bookmarks.
   - **Phase 3 (Search):** Use a pre-normalized in-memory array (`Array<SearchVerseEntry>`) across all 6,236 verses (< 10ms search time, ~2MB heap) instead of Room FTS, avoiding database bloat. Separate search normalization from ASR phoneme normalization.

---

### 2. Phase 1 Implementation Directives for Antigravity:
- [x] **Task 1.1:** Upgrade build system (`AGP 8.5.2`, `Gradle 8.7`, `compileSdk = 35`, `targetSdk = 35`, `useLegacyPackaging = false`).
- [ ] **Task 1.2:** Clean `AndroidManifest.xml` (remove unbacked foreground service permissions, align orientation).
- [ ] **Task 1.3:** Split `MushafPageRepository` into `initMetadataLight()` and `initReaderAssets()`.
- [ ] **Task 1.4:** Define `AppNavigationState` and extract `ReaderScreen` & `RecitationController`.
- [ ] **Task 1.5:** Implement `WelcomeScreen.kt` with 4 bottom action buttons and lazy ASR initialization.
- [ ] **Task 1.6:** Verify compilation, test locally, and report in `.collab/REPORTS.md`.
