package dev.borges.shadow.util;

import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

// TODO(igor): review TargetApi of all files and set minimal on build.gradle
@TargetApi(Build.VERSION_CODES.O)
public class DevicePasswordHelper {

    private static final String PREFS_NAME = "password-token";
    private static final String TOKEN_NAME = "token";

    private static byte[] loadPasswordResetTokenFromPreference(Context context) {
        Context directBootContext = context.createDeviceProtectedStorageContext();
        android.content.SharedPreferences settings = directBootContext.getSharedPreferences(PREFS_NAME, 0);
        String tokenString = settings.getString(TOKEN_NAME, null);
        if (tokenString != null) {
            return Base64.decode(tokenString, Base64.DEFAULT);
        }
        return null;
    }

    private static void savePasswordResetTokenToPreference(Context context, byte[] token) {
        Context directBootContext = context.createDeviceProtectedStorageContext();
        android.content.SharedPreferences settings = directBootContext.getSharedPreferences(PREFS_NAME, 0);
        android.content.SharedPreferences.Editor editor = settings.edit();
        if (token != null) {
            editor.putString(TOKEN_NAME, Base64.encodeToString(token, Base64.DEFAULT).trim());
        } else {
            editor.remove(TOKEN_NAME);
        }
        editor.apply();
    }

    public static void reloadTokenInformation(
            Context context,
            DevicePolicyManager devicePolicyManager,
            ComponentName adminComponentName,
            TokenReloadedCallback onTokenReloaded
    ) {
        byte[] token = loadPasswordResetTokenFromPreference(context);
        String tokenString = token != null ? Base64.encodeToString(token, Base64.DEFAULT).trim() : "None";
        boolean active = devicePolicyManager.isResetPasswordTokenActive(adminComponentName);
        String tokenStatus = active ? "Active" : "Inactive";
        onTokenReloaded.onTokenReloaded(tokenString, tokenStatus);
    }

    public static void createNewPasswordToken(
            Context context,
            DevicePolicyManager devicePolicyManager,
            ComponentName adminComponentName,
            TokenReloadedCallback onTokenReloaded
    ) {
        byte[] token = generateRandomPasswordToken();
        if (!devicePolicyManager.setResetPasswordToken(adminComponentName, token)) {
            Toast.makeText(context, "Set password reset token failed", Toast.LENGTH_SHORT).show();
            return;
        }
        savePasswordResetTokenToPreference(context, token);
        reloadTokenInformation(context, devicePolicyManager, adminComponentName, onTokenReloaded);
    }

    public static void removePasswordToken(
            Context context,
            DevicePolicyManager devicePolicyManager,
            ComponentName adminComponentName,
            TokenReloadedCallback onTokenReloaded
    ) {
        if (!devicePolicyManager.clearResetPasswordToken(adminComponentName)) {
            Toast.makeText(context, "Clear password reset token failed", Toast.LENGTH_SHORT).show();
            return;
        }
        savePasswordResetTokenToPreference(context, null);
        reloadTokenInformation(context, devicePolicyManager, adminComponentName, onTokenReloaded);
    }

    public static void activatePasswordToken(
            KeyguardManager keyguardManager,
            ActivityResultLauncher<Intent> confirmCredentialLauncher
    ) {
        Intent intent = keyguardManager.createConfirmDeviceCredentialIntent(null, null);
        if (intent != null) {
            confirmCredentialLauncher.launch(intent);
        }
    }

    public static void resetPasswordWithToken(
            Context context,
            DevicePolicyManager devicePolicyManager,
            ComponentName adminComponentName,
            String tokenString,
            String password,
            boolean requireEntry,
            boolean doNotRequirePasswordOnBoot,
            boolean doNotAllowOtherAdminsChange
    ) {
        byte[] token;
        try {
            token = Base64.decode(tokenString, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            token = tokenString.getBytes(StandardCharsets.UTF_8);
        }

        int flags = 0;
        flags |= requireEntry ? DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY : 0;
        flags |= doNotRequirePasswordOnBoot ? DevicePolicyManager.RESET_PASSWORD_DO_NOT_ASK_CREDENTIALS_ON_BOOT : 0;
        flags |= doNotAllowOtherAdminsChange ? DevicePolicyManager.RESET_PASSWORD_REQUIRE_ENTRY : 0;

        if (token != null) {
            boolean result = devicePolicyManager.resetPasswordWithToken(
                    adminComponentName,
                    password,
                    token,
                    flags
            );
            if (result) {
                Toast.makeText(
                        context,
                        "Reset password with token succeed: " + password,
                        Toast.LENGTH_SHORT
                ).show();
            } else {
                Toast.makeText(context, "Reset password with token failed", Toast.LENGTH_SHORT)
                        .show();
            }
        } else {
            Toast.makeText(context, "Reset password no token", Toast.LENGTH_SHORT).show();
        }
    }

    private static byte[] generateRandomPasswordToken() {
        try {
            SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
            byte[] seed = secureRandom.generateSeed(32);
            return seed;
        } catch (NoSuchAlgorithmException e) {
            Log.e("PasswordManager", "Error generating random token", e);
            return new byte[0];
        }
    }

    public interface TokenReloadedCallback {
        void onTokenReloaded(String token, String status);
    }
}
