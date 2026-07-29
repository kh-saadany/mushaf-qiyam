
- When committing changes to the Mushaf Qiyam project, you MUST ask the user if they want to build a [full] version, a [lite] version, or [both]. Add the chosen keyword to the git commit message (e.g. `git commit -m "[lite] fixed bugs"`).
- BEFORE committing, you MUST increment the version number in both `package.json` and `App.js` (the `APP_VERSION` variable) based on the user's choice:
  - If building a `[full]` version: Increment the MINOR version by 1 (e.g., `1.0.0` -> `1.1.0`).
  - If building a `[lite]` version: Increment the PATCH version by 1 (e.g., `1.0.0` -> `1.0.1`).
- BEFORE committing, you MUST invoke the `mushaf_code_reviewer` subagent in parallel with a `Consistency Audit Specialist` subagent to perform:
  1. **Code Accuracy & Syntax Review**: Verifying Kotlin syntax, Compose imports, dependencies, and thread-safety in modified files.
  2. **Cross-System Consistency Audit**: Auditing and verifying 100% exact alignment across `versionCode`, `versionName` (`app/build.gradle.kts`), `APP_VERSION` (`MainActivity.kt`), GitHub secrets names (`KEYSTORE_BASE64`, etc.), `abiFilters`, and release tags (`.github/workflows/android-build.yml`).
  3. You MUST NOT commit or push until BOTH subagents issue explicit `PASS` reports.

-   W h e n   p r o p o s i n g   t h e   u s e   o f   a   t h i r d - p a r t y   l i b r a r y   o r   a n   u n d o c u m e n t e d   A P I   p r o p e r t y ,   y o u   M U S T   v e r i f y   i t s   e x i s t e n c e   a n d   e x a c t   u s a g e   b y   s e a r c h i n g   t h e   w e b   o r   r e a d i n g   i t s   s o u r c e   c o d e / d o c u m e n t a t i o n .   D o   N O T   r e l y   o n   a s s u m p t i o n s   o r   h a l l u c i n a t e   A P I   p r o p e r t i e s .  
 
## Rigorous Pre-Execution Review (مراجعة صارمة قبل التنفيذ)
قبل البدء في التنفيذ الآلي لأي خطة أو حل برمجي مقترح، **يجب عليك أولاً:**
1. مراجعة الخطة نقدياً من جميع الجوانب الهندسية المختلفة.
2. التأكد من منطقيتها وقابليتها للتنفيذ الفعلي (Feasibility) في ضوء الإمكانيات المتاحة للبيئة المستهدفة (مثال: موارد التابلت أو الهاتف، سعة الذاكرة RAM، توافر الإنترنت، قدرات المعالج).
3. استخدام منهجية الأمر `/teamwork-preview` لإنشاء مسودة متطلبات واضحة ومقاييس قبول (Acceptance Criteria) موضوعية تكشف أي ثغرات أو تناقضات في الخطة قبل كتابة سطر كود واحد.
4. عدم الشروع في التنفيذ إلا بعد الحصول على موافقة صريحة من المستخدم على هذا التحليل النقدي.