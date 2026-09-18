# Project brief: Storm Root — ambient screen-time nudger

> **App name: Storm Root** — tagline: "Grows with every scroll."
> No exact match found on Google Play or the App Store during a preliminary search; a similarly-named iOS app ("Roots: Screen Time Control") exists in the same category with a different mechanic (blocking vs. overlays), which is a minor brand-adjacency risk worth being aware of, not a hard conflict. Run a full domain/trademark check before final commitment.

## 1. Concept

This app is inspired by **Stop Swarm** (com.stopswarm.app on Google Play), which reduces doomscrolling not by blocking apps, but by layering a visual overlay that gets progressively harder to ignore the longer you exceed a self-set limit.

This project reuses that core mechanic across three independent modules:

1. **Doomscroll Weather** — screen-time-based. A "storm" overlay builds on social/video apps the longer you're in them past your daily limit.
2. **Bedtime Drift** — clock-based. The screen slowly desaturates, warms, and blurs the later you stay awake past your set bedtime.
3. **Sitting Roots** — movement-based. Vine/root graphics creep up from the screen edges the longer you've been sedentary; clears when you move.

**Design philosophy (non-negotiable, carried over from Stop Swarm):**
- Never fully block the screen or the app underneath — friction, not force.
- No accounts, no login.
- All data stored locally on-device only.
- No analytics or tracking SDKs beyond the ad SDK itself.
- User can always pause/snooze the effect manually.
- Daily auto-reset at a fixed hour (default 5:00 AM).

## 2. Tech stack — native Android

**Decision: build fully native (Kotlin) with the traditional XML View system.** The core mechanic of this app — a persistent system overlay with real-time animation, running for hours alongside a background service — needs to stay as lightweight as possible in both memory and APK size. XML layouts + custom `View`s have lower per-view overhead and a smaller runtime footprint than declarative UI toolkits, which matters for something that runs continuously in the background for hours. Native XML also avoids any cross-framework bridge or extra engine instance in the overlay path — the overlay is just a `View` added via `WindowManager`, in the same process as the rest of the app.

