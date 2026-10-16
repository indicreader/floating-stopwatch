# Practice Timer Overlay (Floating Stopwatch) ⏱️

An advanced, lightweight floating Android overlay timer specifically engineered for competitive exam aspirants, MCQ practice drills, mock tests, and speed training. Stays docked or floats smoothly on top of test portals, PDF readers, and e-learning apps.

---

## 🌟 Key Features

### 🎯 1. One-Tap MCQ Practice Tracking
- **Instant Response Row:** Dedicated `A`, `B`, `C`, `D`, and `Skip` buttons directly beneath the timer display for immediate, single-tap question logging.
- **Dynamic Split Timings:** Automatically tracks time elapsed per question and flags overtime pacing spikes when you exceed your target pace (e.g., `+12s over`).
- **Sequential Question Log:** Questions appear in exact chronological order (`Q01, Q02, ... Q98`) as they are answered.

### 🔍 2. Answer Verification & Performance Analytics
- **Post-Pause Review Drawer:** Pause the timer to expand the full question log drawer.
- **One-Touch Verification:** Tap `✔` (Correct) or `✖` (Incorrect) next to each question to mark results.
- **Live Accuracy Banner:** Dynamically calculates:
  - **Overall Accuracy %**
  - **Avg Correct Time**
  - **Avg Incorrect Time**
  - Total answered, correct, incorrect, and skipped counts.

### 📐 3. Fully Movable & Dynamically Resizable
- **Universal Dragging:** Drag anywhere from the header bar to reposition the overlay across your screen.
- **Corner Resize Grip:** Drag the bottom-right grip handle to smoothly resize the overlay card.
- **Two-Finger Pinch-to-Zoom:** Natural multi-touch gesture to scale the overlay width and typography proportionally.
- **Persistent Geometry:** Preserves your preferred overlay dimensions and positions across sessions.

### 🕒 4. Multi-Clock Presets
1. **Practice Stopwatch:** MCQ response logging, overtime alerts, and accuracy analytics.
2. **Countdown Timer:** Configurable interval presets (e.g., 5m, 10m, 15m, 30m).
3. **Simple Clock:** Clean, distraction-free floating stopwatch.
4. **Wall Clock:** Real-time local clock with instant 12-hour / 24-hour toggle.

### ⚙️ 5. Seamless Settings & Customization
- Easily accessible from both the floating timer header and the main app dashboard.
- Configurable target seconds per question (pacing threshold).
- Optional starting question number prompt (e.g., jump straight to question 51).
- Haptic feedback toggles for tactile response on every tap.
- Customizable font sizes and typography scaling.

---

## 🛠️ Tech Stack & Architecture

- **Language:** Kotlin (100% Modern Kotlin)
- **Minimum SDK:** Android 8.0 (API 26)
- **Target SDK:** Android 15 (API 35)
- **UI & Layouts:** Material Design 3 (Material You) semantic styling, ViewBinding
- **Overlay Engine:** Android `WindowManager` with `TYPE_APPLICATION_OVERLAY` and theme-aware context wrapper
- **Preferences:** AndroidX `PreferenceFragmentCompat`

---

## 🚀 Building & Installing

### Prerequisites
- JDK 17
- Android SDK with Platform 35

### Build Release APK
```bash
# Clone the repository
git clone https://github.com/indicreader/practice-timer-overlay.git
cd practice-timer-overlay

# Build release APK via Gradle Wrapper
./gradlew assembleRelease
```

Or on Windows:
```cmd
build-apk.bat
```
The compiled APK will be generated in `app/build/outputs/apk/release/` and copied to `release/`.

---

## 📜 Permissions

- **Display Over Other Apps (`SYSTEM_ALERT_WINDOW`):** Required to render the floating timer overlay over question papers, mock tests, and browsers.
- **Foreground Service (`FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`):** Keeps the timer tick handler accurate and prevents Android from killing the overlay background process.
- **Post Notifications (`POST_NOTIFICATIONS`):** Displays the persistent foreground service notification.
- **Vibration (`VIBRATE`):** Delivers haptic feedback on button presses.

---

## 📄 License & Attribution
Forked and modified from `com.furyform.floatingclock` for personal study, exam practice drills, and MCQ speed enhancement.
