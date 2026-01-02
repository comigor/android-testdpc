# Decoy Profile Feature Implementation Plan

1.  **Permissions and Manifest Changes:**
    *   Add `android.permission.INTERACT_ACROSS_USERS` to `AndroidManifest.xml`.
    *   Add `android.permission.MANAGE_USERS` to `AndroidManifest.xml` to allow user creation.
    *   Apply `UserManager.DISALLOW_USER_SWITCH` user restriction to hide the system's user-switching UI.

2.  **Main Activity (`SettingsActivity.java`):**
    *   Identify the main launcher activity, which appears to be `SettingsActivity`.
    *   Add a "Create Decoy Profile" button to its layout file.
    *   Implement the `createDecoyProfile()` function in `SettingsActivity.java` to:
        *   Create a persistent secondary user named "Guest."
        *   Store the new user's serial number in `SharedPreferences`.
        *   Install the existing Shadow application into the new user's profile.

3.  **DeviceAdminReceiver (`DeviceAdminReceiver.java`):**
    *   Override the `onPasswordFailed()` method.
    *   Inside `onPasswordFailed()`, implement the trigger logic:
        *   Check if the failure occurred on the primary user (User 0).
        *   Retrieve the decoy user's serial number from `SharedPreferences`.
        *   Convert the serial number to a `UserHandle`.
        *   Execute `dpm.switchUser()` to switch to the decoy profile.

4.  **Secret Backdoor:**
    *   In the `SettingsActivity` layout, add a "Return to Owner" button.
    *   This button's visibility will be set to `gone` by default.
    *   In `SettingsActivity.onCreate()`, check if `Process.myUserHandle()` is the system user. If not, make the "Return to Owner" button visible.
    *   Implement the `returnToRealProfile()` method to switch back to User 0.

5.  **UX Enhancements:**
    *   Create a simple layout for a "fake update" or "loading" screen.
    *   Display this overlay during the user switch to mask the transition, making it appear as a system glitch.
