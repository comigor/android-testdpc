package dev.borges.shadow.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public abstract class SettingsHelper {
    private static final String TAG = "SettingsHelper";

    public static final String ORGANIZATION_NAME = "organization_name";
    public static final String DETECT_KEYWORDS = "detect_keywords";
    public static final String THEFT_MODE_TITLE = "theft_mode_title";
    public static final String THEFT_MODE_INSTRUCTIONS = "theft_mode_instructions";
    public static final String THEFT_MODE_PASSWORD = "theft_mode_password";
    public static final String POWER_BUTTON_PRESSES = "power_button_presses";
    public static final String PRESS_TIME_WINDOW = "press_time_window";
    public static final String ACTIVATION_DELAY = "activation_delay";
    public static final String DEACTIVATION_SEQUENCE = "deactivation_sequence";

    public static SharedPreferences getEncryptedSharedPreferences(Context context) {
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            return EncryptedSharedPreferences.create(
                    "shadow_settings",
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Error creating EncryptedSharedPreferences: " + e.getMessage());
            throw new RuntimeException("Failed to initialize secure preferences", e);
        }
    }

    public static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
