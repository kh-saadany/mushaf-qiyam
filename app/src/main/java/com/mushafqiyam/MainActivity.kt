package com.mushafqiyam

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
        const val APP_VERSION = "4.4.0"
    }

    private var audioRecognizer: AudioRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "=== onCreate started ===")
        Log.i(TAG, "App Version: $APP_VERSION (Live Audio-to-Text Pipeline)")
        Log.i(TAG, "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        Log.i(TAG, "Android API: ${android.os.Build.VERSION.SDK_INT}")

        audioRecognizer = AudioRecognizer(this)

        setContent {
            MushafQiyamTheme {
                MainAppScreen(audioRecognizer = audioRecognizer)
            }
        }
        Log.i(TAG, "setContent completed successfully")
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecognizer?.release()
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
fun MainAppScreen(audioRecognizer: AudioRecognizer?) {
    val scrollState = rememberScrollState()

    var isRecording by remember { mutableStateOf(false) }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    var engineStatus by remember { mutableStateOf("⏳ جاري تهيئة محرك التلاوة العربي...") }
    var recognizedText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("جاهز للاستماع والتعرف") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            audioRecognizer?.onAudioLevel = { level ->
                audioLevel = level
            }
            audioRecognizer?.onPartialResult = { text ->
                recognizedText = text
            }
            audioRecognizer?.onError = { err ->
                statusMessage = "❌ $err"
                isRecording = false
            }
            audioRecognizer?.startListening()
            isRecording = true
            statusMessage = "🎤 يستمع ويحول الصوت إلى نص..."
        } else {
            statusMessage = "❌ إذن الميكروفون مرفوض"
        }
    }

    LaunchedEffect(Unit) {
        val success = audioRecognizer?.initEngine("tilawa_model") ?: false
        if (success) {
            engineStatus = "✅ محرك Sherpa-ONNX ونموذج Tilawa جاهز"
        } else {
            engineStatus = "⚠️ المحرك يعمل بوضع الحماية من الانهيار"
        }
    }

    // Pulsing animation for mic indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

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
            text = "الإصدار ${MainActivity.APP_VERSION} — Live ASR & Quran Tracker",
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
                    text = "حالة النظام",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow(label = "واجهة المستخدم (Compose)", status = "✅ يعمل")
                StatusRow(label = "التقاط الصوت (16kHz Mono)", status = "✅ يعمل")
                StatusRow(label = "محرك ASR اللحظي", status = engineStatus)
                StatusRow(label = "مطابقة الآيات (FuzzyMatcher)", status = "✅ جاهز")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Live Audio Test Card
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
                    text = "اختبار الاستماع المباشر والتعرف",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Audio level indicator
                if (isRecording) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .scale(1f + audioLevel * 0.5f)
                            .scale(if (audioLevel > 0.1f) pulseScale else 1f)
                            .clip(CircleShape)
                            .background(
                                Color(0xFFF4D03F).copy(
                                    alpha = 0.3f + audioLevel * 0.7f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎤",
                            fontSize = 36.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "مستوى الصوت: ${(audioLevel * 100).toInt()}%",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!isRecording) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            audioRecognizer?.stopListening()
                            isRecording = false
                            audioLevel = 0f
                            statusMessage = "جاهز للاستماع"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) Color(0xFFE74C3C) else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (isRecording) "إيقاف 🛑" else "بدء الاستماع والتعرف 🎤",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) Color.White else MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusMessage,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Output Recognized Text Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF0A2744)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "النص المتعكف من التلاوة المباشرة:",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (recognizedText.isBlank()) "اقرأ شيئاً من القرآن وسوف يظهر النص المكتوب هنا فوراً..." else recognizedText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (recognizedText.isBlank()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
