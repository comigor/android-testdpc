package dev.borges.shadow.util;

import android.content.ContentResolver;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
import java.io.InputStream;

public class DownloadHelper extends AbstractFetchListener {
    private static final String TAG = "DownloadHelper";

    final Context context;
    final FetchConfiguration fetchConfiguration;
    final Fetch fetch;
    final ContentResolver contentResolver;

    public DownloadHelper(Context context) {
        this.context = context;
        this.contentResolver = context.getContentResolver();
        this.fetchConfiguration = new FetchConfiguration.Builder(context)
                .enableRetryOnNetworkGain(true)
                .setDownloadConcurrentLimit(1)
                .build();
        this.fetch = Fetch.Impl.getInstance(fetchConfiguration);

        fetch.addListener(this);
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

        Toast.makeText(context, "If app is still open, close it and try to update again.", Toast.LENGTH_SHORT).show();
    }
}
