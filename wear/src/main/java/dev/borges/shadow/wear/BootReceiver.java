package dev.borges.shadow.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts the WristDetectionService automatically when the watch boots.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "WearBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot event received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            Log.i(TAG, "Starting WristDetectionService on boot");
            startWristDetectionService(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.i(TAG, "App updated - starting WristDetectionService");
            startWristDetectionService(context);
        } else if (Intent.ACTION_POWER_CONNECTED.equals(action) ||
                   Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            // Use charging events as opportunity to ensure service is running
            if (!WristDetectionService.isServiceRunning()) {
                Log.i(TAG, "Charging state changed - ensuring service is running");
                startWristDetectionService(context);
            }
        }
    }

    private void startWristDetectionService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, WristDetectionService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "WristDetectionService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WristDetectionService", e);
        }
    }
}
