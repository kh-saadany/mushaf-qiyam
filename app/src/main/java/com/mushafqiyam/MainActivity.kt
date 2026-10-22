package com.mushafqiyam

import android.Manifest
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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MushafQiyam"
        const val APP_VERSION = "0.7.4.0"
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
    audioRecognizer: AudioRecognizer?
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val prefs = remember { context.getSharedPreferences("mushaf_prefs", Context.MODE_PRIVATE) }

    // Persistent Recitation Bookmark: load from SharedPreferences
    var savedBookmark by remember {
        val p = prefs.getInt("last_saved_page", 1)
        val s = prefs.getInt("last_saved_surah", 1)
        val a = prefs.getInt("last_saved_ayah", 1)
        mutableStateOf(Triple(p, s, a))
    }

    val saveBookmark: (Int, Int, Int) -> Unit = { p, s, a ->
        val gIdx = MushafPageRepository.getGlobalVerseIndex(s, a)
        prefs.edit()
            .putInt("last_saved_page", p)
            .putInt("last_saved_surah", s)
            .putInt("last_saved_ayah", a)
            .putInt("last_saved_global_verse", gIdx)
            .apply()
        savedBookmark = Triple(p, s, a)
        AppLogger.i("Bookmark", "Saved persistent bookmark: [Surah $s: Ayah $a, Page $p, GlobalVerse=$gIdx]")
    }

    val saveNextVerseAsBookmark: (Int, Int) -> Unit = { lastSurah, lastAyah ->
        val gIdx = MushafPageRepository.getGlobalVerseIndex(lastSurah, lastAyah)
        val nextV = if (gIdx >= 6235) { // 114:6 is index 6235
            MushafPageRepository.getVerse(2, 1) // Wrap around to Al-Baqarah 2:1
        } else {
            MushafPageRepository.getVerseByGlobalIndex(gIdx + 1) ?: MushafPageRepository.getVerse(2, 1)
        }
        if (nextV != null) {
            saveBookmark(nextV.page, nextV.surah, nextV.ayah)
            AppLogger.i("Bookmark", "Advanced next-verse bookmark saved: [Surah ${nextV.surahName}: Ayah ${nextV.ayah}, Page ${nextV.page}]")
        }
    }

    var isListening by remember { mutableStateOf(false) }
    var engineStatus by remember { mutableStateOf("⏳ جاري تهيئة المصحف والمحرك...") }
    var recognizedText by remember { mutableStateOf("") }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    // Mushaf Viewer & Full-Mushaf Tracking State initialized from saved bookmark
    var currentPage by remember { mutableIntStateOf(savedBookmark.first) }
    var activeSurah by remember { mutableIntStateOf(savedBookmark.second) }
    var activeAyah by remember { mutableIntStateOf(savedBookmark.third) }
    var activeWordProgress by remember { mutableFloatStateOf(0f) }
    var isDiscoveryMode by remember { mutableStateOf(true) }
    var isScrollMode by remember { mutableStateOf(false) } // Default: Horizontal page flip mode

    // Smart Prayer Session Manager State
    val prayerManager = remember { PrayerSessionManager() }
    var prayerBannerText by remember { mutableStateOf<String?>(null) }
    var showSplashDialog by remember { mutableStateOf(true) }
    var showPrayerSetupDialog by remember { mutableStateOf(false) }
    var showQiyamNextPairDialog by remember { mutableStateOf(false) }
    var hasRecitedAnyVerse by remember { mutableStateOf(false) }
    var lastMatchedSpeechTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastActiveRecitationStreamTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isScrollMode) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
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
            val started = audioRecognizer?.startListening() == true
            isListening = started
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

            // Set saved bookmark for continuity priority in DISCOVERY mode
            val savedGIdx = MushafPageRepository.getGlobalVerseIndex(savedBookmark.second, savedBookmark.third)
            if (savedGIdx > 0) {
                QuranVocabularyFilter.setLastNonFatihahVerseIndex(savedGIdx)
            }

            QuranVocabularyFilter.enterDiscoveryMode("Initial Startup")
            isDiscoveryMode = true

            engineStatus = "⏳ جاري تهيئة محرك Zipformer2 الفونيمي..."
            val asrOk = audioRecognizer?.initEngine("tilawa_model") ?: false
            val pageCount = MushafPageRepository.getAvailableLocalPageCount(context)
            engineStatus = if (asrOk) {
                "✅ المحرك جاهز (6,236 آية) | الصفحات المحفوظة: $pageCount / 604"
            } else {
                "⚠️ المحرك بوضع الحماية | الصفحات المحفوظة: $pageCount / 604"
            }
        }
    }

    LaunchedEffect(currentPage) {
        QuranVocabularyFilter.currentDisplayedPage = currentPage
    }

    var graceEmptyFrames by remember { mutableIntStateOf(0) }

    // Page-Turn FSM and Anti-Jitter Hysteresis State
    var pendingTargetPage by remember { mutableIntStateOf(-1) }
    var pendingPageConfirmCount by remember { mutableIntStateOf(0) }
    var lastPageTurnTimeMs by remember { mutableLongStateOf(0L) }
    var earlyTurnTriggeredForPage by remember { mutableIntStateOf(-1) }

    var activeLineNumber by remember { mutableIntStateOf(1) }
    var activeLinePage by remember { mutableIntStateOf(1) }
    var lastLineChangeTimeMs by remember { mutableLongStateOf(0L) }
    var backwardConfirmFrames by remember { mutableIntStateOf(0) }

    val executePrayerAction: (PrayerAction) -> Unit = { action ->
        when (action) {
            is PrayerAction.JumpToVerse -> {
                currentPage = action.page
                activeSurah = action.surah
                activeAyah = action.ayah
                activeWordProgress = 0f
                val candidateLine = MushafPageRepository.findAyahLinesOnPage(action.page, action.surah, action.ayah, 0f).first?.lineNumber
                activeLinePage = action.page
                activeLineNumber = candidateLine ?: 1
                if (action.resetTracking) {
                    QuranVocabularyFilter.resetPointerToSurahAyah(action.surah, action.ayah)
                    isDiscoveryMode = false
                }
                prayerBannerText = prayerManager.topBannerText
            }
            is PrayerAction.EnterDiscovery -> {
                QuranVocabularyFilter.enterDiscoveryMode("Prayer plan auto discovery")
                isDiscoveryMode = true
                prayerBannerText = prayerManager.topBannerText
            }
            is PrayerAction.StopListening -> {
                audioRecognizer?.stopListening()
                isListening = false
                prayerBannerText = prayerManager.topBannerText
            }
            is PrayerAction.ShowQiyamNextPairDialog -> {
                showQiyamNextPairDialog = true
                currentPage = 1
                activeSurah = 1
                activeAyah = 1
                activeWordProgress = 0f
                activeLineNumber = 1
                activeLinePage = 1
                QuranVocabularyFilter.resetPointerToSurahAyah(1, 1)
                isDiscoveryMode = false
                prayerBannerText = prayerManager.topBannerText
            }
            is PrayerAction.NoAction -> {
                prayerBannerText = prayerManager.topBannerText
            }
        }
    }

    LaunchedEffect(isListening, prayerManager.appMode) {
        while (isListening && prayerManager.appMode == AppMode.PRAYER) {
            delay(1000L)
            val action = prayerManager.checkSilenceRuku(
                lastMatchedSpeechTimeMs = lastMatchedSpeechTimeMs,
                onSaveBookmark = saveBookmark
            )
            executePrayerAction(action)
        }
    }

    val handlePartialResult: (String) -> Unit = handlePartialResult@{ rawText ->
        if (rawText.isNotBlank()) {
            // 1. Prayer Standby Wakeup Check
            if (prayerManager.appMode == AppMode.PRAYER && (prayerManager.currentPhase == PrayerPhase.FATIHAH_STANDBY || prayerManager.currentPhase == PrayerPhase.QIYAM_PAIR_COMPLETE)) {
                if (!prayerManager.checkStandbyWakeup(rawText)) {
                    // Reciter in standby; highlight remains on 1:1, ignore non-Fatihah sounds
                    prayerBannerText = prayerManager.topBannerText
                    return@handlePartialResult
                } else {
                    // Woke up on Fatihah opening/verse!
                    showQiyamNextPairDialog = false
                    prayerBannerText = prayerManager.topBannerText
                    QuranVocabularyFilter.resetPointerToSurahAyah(1, 1)
                    currentPage = 1
                    activeSurah = 1
                    activeAyah = 1
                    activeWordProgress = 0f
                    activeLineNumber = 1
                    activeLinePage = 1
                    isDiscoveryMode = false
                }
            }

            val trackingResult = QuranVocabularyFilter.filterAndTrackFullMushaf(rawText)

            val cleanStream = rawText.replace(" ", "")
            val isDhikrOrIntroductory = trackingResult.isPrayerTransition || trackingResult.isTasleem ||
                cleanStream.length < 4 ||
                rawText.contains("بسم") ||
                rawText.contains("رحمن") ||
                rawText.contains("رحيم") ||
                rawText.contains("اعوذ") ||
                rawText.contains("أعوذ") ||
                rawText.contains("شيطان") ||
                rawText.contains("امين") ||
                rawText.contains("آمين") ||
                rawText.contains("ءامين") ||
                cleanStream.startsWith("الله") ||
                cleanStream.startsWith("لله") ||
                cleanStream.startsWith("سمع") ||
                cleanStream.startsWith("سبحان") ||
                cleanStream.startsWith("ربنا") ||
                cleanStream.startsWith("ربنو") ||
                cleanStream.startsWith("سليكم") ||
                cleanStream.startsWith("سلام") ||
                cleanStream.contains("اللهاكبر") ||
                cleanStream.contains("اللهكبر") ||
                cleanStream.contains("سمعلله") ||
                cleanStream.contains("سمعالله")
            if (!isDhikrOrIntroductory) {
                lastActiveRecitationStreamTimeMs = System.currentTimeMillis()
            }

            // 2. Prayer Dhikr & Tasleem Isolation Hooks
            if (prayerManager.appMode == AppMode.PRAYER) {
                if (trackingResult.isTasleem) {
                    val action = prayerManager.onTasleem(saveBookmark)
                    executePrayerAction(action)
                    return@handlePartialResult
                }
                if (trackingResult.isPrayerTransition) {
                    val action = prayerManager.onTakbeerOrTasmee(saveBookmark)
                    executePrayerAction(action)
                    return@handlePartialResult
                }
            }

            val filteredText = trackingResult.filteredText
            isDiscoveryMode = (trackingResult.recitationMode == QuranVocabularyFilter.RecitationMode.DISCOVERY)

            val mv = trackingResult.matchedVerse
            if (filteredText.isNotBlank() && mv != null) {
                hasRecitedAnyVerse = true
                val nowMs = System.currentTimeMillis()
                lastMatchedSpeechTimeMs = nowMs

                if (prayerManager.appMode == AppMode.PRAYER) {
                    prayerManager.onVerseTracked(mv.surah, mv.ayah, mv.page)
                    prayerBannerText = prayerManager.topBannerText
                }

                val fullText = if (recognizedText.isEmpty()) filteredText else "$recognizedText $filteredText"
                val textWords = fullText.split(" ")
                recognizedText = if (textWords.size > 25) textWords.takeLast(25).joinToString(" ") else fullText
                AppLogger.i("ASRFilter", "Filtered Quranic text: $filteredText")

                graceEmptyFrames = 0

                val isTrackingMode = (trackingResult.recitationMode == QuranVocabularyFilter.RecitationMode.TRACKING)
                val newCharProgress = trackingResult.charProgress.coerceIn(0f, 1f)

                if (mv.page == currentPage) {
                    // --- Case 1: Match on current displayed page ---
                    val isSameVerse = (mv.surah == activeSurah && mv.ayah == activeAyah)
                    val isNextVerse = (mv.surah == activeSurah && mv.ayah == activeAyah + 1) ||
                                      (mv.surah == activeSurah + 1 && mv.ayah == 1)
                    val isBackwardVerse = (mv.surah == activeSurah && mv.ayah < activeAyah)

                    var allowVerseUpdate = true
                    if (isTrackingMode && !trackingResult.discoveredNewLocation && isBackwardVerse) {
                        backwardConfirmFrames++
                        if (backwardConfirmFrames >= 6) {
                            backwardConfirmFrames = 0
                            allowVerseUpdate = true
                            AppLogger.i("UIHighlight", "Confirmed deliberate backward jump to [$activeSurah:${mv.ayah}]")
                        } else {
                            allowVerseUpdate = false
                        }
                    } else {
                        backwardConfirmFrames = 0
                    }

                    if (allowVerseUpdate) {
                        if (isSameVerse && isTrackingMode) {
                            // Monotonic progress ratchet inside same verse
                            activeWordProgress = maxOf(activeWordProgress, newCharProgress)
                        } else {
                            activeSurah = mv.surah
                            activeAyah = mv.ayah
                            activeWordProgress = newCharProgress
                        }

                        // Proportional Line Mapping & Strict Monotonic Line Ratchet on currentPage
                        val candidateLine = MushafPageRepository.findAyahLinesOnPage(currentPage, activeSurah, activeAyah, activeWordProgress).first?.lineNumber
                        if (candidateLine != null) {
                            if (activeLinePage != currentPage) {
                                activeLinePage = currentPage
                                activeLineNumber = candidateLine
                                lastLineChangeTimeMs = nowMs
                            } else if (isTrackingMode && !trackingResult.discoveredNewLocation) {
                                // Line Dwell Hysteresis: Never bounce upward to an earlier line during tracking on same page
                                if (candidateLine > activeLineNumber) {
                                    activeLineNumber = candidateLine
                                    lastLineChangeTimeMs = nowMs
                                }
                            } else {
                                activeLineNumber = candidateLine
                                lastLineChangeTimeMs = nowMs
                            }
                        }
                    }

                    // Early Bottom-Line Proactive Page Flip
                    val isLastVerseOnPage = run {
                        val pageVerses = MushafPageRepository.getPageVerses(currentPage)
                        pageVerses.isNotEmpty() && pageVerses.last().let { it.surah == mv.surah && it.ayah == mv.ayah }
                    }
                    val linesPair = MushafPageRepository.findAyahLinesOnPage(currentPage, mv.surah, mv.ayah, activeWordProgress)
                    val isBottomLine = (linesPair.second == null)

                    val isNearEndOfPage = (isLastVerseOnPage && isBottomLine) &&
                        trackingResult.totalWordsInAyah >= 3 &&
                        activeWordProgress >= 0.94f &&
                        currentPage < 604

                    if (isNearEndOfPage && earlyTurnTriggeredForPage != currentPage) {
                        if (pendingTargetPage == currentPage + 1) {
                            pendingPageConfirmCount++
                        } else {
                            pendingTargetPage = currentPage + 1
                            pendingPageConfirmCount = 1
                        }
                        if (pendingPageConfirmCount >= 2) {
                            val prevP = currentPage
                            currentPage = currentPage + 1
                            val nextPageVerses = MushafPageRepository.getPageVerses(currentPage)
                            if (nextPageVerses.isNotEmpty()) {
                                activeSurah = nextPageVerses.first().surah
                                activeAyah = nextPageVerses.first().ayah
                            }
                            activeWordProgress = 0f
                            activeLinePage = currentPage
                            activeLineNumber = 1
                            earlyTurnTriggeredForPage = prevP
                            lastPageTurnTimeMs = nowMs
                            pendingTargetPage = -1
                            pendingPageConfirmCount = 0
                            AppLogger.i("PageTurn", "Proactive early flip from $prevP to $currentPage (progress=$activeWordProgress)")
                        }
                    } else {
                        // Reset pending counters when settled on currentPage
                        if (pendingTargetPage != currentPage + 1 || activeWordProgress < 0.70f) {
                            pendingTargetPage = -1
                            pendingPageConfirmCount = 0
                        }
                    }
                } else {
                    // --- Case 2: Match on a different page (mv.page != currentPage) ---
                    // Resolve Page-Turn FSM decision FIRST before touching activeSurah/activeAyah
                    val isBackward = (mv.page < currentPage)
                    val isSinglePageForward = (mv.page == currentPage + 1)
                    var didFlip = false

                    if (isBackward) {
                        // Issue 2 Guard: Prevent spurious backward page flip caused by reciter finishing last verse of previous page
                        val isPrevPageTail = (mv.page == currentPage - 1) && run {
                            val prevPageVerses = MushafPageRepository.getPageVerses(mv.page)
                            if (prevPageVerses.isEmpty()) false
                            else {
                                val lastV = prevPageVerses.last()
                                val isLast = (lastV.surah == mv.surah && lastV.ayah == mv.ayah)
                                val isSecondLast = if (prevPageVerses.size >= 2) {
                                    val sl = prevPageVerses[prevPageVerses.size - 2]
                                    sl.surah == mv.surah && sl.ayah == mv.ayah
                                } else false
                                isLast || isSecondLast
                            }
                        }

                        val timeSinceLastFlip = nowMs - lastPageTurnTimeMs
                        if (isPrevPageTail && isTrackingMode && timeSinceLastFlip < 15000L && !trackingResult.discoveredNewLocation) {
                            AppLogger.w("PageTurn", "Suppressed spurious backward flip to prev page tail [${mv.surah}:${mv.ayah}] on page ${mv.page} (current=$currentPage, elapsed=${timeSinceLastFlip}ms)")
                            pendingTargetPage = -1
                            pendingPageConfirmCount = 0
                        } else {
                            // Backward Lock: Reject spurious backward flips during continuous TRACKING unless explicitly discovered after silence
                            val isSafeBackward = trackingResult.discoveredNewLocation || timeSinceLastFlip >= 5000L
                            if (isSafeBackward && (!isTrackingMode || timeSinceLastFlip >= 5000L)) {
                                if (pendingTargetPage == mv.page) {
                                    pendingPageConfirmCount++
                                } else {
                                    pendingTargetPage = mv.page
                                    pendingPageConfirmCount = 1
                                }
                                if (pendingPageConfirmCount >= 3 || trackingResult.discoveredNewLocation) {
                                    val prevP = currentPage
                                    currentPage = mv.page
                                    lastPageTurnTimeMs = nowMs
                                    pendingTargetPage = -1
                                    pendingPageConfirmCount = 0
                                    didFlip = true
                                    AppLogger.i("PageTurn", "Confirmed backward page turn from $prevP to ${mv.page}")
                                }
                            } else {
                                AppLogger.w("PageTurn", "Suppressed backward jitter flip to page ${mv.page} (current=$currentPage, elapsed=${timeSinceLastFlip}ms)")
                            }
                        }
                    } else if (isSinglePageForward) {
                        // Forward Debounce: Require 2 consecutive frames or immediate flip if previous page was completed
                        val wasLastVerseOnPrevPage = run {
                            val prevPageVerses = MushafPageRepository.getPageVerses(currentPage)
                            prevPageVerses.isNotEmpty() && prevPageVerses.last().let { it.surah == activeSurah && it.ayah == activeAyah }
                        }
                        val wasPrevPageFinished = (earlyTurnTriggeredForPage == currentPage || (wasLastVerseOnPrevPage && activeWordProgress >= 0.85f))
                        if (wasPrevPageFinished) {
                            val prevP = currentPage
                            currentPage = mv.page
                            lastPageTurnTimeMs = nowMs
                            pendingTargetPage = -1
                            pendingPageConfirmCount = 0
                            didFlip = true
                            AppLogger.i("PageTurn", "Immediate forward flip to page ${mv.page} after completed previous page")
                        } else {
                            if (pendingTargetPage == mv.page) {
                                pendingPageConfirmCount++
                            } else {
                                pendingTargetPage = mv.page
                                pendingPageConfirmCount = 1
                            }
                            if (pendingPageConfirmCount >= 2) {
                                val prevP = currentPage
                                currentPage = mv.page
                                lastPageTurnTimeMs = nowMs
                                pendingTargetPage = -1
                                pendingPageConfirmCount = 0
                                didFlip = true
                                AppLogger.i("PageTurn", "Debounced forward flip from $prevP to ${mv.page}")
                            }
                        }
                    } else {
                        // Multi-page forward jump (e.g. discovery)
                        if (trackingResult.discoveredNewLocation || pendingTargetPage == mv.page) {
                            val prevP = currentPage
                            currentPage = mv.page
                            lastPageTurnTimeMs = nowMs
                            pendingTargetPage = -1
                            pendingPageConfirmCount = 0
                            didFlip = true
                            AppLogger.i("PageTurn", "Jumped to discovered page ${mv.page} from $prevP")
                        } else {
                            pendingTargetPage = mv.page
                            pendingPageConfirmCount = 1
                        }
                    }

                    // Issue 1 Resolution: Synchronize activeSurah/activeAyah and line calculation with page turn decision
                    if (didFlip) {
                        // Flip executed to mv.page: now update activeSurah, activeAyah, and compute line on new page
                        activeSurah = mv.surah
                        activeAyah = mv.ayah
                        activeWordProgress = newCharProgress
                        val candidateLine = MushafPageRepository.findAyahLinesOnPage(currentPage, activeSurah, activeAyah, activeWordProgress).first?.lineNumber
                        activeLinePage = currentPage
                        activeLineNumber = candidateLine ?: 1
                        earlyTurnTriggeredForPage = -1
                    } else {
                        // Flip deferred (waiting for debounce or suppressed):
                        // Keep highlight steady and continuous on currentPage!
                        if (mv.page > currentPage) {
                            // Forward pending: keep progress at 100% of current page tail
                            activeWordProgress = 1.0f
                        }
                        // Do NOT touch activeSurah, activeAyah, or activeLineNumber. They remain on currentPage!
                    }
                }

                    if (trackingResult.fatihahCompletedNow) {
                        QuranVocabularyFilter.onFatihahCompleted()
                        audioRecognizer?.resetStream()
                        if (prayerManager.appMode == AppMode.PRAYER) {
                            val action = prayerManager.onFatihahCompleted(savedBookmark)
                            executePrayerAction(action)
                        }
                    }
            } else {
                graceEmptyFrames++
                if (graceEmptyFrames >= 7) {
                    graceEmptyFrames = 0
                }

                if (!isDhikrOrIntroductory &&
                    prayerManager.appMode == AppMode.PRAYER && prayerManager.currentPhase == PrayerPhase.SURAH_RECITATION) {
                    val action = prayerManager.recordMismatchInTarget()
                    executePrayerAction(action)
                }
            }

            val status = if (mv != null) "MATCH" else "HOLD(miss=$graceEmptyFrames)"
            val activeLineNum = if (activeLineNumber > 0 && activeLinePage == currentPage) {
                activeLineNumber
            } else if (activeSurah > 0 && activeAyah > 0) {
                MushafPageRepository.findAyahLinesOnPage(currentPage, activeSurah, activeAyah, activeWordProgress).first?.lineNumber
            } else null
            val wordInfo = if (mv != null) "${trackingResult.currentWordInAyah}/${trackingResult.totalWordsInAyah}" else "-/-"
            AppLogger.i(
                "UIHighlight",
                "[$status] Page=$currentPage | UI=[$activeSurah:$activeAyah] | Line=${activeLineNum ?: "-"} | Prog=${(activeWordProgress * 100).toInt()}% (Word=$wordInfo) | Mode=${trackingResult.mode} | Ratio=${"%.2f".format(trackingResult.confidence)}"
            )
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
    var showJumpDialog by remember { mutableStateOf(false) }

    if (showDiagnosticsPanel) {
        val logs by AppLogger.logs.collectAsState()
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
                activeWordProgress = 0f
                activeLineNumber = -1
                activeLinePage = page
                QuranVocabularyFilter.resetPointerToSurahAyah(surah, ayah)
                isDiscoveryMode = false
                showJumpDialog = false
                saveBookmark(page, surah, ayah)
            }
        )
    }

    if (showSplashDialog) {
        SplashScreenDialog(
            onOpenPrayerSetup = {
                showSplashDialog = false
                showPrayerSetupDialog = true
            },
            onStartFreeReading = {
                showSplashDialog = false
                prayerManager.exitPrayerMode()
                prayerBannerText = null
                if (!hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (!isListening) {
                    val started = audioRecognizer?.startListening() == true
                    isListening = started
                }
            }
        )
    }

    if (showPrayerSetupDialog) {
        PrayerSetupDialog(
            savedBookmark = savedBookmark,
            onDismiss = { showPrayerSetupDialog = false },
            onStartPrayer = { config ->
                showPrayerSetupDialog = false
                val action = prayerManager.startPrayerSession(config, savedBookmark)
                executePrayerAction(action)
                if (!hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (!isListening) {
                    val started = audioRecognizer?.startListening() == true
                    isListening = started
                }
            }
        )
    }

    if (showQiyamNextPairDialog) {
        val nextSName = MushafPageRepository.getSurahName(prayerManager.lastReadSurah)
        QiyamNextPairDialog(
            pairIndex = prayerManager.qiyamPairIndex,
            nextSurahName = nextSName,
            nextAyah = prayerManager.lastReadAyah,
            onContinueNextPair = {
                showQiyamNextPairDialog = false
                val action = prayerManager.startNextQiyamPair()
                executePrayerAction(action)
            },
            onFinishPrayer = {
                showQiyamNextPairDialog = false
                audioRecognizer?.stopListening()
                isListening = false
                prayerManager.exitPrayerMode()
                prayerBannerText = null
                showPrayerSetupDialog = true
            }
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
                        IconButton(onClick = {
                            if (prayerManager.appMode == AppMode.FREE_READING) {
                                if (hasRecitedAnyVerse) {
                                    saveNextVerseAsBookmark(activeSurah, activeAyah)
                                } else {
                                    saveBookmark(currentPage, activeSurah, activeAyah)
                                }
                            }
                            performCompleteExit(activity, audioRecognizer)
                        }) {
                            Icon(Icons.Outlined.PowerSettingsNew, "خروج", tint = Color.White)
                        }

                        IconButton(onClick = {
                            if (isListening) {
                                audioRecognizer?.stopListening()
                                isListening = false
                            }
                            showPrayerSetupDialog = true
                        }) {
                            Icon(Icons.Outlined.Settings, "الإعدادات", tint = Color.White)
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

            // Prayer Session Top Announcement Banner
            if (prayerManager.appMode == AppMode.PRAYER && !prayerBannerText.isNullOrBlank()) {
                Surface(
                    color = Color(0xFFE8F5E9),
                    border = BorderStroke(1.dp, Color(0xFF81C784)),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = prayerBannerText ?: "",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1B5E20),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center
                            )
                            IconButton(
                                onClick = {
                                    prayerManager.exitPrayerMode()
                                    prayerBannerText = null
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "إنهاء وضع الصلاة",
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Main Interactive Mushaf Page Viewer
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MushafPageViewer(
                    currentPage = currentPage,
                    activeSurah = activeSurah,
                    activeAyah = activeAyah,
                    wordProgress = activeWordProgress,
                    activeLineNumber = activeLineNumber,
                    isScrollMode = isScrollMode,
                    onPageChanged = { newPage ->
                        currentPage = newPage
                        val pageVerses = MushafPageRepository.getPageVerses(newPage)
                        val hasCurrentActive = pageVerses.any { it.surah == activeSurah && it.ayah == activeAyah }
                        if (!hasCurrentActive && pageVerses.isNotEmpty()) {
                            val first = pageVerses.first()
                            activeSurah = first.surah
                            activeAyah = first.ayah
                            activeWordProgress = 0f
                            activeLineNumber = -1
                            activeLinePage = newPage
                            QuranVocabularyFilter.resetPointerToSurahAyah(first.surah, first.ayah)
                            isDiscoveryMode = false
                        }
                    },
                    onAyahTapped = { page, surah, ayah ->
                        currentPage = page
                        activeSurah = surah
                        activeAyah = ayah
                        activeWordProgress = 0f
                        activeLineNumber = -1
                        activeLinePage = page
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
                            IconButton(onClick = {
                                if (prayerManager.appMode == AppMode.FREE_READING) {
                                    if (hasRecitedAnyVerse) {
                                        saveNextVerseAsBookmark(activeSurah, activeAyah)
                                    } else {
                                        saveBookmark(currentPage, activeSurah, activeAyah)
                                    }
                                }
                                performCompleteExit(activity, audioRecognizer)
                            }) {
                                Icon(Icons.Outlined.PowerSettingsNew, "خروج", tint = Color.White)
                            }

                            IconButton(onClick = {
                                if (isListening) {
                                audioRecognizer?.stopListening()
                                isListening = false
                            }
                            showPrayerSetupDialog = true
                        }) {
                            Icon(Icons.Outlined.Settings, "الإعدادات", tint = Color.White)
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
    onOpenPrayerSetup: () -> Unit,
    onStartFreeReading: () -> Unit
) {
    Dialog(
        onDismissRequest = onStartFreeReading,
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
                                "إعدادات الصلاة: اضبط عدد الركعات وخطة القراءة المسبقة، وسيتولى التطبيق إدارة الركعات والانتقال بين السور تلقائياً.\n" +
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

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOpenPrayerSetup,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1B5E20)
                            )
                        ) {
                            Text(
                                text = "الإعدادات",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        OutlinedButton(
                            onClick = onStartFreeReading,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.5.dp, Color(0xFF1B5E20))
                        ) {
                            Text(
                                text = "📖 ابدأ التلاوة",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QiyamNextPairDialog(
    pairIndex: Int,
    nextSurahName: String,
    nextAyah: Int,
    onContinueNextPair: () -> Unit,
    onFinishPrayer: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFFCFDF9),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "تقبل الله صلاتكم 🤲",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "اكتمل الشفع الحالي بنجاح. سيبدأ الشفع التالي من:\nسورة $nextSurahName (آية $nextAyah)",
                        fontSize = 15.sp,
                        color = Color(0xFF263238),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "الميكروفون مستمر في الاستماع؛ يمكنك البدء بقراءة الفاتحة مباشرة للقيام للشفع التالي تلقائياً، أو الضغط على الزر أدناه.",
                        fontSize = 13.sp,
                        color = Color(0xFF546E7A),
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onContinueNextPair,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                        ) {
                            Text(
                                text = "بدء الركعتين التاليتين من حيث توقفت",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                        OutlinedButton(
                            onClick = onFinishPrayer,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.5.dp, Color(0xFF1B5E20))
                        ) {
                            Text(
                                text = "العودة لشاشة الإعدادات",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}
