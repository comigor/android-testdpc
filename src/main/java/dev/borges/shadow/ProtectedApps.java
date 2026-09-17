package dev.borges.shadow;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserManager;
import android.util.Base64;
import android.util.Log;

import com.afwsamples.testdpc.DeviceAdminReceiver;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import dev.borges.shadow.util.SettingsHelper;

/**
 * PIN-gated temporary access to a set of apps hidden via setApplicationHidden.
 * Best-effort availability control: app data stays in the same user.
 */
public final class ProtectedApps {
    private static final String TAG = "ProtectedApps";

    private static final String PREFS = "shadow_prefs";
    private static final String PREF_PACKAGES = "protected_apps";
    private static final String PREF_UNLOCK_UNTIL = "protected_apps_unlock_until";
    private static final String PREF_FAILURES = "protected_apps_pin_failures";
    private static final String PREF_BACKOFF_UNTIL = "protected_apps_backoff_until";

    public static final String ACTION_EXPIRE = "dev.borges.shadow.PROTECTED_APPS_EXPIRE";

    private static final int FREE_ATTEMPTS = 5;
    private static final long[] BACKOFF_MS = {30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L};

    private static final int PBKDF2_ITERATIONS = 65536;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private ProtectedApps() {}

    // ============ Configuration ============

    public static Set<String> getPackages(Context context) {
        return new HashSet<>(prefs(context).getStringSet(PREF_PACKAGES, new HashSet<>()));
    }

    public static void setPackages(Context context, Set<String> packages) {
        prefs(context).edit().putStringSet(PREF_PACKAGES, new HashSet<>(packages)).apply();
    }

    public static boolean isEnabled(Context context) {
        return "true".equals(SettingsHelper.getSetting(
            SettingsHelper.getEncryptedSharedPreferences(context), SettingsHelper.PROTECTED_APPS_ENABLED_KEY));
    }

    public static boolean isPinSet(Context context) {
        String hash = SettingsHelper.getSetting(
            SettingsHelper.getEncryptedSharedPreferences(context), SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY);
        return hash != null && !hash.isEmpty();
    }

