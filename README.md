# system_alert_window
[![Pub](https://img.shields.io/pub/v/system_alert_window.svg)](https://pub.dartlang.org/packages/system_alert_window)

A flutter plugin to show Truecaller like overlay window, over all other apps along with callback events. Android Go or Android 11 & above, this plugin shows notification bubble, in other android versions, it shows an overlay window.

> **Platform support: Android only.** This plugin does **not** support iOS — iOS has no public API for drawing windows over other apps, so the overlay/bubble functionality cannot be implemented. The plugin declares no iOS implementation, and the Dart API degrades to safe no-ops on iOS (and any non-Android platform): methods return `false`/`null` instead of throwing, so cross-platform apps still build and run without special-casing every call. See [iOS](#ios) below.

## Android

### Demo
###### 1. Clip of example app and 2. Working of button click in the background
<img src="https://github.com/pvsvamsi/SystemAlertWindow/raw/master/assets/images/example%20demo.gif" width="300" height="570">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;<img src="https://github.com/pvsvamsi/SystemAlertWindow/raw/master/assets/images/background%20button%20click.gif" width="300" height="570">

### Manifest

      //Permissions
      <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
      <uses-permission android:name="android.permission.FOREGROUND_SERVICE " />
      <uses-permission android:name="android.permission.WAKE_LOCK" />


          <application
              //Linking the previously added application class
              android:name=".Application"
              android:label="system_alert_window_example"
              android:icon="@mipmap/ic_launcher">

> **Android 14 (API 34+) note:** the overlay path runs as a `foregroundServiceType="specialUse"` service, and the plugin declares `FOREGROUND_SERVICE_SPECIAL_USE`. These merge into your app via manifest merging. If your app targets API 34+ and ships to Google Play, you must declare a justification for the `specialUse` foreground-service type in the Play Console; Google reviews this and rejects apps that fit a standard FGS category. This requirement applies to every app depending on this plugin's overlay mode.

#### Android 10 & below, Android GO (API 27)

Uses &#x27;draw on top&#x27; permission and displays it as a overlay window

#### Android 11 & above

With SystemWindowPrefMode.OVERLAY, system alert windows uses 'overlay functionality' wherever it's supported. In that mode, it will show as bubbles if the 'display over other apps' is not supported.
With SystemWindowPrefMode.DEFAULT/SystemWindowPrefMode.BUBBLE, User has to allow 'All conversations can bubble' in the notification settings of the app. Uses Android Bubble APIs to show the overlay window inside a notification bubble.

Edge-to-edge content and window insets are handled automatically inside the bubble container — wrap your overlay widget with `SafeArea` to avoid content being obscured by system bars.

#### Android GO (API 29)

User has to manually enable bubbles from the developer options. Uses Android Bubble APIs to show the overlay window inside a notification bubble.


## iOS

**Not supported.** iOS provides no public API for displaying a window over other apps, so the overlay/bubble feature cannot be implemented. The plugin ships **no iOS implementation** (nothing is registered on iOS). The Dart API degrades gracefully: `showSystemWindow`/`updateSystemWindow`/`closeSystemWindow`/`checkPermissions`/`requestPermissions`/`isBubbleMode` resolve with `false`, `platformVersion`/`getLogFile`/`sendMessageToOverlay` resolve with `null`, `enableLogs` is a no-op, and `overlayListener` never emits. Calls do **not** throw and do **not** hang, so an app can call them unconditionally — but they do nothing on iOS. Do not rely on this plugin for any iOS overlay functionality.


## Example

#### Show Overlay

#### Request overlay permission
      await SystemAlertWindow.requestPermissions();

### Inside `main.dart` create an entry point for your Overlay widget;
```dart
// overlay entry point
@pragma("vm:entry-point")
void overlayMain() {
  runApp(const MaterialApp(
    debugShowCheckedModeBanner: false,
    home: Material(child: Text("My overlay"))
  ));
}


 //Open overLay content

//  - Optional arguments:
/// `gravity` Position of the window and default is [SystemWindowGravity.CENTER]
/// `width` Width of the window and default is [Constants.MATCH_PARENT]
/// `height` Height of the window and default is [Constants.WRAP_CONTENT]
/// `notificationTitle` Notification title, applicable in case of bubble
/// `notificationBody` Notification body, applicable in case of bubble
/// `prefMode` Preference for the system window. Default is [SystemWindowPrefMode.DEFAULT]
/// `layoutParamFlags` List of List of WindowManager.LayoutParams. This is not applicable for bubbles.
await SystemAlertWindow.showSystemWindow();

/// update the overlay flag while the overlay in action
///   - Optional arguments:
/// `gravity` Position of the window and default is [SystemWindowGravity.CENTER]
/// `width` Width of the window and default is [Constants.MATCH_PARENT]
/// `height` Height of the window and default is [Constants.WRAP_CONTENT]
/// `notificationTitle` Notification title, applicable in case of bubble
/// `notificationBody` Notification body, applicable in case of bubble
/// `prefMode` Preference for the system window. Default is [SystemWindowPrefMode.DEFAULT]
/// `layoutParamFlags` List of List of WindowManager.LayoutParams. This is not applicable for bubbles.
await SystemAlertWindow.updateSystemWindow();

 // closes overlay if open
await SystemAlertWindow.closeSystemWindow();

 // broadcast data to overlay app from main app
 // NOTE: this channel is one-way (main app -> overlay). Calling sendMessageToOverlay from
 // inside the overlay isolate loops back to the overlay itself, not the main app — for
 // overlay -> main-app communication use the IsolateNameServer pattern shown below.
await SystemAlertWindow.sendMessageToOverlay("Hello from the other side");

 //streams message from main app to overlay.
SystemAlertWindow.overlayListener.listen((event) {
log("Current Event: $event");
});




```




#### Isolate communication
###### Use this snippet, if you want the callbacks on your main thread

###### Create an isolate_manager.dart
```dart
import 'dart:isolate';

import 'dart:ui';

class IsolateManager{

  static const FOREGROUND_PORT_NAME = "foreground_port";

  static SendPort lookupPortByName() {
    return IsolateNameServer.lookupPortByName(FOREGROUND_PORT_NAME);
  }

  static bool registerPortWithName(SendPort port) {
    assert(port != null, "'port' cannot be null.");
    removePortNameMapping(FOREGROUND_PORT_NAME);
    return IsolateNameServer.registerPortWithName(port, FOREGROUND_PORT_NAME);
  }

  static bool removePortNameMapping(String name) {
    assert(name != null, "'name' cannot be null.");
    return IsolateNameServer.removePortNameMapping(name);
  }

}
```

###### While initializing system alert window in your code
```dart
    await SystemAlertWindow.checkPermissions();
    ReceivePort _port = ReceivePort();
    IsolateManager.registerPortWithName(_port.sendPort);
    _port.listen((message) {
          log("message from OVERLAY: $message");
          print("Do what ever you want here. This is inside your application scope");
    });
```

###### Use this to send data to isolate mentioned above
```dart
void callBackFunction(String tag) {
  print("Got tag " + tag);
  SendPort port = IsolateManager.lookupPortByName();
  port.send(tag);
}
```


      

