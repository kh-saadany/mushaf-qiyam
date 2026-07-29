# BRIEFING — 2026-06-29T17:19:35Z

## Mission
Research offline Arabic speech recognition models and C++ libraries for low-end tablets under 500MB RAM.

## 🔒 My Identity
- Archetype: teamwork_preview_explorer
- Roles: explorer
- Working directory: c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1
- Original parent: 80f4688e-0c6a-48c9-b700-2d68584a8803
- Milestone: M1: Exploration & Research (Requirement R1)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- RAM requirement under ~500MB runtime footprint
- RTF (Real Time Factor) and latency must be comfortable for real-time offline speech recognition on 3-4GB tablets (typically CPU-only, low-end)
- Write report to analysis.md and handoff.md in working directory
- All user responses must be in RTL direction.

## Current Parent
- Conversation ID: 80f4688e-0c6a-48c9-b700-2d68584a8803
- Updated: 2026-06-29T17:19:35Z

## Investigation State
- **Explored paths**: Whisper ggml-tiny, Sherpa-onnx (ONNX Runtime), Vosk (Kaldi)
- **Key findings**: 
  - Sherpa-onnx with a quantized Zipformer-transducer model is the optimal engine.
  - Runtime RAM footprint for Sherpa-onnx is 90MB - 150MB (within the 500MB limit).
  - RTF is 0.05 - 0.15 with a latency of 30ms - 80ms (instantaneous feedback).
  - Whisper ggml-tiny has high latency (RTF 0.6 - 1.2, latency 500-2000ms) and suffers from hallucinations in classical Arabic.
  - Vosk has high build and compilation complexity on Android due to Kaldi.
- **Unexplored areas**: None.

## Key Decisions Made
- Recommend Sherpa-onnx with ONNX Runtime using int8 quantized Zipformer model and phrase boosting/hotwords.

## Artifact Index
- c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\ORIGINAL_REQUEST.md — Original request
- c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\BRIEFING.md — Briefing file
- c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\progress.md — Progress tracker
- c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\analysis.md — Comparative analysis report
- c:\Users\Khaled El_Saadany\Desktop\webDevelopment\antigravity\مصحف القيام\.agents\teamwork_preview_explorer_m1_1\handoff.md — Handoff report
