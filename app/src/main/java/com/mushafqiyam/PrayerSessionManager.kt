package com.mushafqiyam

import android.content.Context
import android.content.SharedPreferences

enum class AppMode {
    FREE_READING,
    PRAYER
}

enum class PrayerPhase {
    FATIHAH,               // Active tracking of Al-Fatihah
    FATIHAH_STANDBY,       // Screen on Fatihah awaiting reciter standing up / reciting any verse of Fatihah
    SURAH_RECITATION,      // Reciting Surah/Ayahs after Al-Fatihah
    RUKU_SUJUD,            // In Ruku or Sujud
    TASLEEM_COMPLETE,      // Prayer completed after Tasleem
    QIYAM_PAIR_COMPLETE    // 2-Rakah pair completed in Qiyam mode, awaiting next pair
}

enum class RakahPlanType {
    CONTINUE_PREVIOUS,     // Continue from saved bookmark (Rak'ah 1) or previous rak'ah (Rak'ah 2+)
    CUSTOM_SURAH_AYAH,     // User specified Surah and Ayah
    AUTO_DISCOVERY         // General discovery mode
}

data class RakahPlan(
    val planType: RakahPlanType,
    val targetSurah: Int = 1,
    val targetAyah: Int = 1
)

data class PrayerSessionConfig(
    val totalRakahs: Int = 2,
    val isQiyamMode: Boolean = false,
    val plans: List<RakahPlan> = listOf(
        RakahPlan(RakahPlanType.CONTINUE_PREVIOUS),
        RakahPlan(RakahPlanType.CONTINUE_PREVIOUS)
    )
)

sealed class PrayerAction {
    data class JumpToVerse(val page: Int, val surah: Int, val ayah: Int, val resetTracking: Boolean = true) : PrayerAction()
    object EnterDiscovery : PrayerAction()
    object StopListening : PrayerAction()
    object ShowQiyamNextPairDialog : PrayerAction()
    object NoAction : PrayerAction()
}

class PrayerSessionManager {

    companion object {
        private const val TAG = "PrayerSession"
        private const val RUKU_SILENCE_THRESHOLD_MS = 14000L
    }

    var appMode: AppMode = AppMode.FREE_READING
        private set

    var currentPhase: PrayerPhase = PrayerPhase.FATIHAH
        private set

    var currentRakah: Int = 1
        private set

    var totalRakahs: Int = 2
        private set

    var isQiyamMode: Boolean = false
        private set

    var qiyamPairIndex: Int = 1
        private set

    var rukuRakah2TimeMs: Long = 0L
        private set

    var tasleemHeardForCurrentPair: Boolean = false

    var surahTargetLockedTimeMs: Long = 0L
        private set

    private var plans: List<RakahPlan> = emptyList()

    // Tracking progress in current Rak'ah
    var lastReadSurah: Int = 1
        private set
    var lastReadAyah: Int = 1
        private set
    var lastReadPage: Int = 1
        private set
    var rakahVersesReadCount: Int = 0
        private set

    // For 4 consecutive mismatches fallback
    private var consecutiveQuranMismatchesInTarget: Int = 0
    var isInFallbackDiscovery = false
        private set

    // Top banner announcement text
    var topBannerText: String? = null
        private set

