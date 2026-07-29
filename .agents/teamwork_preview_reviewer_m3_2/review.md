# تقرير المراجعة المعمارية - Mushaf Qiyam Speech Recognition

## Review Summary (ملخص المراجعة)

**Verdict**: REQUEST_CHANGES (FAIL)

---

## Findings (النتائج والملاحظات)

### [Critical] Finding 1: تسريب مرجع JNI العالمي وتصميم المثيل الأحادي المقيد (JNI Global Reference Leak & Singleton Bottleneck)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 357-360، 366-370، 449-473)
- **المشكلة**:
  1. تم الإعلان عن مؤشر عالمي واحد للمحرك `g_bridge` في نطاق الملف (file-level global pointer):
     ```cpp
     namespace {
         mushafqiyam::SpeechRecognizerBridge* g_bridge = nullptr;
     }
     ```
     هذا يقيد المحرك بمثيل أحادي (Singleton) غير مرن.
  2. في فئة Kotlin، يتم تعريف `SpeechRecognizerBridge` كفئة عادية يمكن للمطور إنشاء مثيلات متعددة منها. إذا تم إنشاء مثيل ثانٍ، فإن دالة `nativeInitialize` ستقوم بالكتابة فوق المؤشر العالمي وتعيين مرجع عالمي جديد لـ `listener_global_ref_` دون حذف أو تحرير المرجع العالمي للمثيل الأول (`env->DeleteGlobalRef(listener_global_ref_)`).
- **الأثر**: تسريب مرجع JNI العالمي يؤدي إلى بقاء كائنات Java (مثل الأنشطة Activities أو ViewModels المرتبطة بالـ Listener) معلقة في الذاكرة للأبد، مما يسبب تسريباً حاداً للذاكرة (Memory Leak) وانهيار التطبيق لاحقاً بسبب نفاد المراجع العالمية.
- **التوصية**: تجنب استخدام المؤشرات العالمية المشتركة في C++. بدلاً من ذلك، قم بتخزين عنوان مؤشر كائن C++ داخل حقل في كائن Kotlin (مثال: `private var nativePtr: Long = 0`) وتمريره مع كل استدعاء JNI لضمان عزل دورة حياة كل مثيل بشكل كامل وآمن.

---

### [Critical] Finding 2: خطر الإشارة التائهة وانهيار التطبيق بسبب دورة حياة الذاكرة المشتركة (Dangling Pointer in Direct ByteBuffer Registration)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 475-492)
- **المشكلة**:
  تقوم دالة `registerDirectBuffer` بالاحتفاظ بالمؤشر المباشر للذاكرة `direct_buffer_ptr_` المأخوذ من `env->GetDirectBufferAddress(direct_buffer)`. ومع ذلك، **لا يقوم الكود بإنشاء مرجع عالمي (Global Reference)** لكائن الـ `ByteBuffer` في طرف C++.
- **الأثر**: إذا قام الـ JVM بتجميع المهملات (Garbage Collection) لكائن الـ `ByteBuffer` في طرف Kotlin (لأنه لم يعد محالاً إليه أو تم إعادة تخصيصه)، فسيتم تحرير الذاكرة الأصلية المخصصة له. سيصبح `direct_buffer_ptr_` إشارة تائهة (Dangling Pointer). عند استدعاء `nativeEnqueueFrame` لاحقاً، سيقوم المحرك بالقراءة من ذاكرة محررّة، مما يؤدي إلى انهيار فوري للتطبيق (Segmentation Fault).
- **التوصية**: يجب إنشاء مرجع عالمي لـ `direct_buffer` والاحتفاظ به في C++ لمنع الـ JVM من تحريره أثناء التشغيل:
  ```cpp
  direct_buffer_global_ref_ = env->NewGlobalRef(direct_buffer);
  ```
  ويجب تحريره في دالة `release()` باستخدام `env->DeleteGlobalRef()`.

---

### [Critical] Finding 3: فقدان البيانات الصوتية في حلقة الاستدلال (Data Loss via Vector Clear in Inference Loop)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 578-581)
- **المشكلة**:
  في حلقة الاستدلال `inferenceLoop`، عندما يتجاوز حجم الذاكرة المؤقتة الحجم المستهدف `target_chunk_size` (8000 عينة)، يتم استدعاء الاستدلال ثم مسح الذاكرة بالكامل:
  ```cpp
  if (inference_buffer.size() >= target_chunk_size) {
      performInference(inference_buffer);
      inference_buffer.clear(); // مسح كامل الذاكرة المؤقتة
  }
  ```
