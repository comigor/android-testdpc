package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Alarm receiver that fires when watch-related timeouts expire.
 * Handles both Bluetooth disconnect timeout and wrist removal timeout.
 */
public class WatchDisconnectAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchDisconnectAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Alarm triggered with action: " + action);

        if ("dev.borges.shadow.WRIST_REMOVAL_ALARM".equals(action)) {
            Log.i(TAG, "Wrist removal timeout alarm!");
            BluetoothWatchReceiver.checkWristRemovalTimeout(context);
        } else {
            Log.i(TAG, "Watch disconnect timeout alarm!");
            BluetoothWatchReceiver.checkDisconnectTimeout(context);
        }
    }
}
