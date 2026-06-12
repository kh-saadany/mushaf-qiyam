// خيط المعالجة الصوتي الخلفي لتشغيل نموذج Whisper-tiny-ar-quran عبر Transformers.js
// ================================================

// التقاط أي خطأ غير متوقع في الخيط الخلفي وإرساله للواجهة الرئيسية
self.onerror = function(message, source, lineno, colno, error) {
  self.postMessage({
    type: 'error',
    error: `[Worker Global Error] ${message} (${source}:${lineno}:${colno})`
  });
  return true; // منع ظهور الخطأ مرتين
};

self.onunhandledrejection = function(event) {
  self.postMessage({
    type: 'error',
    error: `[Worker Unhandled Promise] ${event.reason}`
  });
};

// حساب المسار المطلق للموديلات بناءً على مسار ملف الـ Worker نفسه
const workerBaseUrl = self.location.href.substring(0, self.location.href.lastIndexOf('/') + 1);
const absoluteModelPath = workerBaseUrl + 'models/';

console.log('[Whisper Worker] Base URL:', workerBaseUrl);
console.log('[Whisper Worker] Model path:', absoluteModelPath);

// تحميل مكتبة Transformers.js محلياً من ملفات المشروع كـ Module
let libraryLoaded = false;
let libraryLoadError = null;
let transformersInstance = null;

async function loadLibrary() {
  if (libraryLoaded) return true;
  try {
    console.log('[Whisper Worker] جاري استيراد مكتبة Transformers.js (JS) محلياً...');
    const module = await import('./transformers.min.js');
    transformersInstance = module;
    libraryLoaded = true;
    console.log('[Whisper Worker] تم استيراد مكتبة Transformers.js بنجاح.');
    return true;
  } catch (err) {
    console.error('[Whisper Worker] فشل استيراد مكتبة Transformers.js محلياً:', err);
    libraryLoadError = err.message || err.toString();
    return false;
  }
}

let transcriber = null;

// الاستماع للرسائل الواردة من خيط التشغيل الرئيسي
self.onmessage = async (e) => {
  const { type, audio } = e.data;

  if (type === 'load') {
    // محاولة استيراد المكتبة
    const loaded = await loadLibrary();
    if (!loaded || !transformersInstance) {
      self.postMessage({
        type: 'error',
        error: `فشل تحميل مكتبة التعرف الصوتي المحلية (transformers.min.js): ${libraryLoadError || 'سبب غير معروف'}`
      });
      return;
    }

    try {
      const { pipeline, env } = transformersInstance;

      // تكوين بيئة المكتبة لتحميل الموديل محلياً من مجلد المشروع
      env.allowLocalModels = true;
      env.allowRemoteModels = true;  // السماح بالتحميل عن بعد كخطة بديلة
      env.localModelPath = absoluteModelPath;
      env.useBrowserCache = true;

      // تكوين مسارات ملفات ONNX Runtime WASM محلياً لتشغيل أوفلاين بالكامل
      if (env.backends && env.backends.onnx) {
        env.backends.onnx.wasm.wasmPaths = workerBaseUrl;
      }
      if (env.onnx) {
        env.onnx.wasm.wasmPaths = workerBaseUrl;
      }

      console.log('[Whisper Worker] env.localModelPath =', env.localModelPath);
      console.log('[Whisper Worker] WASM paths =', workerBaseUrl);
      console.log('[Whisper Worker] جاري تهيئة خط أنابيب الاستماع (Q4)...');

      // إرسال رسالة تأكيد بدء التحميل الفعلي
      self.postMessage({ type: 'loading_started' });

      transcriber = await pipeline('automatic-speech-recognition', 'whisper-tiny-ar-quran-onnx', {
        dtype: 'q4',
        progress_callback: (data) => {
          console.log('[Whisper Worker] progress:', data.status, data.file, data.progress);
          if (data.status === 'progress') {
            self.postMessage({
              type: 'progress',
              file: data.file,
              progress: data.progress,
              loaded: data.loaded,
              total: data.total
            });
          } else if (data.status === 'ready') {
            self.postMessage({ type: 'file_ready', file: data.file });
          } else if (data.status === 'initiate') {
            self.postMessage({ type: 'file_initiate', file: data.file });
          } else if (data.status === 'done') {
            self.postMessage({ type: 'file_done', file: data.file });
          }
        }
      });

      console.log('[Whisper Worker] اكتمل تحميل النموذج بنجاح وهو جاهز للاستخدام.');
      self.postMessage({ type: 'ready' });

    } catch (err) {
      console.error('[Whisper Worker] فشل تحميل الموديل الصوتي:', err);
      self.postMessage({
        type: 'error',
        error: `فشل تحميل الموديل: ${err.message || err}`
      });
    }

  } else if (type === 'transcribe') {
    if (!transcriber) {
      self.postMessage({ type: 'error', error: 'الموديل الصوتي لم يتم تحميله بعد.' });
      return;
    }

    try {
      const startTime = performance.now();

      const output = await transcriber(audio, {
        chunk_length_s: 30,
        stride_length_s: 5,
        language: 'ar',
        task: 'transcribe',
        return_timestamps: false
      });

      const duration = performance.now() - startTime;
      console.log(`[Whisper Worker] زمن الاستدلال: ${duration.toFixed(2)}ms`);

      self.postMessage({
        type: 'result',
        text: output.text
      });
    } catch (err) {
      console.error('[Whisper Worker] فشل التعرف الصوتي:', err);
      self.postMessage({ type: 'error', error: err.message });
    }
  }
};
