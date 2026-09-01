package dev.borges.shadow;

import android.app.admin.FactoryResetProtectionPolicy;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import dev.borges.shadow.util.SettingsHelper;

public class FRPSettingsActivity extends SubSettingsActivity {
    private static final String TAG = "FRPSettingsActivity";

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        // Description with link
        TextView descView = new TextView(this);
        descView.setText("This will prevent all Google accounts other than the listed ones from being able to access your device, even after factory reset. USE WITH CAUTION.\n\nTap here to get your Google Account ID.");
        descView.setTextSize(14);
        descView.setTextColor(0xFF888888);
        descView.setPadding(0, dpToPx(8), 0, dpToPx(16));
        descView.setClickable(true);
        descView.setOnClickListener(v -> {
            Uri webpage = Uri.parse("https://developers.google.com/people/api/rest/v1/people/get?apix_params=%7B%22resourceName%22%3A%22people%2Fme%22%2C%22personFields%22%3A%22metadata%22%7D");
            Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            } else {
                Toast.makeText(this, "No web browser app found", Toast.LENGTH_SHORT).show();
            }
        });
        settingsContainer.addView(descView);

        // Account IDs input
        String accountIds = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
        addTextInputRow("Google Account IDs (comma-separated)", accountIds,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS, text));

        // FRP toggle (only on Android R+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            FactoryResetProtectionPolicy policy = dpm.getFactoryResetProtectionPolicy(adminComponentName);
            boolean isEnabled = policy != null && policy.isFactoryResetProtectionEnabled();

            addSwitchRow("Enable FRP", isEnabled, (buttonView, newChecked) -> {
                String value = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.FRP_ACCOUNT_IDS);
                List<String> ids = Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(s -> s.length() == 21)
                    .collect(Collectors.toList());

                if (ids.isEmpty()) {
                    Toast.makeText(this, "No valid Google Account IDs (must be 21 chars)", Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false);
                    return;
                }

                try {
                    dpm.setFactoryResetProtectionPolicy(
                        adminComponentName,
                        new FactoryResetProtectionPolicy.Builder()
                            .setFactoryResetProtectionAccounts(ids)
                            .setFactoryResetProtectionEnabled(newChecked)
                            .build());
                    Toast.makeText(this, newChecked ? "FRP enabled" : "FRP disabled", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set FRP policy", e);
                    Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(!newChecked);
                }
            });
        } else {
            addDescription("FRP toggle requires Android 11 or higher.");
        }
    }
}
