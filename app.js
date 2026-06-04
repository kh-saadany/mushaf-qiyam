/**
 * ==========================================================================
 * المنطق البرمجي الأساسي لتطبيق "مصحف القيام" (PWA)
 * مع دمج محرك Vosk-browser للتشغيل أوفلاين بالكامل
 * ==========================================================================
 */

// معلمات وأسماء الحالات (States)
const STATE_IDLE = 'STATE_IDLE';
const STATE_WAITING_FATIHA = 'STATE_WAITING_FATIHA';
const STATE_RECITING = 'STATE_RECITING';
const STATE_RUKU = 'STATE_RUKU';

// المتغيرات العامة للتطبيق
let quranDatabase = null; // قاعدة بيانات المصحف (604 صفحات)
let currentPage = 1;      // الصفحة الحالية المعروضة
let currentSurah = 1;     // السورة الحالية
let isPrayerActive = false;
let prayerState = STATE_IDLE;
let wakeLock = null;

// متغيرات محرك الصوت (أونلاين وأوفلاين)
let whisperWorker = null;
let nativeRecognizer = null;
let isOfflineMode = false;
let isModelCached = false;
let isModelLoading = false;
let audioContext = null;
let mediaStream = null;
let audioSource = null;
let audioProcessor = null;
let isMicGranted = false;

// ذاكرة مؤقتة لمعالجة دفق الصوت في وضع أوفلاين (Sliding Window)
let audioBuffer = [];
const SAMPLE_RATE = 16000;
const WINDOW_SIZE_SEC = 4;
const STRIDE_SIZE_SEC = 2;
const MAX_BUFFER_SAMPLES = SAMPLE_RATE * WINDOW_SIZE_SEC;
const STRIDE_SAMPLES = SAMPLE_RATE * STRIDE_SIZE_SEC;

// علامات تتبع الوقوف والركعات
let lastMatchedVerse = { surah: 1, ayah: 0 };
let checkpointVerse = { page: 1, surah: 1, ayah: 0 };
let rakahCount = 1; // عداد الركعات في الصلاة الحالية
let spokenHistory = ''; // مخزن الكلمات المنطوقة المتراكم للمطابقة المستمرة

// ثوابت روابط الصور ومعرف الموديل الصوتي
const IMAGE_BASE_URL = 'https://raw.githubusercontent.com/GovarJabbar/Quran-PNG/master/';
const MODEL_URL = 'omartariq612/whisper-tiny-ar-quran-onnx';

// ==================== التهيئة عند التشغيل الأول ==================== //
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

async function initApp() {
  try {
    // 1. تحميل قاعدة بيانات المصحف المحلية
    const response = await fetch('quran-pages.json');
    quranDatabase = await response.json();
    
    // 2. ملء قائمة السور والصفحات في واجهة الإعداد
    populateSetupSelectors();
    
    // 3. التحقق من حالة تخزين المصحف أوفلاين
    checkOfflineCacheStatus();
    
    // 4. التحقق من حالة تخزين الموديل الصوتي أوفلاين
    checkOfflineModelStatus();

    // تهيئة اختيار وضع التعرف الصوتي (أونلاين/أوفلاين)
    const savedMode = localStorage.getItem('mushaf-prayer-mode') || 'online';
    isOfflineMode = (savedMode === 'offline');
    const modeSelect = document.getElementById('prayer-mode');
    if (modeSelect) {
      modeSelect.value = savedMode;
      modeSelect.addEventListener('change', (e) => {
        isOfflineMode = (e.target.value === 'offline');
        localStorage.setItem('mushaf-prayer-mode', e.target.value);
        updateStartButtonState();
        
        // إخفاء/إظهار خيار تحميل الموديل الصوتي في شاشة الإعداد بناءً على الخيار
        const downloadContainer = document.getElementById('voice-model-download-container');
        if (downloadContainer) {
          downloadContainer.style.display = isOfflineMode ? 'block' : 'none';
        }
      });
      
      const downloadContainer = document.getElementById('voice-model-download-container');
      if (downloadContainer) {
        downloadContainer.style.display = isOfflineMode ? 'block' : 'none';
      }
    }
    
    // 5. ربط أحداث أزرار وعناصر الواجهة
    setupEventListeners();
    
    // 6. التحقق من وجود تحديثات على جيت هاب
    checkForUpdates();
    
    // 7. تسجيل الـ Service Worker للتشغيل أوفلاين
    registerServiceWorker();

  } catch (error) {
    console.error('خطأ أثناء تهيئة التطبيق:', error);
    showStatusMessage('حدث خطأ في تحميل ملفات المصحف الأساسية.', 'red');
  }
}

// تسجيل الـ Service Worker
function registerServiceWorker() {
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('service-worker.js')
      .then((reg) => {
        console.log('Service Worker registered successfully:', reg.scope);
      })
      .catch((err) => console.error('Service Worker registration failed:', err));

    // الاستماع لرسائل التقدم في التحميل أوفلاين من السيرفس وركر (مستوى الصفحة بالكامل)
    navigator.serviceWorker.addEventListener('message', (event) => {
      if (event.data) {
        if (event.data.type === 'cache-progress') {
          updateDownloadUI(event.data.progress, event.data.cachedCount, event.data.totalCount);
        } else if (event.data.type === 'cache-completed') {
          onDownloadCompleted();
        }
      }
    });
  }
}

