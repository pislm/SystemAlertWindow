# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`system_alert_window` is a Flutter **plugin** (not an app) that displays a Truecaller-like overlay window over all other apps on Android, with two-way messaging and click callbacks. The overlay UI is itself a Flutter widget tree rendered by a **second, headless FlutterEngine**. iOS is effectively unimplemented (`ios/Classes/SwiftSystemAlertWindowPlugin.swift` only handles `getPlatformVersion` and shows a stub `UIAlertController`).

Native package id is `in.jvapps.system_alert_window`; the published pub.dev package is `system_alert_window`.

## Commands

```bash
flutter pub get                                   # install deps (run in repo root and in example/)
flutter test                                      # run all Dart unit tests
flutter test test/system_alert_window_test.dart  # single test file
flutter analyze                                   # lint (uses lints ^4.0.0)

cd example && flutter run                          # run the example app on a device/emulator
cd example && flutter build apk                    # build the example APK
```

There is no native (Android instrumentation / JUnit) test suite — `flutter test` only exercises the Dart method-channel surface against a mock handler.

## Release flow (automated, version-driven)

Bumping `version:` in `pubspec.yaml` on `master` is the release trigger. `.github/workflows/tag.yaml` reads the version on every push to `master` and creates+pushes a `v<version>` tag if it doesn't exist. That tag fires `.github/workflows/publish.yaml`, which runs `flutter test`, a `dart pub publish --dry-run`, then publishes to pub.dev (OIDC auth). **Do not bump the version unless you intend to publish.** Keep `CHANGELOG.md` in sync with the version bump.

## Architecture

### Two FlutterEngines, one overlay entry point

The host app runs in its normal engine. The overlay runs in a **separate cached engine** created from a top-level Dart entry point that **must be named `overlayMain`** and annotated `@pragma("vm:entry-point")`. This name is hard-coded in `SystemAlertWindowPlugin.onAttachedToActivity` (`DartExecutor.DartEntrypoint(..., "overlayMain")`). The engine is built via `FlutterEngineGroup` and stored in `FlutterEngineCache` under the key `Constants.FLUTTER_CACHE_ENGINE`. All native code that needs the overlay engine looks it up from this cache — if it's missing, methods throw `IllegalStateException("FlutterEngine not available")`.

### Request path (Dart → native → display)

1. `lib/system_alert_window.dart` — the entire public Dart API is static methods on `SystemAlertWindow`, sending over a `MethodChannel(Constants.CHANNEL, JSONMethodCodec())`. Because of `JSONMethodCodec`, **all arguments are passed as a positional `JSONArray`** (not a named map), and the native side reads them by index. Enums are stringified by `lib/utils/commons.dart` before crossing the channel.
2. `MethodCallHandlerImpl.onMethodCall` (Android) is the central dispatcher. It decides **bubble vs. overlay** via `isBubbleMode(prefMode)` and routes accordingly.
3. Display strategy:
   - **Overlay window** → starts `WindowServiceNew` (a foreground service) which adds a `FlutterView` (backed by the cached engine) directly to the `WindowManager` with `TYPE_APPLICATION_OVERLAY`. Requires the "draw over other apps" / `SYSTEM_ALERT_WINDOW` permission.
   - **Bubble** → `NotificationHelper.showNotification` posts a MessagingStyle notification with `BubbleMetadata`; tapping it launches `BubbleActivity`, which hosts the same cached engine's `FlutterView`. Requires bubbles to be allowed (or dev-options bubbles on Android Go).

### Bubble-vs-overlay decision (`MethodCallHandlerImpl.isBubbleMode` + `Commons.isForceAndroidBubble`)

- `SystemWindowPrefMode.OVERLAY` → prefer the overlay window where supported.
- `SystemWindowPrefMode.BUBBLE` → force bubble.
- `SystemWindowPrefMode.DEFAULT` → bubble on Android 11+ (API ≥ R), overlay below.
- `isForceAndroidBubble` overrides everything to bubble on low-RAM / Android Go devices (no PIP feature, or `isLowRamDevice`).

When editing display logic, change `isBubbleMode`/`isForceAndroidBubble` together — the four public methods (show/update/close/checkPermissions) each branch on this and the branches must stay consistent.

### Messaging & callbacks (three separate mechanisms)

- **Control commands**: `MethodChannel` `in.jvapps.system_alert_window` (see above).
- **Main app ⇄ overlay messaging**: `BasicMessageChannel` `in.jvapps.system_alert_window/message` with `JSONMessageCodec`. `SystemAlertWindow.sendMessageToOverlay()` / `.overlayListener` use it on the Dart side. On the native side, `SystemAlertWindowPlugin.onMessage` **re-dispatches** the message from the host engine's binary messenger into the *cached overlay engine's* DartExecutor over the same channel name — this bridge is what lets the two engines talk.
- **Overlay → main-app callbacks on the main isolate**: done in user code via `IsolateNameServer` named ports (`registerPortWithName` in the host, `lookupPortByName` from the overlay). The plugin does not provide this; the pattern lives in `README.md` and `example/lib/`.

### Android channel/constant parity

`lib/utils/constants.dart` and `android/.../utils/Constants.java` define the same channel names and must stay in sync. The Dart `Constants.MATCH_PARENT = -1` / `WRAP_CONTENT = -2` sentinels are interpreted in `WindowServiceNew.getLayoutParams` / `Commons.getPixelsFromDp` (a value of `-1` means match-parent and bypasses dp→px conversion).

### Layout flags

`SystemWindowFlags` (FLAG_NOT_FOCUSABLE / FLAG_NOT_TOUCH_MODAL / FLAG_NOT_TOUCHABLE) map to `WindowManager.LayoutParams` flags in `Commons.getLayoutParamFlags`. Setting `FLAG_NOT_TOUCHABLE` also flips the static `Commons.isClickDisabled`, which makes `WindowServiceNew` apply `alpha = 0.8` on Android 12+. Flags apply to the overlay window only — they are ignored for bubbles.

### Logging

`LogUtils` is a singleton used throughout the native code (`d/i/w/e`). File logging is off by default; `SystemAlertWindow.enableLogs(true)` turns it on and logs are written to `<externalFilesDir>/Logs/SAW/<ddMMyyyy>.log`, retrievable via `getLogFile`. Native log tags are prefixed `SAW:` (e.g. `SAW:Plugin`, `SAW:BubbleActivity`).

## Android build config

`android/build.gradle`: AGP 8.7.3, `compileSdk`/`targetSdk` 36, `minSdk` 21, Java 8. Native deps: `androidx.appcompat` and `com.google.code.gson` (Gson deserializes the JSON params map into a `HashMap`). The plugin's `AndroidManifest.xml` declares the required permissions (`SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`) and registers `BubbleActivity` + `WindowServiceNew` (`foregroundServiceType="specialUse"`); these merge into the host app — host apps generally don't need to redeclare them.

## Gotchas

- Renaming the `overlayMain` entry point, or forgetting `@pragma("vm:entry-point")`, silently breaks the overlay (the engine starts but renders nothing). Tree-shaking removes un-annotated entry points in release builds.
- The example's `example/android/app/src/main/AndroidManifest.xml` intentionally declares `android.hardware.ram.low` to exercise the forced-bubble path — don't "clean that up."
- iOS changes here are essentially greenfield; the Swift plugin is a placeholder.
