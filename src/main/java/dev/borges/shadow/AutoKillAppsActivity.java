package dev.borges.shadow;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.afwsamples.testdpc.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.borges.shadow.util.SettingsHelper;

public class AutoKillAppsActivity extends AppCompatActivity {
    private static final String TAG = "AutoKillAppsActivity";
    private static final String PREF_APPS_TO_AUTO_KILL = "apps_to_auto_kill";

    private PackageManager pm;
    private SharedPreferences prefs;

    private ListView listApps;

    private List<AppInfo> allApps = new ArrayList<>();
    private Set<String> appsToKillSet = new HashSet<>();
    private AppListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_kill_apps);

        pm = getPackageManager();
        prefs = getSharedPreferences("shadow_prefs", MODE_PRIVATE);

        // Load saved set
        appsToKillSet = new HashSet<>(prefs.getStringSet(PREF_APPS_TO_AUTO_KILL, new HashSet<>()));

        listApps = findViewById(R.id.listApps);
        loadApps();
    }

    private void loadApps() {
        allApps.clear();

        List<ApplicationInfo> installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : installedApps) {
            // Skip system apps and this app
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (appInfo.packageName.equals(getPackageName())) continue;

            String appName;
            try {
                appName = appInfo.loadLabel(pm).toString();
            } catch (Exception e) {
                appName = appInfo.packageName;
            }

            boolean isSelected = appsToKillSet.contains(appInfo.packageName);
            allApps.add(new AppInfo(appInfo.packageName, appName, appInfo, isSelected));
        }

        // Sort by name
        Collections.sort(allApps, (a, b) -> a.name.compareToIgnoreCase(b.name));

        adapter = new AppListAdapter(this, allApps);
        listApps.setAdapter(adapter);
    }

    private void toggleAppToAutoKill(String packageName, boolean selected) {
        if (selected) {
            appsToKillSet.add(packageName);
        } else {
            appsToKillSet.remove(packageName);
        }
        prefs.edit().putStringSet(PREF_APPS_TO_AUTO_KILL, appsToKillSet).apply();
        Log.d(TAG, "Apps to auto-kill updated: " + appsToKillSet.size() + " apps");
    }

    // ============ Static methods for POffService to query ============

    public static boolean isAutoKillEnabled(Context context) {
        SharedPreferences encryptedPrefs = SettingsHelper.getEncryptedSharedPreferences(context);
        return "true".equals(SettingsHelper.getSetting(encryptedPrefs, SettingsHelper.AUTO_KILL_ENABLED_KEY));
    }

    public static Set<String> getAppsToAutoKill(Context context) {
        return new HashSet<>(context.getSharedPreferences("shadow_prefs", MODE_PRIVATE)
            .getStringSet(PREF_APPS_TO_AUTO_KILL, new HashSet<>()));
    }

    public static int getAutoKillDelay(Context context) {
        SharedPreferences encryptedPrefs = SettingsHelper.getEncryptedSharedPreferences(context);
        String value = SettingsHelper.getSetting(encryptedPrefs, SettingsHelper.AUTO_KILL_DELAY_KEY);
        return SettingsHelper.parseInt(value, 60);
    }

    // ============ Adapter ============

    private class AppListAdapter extends ArrayAdapter<AppInfo> {
        public AppListAdapter(Context context, List<AppInfo> apps) {
            super(context, 0, apps);
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

            checkBox.setVisibility(View.VISIBLE);
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(app.selected);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.selected = isChecked;
                toggleAppToAutoKill(app.packageName, isChecked);
            });

            convertView.setOnClickListener(v -> checkBox.setChecked(!checkBox.isChecked()));

            return convertView;
        }
    }

    // ============ Data class ============

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