// ==================== ملء القوائم المنسدلة ==================== //
function populateSetupSelectors() {
  const surahSelect = document.getElementById('surah-select');
  const pageSelect = document.getElementById('page-select');
  
  if (!quranDatabase) return;

  const surahsMap = new Map();
  quranDatabase.forEach(p => {
    p.verses.forEach(v => {
      if (!surahsMap.has(v.surah)) {
        surahsMap.set(v.surah, {
          id: v.surah,
          name: v.surahName,
          firstPage: p.page
        });
      }
    });
  });

  const sortedSurahs = Array.from(surahsMap.values()).sort((a, b) => a.id - b.id);
  surahSelect.innerHTML = sortedSurahs.map(s => 
    `<option value="${s.id}" data-page="${s.firstPage}">سورة ${s.name.replace('سُورَةُ ', '')}</option>`
  ).join('');

  const pagesHTML = [];
  for (let i = 1; i <= 604; i++) {
    pagesHTML.push(`<option value="${i}">الصفحة ${i}</option>`);
  }
  pageSelect.innerHTML = pagesHTML.join('');

  surahSelect.addEventListener('change', () => {
    const selectedOption = surahSelect.options[surahSelect.selectedIndex];
    const pageNum = selectedOption.getAttribute('data-page');
    pageSelect.value = pageNum;
    currentPage = parseInt(pageNum);
  });

  pageSelect.addEventListener('change', () => {
    currentPage = parseInt(pageSelect.value);
    
    // تحديث اختيار السورة تلقائياً لتناسب الصفحة المحددة
    if (quranDatabase) {
      const pageData = quranDatabase[currentPage - 1];
      if (pageData && pageData.verses.length > 0) {
        const firstVerse = pageData.verses[0];
        surahSelect.value = firstVerse.surah;
      }
    }
  });
}

// ==================== ربط أحداث العناصر (Event Listeners) ==================== //
function setupEventListeners() {
  // أزرار شاشة الإعداد
  document.getElementById('btn-grant-mic').addEventListener('click', requestMicrophonePermission);
  document.getElementById('btn-start-prayer').addEventListener('click', startPrayerSession);
  document.getElementById('btn-download-quran').addEventListener('click', downloadAllQuranImages);
  document.getElementById('btn-download-model').addEventListener('click', downloadVoskModel);
  document.getElementById('btn-show-guide').addEventListener('click', () => toggleModal('guide-modal', true));
  document.getElementById('btn-close-guide').addEventListener('click', () => toggleModal('guide-modal', false));

  // أزرار شاشة الصلاة
  document.getElementById('btn-exit-prayer').addEventListener('click', stopPrayerSession);
  document.getElementById('btn-theme-toggle').addEventListener('click', toggleTheme);
  document.getElementById('btn-skip-ruku').addEventListener('click', skipRukuState);

  // مناطق اللمس الجانبية لتقليب الصفحات يدوياً
  document.getElementById('zone-prev').addEventListener('click', () => flipPageManual(-1));
  document.getElementById('zone-next').addEventListener('click', () => flipPageManual(1));

  // إيماءات اللمس السريعة (Swipe Gestures) على الشاشة
  const viewport = document.getElementById('mushaf-viewport');
  let touchStartX = 0;
  
  viewport.addEventListener('touchstart', (e) => {
    touchStartX = e.changedTouches[0].screenX;
  }, { passive: true });

  viewport.addEventListener('touchend', (e) => {
    const touchEndX = e.changedTouches[0].screenX;
    const diffX = touchEndX - touchStartX;
    
    if (diffX < -60) {
      flipPageManual(1);
    } else if (diffX > 60) {
      flipPageManual(-1);
    }
  }, { passive: true });

  document.getElementById('btn-apply-update').addEventListener('click', () => {
    window.location.reload(true);
  });
}

function toggleModal(id, show) {
  const modal = document.getElementById(id);
  if (show) modal.classList.add('open');
  else modal.classList.remove('open');
}

// ==================== إدارة المظهر (ليلي / نهاري) ==================== //
function toggleTheme() {
  const body = document.body;
  if (body.classList.contains('dark-theme')) {
    body.classList.replace('dark-theme', 'light-theme');
    localStorage.setItem('theme', 'light');
  } else {
    body.classList.replace('light-theme', 'dark-theme');
    localStorage.setItem('theme', 'dark');
  }
}

// ==================== تفعيل صلاحيات الميكروفون ==================== //
function requestMicrophonePermission() {
  navigator.mediaDevices.getUserMedia({ audio: true })
    .then((stream) => {
      stream.getTracks().forEach(track => track.stop());
      
      const dot = document.getElementById('mic-status-dot');
      const text = document.getElementById('mic-status-text');
      
      dot.className = 'pulse-dot green';
      text.innerText = 'الميكروفون مفعل';
      isMicGranted = true;
      
      updateStartButtonState();
      document.getElementById('btn-grant-mic').style.display = 'none';
      
      showStatusMessage('تم تفعيل الميكروفون بنجاح!', 'green');
    })
    .catch((err) => {
      console.error('Microphone access denied:', err);
      showStatusMessage('عذراً، يجب تفعيل الميكروفون لتتبع التلاوة آلياً.', 'red');
    });
}

// تحديث إمكانية بدء الصلاة (الميكروفون + الموديل الصوتي عند الحاجة)
function updateStartButtonState() {
  const startBtn = document.getElementById('btn-start-prayer');
  if (isOfflineMode) {
    startBtn.disabled = !(isMicGranted && isModelCached);
  } else {
    // الوضع أونلاين: يتطلب الميكروفون فقط
    startBtn.disabled = !isMicGranted;
  }
}

// تحديث مؤشر الصوت البصري
function updateAudioIndicator(active) {
  const dot = document.getElementById('audio-indicator-dot');
  if (dot) {
    dot.className = active ? 'pulse-dot green' : 'pulse-dot red';
  }
}

// ==================== معالجة الكلام الذكي (Quran Sync Algorithm) ==================== //
function handleSpokenWords(words) {
  const toastText = document.getElementById('recognized-words');
  toastText.innerText = words;

  const cleanSpoken = normalizeArabic(words);

  if (detectTakbeer(cleanSpoken)) {
    triggerRukuState();
    return;
  }

  if (prayerState === STATE_WAITING_FATIHA) {
    if (detectFatiha(cleanSpoken)) {
      transitionToReciting();
    }
    return;
  }

  if (prayerState === STATE_RECITING) {
    matchRecitationWithQuran(cleanSpoken);
  }
}

