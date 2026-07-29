# Disaster Mitigation Strategy & Code Guidelines Report

## 1. Comprehensive Plan to Prevent Software Disasters

### 1.1 Memory Leaks from Continuous Listening (JNI & C++ Memory Management)
Continuous speech recognition requires running the audio recording and model inference loop for long periods. Memory leaks on either side of the JNI boundary will eventually crash the application due to Out of Memory (OOM) errors or local reference table overflows (which on Android ART is limited to 512 entries per native frame).

#### 1.1.1 JNI Local and Global Reference Management
- **The Local Reference Table Overflow**: Inside the audio processing loop, calling JNI functions that return objects (e.g., arrays, strings, class objects) creates local references. If these are not deleted explicitly, they remain until the JNI boundary returns. To prevent this, every local reference created within loops must be immediately deleted.
- **Push/Pop Local Frames**: Wrapping local blocks with `PushLocalFrame` and `PopLocalFrame` ensures that all local references created inside are freed at once.
- **Global References**: References to Java/Kotlin callbacks or buffers that persist across multiple JNI invocations must be registered as Global References (`NewGlobalRef`). They must be tracked and freed via `DeleteGlobalRef` during the native cleanup lifecycle.

*C++ Example for JNI Reference Management:*
```cpp
JNIEXPORT void JNICALL
Java_com_mushafqiyam_audio_AudioEngine_processAudio(JNIEnv* env, jobject thiz, jobject byteBuffer, jint sizeInBytes) {
    // 1. Wrap in a local frame if multiple local references are created
    if (env->PushLocalFrame(16) < 0) {
        return; // Out of memory in JNI local frame
    }

    // Access the direct byte buffer (does not create a local reference)
    void* bufferAddress = env->GetDirectBufferAddress(byteBuffer);
    if (!bufferAddress) {
        env->PopLocalFrame(nullptr);
        return;
    }

    // Example of a temporary local ref (e.g., class lookup or string creation)
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID mid = env->GetMethodID(clazz, "onNativeFeedback", "(Ljava/lang/String;)V");
    
    jstring feedbackStr = env->NewStringUTF("Processing frame");
    env->CallVoidMethod(thiz, mid, feedbackStr);

    // Explicitly delete local refs if we don't rely solely on PopLocalFrame
    env->DeleteLocalRef(feedbackStr);
    env->DeleteLocalRef(clazz);

    // Pop local frame - cleans up any remaining local references created in this block
    env->PopLocalFrame(nullptr);
}
```

#### 1.1.2 C++ Memory Deallocation (RAII)
- **Avoid Raw Pointers**: Never use raw `new` and `delete` in the main audio path. Use `std::unique_ptr` and `std::shared_ptr` to manage the lifetime of the native inference session and model context.
- **Lifecycle Cleanup**: Define a clear native release function (`nativeDestroy` or `nativeRelease`) invoked by the Kotlin side when the service/activity is stopped. This function must safely release ONNX Runtime environments, sessions, and allocated C++ queues.

*C++ RAII Memory Management Example:*
```cpp
#include <memory>
#include <mutex>
#include "onnxruntime_cxx_api.h"

class InferenceEngine {
private:
    std::unique_ptr<Ort::Env> env;
    std::unique_ptr<Ort::Session> session;
    std::mutex engineMutex;

public:
    InferenceEngine() = default;
    
    bool initialize(const char* modelPath) {
        std::lock_guard<std::mutex> lock(engineMutex);
        try {
            env = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "MushafQiyamEngine");
            Ort::SessionOptions sessionOptions;
            sessionOptions.SetIntraOpNumThreads(2); // optimized for low-end CPUs
            session = std::make_unique<Ort::Session>(*env, modelPath, sessionOptions);
            return true;
        } catch (const std::exception& e) {
            // Log error
            return false;
        }
    }

    void release() {
        std::lock_guard<std::mutex> lock(engineMutex);
        session.reset();
        env.reset();
    }
    
    ~InferenceEngine() {
        release();
    }
};
```

---

### 1.2 Microphone Resource Locking (AudioRecord Lifecycle)
If the microphone is not released properly, it remains locked by our process, preventing other apps from accessing it and causing background termination by the Android OS.

#### 1.2.1 AudioRecord Lifecycle State Machine
- **Lifecycle Mapping**: `AudioRecord` must be initialized on start and completely released when the app goes to the background (unless a foreground service is active) or when listening is stopped.
- **Error-Resilient Recording Loop**: Wrapping the recording loop in a `try-catch-finally` block is mandatory to guarantee release even if exceptions (like buffer overflows or JNI errors) occur.

```kotlin
class AudioCaptureService : Service() {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingThread: Thread? = null

    fun startRecording(sampleRate: Int, bufferSize: Int) {
        if (isRecording) return
        
        // Initialize AudioRecord
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        isRecording = true
        recordingThread = Thread {
            runRecordingLoop()
        }.apply { start() }
    }

    private fun runRecordingLoop() {
        val record = audioRecord ?: return
        val directBuffer = ByteBuffer.allocateDirect(4096) // Pre-allocated Direct Buffer
        
        try {
            record.startRecording()
            while (isRecording) {
                val bytesRead = record.read(directBuffer, directBuffer.capacity())
                if (bytesRead > 0) {
                    // Send to C++ native engine via direct ByteBuffer
                    nativeWriteBuffer(directBuffer, bytesRead)
                } else if (bytesRead < 0) {
                    Log.e("AudioCapture", "Error reading audio data: $bytesRead")
                    break;
                }
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Error in recording loop", e)
        } finally {
            cleanupAudioResources()
        }
    }

    @Synchronized
    fun stopRecording() {
        isRecording = false
        recordingThread?.join()
        recordingThread = null
    }

    @Synchronized
    private fun cleanupAudioResources() {
        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e("AudioCapture", "Failed to release AudioRecord", e)
        } finally {
            audioRecord = null
        }
    }
}
```

