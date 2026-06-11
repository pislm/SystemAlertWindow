package in.jvapps.system_alert_window.services;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Objects;

import in.jvapps.system_alert_window.R;
import in.jvapps.system_alert_window.utils.Commons;
import in.jvapps.system_alert_window.utils.Constants;
import in.jvapps.system_alert_window.utils.ContextHolder;
import in.jvapps.system_alert_window.utils.LogUtils;
import in.jvapps.system_alert_window.utils.NumberUtils;
import io.flutter.embedding.android.FlutterTextureView;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;

public class WindowServiceNew extends Service implements View.OnTouchListener {

    private static final String TAG = WindowServiceNew.class.getSimpleName();
    public static final String CHANNEL_ID = "ForegroundServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String INTENT_EXTRA_IS_UPDATE_WINDOW = "IsUpdateWindow";
    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";


    private WindowManager windowManager;

    private String windowGravity;
    private int windowWidth;
    private int windowHeight;


    private FlutterView flutterView;
    private int paramFlags =  Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED : WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    private int paramsFromDart;
    private final int paramType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
    private float offsetY;
    private boolean moving;

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public void onCreate() {
        createNotificationChannel();
        // Tapping the foreground-service notification should open the host app. The previous target
        // (SystemAlertWindowPlugin.class) is a FlutterPlugin, not an Activity, so the tap did nothing.
        Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = null;
        if (notificationIntent != null) {
            int piFlags = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, piFlags);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Overlay window service is running")
                .setSmallIcon(R.drawable.ic_desktop_windows_black_24dp)
                .setContentIntent(pendingIntent)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            // Android 12+ throws ForegroundServiceStartNotAllowedException when the app is in
            // the background and not exempt from background-start restrictions.
            LogUtils.getInstance().e(TAG, "startForeground failed, stopping service: " + e.getMessage());
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent means the system restarted this service after killing it (the behaviour
        // implied by START_STICKY). We hold no saved window state, so a restart would leave the
        // foreground notification up with no overlay behind it. Tear the service down instead of
        // lingering as an empty foreground service, and use START_NOT_STICKY so this never happens.
        if (intent == null || intent.getExtras() == null) {
            LogUtils.getInstance().d(TAG, "onStartCommand received a null/empty intent, stopping the service");
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        ContextHolder.setApplicationContext(this);
        @SuppressWarnings("unchecked")
        HashMap<String, Object> paramsMap = (HashMap<String, Object>) intent.getSerializableExtra(Constants.INTENT_EXTRA_PARAMS_MAP);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (!isCloseWindow) {
            assert paramsMap != null;
            boolean isUpdateWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_UPDATE_WINDOW, false);
            if (isUpdateWindow && windowManager != null && flutterView != null) {
                if (flutterView.isAttachedToWindow()) {
                    updateWindow(paramsMap);
                } else {
                    createWindow(paramsMap);
                }
            } else {
                createWindow(paramsMap);
            }
        } else {
            closeWindow(true, startId);
        }
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void setWindowManager() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        }
    }

    private void setWindowLayoutFromMap(HashMap<String, Object> paramsMap) {
        paramsFromDart = Commons.getLayoutParamFlags(paramsMap);
        windowGravity = (String) paramsMap.get(Constants.KEY_GRAVITY);
        windowWidth = NumberUtils.getInt(paramsMap.get(Constants.KEY_WIDTH));
        windowHeight = NumberUtils.getInt(paramsMap.get(Constants.KEY_HEIGHT));
    }

