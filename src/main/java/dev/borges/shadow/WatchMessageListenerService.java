package dev.borges.shadow;

import android.content.SharedPreferences;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.Set;

import dev.borges.shadow.util.SettingsHelper;

/**
 * Listens for messages AND data changes from the Wear OS companion app.
 *
 * CRITICAL SECURITY FEATURES:
 * 1. Uses DataClient (persistent) as source of truth for wrist state
 * 2. Detects watch disconnection via onCapabilityChanged
 * 3. If watch disconnects while last known state was "on-wrist", triggers theft mode
 *
 * This handles the "Message Gap" problem where the watch might disconnect
 * before sending an "off-wrist" message.
 */
public class WatchMessageListenerService extends WearableListenerService {
    private static final String TAG = "WatchMessageListener";

    // Must match paths in WristDetectionService on watch
    public static final String PATH_WRIST_STATUS = "/shadow/wrist_status";
    public static final String PATH_WRIST_DATA = "/shadow/wrist_data";
    public static final String STATUS_WORN = "worn";
    public static final String STATUS_REMOVED = "removed";

    // Capability name - must match wear.xml on watch
    public static final String CAPABILITY_WATCH_APP = "shadow_watch_app";

    // DataMap keys
    public static final String KEY_WRIST_STATE = "wrist_state";
    public static final String KEY_TIMESTAMP = "timestamp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WatchMessageListenerService created");
    }

    /**
     * CRITICAL: Called when watch capability changes (watch connects/disconnects).
     * This is our safety net for the "Message Gap" problem.
     */
    @Override
    public void onCapabilityChanged(@NonNull CapabilityInfo capabilityInfo) {
        Log.i(TAG, "Capability changed: " + capabilityInfo.getName());

        // Only process on system user
        UserManager um = getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            return;
        }

        if (CAPABILITY_WATCH_APP.equals(capabilityInfo.getName())) {
            Set<Node> nodes = capabilityInfo.getNodes();

            if (nodes.isEmpty()) {
                // Watch disconnected!
                Log.w(TAG, "Watch disconnected! Checking last known wrist state...");
                handleWatchDisconnected();
            } else {
                Log.i(TAG, "Watch connected: " + nodes.size() + " node(s)");
                for (Node node : nodes) {
                    Log.i(TAG, "  - " + node.getDisplayName() + " (nearby: " + node.isNearby() + ")");
                }
            }
        }
    }

    /**
     * CRITICAL: Handle watch disconnection.
     * If the last known state was "on-wrist", we must assume the worst
     * (watch was forcibly removed/phone stolen) and trigger theft mode.
     */
    private void handleWatchDisconnected() {
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(this);

        // Check if wrist detection is enabled
        if (!"true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_DETECTION_ENABLED_KEY))) {
            Log.d(TAG, "Wrist detection disabled - ignoring disconnection");
            return;
        }

        // Get last known wrist state
        String lastState = SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_LAST_KNOWN_STATE_KEY);
        long lastUpdateTime = Long.parseLong(
            SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_LAST_UPDATE_TIME_KEY)
        );

        Log.i(TAG, "Last known wrist state: " + lastState + " (updated " +
            ((System.currentTimeMillis() - lastUpdateTime) / 1000) + "s ago)");

        // If watch was ON wrist when it disconnected, this is suspicious!
        if (STATUS_WORN.equals(lastState)) {
            Log.w(TAG, "CRITICAL: Watch was ON WRIST when connection lost!");
            Log.w(TAG, "Assuming forcible removal - triggering theft mode!");

            // Use the watch disconnect timeout for the countdown
            int timeoutSeconds = SettingsHelper.parseInt(
                SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WATCH_DISCONNECT_TIMEOUT_KEY),
                30
            );

            // Get activation delay
            int activationDelaySeconds = SettingsHelper.parseInt(
                SettingsHelper.getSetting(settingsPrefs, SettingsHelper.ACTIVATION_DELAY_KEY),
                180
            );

            // Schedule theft mode after disconnect timeout + activation delay
            long activationTime = System.currentTimeMillis() +
                (timeoutSeconds * 1000L) + (activationDelaySeconds * 1000L);

            PowerButtonReceiver.schedulePendingTheftMode(this, activationTime);
            Log.i(TAG, "Theft mode scheduled: " + timeoutSeconds + "s disconnect timeout + " +
                activationDelaySeconds + "s activation delay");

        } else if (STATUS_REMOVED.equals(lastState)) {
            Log.i(TAG, "Watch was already OFF wrist - no action needed");
        } else {
            Log.w(TAG, "Unknown last wrist state: " + lastState);
        }
    }

    /**
     * Receives persistent data changes from watch via DataClient.
     * This is the PRIMARY source of truth for wrist state (survives reboots).
     */
    @Override
    public void onDataChanged(@NonNull DataEventBuffer dataEvents) {
        Log.i(TAG, "Data changed event received");

        // Only process on system user
        UserManager um = getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            return;
        }

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                String path = item.getUri().getPath();

                if (PATH_WRIST_DATA.equals(path)) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    String wristState = dataMap.getString(KEY_WRIST_STATE);
                    long timestamp = dataMap.getLong(KEY_TIMESTAMP);

                    Log.i(TAG, "Received wrist data: state=" + wristState + ", timestamp=" + timestamp);

                    // Persist state locally
                    persistWristState(wristState, timestamp);

                    // Handle the state change
                    handleWristStatusChange(wristState);
                }
            }
        }
    }

    /**
     * Also handle direct messages (for immediate notifications).
     * DataClient is the source of truth, but MessageClient provides faster response.
     */
    @Override
    public void onMessageReceived(@NonNull MessageEvent messageEvent) {
        String path = messageEvent.getPath();
        String data = new String(messageEvent.getData());

        Log.i(TAG, "Received message from watch: path=" + path + ", data=" + data);

        // Only process on system user
        UserManager um = getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            Log.w(TAG, "Ignoring watch message - not on system user");
            return;
        }

        if (PATH_WRIST_STATUS.equals(path)) {
            // Persist state (message is faster than data sync)
            persistWristState(data, System.currentTimeMillis());
            handleWristStatusChange(data);
        }
    }

    /**
     * Persist wrist state locally for reboot survival and disconnection handling.
     */
    private void persistWristState(String state, long timestamp) {
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(this);
        SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WRIST_LAST_KNOWN_STATE_KEY, state);
        SettingsHelper.setSetting(settingsPrefs, SettingsHelper.WRIST_LAST_UPDATE_TIME_KEY, String.valueOf(timestamp));
        Log.d(TAG, "Persisted wrist state: " + state + " at " + timestamp);
    }

    private void handleWristStatusChange(String status) {
        SharedPreferences settingsPrefs = SettingsHelper.getEncryptedSharedPreferences(this);

        // Check if wrist detection feature is enabled
        if (!"true".equals(SettingsHelper.getSetting(settingsPrefs, SettingsHelper.WRIST_DETECTION_ENABLED_KEY))) {
            Log.d(TAG, "Wrist detection disabled - ignoring status change");
            return;
        }

        if (STATUS_REMOVED.equals(status)) {
            // Check if theft mode countdown already started (not cancellable)
            if (PowerButtonReceiver.isTheftModePending(this)) {
                Log.i(TAG, "Watch removed but theft mode already pending - no additional action");
                return;
            }

            // Check if wrist removal timer already pending
            if (BluetoothWatchReceiver.isWristRemovalPending(this)) {
                Log.i(TAG, "Watch removed but wrist removal timer already pending");
                return;
            }

            Log.w(TAG, "Watch removed from wrist! Starting wrist removal timeout.");
            BluetoothWatchReceiver.startWristRemovalTimer(this);

        } else if (STATUS_WORN.equals(status)) {
            Log.i(TAG, "Watch put back on wrist");

            // Cancel wrist removal timeout if pending (this is cancellable)
            if (BluetoothWatchReceiver.isWristRemovalPending(this)) {
                Log.i(TAG, "Cancelling wrist removal timeout - watch back on wrist");
                BluetoothWatchReceiver.cancelWristRemovalTimer(this);
            }

            // NOTE: We do NOT cancel theft mode countdown here.
            // Once theft mode is triggered, putting watch back on won't help.
        }
    }
}
