package dev.borges.shadow;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import dev.borges.shadow.util.PasswordHash;
import dev.borges.shadow.util.SettingsHelper;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ProtectedApps {
    private static final String TAG = "ProtectedApps";
    private static final String PREF_PACKAGES = "protected_apps";
    private static final String PREF_UNLOCK_UNTIL = "protected_apps_unlock_until";
    private static final String PREF_UNLOCK_ELAPSED = "protected_apps_unlock_elapsed";
    private static final String PREF_UNLOCK_BOOT = "protected_apps_unlock_boot";
    private static final String PREF_FAILURES = "protected_apps_pin_failures";
    private static final String PREF_BACKOFF_UNTIL = "protected_apps_backoff_until";
    private static final String PREF_BACKOFF_ELAPSED = "protected_apps_backoff_elapsed";
    private static final String PREF_BACKOFF_BOOT = "protected_apps_backoff_boot";
    private static final long[] BACKOFF_MS = {30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L};
    public static final String ACTION_EXPIRE = "dev.borges.shadow.PROTECTED_APPS_EXPIRE";

    private ProtectedApps() {}

    public static Set<String> getPackages(Context context) {
        return new HashSet<>(prefs(context).getStringSet(PREF_PACKAGES, Collections.emptySet()));
    }

    public static void setPackages(Context context, Set<String> packages) {
        if (packages.contains(context.getPackageName())) {
            throw new IllegalArgumentException("Shadow cannot hide itself");
        }
        prefs(context).edit().putStringSet(PREF_PACKAGES, new HashSet<>(packages)).apply();
    }

    public static boolean isEnabled(Context context) {
        return "true".equals(SettingsHelper.getSetting(secure(context), SettingsHelper.PROTECTED_APPS_ENABLED_KEY));
    }

    public static boolean isPinSet(Context context) {
        String hash = SettingsHelper.getSetting(secure(context), SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY);
        if (hash == null || hash.isEmpty()) return false;
        PasswordHash.validate(hash);
        return true;
    }

    public static void setPin(Context context, String pin) {
        if (!pin.matches("[0-9]{4,}")) {
            throw new IllegalArgumentException("PIN must contain at least four digits");
        }
        if (!secure(context).edit().putString(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, PasswordHash.create(pin))
            .remove(PREF_FAILURES).remove(PREF_BACKOFF_UNTIL).remove(PREF_BACKOFF_ELAPSED).commit()) {
            throw new IllegalStateException("Could not save PIN");
        }
        clearLegacyFailures(context);
        lock(context);
    }

    public static int getWindowMinutes(Context context) {
        int minutes = Integer.parseInt(SettingsHelper.getSetting(secure(context), SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY));
        if (minutes < 1) throw new IllegalStateException("Access window must be positive");
        return minutes;
    }

    public static void setWindowMinutes(Context context, String value) {
        int minutes = Integer.parseInt(value.trim());
        if (minutes < 1) throw new IllegalArgumentException("Enter a positive number of minutes");
        SettingsHelper.setSetting(secure(context), SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY, String.valueOf(minutes));
    }

    public static long getBackoffRemainingMs(Context context) {
        SharedPreferences state = throttle(context);
        if (state.getInt(PREF_BACKOFF_BOOT, -1) == bootCount(context)) {
            return Math.max(0, state.getLong(PREF_BACKOFF_ELAPSED, 0) - SystemClock.elapsedRealtime());
        }
        long remaining = Math.max(0, Math.min(BACKOFF_MS[BACKOFF_MS.length - 1],
            state.getLong(PREF_BACKOFF_UNTIL, 0) - System.currentTimeMillis()));
        state.edit().putInt(PREF_BACKOFF_BOOT, bootCount(context))
            .putLong(PREF_BACKOFF_ELAPSED, SystemClock.elapsedRealtime() + remaining).apply();
        return remaining;
    }

    public static synchronized boolean verifyPin(Context context, String pin) {
        if (getBackoffRemainingMs(context) > 0) return false;
        String hash = SettingsHelper.getSetting(secure(context), SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY);
        if (hash == null || hash.isEmpty()) return false;
        SharedPreferences state = throttle(context);
        if (PasswordHash.matches(pin, hash)) {
            state.edit().remove(PREF_FAILURES).remove(PREF_BACKOFF_UNTIL)
                .remove(PREF_BACKOFF_ELAPSED).apply();
            return true;
        }
        int failures = Math.min(9, state.getInt(PREF_FAILURES, 0) + 1);
        SharedPreferences.Editor editor = state.edit().putInt(PREF_FAILURES, failures);
        if (failures >= 5) {
            long delay = BACKOFF_MS[failures - 5];
            editor.putLong(PREF_BACKOFF_UNTIL, System.currentTimeMillis() + delay)
                .putLong(PREF_BACKOFF_ELAPSED, SystemClock.elapsedRealtime() + delay)
                .putInt(PREF_BACKOFF_BOOT, bootCount(context));
        }
        if (!editor.commit()) throw new IllegalStateException("Could not persist PIN lockout");
        return false;
    }

    public static boolean isUnlocked(Context context) {
        SharedPreferences state = prefs(context);
        return state.getInt(PREF_UNLOCK_BOOT, -1) == bootCount(context)
            && state.getLong(PREF_UNLOCK_ELAPSED, 0) > SystemClock.elapsedRealtime()
            && !TheftModeState.isActive(context);
    }

    public static long getRemainingWindowMs(Context context) {
        return isUnlocked(context) ? Math.max(0, prefs(context).getLong(PREF_UNLOCK_ELAPSED, 0)
            - SystemClock.elapsedRealtime()) : 0;
    }

    public static boolean isLocked(Context context, String packageName) {
        return isEnabled(context) && !isUnlocked(context) && getPackages(context).contains(packageName);
    }

    public static boolean mustStayHidden(Context context, String packageName) {
        return isLocked(context, packageName)
            || (TheftModeState.isActive(context) && HiddenAppsActivity.getAppsToHide(context).contains(packageName));
    }

    public static boolean canReveal(Context context) {
        return onOwnerUser(context) && isEnabled(context) && isPinSet(context)
            && !TheftModeState.isActive(context)
            && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    public static void reveal(Context context) {
        if (!canReveal(context)) throw new IllegalStateException("Protected apps are locked by device policy");
        long duration = getWindowMinutes(context) * 60_000L;
        long deadline = SystemClock.elapsedRealtime() + duration;
        // Arm expiry before exposing apps; an inexact fallback cannot enforce the access window.
        scheduleExpiry(context, deadline);
        prefs(context).edit().putLong(PREF_UNLOCK_UNTIL, System.currentTimeMillis() + duration)
            .putLong(PREF_UNLOCK_ELAPSED, deadline).putInt(PREF_UNLOCK_BOOT, bootCount(context)).apply();
        if (!setHidden(context, getPackages(context), false)) {
            lock(context);
            throw new IllegalStateException("Could not reveal every protected app");
        }
    }

    public static void lock(Context context) {
        prefs(context).edit().remove(PREF_UNLOCK_UNTIL).remove(PREF_UNLOCK_ELAPSED)
            .remove(PREF_UNLOCK_BOOT).apply();
        cancelExpiry(context);
        if (onOwnerUser(context) && isEnabled(context)) {
            setHidden(context, getPackages(context), true);
        }
    }

    public static void disable(Context context) {
        prefs(context).edit().remove(PREF_UNLOCK_UNTIL).remove(PREF_UNLOCK_ELAPSED)
            .remove(PREF_UNLOCK_BOOT).apply();
        cancelExpiry(context);
        if (onOwnerUser(context)) {
            for (String pkg : getPackages(context)) {
                boolean theftHidden = TheftModeState.isActive(context) && HiddenAppsActivity.getAppsToHide(context).contains(pkg);
                setHidden(context, Collections.singleton(pkg), theftHidden);
            }
        }
    }

    public static void enforce(Context context) {
        if (!isEnabled(context) || !onOwnerUser(context)) return;
        if (isUnlocked(context) && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            try {
                scheduleExpiry(context, prefs(context).getLong(PREF_UNLOCK_ELAPSED, 0));
            } catch (SecurityException e) {
                lock(context);
            }
        } else {
            lock(context);
        }
        setLauncherIconVisible(context, true);
    }

    public static void applyMembership(Context context, String packageName, boolean protectedNow) {
        if (!onOwnerUser(context)) return;
        setHidden(context, Collections.singleton(packageName), mustStayHidden(context, packageName));
    }

    public static void setLauncherIconVisible(Context context, boolean visible) {
        context.getPackageManager().setComponentEnabledSetting(new ComponentName(context, ProtectedAppsActivity.class),
            visible && !TheftModeState.isActive(context) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private static boolean setHidden(Context context, Set<String> packages, boolean hidden) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);
        boolean success = true;
        for (String pkg : packages) {
            try {
                if (!dpm.setApplicationHidden(admin, pkg, hidden)) {
                    success = false;
                    Log.e(TAG, "Device policy refused package visibility change: " + pkg);
                }
            } catch (RuntimeException e) {
                success = false;
                Log.e(TAG, "Package visibility change failed: " + pkg, e);
            }
        }
        return success;
    }

    private static boolean onOwnerUser(Context context) {
        return context.getSystemService(UserManager.class).isSystemUser()
            && context.getSystemService(DevicePolicyManager.class).isDeviceOwnerApp(context.getPackageName());
    }

    private static void scheduleExpiry(Context context, long elapsedDeadline) {
        AlarmManager alarm = context.getSystemService(AlarmManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            throw new SecurityException("Exact alarms must be enabled before revealing protected apps");
        }
        alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, expiryIntent(context));
    }

    private static void cancelExpiry(Context context) {
        context.getSystemService(AlarmManager.class).cancel(expiryIntent(context));
    }

    private static PendingIntent expiryIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0,
            new Intent(context, ProtectedAppsReceiver.class).setAction(ACTION_EXPIRE),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    private static SharedPreferences secure(Context context) {
        return SettingsHelper.getEncryptedSharedPreferences(context);
    }

    private static SharedPreferences throttle(Context context) {
        SharedPreferences target = secure(context);
        SharedPreferences legacy = prefs(context);
        if (legacy.contains(PREF_FAILURES) || legacy.contains(PREF_BACKOFF_UNTIL)) {
            if (!target.edit().putInt(PREF_FAILURES, Math.max(target.getInt(PREF_FAILURES, 0), legacy.getInt(PREF_FAILURES, 0)))
                .putLong(PREF_BACKOFF_UNTIL, Math.max(target.getLong(PREF_BACKOFF_UNTIL, 0), legacy.getLong(PREF_BACKOFF_UNTIL, 0)))
                .commit()) throw new IllegalStateException("Could not migrate PIN lockout");
            clearLegacyFailures(context);
        }
        return target;
    }

    private static void clearLegacyFailures(Context context) {
        prefs(context).edit().remove(PREF_FAILURES).remove(PREF_BACKOFF_UNTIL).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
    }
}
