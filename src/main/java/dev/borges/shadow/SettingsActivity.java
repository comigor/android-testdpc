package dev.borges.shadow;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.biometric.BiometricManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.PolicyManagementActivity;
import com.afwsamples.testdpc.R;

import dev.borges.shadow.util.DevicePasswordHelper;
import dev.borges.shadow.util.DownloadHelper;
import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.SettingsHelper;
import dev.borges.shadow.util.ConfigBackup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends AuthenticatedActivity {

    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_SMS_PERMISSION = 100;

    private LinearLayout settingsContainer;
    private SharedPreferences sharedPreferences;
    private SharedPreferences shadowPrefs; // For non-settings data like decoy_serial
    private TextView debugTheftModeText;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponentName;
    private boolean isDeviceOwner = false;
    private DownloadHelper downloadHelper;
    private android.os.Handler theftModeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable theftModeUpdateRunnable;
    private Uri pendingImport;
    private final ActivityResultLauncher<String> exportConfig = registerForActivityResult(
        new ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE), uri -> {
            if (uri != null) writeExport(uri);
        });
    // The picker backgrounds the app, which locks the session; apply only after re-authentication.
    private final ActivityResultLauncher<String[]> importConfig = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> pendingImport = uri);

    @Override
    protected void onAuthenticatedCreate(Bundle savedInstanceState) {
        setContentView(R.layout.activity_settings);


        settingsContainer = findViewById(R.id.settings_container);
        sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this); // For settings
        shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE); // For non-settings data
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponentName = getAdminComponentName(this);
        downloadHelper = new DownloadHelper(this);

        isDeviceOwner = isDeviceOwnerApp(this);
        if (isDeviceOwner && getSystemService(UserManager.class).isSystemUser()) {
            try {
                devicePolicyManager.setSecurityLoggingEnabled(adminComponentName, true);
            } catch (SecurityException e) {
                Log.e(TAG, "Failed to enable security logging - admin may be invalid", e);
                isDeviceOwner = false; // Admin is broken, treat as non-device-owner
            }
        }

        setAffiliationIds();

        populateSettings();

        if (getSystemService(UserManager.class).isSystemUser()) {
            PowerButtonReceiver.registerReceiver(getApplicationContext());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!AdminSession.isAuthenticated() || settingsContainer == null) return;
        if (isDeviceOwner && getSystemService(UserManager.class).isSystemUser()) {
            devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_USER_SWITCH);
            // Keep decoy user running in background for faster switch
            DeviceAdminReceiver.startDecoyInBackground(this);
        }
        startTheftModeTimer();
        if (pendingImport != null) {
            Uri source = pendingImport;
            pendingImport = null;
            confirmImport(source);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTheftModeTimer();
    }

    private void populateSettings() {
        isDeviceOwner = isDeviceOwnerApp(this);
        settingsContainer.removeAllViews();

        // Debug text for theft mode timer
        addTheftModeDebugText();

        // Status dashboard at the top
        addStatusDashboard();

        if (isDeviceOwner) {
            // Feature sections as cards
            addFeatureSections();

            // Permissions section (inline)
            addTitle("Permissions");
            addDeviceOwnerSetting();
            addSmsPermissionSetting();

            // Extras section (inline)
            addTitle("Extras / dev");
            addTestModeSetting();
            addStealthModeSetting();
            addAppUpdateUrl();
            updateAppSetting();
            updateAppSetting2();
            addTestDPCSetting();
            addConfigBackupSettings();
        } else {
            addTitle("Decoy Profile");
            settingsContainer.addView(createClickableTextItem("Return to Owner",
                () -> startActivity(new Intent(this, DecoyProfileActivity.class))));

            addTitle("Permissions");
            addDeviceOwnerSetting();
        }

        // Version info at the end
        addVersionInfo();
    }

    private void addVersionInfo() {
        String version = "Unknown";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {}

        TextView versionText = new TextView(this);
        versionText.setText("Version " + version);
        versionText.setTextSize(12);
        versionText.setTextColor(0xFF666666);
        versionText.setGravity(android.view.Gravity.CENTER);
        versionText.setPadding(0, dpToPx(24), 0, dpToPx(16));
        settingsContainer.addView(versionText);
    }

    private void addFeatureSections() {
        // Decoy Profile
        settingsContainer.addView(createFeatureSection("Decoy Profile", () -> {
            long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
            UserHandle existingUser = decoySerial != -1 ? getSystemService(UserManager.class).getUserForSerialNumber(decoySerial) : null;
            return existingUser != null ? "Profile \"System\" active" : "Not configured";
        }, null, DecoyProfileActivity.class));

        // Protection Setup (no toggle)
        settingsContainer.addView(createFeatureSection("Protection Setup", () -> "Fingerprint, backups, location",
            null, ProtectionSetupActivity.class));

        // Power Off Prevention
        settingsContainer.addView(createFeatureSection("Power Off Prevention", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.POWER_OFF_PREVENTION_ENABLED_KEY));
            if (!enabled) return "Disabled";
            boolean accessibilityEnabled = POffService.isAccessibilityServiceEnabled(this);
            return accessibilityEnabled ? "Accessibility enabled" : "Accessibility not enabled";
        }, SettingsHelper.POWER_OFF_PREVENTION_ENABLED_KEY, PowerOffPreventionActivity.class));

        // Theft Mode
        settingsContainer.addView(createFeatureSection("Theft Mode", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_ENABLED_KEY));
            if (!enabled) return "Disabled";
            int hiddenApps = HiddenAppsActivity.getAppsToHide(this).size();
            String presses = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
            return hiddenApps + " apps hidden, " + presses + " presses";
        }, SettingsHelper.THEFT_MODE_ENABLED_KEY, TheftModeSettingsActivity.class));

        // Auto-Kill Apps
        settingsContainer.addView(createFeatureSection("Auto-Kill Apps", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.AUTO_KILL_ENABLED_KEY));
            if (!enabled) return "Disabled";
            int count = AutoKillAppsActivity.getAppsToAutoKill(this).size();
            return count + " apps selected";
        }, SettingsHelper.AUTO_KILL_ENABLED_KEY, AutoKillAppsActivity.class));

        // Watch Protection
        settingsContainer.addView(createFeatureSection("Watch Protection", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY));
            if (!enabled) return "Disabled";
            String watchName = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_NAME_KEY);
            return watchName.isEmpty() ? "No watch selected" : watchName;
        }, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY, WatchProtectionActivity.class));

        // Wrist Detection
        settingsContainer.addView(createFeatureSection("Wrist Detection", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WRIST_DETECTION_ENABLED_KEY));
            if (!enabled) return "Disabled";
            String timeout = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WRIST_REMOVAL_TIMEOUT_KEY);
            return timeout + "s timeout";
        }, SettingsHelper.WRIST_DETECTION_ENABLED_KEY, WristDetectionActivity.class));

        // FRP
        settingsContainer.addView(createFeatureSection("Factory Reset Protection", () -> {
            boolean enabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ENABLED_KEY));
            if (!enabled) return "Disabled";
            String accounts = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
            long count = java.util.Arrays.stream(accounts.split(",")).map(String::trim).filter(s -> s.length() == 21).count();
            return count + " accounts configured";
        }, SettingsHelper.FRP_ENABLED_KEY, FRPSettingsActivity.class));

        // Protected Apps (toggle lives in its config screen: needs PIN check + launcher icon sync)
        settingsContainer.addView(createFeatureSection("Protected Apps", () -> {
            if (!ProtectedApps.isEnabled(this)) return "Disabled";
            int count = ProtectedApps.getPackages(this).size();
            return count + " apps, " + ProtectedApps.getWindowMinutes(this) + " min window";
        }, null, ProtectedAppsSettingsActivity.class));
    }

    private void addTestModeSetting() {
        View textView = createClickableTextItem("Test Mode", () ->
            startActivity(new Intent(this, TestModeActivity.class)));
        settingsContainer.addView(textView);
    }

    private void addStealthModeSetting() {
        boolean isEnabled = StealthModeManager.isStealthModeEnabled(this);
        View switchCompat = createSwitchItem("Stealth Mode", isEnabled, true, (buttonView, newChecked) -> {
            if (newChecked) {
                // Show warning before enabling
                new AlertDialog.Builder(this)
                    .setTitle("Enable Stealth Mode")
                    .setMessage("This will hide the app from the launcher.\n\n" +
                        "To access the app, dial:\n*#*#742369#*#*\n\n" +
                        "Make sure you remember this code!")
                    .setPositiveButton("Enable", (d, w) -> {
                        StealthModeManager.enableStealthMode(this);
                        Toast.makeText(this, "Stealth mode enabled. Dial *#*#742369#*#* to open.", Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", (d, w) -> {
                        // Reset the switch
                        ((SwitchCompat) buttonView).setChecked(false);
                    })
                    .show();
            } else {
                StealthModeManager.disableStealthMode(this);
                Toast.makeText(this, "Stealth mode disabled", Toast.LENGTH_SHORT).show();
            }
        });

        // Add description below the switch
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(createRow(switchCompat, null));

        TextView desc = new TextView(this);
        desc.setText("Hide app from launcher. Access via dialer: *#*#742369#*#*");
        desc.setTextSize(11);
        desc.setTextColor(0xFF888888);
        desc.setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(8));
        container.addView(desc);

        settingsContainer.addView(container);
    }

    private LinearLayout theftModeBanner;

    private void addTheftModeDebugText() {
        // Create banner container
        theftModeBanner = new LinearLayout(this);
        theftModeBanner.setOrientation(LinearLayout.HORIZONTAL);
        theftModeBanner.setBackgroundColor(0x33FF0000); // Light red background
        theftModeBanner.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        theftModeBanner.setGravity(Gravity.CENTER_VERTICAL);

        // Text showing countdown
        debugTheftModeText = new TextView(this);
        debugTheftModeText.setTextColor(0xFFFF5555); // Red
        debugTheftModeText.setTextSize(14);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        debugTheftModeText.setLayoutParams(textParams);
        theftModeBanner.addView(debugTheftModeText);

        // Cancel button
        TextView cancelBtn = new TextView(this);
        cancelBtn.setText("CANCEL");
        cancelBtn.setTextColor(0xFFFFFFFF);
        cancelBtn.setTextSize(12);
        cancelBtn.setBackgroundColor(0xFFCC0000);
        cancelBtn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        cancelBtn.setOnClickListener(v -> {
            PowerButtonReceiver.clearPendingTheftMode(this);
            updateTheftModeDebugText();
            Toast.makeText(this, "Theft mode cancelled", Toast.LENGTH_SHORT).show();
        });
        theftModeBanner.addView(cancelBtn);

        updateTheftModeDebugText();
        settingsContainer.addView(theftModeBanner);
    }

    private void updateTheftModeDebugText() {
        if (theftModeBanner == null || debugTheftModeText == null) return;

        long activationTime = shadowPrefs.getLong("theft_mode_activation_time", 0);
        if (activationTime > 0) {
            long now = System.currentTimeMillis();
            long remaining = (activationTime - now) / 1000;
            if (remaining > 0) {
                debugTheftModeText.setText("THEFT MODE IN " + remaining + "s");
                theftModeBanner.setVisibility(View.VISIBLE);
            } else {
                debugTheftModeText.setText("THEFT MODE PENDING");
                theftModeBanner.setVisibility(View.VISIBLE);
            }
        } else {
            theftModeBanner.setVisibility(View.GONE);
        }
    }

    private void startTheftModeTimer() {
        theftModeUpdateRunnable = () -> {
            updateTheftModeDebugText();
            PowerButtonReceiver.checkPendingTheftMode(this);
            theftModeHandler.postDelayed(theftModeUpdateRunnable, 1000);
        };
        theftModeHandler.post(theftModeUpdateRunnable);
    }

    private void stopTheftModeTimer() {
        if (theftModeUpdateRunnable != null) {
            theftModeHandler.removeCallbacks(theftModeUpdateRunnable);
        }
    }

    private void addStatusDashboard() {
        // Dashboard container with dark background
        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        dashboard.setBackgroundColor(0xFF1A1A1A);
        dashboard.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        LinearLayout.LayoutParams dashParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dashParams.setMargins(0, 0, 0, dpToPx(8));
        dashboard.setLayoutParams(dashParams);

        // Title
        TextView title = new TextView(this);
        title.setText("PROTECTION STATUS");
        title.setTextColor(0xFF888888);
        title.setTextSize(12);
        title.setPadding(0, 0, 0, dpToPx(8));
        dashboard.addView(title);

        // Get all statuses
        ProtectionStatusChecker checker = new ProtectionStatusChecker(this);
        List<ProtectionStatusChecker.Status> statuses = checker.checkAllStatuses();

        // Create status grid (2 columns)
        LinearLayout row = null;
        for (int i = 0; i < statuses.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                dashboard.addView(row);
            }

            ProtectionStatusChecker.Status status = statuses.get(i);
            View statusView = createStatusItem(status);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(0, dpToPx(4), dpToPx(8), dpToPx(4));
            statusView.setLayoutParams(params);
            row.addView(statusView);
        }

        // Add odd spacer if needed
        if (statuses.size() % 2 != 0 && row != null) {
            View spacer = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1);
            spacer.setLayoutParams(params);
            row.addView(spacer);
        }

        settingsContainer.addView(dashboard);
    }

    private View createStatusItem(ProtectionStatusChecker.Status status) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);

        // Status indicator dot
        TextView dot = new TextView(this);
        int color;
        switch (status.state) {
            case OK: color = 0xFF4CAF50; break;       // Green
            case WARNING: color = 0xFFFF9800; break;  // Orange
            case ERROR: color = 0xFFF44336; break;    // Red
            default: color = 0xFF666666; break;       // Gray
        }
        dot.setText("\u25CF "); // Filled circle
        dot.setTextColor(color);
        dot.setTextSize(10);
        item.addView(dot);

        // Label and status
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(status.label);
        label.setTextColor(0xFFCCCCCC);
        label.setTextSize(12);
        textContainer.addView(label);

        TextView statusText = new TextView(this);
        statusText.setText(status.statusText);
        statusText.setTextColor(color);
        statusText.setTextSize(10);
        textContainer.addView(statusText);

        item.addView(textContainer);
        return item;
    }
    private void addTitle(String title) {
        TextView titleTextView = new TextView(this);
        titleTextView.setText(title);
        titleTextView.setTextAppearance(this, android.R.style.TextAppearance_Medium);
        titleTextView.setTextSize(18);
        titleTextView.setPadding(0, dpToPx(16), 0, dpToPx(8));
        settingsContainer.addView(titleTextView);
    }

    private void addDeviceOwnerSetting() {
        boolean isDeviceOwner = isDeviceOwnerApp(this);
        View switchCompat = createSwitchItem("Device Owner", isDeviceOwner, false, (buttonView, isChecked) -> showDeviceOwnerExplanationDialog());
        View row = createRow(switchCompat, this::showDeviceOwnerExplanationDialog);
        settingsContainer.addView(row);
    }

    private void addSmsPermissionSetting() {
        boolean isSmsEnabled = checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
        View switchCompat = createSwitchItem("SMS Permissions", isSmsEnabled, !isSmsEnabled, (buttonView, isChecked) -> requestSmsPermissions());
        View row = createRow(switchCompat, this::requestSmsPermissions);
        settingsContainer.addView(row);
    }

    private void addTestDPCSetting() {
        View textView = createClickableTextItem("Policy Management", () -> startActivity(new Intent(this, PolicyManagementActivity.class)));
        settingsContainer.addView(textView);
    }

    private void addAppUpdateUrl() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.APP_UPDATE_URL);
        View editText = createTextEditItem("App update URL", value, value, text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.APP_UPDATE_URL, text));
        settingsContainer.addView(editText);
    }

    private void updateAppSetting() {
        String url = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.APP_UPDATE_URL);
        View textView = createClickableTextItem("Update app", () -> downloadHelper.downloadAndInstallApk(this, url));
        settingsContainer.addView(textView);
    }

    private void updateAppSetting2() {
        String url = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.APP_UPDATE_URL);
        View textView = createClickableTextItem("Update app manually", () -> {
            Uri webpage = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Log.w(TAG, "No web browser found.");
            }
        });
        settingsContainer.addView(textView);
    }

    private void addConfigBackupSettings() {
        settingsContainer.addView(createClickableTextItem("Export configuration", () ->
            exportConfig.launch("shadow-config-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new java.util.Date()) + ".json")));
        settingsContainer.addView(createClickableTextItem("Import configuration", () ->
            importConfig.launch(new String[]{ConfigBackup.MIME_TYPE, "text/plain", "application/octet-stream"})));
    }

    private void writeExport(Uri target) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            out.write(ConfigBackup.export(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, "Configuration exported", Toast.LENGTH_SHORT).show();
        } catch (java.io.IOException | RuntimeException e) {
            Log.e(TAG, "Export failed", e);
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmImport(Uri source) {
        String json;
        try (java.io.InputStream in = getContentResolver().openInputStream(source)) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, "Could not read file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Import configuration")
            .setMessage("This replaces all settings and app lists with the file contents. "
                + "Passwords and the Vault PIN are not affected.")
            .setPositiveButton("Import", (d, w) -> {
                try {
                    ConfigBackup.Result result = ConfigBackup.importConfig(this, json);
                    String message = result.applied + " settings imported";
                    if (!result.warnings.isEmpty()) {
                        message += "\n\n" + String.join("\n", result.warnings);
                    }
                    new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Import failed", e);
                    Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
                populateSettings();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    //region Helper Methods for UI Elements
    private View createSwitchItem(String label, boolean isChecked, boolean isEnabled, CompoundButton.OnCheckedChangeListener onCheckedChange) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(0, dpToPx(8), 0, dpToPx(8));

        TextView labelTextView = new TextView(this);
        labelTextView.setText(label);
        labelTextView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        layout.addView(labelTextView);

        SwitchCompat switchCompat = new SwitchCompat(this);
        switchCompat.setChecked(isChecked);
        switchCompat.setEnabled(isEnabled);
        switchCompat.setOnCheckedChangeListener(onCheckedChange);
        layout.addView(switchCompat);

        return layout;
    }

    private View createRow(View content, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(content);
        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> onClick.run());
        }
        return row;
    }

    private TextView createClickableTextItem(String label, Runnable onClick) {
        TextView textView = new TextView(this);
        textView.setText(label);
        textView.setTextAppearance(this, android.R.style.TextAppearance_Medium);
        textView.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        textView.setClickable(true);
        textView.setFocusable(true);
        textView.setOnClickListener(v -> onClick.run());
        return textView;
    }

    private View createTextEditItem(String label, String hint, String initialValue, Consumer<String> onTextChanged) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));

        TextView labelTextView = new TextView(this);
        labelTextView.setText(label);
        layout.addView(labelTextView);

        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setText(initialValue);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s.toString());
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
        layout.addView(editText);
        return layout;
    }

    /**
     * Creates a feature section card with title, subtitle, optional toggle, and click to open config Activity.
     * @param title Feature name
     * @param subtitleProvider Provides the subtitle text (called on each refresh)
     * @param enableKey Settings key for enable/disable toggle (null for no toggle)
     * @param configActivity Activity class to open when clicked
     */
    private View createFeatureSection(String title, java.util.function.Supplier<String> subtitleProvider,
                                       String enableKey, Class<?> configActivity) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClickable(true);
        card.setFocusable(true);

        // Left side: title and subtitle
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        textContainer.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitleProvider.get());
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(0xFFAAAAAA);
        textContainer.addView(subtitleView);

        card.addView(textContainer);

        // Right side: toggle switch (if enableKey provided)
        if (enableKey != null) {
            SwitchCompat toggle = new SwitchCompat(this);
            boolean isEnabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, enableKey));
            toggle.setChecked(isEnabled);
            toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SettingsHelper.setSetting(sharedPreferences, enableKey, isChecked ? "true" : "false");
                subtitleView.setText(subtitleProvider.get());
            });
            card.addView(toggle);
        }

        // Click to open config activity
        card.setOnClickListener(v -> {
            if (configActivity != null) {
                startActivity(new Intent(this, configActivity));
            }
        });

        return card;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
    //endregion

    //region Permission Handling
    private boolean isDeviceOwnerApp(Context context) {
        if (devicePolicyManager == null || adminComponentName == null) {
            return false;
        }
        if (!devicePolicyManager.isDeviceOwnerApp(context.getPackageName())) {
            return false;
        }
        // Verify we can actually use device owner APIs (UID must match)
        try {
            devicePolicyManager.isBackupServiceEnabled(adminComponentName);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Device owner APIs not functional - UID mismatch?", e);
            return false;
        }
    }

    private ComponentName getAdminComponentName(Context context) {
        return new ComponentName(context, DeviceAdminReceiver.class);
    }

    private void showDeviceOwnerExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Set as Device Owner")
                .setMessage("To enable full functionality, this app needs to be set as the Device Owner. This typically requires using ADB commands. Please refer to the app's documentation for detailed instructions.")
                .setPositiveButton("OK", null)
                .setIcon(android.R.drawable.ic_dialog_info)
                .show();
    }

    private void requestSmsPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, REQUEST_SMS_PERMISSION);
        } else {
            Log.i(TAG, "SMS permissions already granted");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "SMS permissions granted");
            } else {
                Log.w(TAG, "SMS permissions denied");
            }
            populateSettings();
        }
    }

    private static final String AFFILIATION_ID = "shadow_affiliation";

    private void setAffiliationIds() {
        // Set affiliation ID so device owner and profile owner can communicate
        try {
            if (devicePolicyManager.isDeviceOwnerApp(getPackageName()) ||
                devicePolicyManager.isProfileOwnerApp(getPackageName())) {
                Set<String> ids = new HashSet<>();
                ids.add(AFFILIATION_ID);
                devicePolicyManager.setAffiliationIds(adminComponentName, ids);
                Log.i(TAG, "Affiliation ID set: " + AFFILIATION_ID);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set affiliation IDs", e);
        }
    }
}
