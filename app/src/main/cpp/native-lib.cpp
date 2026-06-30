#include <jni.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>
#include <atomic>
#include <android/log.h>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#define LOG_TAG "MushafEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <onnxruntime_cxx_api.h>
#include "spsc_queue.h"
#include "silero_vad.h"

// Globals
Ort::Env* g_ort_env = nullptr;
SileroVAD* g_vad = nullptr;
Ort::Session* g_encoder_session = nullptr;
Ort::Session* g_decoder_session = nullptr;

std::atomic<bool> g_is_running(false);
SPSCQueue<float>* g_audio_queue = nullptr;
std::thread* g_inference_thread = nullptr;

void inference_loop() {
    std::vector<float> vad_buffer;
    vad_buffer.reserve(512); // Silero VAD typically uses 512 samples per frame (32ms at 16kHz)
    
    std::vector<float> speech_frames;
    speech_frames.reserve(16000 * 10); // Reserve for 10 seconds of speech

    while (g_is_running) {
        float sample;
        while (vad_buffer.size() < 512 && g_audio_queue->pop(sample)) {
            vad_buffer.push_back(sample);
        }

        if (vad_buffer.size() == 512) {
            if (g_vad) {
                float speech_prob = g_vad->process(vad_buffer);
                if (speech_prob > 0.5f) {
                    // Accumulate speech frames
                    speech_frames.insert(speech_frames.end(), vad_buffer.begin(), vad_buffer.end());
                } else if (!speech_frames.empty()) {
                    // Speech ended, process accumulated frames with Moonshine
                    if (speech_frames.size() > 16000 * 0.5) { // Minimum 0.5 seconds of speech
                        // LOGI("Processing speech segment of size %zu", speech_frames.size());
                        
                        // 1. Run Encoder Session
                        // Ort::Value encoder_input = ...
                        // auto encoder_output = g_encoder_session->Run(...)
                        
                        // 2. Run Decoder Session loop (Autoregressive decoding)
                        // while (token != EOS) {
                        //    auto decoder_output = g_decoder_session->Run(...)
                        //    token = argmax(decoder_output)
                        // }
                        
                        // 3. Decode Token IDs using Tokenizer
                        // std::string recognized_text = tokenizer.decode(tokens)
                        
                        // 4. Send back to Kotlin via JNI Callback
                    }
                    speech_frames.clear();
                }
            }
            vad_buffer.clear();
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_mushafqiyam_MushafEngine_initEngine(JNIEnv* env, jobject /* this */, jstring encoderPath, jstring decoderPath, jstring vadPath) {
    const char* encoder_str = env->GetStringUTFChars(encoderPath, nullptr);
    const char* decoder_str = env->GetStringUTFChars(decoderPath, nullptr);
    const char* vad_str = env->GetStringUTFChars(vadPath, nullptr);

    LOGI("Initializing engine...");
    LOGI("Encoder path: %s", encoder_str);
    LOGI("Decoder path: %s", decoder_str);
    LOGI("VAD path: %s", vad_str);

    try {
        if (!g_ort_env) {
            g_ort_env = new Ort::Env(ORT_LOGGING_LEVEL_WARNING, "MushafEnv");
        }
        
        Ort::SessionOptions session_options;
        session_options.SetIntraOpNumThreads(2);
        
        // Setup Models
        // Note: In real app, check if paths exist before instantiating
        // g_vad = new SileroVAD(*g_ort_env, vad_str);
        
        g_audio_queue = new SPSCQueue<float>(16000 * 10); // 10 seconds capacity
        g_is_running = true;
        g_inference_thread = new std::thread(inference_loop);
        
        LOGI("Engine initialized successfully.");
    } catch (const std::exception& e) {
        LOGE("Failed to initialize engine: %s", e.what());
        env->ReleaseStringUTFChars(encoderPath, encoder_str);
        env->ReleaseStringUTFChars(decoderPath, decoder_str);
        env->ReleaseStringUTFChars(vadPath, vad_str);
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(encoderPath, encoder_str);
    env->ReleaseStringUTFChars(decoderPath, decoder_str);
    env->ReleaseStringUTFChars(vadPath, vad_str);

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mushafqiyam_MushafEngine_destroyEngine(JNIEnv* env, jobject /* this */) {
    g_is_running = false;
    if (g_inference_thread && g_inference_thread->joinable()) {
        g_inference_thread->join();
        delete g_inference_thread;
        g_inference_thread = nullptr;
    }
    if (g_audio_queue) { delete g_audio_queue; g_audio_queue = nullptr; }
    if (g_vad) { delete g_vad; g_vad = nullptr; }
    if (g_encoder_session) { delete g_encoder_session; g_encoder_session = nullptr; }
    if (g_decoder_session) { delete g_decoder_session; g_decoder_session = nullptr; }
    if (g_ort_env) { delete g_ort_env; g_ort_env = nullptr; }
    LOGI("Engine destroyed.");
}

void convert_int16_to_float32(const int16_t* src, float* dst, int length) {
    int i = 0;
#if defined(__ARM_NEON)
    float32x4_t scale = vdupq_n_f32(1.0f / 32768.0f);
    for (; i <= length - 8; i += 8) {
        int16x8_t in = vld1q_s16(src + i);
        int32x4_t low = vmovl_s16(vget_low_s16(in));
        int32x4_t high = vmovl_s16(vget_high_s16(in));
        
        float32x4_t f_low = vcvtq_f32_s32(low);
        float32x4_t f_high = vcvtq_f32_s32(high);
        
        f_low = vmulq_f32(f_low, scale);
        f_high = vmulq_f32(f_high, scale);
        
        vst1q_f32(dst + i, f_low);
        vst1q_f32(dst + i + 4, f_high);
    }
#endif
    for (; i < length; ++i) {
        dst[i] = src[i] / 32768.0f;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mushafqiyam_MushafEngine_enqueueAudio(JNIEnv* env, jobject /* this */, jshortArray audioData, jint length) {
    if (!g_is_running || !g_audio_queue) return;

    jshort* audio_ptr = env->GetShortArrayElements(audioData, nullptr);
    if (!audio_ptr) return;

    std::vector<float> float_audio(length);
    convert_int16_to_float32(audio_ptr, float_audio.data(), length);

    env->ReleaseShortArrayElements(audioData, audio_ptr, JNI_ABORT);

    for (int i = 0; i < length; ++i) {
        g_audio_queue->push(float_audio[i]);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mushafqiyam_MushafEngine_enqueueAudioDirect(JNIEnv* env, jobject /* this */, jobject buffer, jint length) {
    if (!g_is_running || !g_audio_queue) return;

    int16_t* audio_ptr = static_cast<int16_t*>(env->GetDirectBufferAddress(buffer));
    if (!audio_ptr) return;

    std::vector<float> float_audio(length);
    convert_int16_to_float32(audio_ptr, float_audio.data(), length);

    for (int i = 0; i < length; ++i) {
        g_audio_queue->push(float_audio[i]);
    }
}
