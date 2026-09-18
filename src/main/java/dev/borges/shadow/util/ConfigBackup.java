package dev.borges.shadow.util;

import android.app.admin.DevicePolicyManager;
import android.app.admin.FactoryResetProtectionPolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.afwsamples.testdpc.DeviceAdminReceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.borges.shadow.BluetoothWatchReceiver;
import dev.borges.shadow.ProtectedApps;
import dev.borges.shadow.StealthModeManager;

public final class ConfigBackup {
    public static final int FORMAT = 1;
    public static final String MIME_TYPE = "application/json";

    private static final String KEY_FORMAT = "format";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_THEFT_HIDDEN = "theft_hidden_apps";
    private static final String KEY_AUTO_KILL = "auto_kill_apps";
    private static final String KEY_PROTECTED = "protected_apps";
    private static final String KEY_STEALTH = "stealth_mode";

    private static final String PREF_THEFT_HIDDEN = "apps_to_hide_on_theft";
    private static final String PREF_AUTO_KILL = "apps_to_auto_kill";
    private static final String PREF_PROTECTED = "protected_apps";

    // Secrets and runtime state never leave the device.
    private static final Set<String> EXCLUDED_SETTINGS = Set.of(
        SettingsHelper.PROTECTED_APPS_PIN_HASH_KEY,
        SettingsHelper.WRIST_LAST_KNOWN_STATE_KEY,
        SettingsHelper.WRIST_LAST_UPDATE_TIME_KEY);

    public static final class Result {
        public final int applied;
        public final List<String> warnings;

        Result(int applied, List<String> warnings) {
            this.applied = applied;
            this.warnings = warnings;
        }
    }

    private ConfigBackup() {}

    public static Set<String> exportableKeys() {
        Set<String> keys = new LinkedHashSet<>(SettingsHelper.DEFAULTS.keySet());
        keys.removeAll(EXCLUDED_SETTINGS);
        return keys;
    }

