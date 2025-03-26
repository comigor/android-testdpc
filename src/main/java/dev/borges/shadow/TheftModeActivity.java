package dev.borges.shadow;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.DevicePolicyManagerGateway;
import com.afwsamples.testdpc.DevicePolicyManagerGatewayImpl;
import com.afwsamples.testdpc.R;
import com.afwsamples.testdpc.common.Util;

import java.util.ArrayList;
import java.util.List;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import java.util.Arrays;

@TargetApi(VERSION_CODES.M)
public class TheftModeActivity extends Activity {
    private static final String TAG = "TheftModeActivity";

    private static final String KIOSK_PREFERENCE_FILE = "kiosk_preference_file";

    public static final String STOP_THEFT_MODE = "dev.borges.shadow.STOP_THEFT_MODE";

    private static final String[] KIOSK_USER_RESTRICTIONS = {
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_ADJUST_VOLUME,
    };

    private boolean backdoorTriggered = false;

    public static void startTheftMode(Context context) {
        try {
            DevicePolicyManagerGateway mDevicePolicyManagerGateway = new DevicePolicyManagerGatewayImpl(context);
            if (!mDevicePolicyManagerGateway.isDeviceOwnerApp()) {
                return;
            }

            final ComponentName mAdminComponentName = DeviceAdminReceiver.getComponentName(context);
            final DevicePolicyManager mDevicePolicyManager = context.getSystemService(DevicePolicyManager.class);
            final PackageManager mPackageManager = context.getPackageManager();

            final ComponentName customLauncher = new ComponentName(context.getPackageName(), TheftModeActivity.class.getName());

            // enable activity (or else we could select as launcher)
            mPackageManager.setComponentEnabledSetting(
                    customLauncher,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );

            // set it as default home activity
            mDevicePolicyManager.addPersistentPreferredActivity(
                    mAdminComponentName, Util.getHomeIntentFilter(), customLauncher);

            // start activity
            Intent launchIntent = Util.getHomeIntent();
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error while starting theft mode!");
            e.printStackTrace();
        }
    }

    public static void stopTheftMode(Context context) {
        try {
            Intent launchIntent = Util.getHomeIntent();
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra(TheftModeActivity.STOP_THEFT_MODE, true);
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error while stopping theft mode!");
            e.printStackTrace();
        }
    }

    public void onBackdoorClicked() {
        stopLockTask();
        setDefaultKioskPolicies(false);
        mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, false);

        backdoorTriggered = true;

        // clear default home activity
        mDevicePolicyManager.clearPackagePersistentPreferredActivities(mAdminComponentName, getPackageName());

        // disable activity (or else we could select as launcher)
        final ComponentName customLauncher = new ComponentName(getPackageName(), TheftModeActivity.class.getName());
        mPackageManager.setComponentEnabledSetting(
            customLauncher,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );
    }

    private ComponentName mAdminComponentName;
    private DevicePolicyManager mDevicePolicyManager;
    private PackageManager mPackageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        backdoorTriggered = false;

        mAdminComponentName = DeviceAdminReceiver.getComponentName(this);
        mDevicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mPackageManager = getPackageManager();

        if (mDevicePolicyManager.isLockTaskPermitted(getPackageName())) {
            startLockTask();
        }

        setDefaultKioskPolicies(true);
        setContentView(R.layout.activity_theft_mode);

        setupGestures();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Disable all keys
        return true;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Bring the app back to the foreground
        Intent intent = new Intent(this, TheftModeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
            // Reapply immersive mode
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Restart the activity if the user tries to leave it
        if (!isTaskRoot() && !backdoorTriggered) {
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
        } else if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            startLockTask();
        }

        mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, true);
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
            setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, active);
            setUserRestriction(UserManager.DISALLOW_FACTORY_RESET, active);
            setUserRestriction(UserManager.DISALLOW_ADD_USER, active);
            setUserRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, active);
            setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, active);
        } else {
            restorePreviousConfiguration();
        }

        // set lock task packages
        mDevicePolicyManager.setLockTaskPackages(
                mAdminComponentName, active ? new String[]{getPackageName()} : new String[]{});
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
            editor.apply();
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

    // ------------------------------
    private GestureDetector mGestureDetector;
    final private List<String> mGestureSequence = new ArrayList<>();
    private static final List<String> CORRECT_GESTURE_SEQUENCE = Arrays.asList(
            "up", "up", "down", "down", "left", "right", "left", "right"
    );

    private EditText mPasswordEditText;

    private void setupGestures() {
        mPasswordEditText = findViewById(R.id.passwordEditText);

        mGestureDetector = new GestureDetector(this, new GestureListener());

        View rootView = findViewById(android.R.id.content);
        rootView.setOnTouchListener((v, event) -> {
            v.performClick();
            mGestureDetector.onTouchEvent(event);
            return true;
        });

        mPasswordEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                checkPassword();
                return true;
            }
            return false;
        });
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            float diffX = e2.getX() - e1.getX();
            float diffY = e2.getY() - e1.getY();

            String gesture;
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (diffX > 0) {
                    gesture = "right";
                } else {
                    gesture = "left";
                }
            } else {
                if (diffY > 0) {
                    gesture = "down";
                } else {
                    gesture = "up";
                }
            }

            Log.d(TAG, "Flung " + gesture);
            mGestureSequence.add(gesture);
            checkGestureSequence();
            return true;
        }
    }

    private void checkGestureSequence() {
        if (mGestureSequence.size() > 20) {
            mGestureSequence.subList(0, mGestureSequence.size() - 20).clear();
        }

        int correctSequenceSize = CORRECT_GESTURE_SEQUENCE.size();
        if (mGestureSequence.size() >= correctSequenceSize) {
            List<String> lastGestures = mGestureSequence.subList(mGestureSequence.size() - correctSequenceSize, mGestureSequence.size());

            if (lastGestures.equals(CORRECT_GESTURE_SEQUENCE)) {
                mPasswordEditText.setVisibility(View.VISIBLE);
                mPasswordEditText.requestFocus();
                mGestureSequence.clear();
            }
        }
    }

    private void checkPassword() {
        String enteredPassword = mPasswordEditText.getText().toString();
        String savedPassword = PasswordActivity.getSavedPassword(this);
        if (enteredPassword.equals(savedPassword)) {
            onBackdoorClicked();
        }
    }
}
