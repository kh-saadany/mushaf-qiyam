package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Manages microphone recording (16kHz Mono PCM)
 * and streams audio into ONNX Runtime + CtcDecoder for live Quran ASR.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var ctcDecoder: CtcDecoder? = null

    // Callbacks for UI updates
    var onAudioLevel: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Resolves model path: checks filesDir first, then asset directory.
     */
    private fun resolveModelPath(modelDirInAssets: String): String? {
        // 1. Check internal storage (filesDir/tilawa_model/model.onnx)
        val internalFile = File(context.filesDir, "$modelDirInAssets/model.onnx")
        if (internalFile.exists() && internalFile.length() > 1000000) {
            AppLogger.i(TAG, "Found existing model in internal storage: ${internalFile.absolutePath} (${internalFile.length() / 1024 / 1024}MB)")
            return internalFile.absolutePath
        }

        // 2. Try copying from assets if packaged in Full APK
        return try {
            internalFile.parentFile?.mkdirs()
            context.assets.open("$modelDirInAssets/model.onnx").use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            AppLogger.i(TAG, "Copied model from APK assets to ${internalFile.absolutePath}")
            internalFile.absolutePath
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Model not in APK assets: ${t.message}")
            null
        }
    }

    /**
     * Resolves vocab path: checks filesDir first, then assets.
     */
    private fun resolveVocabPath(modelDirInAssets: String): String {
        val internalFile = File(context.filesDir, "$modelDirInAssets/vocab.json")
        if (internalFile.exists() && internalFile.length() > 0) {
            return internalFile.absolutePath
        }

        // Try copying asset
        try {
            internalFile.parentFile?.mkdirs()
            context.assets.open("$modelDirInAssets/vocab.json").use { input ->
                FileOutputStream(internalFile).use { output ->
                    input.copyTo(output)
                }
            }
            return internalFile.absolutePath
        } catch (_: Throwable) {}

        return "$modelDirInAssets/vocab.json"
    }

    /**
     * Initializes ONNX Runtime engine and CtcDecoder safely.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            AppLogger.i(TAG, "Starting ONNX Runtime init (Dir: $modelDirInAssets)...")
            ortEnv = OrtEnvironment.getEnvironment()
            AppLogger.i(TAG, "OrtEnvironment initialized successfully")

            val vocabPath = resolveVocabPath(modelDirInAssets)
            ctcDecoder = CtcDecoder(context, vocabPath)

            val modelPath = resolveModelPath(modelDirInAssets)
            if (modelPath != null && File(modelPath).exists()) {
                val opts = OrtSession.SessionOptions()
                opts.setInterOpNumThreads(2)
                opts.setIntraOpNumThreads(2)
                ortSession = ortEnv?.createSession(modelPath, opts)
                AppLogger.i(TAG, "ONNX model session loaded successfully from $modelPath")
                true
            } else {
                AppLogger.w(TAG, "Model file not found at $modelPath")
                onError?.invoke("نموذج التلاوة غير موجود برقم الذاكرة المحلية")
                false
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "ONNX Runtime init error", t)
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
            AppLogger.i(TAG, "AudioRecord recording started (16kHz Mono)")

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

                            // Process 1.5s audio chunk for live CTC inference
                            if (audioWindow.size >= SAMPLE_RATE * 3 / 2) {
                                runInference(audioWindow.toFloatArray())
                                audioWindow.clear()
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
        val env = ortEnv ?: return
        val session = ortSession ?: return
        val decoder = ctcDecoder ?: return

        try {
            val shape = longArrayOf(1, audioSamples.size.toLong())
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioSamples), shape)

            val inputName = session.inputNames.iterator().next()
            val result = session.run(mapOf(inputName to tensor))

            val outputValue = result.get(0).value
            if (outputValue is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val logits2D = (outputValue as Array<Array<FloatArray>>)[0]
                val text = decoder.decode(logits2D)
                if (text.isNotBlank()) {
                    AppLogger.i(TAG, "Recognized text: $text")
                    onPartialResult?.invoke(text)
                }
            }
            tensor.close()
            result.close()
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Error during ONNX inference", t)
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
        try { ortSession?.close() } catch (_: Throwable) {}
        try { ortEnv?.close() } catch (_: Throwable) {}
        ortSession = null
        ortEnv = null
        ctcDecoder = null
        AppLogger.i(TAG, "AudioRecognizer released")
    }
}
