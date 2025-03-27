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

    private long lastPressTime = 0;
    private int pressCount = 0;

    public static void registerReceiver(Context context) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        PowerButtonReceiver powerButtonReceiver = new PowerButtonReceiver();
        context.registerReceiver(powerButtonReceiver, filter);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Log.d(TAG, "Screen toggled to " + intent.getAction());

            SharedPreferences sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(context);
            int TIME_WINDOW = sharedPreferences.getInt(SettingsHelper.PRESS_TIME_WINDOW, 2000);
            int NUMBER_OF_PRESSES = sharedPreferences.getInt(SettingsHelper.POWER_BUTTON_PRESSES, 4);
            long DELAY_TO_START_MODE = sharedPreferences.getInt(SettingsHelper.ACTIVATION_DELAY, 10 * 60) * 1000L;

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPressTime < TIME_WINDOW) {
                pressCount++;
                if (pressCount == NUMBER_OF_PRESSES) {
                    Log.i(TAG, "Power button pressed " + pressCount + " times in quick succession, theft mode in " + DELAY_TO_START_MODE);

                    Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null) {
                        long[] timings = {0, 100, 200, 100, 200, 100}; //Timings in milliseconds: delay, duration, delay, duration, etc.
                        int[] amplitudes = {0, 255, 0, 255, 0, 255}; // Amplitudes: 0 (off), 255 (max), etc.

                        VibrationEffect effect = null; // -1 means no repeat

                        effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
                        vibrator.vibrate(effect);
                    }

                    new CountDownTimer(DELAY_TO_START_MODE, 200) {
                        public void onTick(long millisUntilFinished) {}

                        public void onFinish() {
                            Log.i(TAG,  "Starting theft mode...");
                            TheftModeActivity.startTheftMode(context);
                        }
                    }.start();
                }
            } else {
                pressCount = 1;
            }
            lastPressTime = currentTime;
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
