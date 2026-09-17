package dev.borges.shadow;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import dev.borges.shadow.util.SettingsHelper;

public final class TheftModeState {
    private static final String ACTIVE = "theft_mode_active";
    private static boolean stopRequested;

    private TheftModeState() {}

    public static boolean isActive(Context context) {
        boolean persisted = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
            .getBoolean(ACTIVE, false);
        int component = context.getPackageManager().getComponentEnabledSetting(
            new ComponentName(context, TheftModeActivity.class));
        return persisted || component == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    public static boolean isTriggerEnabled(Context context) {
        return "true".equals(SettingsHelper.getSetting(
            SettingsHelper.getEncryptedSharedPreferences(context), SettingsHelper.THEFT_MODE_ENABLED_KEY));
    }

    static void setActive(Context context, boolean active) {
        context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(ACTIVE, active).apply();
        if (active) {
            stopRequested = false;
            AdminSession.lock();
        }
    }

    static void requestStop() {
        stopRequested = true;
    }

    static boolean consumeStopRequest() {
        boolean requested = stopRequested;
        stopRequested = false;
        return requested;
    }
}
