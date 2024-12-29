package com.afwsamples.testdpc;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.Manifest;
import android.hardware.fingerprint.FingerprintManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class PasswordActivity extends Activity {

    public static final String PREFS_NAME = "secure_prefs";
    public static final String KEY_PASSWORD = "password";
    private static final String KEY_ALIAS = "my_app_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int REQUEST_CODE_SMS_PERMISSION = 8926318;

    private SharedPreferences sharedPreferences;
    private EditText etCurrentPassword, etPassword, etConfirmPassword;
    private Button btnSubmit, btnChangePassword, btnUseFingerprint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password);

        // Check and request SMS permissions at runtime (for Android 6.0+)
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(new String[]{
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
            }, REQUEST_CODE_SMS_PERMISSION);
        }

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Find views
        etCurrentPassword = findViewById(R.id.etCurrentPassword);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        btnUseFingerprint = findViewById(R.id.btnUseFingerprint);

        // Check if a password is already set
        String encryptedPassword = sharedPreferences.getString(KEY_PASSWORD, null);

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
            btnSubmit.setOnClickListener(v -> verifyPassword(encryptedPassword));
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

        try {
            if (!isKeyInKeystore()) {
                generateKey();
            }

            String encryptedPassword = encryptPassword(password);

            sharedPreferences.edit().putString(KEY_PASSWORD, encryptedPassword).apply();

            Toast.makeText(this, "Password saved!", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save password", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void verifyPassword(String encryptedPassword) {
        String enteredPassword = etCurrentPassword.getText().toString();

        try {
            String decryptedPassword = decryptPassword(encryptedPassword);

            if (enteredPassword.equals(decryptedPassword)) {
                Toast.makeText(this, "Access Granted", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "Incorrect Password", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to verify password", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
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

        String encryptedPassword = sharedPreferences.getString(KEY_PASSWORD, null);

        if (encryptedPassword != null) {
            try {
                String decryptedPassword = decryptPassword(encryptedPassword);

                if (!currentPassword.equals(decryptedPassword)) {
                    Toast.makeText(this, "Current password is incorrect!", Toast.LENGTH_SHORT).show();
                    return;
                }

                String newEncryptedPassword = encryptPassword(newPassword);
                sharedPreferences.edit().putString(KEY_PASSWORD, newEncryptedPassword).apply();

                Toast.makeText(this, "Password changed successfully!", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to change password", Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
        }
    }

    private boolean isKeyInKeystore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore.containsAlias(KEY_ALIAS);
    }

    private void generateKey() throws Exception {
        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        keyGenerator.init(
                new android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
        );
        keyGenerator.generateKey();
    }

    private String encryptPassword(String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] iv = cipher.getIV();
        byte[] encryptedBytes = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + encryptedBytes.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);

        return Base64.encodeToString(combined, Base64.DEFAULT);
    }

    public static String decryptPassword(String encryptedPassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey secretKey = (SecretKey) keyStore.getKey(KEY_ALIAS, null);

        byte[] combined = Base64.decode(encryptedPassword, Base64.DEFAULT);

        byte[] iv = new byte[12];
        byte[] encryptedBytes = new byte[combined.length - iv.length];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, encryptedBytes, 0, encryptedBytes.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(128, iv));

        byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
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
            Toast.makeText(this, "Fingerprint authentication failed", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_SMS_PERMISSION) {
            Toast.makeText(this, "Permission denied to read SMS", Toast.LENGTH_SHORT).show();
        }
    }
}
