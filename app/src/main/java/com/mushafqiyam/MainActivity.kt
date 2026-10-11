package com.mushafqiyam

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
        const val APP_VERSION = "0.5.1.0"
    }

    private var audioRecognizer: AudioRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
    val activity = context as? ComponentActivity
    var isListening by remember { mutableStateOf(false) }
    var engineStatus by remember { mutableStateOf("⏳ جاري تهيئة المصحف والمحرك...") }
    var recognizedText by remember { mutableStateOf("") }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    // Mushaf Viewer & Full-Mushaf Tracking State
    var currentPage by remember { mutableIntStateOf(1) }
    var activeSurah by remember { mutableIntStateOf(1) }
    var activeAyah by remember { mutableIntStateOf(1) }
    var activeWordProgress by remember { mutableFloatStateOf(0f) }
    var isDiscoveryMode by remember { mutableStateOf(true) }
    var isScrollMode by remember { mutableStateOf(true) }

    LaunchedEffect(isScrollMode) {
        if (isScrollMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

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
            MushafPageRepository.initRepository(context) { progressMsg ->
                engineStatus = progressMsg
            }
            engineStatus = "⏳ جاري بناء الفهرس العكسي لكامل المصحف (6,236 آية)..."
            QuranVocabularyFilter.initializeFullMushaf(MushafPageRepository.getAllVerses())
            QuranVocabularyFilter.enterDiscoveryMode("Initial Startup")
            isDiscoveryMode = true

            engineStatus = "⏳ جاري تهيئة محرك FastConformer القرآني..."
            val asrOk = audioRecognizer?.initEngine("tilawa_model") ?: false
            val pageCount = MushafPageRepository.getAvailableLocalPageCount(context)
            engineStatus = if (asrOk) {
                "✅ المحرك جاهز (6,236 آية) | الصفحات المحفوظة: $pageCount / 604"
            } else {
                "⚠️ المحرك بوضع الحماية | الصفحات المحفوظة: $pageCount / 604"
            }
        }
    }

    var matchSimilarityText by remember {
        mutableStateOf("🔍 وضع الاكتشاف الشامل")
    }
    var graceEmptyFrames by remember { mutableIntStateOf(0) }

    val handlePartialResult: (String) -> Unit = { rawText ->
        if (rawText.isNotBlank()) {
            val trackingResult = QuranVocabularyFilter.filterAndTrackFullMushaf(rawText)
            val filteredText = trackingResult.filteredText
            isDiscoveryMode = (trackingResult.recitationMode == QuranVocabularyFilter.RecitationMode.DISCOVERY)

            if (filteredText.isNotBlank()) {
                val fullText = if (recognizedText.isEmpty()) filteredText else "$recognizedText $filteredText"
                val textWords = fullText.split(" ")
                recognizedText = if (textWords.size > 25) textWords.takeLast(25).joinToString(" ") else fullText
                AppLogger.i("ASRFilter", "Filtered Quranic text: $filteredText")

                val mv = trackingResult.matchedVerse
                if (mv != null) {
                    graceEmptyFrames = 0
                    val prevPage = currentPage

                    activeSurah = mv.surah
                    activeAyah = mv.ayah
                    activeWordProgress = if (trackingResult.totalWordsInAyah > 1) {
                        (trackingResult.currentWordInAyah.toFloat() / trackingResult.totalWordsInAyah.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }

                    if (mv.page != prevPage) {
                        currentPage = mv.page
                        AppLogger.i("PageTurn", "Auto-flipped Mushaf page from $prevPage to ${mv.page}")
                    }

                    if (trackingResult.fatihahCompletedNow) {
                        matchSimilarityText = "🔍 اكتملت الفاتحة — بانتظار اكتشاف السورة/الآية التالية..."
                    } else if (trackingResult.discoveredNewLocation) {
                        matchSimilarityText = "🧭 تم اكتشاف: ${mv.surahName} — آية ${mv.ayah}"
                    } else {
                        matchSimilarityText = "🎯 ${mv.surahName} — آية ${mv.ayah}"
                    }
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
    var showJumpDialog by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("mushaf_prefs", Context.MODE_PRIVATE) }
    var showSplashDialog by remember {
        mutableStateOf(prefs.getBoolean("show_splash_dialog", true))
    }

    if (showDiagnosticsPanel) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsPanel = false },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsPanel = false }) {
                    Text("إغلاق")
                }
            },
            title = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Text("السجلات والتشخيص", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    ) {
                        Text(text = engineStatus, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        Text(text = memoryStatus, fontSize = 10.sp, color = Color(0xFF455A64), fontFamily = FontFamily.Monospace)
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
                                    .height(200.dp)
                                    .background(Color.Black, RoundedCornerShape(4.dp))
                                    .padding(6.dp)
                            ) {
                                items(logs) { entry ->
                                    val color = when (entry.level) {
                                        "ERROR" -> Color(0xFFFF5252)
                                        "WARN" -> Color(0xFFFFD740)
                                        else -> if (entry.message.contains("Confirmed") || entry.message.contains("Auto-flipped") || entry.message.contains("🎯")) Color(0xFF69F0AE) else Color(0xFFE0E0E0)
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
        )
    }

    if (showJumpDialog) {
        SurahPagePickerDialog(
            currentPage = currentPage,
            onDismiss = { showJumpDialog = false },
            onSelectPage = { page, surah, ayah ->
                currentPage = page
                activeSurah = surah
                activeAyah = ayah
                QuranVocabularyFilter.resetPointerToSurahAyah(surah, ayah)
                isDiscoveryMode = false
                showJumpDialog = false
            }
        )
    }

    if (showSplashDialog) {
        SplashScreenDialog(
            onDismiss = { showSplashDialog = false }
        )
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Row(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            // Left Navigation Rail
            Surface(
                color = Color(0xFF1B5E20),
                modifier = Modifier.fillMaxHeight().width(72.dp)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column(
                        modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { performCompleteExit(activity, audioRecognizer) }) {
                            Icon(Icons.Outlined.PowerSettingsNew, "خروج", tint = Color.White)
                        }

                        IconButton(onClick = { isScrollMode = !isScrollMode }) {
                            Icon(if (isScrollMode) Icons.Outlined.FormatLineSpacing else Icons.Outlined.AutoStories, "وضع القراءة", tint = Color.White)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            IconButton(onClick = { if (currentPage < 604) currentPage++ }) {
                                Icon(Icons.Default.ChevronRight, "التالي", tint = Color.White)
                            }

                            IconButton(
                                onClick = { showJumpDialog = true },
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Icon(Icons.Outlined.Bookmarks, contentDescription = "السور", tint = Color.White)
                            }

                            IconButton(onClick = { if (currentPage > 1) currentPage-- }) {
                                Icon(Icons.Default.ChevronLeft, "السابق", tint = Color.White)
                            }
                        }

                        if (!hasMicPermission) {
                            IconButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                                Icon(Icons.Outlined.MicNone, "صلاحية الميكروفون", tint = Color.Gray)
                            }
                        } else {
                            IconButton(onClick = {
                                if (isListening) {
                                    audioRecognizer?.stopListening()
                                    isListening = false
                                } else {
                                    if (!isDiscoveryMode) {
                                        QuranVocabularyFilter.resetPointerToSurahAyah(activeSurah, activeAyah)
                                    } else {
                                        QuranVocabularyFilter.enterDiscoveryMode("Start Listening")
                                    }
                                    val started = audioRecognizer?.startListening() == true
                                    isListening = started
                                }
                            }) {
                                Icon(
                                    if (isListening) Icons.Default.GraphicEq else Icons.Outlined.MicNone,
                                    "استماع",
                                    tint = if (isListening) Color(0xFF00E676) else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxHeight().weight(1f)
        ) {
            // Live Audio Volume Wave Bar when listening
            if (isListening) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
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

            // Main Interactive Mushaf Page Viewer
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MushafPageViewer(
                    currentPage = currentPage,
                    activeSurah = activeSurah,
                    activeAyah = activeAyah,
                    wordProgress = activeWordProgress,
                    isScrollMode = isScrollMode,
                    onPageChanged = { newPage ->
                        currentPage = newPage
                        val pageVerses = MushafPageRepository.getPageVerses(newPage)
                        val hasCurrentActive = pageVerses.any { it.surah == activeSurah && it.ayah == activeAyah }
                        if (!hasCurrentActive && pageVerses.isNotEmpty()) {
                            val first = pageVerses.first()
                            activeSurah = first.surah
                            activeAyah = first.ayah
                            QuranVocabularyFilter.resetPointerToSurahAyah(first.surah, first.ayah)
                            isDiscoveryMode = false
                        }
                    },
                    onAyahTapped = { page, surah, ayah ->
                        currentPage = page
                        activeSurah = surah
                        activeAyah = ayah
                        QuranVocabularyFilter.resetPointerToSurahAyah(surah, ayah)
                        isDiscoveryMode = false
                    },
                    onFiveTap = {
                        showDiagnosticsPanel = true
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (!isLandscape) {
                // Fixed Bottom Bar
                Surface(
                    color = Color(0xFF1B5E20),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { performCompleteExit(activity, audioRecognizer) }) {
                                Icon(Icons.Outlined.PowerSettingsNew, "خروج", tint = Color.White)
                            }

                            IconButton(onClick = { isScrollMode = !isScrollMode }) {
                                Icon(if (isScrollMode) Icons.Outlined.FormatLineSpacing else Icons.Outlined.AutoStories, "وضع القراءة", tint = Color.White)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                IconButton(onClick = { if (currentPage < 604) currentPage++ }) {
                                    Icon(Icons.Default.ChevronRight, "التالي", tint = Color.White)
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clickable { showJumpDialog = true }.padding(horizontal = 8.dp)
                                ) {
                                    val sName = if (MushafPageRepository.isInitialized) MushafPageRepository.getSurahName(activeSurah) else ""
                                    Text(sName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("ص $currentPage", color = Color(0xFFFFF59D), fontSize = 12.sp)
                                }

                                IconButton(onClick = { if (currentPage > 1) currentPage-- }) {
                                    Icon(Icons.Default.ChevronLeft, "السابق", tint = Color.White)
                                }
                            }

                            if (!hasMicPermission) {
                                IconButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                                    Icon(Icons.Outlined.MicNone, "صلاحية الميكروفون", tint = Color.Gray)
                                }
                            } else {
                                IconButton(onClick = {
                                    if (isListening) {
                                        audioRecognizer?.stopListening()
                                        isListening = false
                                    } else {
                                        if (!isDiscoveryMode) {
                                            QuranVocabularyFilter.resetPointerToSurahAyah(activeSurah, activeAyah)
                                        } else {
                                            QuranVocabularyFilter.enterDiscoveryMode("Start Listening")
                                        }
                                        val started = audioRecognizer?.startListening() == true
                                        isListening = started
                                    }
                                }) {
                                    Icon(
                                        if (isListening) Icons.Default.GraphicEq else Icons.Outlined.MicNone,
                                        "استماع",
                                        tint = if (isListening) Color(0xFF00E676) else Color.White
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

private fun performCompleteExit(activity: ComponentActivity?, audioRecognizer: AudioRecognizer?) {
    try {
        audioRecognizer?.release()
    } catch (_: Exception) {}
    activity?.finishAndRemoveTask()
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
}

@Composable
fun SplashScreenDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mushaf_prefs", Context.MODE_PRIVATE) }
    var doNotShowAgain by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (doNotShowAgain) {
                prefs.edit().putBoolean("show_splash_dialog", false).commit()
            }
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFCFDF9),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "مُصْحَفُ القِيَام",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "تطبيق رقمي يتابع تلاوتك في صلاة القيام والتهجد، ويظلل السطر الذي تقرأه تلقائياً دون حاجة إلى لمس الجهاز.",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF263238),
                        lineHeight = 24.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "كيف تستخدم التطبيق؟\n" +
                                "موضع الجهاز: ضع هاتفك أو جهازك اللوحي (التابلت) على حاملٍ في مستوى نظرك وقريباً من موضع صلاتك.\n" +
                                "بدء القراءة: اختر السورة أو الصفحة التي تريد أن تبدأ منها، ثم اضغط زر الاستماع.\n" +
                                "أثناء الصلاة: اقرأ بتمهلٍ واطمئنان؛ سيتولى التطبيق تحريك الآيات وصفحات المصحف تباعاً مع جريان صوتك.",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF37474F),
                        lineHeight = 23.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "يعمل التطبيق محلياً بالكامل دون اتصال بالإنترنت، وهو في مرحلة التطوير والتجريب.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF546E7A),
                        lineHeight = 21.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { doNotShowAgain = !doNotShowAgain }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = doNotShowAgain,
                            onCheckedChange = { doNotShowAgain = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF1B5E20)
                            )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "عدم إظهار هذه الشاشة في المستقبل",
                            fontSize = 13.sp,
                            color = Color(0xFF455A64)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (doNotShowAgain) {
                                prefs.edit().putBoolean("show_splash_dialog", false).commit()
                            }
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1B5E20)
                        )
                    ) {
                        Text(
                            text = "بدء التشغيل",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
