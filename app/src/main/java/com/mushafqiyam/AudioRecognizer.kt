package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Real-time Streaming Automatic Speech Recognition for the Holy Quran.
 * Powered by FastConformer Quran Streaming Transducer via sherpa-onnx OnlineRecognizer.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var vad: Vad? = null

    // Callbacks for UI updates
    var onAudioLevel: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

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

    private fun resolveRawResource(rawResId: Int, fileName: String): String? {
        val internalFile = File(context.filesDir, "tilawa_model/$fileName")
        if (internalFile.exists() && internalFile.length() > 0) {
            AppLogger.i(TAG, "Found existing $fileName in storage: ${internalFile.absolutePath}")
            return internalFile.absolutePath
        }

        return try {
            internalFile.parentFile?.mkdirs()
            context.resources.openRawResource(rawResId).use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            AppLogger.i(TAG, "Copied $fileName from res/raw to ${internalFile.absolutePath}")
            internalFile.absolutePath
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Could not resolve raw resource $fileName: ${t.localizedMessage}")
            null
        }
    }

    /**
     * Initializes sherpa-onnx OnlineRecognizer engine with FastConformer Quran Streaming Transducer.
     */
    fun initEngine(modelDirInAssets: String = "tilawa_model"): Boolean {
        return try {
            AppLogger.i(TAG, "Starting FastConformer Quran Streaming engine init (Dir: $modelDirInAssets)...")

            // Clean legacy whisper or obsolete models from internal filesDir to free space
            try {
                val whisperDir = File(context.filesDir, "tilawa_whisper")
                if (whisperDir.exists()) whisperDir.deleteRecursively()
                val oldModel = File(context.filesDir, "$modelDirInAssets/model.int8.onnx")
                if (oldModel.exists()) oldModel.delete()
            } catch (_: Throwable) {}

            val encoderPath = resolveFilePath(modelDirInAssets, "encoder.int8.onnx")
            val decoderPath = resolveFilePath(modelDirInAssets, "decoder.int8.onnx")
            val joinerPath = resolveFilePath(modelDirInAssets, "joiner.int8.onnx")
            val tokensPath = resolveFilePath(modelDirInAssets, "tokens.txt")

            if (encoderPath != null && File(encoderPath).exists() &&
                decoderPath != null && File(decoderPath).exists() &&
                joinerPath != null && File(joinerPath).exists() &&
                tokensPath != null && File(tokensPath).exists()) {

                val transducerConfig = OnlineTransducerModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    joiner = joinerPath
                )

                val modelConfig = OnlineModelConfig(
                    transducer = transducerConfig,
                    tokens = tokensPath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "transducer"
                )

                val config = OnlineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                    enableEndpoint = false
                )

                recognizer = OnlineRecognizer(null, config)
                AppLogger.i(TAG, "FastConformer Quran Streaming Transducer engine initialized successfully")

                // Initialize official Silero VAD from res/raw resource or assets
                val rawResId = context.resources.getIdentifier("silero_vad", "raw", context.packageName)
                val vadModelPath = if (rawResId != 0) resolveRawResource(rawResId, "silero_vad.onnx") else null
                    ?: resolveFilePath(modelDirInAssets, "silero_vad.onnx")

                if (vadModelPath != null && File(vadModelPath).exists()) {
                    try {
                        val sileroConfig = SileroVadModelConfig(
                            model = vadModelPath,
                            threshold = 0.5f,
                            minSilenceDuration = 0.35f,
                            minSpeechDuration = 0.25f,
                            windowSize = 512,
                            maxSpeechDuration = 30.0f
                        )
                        val vadConfig = VadModelConfig(
                            sileroVadModelConfig = sileroConfig,
                            sampleRate = SAMPLE_RATE,
                            numThreads = 1,
                            provider = "cpu",
                            debug = false
                        )
                        vad = Vad(null, vadConfig)
                        AppLogger.i(TAG, "Official Silero VAD initialized successfully")
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Silero VAD initialization error", t)
                        vad = null
                    }
                } else {
                    AppLogger.w(TAG, "silero_vad.onnx missing or unresolved")
                    vad = null
                }
                true
            } else {
                AppLogger.w(TAG, "FastConformer Quran model files missing: encoder=$encoderPath, decoder=$decoderPath, joiner=$joinerPath, tokens=$tokensPath")
                onError?.invoke("ملفات نموذج التلاوة القرآني الجديد غير متوفرة")
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Native JNI Library link error", e)
            onError?.invoke("تنبيه المحرك: تعذر ربط مكتبة JNI الثنائية (${e.localizedMessage})")
            false
        } catch (t: Throwable) {
            AppLogger.e(TAG, "FastConformer Quran init error", t)
            onError?.invoke("تنبيه المحرك: ${t.localizedMessage}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRecording.get()) return true
        val rec = recognizer ?: run {
            AppLogger.w(TAG, "Recognizer not initialized")
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

            audioRecord?.startRecording()
            isRecording.set(true)
            AppLogger.i(TAG, "Audio recording started successfully")

            // Create streaming recognition session
            stream = rec.createStream()

            recordingThread = thread(start = true, name = "FastConformerAudioThread") {
                val buffer = ShortArray(512)
                var lastEmittedText = ""
                var silentFramesCount = 0

                AppLogger.i(TAG, "FastConformer Streaming Audio capture loop started (16kHz Mono)")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSamples > 0) {
                            var sum = 0.0
                            val floatSamples = FloatArray(readSamples)
                            for (i in 0 until readSamples) {
                                val floatSample = buffer[i] / 32768.0f
                                floatSamples[i] = floatSample
                                sum += (buffer[i].toDouble() * buffer[i].toDouble())
                            }
                            val rms = Math.sqrt(sum / readSamples).toFloat()
                            val level = (rms * 5.0).coerceIn(0.0, 1.0).toFloat()
                            onAudioLevel?.invoke(level)

                            // RMS & VAD Gate: check for speech activity
                            val v = vad
                            val isSpeech = if (v != null) {
                                v.acceptWaveform(floatSamples)
                                while (!v.empty()) { v.pop() }
                                v.isSpeechDetected()
                            } else {
                                rms > 0.015f
                            }

                            val currentStream = stream
                            if (currentStream != null && rec != null) {
                                if (isSpeech || rms > 0.012f) {
                                    silentFramesCount = 0
                                    currentStream.acceptWaveform(floatSamples, SAMPLE_RATE)

                                    while (rec.isReady(currentStream)) {
                                        rec.decode(currentStream)
                                    }

                                    val currentText = rec.getResult(currentStream).text.trim()
                                    if (currentText.isNotBlank() && currentText != lastEmittedText) {
                                        lastEmittedText = currentText
                                        AppLogger.i(TAG, "Recognized text (FastConformer Quran): $currentText")
                                        onPartialResult?.invoke(currentText)
                                    }
                                } else {
                                    silentFramesCount++
                                    // After ~1.2s of silence (38 frames of 512 samples @ 16kHz), reset stream for fresh verse
                                    if (silentFramesCount >= 38 && lastEmittedText.isNotEmpty()) {
                                        silentFramesCount = 0
                                        lastEmittedText = ""
                                        rec.reset(currentStream)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in audio capture loop", t)
                    }
                }
                AppLogger.i(TAG, "Audio capture thread stopped")
            }
            true
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error starting audio recording", t)
            onError?.invoke("خطأ: ${t.localizedMessage}")
            false
        }
    }

    fun stopListening() {
        if (!isRecording.get()) return
        isRecording.set(false)

        try { recordingThread?.join(1000) } catch (_: Throwable) {}

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) stop()
                release()
            }
        } catch (_: Throwable) {}

        audioRecord = null
        recordingThread = null

        val currentStream = stream
        val rec = recognizer
        if (currentStream != null && rec != null) {
            try { rec.reset(currentStream) } catch (_: Throwable) {}
        }
        stream = null

        AppLogger.i(TAG, "Audio recording stopped")
    }

    fun release() {
        stopListening()
        stream = null
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        AppLogger.i(TAG, "AudioRecognizer released")
    }
}
