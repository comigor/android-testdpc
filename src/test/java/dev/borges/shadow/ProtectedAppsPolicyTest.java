package dev.borges.shadow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import dev.borges.shadow.util.PasswordHash;
import dev.borges.shadow.util.SettingsHelper;
import java.time.Duration;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = ProtectedAppsPolicyTest.SecureSettings.class)
public class ProtectedAppsPolicyTest {
    private Context context;
    private SharedPreferences secure;
    private static final String PACKAGE = "example.bank";

    @Before
    public void configure() {
        context = RuntimeEnvironment.getApplication();
        secure = context.getSharedPreferences("test_secure_settings", Context.MODE_PRIVATE);
        secure.edit().putString(SettingsHelper.PROTECTED_APPS_ENABLED_KEY, "true")
            .putString(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, PasswordHash.create("135790"))
            .putString(SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY, "10").commit();
        ProtectedApps.setPackages(context, Collections.singleton(PACKAGE));
    }

    @Test
    public void activeTheftOverridesAnOtherwiseValidAccessWindow() {
        openWindow(600_000);
        assertFalse(ProtectedApps.mustStayHidden(context, PACKAGE));
        TheftModeState.setActive(context, true);
        assertTrue(ProtectedApps.mustStayHidden(context, PACKAGE));
        assertFalse(ProtectedApps.isUnlocked(context));
    }

    @Test
    public void autoKillCannotRevealATheftHiddenAppOutsideProtectedSet() {
        context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE).edit()
            .putStringSet("apps_to_hide_on_theft", Collections.singleton("example.other")).commit();
        TheftModeState.setActive(context, true);
        assertTrue(ProtectedApps.mustStayHidden(context, "example.other"));
        TheftModeState.setActive(context, false);
        assertFalse(ProtectedApps.mustStayHidden(context, "example.other"));
    }

    @Test
    public void windowExpiresOnElapsedTimeAndCannotSurviveReboot() {
        openWindow(60_000);
        assertTrue(ProtectedApps.isUnlocked(context));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61));
        assertTrue(ProtectedApps.isLocked(context, PACKAGE));
        openWindow(60_000);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 2);
        assertFalse(ProtectedApps.isUnlocked(context));
    }

    @Test
    public void fifthWrongPinLocksOutCorrectPinAndSuccessfulRetryResetsFailures() {
        for (int i = 0; i < 5; i++) assertFalse(ProtectedApps.verifyPin(context, "000000"));
        assertTrue(ProtectedApps.getBackoffRemainingMs(context) > 0);
        assertFalse(ProtectedApps.verifyPin(context, "135790"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
        assertTrue(ProtectedApps.verifyPin(context, "135790"));
        assertEquals(0, ProtectedApps.getBackoffRemainingMs(context));
    }

    @Test
    public void legacyPinThrottleIsPreservedDuringEncryptedMigration() {
        SharedPreferences legacy = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        legacy.edit().putInt("protected_apps_pin_failures", 5)
            .putLong("protected_apps_backoff_until", System.currentTimeMillis() + 30_000).commit();
        assertFalse(ProtectedApps.verifyPin(context, "135790"));
        assertFalse(legacy.contains("protected_apps_pin_failures"));
        assertFalse(legacy.contains("protected_apps_backoff_until"));
        assertEquals(5, secure.getInt("protected_apps_pin_failures", 0));
    }

    @Test
    public void restartBroadcastCancelsRemainingWindow() {
        openWindow(600_000);
        new ProtectedAppsReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertFalse(ProtectedApps.isUnlocked(context));
    }

    private void openWindow(long millis) {
        context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE).edit()
            .putInt("protected_apps_unlock_boot", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0))
            .putLong("protected_apps_unlock_elapsed", SystemClock.elapsedRealtime() + millis).commit();
    }

    @Implements(SettingsHelper.class)
    public static class SecureSettings {
        @Implementation
        protected static SharedPreferences getEncryptedSharedPreferences(Context context) {
            return context.getSharedPreferences("test_secure_settings", Context.MODE_PRIVATE);
        }
    }
}
