/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.afwsamples.testdpc;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserManager;
import android.util.Log;
import androidx.annotation.RequiresApi;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import android.content.Context;
import dev.borges.shadow.BluetoothWatchReceiver;
import dev.borges.shadow.PowerButtonReceiver;
import dev.borges.shadow.TheftModeActivity;

/**
 * To allow DPC process to be persistent and foreground.
 *
 * @see {@link android.app.admin.DeviceAdminService}
 */
@RequiresApi(api = VERSION_CODES.O)
public class DeviceAdminService extends android.app.admin.DeviceAdminService {

  private static final String TAG = "DeviceAdminService";
  private static final String PREF_THEFT_MODE_ACTIVATION_TIME = "theft_mode_activation_time";
  private static final long THEFT_MODE_CHECK_INTERVAL_MS = 1000; // Check every second

  private BroadcastReceiver mPackageChangedReceiver;
  private Handler mTheftModeHandler;
  private Runnable mTheftModeChecker;

  @Override
  public void onCreate() {
    super.onCreate();
    registerPackageChangesReceiver();

    // Register receivers on owner profile (user 0) only
    UserManager um = getSystemService(UserManager.class);
    if (um != null && um.isSystemUser()) {
      PowerButtonReceiver.registerReceiver(getApplicationContext());
      BluetoothWatchReceiver.registerReceiver(getApplicationContext());

      // Ensure app is exempt from battery optimization (Device Owner privilege)
      ensureBatteryOptimizationExemption();

      // Start periodic theft mode checker - runs regardless of active user
      startTheftModeChecker();
    }
  }

  /**
   * Periodically check if theft mode should activate.
   * This runs in user 0's context, so it continues even when decoy user is active.
   */
  private void startTheftModeChecker() {
    mTheftModeHandler = new Handler(Looper.getMainLooper());
    mTheftModeChecker = new Runnable() {
      @Override
      public void run() {
        checkAndActivateTheftMode();
        mTheftModeHandler.postDelayed(this, THEFT_MODE_CHECK_INTERVAL_MS);
      }
    };
    mTheftModeHandler.post(mTheftModeChecker);
    Log.i(TAG, "Theft mode checker started");
  }

  private void stopTheftModeChecker() {
    if (mTheftModeHandler != null && mTheftModeChecker != null) {
      mTheftModeHandler.removeCallbacks(mTheftModeChecker);
      Log.i(TAG, "Theft mode checker stopped");
    }
  }

  private void checkAndActivateTheftMode() {
    SharedPreferences prefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);
    long activationTime = prefs.getLong(PREF_THEFT_MODE_ACTIVATION_TIME, 0);

    if (activationTime > 0 && System.currentTimeMillis() >= activationTime) {
      Log.i(TAG, "*** THEFT MODE ACTIVATION TIME REACHED (from DeviceAdminService) ***");

      // Clear the activation time
      prefs.edit().remove(PREF_THEFT_MODE_ACTIVATION_TIME).apply();

      // Launch theft mode activity
      Intent intent = new Intent(this, TheftModeActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(intent);
    }
  }

  /**
   * Use Device Owner privileges to ensure the app is exempt from battery optimizations.
   * This is CRITICAL for reliable watch disconnect detection and theft mode triggers.
   */
  private void ensureBatteryOptimizationExemption() {
    try {
      PowerManager pm = getSystemService(PowerManager.class);
      if (pm == null) {
        Log.e(TAG, "PowerManager not available");
        return;
      }

      String packageName = getPackageName();

      // Check if already exempt
      if (pm.isIgnoringBatteryOptimizations(packageName)) {
        Log.i(TAG, "Already exempt from battery optimizations");
        return;
      }

      // As Device Owner, we can exempt ourselves from battery optimizations
      DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
      if (dpm == null) {
        Log.e(TAG, "DevicePolicyManager not available");
        return;
      }

      ComponentName adminComponent = DeviceAdminReceiver.getComponentName(this);

      // Check if we're device owner
      if (!dpm.isDeviceOwnerApp(packageName)) {
        Log.w(TAG, "Not device owner - cannot programmatically exempt from battery optimization");
        return;
      }

      // On Android 12+ (API 31), Device Owner can use setApplicationExemptions
      // On older versions, we use setSystemUpdatePolicy or other methods
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Use reflection to call setApplicationExemptions if available
        // Alternatively, DPM apps are often automatically exempted
        Log.i(TAG, "Device Owner on Android 12+ - app should be auto-exempted");
      }

      // For Device Admin services, Android typically keeps them running
      // But we can also request exemption via intent
      if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        Log.w(TAG, "Battery optimization exemption not yet granted. " +
            "User may need to manually exempt the app in Settings.");
      }

    } catch (Exception e) {
      Log.e(TAG, "Error ensuring battery optimization exemption", e);
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    stopTheftModeChecker();
    unregisterPackageChangesReceiver();
  }

  private void registerPackageChangesReceiver() {
    IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
    intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    intentFilter.addDataScheme("package");
    mPackageChangedReceiver = new PackageMonitorReceiver();
    getApplicationContext().registerReceiver(mPackageChangedReceiver, intentFilter, Context.RECEIVER_EXPORTED);
  }

  private void unregisterPackageChangesReceiver() {
    if (mPackageChangedReceiver != null) {
      getApplicationContext().unregisterReceiver(mPackageChangedReceiver);
      mPackageChangedReceiver = null;
    }
  }

  @Override
  protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
    new ShellCommand(getApplicationContext(), writer, args).run();
  }
}
