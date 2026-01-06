package dev.borges.shadow;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.afwsamples.testdpc.R;

import dev.borges.shadow.util.PasswordHelper;

public class PasswordActivity extends FragmentActivity {
    private static final String TAG = "PasswordActivity";
    public static final String EXTRA_LAUNCH_SETTINGS_ON_SUCCESS = "launch_settings_on_success";

    private EditText etCurrentPassword, etPassword, etConfirmPassword;
    private boolean shouldLaunchSettingsOnSuccess = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password);

        // Check if we should launch SettingsActivity after successful auth (e.g., from T9 code)
        shouldLaunchSettingsOnSuccess = getIntent().getBooleanExtra(EXTRA_LAUNCH_SETTINGS_ON_SUCCESS, false);

        checkAndRequestSMSPermissions();

        // Find views
        etCurrentPassword = findViewById(R.id.etCurrentPassword);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        Button btnSubmit = findViewById(R.id.btnSubmit);
        Button btnChangePassword = findViewById(R.id.btnChangePassword);
        Button btnUseFingerprint = findViewById(R.id.btnUseFingerprint);

        // Check if a password is already set
        String encryptedPassword = PasswordHelper.retrievePasswordHash(this);

        if (encryptedPassword == null) {
            // No password set, prompt user to set a new password
            etCurrentPassword.setVisibility(View.GONE); // Hide the old password field
            btnChangePassword.setVisibility(View.GONE); // Hide the change password button
            btnUseFingerprint.setVisibility(View.GONE); // Hide fingerprint button
            btnSubmit.setText("Save Password");
            btnSubmit.setOnClickListener(v -> savePassword());
        } else {
            // Password is set, prompt user to verify password and change it
            etCurrentPassword.setVisibility(View.VISIBLE); // Show the old password field
            btnChangePassword.setVisibility(View.VISIBLE); // Show the change password button
            btnSubmit.setText("Login");
            btnSubmit.setOnClickListener(v -> verifyPassword());
            btnChangePassword.setOnClickListener(v -> changePassword());

            // Only show fingerprint on owner profile (never on decoy) and if biometric key is valid
            boolean isOwnerProfile = getSystemService(UserManager.class).isSystemUser();
            if (isOwnerProfile && PasswordHelper.isBiometricKeyValid()) {
                btnUseFingerprint.setVisibility(View.VISIBLE);
                btnUseFingerprint.setOnClickListener(v -> authenticateWithBiometrics());
            } else {
                btnUseFingerprint.setVisibility(View.GONE);
            }
        }
    }

    private void onAuthenticationSuccess() {
        if (shouldLaunchSettingsOnSuccess) {
            // Launched from T9 code - open SettingsActivity and skip re-authentication
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            settingsIntent.putExtra(SettingsActivity.EXTRA_ALREADY_AUTHENTICATED, true);
            startActivity(settingsIntent);
        }
        setResult(RESULT_OK);
        finish();
    }

    private void savePassword() {
        String password = etPassword.getText().toString();
        String confirmPassword = etConfirmPassword.getText().toString();

        if (password.isEmpty() || !password.equals(confirmPassword)) {
            Toast.makeText(this, "Passwords do not match!", Toast.LENGTH_SHORT).show();
            return;
        }

        String passwordHash = PasswordHelper.hashPassword(password);
        PasswordHelper.storePasswordHash(this, passwordHash);
        onAuthenticationSuccess();
    }

    private void verifyPassword() {
        String enteredPassword = etCurrentPassword.getText().toString();

        if (PasswordHelper.checkPassword(this, enteredPassword)) {
            Toast.makeText(this, "Access Granted", Toast.LENGTH_SHORT).show();
            onAuthenticationSuccess();
        } else {
            Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show();
        }
    }

    private void changePassword() {
        String currentPassword = etCurrentPassword.getText().toString();
        String newPassword = etPassword.getText().toString();
        String confirmPassword = etConfirmPassword.getText().toString();

        if (currentPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "All fields must be filled!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!newPassword.equals(confirmPassword)) {
            Toast.makeText(this, "New password and confirmation do not match!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (PasswordHelper.checkPassword(this, currentPassword)) {
            String passwordHash = PasswordHelper.hashPassword(newPassword);
            PasswordHelper.storePasswordHash(this, passwordHash);
            Toast.makeText(this, "Password changed successfully!", Toast.LENGTH_SHORT).show();
            onAuthenticationSuccess();
        } else {
            Toast.makeText(this, "Current password is incorrect!", Toast.LENGTH_SHORT).show();
        }
    }

    private void authenticateWithBiometrics() {
        BiometricManager biometricManager = BiometricManager.from(this);
        int canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG |
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        );

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            String message;
            switch (canAuthenticate) {
                case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE:
                    message = "No biometric hardware";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
                    message = "Biometric hardware unavailable";
                    break;
                case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                    message = "No biometrics enrolled";
                    break;
                default:
                    message = "Biometric authentication not available";
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authentication")
            .setSubtitle("Use fingerprint to access Shadow")
            .setNegativeButtonText("Cancel")
            .build();

        BiometricPrompt biometricPrompt = new BiometricPrompt(this,
            ContextCompat.getMainExecutor(this),
            new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    Toast.makeText(PasswordActivity.this, "Access Granted (Biometric)", Toast.LENGTH_SHORT).show();
                    onAuthenticationSuccess();
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    super.onAuthenticationError(errorCode, errString);
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        Toast.makeText(PasswordActivity.this, "Error: " + errString, Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    super.onAuthenticationFailed();
                    Toast.makeText(PasswordActivity.this, "Authentication failed", Toast.LENGTH_SHORT).show();
                }
            });

        biometricPrompt.authenticate(promptInfo);
    }

    private static final int SMS_PERMISSION_REQUEST_CODE = 789234;

    private void checkAndRequestSMSPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED
        || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, SMS_PERMISSION_REQUEST_CODE);
        }
    }

//    @Override
//    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
//        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
//        }
//    }
}
