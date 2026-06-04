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

// متغيرات محرك صوت Vosk-browser
let voskModel = null;
let voskRecognizer = null;
let audioContext = null;
let mediaStream = null;
let audioSource = null;
let audioProcessor = null;
let isMicGranted = false;
let isModelCached = false;

// علامات تتبع الوقوف والركعات
let lastMatchedVerse = { surah: 1, ayah: 0 };
let checkpointVerse = { page: 1, surah: 1, ayah: 0 };
let rakahCount = 1; // عداد الركعات في الصلاة الحالية

// ثوابت روابط الصور والموديل الصوتي
const IMAGE_BASE_URL = 'https://raw.githubusercontent.com/GovarJabbar/Quran-PNG/master/png/';
const MODEL_URL = 'https://alphacephei.com/vosk/models/vosk-model-small-ar-0.22.zip';

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
        
        // الاستماع لرسائل التقدم في التحميل أوفلاين من السيرفس وركر (خاص بالصور)
        navigator.serviceWorker.addEventListener('message', (event) => {
          if (event.data) {
            if (event.data.type === 'cache-progress') {
              updateDownloadUI(event.data.progress, event.data.cachedCount, event.data.totalCount);
            } else if (event.data.type === 'cache-completed') {
              onDownloadCompleted();
            }
          }
        });
      })
      .catch((err) => console.error('Service Worker registration failed:', err));
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

