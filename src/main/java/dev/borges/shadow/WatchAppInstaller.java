package dev.borges.shadow;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles installing/updating the watch companion app from the phone.
 */
public class WatchAppInstaller {
    private static final String TAG = "WatchAppInstaller";

    // Capability that the watch app advertises when installed
    public static final String WATCH_APP_CAPABILITY = "shadow_watch_app";

    // Data path for APK transfer
    public static final String PATH_WATCH_APK = "/shadow/watch_apk";
    public static final String KEY_APK_DATA = "apk_data";
    public static final String KEY_APK_VERSION = "apk_version";
    public static final String KEY_TIMESTAMP = "timestamp";

    private final Context context;
    private final ExecutorService executor;

    public interface InstallCallback {
        void onSuccess(String message);
        void onError(String error);
        void onProgress(String status);
    }

    public WatchAppInstaller(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Check if any watches are connected.
     */
    public void checkConnectedWatches(InstallCallback callback) {
        executor.execute(() -> {
            try {
                Task<java.util.List<Node>> nodeTask = Wearable.getNodeClient(context).getConnectedNodes();
                java.util.List<Node> nodes = Tasks.await(nodeTask);

                if (nodes.isEmpty()) {
                    callback.onError("No watches connected. Make sure your watch is paired and nearby.");
                } else {
                    StringBuilder sb = new StringBuilder("Connected watches:\n");
                    for (Node node : nodes) {
                        sb.append("- ").append(node.getDisplayName()).append("\n");
                    }
                    callback.onSuccess(sb.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking connected watches", e);
                callback.onError("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Check if the watch app is already installed on any connected watch.
     */
    public void checkWatchAppInstalled(InstallCallback callback) {
        executor.execute(() -> {
            try {
                Log.i(TAG, "checkWatchAppInstalled: Starting capability check for: " + WATCH_APP_CAPABILITY);
                callback.onProgress("Checking watch app status...");

                // First check connected nodes
                Log.i(TAG, "checkWatchAppInstalled: Getting connected nodes first...");
                java.util.List<Node> connectedNodes = Tasks.await(
                    Wearable.getNodeClient(context).getConnectedNodes()
                );
                Log.i(TAG, "checkWatchAppInstalled: Found " + connectedNodes.size() + " connected node(s)");
                for (Node node : connectedNodes) {
                    Log.i(TAG, "  Connected: " + node.getDisplayName() + " id=" + node.getId() + " nearby=" + node.isNearby());
                }

                // Now check capability
                Log.i(TAG, "checkWatchAppInstalled: Checking capability: " + WATCH_APP_CAPABILITY);
                Task<CapabilityInfo> capabilityTask = Wearable.getCapabilityClient(context)
                    .getCapability(WATCH_APP_CAPABILITY, CapabilityClient.FILTER_REACHABLE);

                CapabilityInfo info = Tasks.await(capabilityTask);
                Set<Node> nodes = info.getNodes();
                Log.i(TAG, "checkWatchAppInstalled: Capability nodes count: " + nodes.size());

                if (nodes.isEmpty()) {
                    Log.w(TAG, "checkWatchAppInstalled: No nodes with capability found!");
                    if (connectedNodes.isEmpty()) {
                        callback.onError("No watches connected. Make sure watch is nearby and connected.");
                    } else {
                        callback.onError("Watch connected but app not advertising capability.\nTry restarting the watch app.");
                    }
                } else {
                    StringBuilder sb = new StringBuilder("Watch app installed on:\n");
                    for (Node node : nodes) {
                        Log.i(TAG, "  Capability node: " + node.getDisplayName());
                        sb.append("- ").append(node.getDisplayName()).append("\n");
                    }
                    callback.onSuccess(sb.toString());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking watch app", e);
                callback.onError("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Send the watch APK to all connected watches.
     * The APK should be placed in assets/wear-release.apk
     */
    public void sendWatchApk(InstallCallback callback) {
        executor.execute(() -> {
            try {
                Log.i(TAG, "Starting APK send process...");
                callback.onProgress("Reading watch APK...");

                // Read APK from assets
                byte[] apkData = readApkFromAssets();
                if (apkData == null) {
                    Log.e(TAG, "APK not found in assets");
                    callback.onError("Watch APK not found in app assets. Please update the phone app first.");
                    return;
                }

                Log.i(TAG, "APK loaded: " + apkData.length + " bytes");
                callback.onProgress("Connecting to watch...");

                // Check for connected nodes
                Log.i(TAG, "Getting connected nodes...");
                java.util.List<Node> nodes = Tasks.await(
                    Wearable.getNodeClient(context).getConnectedNodes()
                );

                Log.i(TAG, "Connected nodes: " + nodes.size());
                for (Node node : nodes) {
                    Log.i(TAG, "  Node: " + node.getDisplayName() + " (id=" + node.getId() + ", nearby=" + node.isNearby() + ")");
                }

                if (nodes.isEmpty()) {
                    Log.w(TAG, "No watches connected!");
                    callback.onError("No watches connected. Make sure your watch is paired and nearby.");
                    return;
                }

                callback.onProgress("Sending APK to watch (" + (apkData.length / 1024) + " KB)...");

                // Create data item with APK
                Log.i(TAG, "Creating DataItem with APK asset...");
                PutDataMapRequest dataMapRequest = PutDataMapRequest.create(PATH_WATCH_APK);
                dataMapRequest.getDataMap().putAsset(KEY_APK_DATA, Asset.createFromBytes(apkData));
                dataMapRequest.getDataMap().putString(KEY_APK_VERSION, getAppVersion());
                dataMapRequest.getDataMap().putLong(KEY_TIMESTAMP, System.currentTimeMillis());
                dataMapRequest.setUrgent();

                PutDataRequest request = dataMapRequest.asPutDataRequest();
                Log.i(TAG, "Calling putDataItem...");

                Task<com.google.android.gms.wearable.DataItem> putTask =
                    Wearable.getDataClient(context).putDataItem(request);

                com.google.android.gms.wearable.DataItem result = Tasks.await(putTask);
                Log.i(TAG, "putDataItem SUCCESS! URI: " + result.getUri());

                callback.onSuccess("APK sent to watch!\n\nA notification should appear on your watch. " +
                    "Tap it to install the Shadow Watch app.\n\n" +
                    "Note: You may need to enable 'Install unknown apps' on your watch.");

            } catch (Exception e) {
                Log.e(TAG, "Error sending watch APK", e);
                callback.onError("Failed to send APK: " + e.getMessage());
            }
        });
    }

    /**
     * Send a command to start monitoring on the watch.
     */
    public void sendStartMonitoringCommand(InstallCallback callback) {
        executor.execute(() -> {
            try {
                java.util.List<Node> nodes = Tasks.await(
                    Wearable.getNodeClient(context).getConnectedNodes()
                );

                if (nodes.isEmpty()) {
                    callback.onError("No watches connected.");
                    return;
                }

                for (Node node : nodes) {
                    Tasks.await(Wearable.getMessageClient(context).sendMessage(
                        node.getId(),
                        "/shadow/start_monitoring",
                        new byte[0]
                    ));
                }

                callback.onSuccess("Start monitoring command sent to watch.");
            } catch (Exception e) {
                Log.e(TAG, "Error sending start command", e);
                callback.onError("Failed to send command: " + e.getMessage());
            }
        });
    }

    private byte[] readApkFromAssets() {
        // Try release APK first, then debug
        String[] apkNames = {"wear-release.apk", "wear-debug.apk"};

        for (String apkName : apkNames) {
            try {
                InputStream is = context.getAssets().open(apkName);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                is.close();
                Log.i(TAG, "Found watch APK: " + apkName + " (" + baos.size() + " bytes)");
                return baos.toByteArray();
            } catch (IOException e) {
                Log.d(TAG, apkName + " not found in assets, trying next...");
            }
        }

        Log.e(TAG, "No watch APK found in assets (tried: wear-release.apk, wear-debug.apk)");
        return null;
    }

    private String getAppVersion() {
        try {
            return context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
