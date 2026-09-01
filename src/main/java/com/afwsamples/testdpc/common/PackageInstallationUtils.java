/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.afwsamples.testdpc.common;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dev.borges.shadow.SettingsActivity;

/** Utility class for various operations necessary to package installation. */
public class PackageInstallationUtils {
  public static final String TAG = "PackageInstallationUtils";

  public static final String ACTION_INSTALL_COMPLETE = "com.afwsamples.testdpc.INSTALL_COMPLETE";
  public static final String ACTION_DOWNLOAD_AND_INSTALL = "com.afwsamples.testdpc.DOWNLOAD_AND_INSTALL";
  private static final String ACTION_UNINSTALL_COMPLETE = "com.afwsamples.testdpc.UNINSTALL_COMPLETE";

  public static boolean installPackage(Context context, InputStream in, String packageName)
          throws IOException {
    Log.d(TAG, "Will install apk (immutable)");
    final PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
    final PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(packageName);

    // doesn't work on Pixel 9 Pro
    final int sessionId = packageInstaller.createSession(params);
    final PackageInstaller.Session session = packageInstaller.openSession(sessionId);
    final OutputStream out = session.openWrite("TestDPC", 0, -1);
    final byte[] buffer = new byte[65536];
    int c;
    while ((c = in.read(buffer)) != -1) {
      out.write(buffer, 0, c);
    }
    session.fsync(out);
    in.close();
    out.close();
    session.commit(createImmutableInstallIntentSender(context, sessionId));
    Log.d(TAG, "Done trying immutable");
    return true;
  }

  public static boolean installPackageMutable(Context context, InputStream in, String packageName)
          throws IOException {
    Log.d(TAG, "Will install apk (mutable)");
    final PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
    final PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
    params.setAppPackageName(packageName);

    // doesn't work on Pixel 3
    final int sessionId = packageInstaller.createSession(params);
    final PackageInstaller.Session session = packageInstaller.openSession(sessionId);
    final OutputStream out = session.openWrite("TestDPC", 0, -1);
    final byte[] buffer = new byte[65536];
    int c;
    while ((c = in.read(buffer)) != -1) {
      out.write(buffer, 0, c);
    }
    session.fsync(out);
    in.close();
    out.close();
    session.commit(createMutableInstallIntentSender(context, sessionId));
    Log.d(TAG, "Done trying mutable");
    return true;
  }

  public static void uninstallPackage(Context context, String packageName) {
    final PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
    packageInstaller.uninstall(packageName, createUninstallIntentSender(context, packageName));
  }

  @SuppressWarnings("UnspecifiedImmutableFlag") // TODO(b/210723613): proper fix
  private static IntentSender createMutableInstallIntentSender(Context context, int sessionId) {
      Intent intent = new Intent(context, SettingsActivity.class);
      intent.setAction(ACTION_INSTALL_COMPLETE);
      int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
      PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags);
      return pendingIntent.getIntentSender();
  }

  private static IntentSender createImmutableInstallIntentSender(Context context, int sessionId) {
    final PendingIntent pendingIntent =
            PendingIntent.getBroadcast(context, sessionId, new Intent(ACTION_INSTALL_COMPLETE),
                    PendingIntent.FLAG_IMMUTABLE);
    return pendingIntent.getIntentSender();
  }

  private static IntentSender createUninstallIntentSender(Context context, String packageName) {
    final Intent intent = new Intent(ACTION_UNINSTALL_COMPLETE);
    intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
    final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE);
    return pendingIntent.getIntentSender();
  }
}
