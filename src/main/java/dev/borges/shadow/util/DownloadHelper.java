package dev.borges.shadow.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.afwsamples.testdpc.common.PackageInstallationUtils;
import com.tonyodev.fetch2.AbstractFetchListener;
import com.tonyodev.fetch2.Download;
import com.tonyodev.fetch2.Error;
import com.tonyodev.fetch2.Fetch;
import com.tonyodev.fetch2.FetchConfiguration;
import com.tonyodev.fetch2.NetworkType;
import com.tonyodev.fetch2.Priority;
import com.tonyodev.fetch2.Request;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class DownloadHelper extends AbstractFetchListener {
    private static final String TAG = "DownloadHelper";

    final AppCompatActivity context;
    final FetchConfiguration fetchConfiguration;
    final Fetch fetch;
    final ContentResolver contentResolver;
//    final DownloadManager mDownloadManager;

    public DownloadHelper(AppCompatActivity context) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.fetchConfiguration = new FetchConfiguration.Builder(context)
                .enableRetryOnNetworkGain(true)
                .setDownloadConcurrentLimit(1)
                .build();
        this.fetch = Fetch.Impl.getInstance(fetchConfiguration);
//        this.mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

        fetch.addListener(this);

//        context.registerReceiver(mDownloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
    }

    public void downloadAndInstallApk(Context context, String url) {
        File file = new File(context.getFilesDir(), "shadow.apk");
        if (file.exists()) {
            file.delete();
        }
        Log.d(TAG, "Will download file to: " + file.getAbsolutePath());

        final Request request = new Request(url, file.getAbsolutePath());
        request.setPriority(Priority.HIGH);
        request.setNetworkType(NetworkType.ALL);

        fetch.enqueue(request, updatedRequest -> {
            Log.d(TAG, "Download enqueued");
        }, error -> {
            Log.e(TAG, "Download failed", error.getThrowable());
        });

//        DownloadManager.Request request2 = new DownloadManager.Request(Uri.parse(url));
//        request2.setMimeType("application/vnd.android.package-archive");
//        mDownloadManager.enqueue(request2);
    }

    @Override
    public void onError(@NonNull Download download, @NonNull Error error, @Nullable Throwable throwable) {
        Log.e(TAG, "Download error", throwable);
    }

    @Override
    public void onProgress(@NonNull Download download, long etaInMilliSeconds, long downloadedBytesPerSecond) {
        Log.d(TAG, "Download progress: ETA " + etaInMilliSeconds / 1000 + "s, " + downloadedBytesPerSecond + "B/s");
    }

    @Override
    public void onCompleted(@NonNull Download download) {
        Log.i(TAG, "Download completed: " + download.getFileUri());

        try {
            InputStream in = contentResolver.openInputStream(download.getFileUri());
            PackageInstallationUtils.installPackageMutable(context, in, context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Installation error (mutable)", e);
        }

        try {
            InputStream in = contentResolver.openInputStream(download.getFileUri());
            PackageInstallationUtils.installPackage(context, in, context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Installation error (immutable)", e);
        }

        try {
            InputStream in = contentResolver.openInputStream(download.getFileUri());
            File tempFile = new File(context.getExternalCacheDir(), "shadow.apk");
            FileOutputStream outputStream = new FileOutputStream(tempFile);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = in.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            in.close();
            outputStream.close();

            Uri apkUri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", tempFile);

            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(installIntent);
        } catch (Exception e) {
            Log.e(TAG, "Installation error (manual)", e);
        }

        Toast.makeText(context, "If app is still open, close it and try to update again.", Toast.LENGTH_SHORT).show();
    }

//    final private BroadcastReceiver mDownloadReceiver =
//            new BroadcastReceiver() {
//                @Override
//                public void onReceive(Context context, Intent intent) {
//                    if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
//                        return;
//                    }
//
//                    final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0);
//                    Log.d(TAG, "Download complete with id: " + id);
//
//                    try {
//                        Uri apkUri = mDownloadManager.getUriForDownloadedFile(id);
//                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
//                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
//                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//
//                        context.startActivity(installIntent);
//                    } catch (Exception e) {
//                        Log.e(TAG, "Installation error (manual)", e);
//                    }
//                }
//            };
}