- **الأثر**: إذا تراكمت عينات إضافية في الذاكرة المؤقتة (مثلاً تم سحب 9000 عينة بسبب تأخر بسيط في معالجة الإطار السابق)، فإن استدعاء `inference_buffer.clear()` سيمسح الـ 1000 عينة الزائدة بالكامل (تساوي 62.5ms من الصوت). هذا يتسبب في تقطع وتشويه الصوت وفشل عملية التعرف على الكلام.
- **التوصية**: بدلاً من مسح الذاكرة المؤقتة بالكامل، يجب فقط مسح الجزء الذي تمت معالجته والاحتفاظ بالباقي:
  ```cpp
  std::vector<float> chunk(inference_buffer.begin(), inference_buffer.begin() + target_chunk_size);
  performInference(chunk);
  inference_buffer.erase(inference_buffer.begin(), inference_buffer.begin() + target_chunk_size);
  ```

---

### [Major] Finding 4: خطر القراءة خارج الحدود في خوارزمية إعادة التعيين الخطي (Buffer Overread in Linear Resampling)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 667-677)
- **المشكلة**:
  في خوارزمية `resampleLinear`، يتم حساب المؤشر على النحو التالي:
  ```cpp
  float index = i * ratio;
  int low = static_cast<int>(std::floor(index));
  int high = std::min(low + 1, inputLength - 1);
  ```
  إذا حدث أي خطأ في تقريب الأعداد العائمة (Floating Point Precision) وكانت قيمة `index` مساوية لـ `inputLength` أو أكبر عند نهاية الحلقة، فإن `low` سيكون مساوياً لـ `inputLength` وهو خارج حدود مصفوفة المدخلات.
- **الأثر**: قراءة `input[low]` ستؤدي إلى وصول غير مصرح به للذاكرة (Buffer Overread)، مما قد يسبب قراءة عينات غير صحيحة أو انهيار التطبيق.
- **التوصية**: يجب تقييد قيم `low` و`index` للتأكد من عدم تجاوزهما حدود المصفوفة:
  ```cpp
  int low = std::min(static_cast<int>(std::floor(index)), inputLength - 1);
  ```
  بالإضافة إلى ذلك، يمكن استبدال `std::floor` بالتحويل المباشر `static_cast<int>` لتحسين الأداء في الحلقة الحارة (Hot Loop).

---

### [Major] Finding 5: عدم إعداد أولوية خيط الاستدلال فعلياً (Commented-out Thread Priority Execution)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 561-563)
- **المشكلة**:
  تمت كتابة تعيين أولوية خيط الاستدلال (`nice value = -16`) كتعليقات توضيحية فقط دون كتابة كود تنفيذي فعلي:
  ```cpp
  // Set thread nice value/priority (-16)
  // std::this_thread::set_priority(...) equivalent on Android:
  // setpriority(PRIO_PROCESS, 0, -16);
  ```
- **الأثر**: سيعمل خيط الاستدلال بالأولوية العادية، مما يعرضه للتأخير والخنق من نظام أندرويد (CPU Throttling) عند تشغيل التطبيق على أجهزة لوحية ضعيفة المواصفات.
- **التوصية**: يجب تفعيل تعيين الأولوية بشكل حقيقي من خلال تضمين الموجه `<sys/resource.h>` واستدعاء الدالة:
  ```cpp
  #include <sys/resource.h>
  // داخل خيط الاستدلال بعد إنشائه
  setpriority(PRIO_PROCESS, 0, -16);
  ```

---

### [Major] Finding 6: تشوهات صوتية بسبب إعادة تعيين غير محفوظ الحالة (Stateless Resampling Artifacts)

- **الموقع**: `speech_recognizer_jni.cpp` (القسم 6.4)
- **المشكلة**:
  دالة `resampleLinear` تعمل بشكل غير محفوظ للحالة (Stateless) وتتم معالجة كل إطار صوتي (Chunk) بشكل مستقل. يؤدي تجاهل الكسر العشري المتبقي من الفهرس عند نهاية الإطار إلى حدوث انقطاع طوري (Phase Discontinuity) عند بداية الإطار التالي.
- **الأثر**: يتولد عن هذا الانقطاع صوت نقرات متكرر وعالي التردد (clicks) عند حدود كل إطار، مما يشوش على الإشارة الصوتية ويقلل بشكل كبير من دقة خوارزمية التعرف على الكلام للموديل.
- **التوصية**: يجب إدخال حالة مستمرة (Stateful Resampler) تحتفظ بقيمة الكسر العشري المتبقي (Fractional Index Accumulator) بين الاستدعاءات المتتالية، أو استخدام مكتبة موثوقة مثل Oboe أو SpeexDSP لإعادة التعيين.

