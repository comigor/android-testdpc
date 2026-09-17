package dev.borges.shadow;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.afwsamples.testdpc.R;
import dev.borges.shadow.util.SettingsHelper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ProtectedAppsSettingsActivity extends SubSettingsActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<AppEntry> allApps = new ArrayList<>();
    private TextView pinSetting;
    private TextView windowSetting;
    private EditText search;
    private AppAdapter adapter;

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();
        ListView list = new ListView(this);
        ((ViewGroup) settingsContainer.getParent()).removeView(settingsContainer);
        list.addHeaderView(settingsContainer, null, false);
        setContentView(list);
        addDescription("Selected apps stay hidden until the Vault PIN is entered. "
            + "They re-hide at the deadline, when the screen turns off, or when theft mode starts. "
            + "The PIN controls app availability, not data encryption.");
        pinSetting = addClickableItem(ProtectedApps.isPinSet(this) ? "Change PIN" : "Set PIN", this::promptPin);
        windowSetting = addClickableItem(windowLabel(), this::promptWindow);
        addSwitchRow("Enable protected apps", ProtectedApps.isEnabled(this), (button, checked) -> {
            if (checked && !ProtectedApps.isPinSet(this)) {
                Toast.makeText(this, "Set a PIN first", Toast.LENGTH_SHORT).show();
                button.setChecked(false);
                return;
            }
            SettingsHelper.setSetting(sharedPreferences, SettingsHelper.PROTECTED_APPS_ENABLED_KEY, String.valueOf(checked));
            ProtectedApps.setLauncherIconVisible(this, checked);
            if (checked) ProtectedApps.lock(this);
            else ProtectedApps.disable(this);
        });
        addClickableItem("Lock now", () -> ProtectedApps.lock(this));
        addSectionTitle("Protected apps");
        search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        settingsContainer.addView(search);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                filterApps();
            }
        });
        worker.execute(() -> {
            PackageManager pm = getPackageManager();
            List<AppEntry> entries = pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES).stream()
                .filter(info -> (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0)
                .filter(info -> !info.packageName.equals(getPackageName()))
                .map(info -> new AppEntry(info, info.loadLabel(pm).toString()))
                .sorted(Comparator.comparing(entry -> entry.name, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                allApps.addAll(entries);
                filterApps();
            });
        });
    }

    private String windowLabel() {
        return "Access window: " + ProtectedApps.getWindowMinutes(this) + " minutes";
    }

    private void filterApps() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        adapter.clear();
        for (AppEntry entry : allApps) {
            if (entry.search.contains(query)) adapter.add(entry);
        }
        adapter.notifyDataSetChanged();
    }

    private void promptWindow() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(ProtectedApps.getWindowMinutes(this)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Access window (minutes)")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!AdminSession.isAuthenticated()) { dialog.dismiss(); return; }
            try {
                ProtectedApps.setWindowMinutes(this, input.getText().toString());
                windowSetting.setText(windowLabel());
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                input.setError("Enter a positive whole number of minutes");
            }
        }));
        dialog.show();
    }

    private void promptPin() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dpToPx(24), dpToPx(8), dpToPx(24), 0);
        EditText pin = pinField("New PIN", layout);
        EditText confirmation = pinField("Confirm PIN", layout);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Protected Apps PIN").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = pin.getText().toString();
            if (!value.matches("[0-9]{4,}") || !value.equals(confirmation.getText().toString())) {
                confirmation.setError("PINs must match and contain at least four digits");
                return;
            }
            if (!AdminSession.isAuthenticated()) { dialog.dismiss(); return; }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            worker.execute(() -> {
                try {
                    ProtectedApps.setPin(this, value);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        pin.setText("");
                        confirmation.setText("");
                        pinSetting.setText("Change PIN");
                        dialog.dismiss();
                    });
                } catch (RuntimeException e) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        confirmation.setError("Could not save PIN; existing protection remains configured");
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                }
            });
        }));
        dialog.show();
    }

    private EditText pinField(String hint, LinearLayout parent) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(input);
        return input;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry {
        final ApplicationInfo info;
        final String name;
        final String search;

        AppEntry(ApplicationInfo info, String name) {
            this.info = info;
            this.name = name;
            search = (name + " " + info.packageName).toLowerCase(Locale.ROOT);
        }
    }

    private final class AppAdapter extends ArrayAdapter<AppEntry> {
        AppAdapter() {
            super(ProtectedAppsSettingsActivity.this, 0);
            setNotifyOnChange(false);
        }

        @Override
        public View getView(int position, View row, ViewGroup parent) {
            if (row == null) row = LayoutInflater.from(getContext()).inflate(R.layout.item_app, parent, false);
            AppEntry entry = getItem(position);
            ((TextView) row.findViewById(R.id.appName)).setText(entry.name);
            ((TextView) row.findViewById(R.id.appPackage)).setText(entry.info.packageName);
            ((ImageView) row.findViewById(R.id.appIcon)).setImageDrawable(entry.info.loadIcon(getPackageManager()));
            CheckBox checked = row.findViewById(R.id.appCheckbox);
            checked.setOnCheckedChangeListener(null);
            checked.setChecked(ProtectedApps.getPackages(getContext()).contains(entry.info.packageName));
            checked.setOnCheckedChangeListener((button, selected) -> {
                if (!AdminSession.isAuthenticated()) return;
                Set<String> packages = ProtectedApps.getPackages(getContext());
                if (selected) packages.add(entry.info.packageName);
                else packages.remove(entry.info.packageName);
                ProtectedApps.setPackages(getContext(), packages);
                ProtectedApps.applyMembership(getContext(), entry.info.packageName, selected);
            });
            row.setOnClickListener(v -> checked.setChecked(!checked.isChecked()));
            return row;
        }
    }
}
