package dev.borges.shadow;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.comp.DeviceOwnerService;
import com.afwsamples.testdpc.comp.IDeviceOwnerService;

import java.util.List;

public class DecoyProfileActivity extends SubSettingsActivity {
    private static final String TAG = "DecoyProfileActivity";

    private SharedPreferences shadowPrefs;

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();
        shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);

        UserManager um = getSystemService(UserManager.class);
        boolean isSystemUser = um != null && um.isSystemUser();

        if (isSystemUser) {
            if (isDeviceOwner) {
                // Show decoy status
                long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
                UserHandle existingUser = decoySerial != -1 ? um.getUserForSerialNumber(decoySerial) : null;

                if (existingUser != null) {
                    addDescription("Decoy profile \"System\" is active (serial: " + decoySerial + ")");
                    addClickableItem("Delete Decoy Profile", this::deleteDecoyProfile);
                } else {
                    addDescription("No decoy profile configured. Create one to enable the decoy switch feature.");
                    addClickableItem("Create Decoy Profile", this::createDecoyProfile);
                }
            } else {
                addDescription("Device Owner is required to manage decoy profiles.");
            }
        } else {
            // Secondary user (decoy profile)
            addDescription("You are currently in the decoy profile.");
            addClickableItem("Return to Owner", this::returnToRealProfile);
        }
    }

    private void createDecoyProfile() {
        if (!isDeviceOwner) {
            Toast.makeText(this, "Device owner required", Toast.LENGTH_SHORT).show();
            return;
        }

        UserManager um = getSystemService(UserManager.class);
        long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
        if (decoySerial != -1L) {
            UserHandle existingUser = um.getUserForSerialNumber(decoySerial);
            if (existingUser != null) {
                Toast.makeText(this, "Decoy profile already exists", Toast.LENGTH_SHORT).show();
                return;
            } else {
                shadowPrefs.edit().remove("decoy_serial").apply();
            }
        }

        Log.i(TAG, "Creating decoy profile...");
        String hackyName = "System";
        int flags = android.app.admin.DevicePolicyManager.SKIP_SETUP_WIZARD;

        UserHandle userHandle = dpm.createAndManageUser(
            adminComponentName, hackyName, adminComponentName, null, flags
        );
        if (userHandle == null) {
            Toast.makeText(this, "Failed to create decoy profile", Toast.LENGTH_SHORT).show();
            return;
        }

        long serial = um.getSerialNumberForUser(userHandle);
        shadowPrefs.edit().putLong("decoy_serial", serial).apply();
        Log.i(TAG, "Decoy profile created with serial: " + serial);

        int startResult = dpm.startUserInBackground(adminComponentName, userHandle);
        Log.i(TAG, "Started decoy user in background, result: " + startResult);
        DeviceAdminReceiver.startDecoyInBackground(this);

        Toast.makeText(this, "Decoy profile created", Toast.LENGTH_SHORT).show();
        populateSettings();
    }

    private void deleteDecoyProfile() {
        UserManager um = getSystemService(UserManager.class);
        long decoySerial = shadowPrefs.getLong("decoy_serial", -1);
        if (decoySerial == -1) {
            Toast.makeText(this, "No decoy profile to delete", Toast.LENGTH_SHORT).show();
            return;
        }

        UserHandle decoyUser = um.getUserForSerialNumber(decoySerial);
        if (decoyUser == null) {
            shadowPrefs.edit().remove("decoy_serial").apply();
            Toast.makeText(this, "Decoy profile was already deleted", Toast.LENGTH_SHORT).show();
            populateSettings();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Delete Decoy Profile")
            .setMessage("Are you sure you want to delete the decoy profile? This cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                boolean removed = dpm.removeUser(adminComponentName, decoyUser);
                if (removed) {
                    shadowPrefs.edit().remove("decoy_serial").apply();
                    Toast.makeText(this, "Decoy profile deleted", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Failed to delete decoy profile", Toast.LENGTH_SHORT).show();
                }
                populateSettings();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void returnToRealProfile() {
        boolean isProfileOwner = dpm.isProfileOwnerApp(getPackageName());
        if (!isProfileOwner) {
            showReturnToOwnerHelp();
            return;
        }

        UserManager um = getSystemService(UserManager.class);
        UserHandle ownerUser = um.getUserForSerialNumber(0);

        if (ownerUser == null) {
            List<UserHandle> profiles = um.getUserProfiles();
            if (!profiles.isEmpty()) {
                long minSerial = Long.MAX_VALUE;
                for (UserHandle user : profiles) {
                    long serial = um.getSerialNumberForUser(user);
                    if (serial < minSerial) {
                        minSerial = serial;
                        ownerUser = user;
                    }
                }
            }
        }

        if (ownerUser == null) {
            Toast.makeText(this, "Could not find owner user", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent();
        serviceIntent.setClass(this, DeviceOwnerService.class);

        final UserHandle targetUser = ownerUser;
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                IDeviceOwnerService deviceOwnerService = IDeviceOwnerService.Stub.asInterface(service);
                try {
                    deviceOwnerService.switchToOwner();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call switchToOwner", e);
                }
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {}
        };

        boolean bound = dpm.bindDeviceAdminServiceAsUser(
            adminComponentName, serviceIntent, connection,
            BIND_AUTO_CREATE, targetUser
        );

        if (!bound) {
            Toast.makeText(this, "Failed to bind to owner service", Toast.LENGTH_SHORT).show();
        }
    }

    private void showReturnToOwnerHelp() {
        new AlertDialog.Builder(this)
            .setTitle("Cannot Return to Owner")
            .setMessage("This app is not set as profile owner. Use ADB command to return:\n\n" +
                "adb shell am switch-user 0")
            .setPositiveButton("OK", null)
            .show();
    }
}
