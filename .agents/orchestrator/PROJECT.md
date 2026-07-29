# Project: Mushaf Qiyam Offline Speech Recognition Architecture Study

## Architecture
This project is a theoretical research study and architectural design report for an offline, real-time speech recognition Android app written in Kotlin and C++ for low-end tablets (3-4GB RAM).
The target file to produce is `architecture_study.md` in the workspace root: `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\architecture_study.md`.

The architecture encompasses three main modules:
1. **Model Selection**: Comparison of Whisper (ggml-tiny) and Sherpa-onnx (sherpa-onnx-qnn / sherpa-onnx-k2) for Arabic speech recognition under strict RAM (<500MB runtime footprint) and latency (<200ms RTF) constraints.
2. **JNI Bridge**: Non-blocking audio buffer streaming from Android hardware layer (`AudioRecord` in Kotlin) to C++ inference engine using Direct ByteBuffers to avoid JNI transfer overhead and GC allocation churn.
3. **Disaster Mitigation**: Mitigation strategies for memory leaks, microphone locks, format mismatches, and lifecycle state management.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | M1: Exploration & Research | Explore R1 (model comparison), R2 (JNI data flow), and R3 (mitigation rules). | None | DONE |
| 2 | M2: Report Compilation | Implement and draft the `architecture_study.md` file incorporating all findings. | M1 | DONE |
| 3 | M3: Review & Auditing | Perform verification of the study against acceptance criteria via Reviewers, Challengers, and Forensic Auditor. | M2 | DONE |

## Interface Contracts
The report will specify the JNI method signatures for data exchange, including:
- Native audio initialization: `nativeInit(sampleRate: Int, bufferSize: Int)`
- Direct buffer registration: `nativeRegisterBuffer(buffer: ByteBuffer)`
- Native audio frame write: `nativeWriteBuffer(sizeInBytes: Int)`
- Model loading and inference setup: `nativeLoadModel(...)`