    public static String export(Context context) {
        SharedPreferences secure = SettingsHelper.getEncryptedSharedPreferences(context);
        SharedPreferences plain = plain(context);
        try {
            JSONObject settings = new JSONObject();
            for (String key : exportableKeys()) {
                String value = secure.getString(key, null);
                if (value != null) settings.put(key, value);
            }
            return new JSONObject()
                .put(KEY_FORMAT, FORMAT)
                .put(KEY_SETTINGS, settings)
                .put(KEY_THEFT_HIDDEN, sorted(plain.getStringSet(PREF_THEFT_HIDDEN, Set.of())))
                .put(KEY_AUTO_KILL, sorted(plain.getStringSet(PREF_AUTO_KILL, Set.of())))
                .put(KEY_PROTECTED, sorted(plain.getStringSet(PREF_PROTECTED, Set.of())))
                .put(KEY_STEALTH, StealthModeManager.isStealthModeEnabled(context))
                .toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Result importConfig(Context context, String json) {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Not a Shadow configuration file");
        }
        if (root.optInt(KEY_FORMAT, -1) != FORMAT) {
            throw new IllegalArgumentException("Unsupported configuration format " + root.opt(KEY_FORMAT));
        }
        JSONObject settings = root.optJSONObject(KEY_SETTINGS);
        if (settings == null) {
            throw new IllegalArgumentException("Configuration has no settings section");
        }

        List<String> warnings = new ArrayList<>();
        Set<String> allowed = exportableKeys();
        SharedPreferences.Editor secure = SettingsHelper.getEncryptedSharedPreferences(context).edit();
        int applied = 0;
        for (Iterator<String> it = settings.keys(); it.hasNext(); ) {
            String key = it.next();
            Object value = settings.opt(key);
            if (!allowed.contains(key)) {
                warnings.add("Ignored unknown setting " + key);
                continue;
            }
            if (!(value instanceof String)) {
                warnings.add("Ignored non-text value for " + key);
                continue;
            }
            secure.putString(key, ((String) value).isEmpty() ? null : (String) value);
            applied++;
        }
        if (!ProtectedApps.isPinSet(context)
            && "true".equals(settings.optString(SettingsHelper.PROTECTED_APPS_ENABLED_KEY))) {
            secure.putString(SettingsHelper.PROTECTED_APPS_ENABLED_KEY, "false");
            warnings.add("Protected apps left disabled: set a Vault PIN first");
        }
        if (!secure.commit()) {
            throw new IllegalStateException("Could not save settings");
        }

        Set<String> oldProtected = ProtectedApps.getPackages(context);
        SharedPreferences.Editor plain = plain(context).edit();
        if (root.has(KEY_THEFT_HIDDEN)) plain.putStringSet(PREF_THEFT_HIDDEN, packages(root, KEY_THEFT_HIDDEN, context));
        if (root.has(KEY_AUTO_KILL)) plain.putStringSet(PREF_AUTO_KILL, packages(root, KEY_AUTO_KILL, context));
        if (root.has(KEY_PROTECTED)) plain.putStringSet(PREF_PROTECTED, packages(root, KEY_PROTECTED, context));
        plain.commit();

        applySideEffects(context, root, oldProtected, warnings);
        return new Result(applied, warnings);
    }

    private static void applySideEffects(Context context, JSONObject root, Set<String> oldProtected, List<String> warnings) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = DeviceAdminReceiver.getComponentName(context);
        boolean owner = dpm.isDeviceOwnerApp(context.getPackageName());
        SharedPreferences secure = SettingsHelper.getEncryptedSharedPreferences(context);

        if (root.has(KEY_STEALTH)) {
            if (root.optBoolean(KEY_STEALTH)) StealthModeManager.enableStealthMode(context);
            else StealthModeManager.disableStealthMode(context);
        }

        if (owner) {
            String organization = SettingsHelper.getSetting(secure, SettingsHelper.ORGANIZATION_NAME_KEY);
            dpm.setOrganizationName(admin, organization.isEmpty() ? null : organization);
            applyFrp(dpm, admin, secure, warnings);
        }

        if ("true".equals(SettingsHelper.getSetting(secure, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY))) {
            BluetoothWatchReceiver.registerReceiver(context.getApplicationContext());
        }

        for (String pkg : oldProtected) {
            if (!ProtectedApps.getPackages(context).contains(pkg)) ProtectedApps.applyMembership(context, pkg, false);
        }
        if (ProtectedApps.isEnabled(context)) {
            ProtectedApps.lock(context);
        } else {
            ProtectedApps.disable(context);
        }
        ProtectedApps.setLauncherIconVisible(context, ProtectedApps.isEnabled(context));
    }

    private static void applyFrp(DevicePolicyManager dpm, ComponentName admin, SharedPreferences secure, List<String> warnings) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        boolean enabled = "true".equals(SettingsHelper.getSetting(secure, SettingsHelper.FRP_ENABLED_KEY));
        List<String> ids = Arrays.stream(SettingsHelper.getSetting(secure, SettingsHelper.FRP_ACCOUNT_IDS).split(","))
            .map(String::trim).filter(s -> s.length() == 21).collect(Collectors.toList());
        if (enabled && ids.isEmpty()) {
            warnings.add("FRP not applied: no valid Google account IDs");
            return;
        }
        try {
            dpm.setFactoryResetProtectionPolicy(admin, new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(ids)
                .setFactoryResetProtectionEnabled(enabled)
                .build());
        } catch (RuntimeException e) {
            warnings.add("FRP not applied: " + e.getMessage());
        }
    }

    private static Set<String> packages(JSONObject root, String key, Context context) {
        JSONArray array = root.optJSONArray(key);
        if (array == null) throw new IllegalArgumentException(key + " must be a list");
        Set<String> result = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String pkg = array.optString(i, "");
            if (pkg.isEmpty() || pkg.equals(context.getPackageName())) {
                throw new IllegalArgumentException(key + " contains an invalid package entry");
            }
            result.add(pkg);
        }
        return result;
    }

    private static JSONArray sorted(Set<String> values) {
        return new JSONArray(values.stream().sorted().collect(Collectors.toList()));
    }

    private static SharedPreferences plain(Context context) {
        return context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
    }
}
