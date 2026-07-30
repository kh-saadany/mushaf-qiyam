package com.mushafqiyam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
        const val APP_VERSION = "5.0.0"
    }

    private var audioRecognizer: AudioRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "Mushaf Qiyam App Started (Version: $APP_VERSION)")

        audioRecognizer = AudioRecognizer(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppScreen(
                        appVersion = APP_VERSION,
                        audioRecognizer = audioRecognizer
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecognizer?.release()
        AppLogger.i(TAG, "Mushaf Qiyam App Destroyed")
    }
}

@Composable
fun MainAppScreen(
    appVersion: String,
    audioRecognizer: AudioRecognizer?
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var engineStatus by remember { mutableStateOf("جاري تهيئة المحرك...") }
    var recognizedText by remember { mutableStateOf("") }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            AppLogger.i("UI", "Microphone permission granted")
        } else {
            AppLogger.w("UI", "Microphone permission denied by user")
        }
    }

    LaunchedEffect(Unit) {
        engineStatus = "⏳ جاري تهيئة محرك الذكاء الاصطناعي..."
        val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            audioRecognizer?.initEngine("tilawa_model") ?: false
        }
        if (success) {
            engineStatus = "✅ محرك Sherpa-ONNX ونموذج FastConformer جاهز"
        } else {
            engineStatus = "⚠️ المحرك يعمل بوضع الحماية من الانهيار"
        }
    }

    val sampleVerses = remember { QuranData.getSampleVerses() }
    var activeVerseIndex by remember { mutableIntStateOf(-1) }
    var matchSimilarityText by remember { mutableStateOf("") }

    audioRecognizer?.onAudioLevel = { level -> audioLevel = level }
    audioRecognizer?.onPartialResult = { text ->
        if (text.isNotBlank()) {
            recognizedText = if (recognizedText.isEmpty()) text else "$recognizedText $text"
            
            // Perform live fuzzy matching against Quran verses constrained by current active verse
            val match = FuzzyMatcher.matchVerse(text, sampleVerses, currentIndex = activeVerseIndex)
            if (match != null) {
                activeVerseIndex = match.verseIndex
                matchSimilarityText = "🎯 مطابقة الآية ${(match.verseIndex + 1)} (نسبة التشابه: ${(match.similarity * 100).toInt()}%)"
                AppLogger.i("VerseMatch", "Matched verse [${match.verseIndex + 1}]: ${match.verseText}")
            }
        }
    }
    audioRecognizer?.onError = { err -> engineStatus = err }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Text(
            text = "مصحف القيام",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "الإصدار $appVersion (محرك تلاوة ONNX المباشر)",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Engine Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = engineStatus,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                if (matchSimilarityText.isNotEmpty()) {
                    Text(
                        text = matchSimilarityText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mic Permission / Start Button
        if (!hasMicPermission) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("منح صلاحية الميكروفون")
            }
        } else {
            Button(
                onClick = {
                    if (isListening) {
                        audioRecognizer?.stopListening()
                        isListening = false
                        AppLogger.i("UI", "User clicked Stop Listening")
                    } else {
                        recognizedText = ""
                        activeVerseIndex = -1
                        matchSimilarityText = ""
                        audioRecognizer?.startListening()
                        isListening = true
                        AppLogger.i("UI", "User clicked Start Listening")
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isListening) "إيقاف الاستماع" else "بدء الاستماع والتتبع التفاعلي")
            }
        }

        // Live Audio Volume Wave Bar
        if (isListening) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.LightGray)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = audioLevel.coerceIn(0.05f, 1.0f))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive Quran Display Box with Active Verse Highlighting
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.2f),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                Text(
                    text = "📖 المصحف التفاعلي (متابعة التلاوة الحية):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Divider(modifier = Modifier.padding(vertical = 6.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(sampleVerses) { index, verse ->
                        val isActive = index == activeVerseIndex
                        val bgColor = if (isActive) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface
                        val borderColor = if (isActive) Color(0xFF4CAF50) else Color.Transparent
                        val textColor = if (isActive) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = bgColor),
                            border = if (isActive) androidx.compose.foundation.BorderStroke(2.dp, borderColor) else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "﴿${index + 1}﴾",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) Color(0xFF2E7D32) else Color.Gray
                                )
                                Text(
                                    text = verse,
                                    fontSize = 18.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = textColor,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Recognized Raw Text Stream Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Text(
                    text = "النص الذي تم التعرف عليه من التلاوة المباشرة:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.TopEnd
                ) {
                    Text(
                        text = if (recognizedText.isEmpty()) "في انتظار التلاوة الصوتية..." else recognizedText,
                        fontSize = 15.sp,
                        color = if (recognizedText.isEmpty()) Color.Gray else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Collapsible Diagnostic Logs Console Section
        DiagnosticLogsConsole(context = context)
    }
}

@Composable
fun DiagnosticLogsConsole(context: Context) {
    var isExpanded by remember { mutableStateOf(false) }
    val logsList by AppLogger.logs.collectAsState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "📋 سجل التشخيص الفني (Diagnostic Console - ${logsList.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Row {
                    if (isExpanded) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Mushaf Qiyam Logs", AppLogger.getAllLogsText())
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "تم نسخ سجل التشخيص بنجاح", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("نسخ السجل", fontSize = 11.sp, color = Color.Green)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Logs",
                        tint = Color.White
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Divider(color = Color.DarkGray)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(top = 4.dp)
                    ) {
                        items(logsList) { entry ->
                            val color = when (entry.level) {
                                "ERROR" -> Color(0xFFFF6B6B)
                                "WARN" -> Color(0xFFFFD93D)
                                else -> Color(0xFF6BCB77)
                            }
                            Text(
                                text = "[${entry.timestamp}] [${entry.tag}] ${entry.message}",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
