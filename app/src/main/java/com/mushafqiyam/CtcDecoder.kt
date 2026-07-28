package com.mushafqiyam

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer

/**
 * CtcDecoder: Performs CTC Greedy Decoding on ONNX model output logits.
 * Converts ONNX ASR output tensors into Arabic text strings.
 */
class CtcDecoder(context: Context, tokensAssetPath: String) {

    companion object {
        private const val TAG = "CtcDecoder"
        private const val BLANK_INDEX = 0
    }

    private val tokensMap = mutableMapOf<Int, String>()

    init {
        try {
            context.assets.open(tokensAssetPath).use { input ->
                BufferedReader(InputStreamReader(input)).useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        val parts = line.split(" ")
                        val token = if (parts.size >= 1) parts[0] else line
                        tokensMap[index] = token
                    }
                }
            }
            Log.i(TAG, "Loaded ${tokensMap.size} tokens from $tokensAssetPath")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load tokens from $tokensAssetPath", t)
        }
    }

    /**
     * Decodes 2D float array logits [T, V] using CTC Greedy Search.
     */
    fun decode(logits: Array<FloatArray>): String {
        if (logits.isEmpty()) return ""

        val tokenIndices = mutableListOf<Int>()
        var lastToken = -1

        for (frame in logits) {
            // Find argmax for current frame
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in frame.indices) {
                if (frame[i] > maxVal) {
                    maxVal = frame[i]
                    maxIdx = i
                }
            }

            // CTC collapse rule: Ignore blank and consecutive duplicates
            if (maxIdx != BLANK_INDEX && maxIdx != lastToken) {
                tokenIndices.add(maxIdx)
            }
            lastToken = maxIdx
        }

        // Map token indices to Arabic characters / words
        val builder = StringBuilder()
        for (idx in tokenIndices) {
            val token = tokensMap[idx] ?: continue
            // Replace word boundary symbols if present
            val cleanToken = token.replace(" ", " ").replace("<unk>", "")
            builder.append(cleanToken)
        }

        return builder.toString().trim()
    }
}