// خوارزمية تطبيع الحروف العربية (تجريد التشكيل وتوحيد الحروف المتقاربة)
// تدعم الرسم العثماني للمصحف بالكامل (Unicode Uthmani Script)
function normalizeArabic(text) {
  if (!text) return '';
  return text
    // 1. إزالة جميع علامات التشكيل القياسية (فتحة، ضمة، كسرة، شدة، سكون، تنوين)
    .replace(/[\u064B-\u0652]/g, '')
    // 2. إزالة المدة والهمزة الفوقية والتحتية (maddah, hamza above/below)
    .replace(/[\u0653-\u0655]/g, '')
    // 3. إزالة الألف الصغيرة الفوقية (superscript alef) وعلامات أخرى
    .replace(/[\u0656-\u065F\u0670]/g, '')
    // 4. إزالة علامات التلاوة والوقف القرآنية (Quranic annotation signs)
    .replace(/[\u0610-\u061A]/g, '')
    .replace(/[\u06D6-\u06ED]/g, '')
    // 5. إزالة التطويل (kashida/tatweel)
    .replace(/\u0640/g, '')
    // 6. توحيد أشكال الألف: أ إ آ ٱ → ا
    .replace(/[أإآ\u0671]/g, 'ا')
    // 7. توحيد الياء والألف المقصورة: ى → ي
    .replace(/ى/g, 'ي')
    // 8. توحيد التاء المربوطة: ة → ه
    .replace(/ة/g, 'ه')
    // 9. إزالة رموز الأحزاب والأرباع والسجدات وغيرها (۞ ۩ ﷺ إلخ)
    .replace(/[\u06DE\u06E9\uFDFA\uFDFB\uFDFC]/g, '')
    // 10. إزالة أي حرف ليس حرفاً عربياً أساسياً (U+0621-U+064A) أو مسافة
    .replace(/[^\u0621-\u064A\s]/g, '')
    // 11. تنظيف المسافات المتكررة
    .replace(/\s+/g, ' ')
    .trim();
}

// دالة لدمج المقاطع الصوتية المتتالية وتجنب التكرار الناتج عن التداخل (Overlap) في دفق الصوت
function mergeSpeechTranscripts(existingText, newText) {
  if (!existingText) return newText.trim();
  if (!newText) return existingText.trim();

  const words1 = existingText.trim().split(/\s+/);
  const words2 = newText.trim().split(/\s+/);

  // نبحث عن تداخل بطول أقصى يساوي طول إحدى المجموعتين وبحد أقصى 6 كلمات
  const maxOverlap = Math.min(words1.length, words2.length, 6);

  for (let k = maxOverlap; k > 0; k--) {
    const endSlice = words1.slice(-k).join(' ');
    const startSlice = words2.slice(0, k).join(' ');

    if (normalizeArabic(endSlice) === normalizeArabic(startSlice)) {
      // تم العثور على تداخل متطابق، نقوم بدمج النصين مع استبعاد الكلمات المكررة
      return words1.concat(words2.slice(k)).join(' ');
    }
  }

  // إذا لم يعثر على أي تداخل، نقوم بإضافة النص الجديد كـ جملة جديدة
  return existingText + ' ' + newText;
}

// تحديث مخزن الكلمات المنطوقة مع دمج النتائج الجديدة والتأكد من عدم تضخم الحجم
function updateSpokenHistory(newText) {
  spokenHistory = mergeSpeechTranscripts(spokenHistory, newText);
  
  // الاحتفاظ بآخر 40 كلمة فقط لضمان سرعة المعالجة واستقرار المطابقة
  const tokens = spokenHistory.trim().split(/\s+/);
  if (tokens.length > 40) {
    spokenHistory = tokens.slice(-40).join(' ');
  }
}


// التحقق من كشف التكبير ("الله أكبر") للانتقال للركوع
function detectTakbeer(cleanText) {
  const tokens = cleanText.split(' ');
  const lastWords = tokens.slice(-3).join(' ');
  return lastWords.includes('الله اكبر') || lastWords.includes('اللهم اكبر');
}

// التحقق من كشف قراءة سورة الفاتحة لبدء ركعة جديدة
function detectFatiha(cleanText) {
  const fatihaKeywords = [
    'الحمد لله رب العالمين',
    'الرحمن الرحيم',
    'مالك يوم الدين',
    'اياك نعبد واياك نستعين',
    'اهدنا الصراط المستقيم',
    'صراط الذين انعمت عليهم',
    'غير المغضوب عليهم',
    'ولا الضالين'
  ];
  return fatihaKeywords.some(keyword => cleanText.includes(normalizeArabic(keyword)));
}

// البحث عن الآية الأكثر ملاءمة للاستعلام في قائمة الآيات بناءً على عدد الكلمات المشتركة
function findBestMatchingVerse(verses, cleanQuery) {
  let bestVerse = null;
  let maxOverlap = 0;
  const queryTokens = cleanQuery.split(' ');

  verses.forEach(verse => {
    const cleanVerse = normalizeArabic(verse.text);
    let overlap = 0;
    queryTokens.forEach(word => {
      if (cleanVerse.includes(word)) {
        overlap++;
      }
    });

    if (overlap > maxOverlap) {
      maxOverlap = overlap;
      bestVerse = verse;
    }
  });

  return bestVerse || verses[0];
}

