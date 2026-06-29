package com.mushafqiyam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioCapture(private val engine: MushafEngine) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var captureThread: Thread? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    // Use a multiple of minBufferSize, approx 200ms of audio
    private val bufferSize = maxOf(minBufferSize, sampleRate * 2 * 1 / 5) // 16000 * 2 bytes/sample / 5

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.get()) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioCapture", "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            captureThread = thread(start = true, name = "AudioCaptureThread") {
                val directBuffer = ByteBuffer.allocateDirect(bufferSize)
                directBuffer.order(ByteOrder.nativeOrder())

                while (isRecording.get()) {
                    directBuffer.clear()
                    val bytesRead = audioRecord?.read(directBuffer, bufferSize) ?: 0
                    if (bytesRead > 0) {
                        // Pass direct buffer to C++ without copy overhead
                        val samplesRead = bytesRead / 2
                        engine.enqueueAudioDirect(directBuffer, samplesRead)
                    } else if (bytesRead < 0) {
                        Log.e("AudioCapture", "Error reading audio data: $bytesRead")
                    }
                }
            }
            Log.i("AudioCapture", "Audio capture started successfully")
        } catch (e: Exception) {
            Log.e("AudioCapture", "Exception starting audio capture: ${e.message}")
        }
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        
        try {
            captureThread?.join(1000)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
        captureThread = null
        Log.i("AudioCapture", "Audio capture stopped")
    }
}
