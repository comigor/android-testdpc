package dev.borges.shadow.util;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.afwsamples.testdpc.common.PackageInstallationUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;

abstract public class UpdateAppHelper {
    private static final String TAG = "UpdateAppHelper";

    public static void downloadAndInstall(Context context, String apkUrl) {
        new DownloadAndInstallTask(context, apkUrl).execute();
    }

    private static class DownloadAndInstallTask extends AsyncTask<Void, Void, File> {
        private final Context context;
        private final String apkUrl;

        public DownloadAndInstallTask(Context context, String apkUrl) {
            this.context = context.getApplicationContext();
            this.apkUrl = apkUrl;
        }

        @Override
        protected File doInBackground(Void... voids) {
            File tempFile = null;
            try {
                URL url = new URL(apkUrl);
                URLConnection connection = url.openConnection();
                connection.connect();

                InputStream input = connection.getInputStream();
                tempFile = new File(context.getCacheDir(), "temp_install.apk");
                OutputStream output = new FileOutputStream(tempFile);

                byte[] buffer = new byte[1024];
                int len;
                while ((len = input.read(buffer)) != -1) {
                    output.write(buffer, 0, len);
                }
                output.flush();
                output.close();
                input.close();
                return tempFile;

            } catch (IOException e) {
                Log.e(TAG, "Error downloading APK: " + e.getMessage());
                if (tempFile != null && tempFile.exists()) {
                    tempFile.delete();
                }
                return null;
            }
        }

        @Override
        protected void onPostExecute(File downloadedApk) {
            if (downloadedApk != null) {
                installPackage(context, downloadedApk);
            } else {
                Log.e(TAG, "APK download failed, cannot install.");
            }
        }
    }

    private static void installPackage(Context context, File apkFile) {
        try {
            InputStream inFile = new FileInputStream(apkFile);
            PackageInstallationUtils.installPackage(context, inFile, context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
