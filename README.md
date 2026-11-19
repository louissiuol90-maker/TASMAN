# System Checker — Native Android (Kotlin)

Android-only, view-only task manager / system checker for Louis.
Built in Kotlin, packaged as an APK for sideloading. **Not listed on
Google Play, and not an Apple app.**

This is the proper native version. The earlier web demo could only
list its own internal tasks — a browser sandbox cannot see other
Android apps. This project is the one that can actually ask Android
for running processes, services and UsageStats, and poll AppOps for
mic / camera / message-log access.

## What it does

- **View-only process list** — all processes Android will show us,
  foreground and background, auto-refreshing every 2 seconds.
  Each row shows: app name, package name, foreground/background,
  status (Running / Idle / Sleeping / Unknown), CPU, memory,
  start / last-used time, and which API the row came from.
  Filters: All / Foreground / Background. No kill, stop, or change
  controls anywhere.
- **Window mode** — a tiny semi-transparent floating mini-bar
  ("● Checker") that sits on screen while the app runs in the
  background. Tap it to open the full app again; drag it anywhere.
  It is a translucent dark pill (~60% opaque, so you can see
  through it a bit, like the accessibility button) with fully
  opaque white text, kept alive by a foreground service.
  Picture-in-picture is **not** used — it looked like a video /
  YouTube window. Window mode needs the "Display over other apps"
  permission (Settings); the app explains if it is not granted.
  The activity is also `resizeableActivity=true`, so on tablets,
  Samsung DeX / pop-up view and other freeform environments it
  can be dragged and resized like a window.
- **Cleaner interface (v1.2)** — card-style sections with
  consistent padding, colour-coded headings (blue = Processes,
  red = Privacy Guard, teal = Cell tower log), equal-width
  48dp buttons with even spacing, a selected filter button
  that stands out in solid blue, and process rows as cards with
  a green Foreground / orange Background chip. Dark mode uses
  lighter accent colours so everything stays readable.
- **Privacy Guard** — alerts whenever the microphone or camera is
  accessed, or message logs (SMS / call log) are accessed, by a
  **non-trusted** app. Trusted = (1) system app, or (2) installed
  by the Play Store (`com.android.vending`). Everything else —
  sideloaded, unverified, web-page access — triggers a red banner,
  an alert-log entry and a silent notification. Trusted access is
  logged as allowed, with no alert.
- **Cell tower log** — records the serving cell tower when it
  changes: network type (2G / 3G / 4G / 5G), cell ID, TAC / LAC,
  signal in dBm, and operator name. Shown in the app and appended
  to `cell_tower_log.txt` in the app's private storage. No network
  upload, no root, public Android APIs only
  (`TelephonyManager` / `TelephonyCallback`, API 31+, with a
  `PhoneStateListener` fallback). Cell IDs need location
  permission (Android 10+). **Android gives no way to verify a
  tower is genuine** — a change (e.g. a sudden drop to 2G, or a
  new cell ID) is a clue to notice, not proof of a fake tower.
- **No audio.** No chime, beep, spoken line, MediaPlayer,
  SoundPool or TextToSpeech anywhere in the code. The alert
  notification channel has its sound explicitly set to null.

## Project layout

```
android-system-checker/
  settings.gradle.kts          rootProject.name = "System Checker"
  build.gradle.kts             AGP 8.5.2, Kotlin 2.0.20
  gradle.properties
  gradle/wrapper/gradle-wrapper.properties   Gradle 8.7
  app/build.gradle.kts         minSdk 26, targetSdk 35, compileSdk 35
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/arlo/systemchecker/
    MainActivity.kt            list, filters, summary, window-mode toggle
    ProcessInfo.kt             data model — null CPU/memory means "—"
    ProcessRepository.kt       ActivityManager + RunningServices + UsageStats
    PrivacyGuard.kt            AppOps polling, trusted-app rules, alerts
    PrivacyGuardService.kt     foreground service, polls every 5 s
    CellTowerLogger.kt         TelephonyCallback / PhoneStateListener, tower log
    FloatingBarService.kt      translucent floating mini-bar (Window mode), overlay
    ProcessAdapter.kt          RecyclerView adapter, foreground/background chips
  app/src/main/res/layout/
    activity_main.xml          card sections, filters, window buttons, alert log, list
    item_process.xml           one process row as a card
  app/src/main/res/values/     strings, themes, colors (light + night), icon background
  app/src/main/res/drawable/   chip_bg.xml, floating_bar_bg.xml (translucent pill)
```

