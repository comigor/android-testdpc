package dev.borges.shadow;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.borges.shadow.util.SettingsHelper;

public class WatchProtectionActivity extends SubSettingsActivity {

    private WatchAppInstaller watchAppInstaller;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        watchAppInstaller = new WatchAppInstaller(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateSettings();
    }

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        addDescription("Triggers theft mode when your smartwatch disconnects from the phone.");

        // Watch device selection
        String currentName = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_NAME_KEY);
        String label = currentName.isEmpty() ? "Select watch device" : "Watch: " + currentName;
        addClickableItem(label, this::showWatchSelectionDialog);

        // Disconnect timeout
        String timeout = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY);
        addNumberInputRow("Disconnect timeout (seconds)", timeout,
            v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY, v));

        addSectionTitle("Watch Companion App");

        // Install watch app
        addClickableItem("Install/Update Watch App", () -> {
            Toast.makeText(this, "Sending watch app...", Toast.LENGTH_SHORT).show();
            watchAppInstaller.sendWatchApk(new WatchAppInstaller.InstallCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> new AlertDialog.Builder(WatchProtectionActivity.this)
                        .setTitle("Watch App").setMessage(message).setPositiveButton("OK", null).show());
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(WatchProtectionActivity.this, error, Toast.LENGTH_LONG).show());
                }
                @Override
                public void onProgress(String status) {
                    runOnUiThread(() -> Toast.makeText(WatchProtectionActivity.this, status, Toast.LENGTH_SHORT).show());
                }
            });
        });

        // Check watch app status
        addClickableItem("Check Watch App Status", () -> {
            watchAppInstaller.checkWatchAppInstalled(new WatchAppInstaller.InstallCallback() {
                @Override
                public void onSuccess(String message) {
                    runOnUiThread(() -> new AlertDialog.Builder(WatchProtectionActivity.this)
                        .setTitle("Watch App Status").setMessage(message).setPositiveButton("OK", null).show());
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> new AlertDialog.Builder(WatchProtectionActivity.this)
                        .setTitle("Watch App Status").setMessage(error + "\n\nUse 'Install/Update Watch App' to install.")
                        .setPositiveButton("OK", null).show());
                }
                @Override
                public void onProgress(String status) {}
            });
        });
    }

    private void showWatchSelectionDialog() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 200);
                return;
            }
        }

        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show();
            return;
        }

        List<String> names = new ArrayList<>();
        List<String> addresses = new ArrayList<>();
        for (BluetoothDevice device : pairedDevices) {
            names.add(device.getName() != null ? device.getName() : device.getAddress());
            addresses.add(device.getAddress());
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Watch")
            .setItems(names.toArray(new String[0]), (dialog, which) -> {
                SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_NAME_KEY, names.get(which));
                SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WATCH_DEVICE_ADDRESS_KEY, addresses.get(which));
                Toast.makeText(this, "Selected: " + names.get(which), Toast.LENGTH_SHORT).show();
                populateSettings();
            })
            .show();
    }
}
