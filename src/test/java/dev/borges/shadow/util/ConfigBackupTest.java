package dev.borges.shadow.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import dev.borges.shadow.ProtectedApps;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = ConfigBackupTest.SecureSettings.class)
public class ConfigBackupTest {
    private Context context;
    private SharedPreferences secure;
    private SharedPreferences plain;

    @Before
    public void configure() {
        context = RuntimeEnvironment.getApplication();
        secure = context.getSharedPreferences("test_secure_settings", Context.MODE_PRIVATE);
        plain = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        secure.edit().putString(SettingsHelper.ACTIVATION_DELAY_KEY, "480")
            .putString(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, PasswordHash.create("1234"))
            .putString(SettingsHelper.WRIST_LAST_KNOWN_STATE_KEY, "worn").commit();
        plain.edit().putStringSet("apps_to_hide_on_theft", Set.of("com.bank")).commit();
    }

    @Test
    public void exportOmitsSecretsAndRuntimeStateButRoundTripsConfiguration() throws Exception {
        String json = ConfigBackup.export(context);
        JSONObject settings = new JSONObject(json).getJSONObject("settings");
        assertFalse(settings.has(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY));
        assertFalse(settings.has(SettingsHelper.WRIST_LAST_KNOWN_STATE_KEY));
        assertEquals("480", settings.getString(SettingsHelper.ACTIVATION_DELAY_KEY));

        secure.edit().putString(SettingsHelper.ACTIVATION_DELAY_KEY, "10").commit();
        plain.edit().remove("apps_to_hide_on_theft").commit();
        ConfigBackup.Result result = ConfigBackup.importConfig(context, json);

        assertEquals("480", secure.getString(SettingsHelper.ACTIVATION_DELAY_KEY, null));
        assertEquals(Set.of("com.bank"), plain.getStringSet("apps_to_hide_on_theft", Set.of()));
        assertTrue(ProtectedApps.isPinSet(context));
        assertTrue(result.warnings.isEmpty());
    }

    @Test
    public void importIgnoresUnknownKeysAndCannotInjectSecrets() throws Exception {
        String original = secure.getString(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, null);
        String json = new JSONObject().put("format", 1).put("settings", new JSONObject()
            .put(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, PasswordHash.create("0000"))
            .put("bogus_key", "x")
            .put(SettingsHelper.POWER_BUTTON_PRESSES_KEY, "3")).toString();
        ConfigBackup.Result result = ConfigBackup.importConfig(context, json);
        assertEquals(original, secure.getString(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY, null));
        assertEquals("3", secure.getString(SettingsHelper.POWER_BUTTON_PRESSES_KEY, null));
        assertEquals(1, result.applied);
        assertEquals(2, result.warnings.size());
    }

    @Test
    public void importKeepsProtectedAppsDisabledWhenNoPinExists() throws Exception {
        secure.edit().remove(SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY).commit();
        String json = new JSONObject().put("format", 1).put("settings", new JSONObject()
            .put(SettingsHelper.PROTECTED_APPS_ENABLED_KEY, "true")).toString();
        ConfigBackup.Result result = ConfigBackup.importConfig(context, json);
        assertFalse(ProtectedApps.isEnabled(context));
        assertEquals(1, result.warnings.size());
    }

    @Test
    public void importRejectsForeignOrSelfHidingFiles() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ConfigBackup.importConfig(context, "{\"format\":2}"));
        assertThrows(IllegalArgumentException.class, () -> ConfigBackup.importConfig(context, "not json"));
        String selfHide = new JSONObject().put("format", 1).put("settings", new JSONObject())
            .put("theft_hidden_apps", new org.json.JSONArray().put(context.getPackageName())).toString();
        assertThrows(IllegalArgumentException.class, () -> ConfigBackup.importConfig(context, selfHide));
        assertEquals(Set.of("com.bank"), plain.getStringSet("apps_to_hide_on_theft", Set.of()));
    }

    @Implements(SettingsHelper.class)
    public static class SecureSettings {
        @Implementation
        protected static SharedPreferences getEncryptedSharedPreferences(Context context) {
            return context.getSharedPreferences("test_secure_settings", Context.MODE_PRIVATE);
        }
    }
}
