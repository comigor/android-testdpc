/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.afwsamples.testdpc.policy.locktask;

import static android.os.UserManager.DISALLOW_ADD_USER;
import static android.os.UserManager.DISALLOW_ADJUST_VOLUME;
import static android.os.UserManager.DISALLOW_FACTORY_RESET;
import static android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA;
import static android.os.UserManager.DISALLOW_SAFE_BOOT;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.PolicyManagementActivity;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.Util;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Shows the list of apps passed in the {@link #LOCKED_APP_PACKAGE_LIST} extra (or previously saved
 * in shared preferences if the extra is not found) in single app mode:
 *
 * <ul>
 *   <li>The status bar and keyguard are disabled
 *   <li>Several user restrictions are set to prevent the user from escaping this mode (e.g. safe
 *       boot mode and factory reset are disabled)
 *   <li>This activity is set as the Home intent receiver
 * </ul>
 *
 * If the user taps on one of the apps, it is launched in lock tack mode. Tapping on the back or
 * home buttons will bring the user back to the app list. The list also contains a row to exit
 * single app mode and finish this activity.
 */
@TargetApi(VERSION_CODES.M)
public class TheftModeActivity extends Activity {
  private static final String TAG = "TheftModeActivity";

  private static final String KIOSK_PREFERENCE_FILE = "kiosk_preference_file";

  public static final String STOP_THEFT_MODE =
      "com.afwsamples.testdpc.policy.locktask.STOP_THEFT_MODE";  

  private static final String[] KIOSK_USER_RESTRICTIONS = {
    DISALLOW_SAFE_BOOT,
    DISALLOW_FACTORY_RESET,
    DISALLOW_ADD_USER,
    DISALLOW_MOUNT_PHYSICAL_MEDIA,
    DISALLOW_ADJUST_VOLUME
  };

  private ComponentName mAdminComponentName;
  private ArrayList<String> mKioskPackages;
  private DevicePolicyManager mDevicePolicyManager;
  private PackageManager mPackageManager;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    mAdminComponentName = DeviceAdminReceiver.getComponentName(this);
    mDevicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
    mPackageManager = getPackageManager();

    setDefaultKioskPolicies(true);
    setContentView(R.layout.activity_theft_mode);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
      // Disable all keys
      return true;
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
      super.onWindowFocusChanged(hasFocus);
      if (!hasFocus) {
          // Reapply immersive mode
          getWindow().getDecorView().setSystemUiVisibility(
                  View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                          | View.SYSTEM_UI_FLAG_FULLSCREEN
                          | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
      }
  }

  @Override
  protected void onPause() {
      super.onPause();
      // Restart the activity if the user tries to leave it
      if (!isTaskRoot()) {
          startActivity(new Intent(this, TheftModeActivity.class));
      }
  }

  @Override
  protected void onStart() {
    super.onStart();

    // start lock task mode if it's not already active
    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    // ActivityManager.getLockTaskModeState api is not available in pre-M.
    if (Util.SDK_INT < VERSION_CODES.M) {
      if (!am.isInLockTaskMode()) {
        startLockTask();
      }
    } else {
      if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
        startLockTask();
      }
    }
    
    mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, true);
  }

  public void onBackdoorClicked() {
    stopLockTask();
    setDefaultKioskPolicies(false);
    mDevicePolicyManager.clearPackagePersistentPreferredActivities(
        mAdminComponentName, getPackageName());
    mPackageManager.setComponentEnabledSetting(
        new ComponentName(getPackageName(), getClass().getName()),
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.DONT_KILL_APP);
    mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, false);
    finish();
    // startActivity(new Intent(this, PolicyManagementActivity.class));
  }

  private void setUserRestriction(String restriction, boolean disallow) {
    if (disallow) {
      mDevicePolicyManager.addUserRestriction(mAdminComponentName, restriction);
    } else {
      mDevicePolicyManager.clearUserRestriction(mAdminComponentName, restriction);
    }
  }

  private void setDefaultKioskPolicies(boolean active) {
    // restore or save previous configuration
    if (active) {
      saveCurrentConfiguration();
      setUserRestriction(DISALLOW_SAFE_BOOT, active);
      setUserRestriction(DISALLOW_FACTORY_RESET, active);
      setUserRestriction(DISALLOW_ADD_USER, active);
      setUserRestriction(DISALLOW_MOUNT_PHYSICAL_MEDIA, active);
      setUserRestriction(DISALLOW_ADJUST_VOLUME, active);
    } else {
      restorePreviousConfiguration();
    }

    // set lock task packages
    mDevicePolicyManager.setLockTaskPackages(
        mAdminComponentName, active ? new String[] { getPackageName() } : new String[] {});
  }

  @TargetApi(VERSION_CODES.N)
  private void saveCurrentConfiguration() {
    if (Util.SDK_INT >= VERSION_CODES.N) {
      Bundle settingsBundle = mDevicePolicyManager.getUserRestrictions(mAdminComponentName);
      SharedPreferences.Editor editor =
          getSharedPreferences(KIOSK_PREFERENCE_FILE, MODE_PRIVATE).edit();

      for (String userRestriction : KIOSK_USER_RESTRICTIONS) {
        boolean currentSettingValue = settingsBundle.getBoolean(userRestriction);
        editor.putBoolean(userRestriction, currentSettingValue);
      }
      editor.commit();
    }
  }

  private void restorePreviousConfiguration() {
    if (Util.SDK_INT >= VERSION_CODES.N) {
      SharedPreferences sharedPreferences =
          getSharedPreferences(KIOSK_PREFERENCE_FILE, MODE_PRIVATE);

      for (String userRestriction : KIOSK_USER_RESTRICTIONS) {
        boolean prevSettingValue = sharedPreferences.getBoolean(userRestriction, false);
        setUserRestriction(userRestriction, prevSettingValue);
      }
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    if (intent.getBooleanExtra(STOP_THEFT_MODE, false)) {
      onBackdoorClicked();
    }
  }
}
