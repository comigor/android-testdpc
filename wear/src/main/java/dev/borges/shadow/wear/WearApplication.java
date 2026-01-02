package dev.borges.shadow.wear;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Application class that auto-starts the WristDetectionService when the app process starts.
 * This ensures monitoring starts after install, update, or any other app launch.
 */
public class WearApplication extends Application {
    private static final String TAG = "WearApplication";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WearApplication onCreate");

        // Auto-start monitoring if permissions are granted
        if (hasRequiredPermissions()) {
            startWristDetectionService();
        } else {
            Log.i(TAG, "Permissions not yet granted - service will start when user grants permissions");
        }
    }

    private boolean hasRequiredPermissions() {
        // Check foreground sensor permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        // Check background sensor permission (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS_BACKGROUND")
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }

        return true;
    }

    private void startWristDetectionService() {
        if (WristDetectionService.isServiceRunning()) {
            Log.i(TAG, "Service already running");
            return;
        }

        try {
            Intent serviceIntent = new Intent(this, WristDetectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "WristDetectionService auto-started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to auto-start WristDetectionService", e);
        }
    }
}
