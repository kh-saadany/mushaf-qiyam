package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Manages microphone recording (16kHz Mono PCM)
 * and streams audio samples directly into sherpa-onnx OnlineRecognizer or OfflineRecognizer.
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

    private var offlineRecognizer: OfflineRecognizer? = null
    private var offlineStream: OfflineStream? = null

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null

    /**
     * Copies asset files to internal storage directory if needed.
     */
    private fun copyAssetFile(assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Copied asset $assetName to ${file.absolutePath}")
        }
        return file.absolutePath
    }

    /**
     * Initializes the sherpa-onnx engine.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            Log.i(TAG, "Initializing sherpa-onnx engine from assets: $modelDirInAssets...")

            // Copy assets to internal storage for C++ native access
            val modelPath = copyAssetFile("$modelDirInAssets/model.onnx")

            // Check if model file exists
            if (!File(modelPath).exists()) {
                Log.e(TAG, "Model file does not exist at $modelPath")
                onError?.invoke("Model file missing: $modelPath")
                return false
            }

            Log.i(TAG, "sherpa-onnx assets initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize sherpa-onnx engine", e)
            onError?.invoke("Engine Init Failed: ${e.localizedMessage}")
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
                onError?.invoke("AudioRecord init failed")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = thread(start = true, name = "AudioRecognizerThread") {
                val buffer = ShortArray(bufferSize / 2)
                Log.i(TAG, "Audio recording thread started")

                while (isRecording.get()) {
                    val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSamples > 0) {
                        // Convert ShortArray PCM to FloatArray [-1.0f, 1.0f] for ASR
                        val floatSamples = FloatArray(readSamples) { i ->
                            buffer[i] / 32768.0f
                        }

                        // Feed to online stream if active
                        onlineStream?.acceptWaveform(floatSamples, SAMPLE_RATE)
                        if (onlineRecognizer != null && onlineStream != null) {
                            while (onlineRecognizer!!.isReady(onlineStream!!)) {
                                onlineRecognizer!!.decode(onlineStream!!)
                            }
                            val text = onlineRecognizer!!.getResult(onlineStream!!).text
                            if (text.isNotBlank()) {
                                onPartialResult?.invoke(text)
                            }
                        }
                    }
                }
                Log.i(TAG, "Audio recording thread ended")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording", e)
            onError?.invoke("Recording Error: ${e.localizedMessage}")
        }
    }

    fun stopListening() {
        if (!isRecording.get()) return
        isRecording.set(false)

        try {
            recordingThread?.join(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error joining thread", e)
        }

        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
        recordingThread = null
        Log.i(TAG, "Audio recording stopped")
    }

    fun release() {
        stopListening()
        onlineStream?.release()
        onlineRecognizer?.release()
        offlineStream?.release()
        offlineRecognizer?.release()
        onlineStream = null
        onlineRecognizer = null
        offlineStream = null
        offlineRecognizer = null
        Log.i(TAG, "AudioRecognizer released")
    }
}
