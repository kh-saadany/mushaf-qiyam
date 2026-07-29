# Rigorous Pre-Execution Review & Requirements Draft (Updated)

## 1. Objective
Update the `architecture_study.md` report to fix all critical vulnerabilities, concurrency races, cache thrashing, memory safety issues, and singleton bottlenecks.

## 2. Engineering Analysis & Feasibility
- **Target File**: `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\architecture_study.md`
- **Context**: The changes are in JNI specifications, code blocks, and safety guidelines.
- **Constraints**:
  - Do not use global `g_bridge` pointer; store the C++ bridge pointer in Kotlin class field `nativeBridgePtr`.
  - Cache JNI field ID of `nativeBridgePtr` in `JNI_OnLoad`.
  - Prevent JVM GC of the Direct ByteBuffer by storing a global JNI reference `direct_buffer_ref_` in the C++ class, deleted on release.
  - SPSC Queue capacity must be a power of two (262144), using bitwise AND `& (Capacity - 1)` for indices.
  - Concurrency fixes (condition variable wait predicate, avoiding busy looping).
  - NEON Conversion buffer safety (dynamic vectors, bytes_read checks).
  - Stateful Resampling (tracking phase remainder across boundaries to avoid clicks, clamping indices, adding guards).
  - Set real thread priority to `-16` using `<sys/resource.h>` inside the inference loop.
  - Design section on VAD integration.

## 3. Acceptance Criteria
1. **Multi-Instance / JNI Pointer Safety**:
   - `g_bridge` is completely removed.
   - Kotlin class declares `private var nativeBridgePtr: Long = 0`.
   - C++ uses `env->GetLongField(thiz, native_bridge_ptr_fid)` and stores it once during `JNI_OnLoad`.
   - `nativeInitialize` creates a new instance and returns it as a `jlong`.
   - Java ByteBuffer has its global JNI reference managed via `env->NewGlobalRef` and `env->DeleteGlobalRef` in `registerDirectBuffer` and `release` / destructor.

2. **SPSC Queue Safety**:
   - Compile-time assertions `static_assert` for size and power-of-two.
   - Bitwise AND `& (Capacity - 1)` instead of `% Capacity`.
   - `alignas(64)` padding.
   - `std::move` and resetting slot to `T()`.
   - `empty()` method.

3. **Inference Loop & Concurrency**:
   - Wait predicate: `cv_.wait_for(lock, std::chrono::milliseconds(50), [this]() { return !sample_queue_.empty() || !is_listening_.load(); });`.
   - Inference loop processes `target_chunk_size` from `inference_buffer`, copies and erases `inference_buffer.erase(inference_buffer.begin(), inference_buffer.begin() + target_chunk_size)` rather than `clear()`, preserving trickle-in extra samples.
   - Thread priority set with `setpriority(PRIO_PROCESS, 0, -16)` under `<sys/resource.h>`.

4. **NEON Conversion & Buffer Safety**:
   - Validate `bytes_read` against capacity.
   - Remove stack `1600` buffer; size vectors dynamically at `registerDirectBuffer`.

5. **Stateful Resampling**:
   - `resampleLinear` is updated to be stateful. It tracks the fractional phase remainder across calls (`resample_phase_remainder_`).
   - Indices are clamped: `low = std::min(..., inputLength - 1)`.
   - Guards for input/output lengths are added.

6. **Lifecycle & Thread Safety**:
   - Re-initialization checks stop thread and delete refs.
   - Mutex protection for state transitions.
   - Destructor cleans up correctly.

7. **VAD Design Section**:
   - A dedicated section is added details Silero VAD or Sherpa-onnx VAD to avoid useless CPU cycles.

## 4. Execution Plan
- We will perform a single drop-in replacement of the relevant sections in `architecture_study.md` or rewrite the file with updated contents.
