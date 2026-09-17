package dev.borges.shadow;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Launcher entry point: enter the protected-apps PIN to reveal them for the configured window. */
public class ProtectedAppsActivity extends AppCompatActivity {

    private TextView status;
    private EditText pinInput;
    private Button submit;
    private Button lockNow;
    private CountDownTimer countdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pinInput = new EditText(this);
        pinInput.setHint("PIN");
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(16);
        root.addView(pinInput, inputParams);

        submit = new Button(this);
        submit.setText("Unlock");
        submit.setOnClickListener(v -> attempt());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(16);
        root.addView(submit, buttonParams);

        lockNow = new Button(this);
        lockNow.setText("Lock now");
        lockNow.setOnClickListener(v -> {
            ProtectedApps.lock(this);
            finish();
        });
        root.addView(lockNow, buttonParams);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCountdown();
    }

    private void attempt() {
        String pin = pinInput.getText().toString();
        pinInput.setText("");
        if (pin.isEmpty()) {
            return;
        }
        if (ProtectedApps.verifyPin(this, pin)) {
            ProtectedApps.reveal(this);
            finish();
        } else {
            render();
        }
    }

    private void render() {
        stopCountdown();
        if (!ProtectedApps.isEnabled(this) || !ProtectedApps.isPinSet(this)) {
            status.setText("Protected apps are not configured.");
            pinInput.setEnabled(false);
            submit.setEnabled(false);
            lockNow.setEnabled(false);
            return;
        }
        if (PowerButtonReceiver.isTheftModePending(this)) {
            status.setText("Unavailable.");
            pinInput.setEnabled(false);
            submit.setEnabled(false);
            lockNow.setEnabled(false);
            return;
        }
        if (ProtectedApps.isUnlocked(this)) {
            pinInput.setEnabled(false);
            submit.setEnabled(false);
            lockNow.setEnabled(true);
            startCountdown(ProtectedApps.getUnlockUntil(this) - System.currentTimeMillis(), "Unlocked for ", () -> render());
            return;
        }
        lockNow.setEnabled(false);
        long backoff = ProtectedApps.getBackoffRemainingMs(this);
        if (backoff > 0) {
            pinInput.setEnabled(false);
            submit.setEnabled(false);
            startCountdown(backoff, "Too many attempts. Try again in ", () -> render());
            return;
        }
        pinInput.setEnabled(true);
        submit.setEnabled(true);
        status.setText("Enter PIN to unlock protected apps for " + ProtectedApps.getWindowMinutes(this) + " min.");
    }

    private void startCountdown(long millis, String prefix, Runnable onDone) {
        countdown = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long remaining) {
                long total = remaining / 1000;
                status.setText(prefix + String.format("%d:%02d", total / 60, total % 60));
            }

            @Override
            public void onFinish() {
                onDone.run();
            }
        }.start();
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.cancel();
            countdown = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
