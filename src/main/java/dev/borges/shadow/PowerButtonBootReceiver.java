package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.health.connect.datatypes.units.Power;
import android.os.Build;
import android.util.Log;

public class PowerButtonBootReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerButtonBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Device booted (" + intent.getAction() + ")! Starting my service/task.");
        PowerButtonService.startService(context);
    }
}
