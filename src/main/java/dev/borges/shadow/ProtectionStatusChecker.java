package dev.borges.shadow;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.afwsamples.testdpc.DeviceAdminReceiver;

import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.SettingsHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the status of all protection features.
 */
public class ProtectionStatusChecker {

    public enum State {
        OK,      // Feature is properly configured
        WARNING, // Feature needs attention
        ERROR,   // Feature is not configured
        INACTIVE // Feature is disabled/not applicable
    }

    public static class Status {
        public String label;
        public State state;
        public String statusText;
        public Runnable configureAction; // Optional action when tapped

        public Status(String label, State state, String statusText) {
            this.label = label;
            this.state = state;
            this.statusText = statusText;
        }
    }

    private final Context context;
    private final DevicePolicyManager dpm;
    private final SharedPreferences shadowPrefs;
    private final SharedPreferences settingsPrefs;

    public ProtectionStatusChecker(Context context) {
        this.context = context;
        this.dpm = context.getSystemService(DevicePolicyManager.class);
        this.shadowPrefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        this.settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(context);
    }

    public List<Status> checkAllStatuses() {
        List<Status> statuses = new ArrayList<>();
        statuses.add(checkDeviceOwner());
        statuses.add(checkAppPassword());
        statuses.add(checkDecoyProfile());
        statuses.add(checkSmsPermission());
        statuses.add(checkAccessibility());
        statuses.add(checkWatchConnection());
        statuses.add(checkTheftMode());
        return statuses;
    }

    public Status checkDeviceOwner() {
        boolean isOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        return new Status(
            "Device Owner",
            isOwner ? State.OK : State.ERROR,
            isOwner ? "Configured" : "Not Set"
        );
    }

    public Status checkAppPassword() {
        String hash = PasswordHelper.retrievePasswordHash(context);
        boolean hasPassword = hash != null && !hash.isEmpty();
        return new Status(
            "App Password",
            hasPassword ? State.OK : State.WARNING,
            hasPassword ? "Set" : "Not Set"
        );
    }

    public Status checkDecoyProfile() {
        long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
        boolean hasDecoy = decoySerial != -1;
        return new Status(
            "Decoy Profile",
            hasDecoy ? State.OK : State.WARNING,
            hasDecoy ? "Created" : "Not Created"
        );
    }

    public Status checkSmsPermission() {
        boolean granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            == PackageManager.PERMISSION_GRANTED;
        return new Status(
            "SMS Commands",
            granted ? State.OK : State.WARNING,
            granted ? "Enabled" : "Disabled"
        );
    }

    public Status checkAccessibility() {
        boolean enabled = POffService.isAccessibilityServiceEnabled(context);
        return new Status(
            "Power Off Block",
            enabled ? State.OK : State.WARNING,
            enabled ? "Active" : "Disabled"
        );
    }

    public Status checkWatchConnection() {
        String watchAddress = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY);
        String watchName = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DEVICE_NAME_KEY);
        boolean watchEnabled = "true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_ENABLED_KEY));

        if (watchAddress == null || watchAddress.isEmpty()) {
            return new Status("Watch", State.INACTIVE, "Not Configured");
        }

        if (!watchEnabled) {
            return new Status("Watch", State.INACTIVE, "Disabled");
        }

        // Check if watch is currently connected
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.isEnabled()) {
                BluetoothDevice device = adapter.getRemoteDevice(watchAddress);
                // Note: We can't reliably check connection state without BLUETOOTH_CONNECT permission
                // Just show configured status
                return new Status("Watch", State.OK, watchName);
            }
        } catch (Exception e) {
            // Ignore
        }

        return new Status("Watch", State.OK, watchName);
    }

    public Status checkTheftMode() {
        long activationTime = shadowPrefs.getLong("theft_mode_activation_time", 0);
        if (activationTime > 0) {
            long remaining = (activationTime - System.currentTimeMillis()) / 1000;
            if (remaining > 0) {
                return new Status("Theft Mode", State.WARNING, "Activating in " + remaining + "s");
            } else {
                return new Status("Theft Mode", State.WARNING, "Pending");
            }
        }
        return new Status("Theft Mode", State.OK, "Standby");
    }
}
