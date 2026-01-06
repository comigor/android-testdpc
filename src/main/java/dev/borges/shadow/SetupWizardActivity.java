package dev.borges.shadow;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.DevicePolicyManagerGateway;
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.SettingsHelper;

public class SetupWizardActivity extends AppCompatActivity {
    private static final String TAG = "SetupWizardActivity";

    private ScrollView contentScrollView;
    private Button btnBack;
    private Button btnNext;
    private TextView stepIndicator;
    private ProgressBar progressBar;

    private SharedPreferences settingsPrefs;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private DevicePolicyManagerGateway gateway;

    private List<WizardStep> steps = new ArrayList<>();
    private int currentStep = 0;

    // Input fields that need to be accessed across steps
    private EditText passwordField;
    private EditText confirmPasswordField;
    private Spinner watchSpinner;
    private List<BluetoothDevice> pairedDevices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(this);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = DeviceAdminReceiver.getComponentName(this);
        gateway = new DevicePolicyManagerGatewayImpl(this);

        // Check if wizard already completed
        if ("true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WIZARD_COMPLETED_KEY))) {
            finish();
            return;
        }

        buildUI();
        buildSteps();
        showStep(0);
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(4));
        root.addView(progressBar, progressParams);

        // Step indicator
        stepIndicator = new TextView(this);
        stepIndicator.setTextColor(0xFF888888);
        stepIndicator.setTextSize(14);
        stepIndicator.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(8));
        root.addView(stepIndicator);

        // Content area (scrollable)
        contentScrollView = new ScrollView(this);
        contentScrollView.setId(View.generateViewId());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(contentScrollView, scrollParams);

        // Button bar
        LinearLayout buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.HORIZONTAL);
        buttonBar.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        buttonBar.setBackgroundColor(0xFF252525);

        btnBack = new Button(this);
        btnBack.setText("BACK");
        btnBack.setOnClickListener(v -> goBack());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMargins(0, 0, dpToPx(8), 0);
        buttonBar.addView(btnBack, btnParams);

        btnNext = new Button(this);
        btnNext.setText("NEXT");
        btnNext.setOnClickListener(v -> goNext());
        LinearLayout.LayoutParams btnParams2 = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnParams2.setMargins(dpToPx(8), 0, 0, 0);
        buttonBar.addView(btnNext, btnParams2);

        root.addView(buttonBar);

        setContentView(root);
    }

    private void buildSteps() {
        steps.clear();

        // Step 1: Welcome
        steps.add(new WizardStep("Welcome", "Welcome to Shadow Setup", this::buildWelcomeStep, null, false));

        // Step 2: Set Password
        steps.add(new WizardStep("Password", "Set App Password", this::buildPasswordStep, this::validatePassword, true));

        // Step 3: Device Owner
        steps.add(new WizardStep("Device Owner", "Device Owner Setup", this::buildDeviceOwnerStep, null, false));

        // Step 4: Decoy Profile
        steps.add(new WizardStep("Decoy Profile", "Create Decoy Profile", this::buildDecoyStep, null, false));

        // Step 5: Power Button
        steps.add(new WizardStep("Power Button", "Power Button Detection", this::buildPowerButtonStep, null, false));

        // Step 6: Watch (optional)
        steps.add(new WizardStep("Watch", "Watch Protection (Optional)", this::buildWatchStep, null, false));

        // Step 7: Accessibility
        steps.add(new WizardStep("Accessibility", "Accessibility Service", this::buildAccessibilityStep, null, false));

        // Step 8: Summary
        steps.add(new WizardStep("Complete", "Setup Complete", this::buildSummaryStep, null, false));
    }

    private void showStep(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= steps.size()) return;

        currentStep = stepIndex;
        WizardStep step = steps.get(stepIndex);

        // Update progress
        int progress = (int) ((stepIndex / (float) (steps.size() - 1)) * 100);
        progressBar.setProgress(progress);
        stepIndicator.setText("Step " + (stepIndex + 1) + " of " + steps.size() + ": " + step.shortTitle);

        // Update buttons
        btnBack.setVisibility(stepIndex > 0 ? View.VISIBLE : View.INVISIBLE);
        if (stepIndex == steps.size() - 1) {
            btnNext.setText("FINISH");
        } else {
            btnNext.setText(step.required ? "NEXT" : "NEXT / SKIP");
        }

        // Build content
        contentScrollView.removeAllViews();
        contentScrollView.addView(step.contentBuilder.build());
        contentScrollView.scrollTo(0, 0);
    }

    private void goBack() {
        if (currentStep > 0) {
            showStep(currentStep - 1);
        }
    }

    private void goNext() {
        WizardStep step = steps.get(currentStep);

        // Validate if needed
        if (step.validator != null && !step.validator.validate()) {
            return; // Validation failed
        }

        if (currentStep < steps.size() - 1) {
            showStep(currentStep + 1);
        } else {
            // Finish wizard
            finishWizard();
        }
    }

    private void finishWizard() {
        SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WIZARD_COMPLETED_KEY, "true");
        Toast.makeText(this, "Setup complete!", Toast.LENGTH_SHORT).show();

        // Go to settings
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
        finish();
    }

    // ============ STEP BUILDERS ============

    private View buildWelcomeStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Welcome to Shadow");
        layout.addView(title);

        TextView desc = createDescription(
            "Shadow is an anti-theft protection app that helps secure your device.\n\n" +
            "This wizard will help you configure:\n\n" +
            "• App password for secure access\n" +
            "• Device owner permissions\n" +
            "• Decoy profile for wrong password protection\n" +
            "• Power button panic trigger\n" +
            "• Watch disconnect protection\n" +
            "• Accessibility service for shutdown detection\n\n" +
            "Let's get started!");
        layout.addView(desc);

        return layout;
    }

    private View buildPasswordStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Set App Password");
        layout.addView(title);

        TextView desc = createDescription(
            "Set a password to protect access to Shadow settings and to exit theft mode.\n\n" +
            "This password is required and cannot be recovered if forgotten.");
        layout.addView(desc);

        // Check if password already set
        String existingHash = PasswordHelper.retrievePasswordHash(this);
        if (existingHash != null && !existingHash.isEmpty()) {
            TextView existing = createDescription("A password is already set. You can skip this step or set a new one.");
            existing.setTextColor(0xFF4CAF50);
            layout.addView(existing);
        }

        passwordField = new EditText(this);
        passwordField.setHint("Enter password");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordField.setTextColor(0xFFFFFFFF);
        passwordField.setHintTextColor(0xFF666666);
        passwordField.setBackgroundColor(0xFF333333);
        passwordField.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dpToPx(16), 0, dpToPx(8));
        layout.addView(passwordField, params);

        confirmPasswordField = new EditText(this);
        confirmPasswordField.setHint("Confirm password");
        confirmPasswordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        confirmPasswordField.setTextColor(0xFFFFFFFF);
        confirmPasswordField.setHintTextColor(0xFF666666);
        confirmPasswordField.setBackgroundColor(0xFF333333);
        confirmPasswordField.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params2.setMargins(0, 0, 0, 0);
        layout.addView(confirmPasswordField, params2);

        return layout;
    }

    private boolean validatePassword() {
        String existingHash = PasswordHelper.retrievePasswordHash(this);
        String password = passwordField.getText().toString();
        String confirm = confirmPasswordField.getText().toString();

        // Allow skip if password already set
        if ((existingHash != null && !existingHash.isEmpty()) && password.isEmpty()) {
            return true;
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "Password is required", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (password.length() < 4) {
            Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show();
            return false;
        }

        if (!password.equals(confirm)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
            return false;
        }

        // Save password (hash it first)
        String passwordHash = PasswordHelper.hashPassword(password);
        PasswordHelper.storePasswordHash(this, passwordHash);
        Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private View buildDeviceOwnerStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Device Owner Setup");
        layout.addView(title);

        boolean isDeviceOwner = gateway.isDeviceOwnerApp();

        if (isDeviceOwner) {
            TextView status = createDescription("Device owner is set!");
            status.setTextColor(0xFF4CAF50);
            status.setTypeface(null, Typeface.BOLD);
            layout.addView(status);

            TextView desc = createDescription("Shadow has full device control and can:\n" +
                "• Lock the device in theft mode\n" +
                "• Create and manage profiles\n" +
                "• Block factory reset\n" +
                "• And more...");
            layout.addView(desc);
        } else {
            TextView status = createDescription("Device owner is NOT set");
            status.setTextColor(0xFFFF5722);
            status.setTypeface(null, Typeface.BOLD);
            layout.addView(status);

            TextView desc = createDescription(
                "Device owner must be set via ADB. Connect your device to a computer and run:\n");
            layout.addView(desc);

            String adbCommand = "adb shell dpm set-device-owner dev.borges.shadow/com.afwsamples.testdpc.DeviceAdminReceiver";

            TextView cmdText = new TextView(this);
            cmdText.setText(adbCommand);
            cmdText.setTextColor(0xFF00BCD4);
            cmdText.setTextSize(12);
            cmdText.setTypeface(Typeface.MONOSPACE);
            cmdText.setBackgroundColor(0xFF333333);
            cmdText.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
            LinearLayout.LayoutParams cmdParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cmdParams.setMargins(0, dpToPx(8), 0, dpToPx(8));
            layout.addView(cmdText, cmdParams);

            Button copyBtn = new Button(this);
            copyBtn.setText("COPY COMMAND");
            copyBtn.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("ADB command", adbCommand));
                Toast.makeText(this, "Command copied!", Toast.LENGTH_SHORT).show();
            });
            layout.addView(copyBtn);

            TextView note = createDescription(
                "\nNote: You may need to remove other device admin apps first. " +
                "After running the command, restart the app.");
            note.setTextColor(0xFFFF9800);
            layout.addView(note);
        }

        return layout;
    }

    private View buildDecoyStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Create Decoy Profile");
        layout.addView(title);

        boolean isDeviceOwner = gateway.isDeviceOwnerApp();
        SharedPreferences shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);
        long decoySerial = shadowPrefs.getLong("decoy_serial", -1);

        if (!isDeviceOwner) {
            TextView warn = createDescription("Device owner must be set first to create decoy profile.");
            warn.setTextColor(0xFFFF5722);
            layout.addView(warn);
            return layout;
        }

        if (decoySerial != -1) {
            TextView status = createDescription("Decoy profile exists (serial: " + decoySerial + ")");
            status.setTextColor(0xFF4CAF50);
            status.setTypeface(null, Typeface.BOLD);
            layout.addView(status);

            TextView desc = createDescription(
                "When someone enters the wrong password, they will be switched to this decoy profile.\n\n" +
                "The decoy profile appears as a normal device but can have restricted apps and features.");
            layout.addView(desc);
        } else {
            TextView desc = createDescription(
                "A decoy profile is a secondary user that thieves are switched to when entering the wrong password.\n\n" +
                "This creates a fake \"normal\" phone experience while your real data stays hidden.");
            layout.addView(desc);

            Button createBtn = new Button(this);
            createBtn.setText("CREATE DECOY PROFILE");
            createBtn.setOnClickListener(v -> createDecoyProfile());
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.setMargins(0, dpToPx(16), 0, 0);
            layout.addView(createBtn, btnParams);
        }

        return layout;
    }

    private void createDecoyProfile() {
        try {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags |= DevicePolicyManager.SKIP_SETUP_WIZARD;
            }

            android.os.UserHandle newUser = dpm.createAndManageUser(
                adminComponent,
                "System",
                adminComponent,
                null,
                flags
            );

            if (newUser != null) {
                long serial = um.getSerialNumberForUser(newUser);
                getSharedPreferences("shadow_prefs", MODE_PRIVATE)
                    .edit().putLong("decoy_serial", serial).apply();

                // Start user in background
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.startUserInBackground(adminComponent, newUser);
                }

                Toast.makeText(this, "Decoy profile created!", Toast.LENGTH_SHORT).show();
                showStep(currentStep); // Refresh
            } else {
                Toast.makeText(this, "Failed to create decoy profile", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating decoy", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private View buildPowerButtonStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Power Button Detection");
        layout.addView(title);

        TextView desc = createDescription(
            "Shadow can detect rapid power button presses as a panic trigger.\n\n" +
            "When triggered, theft mode will activate after a delay, giving you time to cancel if accidental.");
        layout.addView(desc);

        // Current settings
        String presses = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
        String delay = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.ACTIVATION_DELAY_KEY);

        TextView current = createDescription(
            "Current settings:\n" +
            "• " + presses + " presses to trigger\n" +
            "• " + delay + " seconds activation delay\n\n" +
            "You can adjust these in Settings after completing the wizard.");
        current.setTextColor(0xFF888888);
        layout.addView(current);

        return layout;
    }

    private View buildWatchStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Watch Protection");
        layout.addView(title);

        TextView desc = createDescription(
            "If you have a smartwatch, Shadow can trigger theft mode when the watch disconnects.\n\n" +
            "This is optional but provides additional protection.");
        layout.addView(desc);

        // Check Bluetooth permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Button permBtn = new Button(this);
            permBtn.setText("GRANT BLUETOOTH PERMISSION");
            permBtn.setOnClickListener(v -> {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 100);
            });
            layout.addView(permBtn);
            return layout;
        }

        // List paired devices
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            TextView nobt = createDescription("Bluetooth not available on this device.");
            nobt.setTextColor(0xFFFF5722);
            layout.addView(nobt);
            return layout;
        }

        pairedDevices.clear();
        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        List<String> deviceNames = new ArrayList<>();
        deviceNames.add("-- Select Watch (Optional) --");

        for (BluetoothDevice device : paired) {
            pairedDevices.add(device);
            String name = device.getName();
            if (name == null) name = device.getAddress();
            deviceNames.add(name);
        }

        watchSpinner = new Spinner(this);
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, deviceNames);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        watchSpinner.setAdapter(spinnerAdapter);

        // Pre-select if already configured
        String currentAddress = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY);
        if (!currentAddress.isEmpty()) {
            for (int i = 0; i < pairedDevices.size(); i++) {
                if (pairedDevices.get(i).getAddress().equals(currentAddress)) {
                    watchSpinner.setSelection(i + 1);
                    break;
                }
            }
        }

        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spinnerParams.setMargins(0, dpToPx(16), 0, dpToPx(8));
        layout.addView(watchSpinner, spinnerParams);

        Button saveBtn = new Button(this);
        saveBtn.setText("SAVE WATCH SELECTION");
        saveBtn.setOnClickListener(v -> saveWatchSelection());
        layout.addView(saveBtn);

        return layout;
    }

    private void saveWatchSelection() {
        int pos = watchSpinner.getSelectedItemPosition();
        if (pos > 0 && pos <= pairedDevices.size()) {
            BluetoothDevice device = pairedDevices.get(pos - 1);
            SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY, device.getAddress());
            String name = device.getName();
            if (name == null) name = device.getAddress();
            SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_NAME_KEY, name);
            SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY, "true");
            Toast.makeText(this, "Watch saved: " + name, Toast.LENGTH_SHORT).show();
        } else {
            SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY, "false");
            Toast.makeText(this, "Watch protection disabled", Toast.LENGTH_SHORT).show();
        }
    }

    private View buildAccessibilityStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Accessibility Service");
        layout.addView(title);

        boolean enabled = POffService.isAccessibilityServiceEnabled(this);

        if (enabled) {
            TextView status = createDescription("Accessibility service is enabled!");
            status.setTextColor(0xFF4CAF50);
            status.setTypeface(null, Typeface.BOLD);
            layout.addView(status);

            TextView desc = createDescription(
                "Shadow can now detect:\n" +
                "• Power off dialog keywords\n" +
                "• Attempts to shutdown the device\n\n" +
                "This helps trigger theft mode if someone tries to power off your phone.");
            layout.addView(desc);
        } else {
            TextView desc = createDescription(
                "The accessibility service helps detect when someone tries to power off your device.\n\n" +
                "This is optional but recommended for full protection.");
            layout.addView(desc);

            Button enableBtn = new Button(this);
            enableBtn.setText("OPEN ACCESSIBILITY SETTINGS");
            enableBtn.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            });
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            btnParams.setMargins(0, dpToPx(16), 0, 0);
            layout.addView(enableBtn, btnParams);

            TextView note = createDescription(
                "\nLook for \"Shadow\" or \"Power Off Detection\" in the list and enable it.");
            note.setTextColor(0xFFFF9800);
            layout.addView(note);
        }

        return layout;
    }

    private View buildSummaryStep() {
        LinearLayout layout = createStepLayout();

        TextView title = createTitle("Setup Complete!");
        layout.addView(title);

        // Build summary
        StringBuilder summary = new StringBuilder();

        // Password
        String passHash = PasswordHelper.retrievePasswordHash(this);
        summary.append("Password: ").append(passHash != null && !passHash.isEmpty() ? "Set" : "NOT SET").append("\n\n");

        // Device owner
        summary.append("Device Owner: ").append(gateway.isDeviceOwnerApp() ? "Yes" : "No").append("\n\n");

        // Decoy
        long decoySerial = getSharedPreferences("shadow_prefs", MODE_PRIVATE).getLong("decoy_serial", -1);
        summary.append("Decoy Profile: ").append(decoySerial != -1 ? "Created" : "Not created").append("\n\n");

        // Watch
        String watchEnabled = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY);
        String watchName = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_NAME_KEY);
        if ("true".equals(watchEnabled) && !watchName.isEmpty()) {
            summary.append("Watch Protection: ").append(watchName).append("\n\n");
        } else {
            summary.append("Watch Protection: Disabled\n\n");
        }

        // Accessibility
        summary.append("Accessibility: ").append(POffService.isAccessibilityServiceEnabled(this) ? "Enabled" : "Disabled").append("\n\n");

        TextView summaryText = createDescription(summary.toString());
        layout.addView(summaryText);

        TextView note = createDescription(
            "You can adjust all settings from the main Settings screen.\n\n" +
            "Tap FINISH to complete setup.");
        note.setTextColor(0xFF888888);
        layout.addView(note);

        return layout;
    }

    // ============ UI HELPERS ============

    private LinearLayout createStepLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24));
        return layout;
    }

    private TextView createTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(24);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dpToPx(16));
        tv.setLayoutParams(params);
        return tv;
    }

    private TextView createDescription(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(0xFFCCCCCC);
        tv.setLineSpacing(dpToPx(4), 1f);
        return tv;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            showStep(currentStep); // Refresh step
        }
    }

    @Override
    public void onBackPressed() {
        if (currentStep > 0) {
            goBack();
        } else {
            new AlertDialog.Builder(this)
                .setTitle("Exit Setup?")
                .setMessage("Setup is not complete. Are you sure you want to exit?")
                .setPositiveButton("Exit", (d, w) -> finish())
                .setNegativeButton("Cancel", null)
                .show();
        }
    }

    // ============ HELPER CLASSES ============

    private interface ContentBuilder {
        View build();
    }

    private interface StepValidator {
        boolean validate();
    }

    private static class WizardStep {
        String shortTitle;
        String title;
        ContentBuilder contentBuilder;
        StepValidator validator;
        boolean required;

        WizardStep(String shortTitle, String title, ContentBuilder contentBuilder, StepValidator validator, boolean required) {
            this.shortTitle = shortTitle;
            this.title = title;
            this.contentBuilder = contentBuilder;
            this.validator = validator;
            this.required = required;
        }
    }
}
