
- When committing changes to the Mushaf Qiyam project, you MUST ask the user if they want to build a [full] version, a [lite] version, or [both]. Add the chosen keyword to the git commit message (e.g. `git commit -m "[lite] fixed bugs"`).
- BEFORE committing, you MUST increment the version number in both `package.json` and `App.js` (the `APP_VERSION` variable) based on the user's choice:
  - If building a `[full]` version: Increment the MINOR version by 1 (e.g., `1.0.0` -> `1.1.0`).
  - If building a `[lite]` version: Increment the PATCH version by 1 (e.g., `1.0.0` -> `1.0.1`).
- BEFORE committing, you MUST invoke the `mushaf_code_reviewer` subagent in parallel with a `Consistency Audit Specialist` subagent to perform:
  1. **Code Accuracy & Syntax Review**: Verifying Kotlin syntax, Compose imports, dependencies, and thread-safety in modified files.
  2. **Cross-System Consistency Audit**: Auditing and verifying 100% exact alignment across `versionCode`, `versionName` (`app/build.gradle.kts`), `APP_VERSION` (`MainActivity.kt`), GitHub secrets names (`KEYSTORE_BASE64`, etc.), `abiFilters`, and release tags (`.github/workflows/android-build.yml`).
  3. You MUST NOT commit or push until BOTH subagents issue explicit `PASS` reports.
- **CRITICAL MODEL BUNDLING RULE**: NEVER download or bundle heavy AI model files (such as FastConformer `model.int8.onnx` or Whisper `encoder.int8.onnx` / `decoder.int8.onnx`) into the APK build unless the user explicitly requests a `[full]` build. All patch/lite builds MUST remain ultra-compact (~15MB) and rely strictly on models already installed locally on the user's device.

- When proposing the use of a third-party library or an undocumented API property, you MUST verify its existence and exact usage by searching the web or reading its source code/documentation. Do NOT rely on assumptions or hallucinate API properties.
 
## Rigorous Pre-Execution Review (مراجعة صارمة قبل التنفيذ)
قبل البدء في التنفيذ الآلي لأي خطة أو حل برمجي مقترح، **يجب عليك أولاً:**
1. مراجعة الخطة نقدياً من جميع الجوانب الهندسية المختلفة.
2. التأكد من منطقيتها وقابليتها للتنفيذ الفعلي (Feasibility) في ضوء الإمكانيات المتاحة للبيئة المستهدفة (مثال: موارد التابلت أو الهاتف، سعة الذاكرة RAM، توافر الإنترنت، قدرات المعالج).
3. استخدام منهجية الأمر `/teamwork-preview` لإنشاء مسودة متطلبات واضحة ومقاييس قبول (Acceptance Criteria) موضوعية تكشف أي ثغرات أو تناقضات في الخطة قبل كتابة سطر كود واحد.
4. عدم الشروع في التنفيذ إلا بعد الحصول على موافقة صريحة من المستخدم على هذا التحليل النقدي.