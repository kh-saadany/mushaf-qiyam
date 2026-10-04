package com.mushafqiyam

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FastConformerFbank:
 * Pure Kotlin Mel Filterbank and Spectrogram Extractor for NeMo FastConformer.
 * Matches librosa.filters.mel(sr=16000, n_fft=512, n_mels=80, fmin=0, fmax=8000, htk=false, norm="slaney")
 * with STFT (n_fft=512, hop_length=160, win_length=400, window=hann, center=true)
 * and per-feature normalization ((logmel - mean) / (std + 1e-5)).
 */
class FastConformerFbank {

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 512
        const val N_MELS = 80
        const val HOP_LENGTH = 160
        const val WIN_LENGTH = 400
        const val NUM_FFT_BINS = N_FFT / 2 + 1 // 257
        private const val EPS = 5.9604645e-8f // 2^-24
        private const val NORM_EPS = 1e-5f
    }

    private val bitRev = IntArray(N_FFT)
    private val cosTable = FloatArray(N_FFT / 2)
    private val sinTable = FloatArray(N_FFT / 2)
    private val melWeights = Array(N_MELS) { FloatArray(NUM_FFT_BINS) }
    private val hannWindow = FloatArray(WIN_LENGTH)

    init {
        // 1. Bit-reversal table for N_FFT = 512 (9 bits: 2^9 = 512)
        for (i in 0 until N_FFT) {
            var rev = 0
            var v = i
            for (b in 0 until 9) {
                rev = (rev shl 1) or (v and 1)
                v = v shr 1
            }
            bitRev[i] = rev
        }

        // 2. Twiddle factor lookup tables
        for (k in 0 until N_FFT / 2) {
            val angle = -2.0 * Math.PI * k / N_FFT
            cosTable[k] = cos(angle).toFloat()
            sinTable[k] = sin(angle).toFloat()
        }

        // 3. Periodic Hann window of length WIN_LENGTH (400)
        for (n in 0 until WIN_LENGTH) {
            hannWindow[n] = (0.5 * (1.0 - cos(2.0 * Math.PI * n / WIN_LENGTH))).toFloat()
        }

        // 4. Slaney Mel Filterbank weights
        initSlaneyMelWeights()
    }

    private fun initSlaneyMelWeights() {
        val fSp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = 15.0
        val logStep = Math.log(6.4) / 27.0

        fun hzToMel(hz: Double): Double {
            return if (hz >= minLogHz) {
                minLogMel + Math.log(hz / minLogHz) / logStep
            } else {
                hz / fSp
            }
        }

        fun melToHz(mel: Double): Double {
            return if (mel >= minLogMel) {
                minLogHz * exp(logStep * (mel - minLogMel))
            } else {
                fSp * mel
            }
        }

        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(8000.0)
        val melF = DoubleArray(N_MELS + 2) { i ->
            melToHz(minMel + i * (maxMel - minMel) / (N_MELS + 1))
        }

        val fftFreqs = DoubleArray(NUM_FFT_BINS) { k ->
            k * (SAMPLE_RATE / 2.0) / (NUM_FFT_BINS - 1)
        }

        for (i in 0 until N_MELS) {
            val lower = melF[i]
            val center = melF[i + 1]
            val upper = melF[i + 2]
            val enorm = (2.0 / (upper - lower)).toFloat()

            for (k in 0 until NUM_FFT_BINS) {
                val freq = fftFreqs[k]
                val upSlope = (freq - lower) / (center - lower)
                val downSlope = (upper - freq) / (upper - center)
                val w = max(0.0, min(upSlope, downSlope)).toFloat()
                melWeights[i][k] = w * enorm
            }
        }
    }

    // Preallocated buffers for zero-allocation streaming
    private var pre = FloatArray(28800)
    private var logmel = Array(N_MELS) { FloatArray(1 + 28800 / HOP_LENGTH) }
    private val frameBuf = FloatArray(N_FFT)
    private val re = FloatArray(N_FFT)
    private val im = FloatArray(N_FFT)
    private val power = FloatArray(NUM_FFT_BINS)

    /**
     * Extracts normalized log-mel spectrogram [80, numFrames] from raw audio.
     * Uses preallocated buffers to achieve zero allocation per inference frame.
     */
    fun computeFeatures(audio: FloatArray): Array<FloatArray> {
        if (audio.isEmpty()) return Array(N_MELS) { FloatArray(0) }

        val audioSize = audio.size
        if (pre.size < audioSize) {
            pre = FloatArray(audioSize)
        }
        val numFrames = 1 + audioSize / HOP_LENGTH
        if (logmel[0].size < numFrames) {
            logmel = Array(N_MELS) { FloatArray(numFrames) }
        }

        // Preemphasis: y[t] - 0.97 * y[t-1] (Zero allocation)
        pre[0] = audio[0]
        for (i in 1 until audioSize) {
            pre[i] = audio[i] - 0.97f * audio[i - 1]
        }

        for (t in 0 until numFrames) {
            frameBuf.fill(0f)
            // Librosa center-pad centering: window centered at t * HOP_LENGTH
            // win_length = 400 placed in 512 with left offset 56: (512 - 400) / 2 = 56
            for (n in 0 until WIN_LENGTH) {
                val idx = t * HOP_LENGTH + n - 200
                if (idx in 0 until audioSize) {
                    frameBuf[56 + n] = pre[idx] * hannWindow[n]
                }
            }

            // Radix-2 FFT: bit-reversal initial copy
            for (i in 0 until N_FFT) {
                re[i] = frameBuf[bitRev[i]]
            }
            im.fill(0f)

            var step = 1
            while (step < N_FFT) {
                val jump = step * 2
                val twiddleStep = N_FFT / jump
                for (group in 0 until N_FFT step jump) {
                    for (k in 0 until step) {
                        val pos = group + k
                        val c = cosTable[k * twiddleStep]
                        val s = sinTable[k * twiddleStep]

                        val xr = re[pos + step]
                        val xi = im[pos + step]
                        val tr = c * xr - s * xi
                        val ti = c * xi + s * xr

                        re[pos + step] = re[pos] - tr
                        im[pos + step] = im[pos] - ti
                        re[pos] = re[pos] + tr
                        im[pos] = im[pos] + ti
                    }
                }
                step = jump
            }

            // Power spectrum: |FFT|^2
            for (k in 0 until NUM_FFT_BINS) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }

            // Slaney Mel Filterbank dot product
            for (m in 0 until N_MELS) {
                var melEnergy = 0.0f
                val row = melWeights[m]
                for (k in 0 until NUM_FFT_BINS) {
                    melEnergy += row[k] * power[k]
                }
                logmel[m][t] = ln(melEnergy + EPS)
            }
        }

        // Per-feature normalization: ((logmel - mean) / (std + 1e-5))
        for (m in 0 until N_MELS) {
            val row = logmel[m]
            var sum = 0.0
            for (t in 0 until numFrames) {
                sum += row[t]
            }
            val mean = (sum / numFrames).toFloat()

            var varSum = 0.0
            for (t in 0 until numFrames) {
                val diff = row[t] - mean
                varSum += (diff * diff)
            }
            val std = (sqrt(varSum / numFrames) + NORM_EPS).toFloat()

            for (t in 0 until numFrames) {
                row[t] = (row[t] - mean) / std
            }
        }

        return logmel
    }
}
