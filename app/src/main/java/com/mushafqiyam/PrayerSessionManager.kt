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
    TASLEEM_COMPLETE       // Prayer completed after Tasleem
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
    val plans: List<RakahPlan> = listOf(
        RakahPlan(RakahPlanType.CONTINUE_PREVIOUS),
        RakahPlan(RakahPlanType.CONTINUE_PREVIOUS)
    )
)

sealed class PrayerAction {
    data class JumpToVerse(val page: Int, val surah: Int, val ayah: Int, val resetTracking: Boolean = true) : PrayerAction()
    object EnterDiscovery : PrayerAction()
    object StopListening : PrayerAction()
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

    // Top banner announcement text
    var topBannerText: String? = null
        private set

    fun startPrayerSession(config: PrayerSessionConfig, savedBookmark: Triple<Int, Int, Int>?): PrayerAction {
        appMode = AppMode.PRAYER
        totalRakahs = maxOf(1, config.totalRakahs)
        plans = config.plans
        currentRakah = 1
        currentPhase = PrayerPhase.FATIHAH
        rakahVersesReadCount = 0
        consecutiveQuranMismatchesInTarget = 0

        if (savedBookmark != null) {
            lastReadPage = savedBookmark.first
            lastReadSurah = savedBookmark.second
            lastReadAyah = savedBookmark.third
        }

        updateBanner()
        AppLogger.i(TAG, "Started Prayer Session: $totalRakahs Rak'ahs. First Rak'ah Fatihah started.")
        return PrayerAction.JumpToVerse(page = 1, surah = 1, ayah = 1, resetTracking = true)
    }

