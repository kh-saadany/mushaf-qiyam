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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * AudioRecognizer: Real-time Streaming Automatic Speech Recognition for the Holy Quran.
 * Powered by FastConformer Quran Streaming Transducer via Native ONNX Runtime.
 * Features Ping-Pong Double Buffering, Producer-Consumer Threading, and Zero-Allocation Decoding.
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
    private var captureThread: Thread? = null
    private var inferenceThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Ping-Pong Double Buffering & Producer-Consumer Queue
    private val pingPongBuffers = Array(2) { FloatArray(WINDOW_SAMPLES) }
    private val inferenceQueue = ArrayBlockingQueue<Int>(2)

    // Preallocated buffers for zero-allocation inference loop
    private val flatFeatures = FloatArray(80 * (1 + WINDOW_SAMPLES / FastConformerFbank.HOP_LENGTH))
    private val s1 = FloatArray(640)
    private val s2 = FloatArray(640)
    private val frameEncOut = FloatArray(512)
    private val targetBuf = IntBuffer.allocate(1)
    private val targetLenBuf = IntBuffer.wrap(intArrayOf(1))
    private val s1Buf = FloatBuffer.allocate(640)
    private val s2Buf = FloatBuffer.allocate(640)
    private val outTokens = ArrayList<Int>(64)
    private val eoFlatBuffer = FloatArray(512 * 64)

    private val encSliceShape = longArrayOf(1, 512, 1)
    private val targetShape = longArrayOf(1, 1)
    private val targetLenShape = longArrayOf(1)
    private val stateShape = longArrayOf(1, 1, 640)

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
            if (fileName == "encoder.onnx") {
                val oldEncoder = File(context.filesDir, "$modelDirInAssets/encoder.int8.onnx")
                if (oldEncoder.exists()) {
                    oldEncoder.delete()
                    AppLogger.i(TAG, "Reclaimed space: removed obsolete encoder.int8.onnx")
                }
            }
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

            val encoderPath = resolveFilePath(modelDirInAssets, "encoder.onnx")
                ?: resolveFilePath(modelDirInAssets, "encoder.int8.onnx")
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
                logMemoryUsage("Post-Init")
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

    /**
     * Reports live Java Heap and Native RAM diagnostics to both AppLogger and UI callbacks.
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

            inferenceQueue.clear()
            audioRecord?.startRecording()
            isRecording.set(true)
            logMemoryUsage("Start-Listening")
            AppLogger.i(TAG, "Audio recording started successfully (Ping-Pong Double Buffering Enabled)")

            // 1. Dedicated Inference Consumer Worker Thread
            inferenceThread = thread(start = true, name = "FastConformerInferenceThread") {
                AppLogger.i(TAG, "FastConformer Inference Consumer thread started")
                var lastEmittedText = ""
                var inferenceCounter = 0

                while (isRecording.get()) {
                    try {
                        val bufferIndex = inferenceQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        if (bufferIndex == -1) {
                            lastEmittedText = ""
                            continue
                        }

                        val audioWindow = pingPongBuffers[bufferIndex]
                        val feats = fbank.computeFeatures(audioWindow)
                        val recognized = decodeWindow(feats)

                        if (recognized.isNotBlank() && recognized != lastEmittedText) {
                            lastEmittedText = recognized
                            AppLogger.i(TAG, "Recognized text (FastConformer Native ONNX): $recognized")
                            mainHandler.post { onPartialResult?.invoke(recognized) }
                        }

                        inferenceCounter++
                        if (inferenceCounter % 15 == 0) {
                            logMemoryUsage("Continuous-Inference")
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in inference worker loop", t)
                    }
                }
                AppLogger.i(TAG, "FastConformer Inference Consumer thread stopped")
            }

            // 2. Dedicated Audio Capture Producer Thread (Never blocks on inference!)
            captureThread = thread(start = true, name = "FastConformerAudioCaptureThread") {
                val readChunk = ShortArray(512)
                val floatSamples = FloatArray(512)
                val slidingBuffer = FloatArray(WINDOW_SAMPLES)
                var accumulatedSamples = 0
                var samplesSinceLastInference = 0
                var pingPongWriteIdx = 0
                var consecutiveSilenceFrames = 0

                AppLogger.i(TAG, "FastConformer Audio Capture Producer loop started (16kHz Mono, 1.8s sliding window)")

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

                            if (rms > 0.004f) {
                                consecutiveSilenceFrames = 0
                            } else {
                                consecutiveSilenceFrames++
                            }

                            // Slide buffer left and insert new audio samples at the end (Zero-allocation)
                            System.arraycopy(slidingBuffer, readSamples, slidingBuffer, 0, WINDOW_SAMPLES - readSamples)
                            System.arraycopy(floatSamples, 0, slidingBuffer, WINDOW_SAMPLES - readSamples, readSamples)

                            accumulatedSamples = minOf(WINDOW_SAMPLES, accumulatedSamples + readSamples)
                            samplesSinceLastInference += readSamples

                            // Dispatch window every HOP_SAMPLES (0.6s) if window is full
                            if (accumulatedSamples >= WINDOW_SAMPLES && samplesSinceLastInference >= HOP_SAMPLES) {
                                samplesSinceLastInference = 0

                                if (consecutiveSilenceFrames < 45) {
                                    // Copy snapshot to ping-pong buffer and notify consumer
                                    System.arraycopy(slidingBuffer, 0, pingPongBuffers[pingPongWriteIdx], 0, WINDOW_SAMPLES)
                                    val queued = inferenceQueue.offer(pingPongWriteIdx)
                                    if (queued) {
                                        pingPongWriteIdx = 1 - pingPongWriteIdx
                                    } else {
                                        AppLogger.w(TAG, "Inference queue busy; frame dropped without blocking capture")
                                    }
                                } else {
                                    if (consecutiveSilenceFrames >= 38) {
                                        inferenceQueue.offer(-1)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Error in audio capture loop", t)
                    }
                }
                AppLogger.i(TAG, "FastConformer Audio Capture Producer loop stopped")
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
     * Uses preallocated buffers to eliminate object allocations in the hot loop.
     */
    private fun decodeWindow(features: Array<FloatArray>): String {
        val env = ortEnv ?: return ""
        val enc = encoderSession ?: return ""
        val joint = jointSession ?: return ""

        val numFrames = features[0].size
        if (numFrames <= 0) return ""

        // 1. Flatten features: [1, 80, numFrames] in row-major order (Zero-allocation using preallocated flatFeatures)
        var offset = 0
        for (m in 0 until 80) {
            System.arraycopy(features[m], 0, flatFeatures, offset, numFrames)
            offset += numFrames
        }

        val audioTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(flatFeatures, 0, 80 * numFrames),
            longArrayOf(1, 80, numFrames.toLong())
        )
        val lengthTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(numFrames.toLong())),
            longArrayOf(1)
        )

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
                        if (totalFloats <= eoFlatBuffer.size) {
                            outTensor.floatBuffer.get(eoFlatBuffer, 0, totalFloats)
                        } else {
                            val temp = FloatArray(totalFloats)
                            outTensor.floatBuffer.get(temp)
                            System.arraycopy(temp, 0, eoFlatBuffer, 0, minOf(temp.size, eoFlatBuffer.size))
                        }
                    }
                }
            }
        } finally {
            audioTensor.close()
            lengthTensor.close()
        }

        if (encodedLength <= 0) return ""

        // 2. Greedy search on Joint Network
        s1.fill(0f)
        s2.fill(0f)
        var lastTarget = blankId
        outTokens.clear()

        // Create targetLenTensor once before outer loop to save allocations
        targetLenBuf.clear()
        targetLenBuf.put(0, 1)
        val targetLenTensor = OnnxTensor.createTensor(env, targetLenBuf, targetLenShape)

        try {
            for (t in 0 until encodedLength) {
                // Extract channel slice eo[:, :, t:t+1]
                for (c in 0 until 512) {
                    frameEncOut[c] = eoFlatBuffer[c * encodedLength + t]
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
                        s1Tensor.close()
                        s2Tensor.close()
                    }

                    if (shouldBreak) break
                }
            }
        } finally {
            targetLenTensor.close()
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

        inferenceQueue.offer(-1) // unblock consumer
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
        inferenceQueue.clear()

        logMemoryUsage("Stop-Listening")
        AppLogger.i(TAG, "Audio recording and inference worker stopped")
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
