package dev.borges.shadow;

// From https://github.com/BinitDOX/FakePowerOff/blob/main/app/src/main/java/com/dox/fpoweroff/service/event/PowerMenuOverrideEvent.kt

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@TargetApi(Build.VERSION_CODES.P)
public class POffService extends AccessibilityService {
    private static final String TAG = "POffService";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    public static final List<String> DETECT_KEYWORDS = Arrays.asList("power off", "restart", "emergency"); // TODO(igor): make this configurable

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
//            Log.d(TAG, "Window state changed");
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : null;

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
                            return;
                        }

                        if (
                                (tooltipText != null && containsKeyword(tooltipText.toString())) ||
                                        (hintText != null && containsKeyword(hintText.toString())) ||
                                        (contentDescription != null && containsKeyword(contentDescription.toString())) ||
                                        (text != null && containsKeyword(text.toString()))
                        ) {
                            Log.d(TAG, "[" + "handlePowerMenuEvent" + "] Detected");
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

    public static void startSpecialPermissionActivity(Context context) {
        ComponentName expectedComponentName = new ComponentName(context, POffService.class);
        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServicesSetting == null || !enabledServicesSetting.contains(expectedComponentName.flattenToString())) {
            // TODO(igor): do it in a better way
            Toast.makeText(context, "Please grant ACCESSIBILITY permission", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    private boolean containsKeyword(String text) {
        for (String keyword : DETECT_KEYWORDS) {
            if (text.equalsIgnoreCase(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
