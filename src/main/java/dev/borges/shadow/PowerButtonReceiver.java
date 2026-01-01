package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.os.VibrationEffect;

import dev.borges.shadow.util.SettingsHelper;

public class PowerButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerButtonReceiver";
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
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
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
                    Log.i(TAG, "THRESHOLD REACHED! " + pressCount + " presses detected, activating theft mode in " + (DELAY_TO_START_MODE/1000) + " seconds...");

                    Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null) {
                        long[] timings = {0, 100, 200, 100, 200, 100}; //Timings in milliseconds: delay, duration, delay, duration, etc.
                        int[] amplitudes = {0, 255, 0, 255, 0, 255}; // Amplitudes: 0 (off), 255 (max), etc.

                        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1); // -1 means no repeat
                        vibrator.vibrate(effect);
                    }

                    new CountDownTimer(DELAY_TO_START_MODE, 200) {
                        public void onTick(long millisUntilFinished) {
                            Log.d(TAG, "Theft mode activating in " + (millisUntilFinished/1000) + " seconds...");
                        }

                        public void onFinish() {
                            Log.i(TAG, "Starting theft mode NOW!");
                            TheftModeActivity.startTheftMode(context);
                        }
                    }.start();

                    // Reset counter after activation
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
        }
    }
}
