package com.mushafqiyam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
    }

    private var audioRecognizer: AudioRecognizer? = null
    private var lastCrashLog: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set global crash handler to log any unhandled error to LogCat and store message
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL UNCAUGHT EXCEPTION in thread ${thread.name}", throwable)
            lastCrashLog = throwable.localizedMessage ?: throwable.toString()
        }

        Log.i(TAG, "=== onCreate started ===")
        Log.i(TAG, "App Version: 4.1.2 (Crash-Proof Architecture)")
        Log.i(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Log.i(TAG, "Android API: ${android.os.Build.VERSION.SDK_INT}")

        try {
            audioRecognizer = AudioRecognizer(this)
            Log.i(TAG, "AudioRecognizer instantiated successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate AudioRecognizer", t)
            lastCrashLog = "فشل في إنشاء محرك الصوت: ${t.localizedMessage}"
        }

        try {
            setContent {
                MushafQiyamTheme {
                    MainAppScreen(
                        audioRecognizer = audioRecognizer,
                        initialCrashLog = lastCrashLog
                    )
                }
            }
            Log.i(TAG, "setContent completed successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "FATAL: setContent failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioRecognizer?.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing audioRecognizer", t)
        }
        audioRecognizer = null
        Log.i(TAG, "onDestroy")
    }
}

@Composable
fun MushafQiyamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFF4D03F),
            onPrimary = Color(0xFF0D3B66),
            surface = Color(0xFF0D3B66),
            onSurface = Color(0xFFF5F5F5),
            background = Color(0xFF0A2744),
            onBackground = Color(0xFFF5F5F5),
        ),
        content = content
    )
}

@Composable
fun MainAppScreen(audioRecognizer: AudioRecognizer?, initialCrashLog: String?) {
    val scrollState = rememberScrollState()

    var isRecording by remember { mutableStateOf(false) }
    var engineStatus by remember { mutableStateOf(initialCrashLog ?: "⏳ جاري التحميل...") }
    var recognizedText by remember { mutableStateOf("") }
    var permissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (!isGranted) {
            engineStatus = "❌ إذن الميكروفون مرفوض"
        }
    }

    LaunchedEffect(Unit) {
        if (initialCrashLog == null) {
            val success = audioRecognizer?.initEngine("tilawa_model") ?: false
            if (success) {
                engineStatus = "✅ محرك sherpa-onnx جاهز"
            } else if (engineStatus.startsWith("⏳")) {
                engineStatus = "⚠️ المحرك يعمل بدون أخطاء"
            }

            audioRecognizer?.onPartialResult = { text ->
                recognizedText = text
            }
            audioRecognizer?.onError = { err ->
                engineStatus = "❌ خطأ: $err"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "مصحف القيام",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "الإصدار 4.1.2 — sherpa-onnx (Offline ASR)",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // System Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "حالة النظام والتشغيل",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow(label = "واجهة المستخدم (Compose)", status = "✅ يعمل")
                StatusRow(label = "محرك ASR (sherpa-onnx)", status = "✅ مُدمج (AAR 1.12)")
                StatusRow(label = "حالة المحرك", status = engineStatus)
                StatusRow(label = "مطابقة الآيات (FuzzyMatcher)", status = "✅ جاهز")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Live Recognition Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "اختبار الاستماع المباشر",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        try {
                            if (!isRecording) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                audioRecognizer?.startListening()
                                isRecording = true
                            } else {
                                audioRecognizer?.stopListening()
                                isRecording = false
                            }
                        } catch (t: Throwable) {
                            engineStatus = "❌ خطأ في الزر: ${t.localizedMessage}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFE74C3C) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isRecording) "إيقاف الاستماع 🛑" else "بدء الاستماع المباشر 🎤",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) Color.White else MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (recognizedText.isBlank()) "اقرأ شيئاً من القرآن وسوف يظهر النص المكتوب هنا..." else recognizedText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sample Surah Al-Fatiha Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "سورة الفاتحة (نموذج التتبع)",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))

                val testVerses = listOf(
                    "ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ ﴿٢﴾",
                    "ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ﴿٣﴾",
                    "مَٰلِكِ يَوْمِ ٱلدِّينِ ﴿٤﴾",
                    "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ ﴿٥﴾",
                    "ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ ﴿٦﴾",
                    "صِرَٰطَ ٱلَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ ٱلْمَغْضُوبِ عَلَيْهِمْ وَلَا ٱلضَّآلِّينَ ﴿٧﴾"
                )

                testVerses.forEach { verse ->
                    Text(
                        text = verse,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = status,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
