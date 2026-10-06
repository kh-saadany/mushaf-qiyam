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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
        const val APP_VERSION = "5.7.0"
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
                    color = Color(0xFFF5F5F0)
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
    var engineStatus by remember { mutableStateOf("⏳ جاري تهيئة المصحف والمحرك...") }
    var recognizedText by remember { mutableStateOf("") }
    var audioLevel by remember { mutableFloatStateOf(0f) }
    var repoReady by remember { mutableStateOf(false) }

    // Mushaf Viewer State (Page 1..604, Surah 1..114, Ayah 1..N)
    var currentPage by remember { mutableIntStateOf(1) }
    var activeSurah by remember { mutableIntStateOf(1) }
    var activeAyah by remember { mutableIntStateOf(1) }

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
        withContext(Dispatchers.IO) {
            val repoOk = MushafPageRepository.initRepository(context) { progressMsg ->
                engineStatus = progressMsg
            }
            repoReady = repoOk
            engineStatus = "⏳ جاري تهيئة محرك FastConformer القرآني..."
            val asrOk = audioRecognizer?.initEngine("tilawa_model") ?: false
            val pageCount = MushafPageRepository.getAvailableLocalPageCount(context)
            engineStatus = if (asrOk) {
                "✅ المحرك جاهز | صفحات المصحف المحفوظة: $pageCount / 604"
            } else {
                "⚠️ المحرك بوضع الحماية | صفحات المصحف المحفوظة: $pageCount / 604"
            }
        }
    }

    val sampleVerses = remember { QuranData.getSampleVerses() }
    var activeVerseIndex by remember { mutableIntStateOf(0) }
    var matchSimilarityText by remember { mutableStateOf("اضغط على أي آية في الصفحة لتظليلها أو اسحب لتقليب الصفحات") }
    var graceEmptyFrames by remember { mutableIntStateOf(0) }

    val syncSampleVerseToMushafPage: (Int) -> Unit = { sampleIdx ->
        // Sample verses 0..10 are Surah Ad-Duha (93:1..11), 11..18 are Surah Ash-Sharh (94:1..8) on page 596
        if (sampleIdx in 0..10) {
            currentPage = 596
            activeSurah = 93
            activeAyah = sampleIdx + 1
        } else if (sampleIdx in 11..18) {
            currentPage = 596
            activeSurah = 94
            activeAyah = sampleIdx - 10
        }
    }

    val handlePartialResult: (String) -> Unit = { rawText ->
        if (rawText.isNotBlank()) {
            val trackingResult = QuranVocabularyFilter.filterAndTrack(rawText, sampleVerses)
            val filteredText = trackingResult.filteredText

            if (filteredText.isNotBlank()) {
                val fullText = if (recognizedText.isEmpty()) filteredText else "$recognizedText $filteredText"
                val textWords = fullText.split(" ")
                recognizedText = if (textWords.size > 25) textWords.takeLast(25).joinToString(" ") else fullText
                AppLogger.i("ASRFilter", "Filtered Quranic text: $filteredText (Raw was: $rawText)")

                val targetVerse = trackingResult.matchedVerseIndex
                if (targetVerse != null) {
                    graceEmptyFrames = 0
                    activeVerseIndex = targetVerse
                    syncSampleVerseToMushafPage(targetVerse)
                    matchSimilarityText = "🎯 مطابقة الآية $activeSurah:$activeAyah (نسبة التشابه: ${(trackingResult.highestSimilarity * 100).toInt()}%)"
                    AppLogger.i("VerseMatch", "Confirmed verse [$activeSurah:$activeAyah]: ${sampleVerses[targetVerse]}")
                }
            } else {
                graceEmptyFrames++
                if (graceEmptyFrames >= 7) {
                    graceEmptyFrames = 0
                }
            }
        }
    }

    var memoryStatus by remember { mutableStateOf("RAM: في انتظار بدء التشغيل...") }

    audioRecognizer?.onAudioLevel = { level -> audioLevel = level }
    audioRecognizer?.onPartialResult = handlePartialResult
    audioRecognizer?.onMemoryUpdate = { mem -> memoryStatus = mem }
    audioRecognizer?.onError = { err ->
        AppLogger.e("UI", "AudioRecognizer error: $err")
        engineStatus = "⚠️ $err"
    }

    var showDiagnosticsPanel by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    val logs by AppLogger.logs.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Compact Top Bar: App Title + Mic Button + Diagnostics Toggle
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "مصحف القيام",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "V$appVersion",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    Text(
                        text = matchSimilarityText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF2E7D32),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!hasMicPermission) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("صلاحية الميكروفون", fontSize = 11.sp)
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
                                    activeVerseIndex = 0
                                    QuranVocabularyFilter.resetPointerToVerse(0)
                                    val started: Boolean = audioRecognizer?.startListening() == true
                                    if (started) {
                                        isListening = true
                                        AppLogger.i("UI", "User clicked Start Listening (FastConformer Quran)")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isListening) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isListening) "إيقاف" else "استماع", fontSize = 12.sp)
                        }
                    }

                    IconButton(
                        onClick = { showDiagnosticsPanel = !showDiagnosticsPanel },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showDiagnosticsPanel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "إظهار/إخفاء التشخيص"
                        )
                    }
                }
            }
        }

        // Live Audio Volume Wave Bar when listening
        if (isListening) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.LightGray)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = audioLevel.coerceIn(0.05f, 1.0f))
                        .background(Color(0xFF2E7D32))
                )
            }
        }

        // Main Interactive Mushaf Page Viewer (Occupies primary portrait area)
        MushafPageViewer(
            currentPage = currentPage,
            activeSurah = activeSurah,
            activeAyah = activeAyah,
            onPageChanged = { newPage ->
                currentPage = newPage
                val pageVerses = MushafPageRepository.getPageVerses(newPage)
                val hasCurrentActive = pageVerses.any { it.surah == activeSurah && it.ayah == activeAyah }
                if (!hasCurrentActive && pageVerses.isNotEmpty()) {
                    val first = pageVerses.first()
                    activeSurah = first.surah
                    activeAyah = first.ayah
                    matchSimilarityText = "📖 ${first.surahName} — الآية ${first.ayah} (صفحة $newPage)"
                }
            },
            onAyahTapped = { page, surah, ayah ->
                currentPage = page
                activeSurah = surah
                activeAyah = ayah
                val sName = MushafPageRepository.getSurahName(surah)
                matchSimilarityText = "🎯 تم تحديد: $sName — الآية $ayah (صفحة $page)"
                AppLogger.i("MushafUI", "User tapped Ayah [$surah:$ayah] on page $page")
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        // Collapsible Bottom Panel for Engine Status, RAM Diagnostics, Recognized Text & Logs
        AnimatedVisibility(visible = showDiagnosticsPanel) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    ) {
                        Text(
                            text = engineStatus,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = memoryStatus,
                            fontSize = 10.sp,
                            color = Color(0xFF455A64),
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .padding(6.dp)
                        ) {
                            Text(
                                text = if (recognizedText.isEmpty()) "النص المتعرف عليه سيظهر هنا..." else recognizedText,
                                fontSize = 12.sp,
                                color = if (recognizedText.isEmpty()) Color.Gray else Color.Black,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🛠️ السجلات (${logs.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showLogs = !showLogs }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { showLogs = !showLogs },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text(if (showLogs) "إخفاء السجل" else "عرض السجل", fontSize = 10.sp)
                                }
                                Button(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val logText = AppLogger.getAllLogsText()
                                        clipboard.setPrimaryClip(ClipData.newPlainText("MushafQiyam Logs", logText))
                                        Toast.makeText(context, "تم نسخ السجلات إلى الحافظة", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("نسخ السجل", fontSize = 10.sp)
                                }
                            }
                        }

                        AnimatedVisibility(visible = showLogs) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp)
                                    .height(110.dp)
                                    .background(Color.Black, RoundedCornerShape(4.dp))
                                    .padding(6.dp)
                            ) {
                                items(logs) { entry ->
                                    val color = when (entry.level) {
                                        "ERROR" -> Color(0xFFFF5252)
                                        "WARN" -> Color(0xFFFFD740)
                                        else -> if (entry.message.contains("Confirmed") || entry.message.contains("🎯")) Color(0xFF69F0AE) else Color(0xFFE0E0E0)
                                    }
                                    val displayText = "[${entry.timestamp}] [${entry.level}] ${entry.tag}: ${entry.message}"
                                    Text(
                                        text = displayText,
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
    }
}
