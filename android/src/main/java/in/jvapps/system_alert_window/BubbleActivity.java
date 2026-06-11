package in.jvapps.system_alert_window;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import in.jvapps.system_alert_window.utils.LogUtils;
import java.util.Objects;
import in.jvapps.system_alert_window.utils.Constants;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.FlutterTextureView;



public class BubbleActivity extends AppCompatActivity {

    private Context mContext;

    // Held as a field so onDestroy can detach it from the shared cached engine. Without the detach,
    // every bubble relaunch (dismiss/reopen, rotation, Android 11+ extras-less relaunch) attached a
    // new FlutterView while the previous one was still bound, leaving the engine rendering into a
    // dead surface — a blank bubble.
    private FlutterView flutterView;

    // Distinct from SystemAlertWindowPlugin's "SAW:Plugin" tag so logcat filtering is unambiguous.
    private final String TAG = "SAW:BubbleActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        mContext = this;
        // Always configure UI regardless of extras — on Android 11+ the system can re-launch
        // bubbles without extras, which would leave a blank screen if we only called configureUI
        // when extras were present. configureUI sets its own ContentView, so the XML layout
        // placeholder is intentionally omitted here.
        configureUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            engine.getLifecycleChannel().appIsResumed();
        } catch (Exception ex) {
            LogUtils.getInstance().e(TAG,"onResume " +  ex.getMessage());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            engine.getLifecycleChannel().appIsInactive();
        }
        catch (Exception ex){
            LogUtils.getInstance().e(TAG, "onPause " + ex.getMessage());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            engine.getLifecycleChannel().appIsPaused();
        }
        catch (Exception ex){
            LogUtils.getInstance().e(TAG, "onStop " + ex.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Detach our view from the shared engine first (mirrors WindowServiceNew.closeWindow), then
        // signal detached. Each step has its own catch so one failure never skips the other.
        if (flutterView != null) {
            try {
                flutterView.detachFromFlutterEngine();
            } catch (Exception e) {
                LogUtils.getInstance().e(TAG, "detachFromFlutterEngine failed: " + e.getMessage());
            }
            flutterView = null;
        }
        try{
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            engine.getLifecycleChannel().appIsDetached();
        }
        catch (Exception ex){
            LogUtils.getInstance().e(TAG,  "onDestroy " + ex.getMessage());
        }
    }

    void configureUI(){
        try{
            LinearLayout linearLayout = new LinearLayout(mContext);
            linearLayout.setOrientation(LinearLayout.VERTICAL); // Set the orientation if needed
            linearLayout.setBackgroundColor(Color.WHITE);
            linearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            ViewCompat.setOnApplyWindowInsetsListener(linearLayout, (view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                );
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
            FlutterEngine engine = FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE);
            if (engine == null) {
                throw new IllegalStateException("FlutterEngine not available");
            }
            // appIsResumed() is intentionally omitted here; onResume() fires immediately after
            // onCreate on first launch and is the single authoritative place to signal resumed state.
            flutterView = new FlutterView(this, new FlutterTextureView(this));
            flutterView.attachToFlutterEngine(Objects.requireNonNull(FlutterEngineCache.getInstance().get(Constants.FLUTTER_CACHE_ENGINE)));
            flutterView.setFitsSystemWindows(false);
            flutterView.setFocusable(true);
            flutterView.setFocusableInTouchMode(true);
            flutterView.setBackgroundColor(Color.WHITE);
            // MATCH_PARENT lets the FlutterView fill the full bubble window; WRAP_CONTENT gives
            // Flutter unbounded height and produces incorrect layout metrics in some engine paths.
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            flutterView.setLayoutParams(params);
            linearLayout.addView(flutterView);
            setContentView(linearLayout);
            ViewCompat.requestApplyInsets(linearLayout);
        }
        catch (Exception ex){
            LogUtils.getInstance().e(TAG, "configureUi " + ex.getMessage());
        }
    }
}
