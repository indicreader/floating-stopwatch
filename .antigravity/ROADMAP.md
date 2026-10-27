# Product Roadmap & Feature Pipeline

## Status Legend
- 📋 [BACKLOG] — Captured idea, awaiting full specification
- 📐 [READY] — Fully specified, ready for implementation
- 🚧 [IN PROGRESS] — Currently under active development
- ✅ [COMPLETED] — Shipped, tested, and protected against regressions

---

## 🚀 Active Pipeline (Priority Ranked)

| ID | Feature Name | Layer | Priority | Status |
| :--- | :--- | :--- | :---: | :---: |
| **FEAT-01** | Live Average Time Per Question | Overlay / Analytics | P0 | 📐 [READY] |
| **FEAT-02** | "Mark for Review" & Confidence Tagging | Input / Data | P1 | 📐 [READY] |
| **FEAT-03** | Floating Stylus Scratchpad | Overlay / Canvas | P1 | 📐 [READY] |
| **FEAT-04** | Dynamic Target Pace Color HUD | UI / Visual Cue | P2 | 📋 [BACKLOG] |
| **FEAT-05** | Hardware Volume Key Controls | Input / Service | P2 | 📋 [BACKLOG] |
| **FEAT-06** | 4-Quadrant Speed-Accuracy Matrix | Analytics / Export | P3 | 📋 [BACKLOG] |
| **FEAT-07** | Distraction-Free Stealth Ghost Mode | Overlay / UI | P3 | 📋 [BACKLOG] |
| **FEAT-08** | In-App GitHub Self-Updater | Settings / Network | P1 | 📐 [READY] |
| **FEAT-09** | Auto-Resume & Question Offset Prefetching | Session / Storage | P0 | ✅ [COMPLETED] |
| **FEAT-10** | Overlay UI Polish — Relocate Avg Badge & Remove Target | Overlay / UI | P0 | ✅ [COMPLETED] |
| **FIX-02** | Fix Overlay Launch Touch Lag & Dropped Pointer Events | Overlay / Window | P0 | ✅ [COMPLETED] |
| **FEAT-11** | Full-Screen Desk Mode for Physical Books & Hardcopy Drills | Full-Screen / Activity | P1 | ✅ [COMPLETED] |

---

## 📦 Shipped & Completed (Regression Guard — DO NOT BREAK)

| ID | Change / Feature | Completion Date | Key Guardrails |
| :--- | :--- | :---: | :--- |
| **REFACTOR-01** | Deprecate Simple & Wall Clocks | 2026-10 | Strictly retain only Stopwatch and Countdown modes; never reintroduce wall clock logic. |
| **FEAT-00** | Countdown ABCD + Skip Parity | 2026-10 | Countdown must maintain full response logging, split recording, and timeout states identical to Stopwatch. |
| **FIX-01** | Isolated Overlay Drag Handle | 2026-10 | Drag handler strictly attached to header handle; touch inputs and edit fields must never drag window. |
| **FEAT-09** | Auto-Resume & Question Offset Prefetching | 2026-10 | Automatically prefetch starting question offset across Stopwatch and Countdown drills; uncompleted/cancelled sessions (0 questions) must never corrupt or increment offset. |
| **FEAT-10** | Overlay UI Polish — Relocate Avg Badge & Remove Target | 2026-10 | Sub-header displays strictly Active QXX and split time; Avg badge remains in 5th column slot with disabled touch input. |
| **FIX-02** | Fix Overlay Launch Touch Lag & Dropped Pointer Events | 2026-10 | Overlay WindowManager LayoutParams must maintain FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED to prevent surface input stalls. |
| **FEAT-11** | Full-Screen Desk Mode for Physical Books & Hardcopy Drills | 2026-10 | DeskModeActivity and Floating overlay share single background service/engine; mode switching must minimize/restore overlay seamlessly without interrupting running timer. |



---

## 📋 Feature Specifications (Ready for Execution)

### FEAT-01: Live Average Time Per Question
- Formula: Total Elapsed Time / Answered Questions ($N$).
- If $N = 0$, display `Avg: --`. Format as `15s` or `m:ss`.
- Recalculate on every A/B/C/D or Skip press.

### FEAT-02: "Mark for Review" & Confidence Tagging
- Standard tap: normal response. Long-press (500ms): flags `isMarkedForReview = true` (purple badge, double haptic cue).
- Include review counts in session summary and CSV export.

### FEAT-03: Floating Stylus Scratchpad
- Pen icon on overlay opens translucent drawing canvas over underlying app.
- Palm rejection with `TOOL_TYPE_STYLUS` prioritization; auto-clear on question advance toggle.

### FEAT-08: In-App GitHub Self-Updater
- Settings > About > "Check for Updates" checks GitHub Releases API for latest APK.
- Downloads to cache and triggers Android package installer with FileProvider.

### FEAT-09: Persistent Question Offset & Auto-Resume Prefetching
- Persist next starting question offset in `SharedPreferences` (`KEY_PREFETCH_QUESTION_START`, default `1`).
- Auto-prefetch on new session initialization: if `KEY_AUTO_RESUME_ENABLED` is true, initialize active question counter to `KEY_PREFETCH_QUESTION_START`.
- Track session end: on "Finish", timer expiry, or explicit stop, extract highest completed question number ($Q_{\text{last}}$) and persist $Q_{\text{last}} + 1$.
- Cancelled/aborted sessions (0 questions answered/skipped) preserve existing offset without incrementing or corrupting.
- User controls: direct tap-to-edit on overlay question badge, "Reset Question Index to 1" quick action, and settings toggle.

### FEAT-10: Overlay UI Layout Polish — Relocate Avg Badge & Remove Target Label
- Sub-header cleanup: completely remove `(Target: 30s)` text and pill dividers from the sub-header row. Displays cleanly as `Active QXX • Split: MM:SS.S`.
- Relocate `Avg` badge: move `Avg: --` badge out of the sub-header row into the 5th column slot in the horizontal ABCD button row directly after button `D`.
- Styled as a rounded pill/card matching the height (44dp) of option buttons with neutral background/border (`#2A2A2A` / `#2E3D37`) and non-actionable passive touch behavior.
- Real-time updates: dynamic pace calculation updates this badge in place on every question advance across Stopwatch and Countdown modes.

### FIX-02: Fix Overlay Launch Touch Lag & Dropped Pointer Events
- Update `WindowManager.LayoutParams` flags to incorporate `FLAG_LAYOUT_IN_SCREEN`, `FLAG_LAYOUT_NO_LIMITS`, and `FLAG_HARDWARE_ACCELERATED` alongside `FLAG_NOT_FOCUSABLE`.
- Ensures immediate hardware-accelerated surface rendering and instant pointer event routing to the overlay window upon launch, eliminating initial dropped taps on buttons and the drag handle.

### FEAT-11: Full-Screen Desk Mode for Physical Books & Hardcopy Drills
- Dedicated full-screen Activity (`DeskModeActivity`) designed for phone-on-desk/stand usage when practicing from physical books or hardcopy test papers.
- Large glanceable timers, active question display, and oversized touch targets for A/B/C/D, Skip, and Pause.
- Shared engine architecture: binds to the running timer service/shared session manager ensuring 100% parity with floating overlay (split logging, auto-resume, pace calculation, CSV export).
- Seamless mode switching: toggle between floating overlay and full-screen Desk Mode without losing session progress or interrupting active timing.
- Keeps screen on during practice sessions with wake-lock support.