// خوارزمية مطابقة تسلسلية مرنة للمقارنة بين التلاوة ونص الصفحة الكامل في حال سقوط بعض الحروف أو تبدلها
function checkTextSubSequenceMatch(fullText, queryText) {
  const queryTokens = queryText.split(' ');
  if (queryTokens.length < 3) return false;

  let lastIndex = -1;
  let matchCount = 0;

  for (let word of queryTokens) {
    const index = fullText.indexOf(word, lastIndex + 1);
    if (index > lastIndex) {
      // لضمان تقارب الكلمات المطابقة وعدم قفزها عبر الصفحة بشكل عشوائي
      if (lastIndex === -1 || (index - lastIndex) < 60) {
        lastIndex = index;
        matchCount++;
      }
    }
  }

  return (matchCount / queryTokens.length) >= 0.7;
}

// مطابقة التلاوة مع نصوص آيات الصفحة المفتوحة والصفحة التالية بشكل متسلسل
function matchRecitationWithQuran(cleanSpoken) {
  if (!quranDatabase) return;

  const pageData = quranDatabase[currentPage - 1];
  const nextPageData = quranDatabase[currentPage]; // الصفحة التالية
  
  if (!pageData) return;

  const spokenTokens = cleanSpoken.split(' ');
  const totalTokens = spokenTokens.length;

  const currentPageText = pageData.verses.map(v => normalizeArabic(v.text)).join(' ');
  const nextPageText = nextPageData ? nextPageData.verses.map(v => normalizeArabic(v.text)).join(' ') : '';

  let matchedPage = null;
  let matchedVerse = null;

  // 1. نبحث أولاً في الصفحة الحالية بأطوال استعلام تنازلية من 10 إلى 2
  const currentQueryLengths = [10, 8, 6, 4, 3, 2];
  for (let len of currentQueryLengths) {
    if (totalTokens < len) continue;
    const cleanQuery = spokenTokens.slice(-len).join(' ');

    if (currentPageText.includes(cleanQuery)) {
      matchedPage = currentPage;
      matchedVerse = findBestMatchingVerse(pageData.verses, cleanQuery);
      break;
    }
    if (checkTextSubSequenceMatch(currentPageText, cleanQuery)) {
      matchedPage = currentPage;
      matchedVerse = findBestMatchingVerse(pageData.verses, cleanQuery);
      break;
    }
  }

  // 2. إذا لم نجد مطابقة في الصفحة الحالية، نبحث في الصفحة التالية بأطوال من 10 إلى 4 (لتفادي التقلبات الخاطئة)
  if (!matchedPage && nextPageText) {
    const nextQueryLengths = [10, 8, 6, 4];
    for (let len of nextQueryLengths) {
      if (totalTokens < len) continue;
      const cleanQuery = spokenTokens.slice(-len).join(' ');

      if (nextPageText.includes(cleanQuery)) {
        matchedPage = currentPage + 1;
        matchedVerse = findBestMatchingVerse(nextPageData.verses, cleanQuery);
        break;
      }
      if (checkTextSubSequenceMatch(nextPageText, cleanQuery)) {
        matchedPage = currentPage + 1;
        matchedVerse = findBestMatchingVerse(nextPageData.verses, cleanQuery);
        break;
      }
    }
  }

  // إذا تم العثور على مطابقة موثقة
  if (matchedPage && matchedVerse) {
    lastMatchedVerse = {
      surah: matchedVerse.surah,
      ayah: matchedVerse.ayah
    };

    console.log(`Matched (Stream): Surah ${matchedVerse.surah}, Ayah ${matchedVerse.ayah} on page ${matchedPage}`);

    if (matchedPage > currentPage) {
      flipPage(matchedPage);
    }
  }
}


// ==================== دورة حياة الصلاة وإدارة الحالات ==================== //