- **Language:** Kotlin
- **UI toolkit:** XML layouts (`res/layout/*.xml`) inflated via `LayoutInflater`, with `ViewBinding` enabled for type-safe view references. Overlay content is a custom `View` (or a small `ViewGroup` of standard widgets) added directly via `WindowManager`.
- **Animation:** `ValueAnimator` / `ObjectAnimator` for property animation, custom `onDraw(Canvas)` for procedural effects (rain lines, growing root paths).
- **Min SDK:** API 26 (Android 8.0), to support `TYPE_APPLICATION_OVERLAY` reliably. Target API 34+.
- **Architecture:** MVVM — `ViewModel` + `StateFlow` per module, standard Android Architecture Components.
- **Local storage:** `DataStore` (Preferences) for settings; `Room` if structured history/stats are needed later.
- **Background execution:** a single long-running **foreground service** hosting the overlay + polling logic, with a persistent low-priority notification (required for the service to survive Doze mode and manufacturer battery killers on Xiaomi/Samsung/etc.). `WorkManager` only for lightweight periodic tasks that don't need to run continuously (e.g. daily reset).
- **Permissions:**
  - `PACKAGE_USAGE_STATS` (Usage Access) — required for Doomscroll Weather only, via `UsageStatsManager`
  - `SYSTEM_ALERT_WINDOW` (draw over other apps) — required for all three modules' overlay rendering
  - `ACTIVITY_RECOGNITION` (Android 10+) — required for Sitting Roots
  - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` (or the closest applicable foreground service type per current Play policy — verify at build time) for the persistent service

### Key native APIs to use directly
| Purpose | API |
|---|---|
| Per-app usage stats | `UsageStatsManager` |
| Overlay window | `WindowManager` + `TYPE_APPLICATION_OVERLAY`, content hosted in an inflated XML layout or custom `View` |
| Blur / warm-shift effect | `RenderEffect` (API 31+), with a `ColorMatrixColorFilter` fallback for API 26-30 |
| Step/motion detection | `SensorManager` (`TYPE_STEP_DETECTOR` / `TYPE_STEP_COUNTER`) or Activity Recognition API |
| Local notifications | `NotificationManager` (also needed for the required foreground-service notification) |
| Ads | AdMob Android SDK (`com.google.android.gms:play-services-ads`) |
| Local storage | Jetpack `DataStore` |
| Background scheduling | `WorkManager` (daily reset only, not for the continuous overlay logic, which lives in the foreground service) |

## 3. Module specs

### 3.1 Doomscroll Weather
**Trigger:** User selects target apps (e.g. Instagram, TikTok) and sets a daily time limit per app or globally.
**Behavior:**
- 0-100% of limit: no overlay.
- 100-130% of limit: light rain effect, low opacity, animated falling lines.
- 130-160%: fog/haze increases, rain intensifies.
- 160%+: full "storm", highest opacity, optional subtle thunder-flash effect (non-flashing-hazard safe).
- Overlay clears instantly when the user leaves the targeted app.
- Resets daily at 5:00 AM local time.
**Settings exposed to user:** intensity (Low/Normal/High), theme (rain/fog/snow/thunder), per-app target list, daily reset time.

### 3.2 Bedtime Drift
**Trigger:** Time-of-day only, no usage tracking required. User sets a bedtime and a drift window (default 90 min).
**Behavior:**
- Stage 1 (0-30 min past bedtime): slight warm color-temperature shift + faint edge blur (`RenderEffect.createBlurEffect` at low radius).
- Stage 2 (30-60 min): blur radius increases, contrast/desaturation drops via `ColorMatrix`.
- Stage 3 (60-90 min): strong blur/vignette, slow pulse animation (`ValueAnimator` with `REVERSE` repeat mode).
- Overlay caps at Stage 3, never fully opaque.
- One-tap "snooze" override, capped at N uses/week (default 3), so it can't become a permanent bypass.
- Resets automatically at the user's wake time or a fixed hour.
**Settings exposed to user:** bedtime, drift window length, intensity, theme (fog/dim candle/film grain/heavy eyelids), weekday vs weekend schedule, exempt apps (calls, alarms, messaging always exempt by default).

### 3.3 Sitting Roots
**Trigger:** Step counter / accelerometer detects no meaningful movement for a set threshold (default 45 min).
**Behavior:**
- Root/vine graphics (drawn with a custom `View`'s `onDraw(Canvas)` + animated `Path` progress via `PathMeasure`) animate growing from the bottom (and optionally side) edges of the screen, increasing in coverage the longer sedentary time continues.
- Clears (roots retreat) once a movement threshold is detected (e.g. 20+ steps or 2+ minutes of continuous motion).
**Settings exposed to user:** sitting threshold before roots start, growth speed, target coverage cap (never covers full screen), reminder notification toggle.

## 4. Screens (for the agent to scaffold)

1. **Onboarding** - permission requests (Usage Access, Overlay, Activity Recognition) explained one at a time, each with a plain-language reason before the system dialog.
2. **Home / dashboard** - toggle each module on/off, quick stats (today's screen time, current bedtime countdown, time since last movement).
3. **Module setup screens** (x3) - one per module, matching the settings listed in section 3.
4. **Live overlay** - not a traditional "screen," but the custom `View`/layout rendered by the foreground service; must be tested at each intensity stage per module, and specifically for perf/battery over multi-hour sessions.
5. **Stats/history** (optional v1.1) - simple charts of daily screen time, bedtime adherence, sitting streaks. Also the best placement for banner/native ads (see section 5).
6. **Settings** - daily reset time, notification preferences, snooze/override limits, theme picker.

## 5. Ads integration (AdMob, native Android SDK)

**Placement rules, keep ads non-intrusive, consistent with the app's calm positioning:**
- **Rewarded ad** - offer as the mechanism for extra snoozes/overrides (e.g. "Watch an ad for one more snooze today"). This is the only ad type that should ever touch the core interaction loop.
- **Banner or native ad** - stats/history screen only. Never on the dashboard, never inside or near the overlay.
- **No interstitials.** Full-screen ad interruptions contradict the app's own value proposition (friction-free, non-intrusive).
- Ship a free tier with ads as described above, and an optional one-time or subscription unlock for: additional themes, per-app scheduling, ad removal, and detailed stats.

## 6. Suggested project structure

```
app/src/main/java/com/example/app/
  MainActivity.kt
  App.kt
  di/                          # Hilt or manual DI setup
  core/
    permissions/
    datastore/
    overlay/
      OverlayService.kt        # the single foreground service hosting overlay + polling
      OverlayWindowController.kt
  modules/
    doomscrollweather/
      DoomscrollOverlayView.kt      # custom View for this module's overlay
      DoomscrollSettingsActivity.kt
      DoomscrollViewModel.kt
    bedtimedrift/
      BedtimeOverlayView.kt
      BedtimeSettingsActivity.kt
      BedtimeViewModel.kt
    sittingroots/
      RootsOverlayView.kt
      RootsSettingsActivity.kt
      RootsViewModel.kt
  screens/
    onboarding/
    home/
    stats/
    settings/
  ads/
    AdManager.kt
