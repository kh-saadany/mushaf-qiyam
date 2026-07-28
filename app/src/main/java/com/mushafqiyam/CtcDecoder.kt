package com.mushafqiyam

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * CtcDecoder: Performs CTC Greedy Decoding for Tilawa ONNX FastConformer model.
 * Loads vocab.json (1025 tokens) with blank_id = 1024 and SentencePiece BPE decoding.
 */
class CtcDecoder(context: Context, vocabAssetPath: String) {

    companion object {
        private const val TAG = "CtcDecoder"
        private const val BLANK_INDEX = 1024 // Exact blank_id from export_metadata.json
    }

    private val vocabMap = mutableMapOf<Int, String>()

    init {
        try {
            val jsonString = context.assets.open(vocabAssetPath).use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }
            val jsonObj = JSONObject(jsonString)
            val keys = jsonObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val id = key.toIntOrNull()
                if (id != null) {
                    vocabMap[id] = jsonObj.getString(key)
                }
            }
            Log.i(TAG, "Loaded ${vocabMap.size} tokens from $vocabAssetPath (Blank ID: $BLANK_INDEX)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load vocab from $vocabAssetPath", t)
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
            var maxIdx = 0
            var maxVal = Float.NEGATIVE_INFINITY
            for (i in frame.indices) {
                if (frame[i] > maxVal) {
                    maxVal = frame[i]
                    maxIdx = i
                }
            }

            // CTC collapse rule: Ignore blank (1024) and consecutive repeated tokens
            if (maxIdx != BLANK_INDEX && maxIdx != lastToken) {
                tokenIndices.add(maxIdx)
            }
            lastToken = maxIdx
        }

        if (tokenIndices.isEmpty()) return ""

        // SentencePiece BPE reconstruction
        val builder = StringBuilder()
        for (idx in tokenIndices) {
            val token = vocabMap[idx] ?: continue
            if (token == "<unk>" || token == "<s>" || token == "</s>" || token == "<blank>") continue
            builder.append(token)
        }

        // Replace SentencePiece lower block / underscore symbol with space
        return builder.toString()
            .replace(" ", " ")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
