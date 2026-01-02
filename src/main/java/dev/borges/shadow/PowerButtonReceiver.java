package dev.borges.shadow;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import android.os.Vibrator;
import android.os.VibrationEffect;

import dev.borges.shadow.util.SettingsHelper;

public class PowerButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerButtonReceiver";
    private static final String PREF_THEFT_MODE_ACTIVATION_TIME = "theft_mode_activation_time";
    private static final String ACTION_THEFT_MODE_ALARM = "dev.borges.shadow.THEFT_MODE_ALARM";
    private static PowerButtonReceiver singleton;

    private long lastPressTime = 0;
    private int pressCount = 0;

    public static synchronized void registerReceiver(Context context) {
        Log.i(TAG, "Registering PowerButtonReceiver...");
        if (singleton == null) singleton = new PowerButtonReceiver();

        try {
            context.unregisterReceiver(singleton);
            Log.d(TAG, "Unregistered existing receiver");
        } catch (Exception e) {
            Log.d(TAG, "No existing receiver to unregister: " + e.getMessage());
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);

        context.registerReceiver(singleton, filter);
        Log.i(TAG, "PowerButtonReceiver registered successfully. Listening for screen on/off events.");

        // Check for pending theft mode activation on register
        checkPendingTheftMode(context);
    }

    /**
     * Check if theft mode activation is pending and activate if time has passed.
     * Call this from various entry points (boot, screen events, accessibility service, etc.)
     */
    public static void checkPendingTheftMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        long activationTime = prefs.getLong(PREF_THEFT_MODE_ACTIVATION_TIME, 0);

        if (activationTime > 0) {
            long now = System.currentTimeMillis();
            if (now >= activationTime) {
                Log.i(TAG, "Pending theft mode activation time reached! Starting theft mode NOW!");
                clearPendingTheftMode(context);
                TheftModeActivity.startTheftMode(context);
            } else {
                long remaining = (activationTime - now) / 1000;
                Log.d(TAG, "Theft mode pending, activating in " + remaining + " seconds");
            }
        }
    }

    /**
     * Schedule theft mode activation at the given timestamp.
     */
    public static void schedulePendingTheftMode(Context context, long activationTime) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        prefs.edit().putLong(PREF_THEFT_MODE_ACTIVATION_TIME, activationTime).apply();
        Log.i(TAG, "Theft mode scheduled for " + activationTime + " (in " + ((activationTime - System.currentTimeMillis()) / 1000) + " seconds)");

        // Schedule an alarm 2 seconds after activation time to ensure time has passed when it fires
        scheduleAlarm(context, activationTime + 2000);
    }

    /**
     * Clear any pending theft mode activation.
     */
    public static void clearPendingTheftMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        prefs.edit().remove(PREF_THEFT_MODE_ACTIVATION_TIME).apply();
        Log.i(TAG, "Pending theft mode activation cleared");

        // Cancel the alarm
        cancelAlarm(context);
    }

    private static void scheduleAlarm(Context context, long activationTime) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, TheftModeAlarmReceiver.class);
        intent.setAction(ACTION_THEFT_MODE_ALARM);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationTime, pendingIntent);
                    Log.i(TAG, "Exact alarm scheduled");
                } else {
                    // Fall back to inexact alarm
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationTime, pendingIntent);
                    Log.w(TAG, "Exact alarm not allowed, using inexact alarm");
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationTime, pendingIntent);
                Log.i(TAG, "Exact alarm scheduled");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to schedule exact alarm, using inexact", e);
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, activationTime, pendingIntent);
        }
    }

    private static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, TheftModeAlarmReceiver.class);
        intent.setAction(ACTION_THEFT_MODE_ALARM);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(pendingIntent);
        Log.i(TAG, "Theft mode alarm cancelled");
    }

    /**
     * Check if theft mode activation is pending.
     */
    public static boolean isTheftModePending(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        return prefs.getLong(PREF_THEFT_MODE_ACTIVATION_TIME, 0) > 0;
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        // Check for pending theft mode on every screen event
        checkPendingTheftMode(context);

        // Only count SCREEN_OFF events to avoid double-counting
        // Each power button press generates both SCREEN_OFF and SCREEN_ON events
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            Log.d(TAG, "Screen turned OFF - counting as power button press");

            SharedPreferences sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(context);
            int TIME_WINDOW = Integer.parseInt(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY));
            int NUMBER_OF_PRESSES = Integer.parseInt(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY));
            long DELAY_TO_START_MODE = Integer.parseInt(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY)) * 1000L;

            long currentTime = System.currentTimeMillis();
            long timeSinceLastPress = currentTime - lastPressTime;

            if (timeSinceLastPress < TIME_WINDOW && lastPressTime > 0) {
                pressCount++;
                Log.i(TAG, "Power button press #" + pressCount + " (within " + timeSinceLastPress + "ms of previous press, window=" + TIME_WINDOW + "ms)");

                if (pressCount >= NUMBER_OF_PRESSES) {
                    Log.i(TAG, "THRESHOLD REACHED! " + pressCount + " presses detected, scheduling theft mode in " + (DELAY_TO_START_MODE/1000) + " seconds...");

                    // Vibrate to confirm detection
                    Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null) {
                        long[] timings = {0, 100, 200, 100, 200, 100};
                        int[] amplitudes = {0, 255, 0, 255, 0, 255};
                        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
                        vibrator.vibrate(effect);
                    }

                    // Schedule theft mode activation using persistent timestamp
                    long activationTime = currentTime + DELAY_TO_START_MODE;
                    schedulePendingTheftMode(context, activationTime);

                    // Reset counter after scheduling
                    pressCount = 0;
                }
            } else {
                if (lastPressTime > 0) {
                    Log.i(TAG, "New press sequence started (previous sequence: " + pressCount + " presses, " + timeSinceLastPress + "ms ago)");
                } else {
                    Log.i(TAG, "First power button press detected");
                }
                pressCount = 1;
            }
            lastPressTime = currentTime;
        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Log.d(TAG, "Screen turned ON (not counted)");
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        private static final String TAG = "PowerButtonBootReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Device booted (" + intent.getAction() + ")! Starting my service/task.");
            PowerButtonReceiver.registerReceiver(context.getApplicationContext());
            // Check for pending theft mode on boot
            PowerButtonReceiver.checkPendingTheftMode(context.getApplicationContext());
        }
    }
}