    fun startPrayerSession(config: PrayerSessionConfig, savedBookmark: Triple<Int, Int, Int>?): PrayerAction {
        appMode = AppMode.PRAYER
        isQiyamMode = config.isQiyamMode
        totalRakahs = if (isQiyamMode) 2 else maxOf(1, config.totalRakahs)
        plans = config.plans
        qiyamPairIndex = 1
        rukuRakah2TimeMs = 0L
        tasleemHeardForCurrentPair = false
        surahTargetLockedTimeMs = 0L
        currentRakah = 1
        currentPhase = PrayerPhase.FATIHAH
        rakahVersesReadCount = 0
        consecutiveQuranMismatchesInTarget = 0
        isInFallbackDiscovery = false

        if (savedBookmark != null) {
            lastReadPage = savedBookmark.first
            lastReadSurah = savedBookmark.second
            lastReadAyah = savedBookmark.third
        }

        updateBanner()
        AppLogger.i(TAG, "Started Prayer Session: ${if (isQiyamMode) "Qiyam Mode" else "$totalRakahs Rak'ahs"}. First Rak'ah Fatihah started.")
        return PrayerAction.JumpToVerse(page = 1, surah = 1, ayah = 1, resetTracking = true)
    }

    fun startNextQiyamPair(): PrayerAction {
        qiyamPairIndex++
        currentRakah = 1
        currentPhase = PrayerPhase.FATIHAH
        rakahVersesReadCount = 0
        consecutiveQuranMismatchesInTarget = 0
        isInFallbackDiscovery = false
        rukuRakah2TimeMs = 0L
        tasleemHeardForCurrentPair = false
        surahTargetLockedTimeMs = 0L
        // Ensure plans for subsequent pair are ALWAYS CONTINUE_PREVIOUS
        plans = listOf(
            RakahPlan(RakahPlanType.CONTINUE_PREVIOUS),
            RakahPlan(RakahPlanType.CONTINUE_PREVIOUS)
        )
        updateBanner()
        AppLogger.i(TAG, "Started Qiyam Next Pair $qiyamPairIndex. Target set to CONTINUE_PREVIOUS from [$lastReadSurah:$lastReadAyah].")
        return PrayerAction.JumpToVerse(page = 1, surah = 1, ayah = 1, resetTracking = true)
    }

    fun exitPrayerMode() {
        appMode = AppMode.FREE_READING
        currentPhase = PrayerPhase.FATIHAH
        topBannerText = null
        isInFallbackDiscovery = false
        isQiyamMode = false
        rukuRakah2TimeMs = 0L
        tasleemHeardForCurrentPair = false
        surahTargetLockedTimeMs = 0L
        AppLogger.i(TAG, "Exited Prayer Session back to FREE_READING mode.")
    }

    fun onVerseTracked(surah: Int, ayah: Int, page: Int) {
        if (appMode != AppMode.PRAYER) return

        if (currentPhase == PrayerPhase.FATIHAH_STANDBY) {
            // Woke up from standby by tracking a verse
            currentPhase = if (surah == 1) PrayerPhase.FATIHAH else PrayerPhase.SURAH_RECITATION
            updateBanner()
        }

        if (currentPhase == PrayerPhase.SURAH_RECITATION) {
            if (surah > 1 || (surah == 1 && ayah > 7)) {
                rakahVersesReadCount++
                lastReadSurah = surah
                lastReadAyah = ayah
                lastReadPage = page
                consecutiveQuranMismatchesInTarget = 0
                isInFallbackDiscovery = false
                updateBanner()
            }
        }
    }

    fun onFatihahCompleted(savedBookmark: Triple<Int, Int, Int>?): PrayerAction {
        if (appMode != AppMode.PRAYER) return PrayerAction.NoAction
        if (currentPhase != PrayerPhase.FATIHAH && currentPhase != PrayerPhase.FATIHAH_STANDBY && currentPhase != PrayerPhase.QIYAM_PAIR_COMPLETE) return PrayerAction.NoAction

        currentPhase = PrayerPhase.SURAH_RECITATION
        surahTargetLockedTimeMs = System.currentTimeMillis()
        rakahVersesReadCount = 0
        consecutiveQuranMismatchesInTarget = 0
        isInFallbackDiscovery = false

        val currentPlan = plans.getOrNull(currentRakah - 1) ?: RakahPlan(RakahPlanType.CONTINUE_PREVIOUS)
        AppLogger.i(TAG, "Al-Fatihah completed for Rak'ah $currentRakah. Executing plan: ${currentPlan.planType}")

        val target: Triple<Int, Int, Int> = when (currentPlan.planType) {
            RakahPlanType.CONTINUE_PREVIOUS -> {
                if (currentRakah == 1) {
                    // Use saved bookmark from previous session if available
                    if (savedBookmark != null && savedBookmark.second > 1) {
                        savedBookmark
                    } else if (lastReadSurah > 1) {
                        Triple(lastReadPage, lastReadSurah, lastReadAyah)
                    } else {
                        // Default to Surah Al-Baqarah 2:1
                        val b2 = MushafPageRepository.getVerse(2, 1)
                        Triple(b2?.page ?: 2, 2, 1)
                    }
                } else {
                    // Continue from where previous Rak'ah stopped.
                    // Note: In our architecture, lastReadPage, lastReadSurah, lastReadAyah was ALREADY updated to the NEXT verse at Ruku!
                    Triple(lastReadPage, lastReadSurah, lastReadAyah)
                }
            }
            RakahPlanType.CUSTOM_SURAH_AYAH -> {
                val v = MushafPageRepository.getVerse(currentPlan.targetSurah, currentPlan.targetAyah)
                Triple(v?.page ?: 1, currentPlan.targetSurah, currentPlan.targetAyah)
            }
            RakahPlanType.AUTO_DISCOVERY -> {
                updateBanner()
                return PrayerAction.EnterDiscovery
            }
        }

        lastReadPage = target.first
        lastReadSurah = target.second
        lastReadAyah = target.third
        updateBanner()

        AppLogger.i(TAG, "Deterministic jump to target [${target.second}:${target.third}] on page ${target.first} for Rak'ah $currentRakah")
        return PrayerAction.JumpToVerse(page = target.first, surah = target.second, ayah = target.third, resetTracking = true)
    }

    /**
     * Checks if reciter spoke Bismillah or any verse of Al-Fatihah while in FATIHAH_STANDBY or QIYAM_PAIR_COMPLETE.
     * Hardened against Tashahhud/Dua words ("الرحيم", "الرحمن", "الحمد" as isolated words).
     * Strictly requires explicit multi-word Fatihah compounds.
     */
    fun checkStandbyWakeup(rawStream: String): Boolean {
        if (appMode != AppMode.PRAYER) return false
        if (currentPhase != PrayerPhase.FATIHAH_STANDBY && currentPhase != PrayerPhase.QIYAM_PAIR_COMPLETE) return false

        val now = System.currentTimeMillis()
        if (currentPhase == PrayerPhase.QIYAM_PAIR_COMPLETE) {
            // Require at least 20 seconds elapsed since Ruku of Rakah 2 OR explicit Tasleem heard
            val timeSinceRuku = now - rukuRakah2TimeMs
            if (timeSinceRuku < 20000L && !tasleemHeardForCurrentPair) {
                return false
            }
        }

        val clean = rawStream.replace(" ", "")
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")

        // Hardened against Tashahhud ("التحيات لله", "السلام عليك أيها النبي ورحمة الله", "إنك حميد مجيد", "الغفور الرحيم")
        val isExplicitFatihahCompound = clean.contains("بسمالله") || clean.contains("بسملله") ||
                clean.contains("الحمدلله") || clean.contains("ربالعالمين") || clean.contains("ربلعلمين") ||
                clean.contains("الرحمنالرحيم") || clean.contains("رحمنلرحيم") ||
                clean.contains("مالكيوم") || clean.contains("ملكيوم") || clean.contains("يومالدين") || clean.contains("يوملدين") ||
                clean.contains("اياكنعبد") || clean.contains("واياكنستعين") ||
                clean.contains("اهدنالصراط") || clean.contains("اهدينصراط") || clean.contains("صراطلذين") ||
                clean.contains("غيرالمغضوب") || clean.contains("غيرلمغضوب") || clean.contains("ولاضالين")

        if (isExplicitFatihahCompound) {
            if (currentPhase == PrayerPhase.QIYAM_PAIR_COMPLETE) {
                startNextQiyamPair()
            } else {
                currentPhase = PrayerPhase.FATIHAH
            }
            updateBanner()
            AppLogger.i(TAG, "Woke up from standby ($currentPhase) via Fatihah compound: stream='$rawStream'")
            return true
        }
        return false
    }

    /**
     * Fallback to general DISCOVERY if 4 consecutive mismatches of valid Quran words occur in target surah before reading any verse.
     * Enforces 40s grace period after Fatihah completion where fallback to DISCOVERY is strictly forbidden.
     */
    fun recordMismatchInTarget(): PrayerAction {
        if (appMode != AppMode.PRAYER || currentPhase != PrayerPhase.SURAH_RECITATION) return PrayerAction.NoAction
        if (isInFallbackDiscovery) return PrayerAction.NoAction
        if (rakahVersesReadCount >= 1) return PrayerAction.NoAction

        val now = System.currentTimeMillis()
        if (now - surahTargetLockedTimeMs < 40000L) {
            AppLogger.d(TAG, "Mismatch ignored during 40s post-Fatihah grace period (elapsed=${now - surahTargetLockedTimeMs}ms)")
            return PrayerAction.NoAction
        }

        consecutiveQuranMismatchesInTarget++
        if (consecutiveQuranMismatchesInTarget >= 4) {
            consecutiveQuranMismatchesInTarget = 0
            isInFallbackDiscovery = true
            AppLogger.w(TAG, "4 consecutive mismatches in planned target after 40s grace period. Falling back to general DISCOVERY.")
            updateBanner()
            return PrayerAction.EnterDiscovery
        }
        return PrayerAction.NoAction
    }

    /**
     * Triggered on Takbeer ("الله أكبر") or Tasmee' ("سمع الله لمن حمده")
     */
    fun onTakbeerOrTasmee(onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit): PrayerAction {
        if (appMode != AppMode.PRAYER) return PrayerAction.NoAction

        // Only transition to Ruku if reciter was in Surah recitation and read at least 1 verse
        if (currentPhase == PrayerPhase.SURAH_RECITATION && rakahVersesReadCount >= 1) {
            return triggerRukuTransition(onSaveBookmark)
        }
        return PrayerAction.NoAction
    }

    /**
     * Checks if 14 seconds have elapsed since last matched recitation or active recitation stream while in SURAH_RECITATION.
     */
    fun checkSilenceRuku(
        lastMatchedSpeechTimeMs: Long,
        lastActiveRecitationStreamTimeMs: Long = 0L,
        onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit
    ): PrayerAction {
        if (appMode != AppMode.PRAYER || currentPhase != PrayerPhase.SURAH_RECITATION) return PrayerAction.NoAction
        if (rakahVersesReadCount < 1) return PrayerAction.NoAction

        val now = System.currentTimeMillis()
        val effectiveLastSpeechMs = maxOf(lastMatchedSpeechTimeMs, lastActiveRecitationStreamTimeMs)
        if (effectiveLastSpeechMs > 0L && (now - effectiveLastSpeechMs) >= RUKU_SILENCE_THRESHOLD_MS) {
            AppLogger.i(TAG, "14s silence detected in SURAH_RECITATION (matchedSpeech=${now - lastMatchedSpeechTimeMs}ms, recitationStream=${now - lastActiveRecitationStreamTimeMs}ms). Triggering Ruku transition.")
            return triggerRukuTransition(onSaveBookmark)
        }
        return PrayerAction.NoAction
    }

    private fun triggerRukuTransition(onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit): PrayerAction {
        // Requirement: Always advance bookmark to NEXT verse (N + 1)
        val gIdx = MushafPageRepository.getGlobalVerseIndex(lastReadSurah, lastReadAyah)
        val nextV = if (gIdx >= 6235) { // 114:6 is index 6235 (0-based)
            MushafPageRepository.getVerse(2, 1) // Wrap around to Al-Baqarah 2:1
        } else {
            MushafPageRepository.getVerseByGlobalIndex(gIdx + 1) ?: MushafPageRepository.getVerse(2, 1)
        }
        val nextBookmark = if (nextV != null) {
            Triple(nextV.page, nextV.surah, nextV.ayah)
        } else {
            Triple(lastReadPage, lastReadSurah, lastReadAyah)
        }

        onSaveBookmark(nextBookmark.first, nextBookmark.second, nextBookmark.third)
        lastReadPage = nextBookmark.first
        lastReadSurah = nextBookmark.second
        lastReadAyah = nextBookmark.third
        isInFallbackDiscovery = false
        AppLogger.i(TAG, "Saved Rak'ah $currentRakah bookmark at next verse [Surah $lastReadSurah: Ayah $lastReadAyah, Page $lastReadPage]")

        if (isQiyamMode && currentRakah == 2) {
            rukuRakah2TimeMs = System.currentTimeMillis()
            currentPhase = PrayerPhase.QIYAM_PAIR_COMPLETE
            updateBanner()
            AppLogger.i(TAG, "Qiyam Mode: Pair $qiyamPairIndex completed at Ruku of Rak'ah 2. Next pair will start from [$lastReadSurah:$lastReadAyah]. Pre-navigating to Page 1.")
            return PrayerAction.ShowQiyamNextPairDialog
        }

        if (currentRakah < totalRakahs) {
            currentRakah++
            currentPhase = PrayerPhase.FATIHAH_STANDBY
            rakahVersesReadCount = 0
            consecutiveQuranMismatchesInTarget = 0
            updateBanner()
            AppLogger.i(TAG, "Advanced to Rak'ah $currentRakah. Pre-navigating to Page 1 in FATIHAH_STANDBY.")
            return PrayerAction.JumpToVerse(page = 1, surah = 1, ayah = 1, resetTracking = true)
        } else {
            // Final Rak'ah completed, now in Ruku/Sujud/Tashahhud awaiting Tasleem
            currentPhase = PrayerPhase.RUKU_SUJUD
            updateBanner()
            AppLogger.i(TAG, "Final Rak'ah $currentRakah completed. In RUKU_SUJUD awaiting Tasleem.")
            return PrayerAction.NoAction
        }
    }

    /**
     * Triggered on explicit Tasleem ("السلام عليكم ورحمة الله")
     */
    fun onTasleem(onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit): PrayerAction {
        if (appMode != AppMode.PRAYER) return PrayerAction.NoAction

        // If reciter was still in SURAH_RECITATION with at least 1 verse read, advance and save bookmark to next verse N+1
        if (currentPhase == PrayerPhase.SURAH_RECITATION && rakahVersesReadCount >= 1) {
            val globalIdx = MushafPageRepository.getGlobalVerseIndex(lastReadSurah, lastReadAyah)
            val nextV = if (globalIdx >= 6235) {
                MushafPageRepository.getVerse(2, 1)
            } else {
                MushafPageRepository.getVerseByGlobalIndex(globalIdx + 1) ?: MushafPageRepository.getVerse(2, 1)
            }
            if (nextV != null) {
                lastReadSurah = nextV.surah
                lastReadAyah = nextV.ayah
                lastReadPage = nextV.page
                onSaveBookmark(lastReadPage, lastReadSurah, lastReadAyah)
                AppLogger.i(TAG, "Direct Tasleem during recitation: saved next verse bookmark [Surah $lastReadSurah: Ayah $lastReadAyah, Page $lastReadPage]")
            }
        }

        if (isQiyamMode) {
            tasleemHeardForCurrentPair = true
            updateBanner()
            AppLogger.i(TAG, "Qiyam Mode: Tasleem heard for Pair $qiyamPairIndex. Keeping mic active for next pair.")
            return PrayerAction.NoAction
        }

        currentPhase = PrayerPhase.TASLEEM_COMPLETE
        updateBanner()
        AppLogger.i(TAG, "Explicit Tasleem detected! Prayer completed. Final bookmark saved: [$lastReadSurah:$lastReadAyah].")
        return PrayerAction.StopListening
    }

    private fun updateBanner() {
        val surahName = MushafPageRepository.getSurahName(lastReadSurah)
        topBannerText = when (currentPhase) {
            PrayerPhase.FATIHAH -> {
                val nextSurahInfo = getNextSurahHint()
                if (isQiyamMode) {
                    "🕌 صلاة القيام (الشفع $qiyamPairIndex - الركعة $currentRakah) — سورة الفاتحة" + (if (nextSurahInfo.isNotBlank()) " (يليها: $nextSurahInfo)" else "")
                } else {
                    "🕌 الركعة $currentRakah من $totalRakahs — سورة الفاتحة" + (if (nextSurahInfo.isNotBlank()) " (يليها: $nextSurahInfo)" else "")
                }
            }
            PrayerPhase.FATIHAH_STANDBY -> {
                if (isQiyamMode) {
                    "🕌 صلاة القيام (الشفع $qiyamPairIndex - الركعة $currentRakah) — استعداد للقيام لسورة الفاتحة"
                } else {
                    "🕌 الركعة $currentRakah من $totalRakahs — استعداد للقيام لسورة الفاتحة"
                }
            }
            PrayerPhase.SURAH_RECITATION -> {
                if (isQiyamMode) {
                    "🕌 صلاة القيام (الشفع $qiyamPairIndex - الركعة $currentRakah) — $surahName (آية $lastReadAyah)"
                } else {
                    "🕌 الركعة $currentRakah من $totalRakahs — $surahName (آية $lastReadAyah)"
                }
            }
            PrayerPhase.RUKU_SUJUD -> {
                "🕌 الركعة $currentRakah من $totalRakahs — الركوع والسجود والتشهد الأخير..."
            }
            PrayerPhase.TASLEEM_COMPLETE -> {
                "✅ تقبل الله صلاتكم — تم حفظ موضع التوقف عند [$surahName: آية $lastReadAyah]"
            }
            PrayerPhase.QIYAM_PAIR_COMPLETE -> {
                "🕌 صلاة القيام (الشفع $qiyamPairIndex) اكتمل — تقبل الله، بانتظار الشفع التالي..."
            }
        }
    }

    private fun getNextSurahHint(): String {
        val plan = plans.getOrNull(currentRakah - 1) ?: return ""
        return when (plan.planType) {
            RakahPlanType.CONTINUE_PREVIOUS -> {
                if (lastReadSurah > 1) {
                    val sName = MushafPageRepository.getSurahName(lastReadSurah)
                    "$sName آية $lastReadAyah"
                } else ""
            }
            RakahPlanType.CUSTOM_SURAH_AYAH -> {
                val sName = MushafPageRepository.getSurahName(plan.targetSurah)
                "$sName آية ${plan.targetAyah}"
            }
            RakahPlanType.AUTO_DISCOVERY -> "اكتشاف صوتي تلقائي"
        }
    }
}
