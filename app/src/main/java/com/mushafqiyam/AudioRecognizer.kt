package com.mushafqiyam

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * AudioRecognizer: Real-time Streaming Automatic Speech Recognition for the Holy Quran.
 * Powered by FastConformer Quran Streaming Transducer via Native ONNX Runtime.
 */
class AudioRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 28800 // 1.8 seconds sliding window
        private const val HOP_SAMPLES = 9600     // 0.6 seconds step
    }

    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ortEnv: OrtEnvironment? = null
    private var encoderSession: OrtSession? = null
    private var jointSession: OrtSession? = null

    private val idToToken = HashMap<Int, String>()
    private var blankId = 1024
    private val fbank = FastConformerFbank()

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

    private fun loadTokens(tokensPath: String) {
        idToToken.clear()
        File(tokensPath).forEachLine(Charsets.UTF_8) { line ->
            val trimmed = line.trimEnd('\r', '\n')
            val lastSpace = trimmed.lastIndexOf(' ')
            if (lastSpace > 0) {
                val token = trimmed.substring(0, lastSpace)
                val idStr = trimmed.substring(lastSpace + 1)
                val id = idStr.toIntOrNull()
                if (id != null) {
                    idToToken[id] = token
                }
            }
        }
        blankId = idToToken.keys.maxOrNull() ?: 1024
        AppLogger.i(TAG, "Loaded ${idToToken.size} tokens from $tokensPath (Blank ID: $blankId)")
    }

    /**
     * Initializes native ONNX Runtime sessions for FastConformer Quran Streaming Transducer.
     */
    fun initEngine(modelDirInAssets: String = "tilawa_model"): Boolean {
        return try {
            AppLogger.i(TAG, "Starting Native ONNX Runtime FastConformer Quran engine init (Dir: $modelDirInAssets)...")

            // Clean legacy whisper or obsolete models from internal filesDir to free space
            try {
                val whisperDir = File(context.filesDir, "tilawa_whisper")
                if (whisperDir.exists()) whisperDir.deleteRecursively()
                val oldModel = File(context.filesDir, "$modelDirInAssets/model.int8.onnx")
                if (oldModel.exists()) oldModel.delete()
            } catch (_: Throwable) {}

            val encoderPath = resolveFilePath(modelDirInAssets, "encoder.int8.onnx")
            val jointPath = resolveFilePath(modelDirInAssets, "joiner.int8.onnx")
                ?: resolveFilePath(modelDirInAssets, "decoder.int8.onnx")
            val tokensPath = resolveFilePath(modelDirInAssets, "tokens.txt")

            if (encoderPath != null && File(encoderPath).exists() &&
                jointPath != null && File(jointPath).exists() &&
                tokensPath != null && File(tokensPath).exists()) {

                loadTokens(tokensPath)

                val env = OrtEnvironment.getEnvironment()
                ortEnv = env

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                }

                encoderSession = env.createSession(encoderPath, sessionOptions)
                jointSession = env.createSession(jointPath, sessionOptions)

                AppLogger.i(TAG, "Native ONNX Runtime FastConformer Quran engine initialized successfully")
                true
            } else {
                AppLogger.w(TAG, "FastConformer Quran model files missing: encoder=$encoderPath, joint=$jointPath, tokens=$tokensPath")
                mainHandler.post { onError?.invoke("ملفات نموذج التلاوة القرآني غير متوفرة") }
                false
            }
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "Native ONNX Runtime Library link error", e)
            mainHandler.post { onError?.invoke("تنبيه المحرك: تعذر ربط مكتبة ONNX Runtime (${e.localizedMessage})") }
            false
        } catch (t: Throwable) {
            AppLogger.e(TAG, "FastConformer Quran init error", t)
            mainHandler.post { onError?.invoke("تنبيه المحرك: ${t.localizedMessage}") }
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isRecording.get()) return true
        if (encoderSession == null || jointSession == null || ortEnv == null) {
            AppLogger.w(TAG, "ONNX sessions not initialized")
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

            recordingThread = thread(start = true, name = "FastConformerAudioThread") {
                val readChunk = ShortArray(512)
                val windowBuffer = FloatArray(WINDOW_SAMPLES)
                var accumulatedSamples = 0
                var samplesSinceLastInference = 0

                var lastEmittedText = ""
                var consecutiveSilenceFrames = 0

                AppLogger.i(TAG, "Native FastConformer Audio capture loop started (16kHz Mono, 1.8s sliding window)")

                while (isRecording.get()) {
                    try {
                        val readSamples = audioRecord?.read(readChunk, 0, readChunk.size) ?: 0
                        if (readSamples > 0) {
                            var sum = 0.0
                            val floatSamples = FloatArray(readSamples)
                            for (i in 0 until readSamples) {
                                val s = readChunk[i] / 32768.0f
                                floatSamples[i] = s
                                sum += (s.toDouble() * s.toDouble())
                            }
                            val rms = sqrt(sum / readSamples).toFloat()
                            val level = (rms * 5.0f).coerceIn(0.0f, 1.0f)
                            mainHandler.post { onAudioLevel?.invoke(level) }

                            if (rms > 0.012f) {
                                consecutiveSilenceFrames = 0
                            } else {
                                consecutiveSilenceFrames++
                            }

                            // Slide buffer left and insert new audio samples at the end
                            System.arraycopy(windowBuffer, readSamples, windowBuffer, 0, WINDOW_SAMPLES - readSamples)
                            System.arraycopy(floatSamples, 0, windowBuffer, WINDOW_SAMPLES - readSamples, readSamples)

                            accumulatedSamples = minOf(WINDOW_SAMPLES, accumulatedSamples + readSamples)
                            samplesSinceLastInference += readSamples

                            // Trigger inference every HOP_SAMPLES (0.6s) if window is full
                            if (accumulatedSamples >= WINDOW_SAMPLES && samplesSinceLastInference >= HOP_SAMPLES) {
                                samplesSinceLastInference = 0

                                // If speech was present recently (~within 1.4s)
                                if (consecutiveSilenceFrames < 45) {
                                    val feats = fbank.computeFeatures(windowBuffer)
                                    val recognized = decodeWindow(feats)
                                    if (recognized.isNotBlank() && recognized != lastEmittedText) {
                                        lastEmittedText = recognized
                                        AppLogger.i(TAG, "Recognized text (FastConformer Native ONNX): $recognized")
                                        mainHandler.post { onPartialResult?.invoke(recognized) }
                                    }
                                } else {
                                    // Reset deduplication state during silence
                                    if (consecutiveSilenceFrames >= 38 && lastEmittedText.isNotEmpty()) {
                                        lastEmittedText = ""
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
            mainHandler.post { onError?.invoke("خطأ: ${t.localizedMessage}") }
            false
        }
    }

    /**
     * Decodes extracted Mel features using FastConformer Encoder and Joint Transducer.
     */
    private fun decodeWindow(features: Array<FloatArray>): String {
        val env = ortEnv ?: return ""
        val enc = encoderSession ?: return ""
        val joint = jointSession ?: return ""

        val numFrames = features[0].size
        if (numFrames <= 0) return ""

        // 1. Flatten features: [1, 80, numFrames] in row-major order
        val flatFeatures = FloatArray(80 * numFrames)
        var offset = 0
        for (m in 0 until 80) {
            System.arraycopy(features[m], 0, flatFeatures, offset, numFrames)
            offset += numFrames
        }

        val audioTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatFeatures),
            longArrayOf(1, 80, numFrames.toLong())
        )
        val lengthTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(numFrames.toLong())),
            longArrayOf(1)
        )

        var eoFlat: FloatArray? = null
        var encodedLength = 0

        try {
            val encInputs = mapOf(
                "audio_signal" to audioTensor,
                "length" to lengthTensor
            )
            enc.run(encInputs).use { encResult ->
                for (entry in encResult) {
                    if (entry.key == "encoded_lengths") {
                        val elTensor = entry.value as OnnxTensor
                        encodedLength = elTensor.longBuffer.get(0).toInt()
                    }
                }
                for (entry in encResult) {
                    if (entry.key == "outputs") {
                        val outTensor = entry.value as OnnxTensor
                        val totalFloats = 512 * encodedLength
                        val buffer = FloatArray(totalFloats)
                        outTensor.floatBuffer.get(buffer)
                        eoFlat = buffer
                    }
                }
            }
        } finally {
            audioTensor.close()
            lengthTensor.close()
        }

        val encOutputs = eoFlat ?: return ""
        if (encodedLength <= 0) return ""

        // 2. Greedy search on Joint Network
        val s1 = FloatArray(640)
        val s2 = FloatArray(640)
        var lastTarget = blankId
        val outTokens = mutableListOf<Int>()

        val frameEncOut = FloatArray(512)
        val targetBuf = IntBuffer.allocate(1)
        val targetLenBuf = IntBuffer.wrap(intArrayOf(1))
        val s1Buf = FloatBuffer.allocate(640)
        val s2Buf = FloatBuffer.allocate(640)

        val encSliceShape = longArrayOf(1, 512, 1)
        val targetShape = longArrayOf(1, 1)
        val targetLenShape = longArrayOf(1)
        val stateShape = longArrayOf(1, 1, 640)

        for (t in 0 until encodedLength) {
            // Extract channel slice eo[:, :, t:t+1]
            for (c in 0 until 512) {
                frameEncOut[c] = encOutputs[c * encodedLength + t]
            }

            for (u in 0 until 5) {
                targetBuf.clear()
                targetBuf.put(0, lastTarget)

                s1Buf.clear()
                s1Buf.put(s1)
                s1Buf.flip()

                s2Buf.clear()
                s2Buf.put(s2)
                s2Buf.flip()

                val encSliceTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frameEncOut), encSliceShape)
                val targetTensor = OnnxTensor.createTensor(env, targetBuf, targetShape)
                val targetLenTensor = OnnxTensor.createTensor(env, targetLenBuf, targetLenShape)
                val s1Tensor = OnnxTensor.createTensor(env, s1Buf, stateShape)
                val s2Tensor = OnnxTensor.createTensor(env, s2Buf, stateShape)

                var shouldBreak = false
                try {
                    val jointInputs = mapOf(
                        "encoder_outputs" to encSliceTensor,
                        "targets" to targetTensor,
                        "target_length" to targetLenTensor,
                        "input_states_1" to s1Tensor,
                        "input_states_2" to s2Tensor
                    )

                    joint.run(jointInputs).use { jointResult ->
                        var logitsTensor: OnnxTensor? = null
                        var ns1Tensor: OnnxTensor? = null
                        var ns2Tensor: OnnxTensor? = null

                        for (entry in jointResult) {
                            when (entry.key) {
                                "outputs" -> logitsTensor = entry.value as OnnxTensor
                                "output_states_1" -> ns1Tensor = entry.value as OnnxTensor
                                "output_states_2" -> ns2Tensor = entry.value as OnnxTensor
                            }
                        }

                        if (logitsTensor != null) {
                            val fb = logitsTensor.floatBuffer
                            var maxK = 0
                            var maxVal = Float.NEGATIVE_INFINITY
                            val count = fb.remaining()
                            for (k in 0 until count) {
                                val v = fb.get(k)
                                if (v > maxVal) {
                                    maxVal = v
                                    maxK = k
                                }
                            }

                            if (maxK == blankId) {
                                shouldBreak = true
                            } else {
                                outTokens.add(maxK)
                                lastTarget = maxK
                                ns1Tensor?.floatBuffer?.get(s1)
                                ns2Tensor?.floatBuffer?.get(s2)
                            }
                        }
                    }
                } finally {
                    encSliceTensor.close()
                    targetTensor.close()
                    targetLenTensor.close()
                    s1Tensor.close()
                    s2Tensor.close()
                }

                if (shouldBreak) break
            }
        }

        if (outTokens.isEmpty()) return ""

        val sb = StringBuilder()
        for (id in outTokens) {
            val tok = idToToken[id] ?: continue
            if (tok == "<blk>" || tok == "<unk>") continue
            sb.append(tok)
        }
        return sb.toString().replace("\u2581", " ").trim()
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
        try {
            encoderSession?.close()
            jointSession?.close()
            ortEnv?.close()
        } catch (t: Throwable) {
            AppLogger.w(TAG, "Error closing ORT sessions: ${t.localizedMessage}")
        }
        encoderSession = null
        jointSession = null
        ortEnv = null
        AppLogger.i(TAG, "AudioRecognizer released")
    }
}
