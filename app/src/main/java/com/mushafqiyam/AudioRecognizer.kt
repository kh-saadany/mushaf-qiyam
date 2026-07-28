package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
 * and feeds audio samples into Microsoft ONNX Runtime + CtcDecoder for offline ASR.
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
     * Safely copies asset file to internal storage directory if present.
     */
    private fun copyAssetFileSafely(assetName: String): String? {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }

        return try {
            file.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied asset $assetName to ${file.absolutePath}")
            file.absolutePath
        } catch (t: Throwable) {
            Log.w(TAG, "Asset $assetName not found: ${t.message}")
            null
        }
    }

    /**
     * Initializes ONNX Runtime engine and CtcDecoder safely.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            Log.i(TAG, "Initializing ONNX Runtime engine from assets: $modelDirInAssets...")
            ortEnv = OrtEnvironment.getEnvironment()
            ctcDecoder = CtcDecoder(context, "$modelDirInAssets/vocab.json")

            val modelPath = copyAssetFileSafely("$modelDirInAssets/model.onnx")
            if (modelPath != null && File(modelPath).exists()) {
                val opts = OrtSession.SessionOptions()
                opts.setInterOpNumThreads(2)
                opts.setIntraOpNumThreads(2)
                ortSession = ortEnv?.createSession(modelPath, opts)
                Log.i(TAG, "ONNX model session loaded successfully from $modelPath")
            } else {
                Log.w(TAG, "ONNX model file not found at $modelPath")
            }

            true
        } catch (t: Throwable) {
            Log.e(TAG, "ONNX Runtime init warning/error", t)
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
            val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2 / 5) // ~200ms buffer

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                onError?.invoke("فشل في تشغيل الميكروفون")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            Log.i(TAG, "AudioRecord started (16kHz Mono)")

            recordingThread = thread(start = true, name = "AudioRecognizerThread") {
                val buffer = ShortArray(bufferSize / 2)
                val audioWindow = mutableListOf<Float>()
                Log.i(TAG, "Audio capture thread started")

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

                            // Keep rolling window of ~1.5 seconds of audio for CTC inference
                            if (audioWindow.size >= SAMPLE_RATE * 3 / 2) {
                                runInference(audioWindow.toFloatArray())
                                audioWindow.clear()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in audio loop", t)
                    }
                }
                Log.i(TAG, "Audio capture thread ended")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Error starting audio", t)
            onError?.invoke("خطأ: ${t.localizedMessage}")
        }
    }

    private fun runInference(audioSamples: FloatArray) {
        val env = ortEnv ?: return
        val session = ortSession ?: return
        val decoder = ctcDecoder ?: return

        try {
            // Input shape [1, num_samples]
            val shape = longArrayOf(1, audioSamples.size.toLong())
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioSamples), shape)

            val inputName = session.inputNames.iterator().next()
            val result = session.run(mapOf(inputName to tensor))

            // Get logits output
            val outputValue = result.get(0).value
            if (outputValue is Array<*>) {
                @Suppress("UNCHECKED_CAST")
                val logits2D = (outputValue as Array<Array<FloatArray>>)[0]
                val text = decoder.decode(logits2D)
                if (text.isNotBlank()) {
                    onPartialResult?.invoke(text)
                }
            }
            tensor.close()
            result.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during ONNX inference", t)
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
        Log.i(TAG, "Audio recording stopped")
    }

    fun release() {
        stopListening()
        try { ortSession?.close() } catch (_: Throwable) {}
        try { ortEnv?.close() } catch (_: Throwable) {}
        ortSession = null
        ortEnv = null
        ctcDecoder = null
        Log.i(TAG, "AudioRecognizer released")
    }
}
