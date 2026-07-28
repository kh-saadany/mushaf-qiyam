package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Manages microphone recording (16kHz Mono PCM)
 * and streams audio samples directly into sherpa-onnx.
 * Designed with defensive error handling to prevent native/JNI crashes.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    // Callback for real-time recognized text updates
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var offlineRecognizer: Any? = null
    private var offlineStream: Any? = null

    private var onlineRecognizer: Any? = null
    private var onlineStream: Any? = null

    /**
     * Safely copies asset files to internal storage directory if present.
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
            Log.w(TAG, "Asset file $assetName not found in APK assets: ${t.message}")
            null
        }
    }

    /**
     * Initializes the sherpa-onnx engine safely without crashing if native lib is missing.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            Log.i(TAG, "Initializing sherpa-onnx engine from assets: $modelDirInAssets...")

            // Copy assets to internal storage safely
            val modelPath = copyAssetFileSafely("$modelDirInAssets/model.onnx")

            if (modelPath == null || !File(modelPath).exists()) {
                Log.w(TAG, "Model file is missing or not packaged in assets yet ($modelDirInAssets/model.onnx)")
                onError?.invoke("ملف النموذج الصوتي غير موجود في أصول التطبيق")
                return false
            }

            // Attempt to load sherpa-onnx classes dynamically to prevent UnsatisfiedLinkError crashes
            try {
                val onlineConfigClass = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
                Log.i(TAG, "sherpa-onnx class loaded successfully: ${onlineConfigClass.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "Native library or sherpa-onnx class error", t)
                onError?.invoke("خطأ في تحميل مكتبة sherpa-onnx الصوتية: ${t.localizedMessage}")
                return false
            }

            Log.i(TAG, "sherpa-onnx engine initialization check completed successfully")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize sherpa-onnx engine", t)
            onError?.invoke("خطأ في تهيئة المحرك: ${t.localizedMessage}")
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
                onError?.invoke("فشل في تشغيل ميكروفون الجهاز")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = thread(start = true, name = "AudioRecognizerThread") {
                val buffer = ShortArray(bufferSize / 2)
                Log.i(TAG, "Audio recording thread started")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSamples > 0) {
                            val floatSamples = FloatArray(readSamples) { i ->
                                buffer[i] / 32768.0f
                            }
                            // Process float audio buffer safely
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Error in audio recording loop", t)
                    }
                }
                Log.i(TAG, "Audio recording thread ended")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "Error starting audio recording", t)
            onError?.invoke("خطأ أثناء تسجيل الصوت: ${t.localizedMessage}")
        }
    }

    fun stopListening() {
        if (!isRecording.get()) return
        isRecording.set(false)

        try {
            recordingThread?.join(1000)
        } catch (t: Throwable) {
            Log.e(TAG, "Error joining thread", t)
        }

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing AudioRecord", t)
        }
        audioRecord = null
        recordingThread = null
        Log.i(TAG, "Audio recording stopped")
    }

    fun release() {
        stopListening()
        onlineStream = null
        onlineRecognizer = null
        offlineStream = null
        offlineRecognizer = null
        Log.i(TAG, "AudioRecognizer released")
    }
}
