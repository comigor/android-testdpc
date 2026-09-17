package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

/** Ends the protected-apps access window on alarm expiry, screen off, or boot. */
public class ProtectedAppsReceiver extends BroadcastReceiver {
    private static final String TAG = "ProtectedAppsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "onReceive " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ProtectedApps.lock(context);
        } else if (ProtectedApps.ACTION_EXPIRE.equals(action)) {
            ProtectedApps.enforce(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            ProtectedApps.enforce(context);
        }
    }
}
