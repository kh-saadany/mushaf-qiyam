import React, { useState, useEffect, useRef } from 'react';
import { StyleSheet, Text, View, TouchableOpacity, ActivityIndicator } from 'react-native';
import { initWhisper } from 'whisper.rn';
import { RealtimeTranscriber } from 'whisper.rn/realtime-transcription';
import { AudioPcmStreamAdapter } from 'whisper.rn/realtime-transcription/adapters/AudioPcmStreamAdapter';
import * as FileSystem from 'expo-file-system';
import quranData from './assets/quran-pages.json';

const MODEL_URL = "https://huggingface.co/tarteel-ai/whisper-tiny-ar-quran/resolve/main/ggml-model.bin";
const MODEL_FILE_NAME = "ggml-model.bin";

export default function App() {
  const [modelReady, setModelReady] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);
  const [isDownloading, setIsDownloading] = useState(false);
  const [prayerState, setPrayerState] = useState('setup');
  const [recognizedText, setRecognizedText] = useState('بانتظار قراءتك...');
  const [currentSurah, setCurrentSurah] = useState(1);
  const [currentPage, setCurrentPage] = useState(1);
  const [whisperContext, setWhisperContext] = useState(null);
  const transcriberRef = useRef(null);
  
  const surahs = [...new Set(quranData.map(item => item.surahName))];

  useEffect(() => {
    checkModelExists();
    return () => {
      if (transcriberRef.current) {
        transcriberRef.current.stop().catch(console.error);
      }
    };
  }, []);

  const checkModelExists = async () => {
    const fileInfo = await FileSystem.getInfoAsync(FileSystem.documentDirectory + MODEL_FILE_NAME);
    if (fileInfo.exists) {
      setModelReady(true);
      initializeWhisper(fileInfo.uri);
    }
  };

  const downloadModel = async () => {
    setIsDownloading(true);
    const downloadResumable = FileSystem.createDownloadResumable(
      MODEL_URL,
      FileSystem.documentDirectory + MODEL_FILE_NAME,
      {},
      (downloadProgress) => {
        const progress = downloadProgress.totalBytesWritten / downloadProgress.totalBytesExpectedToWrite;
        setDownloadProgress(progress * 100);
      }
    );

    try {
      const { uri } = await downloadResumable.downloadAsync();
      setModelReady(true);
      setIsDownloading(false);
      initializeWhisper(uri);
    } catch (e) {
      console.error(e);
      setIsDownloading(false);
    }
  };

  const initializeWhisper = async (path) => {
    try {
      const ctx = await initWhisper({ filePath: path });
      setWhisperContext(ctx);
    } catch (e) {
      console.error("Failed to init whisper:", e);
    }
  };

  const startPrayer = async () => {
    if (!whisperContext) return;
    setPrayerState('active');
    
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
            if (event.data?.text) {
              setRecognizedText(event.data.text);
              // matchVerse(event.data.text);
            }
          },
          onError: (error) => {
            console.error("Transcriber error:", error);
          }
        }
      );
      
      transcriberRef.current = transcriber;
      await transcriber.start();
    } catch (e) {
      console.error("Transcription error:", e);
      setPrayerState('setup');
    }
  };

  const stopPrayer = async () => {
    setPrayerState('setup');
    if (transcriberRef.current) {
      try {
        await transcriberRef.current.stop();
      } catch (e) {
        console.error("Failed to stop transcriber:", e);
      }
      transcriberRef.current = null;
    }
  };

  if (prayerState === 'setup') {
    return (
      <View style={styles.container}>
        <Text style={styles.title}>مصحف القيام</Text>
        <Text style={styles.subtitle}>المساعد الذكي لتتبع التلاوة</Text>

        {!modelReady ? (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>التشغيل دون اتصال بالإنترنت</Text>
            {isDownloading ? (
              <View>
                <Text style={styles.whiteText}>جاري التحميل... {Math.round(downloadProgress)}%</Text>
                <ActivityIndicator size="large" color="#00ffcc" />
              </View>
            ) : (
              <TouchableOpacity style={styles.btn} onPress={downloadModel}>
                <Text style={styles.btnText}>تحميل الموديل الصوتي أوفلاين</Text>
              </TouchableOpacity>
            )}
          </View>
        ) : (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>جاهز للصلاة</Text>
            <TouchableOpacity style={styles.btnPrimary} onPress={startPrayer}>
              <Text style={styles.btnText}>ابدأ الصلاة الآن</Text>
            </TouchableOpacity>
          </View>
        )}
      </View>
    );
  }

  return (
    <View style={styles.activeContainer}>
      <View style={styles.header}>
        <TouchableOpacity onPress={stopPrayer}><Text style={styles.headerBtn}>🚪 إنهاء</Text></TouchableOpacity>
        <Text style={styles.headerTitle}>{surahs[currentSurah-1]}</Text>
        <Text style={styles.headerBtn}>صفحة {currentPage}</Text>
      </View>
      <View style={styles.mushafViewport}>
        <Text style={{color: '#fff'}}>هنا سيتم عرض صفحة المصحف ({currentPage})</Text>
      </View>
      <View style={styles.toast}>
        <Text style={styles.toastText}>🎙️ {recognizedText}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#121216', alignItems: 'center', justifyContent: 'center', padding: 20 },
  activeContainer: { flex: 1, backgroundColor: '#121216' },
  title: { fontSize: 32, fontWeight: 'bold', color: '#fff', marginBottom: 10 },
  subtitle: { fontSize: 16, color: '#a0a0a0', marginBottom: 40 },
  card: { backgroundColor: 'rgba(255,255,255,0.05)', padding: 20, borderRadius: 15, width: '100%', alignItems: 'center' },
  cardTitle: { color: '#fff', fontSize: 18, marginBottom: 20 },
  btn: { backgroundColor: '#333', padding: 15, borderRadius: 10, width: '100%', alignItems: 'center' },
  btnPrimary: { backgroundColor: '#00ffcc', padding: 15, borderRadius: 10, width: '100%', alignItems: 'center' },
  btnText: { color: '#000', fontWeight: 'bold', fontSize: 16 },
  whiteText: { color: '#fff', marginBottom: 10 },
  header: { flexDirection: 'row', justifyContent: 'space-between', padding: 20, paddingTop: 50, backgroundColor: '#1a1a24' },
  headerBtn: { color: '#fff', fontSize: 16 },
  headerTitle: { color: '#00ffcc', fontSize: 18, fontWeight: 'bold' },
  mushafViewport: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  toast: { backgroundColor: '#222', padding: 15, position: 'absolute', bottom: 30, left: 20, right: 20, borderRadius: 10 },
  toastText: { color: '#00ffcc', textAlign: 'center', fontSize: 16 },
});
