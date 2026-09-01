package dev.borges.shadow;

import android.content.Intent;

import dev.borges.shadow.util.SettingsHelper;

public class TheftModeSettingsActivity extends SubSettingsActivity {

    @Override
    protected void populateSettings() {
        settingsContainer.removeAllViews();

        // Theft mode title
        String title = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY);
        addTextInputRow("Theft mode title", title,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.THEFT_MODE_TITLE_KEY, text));

        // Theft mode instructions
        String instructions = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY);
        addMultilineTextInputRow("Theft mode instructions", instructions,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.THEFT_MODE_INSTRUCTIONS_KEY, text));

        // Power button presses
        String presses = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY);
        addNumberInputRow("Power button presses to activate", presses,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.POWER_BUTTON_PRESSES_KEY, text));

        // Press time window
        String timeWindow = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY);
        addNumberInputRow("Time window between presses (ms)", timeWindow,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.PRESS_TIME_WINDOW_KEY, text));

        // Activation delay
        String delay = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY);
        addNumberInputRow("Activation delay (seconds)", delay,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.ACTIVATION_DELAY_KEY, text));

        // Deactivation sequence
        String sequence = SettingsHelper.getSetting(sharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY);
        addTextInputRow("Deactivation sequence (e.g., up,up,down,down,right)", sequence,
            text -> SettingsHelper.setSetting(sharedPreferences, SettingsHelper.DEACTIVATION_SEQUENCE_KEY, text));

        // Hidden apps button
        addClickableItem("Apps to hide on theft mode",
            () -> startChildActivity(new Intent(this, HiddenAppsActivity.class)));
    }
}
