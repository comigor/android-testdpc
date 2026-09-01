package dev.borges.shadow;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HiddenAppsActivity extends AppCompatActivity {
    private static final String TAG = "HiddenAppsActivity";

    @Override
    protected void onResume() {
        super.onResume();
        if (!SettingsActivity.isAuthenticated()) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) {
            SettingsActivity.clearAuthentication();
        }
    }
    private static final String PREF_APPS_TO_HIDE = "apps_to_hide_on_theft";
    private static final String PREF_CURRENTLY_HIDDEN = "currently_hidden_apps";

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private PackageManager pm;
    private SharedPreferences prefs;

    private ListView listAppsToHide;
    private ListView listCurrentlyHidden;
    private AppListAdapter appsToHideAdapter;
    private AppListAdapter currentlyHiddenAdapter;

    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> hiddenApps = new ArrayList<>();
    private Set<String> appsToHideSet = new HashSet<>();
    private Set<String> currentlyHiddenSet = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hidden_apps);

        dpm = getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(this, DeviceAdminReceiver.class);
        pm = getPackageManager();
        prefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);

        // Load saved sets
        appsToHideSet = new HashSet<>(prefs.getStringSet(PREF_APPS_TO_HIDE, new HashSet<>()));
        currentlyHiddenSet = new HashSet<>(prefs.getStringSet(PREF_CURRENTLY_HIDDEN, new HashSet<>()));

        setupTabs();
        loadApps();
    }

    private void setupTabs() {
        TabHost tabHost = findViewById(R.id.tabHost);
        tabHost.setup();

        TabHost.TabSpec tab1 = tabHost.newTabSpec("to_hide");
        tab1.setIndicator("Apps to Hide");
        tab1.setContent(R.id.tab1);
        tabHost.addTab(tab1);

        TabHost.TabSpec tab2 = tabHost.newTabSpec("hidden");
        tab2.setIndicator("Currently Hidden");
        tab2.setContent(R.id.tab2);
        tabHost.addTab(tab2);

        listAppsToHide = findViewById(R.id.listAppsToHide);
        listCurrentlyHidden = findViewById(R.id.listCurrentlyHidden);
    }

    private void loadApps() {
        allApps.clear();
        hiddenApps.clear();

        // Cleanup stale entries in currentlyHiddenSet
        Set<String> actuallyHidden = new HashSet<>();
        for (String pkg : currentlyHiddenSet) {
            if (isAppHidden(pkg)) {
                actuallyHidden.add(pkg);
            }
        }
        if (!actuallyHidden.equals(currentlyHiddenSet)) {
            currentlyHiddenSet = actuallyHidden;
            prefs.edit().putStringSet(PREF_CURRENTLY_HIDDEN, currentlyHiddenSet).apply();
            Log.i(TAG, "Cleaned up stale hidden apps entries");
        }

        // Get all installed apps including hidden ones (MATCH_UNINSTALLED_PACKAGES includes hidden)
        List<ApplicationInfo> installedApps = pm.getInstalledApplications(
            PackageManager.GET_META_DATA | PackageManager.MATCH_UNINSTALLED_PACKAGES);

        for (ApplicationInfo appInfo : installedApps) {
            // Skip system apps and this app
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (appInfo.packageName.equals(getPackageName())) continue;

            String appName;
            try {
                appName = appInfo.loadLabel(pm).toString();
            } catch (Exception e) {
                appName = appInfo.packageName; // Fallback if label can't be loaded
            }
            boolean isSelectedToHide = appsToHideSet.contains(appInfo.packageName);
            boolean isCurrentlyHidden = isAppHidden(appInfo.packageName);

            AppInfo info = new AppInfo(appInfo.packageName, appName, appInfo, isSelectedToHide);

            if (isCurrentlyHidden) {
                hiddenApps.add(new AppInfo(appInfo.packageName, appName, appInfo, true));
            } else {
                allApps.add(info);
            }
        }

        // Sort by name
        Collections.sort(allApps, (a, b) -> a.name.compareToIgnoreCase(b.name));
        Collections.sort(hiddenApps, (a, b) -> a.name.compareToIgnoreCase(b.name));

        appsToHideAdapter = new AppListAdapter(this, allApps, false);
        currentlyHiddenAdapter = new AppListAdapter(this, hiddenApps, true);

        listAppsToHide.setAdapter(appsToHideAdapter);
        listCurrentlyHidden.setAdapter(currentlyHiddenAdapter);
    }

    private boolean isAppHidden(String packageName) {
        try {
            return dpm.isApplicationHidden(admin, packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private void toggleAppToHide(String packageName, boolean selected) {
        if (selected) {
            appsToHideSet.add(packageName);
        } else {
            appsToHideSet.remove(packageName);
        }
        prefs.edit().putStringSet(PREF_APPS_TO_HIDE, appsToHideSet).apply();
    }

    private void unhideApp(String packageName) {
        try {
            boolean result = dpm.setApplicationHidden(admin, packageName, false);
            if (result) {
                currentlyHiddenSet.remove(packageName);
                prefs.edit().putStringSet(PREF_CURRENTLY_HIDDEN, currentlyHiddenSet).apply();
                Toast.makeText(this, "App unhidden", Toast.LENGTH_SHORT).show();
                loadApps(); // Refresh lists
            } else {
                Toast.makeText(this, "Failed to unhide app", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unhiding app", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Hide all selected apps. Called when theft mode activates.
     * Only works on user 0.
     */
    public static void hideSelectedApps(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            Log.w(TAG, "hideSelectedApps: Not on system user, skipping");
            return;
        }

        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);

        Set<String> appsToHide = prefs.getStringSet(PREF_APPS_TO_HIDE, new HashSet<>());
        Set<String> currentlyHidden = new HashSet<>(prefs.getStringSet(PREF_CURRENTLY_HIDDEN, new HashSet<>()));

        for (String packageName : appsToHide) {
            try {
                boolean result = dpm.setApplicationHidden(admin, packageName, true);
                if (result) {
                    currentlyHidden.add(packageName);
                    Log.i(TAG, "Hidden app: " + packageName);
                } else {
                    Log.w(TAG, "Failed to hide app: " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error hiding app " + packageName, e);
            }
        }

        prefs.edit().putStringSet(PREF_CURRENTLY_HIDDEN, currentlyHidden).apply();
    }

    /**
     * Unhide all hidden apps. Can be called to restore apps.
     * Only works on user 0.
     */
    public static void unhideAllApps(Context context) {
        UserManager um = context.getSystemService(UserManager.class);
        if (um == null || !um.isSystemUser()) {
            Log.w(TAG, "unhideAllApps: Not on system user, skipping");
            return;
        }

        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, DeviceAdminReceiver.class);
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);

        Set<String> currentlyHidden = new HashSet<>(prefs.getStringSet(PREF_CURRENTLY_HIDDEN, new HashSet<>()));

        for (String packageName : currentlyHidden) {
            try {
                dpm.setApplicationHidden(admin, packageName, false);
                Log.i(TAG, "Unhidden app: " + packageName);
            } catch (Exception e) {
                Log.e(TAG, "Error unhiding app " + packageName, e);
            }
        }

        prefs.edit().putStringSet(PREF_CURRENTLY_HIDDEN, new HashSet<>()).apply();
    }

    /**
     * Get the set of apps that are configured to be hidden on theft mode.
     */
    public static Set<String> getAppsToHide(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("shadow_prefs", Context.MODE_PRIVATE);
        return new HashSet<>(prefs.getStringSet(PREF_APPS_TO_HIDE, new HashSet<>()));
    }

    private class AppListAdapter extends ArrayAdapter<AppInfo> {
        private final boolean isHiddenList;

        public AppListAdapter(Context context, List<AppInfo> apps, boolean isHiddenList) {
            super(context, 0, apps);
            this.isHiddenList = isHiddenList;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_app, parent, false);
            }

            AppInfo app = getItem(position);
            if (app == null) return convertView;

            ImageView icon = convertView.findViewById(R.id.appIcon);
            TextView name = convertView.findViewById(R.id.appName);
            TextView packageNameView = convertView.findViewById(R.id.appPackage);
            CheckBox checkBox = convertView.findViewById(R.id.appCheckbox);

            try {
                icon.setImageDrawable(app.appInfo.loadIcon(pm));
            } catch (Exception e) {
                icon.setImageResource(R.drawable.ic_launcher);
            }
            name.setText(app.name);
            packageNameView.setText(app.packageName);

            if (isHiddenList) {
                checkBox.setVisibility(View.GONE);
                convertView.setOnClickListener(v -> {
                    unhideApp(app.packageName);
                });
            } else {
                checkBox.setVisibility(View.VISIBLE);
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(app.selected);
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    app.selected = isChecked;
                    toggleAppToHide(app.packageName, isChecked);
                });
                convertView.setOnClickListener(v -> {
                    checkBox.setChecked(!checkBox.isChecked());
                });
            }

            return convertView;
        }
    }

    private static class AppInfo {
        String packageName;
        String name;
        ApplicationInfo appInfo;
        boolean selected;

        AppInfo(String packageName, String name, ApplicationInfo appInfo, boolean selected) {
            this.packageName = packageName;
            this.name = name;
            this.appInfo = appInfo;
            this.selected = selected;
        }
    }
}
