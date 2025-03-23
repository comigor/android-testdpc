package com.afwsamples.testdpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.app.Service;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;

public class PowerButtonBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("PowerButtonBootReceiver", "igor Device booted! Starting my service/task.");
        Log.d("igor", "igor " + intent.getAction());

        Intent serviceIntent = new Intent(context, PowerButtonService.class);
        context.startForegroundService(serviceIntent);
    }
}
