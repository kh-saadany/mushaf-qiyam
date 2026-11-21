# Dual-Agent Collaboration Contract: Mushaf Qiyam (مصحف القيام)

## 1. Architectural Model & Role Split
This project is jointly developed by two AI agents synchronized through GitHub:

### Agent A: Google AI Studio (Architect & High-Level Reviewer)
- **Role:** System Architecture, Algorithmic Design & High-Level Reviewer.
- **Primary Responsibilities:**
  - System architecture, component design, schema definitions, and algorithmic decisions (ASR, dynamic programming, Compose UI architecture).
  - Deep code reviews, complex logic debugging, and refactoring strategies.
- **Boundaries & Prohibitions:**
  - Must NOT assume local shell/terminal outputs or environment variables.
  - Must NOT mandate blind local runtime changes without verified feedback from Antigravity.

### Agent B: Google Antigravity (Executive Engineer & Local Operator)
- **Role:** Autonomous local execution and environment management.
- **Primary Responsibilities:**
  - Running local builds (Gradle), profiling, unit tests, and environment management.
  - Fixing local runtime errors, compilation issues, and Android SDK/NDK failures.
  - Performing Git operations (branching, committing, staging) adhering to build protocols.
- **Boundaries & Prohibitions:**
  - Must NOT alter core architecture or design patterns unilaterally without Architect approval.
  - Must NOT commit or push without following the security and handoff protocols.

---

## 2. Git Synchronization Workflow
- **Single Source of Truth:** GitHub repository (https://github.com/kh-saadany/mushaf-qiyam).
- **Working Branch:** Active development branch is `v0.8.0-dev`. Direct pushes to `main` are restricted.
- **Pull Before Edit:** Always fetch and pull the latest commits before beginning any task to prevent merge conflicts:
  ```bash
  git pull origin v0.8.0-dev
  ```

---

## 3. Mandatory Handoff Protocol
Every interaction cycle between the two agents MUST conclude with a standardized, copy-ready markdown block titled `[HANDOFF PROMPT]`.

```markdown
### [HANDOFF PROMPT]
#### 1. Completed:
- Files created/modified: [List files with brief note of changes]
- Commit Hash / Branch: [Commit hash if committed, current branch]

#### 2. Current State:
- Build & Test Status: [PASS / FAIL / NOT RUN with summary]
- Blocking Issues / Runtime Errors: [Specific errors, logs, or "None"]

#### 3. Next Action:
- Target Agent: [Google AI Studio (Architect) OR Google Antigravity (Executive)]
- Specific Task: [Clear, actionable next step for the receiving agent]
```
