package dev.borges.shadow;

// From https://github.com/BinitDOX/FakePowerOff/blob/main/app/src/main/java/com/dox/fpoweroff/service/event/PowerMenuOverrideEvent.kt

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.afwsamples.testdpc.DeviceAdminReceiver;

import java.util.ArrayList;
import java.util.List;

import dev.borges.shadow.util.SettingsHelper;

@TargetApi(Build.VERSION_CODES.P)
public class POffService extends AccessibilityService {
    private static final String TAG = "POffService";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DEFAULT_KEYWORDS = "power off,restart,emergency";

    private SharedPreferences encryptedSharedPreferences;

    @Override
    public void onCreate() {
        super.onCreate();
        encryptedSharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Check for pending theft mode activation on every accessibility event
        PowerButtonReceiver.checkPendingTheftMode(this);

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
//            Log.d(TAG, "Window state changed: " + event.getPackageName() + ", " + event.getClassName());
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : null;

            // bugfix: if on TheftModeActivity, press back twice
            if (getPackageName().equals(packageName) && event.getClassName() != null && event.getClassName().equals(TheftModeActivity.class.getName())) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return;
            }

            if (SYSTEM_UI_PACKAGE.equals(packageName)) {
                KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean isScreenLocked = false;
                if (keyguardManager != null) {
                    isScreenLocked = keyguardManager.isDeviceLocked() || keyguardManager.isKeyguardLocked();
                }

                try {
                    AccessibilityNodeInfo parentNodeInfo = event.getSource();
                    if (parentNodeInfo == null) return;

                    List<AccessibilityNodeInfo> nodeQueue = new ArrayList<>();
                    nodeQueue.add(parentNodeInfo);

                    String[] keywords = encryptedSharedPreferences.getString(SettingsHelper.DETECT_KEYWORDS_KEY, DEFAULT_KEYWORDS).split(",");

                    while (!nodeQueue.isEmpty()) {
                        AccessibilityNodeInfo currentNode = nodeQueue.remove(0);
                        if (currentNode == null) continue;

                        for (int i = 0; i < currentNode.getChildCount(); i++) {
                            nodeQueue.add(currentNode.getChild(i));
                        }
                        CharSequence text = currentNode.getText();
                        CharSequence tooltipText = currentNode.getTooltipText();
                        CharSequence hintText = currentNode.getHintText();
                        CharSequence contentDescription = currentNode.getContentDescription();
//                        Log.d(TAG, "strings: " + text + ", " + tooltipText + ", " + hintText + ", " + contentDescription + ", " + currentNode.getPaneTitle());

                        if (isScreenLocked && currentNode.getPaneTitle() != null && currentNode.getPaneTitle().equals("Quick settings.")) {
                            Log.d(TAG, "Quick settings detected when device is locked.");
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            return;
                        }

                        if (
                                (tooltipText != null && containsKeyword(tooltipText.toString(), keywords)) ||
                                        (hintText != null && containsKeyword(hintText.toString(), keywords)) ||
                                        (contentDescription != null && containsKeyword(contentDescription.toString(), keywords)) ||
                                        (text != null && containsKeyword(text.toString(), keywords))
                        ) {
                            Log.d(TAG, "[" + "handlePowerMenuEvent" + "] Detected");
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[" + "handlePowerMenuEvent" + "] Error: " + e);
                    e.printStackTrace();
                }
            }
        }
    }

    public static void enableAccessibilityService(Context context) {
        final ComponentName mAdminComponentName = DeviceAdminReceiver.getComponentName(context);
        if (!isAccessibilityServiceEnabled(context) && mAdminComponentName != null) {
            // TODO(igor): do this in a better way
            Toast.makeText(context, "Please grant ACCESSIBILITY permission", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        ComponentName expectedComponentName = new ComponentName(context, POffService.class);
        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        return enabledServicesSetting != null && enabledServicesSetting.contains(expectedComponentName.flattenToString());
    }

    private boolean containsKeyword(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.equalsIgnoreCase(keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
