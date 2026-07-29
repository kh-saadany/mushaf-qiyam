# Handoff Report: AI Model & C++ Library Selection (R1)

## 1. Observation
I investigated the workspace structure and verified the current codebase context. The following observations were made:
*   The project root contains standard native Android build configurations, including `settings.gradle.kts` and a subproject `app`.
*   The C++ native build definition exists at `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\app\src\main\cpp\CMakeLists.txt`, containing:
    ```cmake
    cmake_minimum_required(VERSION 3.22.1)
    project("mushafqiyam")
    add_library(
            ${CMAKE_PROJECT_NAME}
            SHARED
            native-lib.cpp)
    ```
*   The native JNI library boilerplate is defined at `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\app\src\main\cpp\native-lib.cpp`, with:
    ```cpp
    #include <jni.h>
    #include <string>

    extern "C" JNIEXPORT jstring JNICALL
    Java_com_mushafqiyam_MainActivity_stringFromJNI(
            JNIEnv* env,
            jobject /* this */) {
        std::string hello = "Hello from C++ (Mushaf Qiyam Harness)";
        return env->NewStringUTF(hello.c_str());
    }
    ```
*   The project has no existing implementation or build dependencies for any speech-to-text engines yet.
*   The system constraints require a runtime RAM footprint under **500MB** to run comfortably on low-end 3-4GB RAM Android tablets.

---

## 2. Logic Chain
1.  **Low-End CPU Limitations**: Low-end tablets typically use quad-core Cortex-A53 CPUs. Models with complex autoregressive decoding (such as Whisper's encoder-decoder transformer) require heavy attention computations over a sliding 30-second context window. This causes the Real-Time Factor (RTF) to hover around **0.60 - 1.20**, leading to UI lag and high latency. Thus, Whisper ggml-tiny is rejected.
2.  **Streaming vs. Chunk-Based**: For real-time Quran recitation tracking, the app must provide instantaneous feedback. Frame-by-frame streaming models (such as Zipformer-transducer or HMM-TDNN) process 30-60ms audio chunks in a state-preserving manner. Both Sherpa-onnx and Vosk satisfy this.
3.  **RAM Consumption**: To fit comfortably under the **500MB** limit, the engine must use quantized models and memory-efficient runtime environments.
    *   Quantized Whisper ggml-tiny uses ~120 - 180MB RAM.
    *   Quantized Sherpa-onnx Zipformer uses **90 - 150MB RAM** (highly optimized via ONNX Runtime).
    *   Vosk ar-mgb2 uses ~150 - 250MB RAM.
    *   All three fit the RAM constraint, with Sherpa-onnx being the most lightweight.
4.  **Quranic Classical Arabic Specifics**: General Modern Standard Arabic (MSA) models struggle with classical grammar and Tajweed pronunciation rules.
    *   Sherpa-onnx supports **contextual biasing (hotwords/phrase boosting)**, allowing developers to boost target Quranic words/verses dynamically.
    *   Vosk supports strict WFST grammar limits.
    *   Both are excellent for this application, but Sherpa-onnx offers greater phonetic robustness.
5.  **Android Integration and Maintenance**:
    *   Vosk relies on Kaldi, which is legacy C++98/11 and extremely difficult to compile and maintain for modern Android (ARM64/ARMv7).
    *   Sherpa-onnx is written in modern C++17, relies on ONNX Runtime (which has official pre-built Android AARs and CMake support), and is actively maintained by the next-gen Kaldi team.
6.  **Conclusion**: Sherpa-onnx (with an int8 quantized Zipformer-transducer model) is the optimal and safest choice for CPU-only, low-end offline Quranic speech recognition.

---

## 3. Caveats
*   The study assumes that the target tablets support standard ARM NEON vector instructions, which is true for virtually all modern ARM-based Android devices, but should be double-checked for extremely old or exotic architectures.
*   This study is purely theoretical and based on established performance benchmarks for these engines on low-end hardware. The actual memory footprint and RTF can vary depending on background OS tasks, CPU thermal throttling, and JNI buffer transfer efficiency (which is the subject of Requirement R2).
*   No physical hardware profiling was performed as the codebase lacks implementation; performance profiles are estimated using standard mobile benchmarking data for similar architectures.

---

## 4. Conclusion
The optimal model and library selection for this project is:
*   **Engine**: `Sherpa-onnx` utilizing the ONNX Runtime execution engine.
*   **Model**: Quantized int8 `Zipformer-transducer` Arabic model (e.g., `sherpa-onnx-zipformer-arabic-2024-05-27` or similar).
*   **Target Memory**: **90MB - 150MB RAM** (well below the 500MB limit).
*   **Latency Target**: **RTF of 0.05 - 0.15** and chunk latency of **30ms - 80ms** on CPU.
*   **Mitigation for Classical Recitation**: Use Sherpa-onnx's dynamic phrase boosting to load current page/surah verses as hotwords to prevent Tajweed and OOV failures.

---

## 5. Verification Method
To verify the completeness of this task:
1.  Inspect the analysis document at:
    `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\analysis.md`
2.  Verify that it contains:
    *   A comparison of at least two engines (Whisper ggml-tiny, Sherpa-onnx, and Vosk are compared).
    *   A detailed comparison matrix with RAM, model size, RTF, latency, and customization options.
    *   A clear, justified architectural recommendation.
3.  Since this is a read-only research study, there are no software tests or build commands to run for this subtask.