// بدء الصلاة وتهيئة محرك الصوت (أونلاين أو أوفلاين)
async function startPrayerSession() {
  if (isPrayerActive) return;

  const statusToastText = document.getElementById('recognized-words');
  statusToastText.innerText = 'جاري تهيئة محرك الصوت...';

  // الانتقال لشاشة الصلاة
  document.getElementById('setup-screen').classList.remove('active');
  document.getElementById('prayer-screen').classList.add('active');

  // عرض صفحة البداية المختارة
  displayPage(currentPage);

  isPrayerActive = true;
  prayerState = STATE_WAITING_FATIHA;
  rakahCount = 1;
  spokenHistory = ''; // إعادة ضبط مخزن الكلمات المنطوقة
  updatePrayerStatusUI();

  // إبقاء الشاشة مضيئة
  requestWakeLock();

  if (!isOfflineMode) {
    // ------------------ وضع التشغيل أونلاين (Native Web Speech API) ------------------
    try {
      const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SpeechRecognition) {
        throw new Error('التعرف الصوتي للمتصفح غير مدعوم في هذا الجهاز.');
      }
      
      nativeRecognizer = new SpeechRecognition();
      nativeRecognizer.lang = 'ar-EG';
      nativeRecognizer.continuous = true;
      nativeRecognizer.interimResults = true;
      
      nativeRecognizer.onresult = (event) => {
        let finalTranscript = '';
        let interimTranscript = '';
        for (let i = 0; i < event.results.length; ++i) {
          const transcript = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += transcript + ' ';
          } else {
            interimTranscript += transcript;
          }
        }
        const text = (finalTranscript + interimTranscript).trim();
        if (text.length > 0) {
          spokenHistory = text; // في وضع أونلاين، المتصفح يتولى التجميع وتصحيح الكلمات تلقائياً
          
          // نقتطع آخر 40 كلمة فقط لتفادي تضخم النص
          const tokens = spokenHistory.split(/\s+/);
          if (tokens.length > 40) {
            spokenHistory = tokens.slice(-40).join(' ');
          }
          
          console.log('[Native Speech Full]:', spokenHistory);
          handleSpokenWords(spokenHistory);
        }
      };

      nativeRecognizer.onerror = (event) => {
        console.error('[Native Speech Error]:', event.error);
        if (event.error === 'no-speech') return;
        statusToastText.innerText = 'تنبيه: ' + event.error;
      };

      nativeRecognizer.onend = () => {
        if (isPrayerActive && nativeRecognizer) {
          console.log('[Native Speech] Restarting Speech Recognition...');
          try { nativeRecognizer.start(); } catch (err) { console.log(err); }
        }
      };

      nativeRecognizer.start();
      updateAudioIndicator(true);
      statusToastText.innerText = 'بانتظار قراءة الفاتحة (وضع أونلاين)...';
      console.log('[Native Speech] Listening started successfully');

    } catch (error) {
      console.error('[Native Speech] Initialization error:', error);
      statusToastText.innerText = 'فشل تشغيل التعرف الصوتي أونلاين. اضغط خروج لإعادة التهيئة.';
      updateAudioIndicator(false);
    }
  } else {
    // ------------------ وضع التشغيل أوفلاين (Whisper WASM Worker) ------------------
    try {
      if (!whisperWorker) {
        // تهيئة الـ Web Worker
        whisperWorker = new Worker('whisper-worker.js');
        
        whisperWorker.onmessage = (e) => {
          const { type, text, error } = e.data;
          if (type === 'result') {
            if (isPrayerActive && text && text.trim().length > 0) {
              console.log('[Whisper Result]:', text);
              updateSpokenHistory(text);
              handleSpokenWords(spokenHistory);
            }
          } else if (type === 'error') {
            console.error('[Whisper Worker Error]:', error);
            statusToastText.innerText = 'خطأ في المحرك: ' + error;
          }
        };
      }

      // إرسال رسالة تحميل احتياطية لضمان تنشيط النموذج في الـ Worker
      whisperWorker.postMessage({ type: 'load' });

      // تهيئة مسجل الصوت ومجرى الميكروفون
      mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
      
      // إنشاء سياق الصوت (Audio Context) بتردد 16000Hz المفضل لـ Whisper
      audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
      if (audioContext.state === 'suspended') {
        await audioContext.resume();
      }
      
      // تفريغ الذاكرة المؤقتة للصوت
      audioBuffer = [];

      audioSource = audioContext.createMediaStreamSource(mediaStream);
      audioProcessor = audioContext.createScriptProcessor(4096, 1, 1);
      
      audioProcessor.onaudioprocess = (event) => {
        if (isPrayerActive && prayerState !== STATE_RUKU) {
          const floatData = event.inputBuffer.getChannelData(0);
          
          // دفع العينات لجدول التخزين المؤقت
          for (let i = 0; i < floatData.length; i++) {
            audioBuffer.push(floatData[i]);
          }

          // معالجة البيانات عند امتلاء النافذة المنزلقة (Sliding Window)
          if (audioBuffer.length >= MAX_BUFFER_SAMPLES) {
            const windowSamples = new Float32Array(audioBuffer);
            
            // تطبيق فحص الصوت البسيط (Simple RMS-based VAD) لتوفير البطارية
            let sum = 0;
            for (let i = 0; i < windowSamples.length; i++) {
              sum += windowSamples[i] * windowSamples[i];
            }
            const rms = Math.sqrt(sum / windowSamples.length);
            const VAD_THRESHOLD = 0.008; // حد الحساسية الصامتة

            if (rms >= VAD_THRESHOLD) {
              if (whisperWorker) {
                // إرسال عينات الصوت للـ Web Worker لمعالجتها بالخلفية
                whisperWorker.postMessage({ type: 'transcribe', audio: windowSamples });
              }
            } else {
              console.log(`[VAD] صمت أو ضوضاء ضعيفة (RMS: ${rms.toFixed(5)}). تخطي المعالجة.`);
            }

            // إزاحة النافذة المنزلقة (Slide Window)
            audioBuffer = audioBuffer.slice(STRIDE_SAMPLES);
          }
        }
      };

      audioSource.connect(audioProcessor);
      audioProcessor.connect(audioContext.destination);

      updateAudioIndicator(true);
      statusToastText.innerText = 'بانتظار قراءة الفاتحة (وضع أوفلاين)...';
      console.log('[Whisper WASM] Listening initialized successfully');

    } catch (error) {
      console.error('[Whisper WASM] Initialization error:', error);
      statusToastText.innerText = 'فشل تشغيل الصوت أوفلاين. اضغط خروج لإعادة التهيئة.';
      updateAudioIndicator(false);
    }
  }
}

// الانتقال لوضع القراءة النشط بعد الفاتحة
function transitionToReciting() {
  prayerState = STATE_RECITING;
  updatePrayerStatusUI();
  document.getElementById('recognized-words').innerText = 'تم كشف الفاتحة - جاري متابعة السورة...';
  console.log('[State Machine] Transition to Reciting Surah');
}

// كشف الركوع وحفظ علامة التوقف
function triggerRukuState() {
  prayerState = STATE_RUKU;
  updatePrayerStatusUI();

  checkpointVerse = {
    page: currentPage,
    surah: lastMatchedVerse.surah,
    ayah: lastMatchedVerse.ayah
  };

  const pageData = quranDatabase[currentPage - 1];
  let surahName = 'سورة البقرة';
  if (pageData) {
    const matchedV = pageData.verses.find(v => v.surah === checkpointVerse.surah) || pageData.verses[0];
    if (matchedV) surahName = matchedV.surahName;
  }

  document.getElementById('saved-location-text').innerText = `${surahName.replace('سُورَةُ ', '')} - آية ${checkpointVerse.ayah}`;
  document.getElementById('ruku-overlay').classList.add('active');

  console.log('[State Machine] Ruku/Sujood state activated. Checkpoint saved:', checkpointVerse);
}

