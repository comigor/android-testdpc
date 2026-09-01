package dev.borges.shadow;

import dev.borges.shadow.util.SettingsHelper;

public class PowerOffPreventionActivity extends SubSettingsActivity {

    @Override
    protected void onResume() {
        super.onResume();
        populateSettings();
    }

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        addDescription("Blocks power menu (power off, restart) when accessibility service is enabled. " +
            "This prevents thieves from turning off the device.");

        // Accessibility service toggle
        boolean isEnabled = POffService.isAccessibilityServiceEnabled(this);
        addSwitchRow("Accessibility Service", isEnabled, (buttonView, isChecked) -> {
            POffService.enableAccessibilityService(this);
        });

        // Detect keywords
        String keywords = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY);
        addTextInputRow("Detect keywords (comma-separated)", keywords,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.DETECT_KEYWORDS_KEY, text));

        addDescription("Keywords to detect in the power menu. When any of these are detected, " +
            "the menu is automatically dismissed. Default: power off,restart,emergency");
    }
}
