package dev.borges.shadow;

import android.content.Context;
import android.location.LocationManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.biometric.BiometricManager;

import java.util.Locale;

import dev.borges.shadow.util.DevicePasswordHelper;
import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.SettingsHelper;

public class ProtectionSetupActivity extends SubSettingsActivity {

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        // Device password token
        addDevicePasswordTokenSetting();

        // Fingerprint login
        addFingerprintLoginSetting();

        if (isDeviceOwner) {
            // Backup services
            addBackupServicesSetting();

            // Location
            addLocationEnabledSetting();

            // Organization name
            addOrganizationNameSetting();

            // Lockscreen info
            addDeviceOwnerLockscreenInfoSetting();
        }
    }

    private void addDevicePasswordTokenSetting() {
        boolean isChecked = DevicePasswordHelper.hasPasswordResetToken(this);
        addSwitchRow("Device password token", isChecked, (buttonView, newChecked) -> {
            if (newChecked) {
                DevicePasswordHelper.createNewPasswordToken(this, dpm, adminComponentName, (token, status) -> {});
            } else {
                DevicePasswordHelper.removePasswordToken(this, dpm, adminComponentName, (token, status) -> {});
            }
        });
    }

    private void addFingerprintLoginSetting() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        );

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            return;
        }

        boolean isEnabled = PasswordHelper.isBiometricKeyValid();
        boolean keyExistsButInvalid = PasswordHelper.biometricKeyExists() && !isEnabled;
        String label = keyExistsButInvalid ? "Fingerprint login (new fingerprints detected)" : "Fingerprint login";

        addSwitchRow(label, isEnabled, (buttonView, newChecked) -> {
            if (newChecked) {
                PasswordHelper.generateBiometricKey();
                Toast.makeText(this, "Fingerprint login enabled", Toast.LENGTH_SHORT).show();
            } else {
                PasswordHelper.deleteBiometricKey();
                Toast.makeText(this, "Fingerprint login disabled", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addBackupServicesSetting() {
        boolean isChecked = dpm.isBackupServiceEnabled(adminComponentName);
        addSwitchRow("Enable backup services", isChecked, (buttonView, newChecked) -> {
            dpm.setBackupServiceEnabled(adminComponentName, newChecked);
        });
    }

    private void addLocationEnabledSetting() {
        boolean isChecked = isLocationEnabled(this);
        addSwitchRow("Set location enabled", isChecked, (buttonView, newChecked) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(adminComponentName, newChecked);
            } else {
                final int locationMode = newChecked ? Settings.Secure.LOCATION_MODE_HIGH_ACCURACY : Settings.Secure.LOCATION_MODE_OFF;
                dpm.setSecureSetting(
                    adminComponentName,
                    Settings.Secure.LOCATION_MODE,
                    String.format(Locale.getDefault(), "%d", locationMode));
            }
        });
    }

    private void addOrganizationNameSetting() {
        String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY);
        addTextInputRow("Organization name (e.g., e-mail)", value, text -> {
            dpm.setOrganizationName(adminComponentName, text);
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.ORGANIZATION_NAME_KEY, text);
        });
    }

    private void addDeviceOwnerLockscreenInfoSetting() {
        CharSequence value = null;
        try {
            value = dpm.getDeviceOwnerLockScreenInfo();
        } catch (Exception ignored) {}
        addTextInputRow("Lockscreen message (e.g., phone number)",
            value == null ? "" : value.toString(),
            text -> dpm.setDeviceOwnerLockScreenInfo(adminComponentName, text));
    }

    public static boolean isLocationEnabled(Context context) {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return locationManager.isLocationEnabled();
        } else {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                   locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        }
    }
}
