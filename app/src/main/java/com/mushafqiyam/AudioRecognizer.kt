package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Manages microphone recording (16kHz Mono PCM)
 * and streams audio into sherpa-onnx OfflineRecognizer for live Quran ASR.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var recognizer: OfflineRecognizer? = null

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

    /**
     * Initializes sherpa-onnx OfflineRecognizer engine safely.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            AppLogger.i(TAG, "Starting sherpa-onnx engine init (Dir: $modelDirInAssets)...")

            val modelPath = resolveFilePath(modelDirInAssets, "model.int8.onnx")
            val tokensPath = resolveFilePath(modelDirInAssets, "tokens.txt")

            if (modelPath != null && File(modelPath).exists() && tokensPath != null && File(tokensPath).exists()) {
                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                        tokens = tokensPath,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(null, config)
                AppLogger.i(TAG, "Sherpa-ONNX engine initialized successfully")
                true
            } else {
                AppLogger.w(TAG, "Sherpa-ONNX model or tokens missing. modelPath=$modelPath, tokensPath=$tokensPath")
                onError?.invoke("نموذج التلاوة الكامل غير متوفر بالذاكرة المحلية")
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Native JNI Library link error", e)
            onError?.invoke("تنبيه المحرك: تعذر ربط مكتبة JNI الثنائية (${e.localizedMessage})")
            false
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Sherpa-ONNX init error", t)
            onError?.invoke("تنبيه المحرك: ${t.localizedMessage}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isRecording.get()) return

        try {
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
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            AppLogger.i(TAG, "Audio recording started successfully")

            recordingThread = thread(start = true, name = "AudioRecognizerThread") {
                val buffer = ShortArray(bufferSize / 2)
                val audioWindow = mutableListOf<Float>()
                AppLogger.i(TAG, "Audio capture thread running")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSamples > 0) {
                            var sum = 0.0
                            for (i in 0 until readSamples) {
                                val floatSample = buffer[i] / 32768.0f
                                sum += (buffer[i].toDouble() * buffer[i].toDouble())
                                audioWindow.add(floatSample)
                            }
                            val rms = Math.sqrt(sum / readSamples) / 32768.0
                            val level = (rms * 5.0).coerceIn(0.0, 1.0).toFloat()
                            onAudioLevel?.invoke(level)

                            // Energy-based Voice Activity Detection (VAD)
                            // Skip silent / low-energy background noise chunks to avoid ASR hallucinations
                            val isSpeechPresent = level > 0.08f

                            // Process 1.5s audio chunk for live CTC inference ONLY if speech is present
                            if (audioWindow.size >= SAMPLE_RATE * 3 / 2) {
                                val chunk = audioWindow.toFloatArray()
                                audioWindow.clear()

                                if (isSpeechPresent) {
                                    runInference(chunk)
                                } else {
                                    AppLogger.i(TAG, "VAD: Silence / Low Energy detected (level: ${String.format("%.3f", level)}), skipping inference.")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in audio capture loop", t)
                    }
                }
                AppLogger.i(TAG, "Audio capture thread stopped")
            }

        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error starting audio recording", t)
            onError?.invoke("خطأ: ${t.localizedMessage}")
        }
    }

    private fun runInference(audioSamples: FloatArray) {
        val rec = recognizer ?: return

        try {
            val stream = rec.createStream()
            stream.acceptWaveform(audioSamples, SAMPLE_RATE)
            rec.decode(stream)

            val result = rec.getResult(stream)
            val text = result.text.trim()
            if (text.isNotBlank()) {
                AppLogger.i(TAG, "Recognized text (Sherpa): $text")
                onPartialResult?.invoke(text)
            }
            stream.release()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error during Sherpa inference", t)
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
        AppLogger.i(TAG, "Audio recording stopped")
    }

    fun release() {
        stopListening()
        try { recognizer?.release() } catch (_: Throwable) {}
        recognizer = null
        AppLogger.i(TAG, "AudioRecognizer released")
    }
}
