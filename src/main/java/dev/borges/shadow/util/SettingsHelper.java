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
    public static final String APP_UPDATE_URL = "app_update_url";
    public static final String FRP_ACCOUNT_IDS = "frp_account_ids";

    // Watch disconnect protection settings
    public static final String WATCH_DISCONNECT_ENABLED_KEY = "watch_disconnect_enabled";
    public static final String WATCH_DEVICE_ADDRESS_KEY = "watch_device_address";
    public static final String WATCH_DEVICE_NAME_KEY = "watch_device_name";
    public static final String WATCH_DISCONNECT_TIMEOUT_KEY = "watch_disconnect_timeout";

    // Setup wizard
    public static final String WIZARD_COMPLETED_KEY = "wizard_completed";

    // Wrist detection state (persisted for reboot survival)
    public static final String WRIST_LAST_KNOWN_STATE_KEY = "wrist_last_known_state";
    public static final String WRIST_LAST_UPDATE_TIME_KEY = "wrist_last_update_time";
    public static final String WRIST_DETECTION_ENABLED_KEY = "wrist_detection_enabled";
    public static final String WRIST_REMOVAL_TIMEOUT_KEY = "wrist_removal_timeout";

    // Auto-kill apps settings
    public static final String AUTO_KILL_ENABLED_KEY = "auto_kill_enabled";
    public static final String AUTO_KILL_DELAY_KEY = "auto_kill_delay";

    public static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(ORGANIZATION_NAME_KEY, ""),
            Map.entry(DETECT_KEYWORDS_KEY, "power off,restart,emergency"),
            Map.entry(THEFT_MODE_TITLE_KEY, "Esse celular é roubado!"),
            Map.entry(THEFT_MODE_INSTRUCTIONS_KEY, "Se você achou/comprou esse celular, por favor entre em contato com o dono."),
            Map.entry(POWER_BUTTON_PRESSES_KEY, "4"),
            Map.entry(PRESS_TIME_WINDOW_KEY, "2000"),
            Map.entry(ACTIVATION_DELAY_KEY, "180"),
            Map.entry(DEACTIVATION_SEQUENCE_KEY, "up,up,down,down,right"),
            Map.entry(APP_UPDATE_URL, "https://public.borges.dev/shadow/latest.apk"),
            Map.entry(FRP_ACCOUNT_IDS, ""),
            Map.entry(WATCH_DISCONNECT_ENABLED_KEY, "false"),
            Map.entry(WATCH_DEVICE_ADDRESS_KEY, ""),
            Map.entry(WATCH_DEVICE_NAME_KEY, ""),
            Map.entry(WATCH_DISCONNECT_TIMEOUT_KEY, "30"),
            Map.entry(WRIST_LAST_KNOWN_STATE_KEY, "unknown"),
            Map.entry(WRIST_LAST_UPDATE_TIME_KEY, "0"),
            Map.entry(WRIST_DETECTION_ENABLED_KEY, "false"),
            Map.entry(WRIST_REMOVAL_TIMEOUT_KEY, "30"),
            Map.entry(WIZARD_COMPLETED_KEY, "false"),
            Map.entry(AUTO_KILL_ENABLED_KEY, "false"),
            Map.entry(AUTO_KILL_DELAY_KEY, "60")
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
