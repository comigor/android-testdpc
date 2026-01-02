package dev.borges.shadow.wear;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.android.gms.tasks.Tasks;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Receives APK updates from the phone and prompts user to install.
 */
public class ApkReceiverService extends WearableListenerService {
    private static final String TAG = "ApkReceiverService";

    private static final String PATH_WATCH_APK = "/shadow/watch_apk";
    private static final String KEY_APK_DATA = "apk_data";
    private static final String KEY_APK_VERSION = "apk_version";

    private static final String CHANNEL_ID = "apk_update_channel";
    private static final int NOTIFICATION_ID = 2;

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.i(TAG, "onDataChanged called");

        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                String path = event.getDataItem().getUri().getPath();
                Log.i(TAG, "Data changed at path: " + path);

                if (PATH_WATCH_APK.equals(path)) {
                    handleApkReceived(event);
                }
            }
        }
    }

    private void handleApkReceived(DataEvent event) {
        try {
            DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
            Asset apkAsset = dataMapItem.getDataMap().getAsset(KEY_APK_DATA);
            String version = dataMapItem.getDataMap().getString(KEY_APK_VERSION);

            if (apkAsset == null) {
                Log.e(TAG, "No APK asset in data item");
                return;
            }

            Log.i(TAG, "Received APK update, version: " + version);

            // Get the asset as input stream
            InputStream inputStream = Tasks.await(
                Wearable.getDataClient(this).getFdForAsset(apkAsset)
            ).getInputStream();

            if (inputStream == null) {
                Log.e(TAG, "Failed to get input stream for APK asset");
                return;
            }

            // Save APK to external files directory
            File apkFile = new File(getExternalFilesDir(null), "shadow-watch-update.apk");
            FileOutputStream fos = new FileOutputStream(apkFile);

            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }

            fos.close();
            inputStream.close();

            Log.i(TAG, "APK saved to: " + apkFile.getAbsolutePath() + " (" + apkFile.length() + " bytes)");

            // Show notification to install
            showInstallNotification(apkFile, version);

        } catch (Exception e) {
            Log.e(TAG, "Error handling APK", e);
        }
    }

    private void showInstallNotification(File apkFile, String version) {
        createNotificationChannel();

        // Create intent to install APK
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Use FileProvider for Android 7+
            apkUri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", apkFile);
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            apkUri = Uri.fromFile(apkFile);
        }

        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Shadow Watch Update")
            .setContentText("Tap to install version " + version)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, builder.build());
        }

        Log.i(TAG, "Install notification shown");
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "App Updates",
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifications for app updates from phone");

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }
}
