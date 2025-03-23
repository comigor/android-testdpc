package com.afwsamples.testdpc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import java.io.PrintWriter;
import android.os.CountDownTimer;
import android.os.Vibrator;
import android.os.VibrationEffect;
import com.afwsamples.testdpc.policy.locktask.TheftModeActivity;

public class PowerButtonReceiver extends BroadcastReceiver {
    private long lastPressTime = 0;
    private int pressCount = 0;

    private long timeWindow = 2000;
    private int numberOfPresses = 4;
    private long delayToStartMode = 10 * 1000; // 5 minutes

    @Override
    public void onReceive(final Context context, final Intent intent) {
        Log.e("igor", "igor onReceive");
        
        if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF) || intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
            Log.e("igor", "igor " + intent.getAction());
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastPressTime < timeWindow) {
                pressCount++;
                if (pressCount == numberOfPresses) {
                    Log.e("igor",  "igor Power button pressed 4 times in quick succession, theft mode in " + delayToStartMode);
                    
                    Context cntx = context;
                    Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vibrator != null) {
                        long[] timings = {0, 100, 200, 100, 200, 100}; //Timings in milliseconds: delay, duration, delay, duration, etc.
                        int[] amplitudes = {0, 255, 0, 255, 0, 255}; // Amplitudes: 0 (off), 255 (max), etc.

                        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1); // -1 means no repeat
                        vibrator.vibrate(effect);
                    }

                    new CountDownTimer(delayToStartMode, 200) {
                        public void onTick(long millisUntilFinished) {
                            Log.e("igor",  "igor will theft");
                        }

                        public void onFinish() {
                            Log.e("igor",  "igor starting theft");
                            PrintWriter writer = new PrintWriter(System.out);
                            ShellCommand shellCommand = new ShellCommand(cntx, writer, new String[] {"start-theft-mode"});
                            shellCommand.run();
                        }
                    }.start();
                }
            } else {
                pressCount = 1;
            }
            lastPressTime = currentTime;

            // Log.e("igor", "igor igor userpresent");
            // Log.e("igor", "igor igor wasScreenOn"+wasScreenOn);
        }
    }
}
