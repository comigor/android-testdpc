package dev.borges.shadow;

// From https://github.com/BinitDOX/FakePowerOff/blob/main/app/src/main/java/com/dox/fpoweroff/service/event/PowerMenuOverrideEvent.kt

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.afwsamples.testdpc.DeviceAdminReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.borges.shadow.util.SettingsHelper;

@TargetApi(Build.VERSION_CODES.P)
public class POffService extends AccessibilityService {
    private static final String TAG = "POffService";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String DEFAULT_KEYWORDS = "power off,restart,emergency";

    private SharedPreferences encryptedSharedPreferences;

    // Auto-kill fields
    private String lastForegroundPackage = null;
    private boolean autoKillEnabled = false;
    private Set<String> appsToAutoKill = new HashSet<>();
    private int autoKillDelaySeconds = 60;
    private Handler autoKillHandler = new Handler(Looper.getMainLooper());
    private Map<String, Runnable> pendingKills = new HashMap<>();
    private Map<String, Long> lastInteractionTimes = new HashMap<>();

    // Ignore window activity in the first ms after scheduling (app exit animations
    // can emit a final content event for the app that was just left).
    private static final long KILL_INTERACTION_SETTLE_MS = 2000;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
        (prefs, key) -> reloadSettings();
    private boolean powerOffPreventionEnabled;
    private String[] keywords;