    fun exitPrayerMode() {
        appMode = AppMode.FREE_READING
        currentPhase = PrayerPhase.FATIHAH
        topBannerText = null
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
                updateBanner()
            }
        }
    }

    fun onFatihahCompleted(savedBookmark: Triple<Int, Int, Int>?): PrayerAction {
        if (appMode != AppMode.PRAYER) return PrayerAction.NoAction
        if (currentPhase != PrayerPhase.FATIHAH && currentPhase != PrayerPhase.FATIHAH_STANDBY) return PrayerAction.NoAction

        currentPhase = PrayerPhase.SURAH_RECITATION
        rakahVersesReadCount = 0
        consecutiveQuranMismatchesInTarget = 0

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
                    // Continue from where previous Rak'ah stopped: lastReadVerse + 1
                    val prevGlobalIdx = MushafPageRepository.getGlobalVerseIndex(lastReadSurah, lastReadAyah)
                    val nextVerse = MushafPageRepository.getVerseByGlobalIndex(prevGlobalIdx + 1)
                    if (nextVerse != null) {
                        Triple(nextVerse.page, nextVerse.surah, nextVerse.ayah)
                    } else {
                        Triple(lastReadPage, lastReadSurah, lastReadAyah)
                    }
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
     * Checks if reciter spoke Bismillah or any verse of Al-Fatihah while in FATIHAH_STANDBY.
     * User requirement: "يمكن أن تكون البسملة أو الحمد لله رب العالمين، او أي آية من آيات الفاتحة."
     */
    fun checkStandbyWakeup(rawStream: String): Boolean {
        if (appMode != AppMode.PRAYER || currentPhase != PrayerPhase.FATIHAH_STANDBY) return false

        val clean = rawStream.replace(" ", "")
            .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
            .replace("ى", "ي").replace("ة", "ه").replace("ؤ", "و").replace("ئ", "ي")

        val isFatihahVerse = clean.contains("بسمالله") || clean.contains("بسملله") ||
                clean.contains("الحمد") || clean.contains("ربالعالمين") || clean.contains("ربلعلمين") ||
                clean.contains("الرحمن") || clean.contains("الرحيم") ||
                clean.contains("مالكيوم") || clean.contains("ملكيوم") || clean.contains("يومالدين") || clean.contains("يوملدين") ||
                clean.contains("اياك") || clean.contains("نعبد") || clean.contains("نستعين") ||
                clean.contains("اهدنا") || clean.contains("الصراط") || clean.contains("المستقيم") || clean.contains("صراطلذين") ||
                clean.contains("انعمت") || clean.contains("المغضوب") || clean.contains("الضالين") || clean.contains("ولاضالين")

        if (isFatihahVerse) {
            currentPhase = PrayerPhase.FATIHAH
            updateBanner()
            AppLogger.i(TAG, "Woke up from FATIHAH_STANDBY via Fatihah verse: stream='$rawStream'")
            return true
        }
        return false
    }

    /**
     * Fallback to general DISCOVERY if 4 consecutive mismatches of valid Quran words occur in target surah.
     */
    fun recordMismatchInTarget(): PrayerAction {
        if (appMode != AppMode.PRAYER || currentPhase != PrayerPhase.SURAH_RECITATION) return PrayerAction.NoAction

        consecutiveQuranMismatchesInTarget++
        if (consecutiveQuranMismatchesInTarget >= 4) {
            consecutiveQuranMismatchesInTarget = 0
            AppLogger.w(TAG, "4 consecutive mismatches in planned target. Falling back to general DISCOVERY.")
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
     * Checks if 14 seconds have elapsed since last matched recitation while in SURAH_RECITATION.
     */
    fun checkSilenceRuku(lastMatchedSpeechTimeMs: Long, onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit): PrayerAction {
        if (appMode != AppMode.PRAYER || currentPhase != PrayerPhase.SURAH_RECITATION) return PrayerAction.NoAction
        if (rakahVersesReadCount < 1) return PrayerAction.NoAction

        val now = System.currentTimeMillis()
        if (lastMatchedSpeechTimeMs > 0L && (now - lastMatchedSpeechTimeMs) >= RUKU_SILENCE_THRESHOLD_MS) {
            AppLogger.i(TAG, "14s silence detected in SURAH_RECITATION. Triggering Ruku transition.")
            return triggerRukuTransition(onSaveBookmark)
        }
        return PrayerAction.NoAction
    }

    private fun triggerRukuTransition(onSaveBookmark: (page: Int, surah: Int, ayah: Int) -> Unit): PrayerAction {
        // Requirement: Save reading end position at the end of each Rak'ah
        onSaveBookmark(lastReadPage, lastReadSurah, lastReadAyah)
        AppLogger.i(TAG, "Saved Rak'ah $currentRakah bookmark at [Surah $lastReadSurah: Ayah $lastReadAyah, Page $lastReadPage]")

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

        currentPhase = PrayerPhase.TASLEEM_COMPLETE
        onSaveBookmark(lastReadPage, lastReadSurah, lastReadAyah)
        updateBanner()
        AppLogger.i(TAG, "Explicit Tasleem detected! Prayer completed. Final bookmark saved: [$lastReadSurah:$lastReadAyah].")
        return PrayerAction.StopListening
    }

    private fun updateBanner() {
        val surahName = MushafPageRepository.getSurahName(lastReadSurah)
        topBannerText = when (currentPhase) {
            PrayerPhase.FATIHAH -> {
                val nextSurahInfo = getNextSurahHint()
                "🕌 الركعة $currentRakah من $totalRakahs — سورة الفاتحة" + (if (nextSurahInfo.isNotBlank()) " (يليها: $nextSurahInfo)" else "")
            }
            PrayerPhase.FATIHAH_STANDBY -> {
                "🕌 الركعة $currentRakah من $totalRakahs — استعداد للقيام لسورة الفاتحة"
            }
            PrayerPhase.SURAH_RECITATION -> {
                "🕌 الركعة $currentRakah من $totalRakahs — $surahName (آية $lastReadAyah)"
            }
            PrayerPhase.RUKU_SUJUD -> {
                "🕌 الركعة $currentRakah من $totalRakahs — الركوع والسجود والتشهد الأخير..."
            }
            PrayerPhase.TASLEEM_COMPLETE -> {
                "✅ تقبل الله صلاتكم — تم حفظ موضع التوقف عند [$surahName: آية $lastReadAyah]"
            }
        }
    }

    private fun getNextSurahHint(): String {
        val plan = plans.getOrNull(currentRakah - 1) ?: return ""
        return when (plan.planType) {
            RakahPlanType.CONTINUE_PREVIOUS -> {
                if (currentRakah == 1 && lastReadSurah > 1) {
                    val sName = MushafPageRepository.getSurahName(lastReadSurah)
                    "$sName آية $lastReadAyah"
                } else if (currentRakah > 1) {
                    val prevGlobalIdx = MushafPageRepository.getGlobalVerseIndex(lastReadSurah, lastReadAyah)
                    val nextV = MushafPageRepository.getVerseByGlobalIndex(prevGlobalIdx + 1)
                    if (nextV != null) "${nextV.surahName} آية ${nextV.ayah}" else ""
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
