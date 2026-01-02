package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class TheftModeAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "TheftModeAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Theft mode alarm triggered!");
        PowerButtonReceiver.checkPendingTheftMode(context);
    }
}