#### 1.2.2 Audio Focus and System Notifications
1. **Audio Focus Listener**: Request focus using `AudioManager.requestAudioFocus` with `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. Listen for `OnAudioFocusChangeListener` updates. If `AUDIOFOCUS_LOSS` occurs, immediately stop recording and release resources.
2. **Foreground Service Requirement**: On Android 9 (API 28) and higher, background apps cannot access the microphone. Therefore, continuous recording MUST run inside a **Foreground Service** with the `android:foregroundServiceType="microphone"` attribute declared in the `AndroidManifest.xml`, showing a persistent notification to the user.

---

### 1.3 Audio Format Mismatches & Conversions
Most models (Whisper/Sherpa-onnx) expect **16kHz, mono, Float32 PCM** (-1.0 to 1.0) audio format. Android audio hardware defaults to recording at **44.1kHz or 48kHz, mono or stereo, Int16 PCM**.

#### 1.3.1 Zero-Copy via Direct ByteBuffers
To avoid GC churn and buffer copying overhead:
1. Allocate a direct `java.nio.ByteBuffer` in Kotlin using `ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder())`.
2. Pass the Direct Buffer to JNI once during initialization or on every frame.
3. Access the raw memory pointer in C++ using `env->GetDirectBufferAddress(buffer)`. This retrieves a direct pointer to the underlying buffer without JNI array copying overhead.

#### 1.3.2 Format Conversion: Int16 to Float32
Since the microphone records in 16-bit signed integer (short) PCM, and the AI engine expects Float32 normalized values, perform this conversion in C++ (where vectorization/SIMD can optimize it) to keep Kotlin code allocation-free.

*C++ Int16 to Float32 Normalization:*
```cpp
void convertInt16ToFloat32(const int16_t* src, float* dst, int sampleCount) {
    constexpr float normalizationFactor = 1.0f / 32768.0f;
    for (int i = 0; i < sampleCount; ++i) {
        dst[i] = static_cast<float>(src[i]) * normalizationFactor;
    }
}
```

#### 1.3.3 Resampling (Sample Rate Conversion)
If the device does not support recording at 16kHz natively:
- **Query Support**: Use `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` or `AudioRecord.getMinBufferSize` to check native capability.
- **Native Resampling**: Implement resampler logic in C++ (e.g., using Linear Interpolation or a lightweight band-limited resampler) rather than JVM.
- **Linear Interpolation Resampling C++ Example (e.g., from 48kHz to 16kHz):**
```cpp
void resampleLinear(const float* input, int inputLength, float* output, int outputLength) {
    float ratio = static_cast<float>(inputLength) / outputLength;
    for (int i = 0; i < outputLength; ++i) {
        float index = i * ratio;
        int low = static_cast<int>(floor(index));
        int high = std::min(low + 1, inputLength - 1);
        float weight = index - low;
        output[i] = (1.0f - weight) * input[low] + weight * input[high];
    }
}
```

---

## 2. Memory Management & Threading Guidelines

To ensure stable, long-running operation on low-end 3-4GB RAM Android tablets, the implementation team must strictly adhere to the following rules:

### 2.1 Memory Management Guidelines
1. **Zero Allocations in Processing Loops**: No allocations (`new`, `malloc`, Kotlin object creations like `ByteArray` or `FloatArray`) are allowed in the real-time audio capture loop or the native processing pipeline. All buffers must be pre-allocated during initialization.
2. **Direct ByteBuffers only**: Pass audio data across the JNI boundary exclusively using direct `ByteBuffer` objects. Never use JNI primitive array functions (e.g., `GetByteArrayElements`) as they copy memory and trigger GC cycles.
3. **Explicit JNI Reference Cleanup**: Every JNI local reference created in a native loop or callback must be deleted via `env->DeleteLocalRef(ref)` immediately. Wrap nested loops in `PushLocalFrame` / `PopLocalFrame`.
4. **C++ RAII Compliance**: Wrap all raw pointers (e.g., Whisper contexts, ONNX sessions) in smart pointers (`std::unique_ptr` or `std::shared_ptr`).
5. **Lifespan Binding**: Align native structures with Kotlin lifecycle. The Kotlin wrapper class must have a `close()` or `release()` method that triggers the native release functions.

### 2.2 Threading Guidelines
1. **Never Block Main (UI) Thread**: Under no circumstances should JNI functions, file operations, model loading, or audio recording run on the UI thread.
2. **Separate Audio Capture and Inference Threads**:
   - **Audio Capture Thread**: A dedicated high-priority background thread (e.g., `android.os.Process.THREAD_PRIORITY_AUDIO`) focused solely on fetching data from `AudioRecord` and copying it to the native engine.
   - **Inference Thread**: A dedicated worker thread that pops audio from the ring buffer and runs model inference. This prevents heavy inference work from blocking the audio recording stream.
3. **Thread-Safe Ring Buffer**: Use a thread-safe circular ring buffer (lock-free or using mutexes) in C++ to transfer audio samples from the Audio Capture Thread to the Inference Thread.
4. **JNI Thread Attachment Rules**:
   - Native threads created in C++ that need to call back into Kotlin must be attached to the Java VM using `JavaVM::AttachCurrentThread`.
   - Any attached native thread **MUST** be detached using `JavaVM::DetachCurrentThread` before it terminates to prevent OS-level thread handle and memory leaks.
5. **Low-End Thread Allocation**: Limit intra-op threads in ONNX Runtime / GGML to a maximum of 2. Excessive threads lead to high CPU context-switching overhead and battery drain.
