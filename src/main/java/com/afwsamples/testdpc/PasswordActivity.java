package com.afwsamples.testdpc;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class PasswordActivity extends Activity {

    private static final String PREFS_NAME = "secure_prefs";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_ALIAS = "my_app_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SharedPreferences sharedPreferences;
    private EditText etPassword, etConfirmPassword;
    private Button btnSubmit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password);

        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnSubmit = findViewById(R.id.btnSubmit);

        String encryptedPassword = sharedPreferences.getString(KEY_PASSWORD, null);

        if (encryptedPassword == null) {
            etConfirmPassword.setVisibility(View.VISIBLE);
            btnSubmit.setText("Save Password");
            btnSubmit.setOnClickListener(v -> savePassword());
        } else {
            etConfirmPassword.setVisibility(View.GONE);
            btnSubmit.setText("Verify Password");
            btnSubmit.setOnClickListener(v -> verifyPassword(encryptedPassword));
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
        String enteredPassword = etPassword.getText().toString();

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

    private String decryptPassword(String encryptedPassword) throws Exception {
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
}
