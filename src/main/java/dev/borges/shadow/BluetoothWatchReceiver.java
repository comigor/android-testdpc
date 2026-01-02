package dev.borges.shadow;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import dev.borges.shadow.util.SettingsHelper;

/**
 * Monitors Bluetooth connection to a configured watch device.
 * When the watch disconnects for longer than the configured timeout,
 * triggers theft mode countdown.
 */
public class BluetoothWatchReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothWatchReceiver";
    private static final String PREF_WATCH_DISCONNECT_TIME = "watch_disconnect_time";
    private static final String ACTION_WATCH_DISCONNECT_ALARM = "dev.borges.shadow.WATCH_DISCONNECT_ALARM";
    private static BluetoothWatchReceiver singleton;

    public static synchronized void registerReceiver(Context context) {
        // Only register on system user (owner profile)
        UserManager um = context.getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            Log.w(TAG, "Not registering BluetoothWatchReceiver - not on system user");
            return;
        }

        Log.i(TAG, "Registering BluetoothWatchReceiver...");
        if (singleton == null) singleton = new BluetoothWatchReceiver();

        try {
            context.unregisterReceiver(singleton);
            Log.d(TAG, "Unregistered existing receiver");
        } catch (Exception e) {
            Log.d(TAG, "No existing receiver to unregister: " + e.getMessage());
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);

        context.registerReceiver(singleton, filter);
        Log.i(TAG, "BluetoothWatchReceiver registered successfully");

        // Check if watch is currently connected on register
        checkCurrentConnectionState(context);
    }

    /**
     * Check current Bluetooth connection state on startup.
     * If watch disconnect protection is enabled and watch is not connected,
     * we should start the disconnect timer.
     */
    private static void checkCurrentConnectionState(Context context) {
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(context);
        if (!"true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY))) {
            return;
        }

        String configuredAddress = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY);
        if (configuredAddress == null || configuredAddress.isEmpty()) {
            return;
        }

        // Check if the device is currently connected
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth not available or disabled - starting disconnect timer");
            startDisconnectTimer(context);
            return;
        }

        // We can't easily check if a specific device is connected without connecting to it.
        // The safest approach is to trust the ACL events. If we just registered and no
        // disconnect event has fired, assume we're in a good state.
        Log.i(TAG, "BluetoothWatchReceiver initialized - watching for disconnection events");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) return;

        // Check if feature is enabled
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(context);
        if (!"true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY))) {
            return;
        }

        // Check if this is the configured watch
        String configuredAddress = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY);
        if (configuredAddress == null || configuredAddress.isEmpty()) {
            return;
        }

        String deviceAddress = device.getAddress();
        if (!configuredAddress.equals(deviceAddress)) {
            Log.d(TAG, "Ignoring event for non-watch device: " + device.getName() + " (" + deviceAddress + ")");
            return;
        }

        String deviceName = device.getName();
        Log.i(TAG, "Watch event: " + action + " for " + deviceName + " (" + deviceAddress + ")");

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            Log.w(TAG, "Watch disconnected! Starting disconnect timer...");
            startDisconnectTimer(context);
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            Log.i(TAG, "Watch reconnected! Cancelling disconnect timer.");
            cancelDisconnectTimer(context);
        }
    }

    /**
     * Start the disconnect timer. If the watch doesn't reconnect within the timeout,
     * theft mode will be triggered.
     */
    private static void startDisconnectTimer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(context);

        int timeoutSeconds = SettingsHelper.parseInt(
            SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY),
            30
        );

        long disconnectTime = System.currentTimeMillis();
        long alarmTime = disconnectTime + (timeoutSeconds * 1000L);

        prefs.edit().putLong(PREF_WATCH_DISCONNECT_TIME, disconnectTime).apply();
        Log.i(TAG, "Disconnect timer started. Theft mode will trigger in " + timeoutSeconds + " seconds if watch doesn't reconnect.");

        scheduleAlarm(context, alarmTime);
    }

    /**
     * Cancel the disconnect timer (called when watch reconnects).
     */
    public static void cancelDisconnectTimer(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        long disconnectTime = prefs.getLong(PREF_WATCH_DISCONNECT_TIME, 0);

        if (disconnectTime > 0) {
            prefs.edit().remove(PREF_WATCH_DISCONNECT_TIME).apply();
            cancelAlarm(context);
            Log.i(TAG, "Disconnect timer cancelled - watch reconnected");
        }
    }

    /**
     * Check if the disconnect timeout has passed and trigger theft mode if so.
     * Called by WatchDisconnectAlarmReceiver.
     */
    public static void checkDisconnectTimeout(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(context);

        long disconnectTime = prefs.getLong(PREF_WATCH_DISCONNECT_TIME, 0);
        if (disconnectTime == 0) {
            Log.d(TAG, "No pending disconnect timer");
            return;
        }

        // Check if feature is still enabled
        if (!"true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY))) {
            Log.d(TAG, "Watch disconnect protection disabled - ignoring timeout");
            prefs.edit().remove(PREF_WATCH_DISCONNECT_TIME).apply();
            return;
        }

        int timeoutSeconds = SettingsHelper.parseInt(
            SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY),
            30
        );

        long now = System.currentTimeMillis();
        long elapsedSeconds = (now - disconnectTime) / 1000;

        if (elapsedSeconds >= timeoutSeconds) {
            Log.w(TAG, "Watch disconnect timeout reached (" + elapsedSeconds + "s >= " + timeoutSeconds + "s). Triggering theft mode!");

            // Clear the disconnect timer
            prefs.edit().remove(PREF_WATCH_DISCONNECT_TIME).apply();

            // Get activation delay for theft mode
            long activationDelayMs = SettingsHelper.parseInt(
                SettingsHelper.getSetting(settingsPrefs, SettingsHelper.ACTIVATION_DELAY_KEY),
                180
            ) * 1000L;

            // Schedule theft mode activation
            long activationTime = now + activationDelayMs;
            PowerButtonReceiver.schedulePendingTheftMode(context, activationTime);

            Log.i(TAG, "Theft mode scheduled to activate in " + (activationDelayMs / 1000) + " seconds");
        } else {
            Log.d(TAG, "Timeout not yet reached (" + elapsedSeconds + "s < " + timeoutSeconds + "s)");
        }
    }

    /**
     * Check if a disconnect timer is currently pending.
     */
    public static boolean isDisconnectPending(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        return prefs.getLong(PREF_WATCH_DISCONNECT_TIME, 0) > 0;
    }

    private static void scheduleAlarm(Context context, long alarmTime) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, WatchDisconnectAlarmReceiver.class);
        intent.setAction(ACTION_WATCH_DISCONNECT_ALARM);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,  // Use request code 1 to differentiate from theft mode alarm
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent);
                    Log.i(TAG, "Exact disconnect alarm scheduled");
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent);
                    Log.w(TAG, "Exact alarm not allowed, using inexact");
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent);
                Log.i(TAG, "Exact disconnect alarm scheduled");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to schedule exact alarm, using inexact", e);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmTime, pendingIntent);
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, WatchDisconnectAlarmReceiver.class);
        intent.setAction(ACTION_WATCH_DISCONNECT_ALARM);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(pendingIntent);
        Log.i(TAG, "Disconnect alarm cancelled");
    }
}
