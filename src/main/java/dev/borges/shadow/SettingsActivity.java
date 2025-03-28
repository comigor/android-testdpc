package dev.borges.shadow;

import android.Manifest;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.util.Log;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.PolicyManagementActivity;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.PackageInstallationUtils;

import dev.borges.shadow.util.DevicePasswordHelper;
import dev.borges.shadow.util.SettingsHelper;
import dev.borges.shadow.util.UpdateAppHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_SMS_PERMISSION = 100;

    private LinearLayout settingsContainer;
    private SharedPreferences encryptedSharedPreferences;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponentName;
    private boolean isDeviceOwner = false;
    private boolean isAuthenticated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsContainer = findViewById(R.id.settings_container);
        encryptedSharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponentName = getAdminComponentName(this);

        populateSettings();

        PowerButtonReceiver.registerReceiver(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!isAuthenticated) {
            Intent intent = new Intent(this, PasswordActivity.class);
            startActivityForResult(intent, 1);
        }

        populateSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Reset authentication state when the app is paused (e.g., when sent to background)
        isAuthenticated = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle result from PasswordActivity
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                isAuthenticated = true; // Authentication succeeded
            } else {
                // Handle cases where authentication failed or the user exited the password screen
                finish(); // Close the app if authentication is not successful
            }
        }
    }

    private void populateSettings() {
        isDeviceOwner = isDeviceOwnerApp(this);

        settingsContainer.removeAllViews();

        // # Permissions & Requirements
        addTitle("Permissions & Requirements");
        addDeviceOwnerSetting();
        if (isDeviceOwner) {
            addSmsPermissionSetting();

            // # Protection Setup
            addTitle("Protection Setup");
            addDevicePasswordTokenSetting();
            addBackupServicesSetting();
            addLocationEnabledSetting();
            addOrganizationNameSetting();
            addDeviceOwnerLockscreenInfoSetting();
            addUserRestrictionsSetting();

            // # Power Off Prevention
            addTitle("Power Off Prevention");
            addAccessibilityServiceSetting();
            addDetectKeywordsSetting();

            // # Theft Mode settings
            addTitle("Theft Mode settings");
            addTheftModeTitleSetting();
            addTheftModeInstructionsSetting();
//        addNewDevicePasswordSetting();
            addPowerButtonPressesSetting();
            addPressTimeWindowSetting();
            addActivationDelaySetting();
            addDeactivationSequenceSetting();

            // # Factory Reset Protection (FRP)
            addTitle("Factory Reset Protection (FRP)");
            addFRPDescriptionSetting();
            addFRPAccountsSetting();
            addFRPToggleSetting();

            addTitle("Extras / dev");
            addTestDPCSetting();
            addAppUpdateUrl();
            updateAppSetting();
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
            return false; // Location service not available
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // For Android P (API level 28) and higher, use isLocationEnabled()
            return locationManager.isLocationEnabled();
        } else {
            // For older versions, check if GPS or Network provider is enabled
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
                final int locationMode;
                if (newChecked) {
                    locationMode = Settings.Secure.LOCATION_MODE_HIGH_ACCURACY;
                } else {
                    locationMode = Settings.Secure.LOCATION_MODE_OFF;
                }
                devicePolicyManager.setSecureSetting(
                        adminComponentName,
                        Settings.Secure.LOCATION_MODE,
                        String.format(Locale.getDefault(), "%d", locationMode));
            }
        });
        settingsContainer.addView(createRow(switchCompat, null));
    }

    private void addOrganizationNameSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY);
        View editText = createTextEditItem("Organization name (e.g., e-mail)", value, value, text -> {
            devicePolicyManager.setOrganizationName(adminComponentName, text);
            SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY, text);
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
                Toast.makeText(this, "No web browser app found.", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        settingsContainer.addView(textView);
    }

    private void addFRPAccountsSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
        View editText = createTextEditItem("Google Account IDs (comma-separated)", value, value, text -> {
            devicePolicyManager.setOrganizationName(adminComponentName, text);
            SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS, text);
        });
        settingsContainer.addView(editText);
    }

    private void addFRPToggleSetting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            FactoryResetProtectionPolicy policy = devicePolicyManager.getFactoryResetProtectionPolicy(adminComponentName);
            boolean isChecked = policy != null && policy.isFactoryResetProtectionEnabled();

            View switchCompat = createSwitchItem("Enable FRP", isChecked, true, (buttonView, newChecked) -> {
                String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
                List<String> accountIds = Arrays.stream(value.split(",")).map(String::trim).filter(s -> s.length() == 21).collect(Collectors.toList());

                if (accountIds.isEmpty()) {
                    Log.i(TAG, "[FRP] No valid Google Account IDs provided");
                    Toast.makeText(this, "No valid Google Account IDs provided", Toast.LENGTH_SHORT).show();
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
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY);
        View editText = createTextEditItem("Detect Keywords", value, value,
                text -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY, text));
        settingsContainer.addView(editText);
    }

    private void addTheftModeTitleSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY);
        View editText = createTextEditItem("Theft mode title", value, value,
                text -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY, text));
        settingsContainer.addView(editText);
    }

    private void addTheftModeInstructionsSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY);
        View editText = createMultilineTextEditItem("Theft mode instructions", value, value,
                text -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY, text));
        settingsContainer.addView(editText);
    }