## How to build the APK

You need a machine with Android Studio (or JDK 17 + Android SDK 35).
This Linux VM has **no Java, Gradle or Android SDK**, so the APK
could not be built here — see "What was verified" below.

1. Install Android Studio (Hedgehog or later), JDK 17, Android SDK
   Platform 35 and build-tools 35.
2. Open this folder in Android Studio, let it sync Gradle.
   If the Gradle wrapper scripts are missing, run
   **File > Sync Project with Gradle Files** — Android Studio
   generates `gradlew` / `gradlew.bat` from `gradle/wrapper/`.
3. Build a debug APK:
   ```
   ./gradlew assembleDebug
   ```
   Output:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```
4. For a release APK, create a signing key and run
   `./gradlew assembleRelease`, or use **Build > Generate Signed
   App Bundle / APK** in Android Studio.

## How to sideload / install it on Android

No Play Store listing is needed.

1. Copy `app-debug.apk` to the phone (USB, Drive, email to yourself,
   or `adb install app-debug.apk`).
2. On the phone: **Settings > Security > Install unknown apps** —
   allow the app you are installing from (Files / Chrome / etc).
   Or with USB debugging on: `adb install -r app-debug.apk`.
3. Open **System Checker**. It will ask for:
   - **Usage Access** — tap "Grant Usage Access" in the app, then
     **Settings > Apps > Special app access > Usage access >
     System Checker > Allow**. Without this, UsageStats is empty
     and the list is much shorter. The app shows a warning until
     this is granted.
   - **Notifications** (Android 13+) — for Privacy Guard alerts.
     These are silent by design.
4. Add it to the home screen like any installed app — long-press
   the icon, or drag it where you want it. No store needed.
5. Window mode: tap **Window mode** in the app for the floating
   mini-bar. First time, allow "Display over other apps" for
   System Checker in Settings when the app sends you there.
   On Samsung, long-press the app in Recents and choose
   **Open in pop-up view** for a true floating window.

## Permissions — what they do, and what they do NOT do

| Permission | Why |
|---|---|
| `PACKAGE_USAGE_STATS` | UsageStatsManager: which apps were used, foreground/background, last-used time. Special access, granted in Settings, not at install. |
| `QUERY_ALL_PACKAGES` | List installed packages and tell system apps apart. Needed for sideloading; Play Store would restrict this, which is one reason this app is sideload-only. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Privacy Guard service polling AppOps in the background. |
| `GET_APP_OPS_STATS` | AppOps history for Privacy Guard. Signature\|privileged — declaring it does not grant it; see "Honest limits". |
| `POST_NOTIFICATIONS` | Privacy Guard alert notifications (silent). |
| `VIBRATE` | Haptic feedback for an alert only. No audio. |
| `READ_PHONE_STATE` | Cell tower log: network type / operator name. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Cell tower log: cell ID / TAC / LAC. Android 10+ hides cell identity without location permission. No location is stored, uploaded or shared. |
| `SYSTEM_ALERT_WINDOW` | Floating mini-bar (Window mode): draw the small translucent "Checker" bar over other apps. Granted in Settings ("Display over other apps"), not at install. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Also keeps the floating mini-bar service alive in the background. |

This app requests **no** `READ_SMS`, `READ_CALL_LOG`, `RECORD_AUDIO`
or `CAMERA` permission, and reads no messages, call logs, photos or
audio itself. It only asks AppOps whether *another* app has recently
used one of those ops.

## Honest limits — read this before trusting a row

This is the most important part of the project, and the app itself
says the same thing on screen.

1. **No complete process list on modern Android.** Android 10+
   restricts `getRunningAppProcesses()` to mainly the calling app,
   and per-process CPU / memory for other apps is not available to
   an ordinary app without system / root privileges. This app
   combines three legal sources — running processes, running
   services, and UsageStats for the last 24 hours — which is the
   widest view available without root. It is still not a kernel
   process list. A row that says "—" for CPU / memory means
   Android would not give us that number, not that the value is zero.
   Only our own process has real CPU / memory, read from our own
   `/proc/<pid>/stat` and `Debug.MemoryInfo`.
2. **Privacy Guard is polling, not interception, and AppOps
   history needs a system permission.** Android does not let an
   ordinary app intercept another app's mic/camera use as it
   happens. The service polls AppOps every 5 seconds. An access
   that starts and stops between polls can be missed, and AppOps
   times are coarse-grained. In addition, `getPackagesForOps()`
   requires `GET_APP_OPS_STATS`, a signature|privileged permission
   — declaring it in the manifest does not grant it. If Android
   denies it, the app catches the SecurityException, records it
   in the alert log instead of fabricating alerts, and keeps
   running. You can try granting it over ADB:
   `adb shell pm grant com.arlo.systemchecker android.permission.GET_APP_OPS_STATS`
   — that grant is not guaranteed on all devices. Android 12+'s
   own green mic/camera dot and Privacy Dashboard are the system's
   real-time indicators — use those alongside this app.
3. **"Google-endorsed developer" has no OS flag.** There is no
   Android API that says "this developer is Google-endorsed".
   The app uses the closest verifiable proxy: installed by the
   Play Store (`installer == com.android.vending`). A Play-installed
   app from a shady developer would still count as trusted by
   that rule, and a legitimate app sideloaded from its own site
   would count as non-trusted. The app says this in code comments.
4. **Message-log detection is SMS / call-log ops only.** It
   watches `READ_SMS` and `READ_CALL_LOG` AppOps. It cannot see
   in-app messages (WhatsApp, Messenger, etc.) or the content of
   any message.
5. **Window mode is a floating mini-bar, not PiP.**
   Picture-in-picture was removed — it looked like a video /
   YouTube window. The mini-bar needs "Display over other apps"
   permission; without it, the app says so instead of silently
   failing. True freeform floating windows are
   manufacturer-dependent; the app also keeps
   `resizeableActivity` for DeX / tablets / pop-up view where
   the OS supports it.
6. **No audio, ever.** If you hear anything on open, it is not
   from this app.
7. **Cell tower log cannot prove a tower is genuine.** Android
   exposes no tower credentials or certificates to apps, and no
   public API verifies tower authenticity. The log shows changes
   only. A fake tower can mimic 4G/5G, and a real tower can change
   ID, so treat a sudden 2G drop or a new cell ID as a reason to
   look closer, not as proof.

## What was verified in this VM — and what was not

Verified here (Linux VM, initially no Android SDK):
- All project files are present, in the standard Gradle layout.
- All XML files (manifest, layouts, values) parse as well-formed
  XML (checked with Python's XML parser).
- `aapt2 compile` succeeded on all resources in
  `app/src/main/res` (Android build-tools 35.0.0, downloaded here).
  `aapt2 link` was not completed because it needs the Material /
  AppCompat AARs, which Gradle supplies at build time.
- Kotlin files were checked for balanced braces and parentheses
  (after stripping comments/strings), and for the absence of any
  audio API and of any kill/stop controls.
- The AppOps API was corrected against AOSP source:
  `OpEntry.getOp()` returns an **int**, and `getPackagesForOps()`
  takes an `int[]` — the first draft used strings and would not
  have compiled.
- JDK 17, Gradle 8.7 and Android SDK platform 35 + build-tools
  35.0.0 were downloaded and installed in this VM to attempt a
  build, and `android.jar` / `aapt2` are present.

Not verified — a full APK was **not** built here:
- Gradle cannot run in this VM at all: even `gradle help` in an
  empty directory fails with "Could not receive a message from
  the daemon". A direct Java localhost-socket test returned the
  runtime message that direct TCP connections are turned off,
  so the Gradle daemon's localhost IPC is blocked in this VM.
  That is an environment limit, not a project error.
- The Android SDK manager also could not fetch packages through
  the VM proxy (Java HttpURLConnection tunnelling failed), so
  platform / build-tools were downloaded directly with curl
  instead.
- The code has therefore **not been compiled**. Build it in
  Android Studio, install the debug APK, grant Usage Access,
  and check the list against Android's own Settings >
  Privacy > Privacy Dashboard — that is the ground truth to
  compare against.
