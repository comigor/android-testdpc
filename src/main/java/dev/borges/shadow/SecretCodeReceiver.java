package dev.borges.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives secret dialer codes to launch the app when hidden.
 * Triggered by dialing *#*#742369#*#* (SHADOW on T9 keyboard).
 */
public class SecretCodeReceiver extends BroadcastReceiver {
    private static final String TAG = "SecretCodeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.SECRET_CODE".equals(intent.getAction())) {
            Log.i(TAG, "Secret code received - launching app");

            // Launch password activity (which will then go to settings if authenticated)
            Intent launchIntent = new Intent(context, PasswordActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launchIntent);
        }
    }
}
