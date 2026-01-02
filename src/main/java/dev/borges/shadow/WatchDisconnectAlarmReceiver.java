package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Alarm receiver that fires when the watch disconnect timeout expires.
 * Checks if the watch is still disconnected and triggers theft mode if so.
 */
public class WatchDisconnectAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchDisconnectAlarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Watch disconnect alarm triggered!");
        BluetoothWatchReceiver.checkDisconnectTimeout(context);
    }
}
