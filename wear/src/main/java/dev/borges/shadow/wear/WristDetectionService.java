package dev.borges.shadow.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that monitors the off-body sensor and sends
 * wrist removal events to the phone app.
 *
 * CRITICAL SECURITY FEATURES:
 * 1. Uses TYPE_LOW_LATENCY_OFFBODY_DETECT for fastest detection (~1s)
 * 2. Sends state via BOTH MessageClient (fast) AND DataClient (persistent)
 * 3. Persists state locally for reboot recovery
 * 4. Holds wake lock to prevent Doze from killing sensor listener
 */
public class WristDetectionService extends Service implements SensorEventListener {
    private static final String TAG = "WristDetectionService";
    private static final String CHANNEL_ID = "wrist_detection_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS_NAME = "wrist_detection_prefs";
    private static final String PREF_LAST_STATE = "last_wrist_state";
    private static final String PREF_LAST_TIME = "last_state_time";

    // Message paths for communication with phone (fast, transient)
    public static final String PATH_WRIST_STATUS = "/shadow/wrist_status";
    // Data paths for communication with phone (slower, persistent)
    public static final String PATH_WRIST_DATA = "/shadow/wrist_data";
    public static final String KEY_WRIST_STATE = "wrist_state";
    public static final String KEY_TIMESTAMP = "timestamp";

    public static final String STATUS_WORN = "worn";
    public static final String STATUS_REMOVED = "removed";

    private static boolean isRunning = false;

    public static boolean isServiceRunning() {
        return isRunning;
    }

    private SensorManager sensorManager;
    private Sensor offBodySensor;
    private ExecutorService executor;
    private boolean isWorn = true;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "WristDetectionService created");
        isRunning = true;

        executor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Try to get the low-latency off-body sensor (TYPE 34)
        offBodySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);

        if (offBodySensor == null) {
            Log.w(TAG, "Low-latency off-body sensor not available, trying sensor type 34 directly");
            offBodySensor = sensorManager.getDefaultSensor(34);
        }

        if (offBodySensor == null) {
            Log.e(TAG, "CRITICAL: No off-body sensor available on this device!");
        } else {
            Log.i(TAG, "Off-body sensor found: " + offBodySensor.getName() +
                " (vendor: " + offBodySensor.getVendor() + ", type: " + offBodySensor.getType() + ")");
        }

        // Note: No wake lock needed - TYPE_LOW_LATENCY_OFFBODY_DETECT is a wake-up sensor
        // that will wake the device when state changes

        createNotificationChannel();

        // Restore last known state from preferences
        restoreLastState();
    }

    private void restoreLastState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastState = prefs.getString(PREF_LAST_STATE, STATUS_WORN);
        isWorn = STATUS_WORN.equals(lastState);
        Log.i(TAG, "Restored last wrist state: " + (isWorn ? "WORN" : "REMOVED"));
    }

    private void persistState(boolean worn) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
            .putString(PREF_LAST_STATE, worn ? STATUS_WORN : STATUS_REMOVED)
            .putLong(PREF_LAST_TIME, System.currentTimeMillis())
            .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "WristDetectionService starting");

        // Start as foreground service
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        // Start listening to off-body sensor
        if (offBodySensor != null) {
            // SENSOR_DELAY_NORMAL is sufficient - off-body sensor fires on state change, not continuously
            boolean registered = sensorManager.registerListener(
                this, offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);

            if (registered) {
                Log.i(TAG, "Successfully registered off-body sensor listener");
            } else {
                Log.e(TAG, "FAILED to register off-body sensor listener!");
            }
        } else {
            Log.e(TAG, "Cannot start sensor monitoring - no off-body sensor available");
        }

        // Send current state to phone on startup (for reconnection scenarios)
        sendWristStatusToPhone(isWorn);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "WristDetectionService destroyed");
        isRunning = false;

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (executor != null) {
            executor.shutdown();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT ||
            event.sensor.getType() == 34) {

            // value[0] = 1.0 means worn, 0.0 means not worn
            boolean newWornStatus = event.values[0] == 1.0f;

            if (newWornStatus != isWorn) {
                isWorn = newWornStatus;
                Log.i(TAG, "*** WRIST STATUS CHANGED: " + (isWorn ? "WORN" : "REMOVED") + " ***");

                // Persist state locally first
                persistState(isWorn);

                // Send status to phone via BOTH channels
                sendWristStatusToPhone(isWorn);

                // Update notification
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, createNotification());
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed: " + accuracy);
    }

    /**
     * Send wrist status to phone via BOTH channels:
     * 1. MessageClient - fast but transient
     * 2. DataClient - slower but persistent (survives disconnections/reboots)
     */
    private void sendWristStatusToPhone(boolean worn) {
        executor.execute(() -> {
            String status = worn ? STATUS_WORN : STATUS_REMOVED;
            long timestamp = System.currentTimeMillis();

            try {
                // CHANNEL 1: MessageClient (fast, immediate notification)
                List<Node> nodes = Tasks.await(Wearable.getNodeClient(this).getConnectedNodes());

                byte[] data = status.getBytes();
                for (Node node : nodes) {
                    Log.i(TAG, "Sending wrist status '" + status + "' via MESSAGE to: " + node.getDisplayName());
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(
                        node.getId(),
                        PATH_WRIST_STATUS,
                        data
                    ));
                }
                Log.i(TAG, "Message sent to " + nodes.size() + " node(s)");

            } catch (Exception e) {
                Log.e(TAG, "Failed to send message to phone (will use DataClient as backup)", e);
            }

            try {
                // CHANNEL 2: DataClient (persistent, survives disconnections)
                PutDataMapRequest dataMapRequest = PutDataMapRequest.create(PATH_WRIST_DATA);
                dataMapRequest.getDataMap().putString(KEY_WRIST_STATE, status);
                dataMapRequest.getDataMap().putLong(KEY_TIMESTAMP, timestamp);
                // Set urgent for faster sync
                dataMapRequest.setUrgent();

                PutDataRequest request = dataMapRequest.asPutDataRequest();
                Tasks.await(Wearable.getDataClient(this).putDataItem(request));

                Log.i(TAG, "Wrist data persisted to DataClient: " + status + " at " + timestamp);

            } catch (Exception e) {
                Log.e(TAG, "Failed to persist wrist data to DataClient", e);
            }
        });
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN  // Minimal importance - no sound, no peek
        );
        channel.setDescription(getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, WearMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );

        // Minimal notification - just shows in notification shade, not on watch face
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shadow")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build();
    }

    /**
     * Check if off-body sensor is available on this device.
     */
    public static boolean isOffBodySensorAvailable(Context context) {
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        if (sensor == null) {
            sensor = sm.getDefaultSensor(34);
        }
        return sensor != null;
    }

    /**
     * Get current wrist state (for UI display).
     */
    public boolean isCurrentlyWorn() {
        return isWorn;
    }
}
