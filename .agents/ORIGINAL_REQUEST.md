# Original User Request

## 2026-06-29T17:16:43Z

Conduct a comprehensive research study and architectural design for a Native Android (Kotlin + C++) application. The app performs offline, real-time speech recognition for Quran recitation on low-end tablets (3-4GB RAM). Produce a theoretical report (`architecture_study.md`) that outlines the optimal models, libraries, and a robust C++/Kotlin bridge to completely prevent performance disasters.

Working directory: `c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام`
Integrity mode: demo

## Requirements

### R1. AI Model & C++ Library Selection
Evaluate and recommend the most suitable offline speech recognition models (e.g., Whisper ggml-tiny, Sherpa-onnx) and C++ libraries tailored for Arabic language processing on low-end CPU-only Android devices. Do not write implementation code; focus on feasibility and constraints.

### R2. JNI Architecture Design
Design the architectural data flow between the Android audio hardware (Kotlin `AudioRecord`) and the native C++ inference engine. The design must ensure zero UI thread blocking and strictly avoid excessive Garbage Collection (GC) pauses associated with audio buffer allocations.

### R3. Disaster Mitigation Strategy
Formulate a clear execution plan to avoid future software disasters, such as memory leaks from continuous listening, microphone resource locking, or audio format mismatches (Int16 vs Float32).

## Acceptance Criteria

### Verification of Deliverables
- [ ] A final markdown report named `architecture_study.md` is created in the working directory.
- [ ] The report includes a section explicitly comparing at least two different on-device AI models for Arabic, with their estimated RAM requirements and latency profiles.
- [ ] The report contains a Mermaid diagram illustrating the continuous audio buffer data flow from Kotlin to the C++ inference engine.
- [ ] The report includes a dedicated "Disaster Prevention" section with explicit rules on memory management and threading for the upcoming implementation phase.
