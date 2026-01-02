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
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
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

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.PolicyManagementActivity;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.comp.DeviceOwnerService;
import com.afwsamples.testdpc.comp.IDeviceOwnerService;

import dev.borges.shadow.util.DevicePasswordHelper;
import dev.borges.shadow.util.DownloadHelper;
import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.SettingsHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_SMS_PERMISSION = 100;

    private LinearLayout settingsContainer;
    private LinearLayout decoySettingsContainer;
    private SharedPreferences sharedPreferences;
    private SharedPreferences shadowPrefs; // For non-settings data like decoy_serial
    private TextView debugTheftModeText;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponentName;
    private boolean isDeviceOwner = false;
    private boolean isAuthenticated = false;
    private DownloadHelper downloadHelper;
    private android.os.Handler theftModeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable theftModeUpdateRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsContainer = findViewById(R.id.settings_container);
        sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this); // For settings
        shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE); // For non-settings data
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponentName = getAdminComponentName(this);
        downloadHelper = new DownloadHelper(this);

        isDeviceOwner = isDeviceOwnerApp(this);
        if (isDeviceOwner) {
            devicePolicyManager.setSecurityLoggingEnabled(adminComponentName, true);
        }

        // Set affiliation ID for cross-user communication between device owner and secondary users
        setAffiliationIds();

        populateSettings();

        PowerButtonReceiver.registerReceiver(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isDeviceOwner && getSystemService(UserManager.class).isSystemUser()) {
            devicePolicyManager.clearUserRestriction(adminComponentName, UserManager.DISALLOW_USER_SWITCH);
            // Keep decoy user running in background for faster switch
            DeviceAdminReceiver.startDecoyInBackground(this);
        }

        if (!isAuthenticated) {
            Intent intent = new Intent(this, PasswordActivity.class);
            startActivityForResult(intent, 1);
        } else {
            updateDecoyProfileSettings();
        }

        // Start theft mode timer updates
        startTheftModeTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isAuthenticated = false;
        stopTheftModeTimer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                isAuthenticated = true;
            } else {
                finish();
            }
        }
    }

    private void populateSettings() {
        isDeviceOwner = isDeviceOwnerApp(this);
        settingsContainer.removeAllViews();

        // Debug text for theft mode timer
        addTheftModeDebugText();

        decoySettingsContainer = new LinearLayout(this);
        decoySettingsContainer.setOrientation(LinearLayout.VERTICAL);
        decoySettingsContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addTitle("Decoy Profile");
        settingsContainer.addView(decoySettingsContainer);
        updateDecoyProfileSettings();

        addTitle("Permissions & Requirements");
        addDeviceOwnerSetting();
        if (isDeviceOwner) {
            addSmsPermissionSetting();
            addTitle("Protection Setup");
            addDevicePasswordTokenSetting();
            addFingerprintLoginSetting();
            addBackupServicesSetting();
            addLocationEnabledSetting();
            addOrganizationNameSetting();
            addDeviceOwnerLockscreenInfoSetting();
            addUserRestrictionsSetting();
            addTitle("Power Off Prevention");
            addAccessibilityServiceSetting();
            addDetectKeywordsSetting();
            addTitle("Theft Mode settings");
            addTheftModeTitleSetting();
            addTheftModeInstructionsSetting();
            addPowerButtonPressesSetting();
            addPressTimeWindowSetting();
            addActivationDelaySetting();
            addDeactivationSequenceSetting();
            addHiddenAppsSetting();
            addTitle("Watch Disconnect Protection");
            addWatchDisconnectEnabledSetting();
            addWatchDeviceSelectSetting();
            addWatchDisconnectTimeoutSetting();
            addTitle("Factory Reset Protection (FRP)");
            addFRPDescriptionSetting();
            addFRPAccountsSetting();
            addFRPToggleSetting();
            addTitle("Extras / dev");
            addAppUpdateUrl();
            updateAppSetting();
            updateAppSetting2();
            addTestDPCSetting();
        }
    }

    private void addTheftModeDebugText() {
        debugTheftModeText = new TextView(this);
        debugTheftModeText.setTextColor(0xFFFF5555); // Red
        debugTheftModeText.setTextSize(14);
        debugTheftModeText.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        debugTheftModeText.setBackgroundColor(0x33FF0000); // Light red background
        updateTheftModeDebugText();
        settingsContainer.addView(debugTheftModeText);
    }

    private void updateTheftModeDebugText() {
        if (debugTheftModeText == null) return;

        long activationTime = shadowPrefs.getLong("theft_mode_activation_time", 0);
        if (activationTime > 0) {
            long now = System.currentTimeMillis();
            long remaining = (activationTime - now) / 1000;
            if (remaining > 0) {
                debugTheftModeText.setText("⚠️ THEFT MODE ACTIVATING IN " + remaining + "s");
                debugTheftModeText.setVisibility(View.VISIBLE);
            } else {
                debugTheftModeText.setText("⚠️ THEFT MODE ACTIVATION PENDING");
                debugTheftModeText.setVisibility(View.VISIBLE);
            }
        } else {
            debugTheftModeText.setVisibility(View.GONE);
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

    private void updateDecoyProfileSettings() {
        if (decoySettingsContainer == null) return;
        decoySettingsContainer.removeAllViews();

        if (getSystemService(UserManager.class).isSystemUser()) {
            if (isDeviceOwner) {
                View createDecoyButton = createClickableTextItem("Create Decoy Profile", this::createDecoyProfile);
                decoySettingsContainer.addView(createDecoyButton);
            }
        } else {
            View returnToOwnerButton = createClickableTextItem("Return to Owner", this::returnToRealProfile);
            decoySettingsContainer.addView(returnToOwnerButton);
        }
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

    private void addDevicePasswordTokenSetting() {
        boolean isChecked = DevicePasswordHelper.hasPasswordResetToken(this);
        View switchCompat = createSwitchItem("Device password token", isChecked, true, (buttonView, newChecked) -> {
            if (newChecked) {
                DevicePasswordHelper.createNewPasswordToken(this, devicePolicyManager, adminComponentName, (token, status) -> {});
            } else {
                DevicePasswordHelper.removePasswordToken(this, devicePolicyManager, adminComponentName, (token, status) -> {});
            }
        });
        View row = createRow(switchCompat, () -> startActivity(new Intent(this, PasswordActivity.class)));
        settingsContainer.addView(row);
    }

    private void addFingerprintLoginSetting() {
        // Check if biometrics are available on this device
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        );

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Biometrics not available, don't show the setting
            return;
        }

        boolean isEnabled = PasswordHelper.isBiometricKeyValid();
        boolean keyExistsButInvalid = PasswordHelper.biometricKeyExists() && !isEnabled;

        String label = keyExistsButInvalid ? "Fingerprint login (new fingerprints detected)" : "Fingerprint login";

        View switchCompat = createSwitchItem(label, isEnabled, true, (buttonView, newChecked) -> {
            if (newChecked) {
                // Enable fingerprint - generate the key
                PasswordHelper.generateBiometricKey();
                Toast.makeText(this, "Fingerprint login enabled", Toast.LENGTH_SHORT).show();
            } else {
                // Disable fingerprint - delete the key
                PasswordHelper.deleteBiometricKey();
                Toast.makeText(this, "Fingerprint login disabled", Toast.LENGTH_SHORT).show();
            }
        });
        settingsContainer.addView(createRow(switchCompat, null));
    }

    private void addBackupServicesSetting() {
        boolean isChecked = devicePolicyManager.isBackupServiceEnabled(adminComponentName);
        View switchCompat = createSwitchItem("Enable backup services", isChecked, true, (buttonView, newChecked) -> {
            devicePolicyManager.setBackupServiceEnabled(adminComponentName, newChecked);
        });
        settingsContainer.addView(createRow(switchCompat, null));
    }

    public static boolean isLocationEnabled(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        } else {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        }
    }

    private void addLocationEnabledSetting() {
        boolean isChecked = isLocationEnabled(this);
        View switchCompat = createSwitchItem("Set location enabled", isChecked, true, (buttonView, newChecked) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                devicePolicyManager.setLocationEnabled(adminComponentName, newChecked);
            } else {
                final int locationMode = newChecked ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF;
                devicePolicyManager.setSecureSetting(
                        adminComponentName,
                        Settings.Secure.LOCATION_MODE,
                        String.format(Locale.getDefault(), "%d", locationMode));
            }
        });
        settingsContainer.addView(createRow(switchCompat, null));
    }

    private void addOrganizationNameSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY);
        View editText = createTextEditItem("Organization name (e.g., e-mail)", value, value, text -> {
            devicePolicyManager.setOrganizationName(adminComponentName, text);
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY, text);
        });
        settingsContainer.addView(editText);
    }

    private void addDeviceOwnerLockscreenInfoSetting() {
        CharSequence value = null;
        try {
            value = devicePolicyManager.getDeviceOwnerLockScreenInfo();
        } catch (Exception ignored) {}
        View editText = createTextEditItem("Lockscreen message (e.g., phone number)", "", value == null ? null : value.toString(),
                text -> devicePolicyManager.setDeviceOwnerLockScreenInfo(adminComponentName, text));
        settingsContainer.addView(editText);
    }

    private void addFRPDescriptionSetting() {
        View textView = createClickableTextItem("This will prevent all Google accounts other than the listed ones from being able to access your device, even after factory reset. USE WITH CAUTION.", () -> {
            Uri webpage = Uri.parse("https://developers.google.com/people/api/rest/v1/people/get?apix_params=%7B%22resourceName%22%3A%22people%2Fme%22%2C%22personFields%22%3A%22metadata%22%7D");
            Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No web browser app found.", Toast.LENGTH_SHORT).show();
            }
        });
        settingsContainer.addView(textView);
    }

    private void addFRPAccountsSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
        View editText = createTextEditItem("Google Account IDs (comma-separated)", value, value, text -> {
            devicePolicyManager.setOrganizationName(adminComponentName, text);
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS, text);
        });
        settingsContainer.addView(editText);
    }

    private void addFRPToggleSetting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            FactoryResetProtectionPolicy policy = devicePolicyManager.getFactoryResetProtectionPolicy(adminComponentName);
            boolean isChecked = policy != null && policy.isFactoryResetProtectionEnabled();
            View switchCompat = createSwitchItem("Enable FRP", isChecked, true, (buttonView, newChecked) -> {
                String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
                List<String> accountIds = Arrays.stream(value.split(",")).map(String::trim).filter(s -> s.length() == 21).collect(Collectors.toList());
                if (accountIds.isEmpty()) {
                    Log.i(TAG, "[FRP] No valid Google Account IDs provided");
                    return;
                }
                devicePolicyManager.setFactoryResetProtectionPolicy(
                    adminComponentName,
                    new FactoryResetProtectionPolicy.Builder()
                            .setFactoryResetProtectionAccounts(accountIds)
                            .setFactoryResetProtectionEnabled(newChecked)
                            .build());
            });
            settingsContainer.addView(createRow(switchCompat, null));
        }
    }

    private void addUserRestrictionsSetting() {
        View textView = createClickableTextItem("Set User Restrictions", () -> startActivity(new Intent(this, PasswordActivity.class)));
        settingsContainer.addView(textView);
    }

    private void addAccessibilityServiceSetting() {
        boolean isEnabled = POffService.isAccessibilityServiceEnabled(this);
        View switchCompat = createSwitchItem("Accessibility Service", isEnabled, true, (buttonView, isChecked) -> {
            POffService.enableAccessibilityService(this);
            populateSettings();
        });
        View row = createRow(switchCompat, () -> {
            POffService.enableAccessibilityService(this);
            populateSettings();
        });
        settingsContainer.addView(row);
    }

    private void addDetectKeywordsSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY);
        View editText = createTextEditItem("Detect Keywords", value, value, text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY, text));
        settingsContainer.addView(editText);
    }

    private void addTheftModeTitleSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY);
        View editText = createTextEditItem("Theft mode title", value, value, text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY, text));
        settingsContainer.addView(editText);
    }

    private void addTheftModeInstructionsSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY);
        View editText = createMultilineTextEditItem("Theft mode instructions", value, value, text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY, text));
        settingsContainer.addView(editText);
    }

    private void addPowerButtonPressesSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
        View editText = createNumberEditItem("Number of power button presses to activate", value, value, v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addPressTimeWindowSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY);
        View editText = createNumberEditItem("Time window between power button presses (milliseconds)", value, value, v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addActivationDelaySetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY);
        View editText = createNumberEditItem("Time delay to activate theft mode (seconds)", value, value, v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addDeactivationSequenceSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY);
        View editText = createTextEditItem("Deactivation sequence (e.g., up,up,down,down)", value, value, v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addHiddenAppsSetting() {
        View textView = createClickableTextItem("Apps to hide on theft mode", () -> startActivity(new Intent(this, HiddenAppsActivity.class)));
        settingsContainer.addView(textView);
    }

    private void addWatchDisconnectEnabledSetting() {
        boolean isEnabled = "true".equals(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY));
        View switchCompat = createSwitchItem("Enable watch disconnect protection", isEnabled, true, (buttonView, newChecked) -> {
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY, newChecked ? "true" : "false");
            if (newChecked) {
                // Re-register the receiver when enabling
                BluetoothWatchReceiver.registerReceiver(getApplicationContext());
                Toast.makeText(this, "Watch disconnect protection enabled", Toast.LENGTH_SHORT).show();
            } else {
                // Cancel any pending disconnect timer when disabling
                BluetoothWatchReceiver.cancelDisconnectTimer(getApplicationContext());
                Toast.makeText(this, "Watch disconnect protection disabled", Toast.LENGTH_SHORT).show();
            }
        });
        settingsContainer.addView(createRow(switchCompat, null));
    }

    private void addWatchDeviceSelectSetting() {
        String currentName = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_NAME_KEY);
        String label = currentName.isEmpty() ? "Select watch device" : "Watch: " + currentName;

        View textView = createClickableTextItem(label, this::showWatchSelectionDialog);
        settingsContainer.addView(textView);
    }

    private void showWatchSelectionDialog() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Check for BLUETOOTH_CONNECT permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 200);
                return;
            }
        }

        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build device list for dialog
        String[] deviceNames = new String[pairedDevices.size()];
        String[] deviceAddresses = new String[pairedDevices.size()];
        int i = 0;
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            deviceNames[i] = name != null ? name : "Unknown device";
            deviceAddresses[i] = device.getAddress();
            i++;
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Watch Device")
            .setItems(deviceNames, (dialog, which) -> {
                String selectedName = deviceNames[which];
                String selectedAddress = deviceAddresses[which];
                SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_NAME_KEY, selectedName);
                SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY, selectedAddress);
                Toast.makeText(this, "Selected: " + selectedName, Toast.LENGTH_SHORT).show();
                // Refresh settings to show new selection
                populateSettings();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void addWatchDisconnectTimeoutSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY);
        View editText = createNumberEditItem("Disconnect timeout (seconds)", value, value,
            v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addTestDPCSetting() {
        View textView = createClickableTextItem("Test DPC", () -> startActivity(new Intent(this, PolicyManagementActivity.class)));
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
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);
        return layout;
    }

    private View createMultilineTextEditItem(String label, String hint, String initialValue, Consumer<String> onTextChanged) {
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
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(2);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);
        return layout;
    }

    private View createNumberEditItem(String label, String hint, String initialValue, Consumer<String> onTextChanged) {
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
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);
        return layout;
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
        return devicePolicyManager.isDeviceOwnerApp(context.getPackageName());
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

    // Decoy Profile Methods

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

    private void createDecoyProfile() {
        if (!isDeviceOwner) {
            Log.w(TAG, "Decoy profile creation requires device owner.");
            return;
        }

        UserManager um = getSystemService(UserManager.class);
        long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
        if (decoySerial != -1L) {
            // Check if the user actually exists
            UserHandle existingUser = um.getUserForSerialNumber(decoySerial);
            if (existingUser != null) {
                Log.w(TAG, "Decoy profile already exists with serial: " + decoySerial);
                return;
            } else {
                // User was deleted, clear the stale serial
                Log.i(TAG, "Stale decoy serial " + decoySerial + " - user no longer exists, clearing");
                shadowPrefs.edit().remove("decoy_serial").apply();
            }
        }

        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, DeviceAdminReceiver.class);

        Log.i(TAG, "Creating decoy profile...");
        String hackyName = "System";
        UserHandle userHandle = dpm.createAndManageUser(
            admin, hackyName, admin, null, 0
        );
        if (userHandle == null) {
            Log.e(TAG, "Failed to create decoy profile.");
            return;
        }

        long serial = um.getSerialNumberForUser(userHandle);
        shadowPrefs.edit().putLong("decoy_serial", serial).apply();

        dpm.installExistingPackage(admin, getPackageName());
        Log.i(TAG, "Decoy profile created successfully.");
        // Start the decoy in background immediately
        DeviceAdminReceiver.startDecoyInBackground(this);
        updateDecoyProfileSettings();
    }

    private void returnToRealProfile() {
        // Use bindDeviceAdminServiceAsUser to communicate with device owner in user 0
        UserManager um = getSystemService(UserManager.class);
        UserHandle ownerUser = um.getUserForSerialNumber(0);
        if (ownerUser == null) {
            // Fallback to serial 0 which is typically the owner
            for (UserHandle user : um.getUserProfiles()) {
                if (um.getSerialNumberForUser(user) == 0) {
                    ownerUser = user;
                    break;
                }
            }
        }

        if (ownerUser == null) {
            Log.e(TAG, "Could not find owner user");
            Toast.makeText(this, "Could not find owner user", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent();
        serviceIntent.setClass(this, DeviceOwnerService.class);

        final UserHandle targetUser = ownerUser;
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "Connected to DeviceOwnerService in user 0");
                IDeviceOwnerService deviceOwnerService = IDeviceOwnerService.Stub.asInterface(service);
                try {
                    deviceOwnerService.switchToOwner();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call switchToOwner", e);
                }
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "Disconnected from DeviceOwnerService");
            }
        };

        boolean bound = devicePolicyManager.bindDeviceAdminServiceAsUser(
                adminComponentName,
                serviceIntent,
                connection,
                Context.BIND_AUTO_CREATE,
                targetUser
        );

        if (!bound) {
            Log.e(TAG, "Failed to bind to DeviceOwnerService in user 0");
            Toast.makeText(this, "Failed to connect to owner profile", Toast.LENGTH_SHORT).show();
        }
    }

    //endregion

    //region TextWatcherAdapter
    private static abstract class TextWatcherAdapter implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {}
    }
    //endregion
}
