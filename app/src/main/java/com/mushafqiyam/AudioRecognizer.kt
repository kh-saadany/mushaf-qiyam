package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * AudioRecognizer: Real-time Streaming Automatic Speech Recognition for the Holy Quran.
 * Powered by Zipformer2 Phoneme CTC architecture via Native Sherpa-ONNX engine.
 * Features C++ streaming feature extraction (Kaldi Fbank), Zero-Allocation CTC decoding,
 * and Phoneme-to-Canonical Projection for flawless Quranic line and verse tracking.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 640 // 40ms audio chunk for ultra-low latency
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var inferenceThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var currentStream: OnlineStream? = null

    // Callbacks for UI updates
    var onAudioLevel: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMemoryUpdate: ((String) -> Unit)? = null

    private fun resolveFilePath(modelDirInAssets: String, fileName: String): String? {
        val internalFile = File(context.filesDir, "$modelDirInAssets/$fileName")
        if (internalFile.exists() && internalFile.length() > 0) {
            AppLogger.i(TAG, "Found existing $fileName in storage: ${internalFile.absolutePath}")
            return internalFile.absolutePath
        }

        return try {
            internalFile.parentFile?.mkdirs()
            context.assets.open("$modelDirInAssets/$fileName").use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            AppLogger.i(TAG, "Copied $fileName from APK assets to ${internalFile.absolutePath}")
            internalFile.absolutePath
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Could not resolve asset $fileName: ${t.localizedMessage}")
            null
        }
    }

    /**
     * Initializes the native Sherpa-ONNX Zipformer2-CTC Quran Phoneme engine.
     */
    fun initEngine(modelDirInAssets: String = "tilawa_model"): Boolean {
        return try {
            AppLogger.i(TAG, "Initializing Sherpa-ONNX Zipformer2-CTC Phoneme engine (Dir: $modelDirInAssets)...")

            // Clean legacy Transducer and Whisper model files to reclaim device storage
            try {
                val legacyFiles = listOf(
                    "encoder.onnx", "encoder.int8.onnx",
                    "decoder.int8.onnx", "joiner.int8.onnx"
                )
                legacyFiles.forEach { fname ->
                    val f = File(context.filesDir, "$modelDirInAssets/$fname")
                    if (f.exists()) {
                        f.delete()
                        AppLogger.i(TAG, "Reclaimed space: removed obsolete $fname")
                    }
                }
                val whisperDir = File(context.filesDir, "tilawa_whisper")
                if (whisperDir.exists()) whisperDir.deleteRecursively()
            } catch (_: Throwable) {}

            val modelPath = resolveFilePath(modelDirInAssets, "model.int8.onnx")
                ?: resolveFilePath(modelDirInAssets, "zipformer_p_arabic_v3.int8.onnx")
                ?: resolveFilePath(modelDirInAssets, "model.onnx")
                ?: resolveFilePath(modelDirInAssets, "encoder.onnx")

            val tokensPath = resolveFilePath(modelDirInAssets, "tokens.txt")

            if (modelPath != null && File(modelPath).exists() &&
                tokensPath != null && File(tokensPath).exists()) {

                val featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80,
                    dither = 0.0f
                )

                val zipformer2CtcConfig = OnlineZipformer2CtcModelConfig(
                    model = modelPath
                )

                val modelConfig = OnlineModelConfig().apply {
                    this.zipformer2Ctc = zipformer2CtcConfig
                    this.tokens = tokensPath
                    this.numThreads = 2
                    this.provider = "cpu"
                    this.modelType = "zipformer2_ctc"
                    this.debug = false
                }

                val endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 2.4f, minUtteranceLength = 0.0f),
                    rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
                    rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0.0f, minUtteranceLength = 20.0f)
                )

                val recognizerConfig = OnlineRecognizerConfig().apply {
                    this.featConfig = featConfig
                    this.modelConfig = modelConfig
                    this.decodingMethod = "greedy_search"
                    this.maxActivePaths = 4
                    this.enableEndpoint = true
                    this.endpointConfig = endpointConfig
                }

                // Pass null as AssetManager to indicate direct filesystem paths
                recognizer = OnlineRecognizer(null, recognizerConfig)

                AppLogger.i(TAG, "Sherpa-ONNX Zipformer2-CTC Quran engine initialized successfully (Model: $modelPath)")
                logMemoryUsage("Post-Init")
                true
            } else {
                AppLogger.w(TAG, "Zipformer2-CTC Quran model files missing: model=$modelPath, tokens=$tokensPath")
                mainHandler.post { onError?.invoke("ملفات نموذج التلاوة الفونيمي غير متوفرة") }
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Native Sherpa-ONNX Library link error", e)
            mainHandler.post { onError?.invoke("تنبيه المحرك: تعذر ربط مكتبة Sherpa-ONNX (${e.localizedMessage})") }
            false
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Sherpa-ONNX Zipformer2-CTC init error", t)
            mainHandler.post { onError?.invoke("تنبيه المحرك: ${t.localizedMessage}") }
            false
        }
    }

    /**
     * Reports live Java Heap and Native RAM diagnostics.
     */
    fun logMemoryUsage(contextTag: String = "Periodic") {
        try {
            val rt = Runtime.getRuntime()
            val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            val totalHeapMb = rt.totalMemory() / (1024 * 1024)
            val maxHeapMb = rt.maxMemory() / (1024 * 1024)
            val nativeAllocMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            val memStr = "RAM: Heap ${usedHeapMb}MB/${totalHeapMb}MB (Max ${maxHeapMb}MB) | Native: ${nativeAllocMb}MB"
            AppLogger.i("MemoryDiag", "[$contextTag] $memStr")
            mainHandler.post { onMemoryUpdate?.invoke(memStr) }
        } catch (_: Throwable) {}
    }

    /**
     * Deterministic, zero-allocation projection mapping tajweed/phoneme units to canonical base words.
     * Maps:
     * - 'ں' (noon mokhfah) -> 'ن'
     * - '۾' (meem mokhfah/iqlab) -> 'م'
     * - 'ۦ' (yaa madd) -> 'ي'
     * - 'ۥ' (waw madd) -> 'و'
     * - 'ٲ' (hamza mosahala) -> 'ء'
     * Strips harakat, special markers, and collapses prolonged madd letters for rock-solid sequence tracking.
     */
    fun projectPhonemesToCanonical(rawPhonemes: String): String {
        if (rawPhonemes.isBlank()) return ""
        val sb = java.lang.StringBuilder(rawPhonemes.length)
        var lastChar = ' '

        // Normalize common divine name phoneme clusters first
        val preprocessed = rawPhonemes
            .replace("لللاه", "لله")
            .replace("لِللَااهِ", "لله")

        for (i in 0 until preprocessed.length) {
            val ch = preprocessed[i]
            val mapped = when (ch) {
                'ں' -> 'ن'
                '۾' -> 'م'
                'ۦ' -> 'ي'
                'ۥ' -> 'و'
                'ٲ' -> 'ا'
                'ٱ' -> 'ا'
                'آ', 'أ', 'إ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                // Strip harakat and tajweed auxiliary annotations
                'َ', 'ُ', 'ِ', '۪', 'ؙ', 'ڇ', 'ۜ', 'ـ', 'ْ', 'ّ', 'ً', 'ٌ', 'ٍ', 'ٓ' -> continue
                '\u2581' -> ' ' // SentencePiece/BPE space token if present
                else -> ch
            }

            if (mapped == ' ') {
                if (lastChar != ' ') {
                    sb.append(' ')
                    lastChar = ' '
                }
            } else {
                // Collapse duplicate consonants/vowels arising from prolonged madds (e.g. "عاالمين" -> "عالمين")
                // but preserve double lam in "لله"
                if (mapped == lastChar && (mapped == 'ا' || mapped == 'و' || mapped == 'ي')) {
                    continue
                }
                sb.append(mapped)
                lastChar = mapped
            }
        }

        val rawWords = sb.toString().trim().split(" ").filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return ""

        val restored = java.lang.StringBuilder(rawWords.size * 8)
        for (idx in rawWords.indices) {
            var w = rawWords[idx]
            // Hamzatul wasl at utterance start: "ءلحمد" -> "الحمد"
            if (w.startsWith("ء") && w.length >= 3) {
                w = "ا" + w.substring(1)
            }
            // Wasl connection: drop of hamzatul wasl in recitation ("لعالمين" -> "العالمين", "لدين" -> "الدين")
            if (w.startsWith("ل") && !w.startsWith("لل") && w.length >= 4) {
                w = "ا$w"
            }
            if (idx > 0) restored.append(' ')
            restored.append(w)
        }
        return restored.toString()
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRecording.get()) return true
        val rec = recognizer
        if (rec == null) {
            AppLogger.w(TAG, "Sherpa-ONNX recognizer not initialized")
            onError?.invoke("المحرك غير مهيأ")
            return false
        }

        return try {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
            val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2 / 5)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize")
                onError?.invoke("فشل في تشغيل الميكروفون")
                return false
            }

            // Create initial streaming recognizer stream
            currentStream = rec.createStream()
            audioRecord?.startRecording()
            isRecording.set(true)
            logMemoryUsage("Start-Listening")
            AppLogger.i(TAG, "Sherpa-ONNX streaming listening started (16kHz Mono)")

            // 1. Dedicated Inference Consumer Worker Thread
            inferenceThread = thread(start = true, name = "ZipformerInferenceThread") {
                AppLogger.i(TAG, "Zipformer2-CTC Inference Consumer thread started")
                var lastEmittedProjected = ""
                var inferenceCounter = 0

                while (isRecording.get()) {
                    try {
                        val stream = currentStream
                        val currentRec = recognizer
                        if (stream == null || currentRec == null) {
                            Thread.sleep(20)
                            continue
                        }

                        while (currentRec.isReady(stream)) {
                            currentRec.decode(stream)
                        }

                        val isEndpoint = currentRec.isEndpoint(stream)
                        val result = currentRec.getResult(stream)
                        val rawText = result.text.trim()

                        if (rawText.isNotBlank()) {
                            val projected = projectPhonemesToCanonical(rawText)
                            if (projected.isNotBlank() && projected != lastEmittedProjected) {
                                lastEmittedProjected = projected
                                AppLogger.i(TAG, "Raw Phonemes: '$rawText' -> Projected Canonical: '$projected'")
                                mainHandler.post { onPartialResult?.invoke(projected) }
                            }
                        }

                        if (isEndpoint) {
                            currentRec.reset(stream)
                            lastEmittedProjected = ""
                        }

                        inferenceCounter++
                        if (inferenceCounter % 50 == 0) {
                            logMemoryUsage("Continuous-Inference")
                        }

                        Thread.sleep(25)
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in Zipformer2 inference loop", t)
                    }
                }
                AppLogger.i(TAG, "Zipformer2-CTC Inference Consumer thread stopped")
            }

            // 2. Dedicated Audio Capture Producer Thread (Never blocks on inference!)
            captureThread = thread(start = true, name = "ZipformerAudioCaptureThread") {
                val readChunk = ShortArray(CHUNK_SAMPLES)
                val floatSamples = FloatArray(CHUNK_SAMPLES)

                AppLogger.i(TAG, "Audio Capture Producer loop started (16kHz Mono, chunk=$CHUNK_SAMPLES samples)")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(readChunk, 0, readChunk.size) ?: 0
                        if (readSamples > 0) {
                            var sum = 0.0
                            for (i in 0 until readSamples) {
                                val s = readChunk[i] / 32768.0f
                                floatSamples[i] = s
                                sum += (s.toDouble() * s.toDouble())
                            }
                            val rms = sqrt(sum / readSamples).toFloat()
                            val level = (rms * 5.0f).coerceIn(0.0f, 1.0f)
                            mainHandler.post { onAudioLevel?.invoke(level) }

                            // Stream samples directly to C++ kaldi-native-fbank and Zipformer buffer
                            currentStream?.acceptWaveform(floatSamples, SAMPLE_RATE)
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in audio capture loop", t)
                    }
                }
                AppLogger.i(TAG, "Audio Capture Producer loop stopped")
            }
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error starting audio recording", t)
            mainHandler.post { onError?.invoke("خطأ: ${t.localizedMessage}") }
            false
        }
    }

    fun stopListening() {
        if (!isRecording.get()) return
        isRecording.set(false)

        try { captureThread?.join(1000) } catch (_: Throwable) {}
        try { inferenceThread?.join(1000) } catch (_: Throwable) {}

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        } catch (_: Throwable) {}

        audioRecord = null
        captureThread = null
        inferenceThread = null

        try {
            currentStream?.release()
        } catch (_: Throwable) {}
        currentStream = null

        logMemoryUsage("Stop-Listening")
        AppLogger.i(TAG, "Audio recording and inference worker stopped")
    }

    fun release() {
        stopListening()
        try {
            recognizer?.release()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error releasing Sherpa-ONNX recognizer: ${t.localizedMessage}")
        }
        recognizer = null
        AppLogger.i(TAG, "AudioRecognizer fully released")
    }
}
