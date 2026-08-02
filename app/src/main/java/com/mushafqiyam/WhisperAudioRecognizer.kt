package com.mushafqiyam

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * WhisperAudioRecognizer: High-performance offline speech recognizer utilizing
 * Sherpa-ONNX's Stateless OfflineWhisper engine with Silero VAD and RMS Energy Gate.
 */
class WhisperAudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "WhisperRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private const val RMS_SILENCE_THRESHOLD = 0.015f
        private const val MIN_SPEECH_DURATION_MS = 400
        private const val SLIDING_WINDOW_MS = 1800
        private const val HOP_MS = 500
    }

    private var audioRecord: AudioRecord? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    var onPartialResult: ((String) -> Unit)? = null
    var onAudioLevel: ((Float) -> Unit)? = null

    private fun resolveRawResource(resId: Int, fileName: String): String? {
        return try {
            val internalFile = File(context.filesDir, fileName)
            if (!internalFile.exists() || internalFile.length() == 0L) {
                context.resources.openRawResource(resId).use { input ->
                    FileOutputStream(internalFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            internalFile.absolutePath
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Could not resolve raw resource $fileName: ${t.localizedMessage}")
            null
        }
    }

    private fun resolveFilePath(subDir: String, fileName: String): String? {
        return try {
            val internalFile = File(File(context.filesDir, subDir), fileName)
            if (internalFile.exists() && internalFile.length() > 0) {
                return internalFile.absolutePath
            }
            val assetPath = "$subDir/$fileName"
            context.assets.open(assetPath).use { input ->
                val dir = File(context.filesDir, subDir)
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(internalFile).use { output -> input.copyTo(output) }
            }
            internalFile.absolutePath
        } catch (t: Throwable) {
            val fileInDir = File(File(context.filesDir, subDir), fileName)
            if (fileInDir.exists()) fileInDir.absolutePath else null
        }
    }

    /**
     * Initializes sherpa-onnx OfflineWhisper engine safely.
     */
    fun initEngine(modelDirInAssets: String): Boolean {
        return try {
            AppLogger.i(TAG, "Starting Whisper engine init (Dir: $modelDirInAssets)...")

            val encoderPath = resolveFilePath(modelDirInAssets, "encoder.int8.onnx")
                ?: resolveFilePath(modelDirInAssets, "encoder.onnx")
            val decoderPath = resolveFilePath(modelDirInAssets, "decoder.int8.onnx")
                ?: resolveFilePath(modelDirInAssets, "decoder.onnx")
            val tokensPath = resolveFilePath(modelDirInAssets, "tokens.txt")

            if (encoderPath != null && File(encoderPath).exists() &&
                decoderPath != null && File(decoderPath).exists() &&
                tokensPath != null && File(tokensPath).exists()) {

                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    language = "ar",
                    task = "transcribe"
                )

                val config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = whisperConfig,
                        tokens = tokensPath,
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
                recognizer = OfflineRecognizer(null, config)
                AppLogger.i(TAG, "Sherpa-ONNX Whisper engine initialized successfully")

                // Initialize Silero VAD
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
                        AppLogger.i(TAG, "Official Silero VAD initialized successfully for Whisper")
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Silero VAD init error", t)
                        vad = null
                    }
                } else {
                    vad = null
                }
                true
            } else {
                AppLogger.w(TAG, "Whisper model files missing in $modelDirInAssets, falling back to CTC FastConformer")
                false
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Failed to initialize Whisper engine", t)
            false
        }
    }

    fun startListening(): Boolean {
        if (isRecording) return true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, SAMPLE_RATE * 2 * 2)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord failed to initialize")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread { processAudioLoop() }
            recordingThread?.start()

            AppLogger.i(TAG, "Whisper Audio recording started successfully")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error starting Whisper audio recording", e)
            false
        }
    }

    fun stopListening() {
        if (!isRecording) return

        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping audioRecord", e)
        } finally {
            audioRecord = null
        }
        AppLogger.i(TAG, "Whisper Audio recording stopped")
    }

    private fun processAudioLoop() {
        val minUtteranceSamples = (SAMPLE_RATE * 0.4).toInt()  // 400ms minimum utterance
        val maxUtteranceSamples = (SAMPLE_RATE * 15.0).toInt() // 15s safety cap

        val pcmAccumulator = ArrayList<Float>(SAMPLE_RATE * 5)
        var isSpeechActive = false

        val shortBuffer = ShortArray(512)
        vad?.reset()

        AppLogger.i(TAG, "Whisper Audio capture loop started (Segment-Based Utterance Mode)")

        while (isRecording && !Thread.currentThread().isInterrupted) {
            val readCount = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            if (readCount <= 0) continue

            var sumSquares = 0.0
            val floatFrame = FloatArray(readCount)
            for (i in 0 until readCount) {
                val sampleFloat = shortBuffer[i] / 32768.0f
                floatFrame[i] = sampleFloat
                sumSquares += (sampleFloat * sampleFloat).toDouble()
            }

            val rms = sqrt(sumSquares / readCount).toFloat()
            val level = (rms * 5.0f).coerceIn(0.0f, 1.0f)
            onAudioLevel?.invoke(level)

            // Silero VAD evaluation with mandatory C++ queue draining
            val v = vad
            val isSpeech = if (v != null) {
                v.acceptWaveform(floatFrame)
                while (!v.empty()) {
                    v.pop()
                }
                v.isSpeechDetected()
            } else {
                level > 0.08f
            }

            if (isSpeech) {
                isSpeechActive = true
                for (s in floatFrame) {
                    pcmAccumulator.add(s)
                }

                // Safety cap: Trigger inference if single utterance exceeds 15 seconds
                if (pcmAccumulator.size >= maxUtteranceSamples) {
                    val completeUtterance = pcmAccumulator.toFloatArray()
                    AppLogger.i(TAG, "Safety cap reached: Triggering Whisper inference on ${completeUtterance.size} samples (${completeUtterance.size * 1000 / SAMPLE_RATE}ms)...")
                    runInference(completeUtterance)
                    pcmAccumulator.clear()
                    isSpeechActive = false
                }
            } else {
                // Speech -> Silence transition: Trigger Whisper ONCE on complete utterance!
                if (isSpeechActive && pcmAccumulator.size >= minUtteranceSamples) {
                    val completeUtterance = pcmAccumulator.toFloatArray()
                    AppLogger.i(TAG, "Utterance completed (Speech -> Silence transition): Triggering Whisper inference on ${completeUtterance.size} samples (${completeUtterance.size * 1000 / SAMPLE_RATE}ms)...")
                    runInference(completeUtterance)
                }
                pcmAccumulator.clear()
                isSpeechActive = false
            }
        }
        AppLogger.i(TAG, "Whisper Audio capture thread stopped")
    }

    private fun runInference(audioSamples: FloatArray) {
        val rec = recognizer ?: return
        if (audioSamples.isEmpty()) return

        try {
            AppLogger.i(TAG, "Triggering Whisper inference on ${audioSamples.size} audio samples (${audioSamples.size * 1000 / SAMPLE_RATE}ms)...")
            val stream = rec.createStream()
            stream.acceptWaveform(audioSamples, SAMPLE_RATE)
            rec.decode(stream)

            val rawResult = rec.getResult(stream).text.trim()
            stream.release()

            AppLogger.i(TAG, "Raw Whisper ASR output: '$rawResult'")

            if (rawResult.isNotBlank()) {
                val cleanedResult = truncateSingleChunkRepetition(rawResult)
                AppLogger.i(TAG, "Recognized text (Whisper): $cleanedResult")
                onPartialResult?.invoke(cleanedResult)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Whisper Inference error", e)
        }
    }

    /**
     * Prevents intra-chunk n-gram repetition (e.g. "بسم الله بسم الله").
     * Truncates output at the first consecutive repeated 2-word or 3-word n-gram.
     */
    private fun truncateSingleChunkRepetition(text: String): String {
        val words = text.split(" ").filter { it.isNotBlank() }
        if (words.size < 4) return text

        for (gramSize in 2..3) {
            if (words.size >= gramSize * 2) {
                for (i in 0..(words.size - gramSize * 2)) {
                    val firstGram = words.subList(i, i + gramSize).joinToString(" ")
                    val secondGram = words.subList(i + gramSize, i + gramSize * 2).joinToString(" ")
                    if (firstGram.equals(secondGram, ignoreCase = true)) {
                        return words.subList(0, i + gramSize).joinToString(" ")
                    }
                }
            }
        }
        return text
    }

    fun release() {
        stopListening()
        vad?.release()
        vad = null
        recognizer?.release()
        recognizer = null
        AppLogger.i(TAG, "Whisper AudioRecognizer resources released")
    }
}