    public static void setPin(Context context, String pin) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        String encoded = Base64.encodeToString(salt, Base64.NO_WRAP) + ":"
            + Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP);
        SettingsHelper.setSetting(SettingsHelper.getEncryptedSharedPreferences(context),
            SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, encoded);
        resetFailures(context);
    }

    public static int getWindowMinutes(Context context) {
        return SettingsHelper.parseInt(SettingsHelper.getSetting(
            SettingsHelper.getEncryptedSharedPreferences(context), SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY), 10);
    }

    // ============ PIN verification ============

    /** Milliseconds until another attempt is allowed; 0 when not throttled. */
    public static long getBackoffRemainingMs(Context context) {
        return Math.max(0L, prefs(context).getLong(PREF_BACKOFF_UNTIL, 0L) - System.currentTimeMillis());
    }

    public static boolean verifyPin(Context context, String pin) {
        if (getBackoffRemainingMs(context) > 0) {
            return false;
        }
        String stored = SettingsHelper.getSetting(
            SettingsHelper.getEncryptedSharedPreferences(context), SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY);
        String[] parts = stored == null ? new String[0] : stored.split(":");
        boolean ok = parts.length == 2
            && Arrays.equals(derive(pin, Base64.decode(parts[0], Base64.NO_WRAP)), Base64.decode(parts[1], Base64.NO_WRAP));
        if (ok) {
            resetFailures(context);
        } else {
            recordFailure(context);
        }
        return ok;
    }

    private static void recordFailure(Context context) {
        SharedPreferences prefs = prefs(context);
        int failures = prefs.getInt(PREF_FAILURES, 0) + 1;
        SharedPreferences.Editor editor = prefs.edit().putInt(PREF_FAILURES, failures);
        if (failures >= FREE_ATTEMPTS) {
            long backoff = BACKOFF_MS[Math.min(failures - FREE_ATTEMPTS, BACKOFF_MS.length - 1)];
            editor.putLong(PREF_BACKOFF_UNTIL, System.currentTimeMillis() + backoff);
            Log.w(TAG, "PIN failure #" + failures + ", backing off " + backoff / 1000 + "s");
        }
        editor.apply();
    }

    private static void resetFailures(Context context) {
        prefs(context).edit().remove(PREF_FAILURES).remove(PREF_BACKOFF_UNTIL).apply();
    }

    private static byte[] derive(String pin, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    // ============ Reveal / lock state ============

    /** True while an access window is open. */
    public static boolean isUnlocked(Context context) {
        return prefs(context).getLong(PREF_UNLOCK_UNTIL, 0L) > System.currentTimeMillis();
    }

    public static long getUnlockUntil(Context context) {
        return prefs(context).getLong(PREF_UNLOCK_UNTIL, 0L);
    }

    /** True when this package must stay hidden right now. */
    public static boolean isLocked(Context context, String packageName) {
        return isEnabled(context) && !isUnlocked(context) && getPackages(context).contains(packageName);
    }

    public static void reveal(Context context) {
        if (!onOwnerUser(context) || PowerButtonReceiver.isTheftModePending(context)) {
            Log.w(TAG, "reveal refused: not owner user or theft mode pending");
            return;
        }
        long until = System.currentTimeMillis() + getWindowMinutes(context) * 60_000L;
        prefs(context).edit().putLong(PREF_UNLOCK_UNTIL, until).apply();
        setHidden(context, getPackages(context), false);
        scheduleExpiry(context, until);
        Log.i(TAG, "Revealed protected apps until " + until);
    }

    public static void lock(Context context) {
        prefs(context).edit().remove(PREF_UNLOCK_UNTIL).apply();
        cancelExpiry(context);
        if (isEnabled(context) && onOwnerUser(context)) {
            setHidden(context, getPackages(context), true);
        }
        Log.i(TAG, "Locked protected apps");
    }

    /** Feature turned off: drop any window and make the apps visible again. */
    public static void disable(Context context) {
        prefs(context).edit().remove(PREF_UNLOCK_UNTIL).apply();
        cancelExpiry(context);
        if (onOwnerUser(context)) {
            setHidden(context, getPackages(context), false);
        }
    }

    /** Re-hide if the window is over; keeps the alarm honest after reboot or missed ticks. */
    public static void enforce(Context context) {
        if (!isEnabled(context) || !onOwnerUser(context)) {
            return;
        }
        if (isUnlocked(context)) {
            scheduleExpiry(context, getUnlockUntil(context));
        } else {
            lock(context);
        }
    }

    /** Called when a package is added to or removed from the protected set. */
    public static void applyMembership(Context context, String packageName, boolean protectedNow) {
        if (!onOwnerUser(context)) {
            return;
        }
        boolean hide = protectedNow && isEnabled(context) && !isUnlocked(context);
        setHidden(context, Set.of(packageName), hide);
    }

    public static void setLauncherIconVisible(Context context, boolean visible) {
        ComponentName vault = new ComponentName(context, ProtectedAppsActivity.class);
        context.getPackageManager().setComponentEnabledSetting(vault,
            visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
    }

    private static void setHidden(Context context, Set<String> packages, boolean hidden) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);
        for (String pkg : packages) {
            try {
                dpm.setApplicationHidden(admin, pkg, hidden);
            } catch (Exception e) {
                Log.e(TAG, "setApplicationHidden(" + pkg + ", " + hidden + ") failed", e);
            }
        }
    }

    private static boolean onOwnerUser(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return context.getSystemService(UserManager.class).isSystemUser()
            && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    private static void scheduleExpiry(Context context, long atMillis) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, expiryIntent(context));
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, expiryIntent(context));
        }
    }

    private static void cancelExpiry(Context context) {
        context.getSystemService(AlarmManager.class).cancel(expiryIntent(context));
    }

    private static PendingIntent expiryIntent(Context context) {
        Intent intent = new Intent(context, ProtectedAppsReceiver.class).setAction(ACTION_EXPIRE);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
