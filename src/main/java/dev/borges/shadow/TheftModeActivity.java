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
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.stream.Collectors;

import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.Restrictions;
import dev.borges.shadow.util.SettingsHelper;

@TargetApi(VERSION_CODES.N)
public class TheftModeActivity extends Activity {
    private static final String TAG = "TheftModeActivity";

    public static final String STOP_THEFT_MODE = "dev.borges.shadow.STOP_THEFT_MODE";

    private boolean backdoorTriggered = false;

    public static void startTheftMode(Context context) {
        try {
            DevicePolicyManagerGateway mDevicePolicyManagerGateway = new DevicePolicyManagerGatewayImpl(context);
            if (!mDevicePolicyManagerGateway.isDeviceOwnerApp()) {
                Log.e(TAG, "Error while starting theft mode! This app is not set as device owner.");
                Toast.makeText(context, "This app is not the device owner.", Toast.LENGTH_SHORT).show();
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
        mDevicePolicyManager.setLockTaskPackages(mAdminComponentName, new String[]{});

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

        // lock task
        mDevicePolicyManager.setLockTaskPackages(mAdminComponentName, new String[]{getPackageName()});
        if (mDevicePolicyManager.isLockTaskPermitted(getPackageName())) {
            startLockTask();
        }

        // set policies
        setDefaultKioskPolicies(true);

        // set beautiful UI
        setContentView(R.layout.activity_theft_mode);

        SharedPreferences sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);

        TextView title = findViewById(R.id.title);
        title.setText(sharedPreferences.getString(SettingsHelper.THEFT_MODE_TITLE, "Esse celular é roubado!"));

        TextView message = findViewById(R.id.contact_info);
        message.setText(sharedPreferences.getString(SettingsHelper.THEFT_MODE_INSTRUCTIONS, "Se você achou/comprou esse celular, por favor entre em contato com o dono.\n"));

        String sequence = sharedPreferences.getString(SettingsHelper.DEACTIVATION_SEQUENCE, "null");
        if (!sequence.equals("null")) {
            correctGestureSequence = Arrays.stream(sequence.split(",")).map(String::trim).collect(Collectors.toList());
        }

        setupGestures();

        // TODO(igor): the following:
        // Disable keyguard; TODO(igor): should I?
//        mDevicePolicyManager.setKeyguardDisabled(mAdminComponentName, true);
//
//        // Disable status bar
//        mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, true);
//
//        if (Build.VERSION.SDK_INT >= VERSION_CODES.N) {
//            mDevicePolicyManager.setDeviceOwnerLockScreenInfo(mAdminComponentName, "lockinfo");
//            mDevicePolicyManager.setLongSupportMessage(mAdminComponentName, "longigor");
//            mDevicePolicyManager.setShortSupportMessage(mAdminComponentName, "shortigor");
//            mDevicePolicyManager.setOrganizationName(mAdminComponentName, "orgname");
//        }
//
//        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
//            mDevicePolicyManager.setOrganizationId("orgid");
//        }
//
//        // TODO(igor): FRP
//        mDevicePolicyManager.setFactoryResetProtectionPolicy();
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
            for (String restriction : Restrictions.DEFAULT_RESTRICTIONS) {
                setUserRestriction(restriction, true);
            }
            for (String restriction : Restrictions.THEFT_MODE_RESTRICTIONS) {
                setUserRestriction(restriction, true);
            }
        } else {
            for (String restriction : Restrictions.THEFT_MODE_RESTRICTIONS) {
                setUserRestriction(restriction, false);
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
    private List<String> correctGestureSequence = Arrays.asList(
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
            if (actionId == EditorInfo.IME_ACTION_DONE &&
                    PasswordHelper.checkPassword(this, mPasswordEditText.getText().toString())) {
                onBackdoorClicked();
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

        int correctSequenceSize = correctGestureSequence.size();
        if (mGestureSequence.size() >= correctSequenceSize) {
            List<String> lastGestures = mGestureSequence.subList(mGestureSequence.size() - correctSequenceSize, mGestureSequence.size());

            if (lastGestures.equals(correctGestureSequence)) {
                mPasswordEditText.setVisibility(View.VISIBLE);
                mPasswordEditText.requestFocus();
                mGestureSequence.clear();
            }
        }
    }
}
