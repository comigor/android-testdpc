package dev.borges.shadow.util;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.afwsamples.testdpc.common.PackageInstallationUtils;

import java.io.FileInputStream;
import java.io.InputStream;

abstract public class UpdateAppHelper {
    private static final String TAG = "UpdateAppHelper";

    public static void downloadAndInstall(Context context, String apkUrl) {
        DownloadManager mDownloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        long id = mDownloadManager.enqueue(request);

        try {
            ParcelFileDescriptor pfd = mDownloadManager.openDownloadedFile(id);
            InputStream in = new FileInputStream(pfd.getFileDescriptor());
            PackageInstallationUtils.installPackage(context, in, context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Error installing package", e);
            e.printStackTrace();
        }
    }
}
