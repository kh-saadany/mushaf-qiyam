package com.mushafqiyam

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*

/**
 * SherpaWrapper: Encapsulates all sherpa-onnx native calls.
 * Isolated in a dedicated file so that native library loading errors
 * can be safely caught by AudioRecognizer without crashing the JVM on startup.
 */
class SherpaWrapper(assetManager: AssetManager, modelDir: String) {

    companion object {
        private const val TAG = "SherpaWrapper"
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    init {
        try {
            Log.i(TAG, "Initializing Sherpa OnlineRecognizer from assets: $modelDir...")
            val config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    tokens = "$modelDir/tokens.txt",
                    neMoCtc = OnlineNeMoCtcModelConfig(
                        model = "$modelDir/model.onnx"
                    ),
                    numThreads = 2,
                    debug = false
                )
            )
            recognizer = OnlineRecognizer(assetManager, config)
            stream = recognizer?.createStream()
            Log.i(TAG, "Sherpa OnlineRecognizer created successfully!")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize Sherpa OnlineRecognizer", t)
            throw t
        }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int = 16000): String? {
        val rec = recognizer ?: return null
        val st = stream ?: return null
        return try {
            st.acceptWaveform(samples, sampleRate)
            while (rec.isReady(st)) {
                rec.decode(st)
            }
            val text = rec.getResult(st).text
            if (text.isNotBlank()) text else null
        } catch (t: Throwable) {
            Log.e(TAG, "Error in acceptWaveform", t)
            null
        }
    }

    fun release() {
        try {
            stream?.release()
            recognizer?.release()
        } catch (_: Throwable) {}
        stream = null
        recognizer = null
        Log.i(TAG, "SherpaWrapper released")
    }
}