    private WindowManager.LayoutParams getLayoutParams() {
        final WindowManager.LayoutParams params;
        params = new WindowManager.LayoutParams();
        params.width = (windowWidth == 0) ? WindowManager.LayoutParams.MATCH_PARENT : Commons.getPixelsFromDp(this, windowWidth);
        params.height = (windowHeight == 0) ? WindowManager.LayoutParams.WRAP_CONTENT : Commons.getPixelsFromDp(this, windowHeight);
        params.format = PixelFormat.TRANSLUCENT;
        params.type = paramType;
        params.flags |= paramFlags;
        params.flags |= paramsFromDart;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Commons.isClickDisabled) {
            params.alpha = 0.8f;
        }
        params.gravity = Commons.getGravity(windowGravity, Gravity.TOP);
        return params;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createWindow(HashMap<String, Object> paramsMap) {
        try {
            closeWindow(false);
            setWindowManager();
            setWindowLayoutFromMap(paramsMap);
            WindowManager.LayoutParams params = getLayoutParams();
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            engine.getLifecycleChannel().appIsResumed();
            flutterView = new FlutterView(getApplicationContext(), new FlutterTextureView(getApplicationContext()));
            flutterView.attachToFlutterEngine(Objects.requireNonNull(FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE)));
            flutterView.setFitsSystemWindows(true);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            flutterView.setBackgroundColor(Color.TRANSPARENT);
            flutterView.setOnTouchListener(this);
            try {
                windowManager.addView(flutterView, params);
            } catch (Exception ex) {
                // A retry with identical params/engine/permission state would just fail the same way
                // (the realistic cause is a revoked draw-over-apps permission). Clean up the orphan
                // FlutterView so the cached engine isn't left attached to an unshown surface.
                LogUtils.getInstance().e(TAG, "addView failed: " + ex.toString());
                closeWindow(false);
            }
        } catch (Exception ex) {
            LogUtils.getInstance().e(TAG, "createWindow " + ex.getMessage());
        }
    }

    private void updateWindow(HashMap<String, Object> paramsMap) {
        setWindowLayoutFromMap(paramsMap);
        WindowManager.LayoutParams newParams = getLayoutParams();
        WindowManager.LayoutParams previousParams = (WindowManager.LayoutParams) flutterView.getLayoutParams();
        previousParams.width = (windowWidth == 0) ? WindowManager.LayoutParams.MATCH_PARENT : Commons.getPixelsFromDp(this, windowWidth);
        previousParams.height = (windowHeight == 0) ? WindowManager.LayoutParams.WRAP_CONTENT : Commons.getPixelsFromDp(this, windowHeight);
        previousParams.flags = newParams.flags;
        previousParams.alpha = newParams.alpha;
        windowManager.updateViewLayout(flutterView, previousParams);
    }

    private void closeWindow(boolean isStopService) {
        closeWindow(isStopService, -1);
    }

    private void closeWindow(boolean isStopService, int startId) {
        LogUtils.getInstance().i(TAG, "Closing the overlay window");
        // Detach from the cached engine before removing the view. The engine is a singleton shared
        // across every show/close cycle; if detach is skipped (e.g. removeView throws first) the
        // next FlutterView attaches to an engine still bound to this dead surface and renders
        // nothing — a blank overlay behind the foreground notification. Each step runs in its own
        // try/catch so a failure in one never skips the other or the null-out below.
        if (flutterView != null) {
            try {
                flutterView.detachFromFlutterEngine();
            } catch (Exception e) {
                LogUtils.getInstance().e(TAG, "detachFromFlutterEngine failed: " + e.getMessage());
            }
        }
        if (windowManager != null && flutterView != null) {
            try {
                windowManager.removeView(flutterView);
                LogUtils.getInstance().i(TAG, "Successfully closed overlay window");
            } catch (IllegalArgumentException e) {
                LogUtils.getInstance().e(TAG, "view not found");
            }
        }
        windowManager = null;
        flutterView = null;
        if (isStopService) {
            // stopSelf(startId) only stops if this is the most recent start request, so a close
            // that races ahead of a newer show no longer tears down the freshly created window.
            if (startId >= 0) {
                stopSelf(startId);
            } else {
                stopSelf();
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(View v, MotionEvent event) {
        if (windowManager != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) flutterView.getLayoutParams();
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    moving = false;
                    offsetY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - offsetY;
                    if (!moving && Math.abs(dy) > 25) {
                        moving = true;
                    }
                    if (moving) {
                        offsetY = event.getRawY();
                        params.y = params.y + (int) dy;
                        windowManager.updateViewLayout(flutterView, params);
                        // Consume the drag so the same stream isn't also delivered to the Flutter
                        // widget tree (which would spuriously fire taps mid-drag). A plain tap with
                        // no movement still falls through (moving stays false) so buttons work.
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (moving) {
                        moving = false;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
            return false;
        }
        return false;
    }

    @Override
    public void onDestroy() {
        // Teardown only — never rethrow. Throwing from onDestroy crashes the process during a
        // normal service shutdown (e.g. if the NotificationManager lookup or cancel fails),
        // turning a harmless cleanup hiccup into an app crash.
        try {
            closeWindow(false);
            LogUtils.getInstance().d(TAG, "Destroying the overlay window service");
            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
            LogUtils.getInstance().i(TAG, "Successfully destroyed overlay window service");
        } catch (java.lang.Exception e) {
            LogUtils.getInstance().e(TAG, "on Destroy " + e.getMessage());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
