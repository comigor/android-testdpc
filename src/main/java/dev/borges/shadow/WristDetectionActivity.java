package dev.borges.shadow;

import dev.borges.shadow.util.SettingsHelper;

public class WristDetectionActivity extends SubSettingsActivity {

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        // Wrist removal timeout
        String timeout = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.WRIST_REMOVAL_TIMEOUT_KEY);
        addNumberInputRow("Wrist removal timeout (seconds)", timeout,
            v -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.WRIST_REMOVAL_TIMEOUT_KEY, v));

        // Description
        addDescription("When enabled, removing the watch from your wrist will trigger theft mode countdown.\n\n" +
            "IMPORTANT:\n" +
            "• Requires Wear OS companion app installed on watch\n" +
            "• Watch must be connected via Bluetooth\n" +
            "• Also triggers if watch disconnects while on-wrist\n" +
            "• Use Watch Protection settings to install the companion app");
    }
}
