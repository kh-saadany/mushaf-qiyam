---
name: update-argon
description: >-
  Use this skill whenever the user says "تحديث معلومات آرجون" or asks to update Argon's information by generating a comprehensive markdown snapshot of all Kotlin source files and copying it directly to the Windows clipboard.
---

# مهارة تحديث معلومات آرجون (Update Argon Info)

هذه المهارة مخصصة للاستدعاء اليدوي فقط عندما يكتب المستخدم عبارة:
**"تحديث معلومات آرجون"**

## خطوات التنفيذ التلقائية:

1. **توليد ملف Markdown الشامل (`argon_snapshot.md`):**
   تشغيل السكريبت التالي لجمع محتوى كافة ملفات `.kt` في `app/src/main/java` مع نتيجة `git status` وترتيب الملفات وعنونتها بوضوح:
   ```powershell
   python scripts/generate_argon_snapshot.py
   ```

2. **نسخ الملف النصي إلى حافظة ويندوز (Windows Clipboard كـ File Drop List):**
   تشغيل أمر PowerShell التالي لوضع مسار الملف في الحافظة كملف مهيأ للصق المباشر في المتصفح:
   ```powershell
   Set-Clipboard -Path (Resolve-Path "argon_snapshot.md").Path
   ```

3. **إظهار رسالة تأكيد مختصرة للمستخدم:**
   إظهار رسالة تأكيد تفيد بأن ملف `argon_snapshot.md` النصي أصبح جاهزاً في الحافظة الآن، وأنه يمكنه التوجه فوراً لنافذة المتصفح والضغط على `Ctrl + V` لرفع ملف الماركداون النصي المقروء مباشرة دون الحاجة لتصفح المجلدات.
