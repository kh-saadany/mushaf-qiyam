## 2026-06-29T17:24:56Z
Update the compiled `architecture_study.md` report at `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\architecture_study.md` to resolve all critical vulnerabilities, bugs, and gaps identified by the Reviewer and Challenger.

Please read the full review and challenge reports for context:
- Review report: `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_reviewer_m3_1\review.md`
- Challenge report: `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_challenger_m3_1\challenge.md`

Specifically, you MUST implement the following changes in the markdown code blocks and sections:

1. **SPSC Queue Fixes**:
   - Add `static_assert(Capacity > 0, "SPSCQueue capacity must be greater than zero");` to the constructor.
   - Prevent cache line false sharing by padding `head_` and `tail_` using `alignas(64)`.
   - Prevent resource retention in `pop(T& val)` by using `val = std::move(ring_buffer_[current_tail]);` and resetting the slot `ring_buffer_[current_tail] = T();`.
   - Implement `bool empty() const { return head_.load(std::memory_order_relaxed) == tail_.load(std::memory_order_relaxed); }` to allow checking queue status.
   - Round the queue capacity to the next power of two (e.g., `262144` instead of `160000`) and replace the modulo operator `% Capacity` with bitwise AND `& (Capacity - 1)`.

2. **Inference Loop & Concurrency Fixes**:
   - Fix the spurious/lost wakeup race condition. In `inferenceLoop`, use a condition variable wait with a predicate: `cv_.wait_for(lock, std::chrono::milliseconds(50), [this]() { return !sample_queue_.empty() || !is_listening_.load(); });`.
   - To prevent busy looping when data is trickling in, make sure the consumer thread only executes `performInference` if `inference_buffer.size() >= target_chunk_size` OR `!is_listening_.load()`. If the buffer is not full and the queue is empty, it must block on the condition variable.

3. **NEON Conversion & Buffer Safety**:
   - Validate `bytes_read` inside `enqueueFrame`: check if `bytes_read <= 0` or if the resulting sample count exceeds the direct buffer capacity.
   - Eliminate the hardcoded `1600` stack allocation in `enqueueFrame` to prevent truncation of larger frames. Instead, dynamically allocate `conversion_buffer_` (a `std::vector<float>`) and a `resample_buffer_` once inside the C++ class during `registerDirectBuffer` when the capacity is known, avoiding stack overflow and dynamic heap allocation in the hot loop.
   - Ensure vector alignment in the JNI loop.

4. **Resampling Integration**:
   - Add `#include <cmath>` and `#include <algorithm>` where appropriate.
   - Update JNI `nativeInitialize` or a new native method to accept the hardware `inputSampleRate`. Cached it as `input_sample_rate_` (with target sample rate being `16000`).
   - Integrate `resampleLinear` directly into the `enqueueFrame` path. If `input_sample_rate_ != 16000`, the converted floats must be resampled to 16kHz using `resampleLinear` (writing to the pre-allocated resample buffer) before pushing them to the `sample_queue_`.

5. **Lifecycle & Thread Safety**:
   - Add a check at the beginning of `initialize` to prevent re-initialization leaks (delete old global references and stop active threads if already initialized).
   - Use a mutex to protect thread state transitions in `startListening`, `stopListening`, and `release`.
   - Call `stopListening()` in the destructor `~SpeechRecognizerBridge()` to prevent std::terminate crashes.

6. **Disaster Prevention Section Updates**:
   - Add an explicit rule that the Kotlin caller MUST hold a strong reference to the Direct ByteBuffer Java object for the entire lifetime of the engine to prevent GC from reclaiming the memory and causing native dangling pointer crashes.

## 2026-06-29T17:25:31Z
Additional refinements required:

1. **JNI Global Reference Leak & Singleton Bottleneck**:
   - Do NOT use a file-level global pointer `g_bridge` for the native bridge.
   - Instead, design the Kotlin class to hold the C++ class pointer as a `private var nativeBridgePtr: Long = 0` field.
   - In C++, retrieve the pointer inside JNI functions using `env->GetLongField(thiz, fieldId)`. Store the resolved field ID once during `JNI_OnLoad`.
   - Update `nativeInitialize` to allocate the bridge:
     `jlong pointer = reinterpret_cast<jlong>(new SpeechRecognizerBridge());`
     and return/store it in Java. Update other native methods to fetch the instance from `nativeBridgePtr`.

2. **Dangling Pointer for Shared ByteBuffer**:
   - In `registerDirectBuffer`, obtain a global reference to the `directBuffer` object and store it in the C++ class:
     `direct_buffer_ref_ = env->NewGlobalRef(direct_buffer);`
   - In `release()`, delete the global reference:
     `env->DeleteGlobalRef(direct_buffer_ref_);`
     This ensures the JVM does not collect the shared buffer while the native engine is using it.

3. **Inference Loop Data Loss**:
   - In `inferenceLoop`, when `inference_buffer.size() >= target_chunk_size`, copy only `target_chunk_size` samples for inference and remove only that processed chunk from the vector using `inference_buffer.erase()`, keeping the excess samples. Do NOT use `clear()`.

4. **Linear Resampler Bounds Checking**:
   - In `resampleLinear`, clamp the indices to prevent out-of-bounds reads. For example, clamp `low` and `high` to `[0, inputLength - 1]`:
     `int low = std::min(static_cast<int>(std::floor(index)), inputLength - 1);`

5. **Stateful Resampling**:
   - Update `resampleLinear` (or the class design) to be stateful. Mention that in continuous streaming, a stateful resampler must preserve the fractional phase remainder (`resample_phase_remainder_`) across successive audio frame calls to prevent phase discontinuities and audio clicks at frame boundaries.

6. **Actual Thread Priority**:
   - In the C++ JNI code, include `<sys/resource.h>` and enact the priority inside the inference thread:
     `setpriority(PRIO_PROCESS, 0, -16);`

7. **VAD (Voice Activity Detection)**:
   - Add a brief design section about Voice Activity Detection (VAD) (e.g., using Silero VAD or Sherpa-onnx's built-in VAD) to save CPU/battery on low-end tablets by avoiding inference during silences/noise.

## 2026-06-29T17:26:28Z
Challenger B refinement:
- To avoid a data race on `direct_buffer_ptr_` between the Audio Capture Thread (calling `enqueueFrame`) and the UI Thread (calling `release()` which resets the pointer to nullptr), make this pointer atomic in the C++ class:
  `std::atomic<int16_t*> direct_buffer_ptr_{nullptr};`
- Ensure that accessing and modifying it uses atomic loads/stores (or standard std::atomic operations).
