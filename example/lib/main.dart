import 'dart:async';
import 'dart:developer';
import 'dart:isolate';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';
import 'package:system_alert_window/system_alert_window.dart';
import 'custom_overlay.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(MyApp());
}

@pragma("vm:entry-point")
void overlayMain() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(
    MaterialApp(
      debugShowCheckedModeBanner: false,
      home: CustomOverlay(),
    ),
  );
}

class MyApp extends StatefulWidget {
  @override
  _MyAppState createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  String _platformVersion = 'Unknown';
  bool _isShowingWindow = false;
  bool _isUpdatedWindow = false;
  SystemWindowPrefMode prefMode = SystemWindowPrefMode.OVERLAY;
  String _status = 'idle';
  static const String _mainAppPort = 'MainApp';
  final _receivePort = ReceivePort();
  SendPort? homePort;
  String? latestMessageFromOverlay;

  // [TEST] harness: every awaited plugin result is logged with this prefix so the
  // flutter run console shows ground truth for each call.
  void _testLog(String msg) {
    log("[TEST] $msg");
    // ignore: avoid_print
    print("[TEST] $msg");
    if (mounted) setState(() => _status = msg);
  }

  @override
  void initState() {
    super.initState();
    _initPlatformState();
    _requestPermissions();
    if (homePort != null) return;
    final res = IsolateNameServer.registerPortWithName(
      _receivePort.sendPort,
      _mainAppPort,
    );
    log("$res: OVERLAY");
    _receivePort.listen((message) {
      log("message from OVERLAY: $message");
      _testLog("overlay->main: $message");
    });
  }

  @override
  void dispose() {
    super.dispose();
  }

  Future<void> _initPlatformState() async {
    await SystemAlertWindow.enableLogs(true);
    String? platformVersion;
    try {
      platformVersion = await SystemAlertWindow.platformVersion;
    } on PlatformException {
      platformVersion = 'Failed to get platform version.';
    }

    if (!mounted) return;
    if (platformVersion != null)
      setState(() {
        _platformVersion = platformVersion!;
      });
  }

  Future<void> _requestPermissions() async {
    final res = await SystemAlertWindow.requestPermissions(prefMode: prefMode);
    _testLog("requestPermissions($prefMode) => $res");
  }

  Future<void> _checkPermissions() async {
    final res = await SystemAlertWindow.checkPermissions(prefMode: prefMode);
    final bubble = await SystemAlertWindow.isBubbleMode(prefMode: prefMode);
    _testLog("checkPermissions($prefMode) => $res, isBubbleMode => $bubble");
  }

  void _cyclePrefMode() {
    setState(() {
      prefMode = SystemWindowPrefMode
          .values[(prefMode.index + 1) % SystemWindowPrefMode.values.length];
    });
    _testLog("prefMode = $prefMode");
  }

  Future<void> _showDefaultSize() async {
    final res = await SystemAlertWindow.showSystemWindow(
      gravity: SystemWindowGravity.CENTER,
      notificationTitle: "Default size",
      notificationBody: "No width/height passed",
      prefMode: prefMode,
    );
    _testLog("showSystemWindow(default size, $prefMode) => $res");
  }

  Future<void> _delayedShow() async {
    _testLog("show scheduled in 5s — background the app now");
    await Future.delayed(const Duration(seconds: 5));
    final res = await SystemAlertWindow.showSystemWindow(
      height: 200,
      gravity: SystemWindowGravity.CENTER,
      notificationTitle: "From background",
      notificationBody: "Shown while app backgrounded",
      prefMode: prefMode,
    );
    _testLog("delayed showSystemWindow($prefMode) => $res");
  }

  Future<void> _delayedClose() async {
    _testLog("close scheduled in 5s");
    await Future.delayed(const Duration(seconds: 5));
    final res = await SystemAlertWindow.closeSystemWindow(prefMode: prefMode);
    _testLog("delayed closeSystemWindow($prefMode) => $res");
  }

  void _showOverlayWindow() async {
    if (!_isShowingWindow) {
      await SystemAlertWindow.sendMessageToOverlay('show system window');
      final res = await SystemAlertWindow.showSystemWindow(
          height: 200,
          width: MediaQuery.of(context).size.width.floor(),
          gravity: SystemWindowGravity.CENTER,
          prefMode: prefMode,
          layoutParamFlags: [SystemWindowFlags.FLAG_NOT_FOCUSABLE]);
      _testLog("showSystemWindow(h200, $prefMode) => $res");
      setState(() {
        _isShowingWindow = true;
      });
    } else if (!_isUpdatedWindow) {
      await SystemAlertWindow.sendMessageToOverlay('update system window');
      final res = await SystemAlertWindow.updateSystemWindow(
        height: 200,
        width: MediaQuery.of(context).size.width.floor(),
        gravity: SystemWindowGravity.CENTER,
        prefMode: prefMode,
        layoutParamFlags: [SystemWindowFlags.FLAG_NOT_FOCUSABLE, SystemWindowFlags.FLAG_NOT_TOUCHABLE],
      );
      _testLog("updateSystemWindow(NOT_TOUCHABLE, $prefMode) => $res");
      setState(() {
        _isUpdatedWindow = true;
        SystemAlertWindow.sendMessageToOverlay(_isUpdatedWindow);
      });
    } else {
      setState(() {
        _isShowingWindow = false;
        _isUpdatedWindow = false;
        SystemAlertWindow.sendMessageToOverlay(_isUpdatedWindow);
      });
      final res = await SystemAlertWindow.closeSystemWindow(prefMode: prefMode);
      _testLog("closeSystemWindow($prefMode) => $res");
    }
  }

  Widget _btn(String label, VoidCallback onPressed) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4.0),
      child: MaterialButton(
        onPressed: onPressed,
        textColor: Colors.white,
        child: Text(label, textAlign: TextAlign.center),
        color: Colors.deepOrange,
        padding: const EdgeInsets.symmetric(vertical: 8.0, horizontal: 12.0),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('SAW test harness'),
        ),
        body: SingleChildScrollView(
          child: Center(
            child: Column(
              children: <Widget>[
                Text('Running on: $_platformVersion'),
                Padding(
                  padding: const EdgeInsets.all(8.0),
                  child: Text('status: $_status',
                      style: const TextStyle(fontSize: 12, color: Colors.black54)),
                ),
                _btn("PrefMode: ${prefMode.toString().split('.').last}", _cyclePrefMode),
                _btn("Check permissions", _checkPermissions),
                _btn("Request permissions", _requestPermissions),
                _btn(
                    !_isShowingWindow
                        ? "Show window (h=200)"
                        : !_isUpdatedWindow
                            ? "Update window (no-touch)"
                            : "Close window",
                    _showOverlayWindow),
                _btn("Show window (default size)", _showDefaultSize),
                _btn("Show in 5s (background test)", _delayedShow),
                _btn("Close in 5s (background test)", _delayedClose),
                _btn("Send message to overlay",
                    () => SystemAlertWindow.sendMessageToOverlay("message from main")),
                TextButton(
                    onPressed: () async {
                      String? logFilePath = await SystemAlertWindow.getLogFile;
                      _testLog("getLogFile => $logFilePath");
                      if (logFilePath != null && logFilePath.isNotEmpty) {
                        final files = <XFile>[];
                        files.add(XFile(logFilePath, name: "Log File from SAW"));
                        await Share.shareXFiles(files);
                      }
                    },
                    child: Text("Share Log file"))
              ],
            ),
          ),
        ),
      ),
    );
  }
}
