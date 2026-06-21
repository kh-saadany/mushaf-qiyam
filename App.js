import React, { useState, useEffect, useRef } from 'react';
import { 
  StyleSheet, 
  Text, 
  View, 
  TouchableOpacity, 
  ActivityIndicator, 
  Image, 
  ScrollView, 
  PermissionsAndroid, 
  Dimensions,
  SafeAreaView
} from 'react-native';
import { initWhisper } from 'whisper.rn';
import { RealtimeTranscriber } from 'whisper.rn/realtime-transcription/index.js';
import { AudioPcmStreamAdapter } from 'whisper.rn/realtime-transcription/adapters/AudioPcmStreamAdapter.js';
import { StatusBar } from 'expo-status-bar';
import quranData from './assets/quran-pages.json';
import { quranImages } from './assets/quran-images.js';

const { width: SCREEN_WIDTH, height: SCREEN_HEIGHT } = Dimensions.get('window');

export default function App() {
  const [prayerState, setPrayerState] = useState('setup'); // 'setup', 'waiting_fatiha', 'reciting', 'ruku'
  const [rakahCount, setRakahCount] = useState(1);
  const [recognizedText, setRecognizedText] = useState('بانتظار قراءتك...');
  const [currentSurah, setCurrentSurah] = useState(1);
  const [currentPage, setCurrentPage] = useState(1);
  const [whisperContext, setWhisperContext] = useState(null);
  const [initializing, setInitializing] = useState(true);
  
  const [selectedSurah, setSelectedSurah] = useState({ id: 1, name: "سُورَةُ ٱلْفَاتِحَةِ", page: 1 });
  const [surahList, setSurahList] = useState([]);
  const [matchedVerseText, setMatchedVerseText] = useState('بانتظار بدء التلاوة...');

  const transcriberRef = useRef(null);
  const currentPageRef = useRef(1);
  const prayerStateRef = useRef('setup');
  const lastMatchedVerseRef = useRef({ surah: 1, ayah: 0, surahName: '' });
  const checkpointVerseRef = useRef({ page: 1, surah: 1, ayah: 0, surahName: '' });
  const rakahCountRef = useRef(1);
  const spokenHistoryRef = useRef('');
  const lastNormalizedTextRef = useRef('');

  // Extract Surah List from quranData
  useEffect(() => {
    const list = [];
    const seen = new Set();
    quranData.forEach(pageItem => {
      if (pageItem.verses) {
        pageItem.verses.forEach(v => {
          if (!seen.has(v.surah)) {
            seen.add(v.surah);
            list.push({
              id: v.surah,
              name: v.surahName,
              page: pageItem.page
            });
          }
        });
      }
    });
    setSurahList(list);
  }, []);

  useEffect(() => {
    initializeWhisper();
    return () => {
      if (transcriberRef.current) {
        transcriberRef.current.stop().catch(console.error);
      }
    };
  }, []);

  const initializeWhisper = async () => {
    try {
      setInitializing(true);
      // Load the model directly from bundled assets using require
      const ctx = await initWhisper({ filePath: require('./assets/ggml-model.bin') });
      setWhisperContext(ctx);
      setInitializing(false);
    } catch (e) {
      console.error("Failed to init whisper:", e);
      alert("فشل تهيئة محرك الصوت المحلي أوفلاين. يرجى إغلاق التطبيق وإعادة تشغيله.");
      setInitializing(false);
    }
  };

  const requestMicrophonePermission = async () => {
    try {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
        {
          title: 'صلاحية الميكروفون',
          message: 'التطبيق يحتاج لصلاحية الميكروفون لتتبع قراءتك أثناء الصلاة.',
          buttonNeutral: 'اسألني لاحقاً',
          buttonNegative: 'إلغاء',
          buttonPositive: 'موافق',
        },
      );
      return granted === PermissionsAndroid.RESULTS.GRANTED;
    } catch (err) {
      console.warn(err);
      return false;
    }
  };

  const startPrayer = async () => {
    const hasPermission = await requestMicrophonePermission();
    if (!hasPermission) {
      alert("يرجى إعطاء صلاحية الميكروفون للتعرف على الصوت.");
      return;
    }

    if (!whisperContext) return;

    currentPageRef.current = selectedSurah.page;
    setCurrentPage(selectedSurah.page);
    setCurrentSurah(selectedSurah.id);

    lastMatchedVerseRef.current = { surah: selectedSurah.id, ayah: 0, surahName: selectedSurah.name };
    checkpointVerseRef.current = { page: selectedSurah.page, surah: selectedSurah.id, ayah: 0, surahName: selectedSurah.name };
    rakahCountRef.current = 1;
    setRakahCount(1);
    spokenHistoryRef.current = '';
    setRecognizedText('بانتظار قراءتك للفاتحة...');
    setMatchedVerseText('بانتظار تلاوة السورة...');
    
    setPrayerStateAndRef('waiting_fatiha');

    try {
      const audioStream = new AudioPcmStreamAdapter();
      const transcriber = new RealtimeTranscriber(
        {
          whisperContext,
          audioStream,
        },
        {
          audioSliceSec: 30,
          transcribeOptions: {
            language: 'ar',
          },
        },
        {
          onTranscribe: (event) => {
            if (event.data?.result) {
              const text = event.data.result;
              handleSpokenWords(text);
            }
          },
          onError: (error) => {
            console.error("Transcriber error:", error);
            setRecognizedText(`خطأ في التعرف على الصوت: ${error}`);
          }
        }
      );
      
      transcriberRef.current = transcriber;
      await transcriber.start();
    } catch (e) {
      console.error("Transcription error:", e);
      setPrayerStateAndRef('setup');
    }
  };

  const stopPrayer = async () => {
    setPrayerStateAndRef('setup');
    if (transcriberRef.current) {
      try {
        await transcriberRef.current.stop();
      } catch (e) {
        console.error("Failed to stop transcriber:", e);
      }
      transcriberRef.current = null;
    }
  };

  const setPrayerStateAndRef = (state) => {
    prayerStateRef.current = state;
    setPrayerState(state);
  };

  const flipPage = (targetPage) => {
    if (targetPage < 1 || targetPage > 604) return;
    
    // Reset spoken history to avoid false positives from previous page
    spokenHistoryRef.current = '';
    
    currentPageRef.current = targetPage;
    setCurrentPage(targetPage);

    // Update current Surah title from pageData
    const pageData = quranData[targetPage - 1];
    if (pageData && pageData.verses && pageData.verses.length > 0) {
      setCurrentSurah(pageData.verses[0].surah);
    }
  };

  const nextPage = () => {
    if (currentPage < 604) {
      flipPage(currentPage + 1);
    }
  };

  const prevPage = () => {
    if (currentPage > 1) {
      flipPage(currentPage - 1);
    }
  };

  // State machine logic
  const handleSpokenWords = (text) => {
    setRecognizedText(text);
    const cleanSpoken = normalizeArabic(text);

    if (detectTakbeer(cleanSpoken)) {
      triggerRukuState();
      return;
    }

    if (prayerStateRef.current === 'waiting_fatiha') {
      if (detectFatiha(cleanSpoken)) {
        transitionToReciting();
      }
    } else if (prayerStateRef.current === 'reciting') {
      matchRecitationWithQuran(cleanSpoken);
    }
  };

  const transitionToReciting = () => {
    setPrayerStateAndRef('reciting');
    setRecognizedText('تم كشف الفاتحة - جاري متابعة السورة...');
  };

  const triggerRukuState = () => {
    setPrayerStateAndRef('ruku');

    // Save checkpoint
    checkpointVerseRef.current = {
      page: currentPageRef.current,
      surah: lastMatchedVerseRef.current.surah,
      ayah: lastMatchedVerseRef.current.ayah,
      surahName: lastMatchedVerseRef.current.surahName || selectedSurah.name
    };
  };

  const resumeFromRuku = () => {
    rakahCountRef.current = rakahCountRef.current + 1;
    setRakahCount(rakahCountRef.current);
    
    setPrayerStateAndRef('waiting_fatiha');
    setRecognizedText('انتظار قراءة الفاتحة للركعة التالية...');
    setMatchedVerseText('بانتظار تلاوة السورة...');
    spokenHistoryRef.current = '';

    flipPage(checkpointVerseRef.current.page);
  };

  // Arabic Normalization & Fuzzy Search Algorithms
  const normalizeArabic = (text) => {
    if (!text) return '';
    return text
      // 1. Remove standard diacritics (fatha, damma, kasra, shadda, sukun, tanween)
      .replace(/[\u064B-\u0652]/g, '')
      // 2. Remove maddah and hamza above/below
      .replace(/[\u0653-\u0655]/g, '')
      // 3. Remove superscript alef
      .replace(/[\u0656-\u065F\u0670]/g, '')
      // 4. Remove Quranic recitation and stop annotation signs
      .replace(/[\u0610-\u061A]/g, '')
      .replace(/[\u06D6-\u06ED]/g, '')
      // 5. Remove kashida/tatweel
      .replace(/\u0640/g, '')
      // 6. Normalize Alef forms: أ إ آ ٱ → ا
      .replace(/[أإآ\u0671]/g, 'ا')
      // 7. Normalize Yaa/Alef Maksoura: ى → ي
      .replace(/ى/g, 'ي')
      // 8. Normalize Ta Marbouta: ة → ه
      .replace(/ة/g, 'ه')
      // 9. Remove special symbols (rub-el-hizb, sajdah, etc.)
      .replace(/[\u06DE\u06E9\uFDFA\uFDFB\uFDFC]/g, '')
      // 10. Remove any non-basic Arabic characters and non-spaces
      .replace(/[^\u0621-\u064A\s]/g, '')
      // 11. Normalize multiple spaces to single space
      .replace(/\s+/g, ' ')
      .trim();
  };

  const detectTakbeer = (cleanText) => {
    const tokens = cleanText.split(' ');
    const lastWords = tokens.slice(-3).join(' ');
    return lastWords.includes('الله اكبر') || lastWords.includes('اللهم اكبر');
  };

  const detectFatiha = (cleanText) => {
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
  };

  // 1. Levenshtein Distance character-level computation (Optimized 1D Array)
  const levenshteinDistance = (str1, str2) => {
    if (str1.length === 0) return str2.length;
    if (str2.length === 0) return str1.length;
    
    let prevRow = Array(str2.length + 1);
    let currRow = Array(str2.length + 1);
    
    for (let j = 0; j <= str2.length; j++) {
      prevRow[j] = j;
    }
    
    for (let i = 1; i <= str1.length; i++) {
      currRow[0] = i;
      for (let j = 1; j <= str2.length; j++) {
        const cost = str1[i - 1] === str2[j - 1] ? 0 : 1;
        currRow[j] = Math.min(
          currRow[j - 1] + 1,      // insertion
          prevRow[j] + 1,          // deletion
          prevRow[j - 1] + cost    // substitution
        );
      }
      let temp = prevRow;
      prevRow = currRow;
      currRow = temp;
    }
    
    return prevRow[str2.length];
  };

  // 2. Character-level Similarity percentage
  const getSimilarity = (s1, s2) => {
    if (s1.length === 0 && s2.length === 0) return 1.0;
    const dist = levenshteinDistance(s1, s2);
    const maxLength = Math.max(s1.length, s2.length);
    return 1.0 - (dist / maxLength);
  };

  // 3. Word-level sliding window fuzzy matching
  const fuzzySubstringMatch = (verseText, queryText, threshold = 0.75) => {
    const queryWords = queryText.split(' ');
    const verseWords = verseText.split(' ');
    const qLen = queryWords.length;
    const vLen = verseWords.length;
    
    if (vLen < qLen) {
      return getSimilarity(verseText, queryText) >= threshold;
    }
    
    let maxSim = 0;
    for (let i = 0; i <= vLen - qLen; i++) {
      const windowText = verseWords.slice(i, i + qLen).join(' ');
      const sim = getSimilarity(windowText, queryText);
      if (sim > maxSim) {
        maxSim = sim;
      }
      if (maxSim >= threshold) return true;
    }
    
    return false;
  };

  const matchRecitationWithQuran = (cleanSpoken) => {
    if (!quranData) return;

    if (cleanSpoken === lastNormalizedTextRef.current) return;
    lastNormalizedTextRef.current = cleanSpoken;

    const pageIndex = currentPageRef.current - 1;
    const pageData = quranData[pageIndex];
    const nextPageData = quranData[pageIndex + 1];
    
    if (!pageData) return;

    const spokenTokens = cleanSpoken.split(' ');
    const totalTokens = spokenTokens.length;

    let matchedPage = null;
    let matchedVerse = null;

    // Search with query lengths in descending order (longer matches first)
    const queryLengths = [10, 8, 6, 4, 3, 2];
    
    // First, scan verses of the current page
    let startIndex = 0;
    if (lastMatchedVerseRef.current && lastMatchedVerseRef.current.surah === pageData.verses[0]?.surah) {
      const idx = pageData.verses.findIndex(v => v.ayah === lastMatchedVerseRef.current.ayah);
      if (idx !== -1) startIndex = idx;
    }

    for (let len of queryLengths) {
      if (totalTokens < len) continue;
      const cleanQuery = spokenTokens.slice(-len).join(' ');

      for (let i = startIndex; i < pageData.verses.length; i++) {
        const verse = pageData.verses[i];
        if (fuzzySubstringMatch(verse.cleanText, cleanQuery, 0.75)) {
          matchedPage = currentPageRef.current;
          matchedVerse = verse;
          break;
        }
      }
      if (matchedVerse) break;
    }

    // If no match on current page, scan verses of the next page
    if (!matchedVerse && nextPageData) {
      for (let len of queryLengths) {
        if (totalTokens < len) continue;
        const cleanQuery = spokenTokens.slice(-len).join(' ');

        for (let verse of nextPageData.verses) {
          if (fuzzySubstringMatch(verse.cleanText, cleanQuery, 0.75)) {
            matchedPage = currentPageRef.current + 1;
            matchedVerse = verse;
            break;
          }
        }
        if (matchedVerse) break;
      }
    }

    if (matchedPage && matchedVerse) {
      lastMatchedVerseRef.current = {
        surah: matchedVerse.surah,
        ayah: matchedVerse.ayah,
        surahName: matchedVerse.surahName
      };

      setMatchedVerseText(`سورة ${getSurahDisplayName(matchedVerse.surahName)} - آية ${matchedVerse.ayah}:\n﴿ ${matchedVerse.text} ﴾`);

      if (matchedPage > currentPageRef.current) {
        flipPage(matchedPage);
      } else {
        // Preemptive page flip: if we matched the last verse of the current page
        if (matchedVerse === pageData.verses[pageData.verses.length - 1] && nextPageData) {
          const verseWords = matchedVerse.cleanText.split(' ');
          if (verseWords.length >= 3 && totalTokens >= 3) {
            const queryLastWords = spokenTokens.slice(-3).join(' ');
            const verseLastWords = verseWords.slice(-3).join(' ');
            if (getSimilarity(verseLastWords, queryLastWords) >= 0.7) {
               flipPage(currentPageRef.current + 1);
            }
          } else if (verseWords.length < 3) {
             flipPage(currentPageRef.current + 1);
          }
        }
      }
    }
  };

  const getSurahDisplayName = (surahName) => {
    return surahName.replace('سُورَةُ ', '');
  };

  // UI Rendering
  if (initializing || !whisperContext) {
    return (
      <SafeAreaView style={styles.container}>
        <StatusBar style="light" />
        <Text style={styles.title}>مصحف القيام</Text>
        <Text style={styles.subtitle}>المساعد الذكي لتتبع التلاوة والتقليب التلقائي</Text>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>جاري تهيئة محرك الصوت المحلي أوفلاين...</Text>
          <ActivityIndicator size="large" color="#00ffcc" style={{ marginTop: 20 }} />
        </View>
      </SafeAreaView>
    );
  }

  if (prayerState === 'setup') {
    return (
      <SafeAreaView style={styles.container}>
        <StatusBar style="light" />
        <Text style={styles.title}>مصحف القيام</Text>
        <Text style={styles.subtitle}>المساعد الذكي لتتبع التلاوة والتقليب التلقائي</Text>

        <View style={styles.cardExpanded}>
          <Text style={styles.cardTitle}>تحديد سورة البداية</Text>
          <View style={styles.selectedIndicator}>
            <Text style={styles.selectedLabel}>السورة المختارة:</Text>
            <Text style={styles.selectedValue}>{getSurahDisplayName(selectedSurah.name)} (صفحة {selectedSurah.page})</Text>
          </View>

          <View style={styles.scrollListContainer}>
            <ScrollView 
              contentContainerStyle={styles.surahList}
              showsVerticalScrollIndicator={true}
            >
              {surahList.map((surah) => (
                <TouchableOpacity 
                  key={surah.id} 
                  style={[
                    styles.surahItem, 
                    selectedSurah.id === surah.id && styles.surahItemActive
                  ]} 
                  onPress={() => setSelectedSurah(surah)}
                >
                  <Text style={[
                    styles.surahText, 
                    selectedSurah.id === surah.id && styles.surahTextActive
                  ]}>
                    {getSurahDisplayName(surah.name)}
                  </Text>
                  <Text style={styles.surahPageText}>صفحة {surah.page}</Text>
                </TouchableOpacity>
              ))}
            </ScrollView>
          </View>

          <TouchableOpacity style={styles.btnStart} onPress={startPrayer}>
            <Text style={styles.btnTextPrimary}>ابدأ الصلاة الآن 🎙️</Text>
          </TouchableOpacity>
        </View>
      </SafeAreaView>
    );
  }

  // Active Prayer screen
  const pageData = quranData[currentPage - 1];
  const surahName = pageData && pageData.verses && pageData.verses.length > 0 
    ? getSurahDisplayName(pageData.verses[0].surahName) 
    : 'سورة البقرة';

  return (
    <SafeAreaView style={styles.activeContainer}>
      <StatusBar style="light" />
      
      {/* Top Header */}
      <View style={styles.header}>
        <TouchableOpacity style={styles.exitBtn} onPress={stopPrayer}>
          <Text style={styles.exitBtnText}>🚪 إنهاء</Text>
        </TouchableOpacity>
        <Text style={styles.headerTitle}>{surahName}</Text>
        <View style={styles.rakahBadge}>
          <Text style={styles.rakahBadgeText}>الركعة {rakahCount}</Text>
        </View>
      </View>

      {/* Main Mushaf Viewport */}
      <View style={styles.mushafViewport}>
        {/* Navigation tap areas */}
        <TouchableOpacity style={styles.leftNavZone} onPress={nextPage} activeOpacity={0.1}>
          <Text style={styles.navZoneArrow}>‹</Text>
        </TouchableOpacity>
        
        <TouchableOpacity style={styles.rightNavZone} onPress={prevPage} activeOpacity={0.1}>
          <Text style={styles.navZoneArrow}>›</Text>
        </TouchableOpacity>

        {/* Mushaf Page Image */}
        <Image 
          source={quranImages[currentPage]}
          style={styles.mushafImage}
          fadeDuration={200}
        />
      </View>

      {/* Page Number Indicator */}
      <View style={styles.pageIndicator}>
        <Text style={styles.pageIndicatorText}>صفحة {currentPage}</Text>
      </View>

      {/* Recognized text overlay / toast */}
      <View style={styles.toast}>
        <Text style={styles.toastLabel}>
          {prayerState === 'waiting_fatiha' && '🎙️ بانتظار قراءة الفاتحة...'}
          {prayerState === 'reciting' && '🎙️ تلاوة نشطة متتبعة...'}
          {prayerState === 'ruku' && '🛑 ركوع أو سجود'}
        </Text>
        <Text style={styles.toastText} numberOfLines={3} ellipsizeMode="tail">
          {prayerState === 'reciting' && matchedVerseText ? matchedVerseText : recognizedText}
        </Text>
      </View>

      {/* Ruku Overlay */}
      {prayerState === 'ruku' && (
        <View style={styles.overlayContainer}>
          <View style={styles.overlayCard}>
            <Text style={styles.overlayTitle}>وضعية الركوع والسجود 🛑</Text>
            <Text style={styles.overlaySubtitle}>تم حفظ الموضع الحالي تلقائياً:</Text>
            <Text style={styles.overlayLocation}>
              {getSurahDisplayName(checkpointVerseRef.current.surahName)} - آية {checkpointVerseRef.current.ayah}
            </Text>
            <TouchableOpacity style={styles.overlayBtn} onPress={resumeFromRuku}>
              <Text style={styles.overlayBtnText}>الركعة التالية 📖</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { 
    flex: 1, 
    backgroundColor: '#0a0a0d', 
    alignItems: 'center', 
    justifyContent: 'flex-start', 
    padding: 20,
    paddingTop: 50
  },
  activeContainer: { 
    flex: 1, 
    backgroundColor: '#000' 
  },
  title: { 
    fontSize: 34, 
    fontWeight: 'bold', 
    color: '#fff', 
    marginBottom: 5,
    textAlign: 'center',
    fontFamily: 'System'
  },
  subtitle: { 
    fontSize: 15, 
    color: '#88889a', 
    marginBottom: 30,
    textAlign: 'center',
    paddingHorizontal: 20
  },
  card: { 
    backgroundColor: '#16161f', 
    padding: 24, 
    borderRadius: 20, 
    width: '100%', 
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#242435',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 10 },
    shadowOpacity: 0.5,
    shadowRadius: 15,
    elevation: 8
  },
  cardExpanded: { 
    backgroundColor: '#16161f', 
    padding: 20, 
    borderRadius: 20, 
    width: '100%', 
    flex: 1,
    maxHeight: SCREEN_HEIGHT * 0.7,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#242435'
  },
  cardTitle: { 
    color: '#fff', 
    fontSize: 20, 
    fontWeight: 'bold',
    marginBottom: 8,
    textAlign: 'center'
  },
  cardSubtitle: { 
    color: '#88889a', 
    fontSize: 13, 
    marginBottom: 24,
    textAlign: 'center',
    lineHeight: 18
  },
  selectedIndicator: {
    flexDirection: 'row-reverse',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(0, 255, 204, 0.08)',
    borderColor: '#00ffcc',
    borderWidth: 1,
    padding: 12,
    borderRadius: 12,
    width: '100%',
    marginBottom: 16,
  },
  selectedLabel: {
    color: '#00ffcc',
    fontSize: 14,
    fontWeight: 'bold'
  },
  selectedValue: {
    color: '#fff',
    fontSize: 14,
    fontWeight: 'bold'
  },
  scrollListContainer: {
    flex: 1,
    width: '100%',
    backgroundColor: '#0a0a0d',
    borderRadius: 12,
    marginBottom: 16,
    padding: 4
  },
  surahList: { 
    paddingVertical: 8 
  },
  surahItem: { 
    flexDirection: 'row-reverse', 
    justifyContent: 'space-between', 
    padding: 14, 
    borderRadius: 8,
    marginVertical: 3,
    marginHorizontal: 5,
    backgroundColor: 'rgba(255,255,255,0.02)'
  },
  surahItemActive: { 
    backgroundColor: '#00ffcc' 
  },
  surahText: { 
    color: '#fff', 
    fontSize: 16,
    textAlign: 'right',
    fontWeight: '500'
  },
  surahTextActive: { 
    color: '#000',
    fontWeight: 'bold'
  },
  surahPageText: { 
    color: '#66667a', 
    fontSize: 13 
  },
  btnPrimary: { 
    backgroundColor: '#333345', 
    padding: 16, 
    borderRadius: 12, 
    width: '100%', 
    alignItems: 'center' 
  },
  btnStart: { 
    backgroundColor: '#00ffcc', 
    padding: 16, 
    borderRadius: 12, 
    width: '100%', 
    alignItems: 'center',
    shadowColor: '#00ffcc',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 4
  },
  btnTextPrimary: { 
    color: '#000', 
    fontWeight: 'bold', 
    fontSize: 16 
  },
  progressContainer: { 
    width: '100%', 
    alignItems: 'center' 
  },
  progressBarBg: { 
    height: 8, 
    backgroundColor: '#222230', 
    borderRadius: 4, 
    width: '100%', 
    marginTop: 15,
    overflow: 'hidden'
  },
  progressBarFill: { 
    height: '100%', 
    backgroundColor: '#00ffcc', 
    borderRadius: 4 
  },
  whiteText: { 
    color: '#fff', 
    fontSize: 15, 
    fontWeight: '600' 
  },
  header: { 
    flexDirection: 'row-reverse', 
    justifyContent: 'space-between', 
    alignItems: 'center',
    paddingHorizontal: 20, 
    paddingTop: 55, 
    paddingBottom: 15, 
    backgroundColor: '#0a0a0d',
    borderBottomWidth: 1,
    borderColor: '#1a1a24'
  },
  exitBtn: { 
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: '#242435'
  },
  exitBtnText: { 
    color: '#fff', 
    fontSize: 14, 
    fontWeight: 'bold' 
  },
  headerTitle: { 
    color: '#00ffcc', 
    fontSize: 20, 
    fontWeight: 'bold',
    textAlign: 'center'
  },
  rakahBadge: {
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderRadius: 8,
    backgroundColor: 'rgba(0, 255, 204, 0.15)',
    borderWidth: 1,
    borderColor: '#00ffcc'
  },
  rakahBadgeText: {
    color: '#00ffcc',
    fontSize: 14,
    fontWeight: 'bold'
  },
  mushafViewport: { 
    flex: 1, 
    justifyContent: 'center', 
    alignItems: 'center',
    position: 'relative',
    backgroundColor: '#fbfbfb' // standard light page background for the image
  },
  mushafImage: { 
    width: SCREEN_WIDTH, 
    height: '100%', 
    resizeMode: 'contain' 
  },
  leftNavZone: { 
    position: 'absolute', 
    left: 0, 
    top: 0, 
    bottom: 0, 
    width: SCREEN_WIDTH * 0.18, 
    zIndex: 10, 
    justifyContent: 'center',
    alignItems: 'flex-start',
    paddingLeft: 10
  },
  rightNavZone: { 
    position: 'absolute', 
    right: 0, 
    top: 0, 
    bottom: 0, 
    width: SCREEN_WIDTH * 0.18, 
    zIndex: 10, 
    justifyContent: 'center',
    alignItems: 'flex-end',
    paddingRight: 10
  },
  navZoneArrow: {
    fontSize: 48,
    color: 'rgba(0,0,0,0.15)',
    fontWeight: '200'
  },
  pageIndicator: {
    position: 'absolute',
    bottom: 95,
    alignSelf: 'center',
    backgroundColor: 'rgba(0,0,0,0.6)',
    paddingVertical: 4,
    paddingHorizontal: 12,
    borderRadius: 10,
    zIndex: 5
  },
  pageIndicatorText: {
    color: '#fff',
    fontSize: 12,
    fontWeight: '600'
  },
  toast: { 
    backgroundColor: '#0a0a0d', 
    padding: 12, 
    borderTopWidth: 1,
    borderColor: '#1a1a24',
    paddingBottom: 25
  },
  toastLabel: {
    color: '#88889a',
    fontSize: 12,
    textAlign: 'center',
    marginBottom: 4
  },
  toastText: { 
    color: '#00ffcc', 
    textAlign: 'center', 
    fontSize: 16,
    fontWeight: '500',
    paddingHorizontal: 10
  },
  overlayContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.85)',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 100
  },
  overlayCard: {
    backgroundColor: '#16161f',
    padding: 30,
    borderRadius: 20,
    alignItems: 'center',
    width: '80%',
    borderWidth: 1,
    borderColor: '#242435'
  },
  overlayTitle: {
    color: '#00ffcc',
    fontSize: 22,
    fontWeight: 'bold',
    marginBottom: 10,
    textAlign: 'center'
  },
  overlaySubtitle: {
    color: '#88889a',
    fontSize: 14,
    marginBottom: 5
  },
  overlayLocation: {
    color: '#fff',
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 25,
    textAlign: 'center'
  },
  overlayBtn: {
    backgroundColor: '#00ffcc',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 10
  },
  overlayBtnText: {
    color: '#000',
    fontSize: 16,
    fontWeight: 'bold'
  }
});
