package in.jvapps.system_alert_window;

import static in.jvapps.system_alert_window.services.WindowServiceNew.INTENT_EXTRA_IS_CLOSE_WINDOW;
import static in.jvapps.system_alert_window.services.WindowServiceNew.INTENT_EXTRA_IS_UPDATE_WINDOW;
import static in.jvapps.system_alert_window.utils.Constants.INTENT_EXTRA_PARAMS_MAP;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

import in.jvapps.system_alert_window.services.WindowServiceNew;
import in.jvapps.system_alert_window.utils.Commons;
import in.jvapps.system_alert_window.utils.Constants;
import in.jvapps.system_alert_window.utils.ContextHolder;
import in.jvapps.system_alert_window.utils.LogUtils;
import in.jvapps.system_alert_window.utils.NotificationHelper;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;

public class MethodCallHandlerImpl implements MethodChannel.MethodCallHandler, PluginRegistry.ActivityResultListener {

    private static final String TAG = "SAW:MethodCallHandlerImpl";
    public int ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE = 1237;

    @Nullable
    private Activity mActivity;

    @Nullable
    private MethodChannel channel;

    // Held while the "draw over other apps" settings screen is open so requestPermissions can
    // resolve with the real post-grant state from onActivityResult instead of a stale "false".
    @Nullable
    private MethodChannel.Result pendingPermissionResult;

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        try {
            LogUtils.getInstance().d(TAG, "On method call " + call.method);
            Context mContext = ContextHolder.getApplicationContext();
            String prefMode;
            JSONArray arguments;
            switch (call.method) {
                case "getPlatformVersion":
                    result.success("Android " + Build.VERSION.RELEASE);
                    break;
                case "enableLogs":
                    assert (call.arguments != null);
                    arguments = (JSONArray) call.arguments;
                    LogUtils.getInstance().setLogFileEnabled((boolean) arguments.get(0));
                    result.success(true);
                    break;
                case "getLogFile":
                    result.success(LogUtils.getInstance().getLogFilePath());
                    break;
                case "requestPermissions":
                    assert (call.arguments != null);
                    arguments = (JSONArray) call.arguments;
                    prefMode = (String) arguments.get(0);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    boolean wantOverlay = !isBubbleMode(prefMode);
                    if (!wantOverlay) {
                        // Bubbles can only be toggled by the user in notification settings; report the
                        // current state synchronously.
                        result.success(askPermission(false));
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(mContext)) {
                        result.success(true);
                    } else if (mActivity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // Defer the reply: the grant happens asynchronously after the user returns from
                        // the system settings screen, surfaced in onActivityResult.
                        if (pendingPermissionResult != null) {
                            pendingPermissionResult.success(false);
                        }
                        pendingPermissionResult = result;
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + mContext.getPackageName()));
                        mActivity.startActivityForResult(intent, ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE);
                    } else {
                        // No Activity to receive the result (or pre-M); fall back to the legacy
                        // fire-the-intent-and-report-current-state behaviour.
                        result.success(askPermission(true));
                    }
                    break;
                case "checkPermissions":
                    arguments = (JSONArray) call.arguments;
                    prefMode = (String) arguments.get(0);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    if (checkPermission(!isBubbleMode(prefMode))) {
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                    break;
                case "showSystemWindow":
                    assert (call.arguments != null);
                    arguments = (JSONArray) call.arguments;
                    String title = (String) arguments.get(0);
                    String body = (String) arguments.get(1);
                    JSONObject paramObj = (JSONObject) arguments.get(2);
                    @SuppressWarnings("unchecked")
                    HashMap<String, Object> params = new Gson().fromJson(paramObj.toString(), HashMap.class);
                    prefMode = (String) arguments.get(3);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isBubbleMode(prefMode)) {
                        if (checkPermission(false)) {
                            LogUtils.getInstance().d(TAG, "Going to show Bubble");
                            showBubble(title, body, params);
                            result.success(true);
                        } else {
                            Toast.makeText(mContext, "Please enable bubbles", Toast.LENGTH_LONG).show();
                            result.success(false);
                        }
                    } else {
                        if (checkPermission(true)) {
                            LogUtils.getInstance().d(TAG, "Going to show System Alert Window");
                            final Intent i = new Intent(mContext, WindowServiceNew.class);
                            i.putExtra(INTENT_EXTRA_PARAMS_MAP, params);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            i.putExtra(INTENT_EXTRA_IS_UPDATE_WINDOW, false);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                mContext.startForegroundService(i);
                            } else {
                                mContext.startService(i);
                            }
                            result.success(true);
                        } else {
                            Toast.makeText(mContext, "Please give draw over other apps permission", Toast.LENGTH_LONG).show();
                            result.success(false);
                        }
                    }
                    break;
                case "updateSystemWindow":
                    assert (call.arguments != null);
                    JSONArray updateArguments = (JSONArray) call.arguments;
                    String updateTitle = (String) updateArguments.get(0);
                    String updateBody = (String) updateArguments.get(1);
                    @SuppressWarnings("unchecked")
                    HashMap<String, Object> updateParams = new Gson().fromJson(updateArguments.get(2).toString(), HashMap.class);
                    prefMode = (String) updateArguments.get(3);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isBubbleMode(prefMode)) {
                        if (checkPermission(false)) {
                            LogUtils.getInstance().d(TAG, "Going to update Bubble");
                            // Re-post with the same notification id to update the bubble in place.
                            // Cancelling first tore the bubble down and collapsed/flickered it.
                            showBubble(updateTitle, updateBody, updateParams);
                            result.success(true);
                        } else {
                            Toast.makeText(mContext, "Please enable bubbles", Toast.LENGTH_LONG).show();
                            result.success(false);
                        }
                    } else {
                        if (checkPermission(true)) {
                            LogUtils.getInstance().d(TAG, "Going to update System Alert Window");
                            final Intent i = new Intent(mContext, WindowServiceNew.class);
                            i.putExtra(INTENT_EXTRA_PARAMS_MAP, updateParams);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            i.putExtra(INTENT_EXTRA_IS_UPDATE_WINDOW, true);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                mContext.startForegroundService(i);
                            } else {
                                mContext.startService(i);
                            }
                            result.success(true);
                        } else {
                            Toast.makeText(mContext, "Please give draw over other apps permission", Toast.LENGTH_LONG).show();
                            result.success(false);
                        }
                    }
                    break;
                case "closeSystemWindow":
                    arguments = (JSONArray) call.arguments;
                    prefMode = (String) arguments.get(0);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    if (checkPermission(!isBubbleMode(prefMode))) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isBubbleMode(prefMode)) {
                            NotificationHelper.getInstance(mContext).dismissNotification();
                        } else {
                            final Intent i = new Intent(mContext, WindowServiceNew.class);
                            i.putExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, true);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                mContext.startForegroundService(i);
                            } else {
                                mContext.startService(i);
                            }
                        }
                        result.success(true);
                    } else {
                        result.success(false);
                    }
                    break;
                case "isBubbleMode":
                    arguments = (JSONArray) call.arguments;
                    prefMode = (String) arguments.get(0);
                    if (prefMode == null) {
                        prefMode = "default";
                    }
                    result.success(isBubbleMode(prefMode));
                    break;
                default:
                    result.notImplemented();
            }
        } catch (Exception ex) {
            // Always complete the Result on failure; otherwise the awaiting Dart Future hangs forever.
            LogUtils.getInstance().e(TAG, ex.toString());
            try {
                result.error("SAW_ERROR", ex.getMessage(), null);
            } catch (Exception ignored) {
                // Result was already completed before the throw; nothing more to do.
            }
        }
    }

    /**
     * Registers this instance as a method call handler on the given {@code messenger}.
     *
     * <p>Stops any previously started and unstopped calls.
     *
     * <p>This should be cleaned with {@link #stopListening} once the messenger is disposed of.
     */
    void startListening(BinaryMessenger messenger) {
        if (channel != null) {
            LogUtils.getInstance().w(TAG, "Setting a method call handler before the last was disposed.");
            stopListening();
        }

        channel = new MethodChannel(messenger, Constants.CHANNEL, JSONMethodCodec.INSTANCE);
        channel.setMethodCallHandler(this);
    }

    /**
     * Clears this instance from listening to method calls.
     *
     * <p>Does nothing if {@link #startListening} hasn't been called, or if we're already stopped.
     */
    void stopListening() {
        if (channel == null) {
            LogUtils.getInstance().d(TAG, "Tried to stop listening when no MethodChannel had been initialized.");
            return;
        }

        channel.setMethodCallHandler(null);
        channel = null;
        // Don't leave a deferred requestPermissions() Future dangling if we're torn down mid-flow.
        if (pendingPermissionResult != null) {
            pendingPermissionResult.success(false);
            pendingPermissionResult = null;
        }
    }

    void setActivity(@Nullable Activity activity) {
        this.mActivity = activity;
    }


    private boolean isBubbleMode(String prefMode) {
        boolean isPreferOverlay = "overlay".equalsIgnoreCase(prefMode);
        return Commons.isForceAndroidBubble(ContextHolder.getApplicationContext()) ||
                (!isPreferOverlay && ("bubble".equalsIgnoreCase(prefMode) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R));
    }

    public boolean askPermission(boolean isOverlay) {
        Context mContext = ContextHolder.getApplicationContext();
        if (mContext != null) {
            // >= Q (not > Q): the show/update gate treats API 29 as bubble-capable, so the permission
            // check must agree — otherwise API 29 + BUBBLE pref checked the overlay permission while
            // displaying a bubble. areBubblesAllowed() handles the pre-R dev-options path itself.
            if (!isOverlay && (Commons.isForceAndroidBubble(mContext) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
                return NotificationHelper.getInstance(mContext).areBubblesAllowed();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(mContext)) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + mContext.getPackageName()));
                    if (mActivity == null) {
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        Toast.makeText(mContext, "Please grant, Can Draw Over Other Apps permission.", Toast.LENGTH_SHORT).show();
                        LogUtils.getInstance().e(TAG, "Can't detect the permission change, as the mActivity is null");

                    } else {
                        mActivity.startActivityForResult(intent, ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE);
                    }
                } else {
                    return true;
                }
            }
        } else {
            LogUtils.getInstance().e(TAG, "'Can Draw Over Other Apps' permission is not requested as context is null");
        }
        return false;
    }

    public boolean checkPermission(boolean isOverlay) {
        Context mContext = ContextHolder.getApplicationContext();
        // Mirror askPermission exactly (same >= Q threshold, same areBubblesAllowed check) so
        // checkPermissions() never claims a bubble permission that requestPermissions() would deny.
        if (!isOverlay && (Commons.isForceAndroidBubble(mContext) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
            return NotificationHelper.getInstance(mContext).areBubblesAllowed();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(mContext);
        }
        return false;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void showBubble(String title, String body, HashMap<String, Object> params) {
        Context mContext = ContextHolder.getApplicationContext();
        Icon icon = Icon.createWithResource(mContext, R.drawable.ic_notification);
        NotificationHelper notificationHelper = NotificationHelper.getInstance(mContext);
        notificationHelper.showNotification(icon, title, body, params);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ACTION_MANAGE_OVERLAY_PERMISSION_REQUEST_CODE) {
            Context mContext = ContextHolder.getApplicationContext();
            boolean granted = Settings.canDrawOverlays(mContext);
            if (!granted) {
                LogUtils.getInstance().e(TAG, "System Alert Window will not work without 'Can Draw Over Other Apps' permission");
                Toast.makeText(mContext, "System Alert Window will not work without 'Can Draw Over Other Apps' permission", Toast.LENGTH_LONG).show();
            }
            // Resolve the deferred requestPermissions() Future with the real post-grant state.
            if (pendingPermissionResult != null) {
                pendingPermissionResult.success(granted);
                pendingPermissionResult = null;
            }
        }
        return false;
    }

}
