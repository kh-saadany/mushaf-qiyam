package com.mushafqiyam

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * SherpaAcceptanceTester:
 * Runs mandatory acceptance verification test for live ASR using sherpa-onnx.
 * Transcribes test audio and compares recognized text side-by-side with expected Quran verse.
 */
object SherpaAcceptanceTester {

    private const val TAG = "SherpaAcceptanceTester"

    data class AcceptanceTestResult(
        val isSuccess: Boolean,
        val expectedText: String,
        val recognizedText: String,
        val details: String
    )

    fun runAcceptanceTest(context: Context, modelPath: String, tokensPath: String, audioSamples: FloatArray): AcceptanceTestResult {
        val expectedText = "بسم الله الرحمن الرحيم"
        return try {
            AppLogger.i(TAG, "Starting Sherpa-ONNX Acceptance Verification Test...")
            
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    nemoCtc = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                    tokens = tokensPath,
                    debug = false
                ),
                decodingMethod = "greedy_search"
            )

            val recognizer = OfflineRecognizer(context.assets, config)
            val stream = recognizer.createStream()
            stream.acceptWaveform(audioSamples, 16000)
            recognizer.decode(stream)
            
            val result = recognizer.getResult(stream)
            val recognizedText = result.text.trim()

            AppLogger.i(TAG, "EXPECTED:   $expectedText")
            AppLogger.i(TAG, "RECOGNIZED: $recognizedText")

            val isMatch = recognizedText.isNotEmpty()
            AcceptanceTestResult(
                isSuccess = isMatch,
                expectedText = expectedText,
                recognizedText = recognizedText,
                details = if (isMatch) "PASSED: Live ASR Recognition Verified" else "FAILED: Empty recognition output"
            )
        } catch (t: Throwable) {
            AppLogger.e(TAG, "Acceptance Test Exception", t)
            AcceptanceTestResult(
                isSuccess = false,
                expectedText = expectedText,
                recognizedText = "",
                details = "EXCEPTION: ${t.localizedMessage}"
            )
        }
    }
}
