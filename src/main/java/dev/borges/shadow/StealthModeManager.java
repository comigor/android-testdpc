package dev.borges.shadow;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Manages stealth mode - hiding/showing the app in the launcher.
 */
public class StealthModeManager {
    private static final String TAG = "StealthModeManager";
    private static final String PREF_STEALTH_ENABLED = "stealth_mode_enabled";

    /**
     * Enable stealth mode - hides app from launcher.
     * App can still be accessed via dialer code *#*#742369#*#*
     */
    public static void enableStealthMode(Context context) {
        Log.i(TAG, "Enabling stealth mode");

        PackageManager pm = context.getPackageManager();

        // Disable the launcher alias component
        ComponentName launcherAlias = new ComponentName(context, "dev.borges.shadow.SettingsActivityAlias");
        pm.setComponentEnabledSetting(
            launcherAlias,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );

        // Store state
        context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_STEALTH_ENABLED, true).apply();

        Log.i(TAG, "Stealth mode enabled - app hidden from launcher");
    }

    /**
     * Disable stealth mode - shows app in launcher again.
     */
    public static void disableStealthMode(Context context) {
        Log.i(TAG, "Disabling stealth mode");

        PackageManager pm = context.getPackageManager();

        // Enable the launcher alias component
        ComponentName launcherAlias = new ComponentName(context, "dev.borges.shadow.SettingsActivityAlias");
        pm.setComponentEnabledSetting(
            launcherAlias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        );

        // Store state
        context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_STEALTH_ENABLED, false).apply();

        Log.i(TAG, "Stealth mode disabled - app visible in launcher");
    }

    /**
     * Check if stealth mode is currently enabled.
     */
    public static boolean isStealthModeEnabled(Context context) {
        return context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_STEALTH_ENABLED, false);
    }

    /**
     * Toggle stealth mode.
     */
    public static void setStealthMode(Context context, boolean enabled) {
        if (enabled) {
            enableStealthMode(context);
        } else {
            disableStealthMode(context);
        }
    }
}