// تخطي وضع الركوع يدوياً والعودة للركعة التالية
function skipRukuState() {
  document.getElementById('ruku-overlay').classList.remove('active');
  
  rakahCount++;
  prayerState = STATE_WAITING_FATIHA;
  spokenHistory = ''; // إعادة ضبط مخزن الكلمات المنطوقة
  updatePrayerStatusUI();

  currentPage = checkpointVerse.page;
  displayPage(currentPage);

  document.getElementById('recognized-words').innerText = 'انتظار قراءة الفاتحة للركعة التالية...';
}

// إنهاء الصلاة والعودة للتهيئة
function stopPrayerSession() {
  isPrayerActive = false;
  prayerState = STATE_IDLE;
  spokenHistory = ''; // إعادة ضبط مخزن الكلمات المنطوقة

  releaseWakeLock();

  // إغلاق مجرى الصوت ومسجل Vosk
  try {
    if (audioProcessor) {
      audioProcessor.disconnect();
      audioProcessor = null;
    }
    if (audioSource) {
      audioSource.disconnect();
      audioSource = null;
    }
    if (audioContext) {
      audioContext.close();
      audioContext = null;
    }
    if (mediaStream) {
      mediaStream.getTracks().forEach(track => track.stop());
      mediaStream = null;
    }
    if (nativeRecognizer) {
      nativeRecognizer.onend = null;
      nativeRecognizer.stop();
      nativeRecognizer = null;
    }
    audioBuffer = [];
  } catch (e) {
    console.error('Error stopping audio tracks:', e);
  }

  updateAudioIndicator(false);
  document.getElementById('ruku-overlay').classList.remove('active');
  document.getElementById('prayer-screen').classList.remove('active');
  document.getElementById('setup-screen').classList.add('active');
}

// ==================== إدارة عرض صفحات المصحف ==================== //

function displayPage(pageNum) {
  if (pageNum < 1 || pageNum > 604) return;
  
  const imgElement = document.getElementById('mushaf-image');
  const threeDigitPage = String(pageNum).padStart(3, '0');
  const imageUrl = `${IMAGE_BASE_URL}${threeDigitPage}.png`;

  imgElement.src = imageUrl;
  document.getElementById('current-page-num').innerText = `صفحة ${pageNum}`;
  
  if (quranDatabase) {
    const pageData = quranDatabase[pageNum - 1];
    if (pageData && pageData.verses.length > 0) {
      const firstVerse = pageData.verses[0];
      document.getElementById('current-surah-title').innerText = `${firstVerse.surahName}`;
    }
  }

  if (pageNum < 604) {
    const nextThreeDigit = String(pageNum + 1).padStart(3, '0');
    const nextImg = new Image();
    nextImg.src = `${IMAGE_BASE_URL}${nextThreeDigit}.png`;
  }
}

function flipPage(targetPage) {
  if (targetPage === currentPage || targetPage < 1 || targetPage > 604) return;

  const wrapper = document.getElementById('mushaf-page-wrapper');
  wrapper.style.transform = 'translateX(-30px)';
  wrapper.style.opacity = '0';
  
  setTimeout(() => {
    currentPage = targetPage;
    spokenHistory = ''; // إعادة ضبط الكلمات المنطوقة عند الانتقال لصفحة جديدة لتفادي المطابقات الخاطئة من الصفحة السابقة
    displayPage(currentPage);
    wrapper.style.transform = 'translateX(30px)';
    
    setTimeout(() => {
      wrapper.style.transform = 'translateX(0)';
      wrapper.style.opacity = '1';
    }, 50);

  }, 250);
}

function flipPageManual(direction) {
  const target = currentPage + direction;
  if (target >= 1 && target <= 604) {
    spokenHistory = ''; // إعادة ضبط الكلمات المنطوقة عند الانتقال اليدوي لتفادي المطابقة القديمة
    if (quranDatabase) {
      const pageData = quranDatabase[target - 1];
      if (pageData && pageData.verses.length > 0) {
        lastMatchedVerse = {
          surah: pageData.verses[0].surah,
          ayah: pageData.verses[0].ayah
        };
      }
    }
    flipPage(target);
  }
}

// ==================== تحديث واجهات الاستخدام (UI Update) ==================== //
function updatePrayerStatusUI() {
  const badgeText = document.getElementById('prayer-state-text');
  
  if (prayerState === STATE_WAITING_FATIHA) {
    badgeText.innerText = `الركعة ${rakahCount} - بانتظار الفاتحة`;
  } else if (prayerState === STATE_RECITING) {
    badgeText.innerText = `الركعة ${rakahCount} - تلاوة نشطة`;
  } else if (prayerState === STATE_RUKU) {
    badgeText.innerText = `وضع الركوع والسجود`;
  }
}

function showStatusMessage(msg, color) {
  console.log(`[Status Message] ${msg} (${color})`);
}

// ==================== الحفاظ على الشاشة مضيئة (Wake Lock API) ==================== //
async function requestWakeLock() {
  if ('wakeLock' in navigator) {
    try {
      wakeLock = await navigator.wakeLock.request('screen');
      console.log('[Wake Lock] Screen remains active successfully.');
    } catch (err) {
      console.error('[Wake Lock] Failed to acquire screen lock:', err.message);
    }
  }
}

function releaseWakeLock() {
  if (wakeLock !== null) {
    wakeLock.release()
      .then(() => {
        wakeLock = null;
        console.log('[Wake Lock] Screen lock released.');
      });
  }
}

document.addEventListener('visibilitychange', async () => {
  if (wakeLock !== null && document.visibilityState === 'visible' && isPrayerActive) {
    await requestWakeLock();
  }
});

// ==================== إدارة تخزين البيانات أوفلاين (Download Manager) ==================== //