---

### [Minor] Finding 7: غياب فحص الأخطاء لـ AudioRecord وقيم الإدخال السالبة (Missing Error and Bounds Checking in nativeEnqueueFrame)

- **الموقع**: `speech_recognizer_jni.cpp` (الأسطر 503-524)
- **المشكلة**:
  1. لا تقوم دالة `nativeEnqueueFrame` بالتحقق من قيمة `bytes_read`. يمكن لدالة `AudioRecord.read()` في Kotlin إرجاع قيم سالبة لتمثيل الأخطاء (مثل `ERROR_INVALID_OPERATION`).
  2. لا توجد مقارنة بين `bytes_read` والسعة القصوى المسجلة للمخزن المؤقت `direct_buffer_capacity_`.
- **الأثر**: تمرير قيم سالبة أو قيم تتجاوز سعة المخزن المؤقت قد يؤدي إلى سلوك غير متوقع أو قراءة خارج حدود الذاكرة.
- **التوصية**: أضف التحقق التالي في بداية `enqueueFrame`:
  ```cpp
  if (bytes_read <= 0 || bytes_read > direct_buffer_capacity_) {
      LOGE("Invalid bytes_read value: %d", bytes_read);
      return 0;
  }
  ```

---

### [Minor] Finding 8: تداخل الذاكرة المخبئية للمتغيرات الذرية (Atomic False Sharing in SPSC Queue)

- **الموقع**: `speech_recognizer_jni.h` (الأسطر 202-203)
- **المشكلة**:
  تم تعريف `head_` و`tail_` كمتغيرات ذرية متجاورة مباشرة في الذاكرة. في بنية المعالجات متعددة الأنوية، قد يقع المتغيران على نفس سطر الذاكرة المخبئية (Cache Line - عادة 64 بايت).
- **الأثر**: بما أن خيط المنتج يكتب في `head_` ويقرأ من `tail_` وخيط المستهلك يكتب في `tail_` ويقرأ من `head_` بشكل متكرر جداً، فإن هذا يؤدي إلى ارتداد سطر الذاكرة المخبئية (Cache Line Bouncing) مما يؤثر سلباً على أداء طابور SPSC.
- **التوصية**: أضف محاذاة صريحة لمنع التداخل (False Sharing):
  ```cpp
  alignas(64) std::atomic<size_t> head_;
  alignas(64) std::atomic<size_t> tail_;
  ```

---

## Verified Claims (الادعاءات التي تم التحقق منها)

- **تطابق تواقيع JNI بين Kotlin و C++** ← تم التحقق منها عبر مقارنة `JNINativeMethod g_methods[]` يدوياً مع تصريحات `external fun` في `SpeechRecognizerBridge.kt` ← **PASS**
- **صحة كود تحويل NEON SIMD** ← تم تتبع عمليات التحميل، التقسيم، التحويل العائم، والضرب بالمعامل الرياضي بدقة ← **PASS**
- **صحة صياغة مخطط Mermaid** ← تم فحص الصياغة والتأكد من مطابقتها لمعايير Mermaid sequence diagrams ← **PASS**

---

## Coverage Gaps (فجوات التغطية والمخاطر)

- **التعامل مع فقدان التركيز الصوتي (Audio Focus Loss)** — مستوى الخطورة: **Medium** — التوصية: على الرغم من الإشارة إليها في القسم 6.3، يجب توفير تصميم دقيق لكيفية إشعار المحرك الأصلي C++ بتعليق المعالجة الفوري عند حدوث `AUDIOFOCUS_LOSS` لمنع استمرار عمل الموديل في الخلفية دون داعٍ.
- **استراتيجية التقطيع الصوتي (Acoustic VAD)** — مستوى الخطورة: **High** — التوصية: لم تشر الدراسة إلى كيفية دمج كاشف النشاط الصوتي (Voice Activity Detection - VAD) لمنع إرسال الصمت أو الضوضاء المحيطة إلى خيط الاستدلال، وهو أمر حيوي للأجهزة الضعيفة لتوفير طاقة المعالج وتقليل استهلاك البطارية.

---

## Unverified Items (عناصر لم يتم التحقق منها)

- **أداء الاستدلال الفعلي لموديل Sherpa-onnx** — السبب: لم يتم التحقق منه لأن الدراسة نظرية وتصميمية فقط ولا توجد بيئة تشغيل فعلية أو كود لتشغيل الموديل في هذه المرحلة.
