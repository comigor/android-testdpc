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

package com.afwsamples.testdpc;

import android.annotation.TargetApi;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.SecurityLog.SecurityEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import com.afwsamples.testdpc.common.NotificationUtil;
import com.afwsamples.testdpc.common.Util;
import com.afwsamples.testdpc.provision.PostProvisioningTask;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Handles events related to the managed profile. */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {
  private static final String TAG = "DeviceAdminReceiver";

  public static final String ACTION_PASSWORD_REQUIREMENTS_CHANGED =
      "com.afwsamples.testdpc.policy.PASSWORD_REQUIREMENTS_CHANGED";
  public static final String ACTION_SWITCH_TO_OWNER = "dev.borges.shadow.SWITCH_TO_OWNER";

  private static final String LOGS_DIR = "logs";

  private static final String FAILED_PASSWORD_LOG_FILE = "failed_pw_attempts_timestamps.log";
  private static final String SECURITY_LOG_FILE = "security_log.log";

  private static final int CHANGE_PASSWORD_NOTIFICATION_ID = 101;
  private static final int PASSWORD_FAILED_NOTIFICATION_ID = 102;

  @Override
  public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (action == null) {
        return;
    }
    switch (action) {
      case ACTION_PASSWORD_REQUIREMENTS_CHANGED:
      case Intent.ACTION_BOOT_COMPLETED:
        updatePasswordConstraintNotification(context);
        startDecoyInBackground(context);
        break;
      case DevicePolicyManager.ACTION_PROFILE_OWNER_CHANGED:
        onProfileOwnerChanged(context);
        break;
      case DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED:
        onDeviceOwnerChanged(context);
        break;
      case ACTION_SWITCH_TO_OWNER:
          if (context.getSystemService(UserManager.class).isSystemUser()) {
              Log.i(TAG, "Received command to switch to owner. Executing.");
              DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
              ComponentName admin = getComponentName(context);
              dpm.clearUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH);
              dpm.switchUser(admin, null);
          }
          break;
      default:
        super.onReceive(context, intent);
        break;
    }
  }

  @TargetApi(VERSION_CODES.N)
  @Override
  public void onSecurityLogsAvailable(Context context, Intent intent) {
    Log.i(TAG, "onSecurityLogsAvailable() called");

    DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
    ComponentName admin = getComponentName(context);

    try {
        List<SecurityEvent> logs = dpm.retrieveSecurityLogs(admin);
        if (logs != null) {
            for (SecurityEvent event : logs) {
                if (event.getTag() == android.app.admin.SecurityLog.TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT) {
                    int authResult = (int) event.getData();
                    if (authResult == 0) {
                        executeDecoySwitch(context);
                        logSecurityEvent(context, event);
                    }
                }
            }
        }
    } catch (SecurityException e) {
        Log.e(TAG, "Error retrieving security logs", e);
    }
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onNetworkLogsAvailable(
      Context context, Intent intent, long batchToken, int networkLogsCount) {
    Log.i(TAG, "onNetworkLogsAvailable() called");
    CommonReceiverOperations.onNetworkLogsAvailable(
        context, getComponentName(context), batchToken, networkLogsCount);
  }

  @Override
  public void onProfileProvisioningComplete(Context context, Intent intent) {
    if (Util.SDK_INT >= VERSION_CODES.O) {
      return;
    }
    PostProvisioningTask task = new PostProvisioningTask(context);
    if (!task.performPostProvisioningOperations(intent)) {
      return;
    }

    final Intent launchIntent = task.getPostProvisioningLaunchIntent(intent);
    if (launchIntent != null) {
      context.startActivity(launchIntent);
    } else {
      Log.e(TAG, "DeviceAdminReceiver.onProvisioningComplete() invoked, but ownership not assigned");
      showToast(context, R.string.device_admin_receiver_failure);
    }
  }

  @TargetApi(VERSION_CODES.N)
  @Override
  public void onBugreportSharingDeclined(Context context, Intent intent) {
    Log.i(TAG, "Bugreport sharing declined");
    NotificationUtil.showNotification(
        context,
        R.string.bugreport_title,
        context.getString(R.string.bugreport_sharing_declined),
        NotificationUtil.BUGREPORT_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.N)
  @Override
  public void onBugreportShared(
      final Context context, Intent intent, final String bugreportFileHash) {
    Log.i(TAG, "Bugreport shared, hash: " + bugreportFileHash);
    final Uri bugreportUri = intent.getData();
    Log.i(TAG, "Bugreport URI: " + bugreportUri);

    final PendingResult result = goAsync();
    new AsyncTask<Void, Void, String>() {
      @Override
      protected String doInBackground(Void... params) {
        File outputBugreportFile;
        String message;
        InputStream in;
        OutputStream out;
        try {
          ParcelFileDescriptor mInputPfd =
              context.getContentResolver().openFileDescriptor(bugreportUri, "r");
          in = new FileInputStream(mInputPfd.getFileDescriptor());
          outputBugreportFile =
              new File(context.getExternalFilesDir(null), bugreportUri.getLastPathSegment());
          Log.i(TAG, "Writing bugreport to " + outputBugreportFile);
          out = new FileOutputStream(outputBugreportFile);
          byte[] buffer = new byte[1024];
          int read;
          long totalRead = 0;
          while ((read = in.read(buffer)) != -1) {
            totalRead += read;
            out.write(buffer, 0, read);
          }
          in.close();
          out.close();
          message =
              context.getString(
                  R.string.received_bugreport,
                  outputBugreportFile.getPath(),
                  bugreportFileHash,
                  totalRead);
          Log.i(TAG, message);
        } catch (IOException e) {
          Log.e(TAG, e.getMessage());
          message = context.getString(R.string.received_bugreport_failed_retrieval);
        }
        return message;
      }

      @Override
      protected void onPostExecute(String message) {
        NotificationUtil.showNotification(
            context, R.string.bugreport_title, message, NotificationUtil.BUGREPORT_NOTIFICATION_ID);
        result.finish();
      }
    }.execute();
  }

  @TargetApi(VERSION_CODES.N)
  @Override
  public void onBugreportFailed(Context context, Intent intent, int failureCode) {
    String failureReason;
    switch (failureCode) {
      case BUGREPORT_FAILURE_FILE_NO_LONGER_AVAILABLE:
        failureReason = context.getString(R.string.bugreport_failure_file_no_longer_available);
        break;
      case BUGREPORT_FAILURE_FAILED_COMPLETING:
        // fall through
      default:
        failureReason = context.getString(R.string.bugreport_failure_failed_completing);
    }
    Log.i(TAG, "Bugreport failed: " + failureReason);
    NotificationUtil.showNotification(
        context,
        R.string.bugreport_title,
        context.getString(R.string.bugreport_failure_message, failureReason),
        NotificationUtil.BUGREPORT_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onUserAdded(Context context, Intent intent, UserHandle newUser) {
    handleUserAction(
        context,
        newUser,
        R.string.on_user_added_title,
        R.string.on_user_added_message,
        NotificationUtil.USER_ADDED_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onUserRemoved(Context context, Intent intent, UserHandle removedUser) {
    handleUserAction(
        context,
        removedUser,
        R.string.on_user_removed_title,
        R.string.on_user_removed_message,
        NotificationUtil.USER_REMOVED_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.P)
  @Override
  public void onUserStarted(Context context, Intent intent, UserHandle startedUser) {
    handleUserAction(
        context,
        startedUser,
        R.string.on_user_started_title,
        R.string.on_user_started_message,
        NotificationUtil.USER_STARTED_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.P)
  @Override
  public void onUserStopped(Context context, Intent intent, UserHandle stoppedUser) {
    handleUserAction(
        context,
        stoppedUser,
        R.string.on_user_stopped_title,
        R.string.on_user_stopped_message,
        NotificationUtil.USER_STOPPED_NOTIFICATION_ID);
    // Restart decoy user if it was stopped
    startDecoyInBackground(context);
  }

  // Track when we switched TO owner to prevent immediate switch back to decoy
  private static volatile long lastSwitchToOwnerTime = 0;

  public static void markSwitchingToOwner() {
    lastSwitchToOwnerTime = System.currentTimeMillis();
  }

  @TargetApi(VERSION_CODES.P)
  @Override
  public void onUserSwitched(Context context, Intent intent, UserHandle switchedUser) {
    handleUserAction(
        context,
        switchedUser,
        R.string.on_user_switched_title,
        R.string.on_user_switched_message,
        NotificationUtil.USER_SWITCHED_NOTIFICATION_ID);
  }

  @TargetApi(VERSION_CODES.M)
  @Override
  @SuppressWarnings("SimpleDateFormat")
  public void onSystemUpdatePending(Context context, Intent intent, long receivedTime) {
    if (receivedTime != -1) {
      DateFormat sdf = new SimpleDateFormat("hh:mm:ss dd/MM/yyyy");
      String timeString = sdf.format(new Date(receivedTime));
      showToast(context, "System update received at: " + timeString);
    } else {
      // No system update is currently available on this device.
    }
  }

  @TargetApi(VERSION_CODES.M)
  @Override
  public String onChoosePrivateKeyAlias(
      Context context, Intent intent, int uid, Uri uri, String alias) {
    return CommonReceiverOperations.onChoosePrivateKeyAlias(context, uid);
  }

  public static ComponentName getComponentName(Context context) {
    if (Util.isDeviceOwner(context) || Util.isProfileOwner(context)) {
      return getReceiverComponentName(context);
    } else {
      return null;
    }
  }

  public static ComponentName getReceiverComponentName(Context context) {
    return new ComponentName(context.getApplicationContext(), DeviceAdminReceiver.class);
  }

  @Deprecated
  @Override
  public void onPasswordExpiring(Context context, Intent intent) {
    onPasswordExpiring(context, intent, Process.myUserHandle());
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onPasswordExpiring(Context context, Intent intent, UserHandle user) {
    if (!Process.myUserHandle().equals(user)) {
      return;
    }
    DevicePolicyManager devicePolicyManager =
        (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);

    final long timeNow = System.currentTimeMillis();
    final long timeAdminExpires =
        devicePolicyManager.getPasswordExpiration(getComponentName(context));
    final boolean expiredBySelf = (timeNow >= timeAdminExpires && timeAdminExpires != 0);

    NotificationUtil.showNotification(
        context,
        R.string.password_expired_title,
        context.getString(
            expiredBySelf
                ? R.string.password_expired_by_self
                : R.string.password_expired_by_others),
        NotificationUtil.PASSWORD_EXPIRATION_NOTIFICATION_ID);
  }

  @Deprecated
  @Override
  public void onPasswordFailed(Context context, Intent intent) {
    onPasswordFailed(context, intent, Process.myUserHandle());
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onPasswordFailed(Context context, Intent intent, UserHandle user) {
      if (user.equals(UserHandle.getUserHandleForUid(0))) {
          executeDecoySwitch(context);
      }
  }

  @Deprecated
  @Override
  public void onPasswordSucceeded(Context context, Intent intent) {
    onPasswordSucceeded(context, intent, Process.myUserHandle());
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onPasswordSucceeded(Context context, Intent intent, UserHandle user) {
    if (Process.myUserHandle().equals(user)) {
      logFile(context, FAILED_PASSWORD_LOG_FILE).delete();
    }
  }

  @Deprecated
  @Override
  public void onPasswordChanged(Context context, Intent intent) {
    onPasswordChanged(context, intent, Process.myUserHandle());
  }

  @TargetApi(VERSION_CODES.O)
  @Override
  public void onPasswordChanged(Context context, Intent intent, UserHandle user) {
    if (Process.myUserHandle().equals(user)) {
      updatePasswordConstraintNotification(context);
    }
  }

  @Override
  public void onEnabled(Context context, Intent intent) {
    UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    long serialNumber = userManager.getSerialNumberForUser(Binder.getCallingUserHandle());
    Log.i(TAG, "Device admin enabled in user with serial number: " + serialNumber);
  }

  private static File logFile(Context context, String fileName) {
    File parent = context.getDir(LOGS_DIR, Context.MODE_PRIVATE);
    return new File(parent, fileName);
  }

  private static ArrayList<Date> getFailedPasswordAttempts(Context context) {
    File logFile = logFile(context, FAILED_PASSWORD_LOG_FILE);
    ArrayList<Date> result = new ArrayList<Date>();

    if (!logFile.exists()) {
      return result;
    }

    FileInputStream fis = null;
    try {
      fis = new FileInputStream(logFile);
      BufferedReader br = new BufferedReader(new InputStreamReader(fis));

      String line = null;
      while ((line = br.readLine()) != null && line.length() > 0) {
        result.add(new Date(Long.parseLong(line)));
      }

      br.close();
    } catch (IOException e) {
      Log.e(TAG, "Unable to read failed password attempts", e);
    } finally {
      if (fis != null) {
        try {
          fis.close();
        } catch (IOException e) {
          Log.e(TAG, "Unable to close failed password attempts log file", e);
        }
      }
    }

    return result;
  }

  private static void saveFailedPasswordAttempts(Context context, ArrayList<Date> attempts)
      throws IOException {
    File logFile = logFile(context, FAILED_PASSWORD_LOG_FILE);

    if (!logFile.exists()) {
      logFile.createNewFile();
    }

    FileOutputStream fos = new FileOutputStream(logFile);
    BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));

    for (Date date : attempts) {
      bw.write(Long.toString(date.getTime()));
      bw.newLine();
    }

    bw.close();
  }

    private void logSecurityEvent(Context context, SecurityEvent event) {
        File logFile = logFile(context, SECURITY_LOG_FILE);
        try (FileOutputStream fos = new FileOutputStream(logFile, true);
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos))) {
            bw.write(event.toString());
            bw.newLine();
        } catch (IOException e) {
            Log.e(TAG, "Unable to write to security log file", e);
        }
    }

    public static void startDecoyInBackground(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            return; // Only device owner on user 0 can do this
        }

        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = getReceiverComponentName(context);
        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            return;
        }

        android.content.SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        long decoySerial = prefs.getLong("decoy_serial", -1);
        if (decoySerial == -1L) {
            return;
        }

        UserHandle decoyHandle = um.getUserForSerialNumber(decoySerial);
        if (decoyHandle == null) {
            Log.w(TAG, "Decoy user not found for serial: " + decoySerial);
            return;
        }

        try {
            int result = dpm.startUserInBackground(admin, decoyHandle);
            Log.i(TAG, "startUserInBackground result: " + result + " for user " + decoySerial);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start decoy in background", e);
        }
    }

    private void executeDecoySwitch(Context context) {
        // Don't switch to decoy if we just switched TO owner (within 10 seconds)
        if (System.currentTimeMillis() - lastSwitchToOwnerTime < 10000) {
            Log.i(TAG, "Skipping decoy switch - recently returned to owner");
            return;
        }

        android.content.SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        long decoySerial = prefs.getLong("decoy_serial", -1);

        if (decoySerial != -1L) {
            Log.i(TAG, "Launching lock task switch for decoy: " + decoySerial);
            dev.borges.shadow.LockTaskSwitchActivity.launch(context);
        } else {
            Log.w(TAG, "No decoy serial configured");
        }
    }

  @SuppressWarnings("UnspecifiedImmutableFlag") // TODO(b/210723613): proper fix
  private static void updatePasswordConstraintNotification(Context context) {
    final DevicePolicyManager dpm =
        (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);

    if (!dpm.isProfileOwnerApp(context.getPackageName())
        && !dpm.isDeviceOwnerApp(context.getPackageName())) {
      return;
    }

    final NotificationManager nm =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    final ArrayList<CharSequence> problems = new ArrayList<>();
    if (!dpm.isActivePasswordSufficient()) {
      problems.add(context.getText(R.string.password_not_compliant_title));
    }

    if (um.hasUserRestriction(UserManager.DISALLOW_UNIFIED_PASSWORD)
        && Util.isManagedProfileOwner(context)
        && isUsingUnifiedPassword(context)) {
      problems.add(context.getText(R.string.separate_challenge_required_title));
    }

    if (!problems.isEmpty()) {
      final NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
      style.setBigContentTitle(context.getText(R.string.set_new_password_notification_content));
      for (final CharSequence problem : problems) {
        style.addLine(problem);
      }
      final NotificationCompat.Builder warn = NotificationUtil.getNotificationBuilder(context);
      warn.setOngoing(true)
          .setSmallIcon(R.drawable.ic_launcher)
          .setStyle(style)
          .setContentIntent(
              PendingIntent.getActivity(
                  context, /*requestCode*/
                  -1,
                  new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD), /*flags*/
                  PendingIntent.FLAG_IMMUTABLE));
      nm.notify(CHANGE_PASSWORD_NOTIFICATION_ID, warn.getNotification());
    } else {
      nm.cancel(CHANGE_PASSWORD_NOTIFICATION_ID);
    }
  }

  @TargetApi(VERSION_CODES.P)
  private static Boolean isUsingUnifiedPassword(Context context) {
    if (Util.SDK_INT < VERSION_CODES.P) {
      return false;
    }
    final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
    return dpm.isUsingUnifiedPassword(getComponentName(context));
  }

  public static void sendPasswordRequirementsChanged(Context context) {
    final Intent changedIntent =
        new Intent(DeviceAdminReceiver.ACTION_PASSWORD_REQUIREMENTS_CHANGED);
    changedIntent.setComponent(getComponentName(context));
    context.sendBroadcast(changedIntent);
  }

  private void onProfileOwnerChanged(Context context) {
    Log.i(TAG, "onProfileOwnerChanged");
    NotificationUtil.showNotification(
        context,
        R.string.transfer_ownership_profile_owner_changed_title,
        context.getString(R.string.transfer_ownership_profile_owner_changed_title),
        NotificationUtil.PROFILE_OWNER_CHANGED_ID);
  }

  private void onDeviceOwnerChanged(Context context) {
    Log.i(TAG, "onDeviceOwnerChanged");
    NotificationUtil.showNotification(
        context,
        R.string.transfer_ownership_device_owner_changed_title,
        context.getString(R.string.transfer_ownership_device_owner_changed_title),
        NotificationUtil.DEVICE_OWNER_CHANGED_ID);
  }

  @TargetApi(VERSION_CODES.P)
  public void onTransferOwnershipComplete(Context context, PersistableBundle bundle) {
    Log.i(TAG, "onTransferOwnershipComplete");
    NotificationUtil.showNotification(
        context,
        R.string.transfer_ownership_complete_title,
        context.getString(R.string.transfer_ownership_complete_message, getComponentName(context)),
        NotificationUtil.TRANSFER_OWNERSHIP_COMPLETE_ID);
  }

  @TargetApi(VERSION_CODES.P)
  public void onTransferAffiliatedProfileOwnershipComplete(Context context, UserHandle user) {
    Log.i(TAG, "onTransferAffiliatedProfileOwnershipComplete");
    NotificationUtil.showNotification(
        context,
        R.string.transfer_ownership_affiliated_complete_title,
        context.getString(R.string.transfer_ownership_affiliated_complete_message, user),
        NotificationUtil.TRANSFER_AFFILIATED_PROFILE_OWNERSHIP_COMPLETE_ID);
  }

  private void handleUserAction(
      Context context,
      UserHandle userHandle,
      int titleResId,
      int messageResId,
      int notificationId) {
    UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    String message =
        context.getString(messageResId, userManager.getSerialNumberForUser(userHandle));
    Log.i(TAG, message);
    NotificationUtil.showNotification(context, titleResId, message, notificationId);
  }

  private void showToast(Context context, int resId) {
    showToast(context, context.getString(resId));
  }

  private void showToast(Context context, String message) {
    Log.v(TAG, "showToast():" + message);
    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
  }
}
