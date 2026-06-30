package com.mushafqiyam

import java.nio.ByteBuffer

class MushafEngine {
    companion object {
        var isNativeReady = false
            private set

        init {
            try {
                System.loadLibrary("mushafqiyam")
                isNativeReady = true
            } catch (e: UnsatisfiedLinkError) {
                // Log error but don't crash the app
                e.printStackTrace()
            }
        }
    }

    /**
     * Initializes the ONNX Runtime engine.
     * @param encoderPath Path to moonshine encoder_model.onnx
     * @param decoderPath Path to moonshine decoder_model.onnx
     * @param vadPath Path to silero_vad.onnx
     * @return true if initialization is successful
     */
    external fun initEngine(encoderPath: String, decoderPath: String, vadPath: String): Boolean

    /**
     * Enqueue standard ShortArray audio.
     */
    external fun enqueueAudio(audioData: ShortArray, length: Int)

    /**
     * Enqueue DirectByteBuffer audio to avoid JNI array copying overhead (Zero-Copy).
     */
    external fun enqueueAudioDirect(buffer: ByteBuffer, length: Int)

    /**
     * Cleans up all C++ resources and stops inference.
     */
    external fun destroyEngine()
}
