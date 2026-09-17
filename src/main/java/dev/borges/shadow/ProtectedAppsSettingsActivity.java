package dev.borges.shadow;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.borges.shadow.util.SettingsHelper;

public class ProtectedAppsSettingsActivity extends SubSettingsActivity {

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        addDescription("Selected apps stay hidden until the Protected Apps PIN is entered from its launcher icon. "
            + "They re-hide when the window ends, when the phone locks, or when theft mode starts. "
            + "App data is not encrypted by this PIN.");

        boolean pinSet = ProtectedApps.isPinSet(this);
        addClickableItem(pinSet ? "Change PIN" : "Set PIN", this::promptPin);

        String minutes = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY);
        addNumberInputRow("Access window (minutes)", minutes, text -> {
            int value = SettingsHelper.parseInt(text, 0);
            if (value > 0) {
                SettingsHelper.setSetting(sharedPreferences, SettingsHelper.PROTECTED_APPS_WINDOW_MINUTES_KEY, String.valueOf(value));
            }
        });

        boolean enabled = ProtectedApps.isEnabled(this);
        addSwitchRow("Enable protected apps", enabled, (button, checked) -> {
            if (checked && !ProtectedApps.isPinSet(this)) {
                Toast.makeText(this, "Set a PIN first", Toast.LENGTH_SHORT).show();
                button.setChecked(false);
                return;
            }
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.PROTECTED_APPS_ENABLED_KEY, checked ? "true" : "false");
            ProtectedApps.setLauncherIconVisible(this, checked);
            if (checked) {
                ProtectedApps.lock(this);
            } else {
                ProtectedApps.disable(this);
            }
        });

        addClickableItem("Lock now", () -> {
            ProtectedApps.lock(this);
            Toast.makeText(this, "Protected apps hidden", Toast.LENGTH_SHORT).show();
        });

        addSectionTitle("Protected apps");
        Set<String> selected = ProtectedApps.getPackages(this);
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).stream()
            .filter(info -> (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
            .filter(info -> !info.packageName.equals(getPackageName()))
            .sorted(Comparator.comparing(info -> info.loadLabel(pm).toString().toLowerCase()))
            .collect(Collectors.toList());

        for (ApplicationInfo info : apps) {
            CheckBox box = new CheckBox(this);
            box.setText(info.loadLabel(pm) + "\n" + info.packageName);
            box.setChecked(selected.contains(info.packageName));
            box.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
            box.setOnCheckedChangeListener((button, checked) -> {
                Set<String> current = ProtectedApps.getPackages(this);
                if (checked) {
                    current.add(info.packageName);
                } else {
                    current.remove(info.packageName);
                }
                ProtectedApps.setPackages(this, current);
                ProtectedApps.applyMembership(this, info.packageName, checked);
            });
            settingsContainer.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void promptPin() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(8), dpToPx(24), 0);

        EditText pin = new EditText(this);
        pin.setHint("New PIN");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(pin);

        EditText confirm = new EditText(this);
        confirm.setHint("Confirm PIN");
        confirm.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        layout.addView(confirm);

        new AlertDialog.Builder(this)
            .setTitle("Protected Apps PIN")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (dialog, which) -> {
                String a = pin.getText().toString();
                String b = confirm.getText().toString();
                if (a.length() < 4 || !a.equals(b)) {
                    Toast.makeText(this, "PINs must match and have at least 4 digits", Toast.LENGTH_SHORT).show();
                    return;
                }
                ProtectedApps.setPin(this, a);
                Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show();
                populateSettings();
            })
            .show();
    }
}