res/
  layout/
    activity_home.xml
    activity_onboarding.xml
    activity_settings.xml
    activity_stats.xml
    overlay_doomscroll.xml
    overlay_bedtime.xml
    overlay_roots.xml
```

**Key architectural note:** all three modules share one `OverlayService` and one `WindowManager`-attached overlay window, don't spin up a separate foreground service or overlay window per module. The service holds a reference to whichever module(s) are currently active and layers their custom `View`s inside a single root `FrameLayout` (e.g. Bedtime Drift's blur layer underneath, Doomscroll Weather's rain on top) rather than stacking multiple independent overlay windows, which would multiply memory cost and complicate z-ordering.

## 7. MVP scope (build order)

1. Core overlay pipeline: `SYSTEM_ALERT_WINDOW` permission flow + foreground service + a single inflated XML overlay layout with a trivial `ValueAnimator` animation. Get this rock-solid (including surviving Doze mode / app-switching / device reboot) before building any module logic.
2. Bedtime Drift (simplest: time-of-day trigger only, no usage-stats dependency).
3. Doomscroll Weather (adds `UsageStatsManager` polling).
4. Sitting Roots (adds Activity Recognition / sensor integration).
5. Settings + onboarding flow.
6. AdMob integration (rewarded ad for snoozes, banner on stats screen).
7. Stats/history screen.

## 8. Non-goals for v1
- No account system or cloud sync.
- No iOS build (this app's core mechanic, system overlay + usage stats, doesn't have an iOS equivalent; iOS's Screen Time API is far more restrictive and doesn't allow third-party overlays).
- No bank/spending integration (dropped from earlier concept list, compliance overhead not justified for v1).
- No camera-based features (e.g. posture detection, dropped for v1, revisit only with clear consent flow and privacy review).

## 9. Known risk areas to test early
- **Doze mode / battery optimization**: manufacturer-specific killers (Xiaomi MIUI, Samsung, Huawei) are aggressive about killing background services regardless of foreground-service status. Test on at least one heavily-customized OEM skin, not just stock/Pixel.
- **Play Store policy on `SYSTEM_ALERT_WINDOW` and Usage Access**: both are sensitive permissions with declaration/review requirements. Read current Play Console policy before submission, this changes periodically.
- **Overlay + multi-window / split-screen**: verify the overlay behaves correctly when the target app is in split-screen or picture-in-picture mode.
