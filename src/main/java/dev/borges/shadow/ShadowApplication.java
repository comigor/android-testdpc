package dev.borges.shadow;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class ShadowApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Set<Activity> startedActivities = Collections.newSetFromMap(new IdentityHashMap<>());

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                AdminSession.lock();
                if (context.getSystemService(android.os.UserManager.class).isUserUnlocked()) {
                    ProtectedApps.lock(context);
                }
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities.add(activity);
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities.remove(activity);
        if (startedActivities.isEmpty() && !activity.isChangingConfigurations()) {
            AdminSession.lock();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle state) {}

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity instanceof ProtectedAppsActivity || activity instanceof TheftModeActivity) {
            AdminSession.lock();
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}
}
