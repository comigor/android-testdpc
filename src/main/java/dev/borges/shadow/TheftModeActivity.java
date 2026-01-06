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
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.stream.Collectors;

import dev.borges.shadow.util.PasswordHelper;
import dev.borges.shadow.util.Restrictions;
import dev.borges.shadow.util.SettingsHelper;

public class TheftModeActivity extends Activity {
    private static final String TAG = "TheftModeActivity";

    public static final String STOP_THEFT_MODE = "dev.borges.shadow.STOP_THEFT_MODE";

    private boolean backdoorTriggered = false;
    private boolean passwordInputVisible = false;

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

            Log.i(TAG, "Disabling status bar");
            mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, true);

            Log.i(TAG, "Setting activity as home/launcher");
            final ComponentName customLauncher = new ComponentName(context.getPackageName(), TheftModeActivity.class.getName());
            mPackageManager.setComponentEnabledSetting(
                    customLauncher,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            mDevicePolicyManager.addPersistentPreferredActivity(
                    mAdminComponentName, Util.getHomeIntentFilter(), customLauncher);

            // Disable fingerprint access
            Log.i(TAG, "Disabling fingerprint access");
            dev.borges.shadow.util.PasswordHelper.deleteBiometricKey();

            // Hide selected apps (only on user 0)
            Log.i(TAG, "Hiding selected apps");
            HiddenAppsActivity.hideSelectedApps(context);

            Log.i(TAG, "Starting (home) activity");
            Intent launchIntent = Util.getHomeIntent();
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error while starting theft mode!");
            e.printStackTrace();
        }
    }

    public static void stopTheftMode(Context context) {
        try {
            Log.i(TAG, "Stopping theft mode...");
            Intent launchIntent = Util.getHomeIntent();
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            launchIntent.putExtra(TheftModeActivity.STOP_THEFT_MODE, true);
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error while stopping theft mode!");
            e.printStackTrace();
        }
    }

    // TODO(igor): understand why this is not working
    public void onBackdoorClicked() {
        backdoorTriggered = true;

        Log.i(TAG, "Re-enabling status bar");
        mDevicePolicyManager.setStatusBarDisabled(mAdminComponentName, false);

        Log.i(TAG, "Stopping kiosk mode");
        mDevicePolicyManager.setLockTaskPackages(mAdminComponentName, new String[]{});
        stopLockTask();
        mDevicePolicyManager.clearPackagePersistentPreferredActivities(mAdminComponentName, getPackageName());

        Log.i(TAG, "Resetting user restrictions");
        setDefaultKioskPolicies(false);

        Log.i(TAG, "Unhiding all hidden apps");
        HiddenAppsActivity.unhideAllApps(this);

        Log.i(TAG, "Clearing home/launcher activity");
        final ComponentName customLauncher = new ComponentName(getPackageName(), TheftModeActivity.class.getName());
        mPackageManager.setComponentEnabledSetting(
            customLauncher,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        );

        Log.i(TAG, "Starting (home) activity");
        Intent launchIntent = Util.getHomeIntent();
        startActivity(launchIntent);
    }

    private ComponentName mAdminComponentName;
    private DevicePolicyManager mDevicePolicyManager;
    private PackageManager mPackageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        backdoorTriggered = false;

        mAdminComponentName = DeviceAdminReceiver.getComponentName(this);
        mDevicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mPackageManager = getPackageManager();
        SharedPreferences sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);

        Log.i(TAG, "Starting kiosk mode");
        try {
            mDevicePolicyManager.setLockTaskPackages(mAdminComponentName, new String[]{getPackageName()});
            if (mDevicePolicyManager.isLockTaskPermitted(getPackageName())) {
                startLockTask();
            }
        } catch (SecurityException e) {
            // Expected on secondary user where we're not profile owner
            Log.w(TAG, "Could not start lock task (not admin on this user): " + e.getMessage());
        }

        Log.i(TAG, "Setting custom user restrictions");
        try {
            setDefaultKioskPolicies(true);
        } catch (SecurityException e) {
            // Expected on secondary user where we're not profile owner
            Log.w(TAG, "Could not set kiosk policies (not admin on this user): " + e.getMessage());
        }

        // set beautiful UI
        setContentView(R.layout.activity_theft_mode);

        TextView title = findViewById(R.id.title);
        title.setText(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY));

        TextView message = findViewById(R.id.contact_info);
        message.setText(SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY));

        Log.i(TAG, "Setting up backdoor gestures");
        String sequence = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY);
        correctGestureSequence = Arrays.stream(sequence.split(",")).map(String::trim).filter(POSSIBLE_GESTURES::contains).collect(Collectors.toList());
        setupGestures();

        // Check if test mode is active - show visible exit button
        SharedPreferences shadowPrefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);
        if (shadowPrefs.getBoolean("test_mode_active", false)) {
            android.widget.Button exitButton = new android.widget.Button(this);
            exitButton.setText("EXIT TEST MODE");
            exitButton.setBackgroundColor(0xFF4CAF50);
            exitButton.setTextColor(0xFFFFFFFF);
            exitButton.setOnClickListener(v -> {
                shadowPrefs.edit().remove("test_mode_active").apply();
                onBackdoorClicked();
            });

            android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            );
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            params.topMargin = 100;
            ((android.widget.FrameLayout) findViewById(android.R.id.content)).addView(exitButton, params);
        }

        Log.i(TAG, "Changing owntracks mode to 'move'");
        try {
            Intent intent = new Intent("org.owntracks.android.CHANGE_MONITORING");
            intent.setPackage("org.owntracks.android");
            intent.putExtra("monitoring", 2);
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error while changing owntracks mode to 'move'!");
            e.printStackTrace();
        }

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
        Log.i(TAG, "onUserLeaveHint");
        // Bring the app back to the foreground
        Intent intent = new Intent(this, TheftModeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Don't reapply immersive mode when password input is visible (keyboard needs to show)
        if (!passwordInputVisible) {
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
        Log.i(TAG, "onPause, backdoorTriggered=" + backdoorTriggered);
        // Restart the activity if the user tries to leave it
        if (!isTaskRoot() && !backdoorTriggered) {
            startActivity(new Intent(this, TheftModeActivity.class));
        }
    }

    private void setUserRestriction(String restriction, boolean disallow) {
        try {
            if (disallow) {
                mDevicePolicyManager.addUserRestriction(mAdminComponentName, restriction);
            } else {
                mDevicePolicyManager.clearUserRestriction(mAdminComponentName, restriction);
            }
        } catch (SecurityException e) {
            // Expected on secondary user where we're not profile owner
            Log.w(TAG, "Could not set restriction " + restriction + ": " + e.getMessage());
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
    private final List<String> POSSIBLE_GESTURES = Arrays.asList("up", "down", "left", "right");
    private final List<String> mGestureSequence = new ArrayList<>();
    private List<String> correctGestureSequence = new ArrayList<>();

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
                showPasswordInput();
                mGestureSequence.clear();
            }
        }
    }

    private void showPasswordInput() {
        Log.i(TAG, "Showing password input");
        passwordInputVisible = true;

        // Clear DISALLOW_CREATE_WINDOWS to allow keyboard to appear
        try {
            mDevicePolicyManager.clearUserRestriction(mAdminComponentName,
                android.os.UserManager.DISALLOW_CREATE_WINDOWS);
            Log.i(TAG, "Cleared DISALLOW_CREATE_WINDOWS restriction");
        } catch (SecurityException e) {
            // Expected on secondary user where we're not profile owner
            Log.w(TAG, "Could not clear DISALLOW_CREATE_WINDOWS (not admin): " + e.getMessage());
        }

        // Exit immersive mode to allow keyboard
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);

        // Make password field visible and focusable
        mPasswordEditText.setVisibility(View.VISIBLE);
        mPasswordEditText.setFocusable(true);
        mPasswordEditText.setFocusableInTouchMode(true);
        mPasswordEditText.requestFocus();

        // Allow keyboard to resize the screen
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Show keyboard with multiple attempts
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            // First attempt immediately
            imm.showSoftInput(mPasswordEditText, InputMethodManager.SHOW_FORCED);

            // Second attempt after short delay
            mPasswordEditText.postDelayed(() -> {
                mPasswordEditText.requestFocus();
                imm.showSoftInput(mPasswordEditText, InputMethodManager.SHOW_FORCED);
            }, 100);

            // Third attempt with toggle
            mPasswordEditText.postDelayed(() -> {
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }, 300);
        }
    }
}
