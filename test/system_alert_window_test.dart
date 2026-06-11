import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:system_alert_window/system_alert_window.dart';

void main() {
  const MethodChannel channel = MethodChannel('in.jvapps.system_alert_window', JSONMethodCodec());

  TestWidgetsFlutterBinding.ensureInitialized();

  final List<MethodCall> log = <MethodCall>[];

  setUp(() {
    // The plugin only talks to the platform on Android; pin the target so the channel-contract
    // tests below exercise the real marshalling path regardless of the host running the tests.
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    log.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'getPlatformVersion':
            return '42';
          case 'getLogFile':
            return '/tmp/log.txt';
          default:
            return true;
        }
      },
    );
  });

  tearDown(() {
    debugDefaultTargetPlatformOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('getPlatformVersion', () async {
    expect(await SystemAlertWindow.platformVersion, '42');
    expect(log.single.method, 'getPlatformVersion');
  });

  // The native side reads JSONArray args strictly by index, so the positional shape and the
  // stringified enums / -1,-2 sentinels are a hard contract. These tests fail fast if it drifts.
  test('showSystemWindow sends the default positional args and sentinels', () async {
    await SystemAlertWindow.showSystemWindow();
    final MethodCall call = log.single;
    expect(call.method, 'showSystemWindow');
    final List<dynamic> args = call.arguments as List<dynamic>;
    expect(args.length, 4);
    expect(args[0], 'Title'); // notificationTitle default
    expect(args[1], 'Body'); // notificationBody default
    final Map<String, dynamic> params = (args[2] as Map).cast<String, dynamic>();
    expect(params['gravity'], 'center'); // SystemWindowGravity.CENTER
    expect(params['width'], -1); // Constants.MATCH_PARENT
    expect(params['height'], -2); // Constants.WRAP_CONTENT
    expect(params['layoutParamFlags'], isEmpty);
    expect(args[3], 'default'); // SystemWindowPrefMode.DEFAULT
  });

  test('showSystemWindow stringifies gravity, prefMode and flags and keeps explicit sizes', () async {
    await SystemAlertWindow.showSystemWindow(
      gravity: SystemWindowGravity.BOTTOM,
      width: 100,
      height: 200,
      notificationTitle: 'T',
      notificationBody: 'B',
      prefMode: SystemWindowPrefMode.BUBBLE,
      layoutParamFlags: const [SystemWindowFlags.FLAG_NOT_FOCUSABLE, SystemWindowFlags.FLAG_NOT_TOUCHABLE],
    );
    final List<dynamic> args = log.single.arguments as List<dynamic>;
    expect(args[0], 'T');
    expect(args[1], 'B');
    final Map<String, dynamic> params = (args[2] as Map).cast<String, dynamic>();
    expect(params['gravity'], 'bottom');
    expect(params['width'], 100);
    expect(params['height'], 200);
    expect(params['layoutParamFlags'], ['FLAG_NOT_FOCUSABLE', 'FLAG_NOT_TOUCHABLE']);
    expect(args[3], 'bubble');
  });

  test('updateSystemWindow uses the same positional contract', () async {
    await SystemAlertWindow.updateSystemWindow(
      gravity: SystemWindowGravity.TOP,
      prefMode: SystemWindowPrefMode.OVERLAY,
    );
    final MethodCall call = log.single;
    expect(call.method, 'updateSystemWindow');
    final List<dynamic> args = call.arguments as List<dynamic>;
    final Map<String, dynamic> params = (args[2] as Map).cast<String, dynamic>();
    expect(params['gravity'], 'top');
    expect(args[3], 'overlay');
  });

  test('gravity enum maps to every expected string', () async {
    Future<String> gravityFor(SystemWindowGravity g) async {
      log.clear();
      await SystemAlertWindow.showSystemWindow(gravity: g);
      final Map<String, dynamic> params = ((log.single.arguments as List)[2] as Map).cast<String, dynamic>();
      return params['gravity'] as String;
    }

    expect(await gravityFor(SystemWindowGravity.TOP), 'top');
    expect(await gravityFor(SystemWindowGravity.CENTER), 'center');
    expect(await gravityFor(SystemWindowGravity.BOTTOM), 'bottom');
    expect(await gravityFor(SystemWindowGravity.LEADING), 'leading');
    expect(await gravityFor(SystemWindowGravity.TRAILING), 'trailing');
  });

  test('prefMode-only methods pass the stringified mode positionally', () async {
    await SystemAlertWindow.closeSystemWindow(prefMode: SystemWindowPrefMode.OVERLAY);
    expect(log.last.method, 'closeSystemWindow');
    expect((log.last.arguments as List).single, 'overlay');

    await SystemAlertWindow.checkPermissions(prefMode: SystemWindowPrefMode.BUBBLE);
    expect(log.last.method, 'checkPermissions');
    expect((log.last.arguments as List).single, 'bubble');

    await SystemAlertWindow.requestPermissions(prefMode: SystemWindowPrefMode.DEFAULT);
    expect(log.last.method, 'requestPermissions');
    expect((log.last.arguments as List).single, 'default');

    final bool? bubble = await SystemAlertWindow.isBubbleMode(prefMode: SystemWindowPrefMode.DEFAULT);
    expect(bubble, true);
    expect(log.last.method, 'isBubbleMode');
    expect((log.last.arguments as List).single, 'default');
  });

  test('enableLogs sends a bool positionally and getLogFile round-trips', () async {
    await SystemAlertWindow.enableLogs(true);
    expect(log.last.method, 'enableLogs');
    expect((log.last.arguments as List).single, true);

    expect(await SystemAlertWindow.getLogFile, '/tmp/log.txt');
  });

  // Android-only plugin: on every other platform the API must degrade to safe no-ops without
  // touching the platform channel (no MissingPluginException, nothing crosses the wire).
  test('methods are safe no-ops on non-Android platforms', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.iOS;
    log.clear();

    expect(await SystemAlertWindow.showSystemWindow(), false);
    expect(await SystemAlertWindow.updateSystemWindow(), false);
    expect(await SystemAlertWindow.checkPermissions(), false);
    expect(await SystemAlertWindow.requestPermissions(), false);
    expect(await SystemAlertWindow.closeSystemWindow(), false);
    expect(await SystemAlertWindow.isBubbleMode(), false);
    expect(await SystemAlertWindow.platformVersion, isNull);
    expect(await SystemAlertWindow.getLogFile, isNull);
    await SystemAlertWindow.enableLogs(true);
    expect(await SystemAlertWindow.sendMessageToOverlay('x'), isNull);

    expect(log, isEmpty);
  });
}
