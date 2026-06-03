/**
 * ==========================================================================
 * المنطق البرمجي الأساسي لتطبيق "مصحف القيام" (PWA)
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
let speechRecognition = null;
let wakeLock = null;
let isAudioMuted = false;

// علامات تتبع الوقوف والركعات
let lastMatchedVerse = { surah: 1, ayah: 0 };
let checkpointVerse = { page: 1, surah: 1, ayah: 0 };
let rakahCount = 1; // عداد الركعات في الصلاة الحالية

// ثوابت روابط الصور (مستودع GovarJabbar/Quran-PNG)
const IMAGE_BASE_URL = 'https://raw.githubusercontent.com/GovarJabbar/Quran-PNG/master/png/';

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
    
    // 4. ربط أحداث أزرار وعناصر الواجهة
    setupEventListeners();
    
    // 5. تهيئة محرك التعرف الصوتي
    initSpeechRecognition();
    
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
        
        // الاستماع لرسائل التقدم في التحميل أوفلاين من السيرفس وركر
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

  // استخراج السور الفريدة وأول صفحة تظهر فيها
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

  // ملء قائمة السور مرتبة
  const sortedSurahs = Array.from(surahsMap.values()).sort((a, b) => a.id - b.id);
  surahSelect.innerHTML = sortedSurahs.map(s => 
    `<option value="${s.id}" data-page="${s.firstPage}">سورة ${s.name.replace('سُورَةُ ', '')}</option>`
  ).join('');

  // ملء قائمة الصفحات (1 - 604)
  const pagesHTML = [];
  for (let i = 1; i <= 604; i++) {
    pagesHTML.push(`<option value="${i}">الصفحة ${i}</option>`);
  }
  pageSelect.innerHTML = pagesHTML.join('');

  // ربط اختيار السورة بتحديث رقم الصفحة تلقائياً
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
    
    // سحب من اليمين لليسار (تقليب للصفحة التالية)
    if (diffX < -60) {
      flipPageManual(1);
    }
    // سحب من اليسار لليمين (تقليب للصفحة السابقة)
    else if (diffX > 60) {
      flipPageManual(-1);
    }
  }, { passive: true });

  // نافذة التحديث
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

// ==================== صلاحيات الميكروفون والتعرف الصوتي ==================== //
function requestMicrophonePermission() {
  navigator.mediaDevices.getUserMedia({ audio: true })
    .then((stream) => {
      // إيقاف الستريم فوراً، نحتاج فقط للتأكد من موافقة المستخدم
      stream.getTracks().forEach(track => track.stop());
      
      const dot = document.getElementById('mic-status-dot');
      const text = document.getElementById('mic-status-text');
      
      dot.className = 'pulse-dot green';
      text.innerText = 'الميكروفون مفعل وجاهز لبدء الصلاة';
      document.getElementById('btn-start-prayer').disabled = false;
      document.getElementById('btn-grant-mic').style.display = 'none';
      
      showStatusMessage('تم تفعيل الميكروفون بنجاح!', 'green');
    })
    .catch((err) => {
      console.error('Microphone access denied:', err);
      showStatusMessage('عذراً، يجب تفعيل الميكروفون لتتبع التلاوة آلياً.', 'red');
    });
}

// تهيئة محرك الاستماع الصوتي
function initSpeechRecognition() {
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  
  if (!SpeechRecognition) {
    showStatusMessage('عذراً، متصفحك الحالي لا يدعم التعرف الصوتي المدمج. يرجى استخدام متصفح Google Chrome.', 'red');
    return;
  }

  speechRecognition = new SpeechRecognition();
  speechRecognition.continuous = true;
  speechRecognition.interimResults = true;
  speechRecognition.lang = 'ar-SA'; // نبرة لغة عربية فصحى

  // عند استقبال كلام منطوق
  speechRecognition.onresult = (event) => {
    let interimTranscript = '';
    let finalTranscript = '';

    for (let i = event.resultIndex; i < event.results.length; ++i) {
      if (event.results[i].isFinal) {
        finalTranscript += event.results[i][0].transcript;
      } else {
        interimTranscript += event.results[i][0].transcript;
      }
    }

    const currentTranscript = finalTranscript || interimTranscript;
    if (currentTranscript.trim().length > 0) {
      handleSpokenWords(currentTranscript);
    }
  };

  // معالجة توقف الخدمة التلقائي (الأندرويد يوقف الميكروفون عند الصمت الطويل)
  speechRecognition.onend = () => {
    if (isPrayerActive && prayerState !== STATE_RUKU) {
      console.log('[Speech Recognition] Restarting due to timeout...');
      try {
        speechRecognition.start();
        updateAudioIndicator(true);
      } catch (err) {
        console.error('Error restarting speech recognition:', err);
      }
    }
  };

  speechRecognition.onerror = (event) => {
    console.error('Speech recognition error:', event.error);
    if (event.error === 'not-allowed') {
      showStatusMessage('تم حظر الميكروفون. يرجى تفعيله من إعدادات المتصفح.', 'red');
    }
  };
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
  // 1. تحديث المؤشر النصي أسفل الشاشة
  const toastText = document.getElementById('recognized-words');
  toastText.innerText = words;

  // 2. تطهير وتنظيف النص المنطوق
  const cleanSpoken = normalizeArabic(words);

  // 3. التحقق من حالات الصلاة الخاصة أولاً (التكبير، الفاتحة)
  if (detectTakbeer(cleanSpoken)) {
    triggerRukuState();
    return;
  }

  // إذا كنا ننتظر الفاتحة لبدء الركعة
  if (prayerState === STATE_WAITING_FATIHA) {
    if (detectFatiha(cleanSpoken)) {
      transitionToReciting();
    }
    return;
  }

  // 4. مطابقة النص مع آيات الصفحة الحالية والصفحة التالية (Fuzzy Matching)
  if (prayerState === STATE_RECITING) {
    matchRecitationWithQuran(cleanSpoken);
  }
}

// خوارزمية تطبيع الحروف العربية (تجريد التشكيل وتوحيد الحروف المتقاربة)
function normalizeArabic(text) {
  if (!text) return '';
  return text
    // إزالة التشكيل وعلامات التجويد
    .replace(/[\u064B-\u0652]/g, '')
    // إزالة علامات الوقف القرآنية
    .replace(/[\u0615-\u061A\u06D6-\u06E2\u06E4\u06E7\u06E8\u06EA-\u06EC]/g, '')
    // توحيد همزات الألف
    .replace(/[أإآ]/g, 'ا')
    // توحيد الياء والألف المقصورة
    .replace(/ى/g, 'ي')
    // توحيد التاء المربوطة والهاء
    .replace(/ة/g, 'هـ')
    // إزالة الحروف غير العربية والمسافات الزائدة
    .replace(/[^\u0600-\u06FF\s]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

// التحقق من كشف التكبير ("الله أكبر") للانتقال للركوع
function detectTakbeer(cleanText) {
  // نبحث عن كلمات التكبير في آخر جزء من النص المنطوق
  const tokens = cleanText.split(' ');
  const lastWords = tokens.slice(-3).join(' '); // آخر 3 كلمات فقط للسرعة والدقة
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

  // يكفي مطابقة جملة واحدة قوية من الفاتحة لنعرف أن الإمام يقرأها
  return fatihaKeywords.some(keyword => cleanText.includes(normalizeArabic(keyword)));
}

// مطابقة التلاوة مع نصوص آيات الصفحة المفتوحة
function matchRecitationWithQuran(cleanSpoken) {
  if (!quranDatabase) return;

  const pageData = quranDatabase[currentPage - 1];
  const nextRawPageData = quranDatabase[currentPage]; // الصفحة التالية
  
  if (!pageData) return;

  // نأخذ آخر كلمات نطقها الإمام (مثلاً آخر 8 كلمات) لمطابقتها لعدم تشتيت الخوارزمية
  const spokenTokens = cleanSpoken.split(' ');
  const queryWordsCount = Math.min(10, spokenTokens.length);
  const cleanQuery = spokenTokens.slice(-queryWordsCount).join(' ');

  let bestMatch = null;
  let isNextPageMatch = false;

  // 1. نبحث أولاً في الصفحة الحالية
  pageData.verses.forEach((verse, index) => {
    const cleanVerse = normalizeArabic(verse.text);
    if (cleanVerse.includes(cleanQuery) || checkSubSequenceMatch(cleanVerse, cleanQuery)) {
      bestMatch = {
        verse: verse,
        index: index,
        page: currentPage
      };
    }
  });

  // 2. إذا لم نجد مطابقة، نبحث في أول آيتين من الصفحة التالية للتأكد من تقليب الصفحة الذكي
  if (!bestMatch && nextRawPageData) {
    const firstTwoVerses = nextRawPageData.verses.slice(0, 2);
    firstTwoVerses.forEach((verse, index) => {
      const cleanVerse = normalizeArabic(verse.text);
      if (cleanVerse.includes(cleanQuery) || checkSubSequenceMatch(cleanVerse, cleanQuery)) {
        bestMatch = {
          verse: verse,
          index: index,
          page: currentPage + 1
        };
        isNextPageMatch = true;
      }
    });
  }

  // 3. إذا تم العثور على مطابقة موثقة
  if (bestMatch) {
    lastMatchedVerse = {
      surah: bestMatch.verse.surah,
      ayah: bestMatch.verse.ayah
    };

    console.log(`Matched: Surah ${bestMatch.verse.surah}, Ayah ${bestMatch.verse.ayah} on page ${bestMatch.page}`);

    // تحديث الشاشة بالصفحة الصحيحة إذا كانت المطابقة في الصفحة التالية
    if (isNextPageMatch || bestMatch.page > currentPage) {
      flipPage(bestMatch.page);
    }
  }
}

// خوارزمية مطابقة تسلسلية بسيطة (Sub-sequence match) في حالة سقوط بعض الحروف الصامتة
function checkSubSequenceMatch(verseText, queryText) {
  const queryTokens = queryText.split(' ');
  if (queryTokens.length < 3) return false; // نحتاج 3 كلمات على الأقل للتثبت

  // نبحث عن وجود الكلمات بنفس الترتيب داخل الآية
  let lastIndex = -1;
  let matchCount = 0;

  for (let word of queryTokens) {
    const index = verseText.indexOf(word, lastIndex + 1);
    if (index > lastIndex) {
      lastIndex = index;
      matchCount++;
    }
  }

  // إذا تطابق 70% من الكلمات بنفس الترتيب، نعتبرها مطابقة مقبولة
  return (matchCount / queryTokens.length) >= 0.7;
}

// ==================== دورة حياة الصلاة وإدارة الحالات ==================== //

// 1. بدء الصلاة
function startPrayerSession() {
  if (!speechRecognition) return;

  isPrayerActive = true;
  prayerState = STATE_WAITING_FATIHA;
  rakahCount = 1;

  // إبقاء الشاشة مضيئة
  requestWakeLock();

  // الانتقال لشاشة الصلاة
  document.getElementById('setup-screen').classList.remove('active');
  document.getElementById('prayer-screen').classList.add('active');

  // عرض صفحة البداية المختارة
  displayPage(currentPage);

  // تحديث شريط معلومات الواجهة
  updatePrayerStatusUI();

  // تشغيل الاستماع الصوتي
  try {
    speechRecognition.start();
    updateAudioIndicator(true);
  } catch (e) {
    console.log('Recognition already active');
  }

  showStatusMessage('تم بدء الصلاة وتفعيل وضع الانتظار (تلاوة الفاتحة)...', 'green');
}

// الانتقال لوضع القراءة النشط بعد الفاتحة
function transitionToReciting() {
  prayerState = STATE_RECITING;
  updatePrayerStatusUI();
  
  // تصفير مؤشر الكلمات المنطوقة
  document.getElementById('recognized-words').innerText = 'تم كشف الفاتحة - جاري متابعة السورة...';
  console.log('[State Machine] Transition to Reciting Surah');
}

// 2. كشف الركوع وحفظ علامة التوقف
function triggerRukuState() {
  prayerState = STATE_RUKU;
  updatePrayerStatusUI();

  // حفظ موضع الوقوف الفعلي (Checkpoint)
  checkpointVerse = {
    page: currentPage,
    surah: lastMatchedVerse.surah,
    ayah: lastMatchedVerse.ayah
  };

  // جلب اسم السورة المحفوظة لعرضها في لوحة الإيقاف المؤقت
  const pageData = quranDatabase[currentPage - 1];
  let surahName = 'سورة البقرة';
  if (pageData) {
    const matchedV = pageData.verses.find(v => v.surah === checkpointVerse.surah) || pageData.verses[0];
    if (matchedV) surahName = matchedV.surahName;
  }

  document.getElementById('saved-location-text').innerText = `${surahName.replace('سُورَةُ ', '')} - آية ${checkpointVerse.ayah}`;
  
  // إظهار لوحة الركوع والسجود وتعتيم الإضاءة
  document.getElementById('ruku-overlay').classList.add('active');

  console.log('[State Machine] Ruku/Sujood state activated. Checkpoint saved:', checkpointVerse);
}

// تخطي وضع الركوع يدوياً والعودة للركعة التالية
function skipRukuState() {
  document.getElementById('ruku-overlay').classList.remove('active');
  
  // زيادة عداد الركعات
  rakahCount++;
  prayerState = STATE_WAITING_FATIHA;
  updatePrayerStatusUI();

  // إعادة توجيه الصفحة إلى الصفحة المحفوظة استعداداً للركعة التالية
  currentPage = checkpointVerse.page;
  displayPage(currentPage);

  document.getElementById('recognized-words').innerText = 'انتظار قراءة الفاتحة للركعة التالية...';
  
  // إعادة تشغيل الاستماع الصوتي في المتصفح إذا تم إيقافه
  try {
    speechRecognition.start();
    updateAudioIndicator(true);
  } catch(e) {}
}

// 3. إنهاء الصلاة والعودة للتهيئة
function stopPrayerSession() {
  isPrayerActive = false;
  prayerState = STATE_IDLE;

  // تحرير حظر الشاشة
  releaseWakeLock();

  // إيقاف الصوت
  try {
    speechRecognition.stop();
    updateAudioIndicator(false);
  } catch(e) {}

  // إخفاء التعتيم
  document.getElementById('ruku-overlay').classList.remove('active');

  // تبديل الشاشات
  document.getElementById('prayer-screen').classList.remove('active');
  document.getElementById('setup-screen').classList.add('active');
}

// ==================== إدارة عرض صفحات المصحف ==================== //

// عرض صفحة معينة بالرقم
function displayPage(pageNum) {
  if (pageNum < 1 || pageNum > 604) return;
  
  const imgElement = document.getElementById('mushaf-image');
  const threeDigitPage = String(pageNum).padStart(3, '0');
  const imageUrl = `${IMAGE_BASE_URL}${threeDigitPage.slice(0,1)}/${threeDigitPage}.png`; // مسار raw جيت هاب

  // تحديث الصورة
  imgElement.src = imageUrl;

  // تحديث بيانات شريط المعلومات
  document.getElementById('current-page-num').innerText = `صفحة ${pageNum}`;
  
  // جلب اسم السورة المعروضة في الصفحة
  if (quranDatabase) {
    const pageData = quranDatabase[pageNum - 1];
    if (pageData && pageData.verses.length > 0) {
      const firstVerse = pageData.verses[0];
      document.getElementById('current-surah-title').innerText = `${firstVerse.surahName}`;
    }
  }

  // التحميل المسبق (Preload) للصفحة التالية في ذاكرة المتصفح لتقليب فوري أوفلاين
  if (pageNum < 604) {
    const nextThreeDigit = String(pageNum + 1).padStart(3, '0');
    const nextImg = new Image();
    nextImg.src = `${IMAGE_BASE_URL}${nextThreeDigit.slice(0,1)}/${nextThreeDigit}.png`;
  }
}

// تقليب الصفحة تلقائياً
function flipPage(targetPage) {
  if (targetPage === currentPage || targetPage < 1 || targetPage > 604) return;

  const wrapper = document.getElementById('mushaf-page-wrapper');
  
  // تأثير انتقالي ناعم (Fade & Slide) يحاكي تقليب الكتاب
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

// تقليب يدوي باللمس (حالة الطوارئ)
function flipPageManual(direction) {
  const target = currentPage + direction;
  if (target >= 1 && target <= 604) {
    // تحديث علامة وقوف افتراضية عند التقليب اليدوي لمنع التشتت
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

// عرض رسائل التنبيه والخطأ في الهيدر
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

// إعادة طلب قفل الشاشة إذا عاد التطبيق للظهور بعد الخروج
document.addEventListener('visibilitychange', async () => {
  if (wakeLock !== null && document.visibilityState === 'visible' && isPrayerActive) {
    await requestWakeLock();
  }
});

// ==================== إدارة تخزين الصور أوفلاين (Download Manager) ==================== //

// التحقق من حالة الملفات المخزنة أوفلاين
function checkOfflineCacheStatus() {
  if (!('caches' in window)) return;
  
  caches.has('mushaf-qiyam-images-v1').then(exists => {
    if (exists) {
      // نتحقق من عدد العناصر المخزنة
      caches.open('mushaf-qiyam-images-v1').then(cache => {
        cache.keys().then(keys => {
          if (keys.length >= 600) {
            document.getElementById('cache-status-label').innerText = 'المصحف محمل بالكامل للعمل دون اتصال بالإنترنت (604 صفحات)';
            document.getElementById('cache-percent-label').innerText = '100%';
            document.getElementById('cache-progress-bar').style.width = '100%';
            document.getElementById('btn-download-quran').innerText = 'تحديث صور المصحف المخزنة محلياً';
          }
        });
      });
    }
  });
}

// تحميل كافة الصور وتخزينها محلياً
function downloadAllQuranImages() {
  const btn = document.getElementById('btn-download-quran');
  btn.disabled = true;
  btn.innerText = 'جاري الاتصال بخادم الصور...';

  // إعداد قائمة الـ 604 رابط لصور الصفحات
  const urlsToCache = [];
  for (let i = 1; i <= 604; i++) {
    const threeDigitPage = String(i).padStart(3, '0');
    // raw github url
    urlsToCache.push(`${IMAGE_BASE_URL}${threeDigitPage.slice(0,1)}/${threeDigitPage}.png`);
  }

  // إرسال الطلب لـ Service Worker
  if (navigator.serviceWorker && navigator.serviceWorker.controller) {
    navigator.serviceWorker.controller.postMessage({
      action: 'cache-images',
      urls: urlsToCache
    });
  } else {
    // إعادة محاولة الربط في حال عدم كفاءة الـ Service Worker فوراً
    showStatusMessage('جاري تهيئة خادم التحميل الصامت، يرجى المحاولة بعد قليل.', 'yellow');
    btn.disabled = false;
    btn.innerText = 'تحميل صفحات المصحف (حوالي 85 ميجا)';
  }
}

// تحديث شريط تقدم التحميل
function updateDownloadUI(progress, current, total) {
  document.getElementById('cache-status-label').innerText = `جاري تحميل الصفحات محلياً (${current} من ${total})...`;
  document.getElementById('cache-percent-label').innerText = `${progress}%`;
  document.getElementById('cache-progress-bar').style.width = `${progress}%`;
}

// انتهاء التحميل
function onDownloadCompleted() {
  document.getElementById('cache-status-label').innerText = 'اكتمل التحميل! المصحف محمل بالكامل للعمل دون اتصال بالإنترنت.';
  document.getElementById('cache-percent-label').innerText = '100%';
  document.getElementById('cache-progress-bar').style.width = '100%';
  
  const btn = document.getElementById('btn-download-quran');
  btn.disabled = false;
  btn.innerText = 'تحديث صور المصحف المخزنة محلياً';
  
  showStatusMessage('اكتمل تحميل جميع صفحات المصحف أوفلاين بنجاح!', 'green');
}

// ==================== إدارة التحديثات التلقائية (GitHub Auto Update) ==================== //
function checkForUpdates() {
  // نقارن رقم الإصدار المحلي بملف version.json المرفوع على سيرفر جيت هاب بيجز
  fetch('version.json?nocache=' + Date.now())
    .then(res => res.json())
    .then(serverInfo => {
      // جلب الإصدار الحالي من الواجهة
      fetch('version.json')
        .then(res => res.json())
        .then(localInfo => {
          console.log(`Local Version: ${localInfo.version}, Server Version: ${serverInfo.version}`);
          document.getElementById('app-version-label').innerText = `إصدار ${localInfo.version}`;
          
          if (compareVersions(serverInfo.version, localInfo.version) > 0) {
            // إظهار إشعار التحديث الأنيق
            document.getElementById('update-toast').classList.add('show');
          }
        });
    })
    .catch(err => console.log('Update check failed (normal if running local/offline):', err));
}

// خوارزمية مقارنة إصدارات الأكواد (Semantic Versioning comparison)
function compareVersions(v1, v2) {
  const parts1 = v1.split('.').map(Number);
  const parts2 = v2.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (parts1[i] > parts2[i]) return 1;
    if (parts1[i] < parts2[i]) return -1;
  }
  return 0;
}
