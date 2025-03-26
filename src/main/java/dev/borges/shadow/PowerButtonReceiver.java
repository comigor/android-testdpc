package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.os.VibrationEffect;

public class PowerButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerButtonReceiver";

    private static final long TIME_WINDOW = 2000; // TODO: make configurable
    private static final int NUMBER_OF_PRESSES = 4; // TODO: make configurable
    private static final long DELAY_TO_START_MODE = 10 * 1000; // TODO: change to 5 minutes, make configurable

    private long lastPressTime = 0;
    private int pressCount = 0;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) || Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
            Log.d(TAG, "Screen toggled to " + intent.getAction());

            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPressTime < TIME_WINDOW) {
                pressCount++;
                if (pressCount == NUMBER_OF_PRESSES) {
                    Log.i(TAG, "Power button pressed " + pressCount + " times in quick succession, theft mode in " + DELAY_TO_START_MODE);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                        if (vibrator != null) {
                            long[] timings = {0, 100, 200, 100, 200, 100}; //Timings in milliseconds: delay, duration, delay, duration, etc.
                            int[] amplitudes = {0, 255, 0, 255, 0, 255}; // Amplitudes: 0 (off), 255 (max), etc.

                            VibrationEffect effect = null; // -1 means no repeat

                            effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
                            vibrator.vibrate(effect);
                        }
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
}