// 1. التحقق من كاش الصور
function checkOfflineCacheStatus() {
  if (!('caches' in window)) return;
  
  caches.has('mushaf-qiyam-images-v1').then(exists => {
    if (exists) {
      caches.open('mushaf-qiyam-images-v1').then(cache => {
        cache.keys().then(keys => {
          if (keys.length >= 600) {
            document.getElementById('cache-status-label').innerText = 'المصحف محمل بالكامل أوفلاين';
            document.getElementById('cache-percent-label').innerText = '100%';
            document.getElementById('cache-progress-bar').style.width = '100%';
            document.getElementById('btn-download-quran').innerText = 'تحديث صور المصحف المخزنة محلياً';
          }
        });
      });
    }
  });
}

function downloadAllQuranImages() {
  const btn = document.getElementById('btn-download-quran');
  btn.disabled = true;
  btn.innerText = 'جاري الاتصال بخادم الصور...';

  const urlsToCache = [];
  for (let i = 1; i <= 604; i++) {
    const threeDigitPage = String(i).padStart(3, '0');
    urlsToCache.push(`${IMAGE_BASE_URL}${threeDigitPage}.png`);
  }

  function sendCacheMessage() {
    if (navigator.serviceWorker && navigator.serviceWorker.controller) {
      navigator.serviceWorker.controller.postMessage({
        action: 'cache-images',
        urls: urlsToCache
      });
      document.getElementById('cache-status-label').innerText = 'جاري تحميل الصور محلياً...';
    } else if (navigator.serviceWorker) {
      // الـ Service Worker مسجل لكن لم يتولَّ التحكم بعد، انتظر حتى يصبح جاهزاً
      navigator.serviceWorker.ready.then((registration) => {
        // أرسل رسالة إلى الـ SW عبر الـ active worker مباشرة
        if (registration.active) {
          registration.active.postMessage({
            action: 'cache-images',
            urls: urlsToCache
          });
          document.getElementById('cache-status-label').innerText = 'جاري تحميل الصور محلياً...';
        } else {
          showStatusMessage('فشل تفعيل خادم التخزين، يرجى إعادة تحميل الصفحة.', 'red');
          btn.disabled = false;
          btn.innerText = 'تحميل صفحات المصحف (حوالي 85 ميجا)';
        }
      });
    } else {
      showStatusMessage('المتصفح لا يدعم التخزين أوفلاين.', 'red');
      btn.disabled = false;
      btn.innerText = 'تحميل صفحات المصحف (حوالي 85 ميجا)';
    }
  }

  sendCacheMessage();
}


function updateDownloadUI(progress, current, total) {
  document.getElementById('cache-status-label').innerText = `جاري تحميل الصور محلياً (${current} من ${total})...`;
  document.getElementById('cache-percent-label').innerText = `${progress}%`;
  document.getElementById('cache-progress-bar').style.width = `${progress}%`;
}

function onDownloadCompleted() {
  document.getElementById('cache-status-label').innerText = 'اكتمل تحميل الصور أوفلاين!';
  document.getElementById('cache-percent-label').innerText = '100%';
  document.getElementById('cache-progress-bar').style.width = '100%';
  
  const btn = document.getElementById('btn-download-quran');
  btn.disabled = false;
  btn.innerText = 'تحديث صور المصحف المخزنة محلياً';
  
  showStatusMessage('اكتمل تحميل جميع صفحات المصحف أوفلاين بنجاح!', 'green');
}

// 2. التحقق من كاش الموديل الصوتي أوفلاين
function checkOfflineModelStatus() {
  if (!('caches' in window)) return;

  const checkCache = (cacheName) => {
    return caches.open(cacheName).then(cache => {
      return cache.keys().then(keys => {
        return keys.some(request => request.url.includes('whisper-tiny-ar-quran-onnx'));
      });
    }).catch(() => false);
  };

  const isLocalhost = window.location.hostname === 'localhost' || 
                      window.location.hostname === '127.0.0.1' || 
                      window.location.protocol === 'file:';

  Promise.all([
    checkCache('transformers-cache'),
    checkCache('mushaf-qiyam-model-v2'),
    isLocalhost ? fetch('models/whisper-tiny-ar-quran-onnx/config.json', { method: 'HEAD' })
      .then(res => res.ok)
      .catch(() => false) : Promise.resolve(false)
  ]).then(([cachedTransformers, cachedSW, localExists]) => {
    if (cachedTransformers || cachedSW || localExists) {
      document.getElementById('model-status-label').innerText = 'الموديل الصوتي محمل بالكامل أوفلاين';
      document.getElementById('model-percent-label').innerText = '100%';
      document.getElementById('model-progress-bar').style.width = '100%';
      document.getElementById('btn-download-model').innerText = 'تحديث الموديل الصوتي المخزن محلياً';
      isModelCached = true;
      updateStartButtonState();
    } else {
      document.getElementById('model-status-label').innerText = 'الموديل الصوتي غير محمل محلياً';
      document.getElementById('model-percent-label').innerText = '0%';
      document.getElementById('model-progress-bar').style.width = '0%';
      document.getElementById('btn-download-model').innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 75 ميجا)';
      isModelCached = false;
      updateStartButtonState();
    }
  });
}

