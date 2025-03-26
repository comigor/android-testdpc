package dev.borges.shadow;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

@TargetApi(Build.VERSION_CODES.O)
public class PowerButtonService extends Service {
    private static final String TAG = "PowerButtonService";

    private static final String CHANNEL_ID = "PowerButtonServiceChannel";
    private PowerButtonReceiver powerButtonReceiver;

    public static void startService(Context context) {
        Intent serviceIntent = new Intent(context, PowerButtonService.class);
        context.startForegroundService(serviceIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        // Create a notification channel (for foreground service)
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "PowerButtonService",
                NotificationManager.IMPORTANCE_MIN
        );
        serviceChannel.setSound(null, null);
        serviceChannel.enableVibration(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);

        // Register the secondary receiver
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        powerButtonReceiver = new PowerButtonReceiver();
        registerReceiver(powerButtonReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service started");

        // Create a foreground notification
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PowerButtonService")
                .setContentText("Running in the background")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setShowWhen(false)
                .build();
        startForeground(1, notification);

        return START_STICKY; // Or START_NOT_STICKY, START_REDELIVER_INTENT
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        if (powerButtonReceiver != null) {
            unregisterReceiver(powerButtonReceiver);
            powerButtonReceiver = null;
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        private static final String TAG = "PowerButtonBootReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Device booted (" + intent.getAction() + ")! Starting my service/task.");
            PowerButtonService.startService(context);
        }
    }
}
