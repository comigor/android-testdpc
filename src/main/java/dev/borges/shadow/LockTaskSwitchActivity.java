package dev.borges.shadow;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.afwsamples.testdpc.DeviceAdminReceiver;

public class LockTaskSwitchActivity extends AppCompatActivity {
    private static final String TAG = "LockTaskSwitchActivity";
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // Create UI
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);

        TextView text = new TextView(this);
        text.setText("Carregando...");
        text.setTextColor(Color.WHITE);
        text.setTextSize(24);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.gravity = android.view.Gravity.CENTER;
        layout.addView(text, textParams);

        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        progressParams.gravity = android.view.Gravity.CENTER;
        progressParams.topMargin = 200;
        layout.addView(progress, progressParams);

        setContentView(layout);

        // Start lock task mode
        startLockTask();
        Log.i(TAG, "Lock task mode started");

        // Delay then switch
        handler.postDelayed(this::switchToDecoy, 500);
    }

    private void switchToDecoy() {
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        UserManager um = getSystemService(UserManager.class);
        ComponentName admin = new ComponentName(this, DeviceAdminReceiver.class);

        SharedPreferences prefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);
        long decoySerial = prefs.getLong("decoy_serial", -1);

        if (decoySerial != -1L) {
            UserHandle decoyHandle = um.getUserForSerialNumber(decoySerial);
            if (decoyHandle != null) {
                Log.i(TAG, "Switching to decoy: " + decoySerial);

                // Set brightness to minimum
                setBrightnessToMinimum();

                // Stop lock task before switch
                stopLockTask();

                dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH);
                dpm.switchUser(admin, decoyHandle);

                // Try to turn screen off after a short delay
                handler.postDelayed(() -> {
                    try {
                        dpm.lockNow();
                    } catch (Exception e) {
                        Log.w(TAG, "Could not lock screen", e);
                    }
                }, 100);
            }
        }

        // Finish after a delay
        handler.postDelayed(this::finish, 5000);
    }

    private void setBrightnessToMinimum() {
        // Set window brightness to minimum (no permission needed)
        try {
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = 0.0f;
            getWindow().setAttributes(layoutParams);
            Log.i(TAG, "Window brightness set to minimum");
        } catch (Exception e) {
            Log.w(TAG, "Could not set window brightness", e);
        }

        // Also try system brightness (needs WRITE_SETTINGS permission, may fail)
        try {
            android.provider.Settings.System.putInt(
                getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                0
            );
            Log.i(TAG, "System brightness set to minimum");
        } catch (Exception e) {
            // This is expected if WRITE_SETTINGS permission not granted
            Log.d(TAG, "Could not set system brightness (WRITE_SETTINGS not granted)");
        }
    }

    @Override
    public void onBackPressed() {
        // Block back button
    }

    public static void launch(Context context) {
        // First whitelist for lock task
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);

        try {
            dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
        } catch (Exception e) {
            Log.e(TAG, "Failed to set lock task packages", e);
        }

        android.content.Intent intent = new android.content.Intent(context, LockTaskSwitchActivity.class);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
