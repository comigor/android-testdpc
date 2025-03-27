package dev.borges.shadow.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

public abstract class SettingsHelper {
    private static final String TAG = "SettingsHelper";

    public static final String ORGANIZATION_NAME_KEY = "organization_name";
    public static final String DETECT_KEYWORDS_KEY = "detect_keywords";
    public static final String THEFT_MODE_TITLE_KEY = "theft_mode_title";
    public static final String THEFT_MODE_INSTRUCTIONS_KEY = "theft_mode_instructions";
    public static final String THEFT_MODE_PASSWORD_KEY = "theft_mode_password";
    public static final String POWER_BUTTON_PRESSES_KEY = "power_button_presses";
    public static final String PRESS_TIME_WINDOW_KEY = "press_time_window";
    public static final String ACTIVATION_DELAY_KEY = "activation_delay";
    public static final String DEACTIVATION_SEQUENCE_KEY = "deactivation_sequence";

    public static final Map<String, String> DEFAULTS = Map.of(
            ORGANIZATION_NAME_KEY, "",
            DETECT_KEYWORDS_KEY, "power off,restart,emergency",
            THEFT_MODE_TITLE_KEY, "Esse celular é roubado!",
            THEFT_MODE_INSTRUCTIONS_KEY, "Se você achou/comprou esse celular, por favor entre em contato com o dono.",
            POWER_BUTTON_PRESSES_KEY, "4",
            PRESS_TIME_WINDOW_KEY, "2000",
            ACTIVATION_DELAY_KEY, "600",
            DEACTIVATION_SEQUENCE_KEY, "up,up,down,down,right"
    );

    public static String getSetting(SharedPreferences sharedPreferences, String key) {
        String mDefault = DEFAULTS.get(key);
        return sharedPreferences.getString(key, mDefault);
    }

    public static void setSetting(SharedPreferences sharedPreferences, String key, String value) {
        if (value == null || value.isEmpty()) {
            sharedPreferences.edit().putString(key, null).apply();
        } else {
            sharedPreferences.edit().putString(key, value).apply();
            Log.d(TAG, "setting " + key + " to " + value);
        }
    }

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
