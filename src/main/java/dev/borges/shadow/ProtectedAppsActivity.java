package dev.borges.shadow;

import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean foreground;
    private boolean verifying;
    private int attemptGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

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
        pinInput.setSaveEnabled(false);
        pinInput.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
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
        foreground = true;
        ProtectedApps.enforce(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        attemptGeneration++;
        verifying = false;
        pinInput.setText("");
        stopCountdown();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void attempt() {
        if (verifying) return;
        String pin = pinInput.getText().toString();
        pinInput.setText("");
        if (pin.isEmpty()) return;
        verifying = true;
        submit.setEnabled(false);
        int generation = ++attemptGeneration;
        worker.execute(() -> {
            try {
                boolean accepted = ProtectedApps.verifyPin(this, pin);
                runOnUiThread(() -> {
                    if (!foreground || generation != attemptGeneration) return;
                    verifying = false;
                    try {
                        if (accepted) {
                            ProtectedApps.reveal(this);
                            finish();
                        } else {
                            render();
                            if (ProtectedApps.getBackoffRemainingMs(this) == 0) status.setText("Incorrect PIN");
                        }
                    } catch (RuntimeException e) {
                        render();
                        status.setText(e.getMessage());
                    }
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (!foreground || generation != attemptGeneration) return;
                    verifying = false;
                    status.setText("PIN storage unavailable. Protected apps remain locked.");
                    submit.setEnabled(false);
                });
            }
        });
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
        if (TheftModeState.isActive(this)) {
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
            startCountdown(ProtectedApps.getRemainingWindowMs(this), "Unlocked for ", () -> {
                ProtectedApps.enforce(this);
                render();
            });
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
                status.setText(prefix + String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60));
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