// تحميل الموديل الصوتي وتخزينه عبر خيط المعالجة الخلفي (Transformers.js)
async function downloadVoskModel() {
  if (isModelLoading) return;
  
  const btn = document.getElementById('btn-download-model');
  btn.disabled = true;
  btn.innerText = 'جاري تحميل مكتبة الصوت...';
  
  document.getElementById('model-status-label').innerText = 'جاري تحميل مكتبة التعرف الصوتي...';
  document.getElementById('model-percent-label').innerText = '0%';
  document.getElementById('model-progress-bar').style.width = '0%';

  isModelLoading = true;

  // تهيئة الـ Web Worker (إعادة إنشائه في كل مرة لتجنب حالة معطوبة)
  if (whisperWorker) {
    whisperWorker.terminate();
    whisperWorker = null;
  }
  
  whisperWorker = new Worker('whisper-worker.js');

  const fileProgress = {};
  
  // مؤقت للكشف عن توقف الخيط الخلفي بالكامل (30 ثانية بدون أي رسالة)
  let workerTimeout = setTimeout(() => {
    if (isModelLoading) {
      isModelLoading = false;
      btn.disabled = false;
      btn.innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 75 ميجا)';
      document.getElementById('model-status-label').innerText = 'انقطع الاتصال بخيط المعالجة. أعد المحاولة.';
      showStatusMessage('فشل الاتصال بخيط المعالجة الصوتي. حاول مرة أخرى.', 'red');
    }
  }, 30000);

  // التقاط أخطاء الخيط الخلفي الصامتة
  whisperWorker.onerror = (event) => {
    clearTimeout(workerTimeout);
    isModelLoading = false;
    btn.disabled = false;
    btn.innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 75 ميجا)';
    
    const errorMsg = event.message || 'خطأ غير معروف في خيط المعالجة';
    document.getElementById('model-status-label').innerText = `خطأ: ${errorMsg}`;
    showStatusMessage(`فشل خيط المعالجة الصوتي: ${errorMsg}`, 'red');
    console.error('[Worker Error Event]', event);
  };

  whisperWorker.onmessage = (e) => {
    const { type, file, progress, loaded, total, error, text } = e.data;
    
    // إعادة ضبط المؤقت مع كل رسالة واردة (الخيط الخلفي لا يزال حياً)
    clearTimeout(workerTimeout);
    workerTimeout = setTimeout(() => {
      if (isModelLoading) {
        isModelLoading = false;
        btn.disabled = false;
        btn.innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 75 ميجا)';
        document.getElementById('model-status-label').innerText = 'توقف التحميل. أعد المحاولة.';
        showStatusMessage('توقف تحميل الموديل الصوتي. حاول مرة أخرى.', 'red');
      }
    }, 60000); // 60 ثانية أثناء التحميل الفعلي

    if (type === 'loading_started') {
      document.getElementById('model-status-label').innerText = 'بدأ تحميل ملفات الموديل الصوتي...';
      btn.innerText = 'جاري تحميل الموديل...';
      
    } else if (type === 'file_initiate') {
      document.getElementById('model-status-label').innerText = `جاري تحميل: ${file || 'ملف'}...`;
      
    } else if (type === 'progress') {
      fileProgress[file] = { loaded, total };
      
      // حساب إجمالي نسبة التحمل الفعلي
      let totalLoaded = 0;
      let totalBytes = 0;
      for (const f in fileProgress) {
        totalLoaded += fileProgress[f].loaded;
        totalBytes += fileProgress[f].total || fileProgress[f].loaded;
      }
      
      const pct = totalBytes > 0 ? Math.min(99, Math.round((totalLoaded / totalBytes) * 100)) : 0;
      const loadedMB = (totalLoaded / (1024 * 1024)).toFixed(1);
      const totalMB = totalBytes > 0 ? (totalBytes / (1024 * 1024)).toFixed(1) : '75.0';
      
      document.getElementById('model-status-label').innerText = `جاري تحميل الموديل الصوتي (${loadedMB}MB من ${totalMB}MB)...`;
      document.getElementById('model-percent-label').innerText = `${pct}%`;
      document.getElementById('model-progress-bar').style.width = `${pct}%`;
      
    } else if (type === 'ready') {
      clearTimeout(workerTimeout);
      isModelLoading = false;
      isModelCached = true;
      
      document.getElementById('model-status-label').innerText = 'اكتمل تحميل الموديل الصوتي أوفلاين!';
      document.getElementById('model-percent-label').innerText = '100%';
      document.getElementById('model-progress-bar').style.width = '100%';
      
      btn.disabled = false;
      btn.innerText = 'تحديث الموديل الصوتي المخزن محلياً';
      
      updateStartButtonState();
      showStatusMessage('تم تحميل الموديل الصوتي محلياً وجاهز للتشغيل بالمسجد!', 'green');
      
    } else if (type === 'error') {
      clearTimeout(workerTimeout);
      isModelLoading = false;
      btn.disabled = false;
      btn.innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 75 ميجا)';
      document.getElementById('model-status-label').innerText = `خطأ: ${error}`;
      showStatusMessage(`فشل تحميل الموديل الصوتي: ${error}`, 'red');
      console.error('[Whisper Model Error]', error);
      
    } else if (type === 'result') {
      if (isPrayerActive && text && text.trim().length > 0) {
        console.log('[Whisper Result]:', text);
        updateSpokenHistory(text);
        handleSpokenWords(spokenHistory);
      }
    }
  };

  // إرسال رسالة البدء في تحميل الموديل للخلفية
  whisperWorker.postMessage({ type: 'load' });
}

// ==================== إدارة التحديثات التلقائية (GitHub Auto Update) ==================== //
function checkForUpdates() {
  fetch('version.json?nocache=' + Date.now())
    .then(res => res.json())
    .then(serverInfo => {
      fetch('version.json')
        .then(res => res.json())
        .then(localInfo => {
          console.log(`Local Version: ${localInfo.version}, Server Version: ${serverInfo.version}`);
          document.getElementById('app-version-label').innerText = `إصدار ${localInfo.version}`;
          
          if (compareVersions(serverInfo.version, localInfo.version) > 0) {
            document.getElementById('update-toast').classList.add('show');
          }
        });
    })
    .catch(err => console.log('Update check failed (normal if running local/offline):', err));
}

function compareVersions(v1, v2) {
  const parts1 = v1.split('.').map(Number);
  const parts2 = v2.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (parts1[i] > parts2[i]) return 1;
    if (parts1[i] < parts2[i]) return -1;
  }
  return 0;
}
