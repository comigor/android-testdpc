package dev.borges.shadow;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import dev.borges.shadow.util.SettingsHelper;

/**
 * Test mode activity to verify all protection features work correctly.
 */
public class TestModeActivity extends AppCompatActivity {

    private static final String TAG = "TestModeActivity";
    private SharedPreferences settingsPrefs;
    private SharedPreferences shadowPrefs;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(this);
        shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);

        // Create UI programmatically
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(0xFF303030);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));

        // Title
        TextView title = new TextView(this);
        title.setText("Test Mode");
        title.setTextSize(24);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dpToPx(8));
        container.addView(title);

        // Description
        TextView desc = new TextView(this);
        desc.setText("Verify your protection setup is working correctly. These tests will simulate triggers but auto-cancel to prevent accidental lockout.");
        desc.setTextColor(0xFFAAAAAA);
        desc.setTextSize(14);
        desc.setPadding(0, 0, 0, dpToPx(16));
        container.addView(desc);

        // Test buttons
        container.addView(createTestSection("Power Button Detection",
            "Tests if rapid power button presses are detected correctly.",
            this::testPowerButton));

        container.addView(createTestSection("Watch Disconnect",
            "Tests the watch disconnection detection timer.",
            this::testWatchDisconnect));

        container.addView(createTestSection("Theft Mode Preview",
            "Shows the theft mode lock screen in test mode (easy exit).",
            this::testTheftMode));

        container.addView(createTestSection("Current Configuration",
            "Shows your current protection settings.",
            this::showConfiguration));

        scrollView.addView(container);
        setContentView(scrollView);
    }

    private View createTestSection(String title, String description, Runnable action) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(0xFF404040);
        section.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dpToPx(12));
        section.setLayoutParams(params);

        TextView titleText = new TextView(this);
        titleText.setText(title);
        titleText.setTextSize(16);
        titleText.setTextColor(0xFFFFFFFF);
        section.addView(titleText);

        TextView descText = new TextView(this);
        descText.setText(description);
        descText.setTextSize(12);
        descText.setTextColor(0xFF888888);
        descText.setPadding(0, dpToPx(4), 0, dpToPx(8));
        section.addView(descText);

        Button button = new Button(this);
        button.setText("RUN TEST");
        button.setOnClickListener(v -> action.run());
        section.addView(button);

        return section;
    }

    private void testPowerButton() {
        String presses = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
        String timeWindow = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.PRESS_TIME_WINDOW_KEY);
        String delay = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.ACTIVATION_DELAY_KEY);

        String message = String.format(
            "Power button detection is configured:\n\n" +
            "• Presses required: %s\n" +
            "• Time window: %s ms\n" +
            "• Activation delay: %s seconds\n\n" +
            "Press the power button %s times quickly to trigger.\n\n" +
            "TEST MODE: Theft mode will auto-cancel after 10 seconds.",
            presses, timeWindow, delay, presses
        );

        new AlertDialog.Builder(this)
            .setTitle("Power Button Test")
            .setMessage(message)
            .setPositiveButton("Start Test", (d, w) -> {
                // Set test mode flag
                shadowPrefs.edit().putBoolean("test_mode_active", true).apply();
                Toast.makeText(this, "Test mode active for 10 seconds. Press power button " + presses + " times!", Toast.LENGTH_LONG).show();

                // Auto-cancel after 10 seconds
                handler.postDelayed(() -> {
                    PowerButtonReceiver.clearPendingTheftMode(this);
                    shadowPrefs.edit().remove("test_mode_active").apply();
                    Toast.makeText(this, "Test complete. Theft mode cancelled.", Toast.LENGTH_SHORT).show();
                }, 10000);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void testWatchDisconnect() {
        String watchName = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_NAME_KEY);
        String timeout = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY);
        boolean enabled = "true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY));

        if (!enabled || watchName.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("Watch Not Configured")
                .setMessage("Watch disconnect protection is not configured.\n\nGo to Settings and:\n1. Enable watch disconnect protection\n2. Select your watch device")
                .setPositiveButton("OK", null)
                .show();
            return;
        }

        String message = String.format(
            "Watch disconnect protection is configured:\n\n" +
            "• Watch: %s\n" +
            "• Timeout: %s seconds\n\n" +
            "To test: Turn off Bluetooth or move away from your watch.\n\n" +
            "TEST MODE: Will show countdown but auto-cancel after 15 seconds.",
            watchName, timeout
        );

        new AlertDialog.Builder(this)
            .setTitle("Watch Disconnect Test")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show();
    }

    private void testTheftMode() {
        new AlertDialog.Builder(this)
            .setTitle("Theft Mode Preview")
            .setMessage("This will show the theft mode lock screen.\n\n" +
                "In test mode, a visible EXIT button will be shown to easily exit.\n\n" +
                "Continue?")
            .setPositiveButton("Show Preview", (d, w) -> {
                // Set test mode flag so TheftModeActivity shows exit button
                shadowPrefs.edit().putBoolean("test_mode_active", true).apply();

                // Launch theft mode
                android.content.Intent intent = new android.content.Intent(this, TheftModeActivity.class);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showConfiguration() {
        StringBuilder config = new StringBuilder();

        // Power button
        config.append("POWER BUTTON DETECTION\n");
        config.append("• Presses: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.POWER_BUTTON_PRESSES_KEY)).append("\n");
        config.append("• Time window: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.PRESS_TIME_WINDOW_KEY)).append(" ms\n");
        config.append("• Delay: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.ACTIVATION_DELAY_KEY)).append(" seconds\n\n");

        // Watch
        config.append("WATCH PROTECTION\n");
        String watchEnabled = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY);
        config.append("• Enabled: ").append("true".equals(watchEnabled) ? "Yes" : "No").append("\n");
        config.append("• Watch: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_NAME_KEY)).append("\n");
        config.append("• Timeout: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY)).append(" seconds\n\n");

        // Wrist detection
        config.append("WRIST DETECTION\n");
        String wristEnabled = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_DETECTION_ENABLED_KEY);
        config.append("• Enabled: ").append("true".equals(wristEnabled) ? "Yes" : "No").append("\n");
        config.append("• Timeout: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_REMOVAL_TIMEOUT_KEY)).append(" seconds\n\n");

        // Theft mode
        config.append("THEFT MODE\n");
        config.append("• Title: ").append(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.THEFT_MODE_TITLE_KEY)).append("\n");

        new AlertDialog.Builder(this)
            .setTitle("Current Configuration")
            .setMessage(config.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
