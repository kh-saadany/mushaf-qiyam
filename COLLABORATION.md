# Dual-Agent Collaboration Contract: Mushaf Qiyam (مصحف القيام)

## 1. Architectural Model & Role Split
This project is jointly developed by two AI agents synchronized through GitHub:

### Agent A: Google AI Studio (Architect & High-Level Reviewer)
- **Role:** System Architecture, Algorithmic Design & High-Level Reviewer.
- **Primary Responsibilities:**
  - System architecture, component design, schema definitions, and algorithmic decisions (ASR, dynamic programming, Compose UI architecture).
  - Deep code reviews, complex logic debugging, and refactoring strategies.
  - Writing directives and reviewing reports in `.collab/`.
- **Boundaries & Prohibitions:**
  - Must NOT assume local shell/terminal outputs or environment variables.
  - Must NOT mandate blind local runtime changes without verified feedback from Antigravity.

### Agent B: Google Antigravity (Executive Engineer & Local Operator)
- **Role:** Autonomous local execution and environment management.
- **Primary Responsibilities:**
  - Running local builds (Gradle), profiling, unit tests, and environment management.
  - Fixing local runtime errors, compilation issues, and Android SDK/NDK failures.
  - Performing Git operations (branching, committing, staging) adhering to build protocols.
  - Formulating agreed proposals for the Project Manager.
- **Boundaries & Prohibitions:**
  - Must NOT alter core architecture or design patterns unilaterally without Architect approval.
  - Must NOT commit or push without following the security and handoff protocols.

### The Project Manager (User)
- Owns all strategic decisions, priorities, and feature roadmaps.
- Reviews and approves or requests changes to consensus proposals formulated in `.collab/PROPOSALS.md`.
- Acts as supervisor, not a manual copy-paste intermediary.

---

## 2. Git Synchronization Workflow
- **Single Source of Truth:** GitHub repository (https://github.com/kh-saadany/mushaf-qiyam).
- **Working Branch:** Active development branch is `v0.8.0-dev`. Direct pushes to `main` are restricted.
- **Pull Before Edit:** Always fetch and pull the latest commits before beginning any task to prevent merge conflicts:
  ```bash
  git pull origin v0.8.0-dev
  ```

---

## 3. Asynchronous Git-Mediated Mailbox Protocol (`.collab/`)
Communication between Google AI Studio and Antigravity is mediated directly through the repository to eliminate manual copy-pasting:

1. **`.collab/DIRECTIVES.md`**: Maintained by **Google AI Studio**. Contains architectural specifications, blueprints, and specific tasks assigned to Antigravity.
2. **`.collab/REPORTS.md`**: Maintained by **Google Antigravity**. Contains local build/test results, memory profiles, hardware diagnostics, and execution feedback.
3. **`.collab/PROPOSALS.md`**: Maintained jointly to present consensus recommendations and roadmaps for final Project Manager approval before implementation.
4. **Draft Code & Exceptions**: When Google AI Studio authors complex code blocks directly, it commits them under `.collab/drafts/` and directs Antigravity in `DIRECTIVES.md` to integrate, compile, and verify them locally.

---

## 4. Mandatory Handoff Protocol
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
