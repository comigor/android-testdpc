package dev.borges.shadow.wear;

import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Listens for messages from the phone app.
 * Can be used to remotely start/stop monitoring or trigger actions.
 */
public class PhoneMessageListenerService extends WearableListenerService {
    private static final String TAG = "PhoneMessageListener";

    public static final String PATH_START_MONITORING = "/shadow/start_monitoring";
    public static final String PATH_STOP_MONITORING = "/shadow/stop_monitoring";

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        Log.i(TAG, "Received message from phone: " + path);

        switch (path) {
            case PATH_START_MONITORING:
                startWristDetection();
                break;

            case PATH_STOP_MONITORING:
                stopWristDetection();
                break;

            default:
                Log.d(TAG, "Unknown message path: " + path);
                break;
        }
    }

    private void startWristDetection() {
        Log.i(TAG, "Starting wrist detection service from phone command");
        Intent serviceIntent = new Intent(this, WristDetectionService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopWristDetection() {
        Log.i(TAG, "Stopping wrist detection service from phone command");
        Intent serviceIntent = new Intent(this, WristDetectionService.class);
        stopService(serviceIntent);
    }
}