// تحديث إمكانية بدء الصلاة (الميكروفون + الموديل الصوتي يجب توفرهما)
function updateStartButtonState() {
  const startBtn = document.getElementById('btn-start-prayer');
  startBtn.disabled = !(isMicGranted && isModelCached);
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
function normalizeArabic(text) {
  if (!text) return '';
  return text
    .replace(/[\u064B-\u0652]/g, '')
    .replace(/[\u0615-\u061A\u06D6-\u06E2\u06E4\u06E7\u06E8\u06EA-\u06EC]/g, '')
    .replace(/[أإآ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'هـ')
    .replace(/[^\u0600-\u06FF\s]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
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

// دالة مساعدة موحدة للبحث عن تطابق في آيات صفحة محددة (تم حل تكرار الكود المكتشف بـ Fallow)
function searchPageForMatch(pageData, cleanQuery, pageNumber) {
  let bestMatch = null;
  pageData.verses.forEach((verse, index) => {
    const cleanVerse = normalizeArabic(verse.text);
    if (cleanVerse.includes(cleanQuery) || checkSubSequenceMatch(cleanVerse, cleanQuery)) {
      bestMatch = {
        verse: verse,
        index: index,
        page: pageNumber
      };
    }
  });
  return bestMatch;
}

// مطابقة التلاوة مع نصوص آيات الصفحة المفتوحة
function matchRecitationWithQuran(cleanSpoken) {
  if (!quranDatabase) return;

  const pageData = quranDatabase[currentPage - 1];
  const nextRawPageData = quranDatabase[currentPage]; // الصفحة التالية
  
  if (!pageData) return;

  const spokenTokens = cleanSpoken.split(' ');
  const queryWordsCount = Math.min(10, spokenTokens.length);
  const cleanQuery = spokenTokens.slice(-queryWordsCount).join(' ');

  // 1. نبحث أولاً في الصفحة الحالية
  let bestMatch = searchPageForMatch(pageData, cleanQuery, currentPage);
  let isNextPageMatch = false;

  // 2. إذا لم نجد مطابقة، نبحث في أول آيتين من الصفحة التالية للتأكد من تقليب الصفحة الذكي
  if (!bestMatch && nextRawPageData) {
    const firstTwoVersesData = {
      verses: nextRawPageData.verses.slice(0, 2)
    };
    bestMatch = searchPageForMatch(firstTwoVersesData, cleanQuery, currentPage + 1);
    if (bestMatch) {
      isNextPageMatch = true;
    }
  }

  // 3. إذا تم العثور على مطابقة موثقة
  if (bestMatch) {
    lastMatchedVerse = {
      surah: bestMatch.verse.surah,
      ayah: bestMatch.verse.ayah
    };

    console.log(`Matched: Surah ${bestMatch.verse.surah}, Ayah ${bestMatch.verse.ayah} on page ${bestMatch.page}`);

    if (isNextPageMatch || bestMatch.page > currentPage) {
      flipPage(bestMatch.page);
    }
  }
}

// خوارزمية مطابقة تسلسلية بسيطة (Sub-sequence match) في حالة سقوط بعض الحروف الصامتة
function checkSubSequenceMatch(verseText, queryText) {
  const queryTokens = queryText.split(' ');
  if (queryTokens.length < 3) return false;

  let lastIndex = -1;
  let matchCount = 0;

  for (let word of queryTokens) {
    const index = verseText.indexOf(word, lastIndex + 1);
    if (index > lastIndex) {
      lastIndex = index;
      matchCount++;
    }
  }

  return (matchCount / queryTokens.length) >= 0.7;
}

// ==================== دورة حياة الصلاة وإدارة الحالات ==================== //

// بدء الصلاة وتهيئة Vosk-browser أوفلاين
async function startPrayerSession() {
  if (isPrayerActive) return;

  const statusToastText = document.getElementById('recognized-words');
  statusToastText.innerText = 'جاري تهيئة محرك الصوت المحلي...';

  // الانتقال لشاشة الصلاة
  document.getElementById('setup-screen').classList.remove('active');
  document.getElementById('prayer-screen').classList.add('active');

  // عرض صفحة البداية المختارة
  displayPage(currentPage);

  isPrayerActive = true;
  prayerState = STATE_WAITING_FATIHA;
  rakahCount = 1;
  updatePrayerStatusUI();

  // إبقاء الشاشة مضيئة
  requestWakeLock();

  try {
    // تحميل الموديل الصوتي من الكاش/المستند المحلي
    if (!voskModel) {
      console.log('[Vosk] Loading model from cached URL:', MODEL_URL);
      voskModel = await Vosk.createModel(MODEL_URL);
    }

    // تهيئة مسجل الصوت ومجرى الميكروفون
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    
    // إنشاء سياق الصوت (Audio Context) بتردد 16000Hz (المفضل لـ Vosk)
    audioContext = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 16000 });
    
    // إنشاء المستمع
    voskRecognizer = new voskModel.KaldiRecognizer(16000);
    
    // ربط أحداث التعرف الصوتي لـ Vosk
    voskRecognizer.on('result', (message) => {
      const text = message.result.text;
      if (text && text.trim().length > 0) {
        console.log('[Vosk Final Result]:', text);
        handleSpokenWords(text);
      }
    });

    voskRecognizer.on('partialresult', (message) => {
      const partialText = message.result.partial;
      if (partialText && partialText.trim().length > 0) {
        // تحديث النص اللحظي على الشاشة فوراً لمساندة الإمام بصرياً
        document.getElementById('recognized-words').innerText = partialText;
        handleSpokenWords(partialText);
      }
    });

    // ربط خط الأنابيب الصوتي (Audio Pipeline)
    audioSource = audioContext.createMediaStreamSource(mediaStream);
    audioProcessor = audioContext.createScriptProcessor(4096, 1, 1);
    
    audioProcessor.onaudioprocess = (event) => {
      if (isPrayerActive && prayerState !== STATE_RUKU) {
        const floatData = event.inputBuffer.getChannelData(0);
        voskRecognizer.acceptWaveform(floatData);
      }
    };

    audioSource.connect(audioProcessor);
    audioProcessor.connect(audioContext.destination);

    updateAudioIndicator(true);
    statusToastText.innerText = 'بانتظار قراءة الفاتحة...';
    console.log('[Vosk] Listening initialized successfully');

  } catch (error) {
    console.error('[Vosk] Initialization error:', error);
    statusToastText.innerText = 'فشل تشغيل الصوت المحلي. اضغط خروج لإعادة التهيئة.';
    updateAudioIndicator(false);
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
  updatePrayerStatusUI();

  currentPage = checkpointVerse.page;
  displayPage(currentPage);

  document.getElementById('recognized-words').innerText = 'انتظار قراءة الفاتحة للركعة التالية...';
}

// إنهاء الصلاة والعودة للتهيئة
function stopPrayerSession() {
  isPrayerActive = false;
  prayerState = STATE_IDLE;

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
    if (voskRecognizer) {
      voskRecognizer.remove();
      voskRecognizer = null;
    }
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
  const imageUrl = `${IMAGE_BASE_URL}${threeDigitPage.slice(0,1)}/${threeDigitPage}.png`;

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
    nextImg.src = `${IMAGE_BASE_URL}${nextThreeDigit.slice(0,1)}/${nextThreeDigit}.png`;
  }
}

function flipPage(targetPage) {
  if (targetPage === currentPage || targetPage < 1 || targetPage > 604) return;

  const wrapper = document.getElementById('mushaf-page-wrapper');
  wrapper.style.transform = 'translateX(-30px)';
  wrapper.style.opacity = '0';
  
  setTimeout(() => {
    currentPage = targetPage;
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
    urlsToCache.push(`${IMAGE_BASE_URL}${threeDigitPage.slice(0,1)}/${threeDigitPage}.png`);
  }

  if (navigator.serviceWorker && navigator.serviceWorker.controller) {
    navigator.serviceWorker.controller.postMessage({
      action: 'cache-images',
      urls: urlsToCache
    });
  } else {
    showStatusMessage('جاري تهيئة خادم التحميل الصامت، يرجى المحاولة بعد قليل.', 'yellow');
    btn.disabled = false;
    btn.innerText = 'تحميل صفحات المصحف (حوالي 85 ميجا)';
  }
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

  caches.open('mushaf-qiyam-model-v1').then(cache => {
    cache.match(MODEL_URL).then(response => {
      if (response) {
        document.getElementById('model-status-label').innerText = 'الموديل الصوتي محمل بالكامل أوفلاين';
        document.getElementById('model-percent-label').innerText = '100%';
        document.getElementById('model-progress-bar').style.width = '100%';
        document.getElementById('btn-download-model').innerText = 'تحديث الموديل الصوتي المخزن محلياً';
        isModelCached = true;
        updateStartButtonState();
      }
    });
  });
}

// تحميل الموديل الصوتي مع إشهار شريط التقدم الفعلي (Fetch Progress Stream)
async function downloadVoskModel() {
  const btn = document.getElementById('btn-download-model');
  btn.disabled = true;
  btn.innerText = 'جاري الاتصال بخادم الصوت...';
  
  document.getElementById('model-status-label').innerText = 'جاري الاتصال بخادم تحميل الموديل...';
  document.getElementById('model-percent-label').innerText = '0%';
  document.getElementById('model-progress-bar').style.width = '0%';

  try {
    const response = await fetch(MODEL_URL);
    if (!response.ok) throw new Error('Network response was not ok');

    const contentLength = response.headers.get('content-length');
    if (!contentLength) {
      throw new Error('Content-Length response header is missing');
    }
    const totalBytes = parseInt(contentLength, 10);
    let loadedBytes = 0;

    const reader = response.body.getReader();
    const chunks = [];

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      loadedBytes += value.length;
      
      const progress = Math.min(100, Math.round((loadedBytes / totalBytes) * 100));
      updateModelDownloadUI(progress, loadedBytes, totalBytes);
    }

    console.log('[Vosk Model] Assembly response chunks...');
    const modelBlob = new Blob(chunks);
    const cachedResponse = new Response(modelBlob, {
      status: 200,
      statusText: 'OK',
      headers: { 
        'Content-Type': 'application/zip',
        'Content-Length': modelBlob.size.toString()
      }
    });

    console.log('[Vosk Model] Saving model to mushaf-qiyam-model-v1 cache...');
    const modelCache = await caches.open('mushaf-qiyam-model-v1');
    await modelCache.put(MODEL_URL, cachedResponse);

    onModelDownloadCompleted();

  } catch (error) {
    console.error('Error downloading Vosk model:', error);
    btn.disabled = false;
    btn.innerText = 'تحميل الموديل الصوتي أوفلاين (حوالي 45 ميجا)';
    document.getElementById('model-status-label').innerText = 'فشل التحميل، يرجى التحقق من اتصال الإنترنت.';
    showStatusMessage('فشل تحميل الموديل الصوتي، يرجى المحاولة لاحقاً.', 'red');
  }
}

function updateModelDownloadUI(progress, loaded, total) {
  const currentMB = (loaded / (1024 * 1024)).toFixed(1);
  const totalMB = (total / (1024 * 1024)).toFixed(1);
  
  document.getElementById('model-status-label').innerText = `جاري تحميل الموديل الصوتي (${currentMB}MB من ${totalMB}MB)...`;
  document.getElementById('model-percent-label').innerText = `${progress}%`;
  document.getElementById('model-progress-bar').style.width = `${progress}%`;
}

function onModelDownloadCompleted() {
  document.getElementById('model-status-label').innerText = 'اكتمل تحميل الموديل الصوتي أوفلاين!';
  document.getElementById('model-percent-label').innerText = '100%';
  document.getElementById('model-progress-bar').style.width = '100%';
  
  const btn = document.getElementById('btn-download-model');
  btn.disabled = false;
  btn.innerText = 'تحديث الموديل الصوتي المخزن محلياً';
  
  isModelCached = true;
  updateStartButtonState();
  
  showStatusMessage('تم تحميل الموديل الصوتي محلياً وجاهز للتشغيل بالمسجد!', 'green');
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