//    private void addNewDevicePasswordSetting() {
//        String value = encryptedSharedPreferences.getString("theft_mode_password", "");
//        View editText = createTextEditItem("New device password when theft mode is activated", null, value,
//                text -> encryptedSharedPreferences.edit().putString("theft_mode_password", text).apply(), InputType.TYPE_TEXT_VARIATION_PASSWORD);
//        settingsContainer.addView(editText);
//    }

    private void addPowerButtonPressesSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
        View editText = createNumberEditItem("Number of power button presses to activate", value, value,
                v -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addPressTimeWindowSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY);
        View editText = createNumberEditItem("Time window between power button presses (milliseconds)", value, value,
                v -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addActivationDelaySetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY);
        View editText = createNumberEditItem("Time delay to activate theft mode (seconds)", value, value,
                v -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addDeactivationSequenceSetting() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY);
        View editText = createTextEditItem("Deactivation sequence (e.g., up,up,down,down)", value, value,
                v -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY, v));
        settingsContainer.addView(editText);
    }

    private void addTestDPCSetting() {
        View textView = createClickableTextItem("Test DPC", () -> startActivity(new Intent(this, PolicyManagementActivity.class)));
        settingsContainer.addView(textView);
    }

    private void addAppUpdateUrl() {
        String value = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.APP_UPDATE_URL);
        View editText = createTextEditItem("App update URL", value, value,
                text -> SettingsHelper.setSetting(encryptedSharedPreferences, SettingsHelper.APP_UPDATE_URL, text));
        settingsContainer.addView(editText);
    }

    private void updateAppSetting() {
        registerReceiver(mInstallReceiver, new IntentFilter(PackageInstallationUtils.ACTION_INSTALL_COMPLETE), Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Context.RECEIVER_EXPORTED : Context.RECEIVER_VISIBLE_TO_INSTANT_APPS);

        String url = SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.APP_UPDATE_URL);
        View textView = createClickableTextItem("Update app", () -> UpdateAppHelper.downloadAndInstall(this, url));
        settingsContainer.addView(textView);
    }

    final private BroadcastReceiver mInstallReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!PackageInstallationUtils.ACTION_INSTALL_COMPLETE.equals(intent.getAction())) {
                    return;
                }

                int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
                Log.d(TAG, "PackageInstallerCallback: result=" + result + " packageName=" + packageName);

                unregisterReceiver(mInstallReceiver);
            }
        };

    @Override
    public void onBackPressed() {
        finish();
    }

    //region Helper Methods for UI Elements
    private View createSwitchItem(String label, boolean isChecked, boolean isEnabled, CompoundButton.OnCheckedChangeListener onCheckedChange) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
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
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
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

    private View createTextEditItem(String label, String hint, String initialValue, java.util.function.Consumer<String> onTextChanged) {
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

    private View createMultilineTextEditItem(String label, String hint, String initialValue, java.util.function.Consumer<String> onTextChanged) {
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
        editText.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);
        return layout;
    }

    private View createNumberEditItem(String label, String hint, String initialValue, java.util.function.Consumer<String> onTextChanged) {
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
            Toast.makeText(this, "SMS permissions already granted", Toast.LENGTH_SHORT).show();
            populateSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SMS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "SMS permissions granted", Toast.LENGTH_SHORT).show();
                // Update the switch state in the UI
                populateSettings(); // Re-render to update the SMS switch
            } else {
                Toast.makeText(this, "SMS permissions denied", Toast.LENGTH_SHORT).show();
                // Optionally update the switch state in the UI
                populateSettings(); // Re-render to update the SMS switch
            }
        }
    }
    //endregion

    //region TextWatcherAdapter
    private static abstract class TextWatcherAdapter implements android.text.TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {}
    }
    //endregion
}