    @Override
    public void onCreate() {
        super.onCreate();
        encryptedSharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);
        encryptedSharedPreferences.registerOnSharedPreferenceChangeListener(settingsListener);
        getSharedPreferences("shadow_prefs", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingsListener);
        reloadSettings();
    }

    @Override
    public void onDestroy() {
        encryptedSharedPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener);
        getSharedPreferences("shadow_prefs", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingsListener);
        super.onDestroy();
    }

    private void reloadSettings() {
        powerOffPreventionEnabled = "true".equals(
            SettingsHelper.getSetting(encryptedSharedPreferences, SettingsHelper.POWER_OFF_PREVENTION_ENABLED_KEY));
        keywords = encryptedSharedPreferences.getString(SettingsHelper.DETECT_KEYWORDS_KEY, DEFAULT_KEYWORDS).split(",");
        autoKillEnabled = AutoKillAppsActivity.isAutoKillEnabled(this);
        appsToAutoKill = AutoKillAppsActivity.getAppsToAutoKill(this);
        autoKillDelaySeconds = AutoKillAppsActivity.getAutoKillDelay(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Check for pending theft mode activation on every accessibility event
        PowerButtonReceiver.checkPendingTheftMode(this);

        // Auto-kill: remember recent window activity per package (used by the
        // fire-time guard to detect an app that came back to the foreground).
        recordAutoKillInteraction(event);

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
//            Log.d(TAG, "Window state changed: " + event.getPackageName() + ", " + event.getClassName());

            // Auto-kill: Track foreground app changes
            handleAutoKill(event);

            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : null;

            // bugfix: if on TheftModeActivity, press back twice
            if (getPackageName().equals(packageName) && event.getClassName() != null && event.getClassName().equals(TheftModeActivity.class.getName())) {
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                return;
            }

            if (powerOffPreventionEnabled && SYSTEM_UI_PACKAGE.equals(packageName)) {
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

    // ============ Auto-Kill Methods ============

    // Packages to ignore for foreground tracking (transient system UI elements)
    private static final Set<String> IGNORED_PACKAGES = Set.of(
        "com.android.systemui",
        "com.google.android.inputmethod.latin",  // Gboard
        "com.samsung.android.honeyboard",        // Samsung keyboard
        "com.android.inputmethod.latin",         // AOSP keyboard
        "com.swiftkey.swiftkey",                 // SwiftKey
        "com.google.android.packageinstaller",   // install/permission dialogs
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.gms",                // Play services / sign-in overlays
        "com.android.chrome",                    // Chrome Custom Tabs
        "org.chromium.chrome"
    );

    private void recordAutoKillInteraction(AccessibilityEvent event) {
        if (!autoKillEnabled) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED
                && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }
        CharSequence pkgChars = event.getPackageName();
        if (pkgChars == null) {
            return;
        }
        String packageName = pkgChars.toString();
        if (IGNORED_PACKAGES.contains(packageName) || packageName.contains(".inputmethod.")) {
            return;
        }
        lastInteractionTimes.put(packageName, System.currentTimeMillis());
    }

    private void handleAutoKill(AccessibilityEvent event) {
        if (!autoKillEnabled || event.getPackageName() == null) {
            return;
        }
        String currentPackage = event.getPackageName().toString();

        // Ignore transient system packages (keyboards, system UI, etc.)
        if (IGNORED_PACKAGES.contains(currentPackage) || currentPackage.contains(".inputmethod.")) {
            return;
        }

        // Only full-screen windows count as real app switches. Dialogs, permission
        // prompts, pickers, share sheets and chat bubbles are not full-screen, so
        // they must not mark the previous app as "left" nor clobber foreground state.
        if (!event.isFullScreen()) {
            return;
        }

        if (!currentPackage.equals(lastForegroundPackage)) {
            // Cancel pending kill if user returned to the app
            cancelPendingKill(currentPackage, "user returned to app");

            // Schedule clean purge for previous app if in auto-kill list. Reaching
            // here means a real full-screen window from a different package took the
            // foreground, so the previous app is genuinely backgrounded now.
            if (lastForegroundPackage != null && appsToAutoKill.contains(lastForegroundPackage)) {
                scheduleCleanPurge(lastForegroundPackage);
            }

            lastForegroundPackage = currentPackage;
        }
    }

    private void cancelPendingKill(String packageName, String reason) {
        Runnable pending = pendingKills.remove(packageName);
        if (pending != null) {
            autoKillHandler.removeCallbacks(pending);
            Log.d(TAG, "Auto-kill cancelled for " + packageName + " (" + reason + ")");
        }
    }

    private void scheduleCleanPurge(String packageName) {
        // Cancel any existing pending purge for this package
        Runnable existing = pendingKills.get(packageName);
        if (existing != null) {
            autoKillHandler.removeCallbacks(existing);
        }

        final long scheduledAt = System.currentTimeMillis();
        Runnable purgeRunnable = () -> {
            try {
                // Kill-time guard: the user may have returned to the app while the
                // purge was pending (e.g. an overlay closed without firing a new
                // window event for it). Re-verify before killing.
                String check = foregroundCheckDescription(packageName, scheduledAt);
                if (check != null) {
                    Log.d(TAG, "Auto-kill aborted for " + packageName + " (still foreground: " + check + ")");
                } else {
                    Log.d(TAG, "Auto-kill firing for " + packageName + " (foreground checks passed:"
                            + " trackedFg=" + lastForegroundPackage
                            + ", lastInteraction=" + lastInteractionTimes.get(packageName)
                            + ", scheduledAt=" + scheduledAt + ")");
                    cleanPurgeApp(packageName);
                }
            } finally {
                pendingKills.remove(packageName);
            }
        };

        pendingKills.put(packageName, purgeRunnable);

        if (autoKillDelaySeconds <= 0) {
            purgeRunnable.run();
        } else {
            autoKillHandler.postDelayed(purgeRunnable, autoKillDelaySeconds * 1000L);
        }
    }

    // Returns a non-null description of why the app is considered foreground, or
    // null if every check agrees the app is backgrounded and may be killed.
    private String foregroundCheckDescription(String packageName, long scheduledAt) {
        // 1. Our own full-screen window tracking still has this app on top.
        if (packageName.equals(lastForegroundPackage)) {
            return "tracked foreground package";
        }

        // 2. The app emitted window events after the kill was scheduled (past the
        // settle window that covers exit-animation noise), i.e. it is visible and
        // in use again.
        Long lastInteraction = lastInteractionTimes.get(packageName);
        if (lastInteraction != null && lastInteraction > scheduledAt + KILL_INTERACTION_SETTLE_MS) {
            return "window interaction at " + lastInteraction + " after scheduling";
        }

        // 3. Usage stats (only if the app-op is granted): the most recently
        // resumed app is still this one.
        if (isLastResumedByUsageStats(packageName, scheduledAt)) {
            return "usage stats last-resumed package";
        }

        return null;
    }

    private boolean isLastResumedByUsageStats(String packageName, long sinceMillis) {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return false;
            }
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(sinceMillis, now);
            UsageEvents.Event usageEvent = new UsageEvents.Event();
            String lastResumedPackage = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(usageEvent);
                if (usageEvent.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    lastResumedPackage = usageEvent.getPackageName();
                }
            }
            return packageName.equals(lastResumedPackage);
        } catch (Exception e) {
            Log.e(TAG, "Usage stats foreground check failed", e);
            return false;
        }
    }

    private void cleanPurgeApp(String packageName) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = DeviceAdminReceiver.getComponentName(this);

            if (dpm == null || admin == null) {
                Log.e(TAG, "Cannot purge app - DPM or admin is null");
                return;
            }

            // 1. Hide the app (this force-stops it)
            dpm.setApplicationHidden(admin, packageName, true);

            // 2. Unhide after 500ms so app is ready for next manual launch, unless it must stay hidden
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (ProtectedApps.mustStayHidden(this, packageName)) {
                    return;
                }
                try {
                    dpm.setApplicationHidden(admin, packageName, false);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to unhide app: " + packageName, e);
                }
            }, 500);
        } catch (Exception e) {
            Log.e(TAG, "Failed to purge app: " + packageName, e);
        }
    }
}
