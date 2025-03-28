package dev.borges.shadow;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.hardware.fingerprint.FingerprintManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.afwsamples.testdpc.R;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

import dev.borges.shadow.util.PasswordHelper;

public class PasswordActivity extends Activity {
    private static final String TAG = "PasswordActivity";

    private static final String KEY_ALIAS = "my_app_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private EditText etCurrentPassword, etPassword, etConfirmPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password);

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
            btnSubmit.setText("Save Password");
            btnSubmit.setOnClickListener(v -> savePassword());
        } else {
            // Password is set, prompt user to verify password and change it
            etCurrentPassword.setVisibility(View.VISIBLE); // Show the old password field
            btnChangePassword.setVisibility(View.VISIBLE); // Show the change password button
            btnUseFingerprint.setVisibility(View.VISIBLE);
            btnUseFingerprint.setOnClickListener(v -> authenticateWithBiometrics());
            btnSubmit.setText("Login");
            btnSubmit.setOnClickListener(v -> verifyPassword());
            btnChangePassword.setOnClickListener(v -> changePassword());
        }
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
        setResult(RESULT_OK);
        finish();
    }

    private void verifyPassword() {
        String enteredPassword = etCurrentPassword.getText().toString();

        if (PasswordHelper.checkPassword(this, enteredPassword)) {
            Toast.makeText(this, "Access Granted", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
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
            setResult(RESULT_OK);
            finish();
        } else {
            Toast.makeText(this, "Current password is incorrect!", Toast.LENGTH_SHORT).show();
        }
    }

    private Cipher initCipher() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            return cipher;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize cipher", e);
            e.printStackTrace();
            return null;
        }
    }

    private void authenticateWithBiometrics() {
        try {
            FingerprintManager fingerprintManager = (FingerprintManager) getSystemService(FINGERPRINT_SERVICE);

            if (!fingerprintManager.isHardwareDetected()) {
                Toast.makeText(this, "Fingerprint hardware not detected", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!fingerprintManager.hasEnrolledFingerprints()) {
                Toast.makeText(this, "No fingerprints enrolled", Toast.LENGTH_SHORT).show();
                return;
            }

            Cipher cipher = initCipher();
            if (cipher == null) {
                Toast.makeText(this, "Failed to initialize cipher", Toast.LENGTH_SHORT).show();
                return;
            }

            FingerprintManager.CryptoObject cryptoObject = new FingerprintManager.CryptoObject(cipher);

            fingerprintManager.authenticate(cryptoObject, null, 0, new FingerprintManager.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(FingerprintManager.AuthenticationResult result) {
                    runOnUiThread(() -> {
                        Toast.makeText(PasswordActivity.this, "Access Granted (Fingerprint)", Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    });
                }

                @Override
                public void onAuthenticationFailed() {
                    runOnUiThread(() -> Toast.makeText(PasswordActivity.this, "Authentication failed", Toast.LENGTH_SHORT).show());
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "Fingerprint authentication failed", e);
            Toast.makeText(this, "Fingerprint authentication failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
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
