package dev.borges.shadow;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.afwsamples.testdpc.DeviceAdminReceiver;
import com.afwsamples.testdpc.R;

import java.util.function.Consumer;

import dev.borges.shadow.util.SettingsHelper;

/**
 * Base class for all feature configuration sub-activities.
 * Provides common UI helpers and setup.
 */
public abstract class SubSettingsActivity extends AppCompatActivity {

    protected LinearLayout settingsContainer;
    protected SharedPreferences sharedPreferences;
    protected DevicePolicyManager dpm;
    protected ComponentName adminComponentName;
    protected boolean isDeviceOwner = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feature_settings);

        settingsContainer = findViewById(R.id.settings_container);
        sharedPreferences = SettingsHelper.getEncryptedSharedPreferences(this);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponentName = new ComponentName(this, DeviceAdminReceiver.class);
        isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        populateSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If not authenticated, finish and go back to main settings (which will prompt for password)
        if (!SettingsActivity.isAuthenticated()) {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Clear authentication only when backgrounding (not when pressing back)
        if (!isFinishing()) {
            SettingsActivity.clearAuthentication();
        }
    }

    /**
     * Override this method to add your settings UI.
     */
    protected abstract void populateSettings();

    // ============ UI Helper Methods ============

    protected void addDescription(String text) {
        TextView desc = new TextView(this);
        desc.setText(text);
        desc.setTextSize(14);
        desc.setTextColor(0xFF888888);
        desc.setPadding(0, dpToPx(8), 0, dpToPx(16));
        settingsContainer.addView(desc);
    }

    protected void addSectionTitle(String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setPadding(0, dpToPx(24), 0, dpToPx(8));
        settingsContainer.addView(titleView);
    }

    protected TextView addClickableItem(String label, Runnable onClick) {
        TextView textView = new TextView(this);
        textView.setText(label);
        textView.setTextSize(16);
        textView.setTextColor(0xFFFFFFFF);
        textView.setPadding(0, dpToPx(12), 0, dpToPx(12));
        textView.setClickable(true);
        textView.setFocusable(true);
        textView.setOnClickListener(v -> onClick.run());
        settingsContainer.addView(textView);
        return textView;
    }

    protected LinearLayout addSwitchRow(String label, boolean isChecked,
                                         android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, dpToPx(8), 0, dpToPx(8));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(labelView);

        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(isChecked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle);

        settingsContainer.addView(row);
        return row;
    }

    protected LinearLayout addTextInputRow(String label, String initialValue, Consumer<String> onTextChanged) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(0, dpToPx(8), 0, dpToPx(8));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(0xFFAAAAAA);
        layout.addView(labelView);

        EditText editText = new EditText(this);
        editText.setText(initialValue);
        editText.setTextColor(0xFFFFFFFF);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);

        settingsContainer.addView(layout);
        return layout;
    }

    protected LinearLayout addMultilineTextInputRow(String label, String initialValue, Consumer<String> onTextChanged) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(0, dpToPx(8), 0, dpToPx(8));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(0xFFAAAAAA);
        layout.addView(labelView);

        EditText editText = new EditText(this);
        editText.setText(initialValue);
        editText.setTextColor(0xFFFFFFFF);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editText.setMinLines(2);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);

        settingsContainer.addView(layout);
        return layout;
    }

    protected LinearLayout addNumberInputRow(String label, String initialValue, Consumer<String> onTextChanged) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(0, dpToPx(8), 0, dpToPx(8));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(14);
        labelView.setTextColor(0xFFAAAAAA);
        layout.addView(labelView);

        EditText editText = new EditText(this);
        editText.setText(initialValue);
        editText.setTextColor(0xFFFFFFFF);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.addTextChangedListener(new TextWatcherAdapter() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                onTextChanged.accept(s == null ? null : s.toString());
            }
        });
        layout.addView(editText);

        settingsContainer.addView(layout);
        return layout;
    }

    protected int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ============ Text Watcher Adapter ============

    protected static abstract class TextWatcherAdapter implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void afterTextChanged(android.text.Editable s) {}
    }
}
