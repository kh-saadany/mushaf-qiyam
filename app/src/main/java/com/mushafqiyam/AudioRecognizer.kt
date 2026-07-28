package com.mushafqiyam

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * AudioRecognizer: Manages microphone recording (16kHz Mono PCM)
 * and streams audio samples directly into SherpaWrapper / ONNX Runtime.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null

    private var sherpaWrapper: SherpaWrapper? = null

    // Callbacks for UI updates
    var onAudioLevel: ((Float) -> Unit)? = null
    var onPartialResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Initializes SherpaWrapper safely without risking startup crashes.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            Log.i(TAG, "Initializing SherpaWrapper from assets: $modelDirInAssets...")
            sherpaWrapper = SherpaWrapper(context.assets, modelDirInAssets)
            Log.i(TAG, "SherpaWrapper initialized successfully!")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "SherpaWrapper init warning/error", t)
            onError?.invoke("تنبيه المحرك الصوتى: ${t.localizedMessage}")
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
                Log.i(TAG, "Audio capture thread started")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readSamples > 0) {
                            // Calculate RMS audio level for visual feedback
                            var sum = 0.0
                            val floatSamples = FloatArray(readSamples)
                            for (i in 0 until readSamples) {
                                sum += (buffer[i].toDouble() * buffer[i].toDouble())
                                floatSamples[i] = buffer[i] / 32768.0f
                            }
                            val rms = Math.sqrt(sum / readSamples) / 32768.0
                            val level = (rms * 5.0).coerceIn(0.0, 1.0).toFloat()
                            onAudioLevel?.invoke(level)

                            // Feed to Sherpa ASR engine
                            val text = sherpaWrapper?.acceptWaveform(floatSamples, SAMPLE_RATE)
                            if (!text.isNull信Blank()) {
                                onPartialResult?.invoke(text)
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

    private fun String?.isNull信Blank(): Boolean {
        return this == null || this.trim().isEmpty()
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
        try { sherpaWrapper?.release() } catch (_: Throwable) {}
        sherpaWrapper = null
        Log.i(TAG, "AudioRecognizer released")
    }
}
