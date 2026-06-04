// خيط المعالجة الصوتي الخلفي لتشغيل نموذج Whisper-tiny-ar-quran عبر Transformers.js
importScripts('https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.0.1/dist/transformers.min.js');

const { pipeline, env } = transformers;

// تكوين التخزين المؤقت المحلي للعمل أوفلاين بالكامل
env.useWasmCache = true;
env.allowLocalModels = true;
env.allowRemoteModels = false;
env.localModelPath = './models/';

let transcriber = null;

// الاستماع للرسائل الواردة من خيط التشغيل الرئيسي
self.onmessage = async (e) => {
  const { type, audio } = e.data;

  if (type === 'load') {
    try {
      console.log('[Whisper Worker] جاري تهيئة خط أنابيب الاستماع المحلي (Q4)...');
      transcriber = await pipeline('automatic-speech-recognition', 'whisper-tiny-ar-quran-onnx', {
        dtype: 'q4',
        progress_callback: (data) => {
          if (data.status === 'progress') {
            // إرسال نسب تحميل الملفات الفردية لتحديث شريط التقدم
            self.postMessage({
              type: 'progress',
              file: data.file,
              progress: data.progress,
              loaded: data.loaded,
              total: data.total
            });
          } else if (data.status === 'ready') {
            self.postMessage({ type: 'file_ready', file: data.file });
          }
        }
      });
      console.log('[Whisper Worker] اكتمل تحميل النموذج المحلي بنجاح وهو جاهز للاستخدام.');
      self.postMessage({ type: 'ready' });
    } catch (err) {
      console.error('[Whisper Worker] فشل تحميل الموديل الصوتي المحلي:', err);
      self.postMessage({ type: 'error', error: err.message });
    }
  } else if (type === 'transcribe') {
    if (!transcriber) {
      self.postMessage({ type: 'error', error: 'الموديل الصوتي لم يتم تحميله بعد.' });
      return;
    }

    try {
      const startTime = performance.now();
      
      // تشغيل الاستدلال الصوتي على دفق الصوت المُمرر
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
