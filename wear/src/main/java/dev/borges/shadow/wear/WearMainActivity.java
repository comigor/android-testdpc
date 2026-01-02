package dev.borges.shadow.wear;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Main activity for the Wear OS app.
 * Provides a simple UI to start/stop wrist detection monitoring.
 *
 * CRITICAL: Handles Wear OS 4+ split permission workflow:
 * 1. First request BODY_SENSORS (foreground only)
 * 2. Then guide user to Settings for "Allow all the time" (background)
 */
public class WearMainActivity extends Activity {
    private static final int PERMISSION_REQUEST_BODY_SENSORS = 100;
    private static final int PERMISSION_REQUEST_BODY_SENSORS_BACKGROUND = 101;
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 102;
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 103;

    private TextView statusText;
    private TextView permissionStatusText;
    private Button toggleButton;
    private Button permissionButton;
    private boolean isMonitoring = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUI();
        updatePermissionStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Check if service is already running
        isMonitoring = WristDetectionService.isServiceRunning();
        updateUI();
        updatePermissionStatus();

        // Auto-start monitoring if all permissions are granted and not already running
        if (!isMonitoring && hasAllPermissions()) {
            startMonitoring();
        }
    }

    private boolean hasAllPermissions() {
        return hasForegroundSensorPermission() &&
               hasBackgroundSensorPermission() &&
               hasNotificationPermission() &&
               hasBatteryOptimizationExemption();
    }

    private void buildUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(24, 24, 24, 24);

        // Title
        TextView titleText = new TextView(this);
        titleText.setText(R.string.app_name);
        titleText.setTextSize(16);
        titleText.setGravity(Gravity.CENTER);
        layout.addView(titleText);

        addSpacer(layout, 16);

        // Status text
        statusText = new TextView(this);
        statusText.setText(R.string.status_stopped);
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        layout.addView(statusText);

        addSpacer(layout, 8);

        // Sensor availability
        TextView sensorText = new TextView(this);
        boolean sensorAvailable = WristDetectionService.isOffBodySensorAvailable(this);
        sensorText.setText(sensorAvailable ? "Sensor: Available" : "Sensor: NOT AVAILABLE");
        sensorText.setTextSize(11);
        sensorText.setGravity(Gravity.CENTER);
        if (!sensorAvailable) {
            sensorText.setTextColor(0xFFFF4444);
        }
        layout.addView(sensorText);

        addSpacer(layout, 8);

        // Permission status
        permissionStatusText = new TextView(this);
        permissionStatusText.setTextSize(10);
        permissionStatusText.setGravity(Gravity.CENTER);
        layout.addView(permissionStatusText);

        addSpacer(layout, 16);

        // Permission button (shown if permissions needed)
        permissionButton = new Button(this);
        permissionButton.setText("Grant Permissions");
        permissionButton.setTextSize(12);
        permissionButton.setOnClickListener(v -> handlePermissionButton());
        layout.addView(permissionButton);

        addSpacer(layout, 8);

        // Toggle button
        toggleButton = new Button(this);
        toggleButton.setText(R.string.start_monitoring);
        toggleButton.setTextSize(12);
        toggleButton.setOnClickListener(v -> toggleMonitoring());
        layout.addView(toggleButton);

        setContentView(layout);
    }

    private void addSpacer(LinearLayout layout, int heightDp) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, heightDp));
        layout.addView(spacer);
    }

    private void updatePermissionStatus() {
        boolean hasForeground = hasForegroundSensorPermission();
        boolean hasBackground = hasBackgroundSensorPermission();
        boolean hasNotification = hasNotificationPermission();
        boolean hasBatteryExempt = hasBatteryOptimizationExemption();

        StringBuilder sb = new StringBuilder();

        if (hasForeground && hasBackground) {
            sb.append("Sensors: OK\n");
        } else if (hasForeground) {
            sb.append("Sensors: Foreground only!\n");
        } else {
            sb.append("Sensors: DENIED\n");
        }

        sb.append("Notifications: ").append(hasNotification ? "OK" : "DENIED").append("\n");
        sb.append("Battery: ").append(hasBatteryExempt ? "Unrestricted" : "Restricted");

        permissionStatusText.setText(sb.toString());

        // Color based on status
        if (hasForeground && hasBackground && hasNotification && hasBatteryExempt) {
            permissionStatusText.setTextColor(0xFF44FF44); // Green
            permissionButton.setVisibility(View.GONE);
        } else if (hasForeground) {
            permissionStatusText.setTextColor(0xFFFFAA00); // Orange
            permissionButton.setVisibility(View.VISIBLE);
            permissionButton.setText("Enable Background Access");
        } else {
            permissionStatusText.setTextColor(0xFFFF4444); // Red
            permissionButton.setVisibility(View.VISIBLE);
            permissionButton.setText("Grant Sensor Permission");
        }

        // Enable/disable toggle based on permissions
        toggleButton.setEnabled(hasForeground);
    }

    private boolean hasForegroundSensorPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundSensorPermission() {
        // BODY_SENSORS_BACKGROUND was added in API 33 (Android 13 / Wear OS 4)
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, "android.permission.BODY_SENSORS_BACKGROUND")
                == PackageManager.PERMISSION_GRANTED;
        }
        // Before API 33, foreground permission was sufficient
        return true;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private boolean hasBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return false;
    }

    private void handlePermissionButton() {
        if (!hasForegroundSensorPermission()) {
            // Step 1: Request foreground sensor permission
            requestForegroundSensorPermission();
        } else if (!hasBackgroundSensorPermission()) {
            // Step 2: Guide user to enable background sensor access
            showBackgroundPermissionDialog();
        } else if (!hasNotificationPermission()) {
            // Step 3: Request notification permission
            requestNotificationPermission();
        } else if (!hasBatteryOptimizationExemption()) {
            // Step 4: Request battery optimization exemption
            requestBatteryExemption();
        }
    }

    /**
     * Step 1: Request BODY_SENSORS permission (foreground only on Wear OS 4+)
     */
    private void requestForegroundSensorPermission() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.BODY_SENSORS},
            PERMISSION_REQUEST_BODY_SENSORS);
    }

    /**
     * Step 2: On Wear OS 4+, BODY_SENSORS_BACKGROUND cannot be requested via prompt.
     * User MUST go to Settings and select "Allow all the time".
     */
    private void showBackgroundPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Background Access Required")
            .setMessage("For 24/7 wrist detection, you must allow sensor access 'All the time'.\n\n" +
                "Tap OK to open Settings, then select:\n" +
                "Permissions → Body Sensors → Allow all the time")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                openAppSettings();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    /**
     * Step 3: Request notification permission (Android 13+)
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                PERMISSION_REQUEST_NOTIFICATIONS);
        }
    }

    /**
     * Step 4: Request battery optimization exemption for reliable background operation
     */
    private void requestBatteryExemption() {
        // On Wear OS, show dialog with instructions and open app info
        new AlertDialog.Builder(this)
            .setTitle("Battery Settings")
            .setMessage("To enable background access:\n\n" +
                "1. Tap 'Open'\n" +
                "2. Scroll down and tap 'Battery'\n" +
                "3. Select 'Unrestricted'")
            .setPositiveButton("Open", (dialog, which) -> {
                try {
                    // Open app info page where Battery setting is accessible
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Open Settings > Apps > Shadow Watch > Battery", Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_BODY_SENSORS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Sensor permission granted", Toast.LENGTH_SHORT).show();

                // On Wear OS 4+, now prompt for background access
                if (Build.VERSION.SDK_INT >= 33 && !hasBackgroundSensorPermission()) {
                    // Small delay to let the first dialog close
                    toggleButton.postDelayed(this::showBackgroundPermissionDialog, 500);
                }
            } else {
                Toast.makeText(this, "Sensor permission DENIED - wrist detection won't work!", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PERMISSION_REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
            }
        }

        updatePermissionStatus();
    }

    private void toggleMonitoring() {
        if (isMonitoring) {
            stopMonitoring();
        } else {
            startMonitoring();
        }
    }

    private void startMonitoring() {
        // Check sensor availability
        if (!WristDetectionService.isOffBodySensorAvailable(this)) {
            Toast.makeText(this, "Off-body sensor not available!", Toast.LENGTH_LONG).show();
            return;
        }

        // Check foreground permission
        if (!hasForegroundSensorPermission()) {
            requestForegroundSensorPermission();
            return;
        }

        // Warn if background permission is missing (Wear OS 4+)
        if (Build.VERSION.SDK_INT >= 33 && !hasBackgroundSensorPermission()) {
            new AlertDialog.Builder(this)
                .setTitle("Warning")
                .setMessage("Background sensor access not granted. Wrist detection may stop when screen turns off.\n\n" +
                    "Continue anyway?")
                .setPositiveButton("Start Anyway", (dialog, which) -> {
                    doStartMonitoring();
                })
                .setNegativeButton("Grant Permission", (dialog, which) -> {
                    showBackgroundPermissionDialog();
                })
                .show();
            return;
        }

        doStartMonitoring();
    }

    private void doStartMonitoring() {
        Intent serviceIntent = new Intent(this, WristDetectionService.class);
        startForegroundService(serviceIntent);

        isMonitoring = true;
        updateUI();
        Toast.makeText(this, "Wrist monitoring started", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        Intent serviceIntent = new Intent(this, WristDetectionService.class);
        stopService(serviceIntent);

        isMonitoring = false;
        updateUI();
        Toast.makeText(this, "Wrist monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        if (isMonitoring) {
            statusText.setText(R.string.status_monitoring);
            toggleButton.setText(R.string.stop_monitoring);
        } else {
            statusText.setText(R.string.status_stopped);
            toggleButton.setText(R.string.start_monitoring);
        }
    }
}
